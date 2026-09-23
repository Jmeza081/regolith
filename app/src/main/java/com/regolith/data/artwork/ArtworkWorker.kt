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
import com.regolith.data.db.UserChapterDao
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.artwork.ArtworkOwner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    private val chapters: UserChapterDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shareId = inputData.getLong(KEY_SHARE_ID, -1)
        if (shareId < 0) return Result.failure()

        val owners = ownersOf(shareId)
        if (owners.isEmpty()) return Result.success()

        runCatching { setForeground(foregroundInfo(0, owners.size)) }
            .onFailure { Log.w(TAG, "no foreground: ${it.message}") }

        var done = 0
        return try {
            for (owner in owners) {
                if (isStopped) return Result.success()
                // The repository time-boxes each owner itself and hands back
                // true when it gives up, so reaching this timeout means the
                // one case it cannot escape: a native decoder or GL context
                // that never returns, with no suspension point to cancel at.
                // The permits are still held by that job, so the next item
                // would wait on them for ever — which is exactly the freeze
                // this is here to refuse.
                val ok = withTimeoutOrNull(STUCK_MS) { artwork.prefetch(owner) }
                if (ok == null) {
                    // Deliberately NOT a retry. The wedged job still holds the
                    // repository's permits, so another pass in this process
                    // would stall on the very first item — a pass that restarts
                    // for ever is worse than one that stops and says why.
                    logStuckThreads(owner, done, owners.size)
                    return Result.failure()
                }
                // False means the share stopped answering. Everything already
                // written stays written, and WorkManager brings us back.
                if (!ok) {
                    Log.i(TAG, "share $shareId went quiet after $done of ${owners.size}; will resume")
                    return Result.retry()
                }
                done++
                report(done, owners.size)
            }
            // The list was a SNAPSHOT taken before the first frame was
            // grabbed, and a walk of a real library outlives the scan that
            // started it — so by now the scan has very likely written rows
            // this pass never knew about. The scan does re-enqueue us when it
            // finishes, but `KEEP` drops that while this job is still
            // running, and the pass would end reporting "900 of 900" over a
            // library of three thousand. Asking again is what closes it; it
            // is the same re-check `TransferQueueWorker` ends on, for the
            // same reason.
            val now = ownersOf(shareId)
            if (now.size > owners.size) {
                Log.i(TAG, "share $shareId: ${now.size - owners.size} more arrived while walking; going round again")
                return Result.retry()
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

    /**
     * Everything on [shareId] that could want a picture.
     *
     * Folders first: they are far fewer, and they are the wall the Library
     * opens on. Then the named marks — the points of interest Search shows,
     * each drawn with the frame at its own time. They are a hundred or so on
     * a real library and come out several to an open, so they cost minutes,
     * where leaving them behind a first walk of thousands of films would
     * leave Search grabbing them live for an hour. Then the files, newest
     * first, because that is the order Home shows them in — so the screens
     * someone is likeliest to open next fill in before the deep back
     * catalogue.
     */
    private suspend fun ownersOf(shareId: Long): List<ArtworkOwner> {
        val folders = library.observeFoldersInShares(listOf(shareId)).first()
            .filter { it.parentId != null }
            .map { ArtworkOwner.Folder(it.id) }
        val files = library.observeFilesInShares(listOf(shareId)).first()
            .sortedByDescending { it.addedAtMs }
            .map { ArtworkOwner.File(it.id) }
        val marks = chapters.namedInShare(shareId).map { ArtworkOwner.Moment(it.fileId, it.startMs) }
        return folders + marks + files
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

    /**
     * What the walk was doing when it stopped answering.
     *
     * The stall this exists for is reproducible and hardware-specific — it
     * lands on the same item every time on a real device and never on the
     * emulator, which decodes in software — so the stack is the only thing
     * that can say whether it is wedged in the decoder, in GL, or on the
     * share. Logged at ERROR so it survives a filtered logcat.
     */
    private fun logStuckThreads(owner: ArtworkOwner, done: Int, total: Int) {
        Log.e(TAG, "STUCK on $owner after $done of $total items; thread dump follows")
        Thread.getAllStackTraces()
            .filterKeys { t -> INTERESTING.any { t.name.contains(it, ignoreCase = true) } }
            .forEach { (thread, stack) ->
                Log.e(TAG, "  thread '${thread.name}' ${thread.state}")
                stack.take(STACK_DEPTH).forEach { Log.e(TAG, "      at $it") }
            }
    }

    companion object {
        const val KEY_SHARE_ID = "shareId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val TAG = "Regolith/Artwork"

        /**
         * The backstop, above the repository's own 90 s. Only a wedge the
         * repository could not cancel ever reaches it.
         */
        private const val STUCK_MS = 120_000L

        /** Thread names worth printing: the decoder, GL, the loader, the coroutine pools. */
        private val INTERESTING = listOf("Dispatcher", "ExoPlayer", "Codec", "Loader", "GL", "media", "jcifs", "Thread-")
        private const val STACK_DEPTH = 18
    }
}
