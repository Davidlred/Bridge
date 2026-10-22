package app.thebridge.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.GoalImageEntity
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.Feasibility
import app.thebridge.data.remote.GoalImageDto
import app.thebridge.data.remote.PlanDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** Everything that needs the AI or the user's photo. Online only; callers handle OfflineException. */
@Singleton
class AiRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: ApiClient,
    private val db: BridgeDb,
) {
    suspend fun plan(goal: String, deadline: String, today: String, routine: String, existingGoals: Int): PlanDto =
        api.plan(goal, deadline, today, routine, existingGoals)

    suspend fun reality(goal: String, deadline: String, today: String, routine: String, tasks: List<String>): Feasibility =
        api.reality(goal, deadline, today, routine, tasks)

    suspend fun nextSteps(goal: String, deadline: String, today: String, routine: String, habits: List<String>, done: List<String>, open: List<String>) =
        api.nextSteps(goal, deadline, today, routine, habits, done, open)

    /** Starts the missing (or failed) images for a goal and stores their state locally. */
    suspend fun startImages(goalId: String, goal: String, routine: String, kinds: List<String> = listOf("future", "reality")): List<GoalImageDto> =
        api.startImages(goalId, goal, routine, kinds).also { save(goalId, it) }

    /** Polls until no image is still running (or [timeoutMs] passes). Each poll is saved, so screens update as images land. */
    suspend fun awaitImages(goalId: String, timeoutMs: Long = 180_000, onUpdate: (List<GoalImageDto>) -> Unit = {}): List<GoalImageDto> {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val now = api.images(goalId).also { save(goalId, it); onUpdate(it) }
            if (now.none { it.status == "running" } || System.currentTimeMillis() > until) return now
            delay(2_500)
        }
    }

    /** Signed links expire after an hour; refresh them when a goal screen opens. */
    suspend fun refreshImages(goalId: String) = runCatching { save(goalId, api.images(goalId)) }

    private suspend fun save(goalId: String, images: List<GoalImageDto>) {
        db.goals().upsertImages(images.filter { it.status != "none" }.map { GoalImageEntity(goalId, it.kind, it.status, it.url, it.withFace) })
    }

    /** Downscales to at most 1024px (keeps the upload small and strips metadata such as location), then uploads. */
    suspend fun uploadPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) decoder.setTargetSampleSize(longest / MAX_SIDE)   // rough first pass; scaled exactly below
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val scale = MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val jpeg = ByteArrayOutputStream().use { sized.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
        api.uploadPhoto(jpeg)
    }

    suspend fun deletePhoto() = api.deletePhoto()

    private companion object { const val MAX_SIDE = 1024 }
}
