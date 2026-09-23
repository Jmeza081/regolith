package com.regolith.player

import com.regolith.data.demo.DemoStore
import com.regolith.data.repository.PhoneLibrary
import com.regolith.data.transfer.TransferRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Is there a copy of this file on the device, and where?"
 *
 * Three things can answer yes: a finished download ("Keep on this
 * device"), a video the phone already had ([PhoneLibrary]), and the demo
 * library. Everything that opens a file — the player, the
 * artwork pipeline, the scrub previews, the container probe — asks here
 * first and falls back to the share, so none of them needs to know which
 * of the three it got, or that a demo library exists at all.
 *
 * Web analogy: a cache lookup in front of the network fetch.
 */
@Singleton
class LocalMedia @Inject constructor(
    private val demo: DemoStore,
    private val transfers: TransferRepository,
    private val phone: PhoneLibrary,
) {
    /** For coroutines. Null when the file only exists on the share. */
    suspend fun file(fileId: Long): File? = demo.fileFor(fileId) ?: transfers.localFile(fileId) ?: phone.file(fileId)

    /** For ExoPlayer's loader and the frame decoder, which are already on their own threads. */
    fun fileBlocking(fileId: Long): File? = demo.fileFor(fileId) ?: transfers.localFileBlocking(fileId) ?: phone.fileBlocking(fileId)
}
