package com.regolith.data.artwork

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.regolith.R
import com.regolith.data.RegolithNotifications
import com.regolith.data.repository.LibraryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes every video's chapter wall on one share ahead of anyone opening it:
 * the pictures the player's chapter sheet shows, one per chapter
 * ([ArtworkRepository.prefetchChapterWall]).
 *
 * The sheet waits until every one of its pictures is in. On a video played
 * before they are on disk already, but the first time it meant a wait while
 * they were made off the share. This pays that wait in the background
 * instead, so even a video never opened has its wall ready.
 *
 * Its own job rather than one more pass of [ArtworkWorker], because it wants
 * a different rule about when to run. A wall is a dozen key-frame reads per
 * video, tens of megabytes for a big film and gigabytes for a library, so it
 * runs on Wi-Fi only ([ArtworkPrefetcher.enqueueWalls]). WorkManager holds it
 * until then by itself, and the poster pass is not held up waiting. It is
 * enqueued when a share's artwork walk finishes, which is also when the rows
 * have their runtimes.
 *
 * Newest first, as Home lists them. A video whose wall is already made costs
 * a few cached-row lookups, so a walk after every scan is cheap.
 */
@HiltWorker
class ChapterWallWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val library: LibraryRepository,
    private val artwork: ArtworkRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shareId = inputData.getLong(KEY_SHARE_ID, -1)
        if (shareId < 0) return Result.failure()
        val films = filmsOf(shareId)
        if (films.isEmpty()) return Result.success()

        runCatching { setForeground(foregroundInfo(0, films.size)) }
            .onFailure { Log.w(TAG, "no foreground: ${it.message}") }

        var done = 0
        return try {
            for (fileId in films) {
                if (isStopped) return Result.success()
                // The same backstop as the artwork walk's, for the same wedge.
                val ok = withTimeoutOrNull(STUCK_MS) { artwork.prefetchChapterWall(fileId) }
                if (ok == null) {
                    Log.e(TAG, "STUCK on the chapter wall of file $fileId after $done of ${films.size}")
                    return Result.failure()
                }
                if (!ok) {
                    Log.i(TAG, "share $shareId went quiet after $done of ${films.size} chapter walls; will resume")
                    return Result.retry()
                }
                done++
                report(done, films.size)
            }
            // A scan that added films while this ran re-enqueues the artwork
            // walk, which enqueues this again, but KEEP drops that while this
            // is still running. Asking again closes it, as ArtworkWorker does.
            if (filmsOf(shareId).size > films.size) return Result.retry()
            Log.i(TAG, "share $shareId: chapter walls ready for ${films.size} videos")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "chapter walls of share $shareId crashed", e)
            Result.failure()
        }
    }

    private suspend fun filmsOf(shareId: Long): List<Long> =
        library.observeFilesInShares(listOf(shareId)).first()
            .sortedByDescending { it.addedAtMs }
            .map { it.id }

    private suspend fun report(done: Int, total: Int) {
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        runCatching { setForeground(foregroundInfo(done, total)) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RegolithNotifications.CHANNEL_ARTWORK, "Artwork", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, RegolithNotifications.CHANNEL_ARTWORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Making chapter pictures")
            .setContentText(if (total > 0) "$done of $total videos" else "Working out what needs a picture")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(RegolithNotifications.CHAPTER_WALLS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_SHARE_ID = "shareId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val TAG = "Regolith/Artwork"

        /** Above the repository's own 90 s, as in [ArtworkWorker]. */
        private const val STUCK_MS = 120_000L
    }
}
