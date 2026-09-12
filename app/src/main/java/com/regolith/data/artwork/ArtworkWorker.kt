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
import com.regolith.domain.artwork.ArtworkOwner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Generates the artwork for one share ahead of anyone looking at it.
 *
 * Artwork used to be made only when a tile scrolled into view, and each one
 * is a key-frame seek over SMB costing one to three seconds. Opening the
 * library therefore meant a wall of placeholders filling in slowly, and
 * opening a title meant waiting again for its backdrop. This does that work
 * after a scan, when nobody is waiting, and it does it ONCE per file:
 * [ArtworkRepository.prefetch] writes the poster, the thumbnail and the
 * backdrop from a single grab.
 *
 * It is deliberately unhurried. One extraction at a time (the repository
 * keeps a slot free for whatever is on screen), and it yields the moment
 * the share stops answering rather than grinding through a thousand files
 * that will all fail.
 *
 * Web analogy: a warm-the-cache job on a queue, with a progress row the UI
 * can subscribe to.
 */
@HiltWorker
class ArtworkWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val library: LibraryRepository,
    private val artwork: ArtworkRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shareId = inputData.getLong(KEY_SHARE_ID, -1)
        if (shareId < 0) return Result.failure()

        // Folders first: they are far fewer, and they are the wall the
        // Library opens on. Then the files, newest first, because that is
        // the order Home shows them in — so the two screens someone is
        // likeliest to open next fill in before the deep back catalogue.
        val folders = library.observeFoldersInShares(listOf(shareId)).first()
            .filter { it.parentId != null }
            .map { ArtworkOwner.Folder(it.id) }
        val files = library.observeFilesInShares(listOf(shareId)).first()
            .sortedByDescending { it.addedAtMs }
            .map { ArtworkOwner.File(it.id) }
        val owners = folders + files
        if (owners.isEmpty()) return Result.success()

        runCatching { setForeground(foregroundInfo(0, owners.size)) }
            .onFailure { Log.w(TAG, "no foreground: ${it.message}") }

        var done = 0
        return try {
            for (owner in owners) {
                if (isStopped) return Result.success()
                // False means the share stopped answering. Everything already
                // written stays written, and WorkManager brings us back.
                if (!artwork.prefetch(owner)) {
                    Log.i(TAG, "share $shareId went quiet after $done of ${owners.size}; will resume")
                    return Result.retry()
                }
                done++
                report(done, owners.size)
            }
            Log.i(TAG, "share $shareId: artwork ready for ${owners.size} items")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "artwork walk of share $shareId crashed", e)
            Result.failure()
        }
    }

    private suspend fun report(done: Int, total: Int) {
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        // Each item takes a second or more, so one notification per item is
        // already a slow enough drumbeat to leave alone.
        runCatching { setForeground(foregroundInfo(done, total)) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RegolithNotifications.CHANNEL_ARTWORK, "Artwork", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, RegolithNotifications.CHANNEL_ARTWORK)
            .setSmallIcon(R.drawable.ic_splash_wordmark)
            .setContentTitle("Preparing artwork")
            .setContentText(if (total > 0) "$done of $total" else "Working out what needs a picture")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // A walk of a large library runs for a while. Whoever is looking
            // at the notification is the person best placed to say "not now".
            .addAction(0, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(RegolithNotifications.ARTWORK_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_SHARE_ID = "shareId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val TAG = "Regolith/Artwork"
    }
}
