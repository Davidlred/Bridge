package app.thebridge.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Tracks what the field shows versus what came back from the ViewModel. Pure, so it is unit-tested (LiveTextTest).
 *
 * Screens hold their text in a ViewModel StateFlow built with `combine`, which reaches the screen a frame or two after each
 * keystroke. Feeding that straight back into a text field resets the keyboard to slightly old text, and fast typing loses
 * letters (seen on the emulator: "from memory" became "from memor"). So the field keeps its own text, treats values that
 * echo its own edits as already applied, and only takes values that really changed elsewhere (an example filled in, a sent
 * message clearing the box, a restored draft).
 */
class LiveText(initial: String, private val maxLength: Int = Int.MAX_VALUE) {
    // Cursor at the start, as BasicTextField does: a long single-line title shows its beginning until tapped.
    var field = TextFieldValue(initial); private set
    private var seen = initial
    private val pending = ArrayDeque<String>()

    /** A value from the ViewModel. Returns true when it replaced what the field shows. */
    fun incoming(value: String): Boolean {
        if (value == seen) return false                         // nothing new; maybe still behind our typing
        seen = value
        val i = pending.indexOf(value)
        if (i >= 0) { repeat(i + 1) { pending.removeFirst() }; return false }   // our own edit, arriving late
        if (value == field.text) { pending.clear(); return false }
        field = TextFieldValue(value, TextRange(value.length)); pending.clear()
        return true
    }

    /** The user edited the field. Returns the text to report, or null when only the cursor moved (or it went over the limit). */
    fun typed(v: TextFieldValue): String? {
        if (v.text.length > maxLength && v.text.length > field.text.length) return null
        val changed = v.text != field.text
        field = v
        if (!changed) return null
        pending.addLast(v.text)
        if (pending.size > 64) pending.removeFirst()
        return v.text
    }
}

/** [BasicTextField] for text that round-trips through a ViewModel. Same parameters; [maxLength] stops input at a cap. */
@Composable
fun LiveTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None, interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(Color.Black), maxLength: Int = Int.MAX_VALUE,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
) {
    val live = remember { LiveText(value, maxLength) }
    var edits by remember { mutableIntStateOf(0) }
    live.incoming(value)
    @Suppress("UNUSED_EXPRESSION") edits                        // typing recomposes through this counter
    BasicTextField(
        live.field, { v -> live.typed(v)?.let(onValueChange); edits++ }, modifier, enabled, readOnly, textStyle, keyboardOptions, keyboardActions,
        singleLine, maxLines, minLines, visualTransformation, interactionSource = interactionSource, cursorBrush = cursorBrush, decorationBox = decorationBox,
    )
}
