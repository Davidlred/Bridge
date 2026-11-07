package app.thebridge.data.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** Who to call: a name to find in the phone's contacts, or a number as typed. */
sealed interface CallTarget {
    data class Name(val name: String) : CallTarget
    data class Number(val number: String) : CallTarget
}

data class PhoneContact(val name: String, val number: String, val label: String? = null)

/**
 * Something the coach did on the phone, stored with its reply (JSON in the message's `action`).
 * type "reminder": [at] is local wall time "2026-11-07T17:00", [text] what to say, [call] who to call then (or null).
 * type "call": [name] as the user said it. Numbers are never stored here: the phone looks them up when needed.
 */
@Serializable
data class CoachActionDto(val type: String, val at: String? = null, val text: String? = null, val call: String? = null, val name: String? = null)

object ArcCommand {
    private val NOT_NOW = setOf("at", "in", "tomorrow", "tonight", "later", "after", "before", "when", "on", "by", "every", "today", "morning", "afternoon", "evening", "o'clock")
    private val NOT_A_NAME = setOf("it", "this", "that", "them", "him", "her", "me", "us", "you", "back", "off", "out", "up", "a", "an", "the", "someone", "somebody", "anyone")

    /**
     * "call Sharon", "Arc, ring my mum", "hey arc call Dr Bello please", "dial 0803 000 0001" → who to call now.
     * Anything else (a time, "call it a day", a longer request) is left for the coach, so it reaches the chat as usual.
     */
    fun parseCall(text: String, coachName: String = "Arc"): CallTarget? {
        val names = listOf(coachName, "Arc").filter { it.isNotBlank() }.distinct().joinToString("|") { Regex.escape(it) }
        val re = Regex("""^(?:(?:hey|hi|ok|okay)[,\s]+)?(?:(?:$names)[,:\s]+)?(?:(?:please|can you|could you|would you)[,\s]+)?(?:call|ring|phone|dial)\s+(.+?)(?:[,\s]+(?:for me|now|right now|please))*$""", RegexOption.IGNORE_CASE)
        val m = re.matchEntire(text.trim().trimEnd('.', '!', '?').trim()) ?: return null
        val target = m.groupValues[1].trim()
        if (target.all { it.isDigit() || it in "+-() " } && target.count(Char::isDigit) >= 3) return CallTarget.Number(target.filter { it.isDigit() || it == '+' })
        val words = target.split(Regex("""\s+"""))
        if (words.size > 4 || words.any { it.lowercase() in NOT_NOW } || words.first().lowercase() in NOT_A_NAME) return null
        return CallTarget.Name(target)
    }

    /** Lower case, no accents or punctuation, single spaces: "Dr. Bélló" → "dr bello". */
    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        .lowercase().replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim().replace(Regex("""\s+"""), " ")

    /**
     * The best matches for [query] ("my mum" also finds "Mum"): the exact name, then the first name, then any whole word,
     * then a name that starts with it. Only the best tier comes back; the same number saved twice counts once.
     */
    fun rank(query: String, contacts: List<PhoneContact>): List<PhoneContact> {
        val q = norm(query).removePrefix("my ").trim()
        if (q.isEmpty()) return emptyList()
        fun score(c: PhoneContact): Int {
            val n = norm(c.name); val words = n.split(' ')
            return when {
                n == q -> 4
                words.first() == q -> 3
                " $n ".contains(" $q ") -> 2
                n.startsWith(q) || words.any { it.startsWith(q) } -> 1
                else -> 0
            }
        }
        val scored = contacts.map { it to score(it) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }.distinctBy { it.number.filter(Char::isDigit).takeLast(9) }
    }
}

/** The phone's contacts, read only after the user allows it. Nothing read here leaves the device. */
@Singleton
class PhoneContacts @Inject constructor(@ApplicationContext private val ctx: Context) {
    fun canRead() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canCall() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    suspend fun find(name: String): List<PhoneContact> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val first = ArcCommand.norm(name).removePrefix("my ").substringBefore(' ')
        if (first.isEmpty()) return@withContext emptyList()
        val found = mutableListOf<PhoneContact>()
        runCatching {
            ctx.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$first%"), "${Phone.IS_SUPER_PRIMARY} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue; val num = c.getString(1) ?: continue
                    val label = Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString()
                    found += PhoneContact(n, num, label)
                }
            }
        }
        ArcCommand.rank(name, found)
    }

    /** Rings [number] straight away when the user allowed calls; otherwise opens the dialer with it filled in. */
    fun call(number: String): Boolean {
        val intent = Intent(if (canCall()) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }
}

/** A call Arc was asked to make, from the chat, a reminder's "Call" button or the coach. The call sheet picks it up. */
@Singleton
class ArcCalls @Inject constructor() {
    private val _request = MutableStateFlow<CallTarget?>(null)
    val request: StateFlow<CallTarget?> = _request.asStateFlow()
    fun call(target: CallTarget) { _request.value = target }
    fun done() { _request.value = null }
}
