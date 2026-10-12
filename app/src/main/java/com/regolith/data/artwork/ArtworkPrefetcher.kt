package com.regolith.data.artwork

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the artwork walk (or the chapter-picture pass, [ArtworkPrefetcher.observeWalls]) is doing right now, summed over every share. */
data class PrefetchStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Starts and stops the background artwork walk ([ArtworkWorker]), and
 * reports what it is doing.
 *
 * One unique job per share, so a rescan while a walk is already running
 * does not start a second one over the same files. Progress rides on
 * WorkManager's own progress data rather than a table of our own: unlike a
 * scan there is nothing here worth surviving the process, since the walk
 * re-derives what is left to do from what is already cached.
 */
@Singleton
class ArtworkPrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(shareId: Long) {
        val request = OneTimeWorkRequestBuilder<ArtworkWorker>()
            .setInputData(workDataOf(ArtworkWorker.KEY_SHARE_ID to shareId))
            // Every frame grab is a read off the share, so there is nothing to
            // do without a network. Without this the walk woke on no network,
            // failed on its first file, asked for a retry, and flickered its
            // notification away again once per backoff.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Expedited for the same reason the scan and the download queue
            // are, and this is the one that was actually missing: an ORDINARY
            // worker calling setForeground() while the app is in the background
            // is REFUSED on Android 12+ (ForegroundServiceStartNotAllowedException).
            // ArtworkWorker swallows that failure and carries on, so the walk
            // kept running with no notification behind it — which from the
            // outside is indistinguishable from a job that had stopped. The
            // fallback matters: expedited quota is finite, and running as
            // ordinary work is better than not running at all.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        // KEEP, not REPLACE: a walk already in flight has files behind it and
        // restarting would re-check every one of them.
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(shareId), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Every video's chapter pictures on [shareId] ([ChapterWallWorker]),
     * queued by the share's artwork walk when it finishes.
     *
     * Wi-Fi only (UNMETERED), where the artwork walk takes any network. A
     * wall is a dozen key-frame reads per video, which over a library is
     * gigabytes, and the share is reached on mobile data only through a VPN.
     * WorkManager holds the job until Wi-Fi comes back, by itself.
     *
     * Expedited for the artwork walk's reason (a foreground notification
     * from the background), which also rules out "only while charging":
     * expedited work takes network and storage constraints only.
     */
    fun enqueueWalls(shareId: Long) {
        val request = OneTimeWorkRequestBuilder<ChapterWallWorker>()
            .setInputData(workDataOf(ChapterWallWorker.KEY_SHARE_ID to shareId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WALLS_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(wallsName(shareId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(shareId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(shareId))
        WorkManager.getInstance(context).cancelUniqueWork(wallsName(shareId))
    }

    /** Stop: the artwork walk and the chapter-picture pass both. */
    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        WorkManager.getInstance(context).cancelAllWorkByTag(WALLS_TAG)
    }

    /** Live progress across every share's walk. */
    fun observe(): Flow<PrefetchStatus> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            val live = infos.filter { it.state == WorkInfo.State.RUNNING }
            PrefetchStatus(
                running = infos.any { !it.state.isFinished },
                done = live.sumOf { it.progress.getInt(ArtworkWorker.KEY_DONE, 0) },
                total = live.sumOf { it.progress.getInt(ArtworkWorker.KEY_TOTAL, 0) },
            )
        }

    /**
     * Live progress of the chapter-picture pass, kept apart from [observe] so
     * Shorts' "files checked" and the poster walk's own count stay theirs.
     *
     * Running means RUNNING. A pass waiting for Wi-Fi is enqueued but not
     * running, and counting it would leave "Making chapter pictures" up for as
     * long as the phone is on mobile data.
     */
    fun observeWalls(): Flow<PrefetchStatus> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(WALLS_TAG).map { infos ->
            val live = infos.filter { it.state == WorkInfo.State.RUNNING }
            PrefetchStatus(
                running = live.isNotEmpty(),
                done = live.sumOf { it.progress.getInt(ChapterWallWorker.KEY_DONE, 0) },
                total = live.sumOf { it.progress.getInt(ChapterWallWorker.KEY_TOTAL, 0) },
            )
        }

    private fun uniqueName(shareId: Long) = "artwork-share-$shareId"

    private fun wallsName(shareId: Long) = "chapter-walls-share-$shareId"

    private companion object {
        const val TAG = "regolith-artwork"
        const val WALLS_TAG = "regolith-chapter-walls"
    }
}
