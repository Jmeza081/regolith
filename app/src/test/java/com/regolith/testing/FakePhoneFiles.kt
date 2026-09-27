package com.regolith.testing

import com.regolith.data.transfer.PhoneFiles
import com.regolith.data.transfer.SourceChanged
import com.regolith.data.transfer.SourceGone
import com.regolith.domain.transfer.PickedFile
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The phone's files for JVM tests: address -> bytes, plus the read grants
 * the app holds. A file missing from [files] is one the user deleted from
 * the gallery; [onRead] lets a test act part-way through a read — cancel
 * the upload, say — the way a user would while the bytes are going.
 */
class FakePhoneFiles : PhoneFiles {
    val files = mutableMapOf<String, ByteArray>()
    val names = mutableMapOf<String, String>()
    val dates = mutableMapOf<String, Long>()

    /** Addresses whose grant the app has made to outlive it. */
    val held = mutableSetOf<String>()

    /** Addresses the provider refuses to make persistent (some document providers). */
    val refusesToKeep = mutableSetOf<String>()

    /** Called once the reading has passed [onReadAfter] bytes of a file, with its address. */
    var onRead: (uri: String) -> Unit = {}
    var onReadAfter: Long = Long.MAX_VALUE

    /** How many bytes were read, in all, from every file: a resume should read only the rest. */
    var bytesRead = 0L

    fun add(uri: String, name: String, bytes: ByteArray, modifiedAtMs: Long? = null) {
        files[uri] = bytes
        names[uri] = name
        modifiedAtMs?.let { dates[uri] = it }
    }

    override fun describe(uri: String): PickedFile? {
        val bytes = files[uri] ?: return null
        return PickedFile(uri, names.getValue(uri), bytes.size.toLong(), dates[uri])
    }

    override fun open(uri: String, offset: Long): InputStream {
        val bytes = files[uri] ?: throw SourceGone(uri)
        if (offset > bytes.size) throw SourceChanged(uri)
        return object : ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) {
            private var read = 0L

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    val before = read
                    read += n
                    bytesRead += n
                    if (before < onReadAfter && read >= onReadAfter) onRead(uri)
                }
                return n
            }
        }
    }

    override fun keepAccess(uri: String): Boolean {
        if (uri in refusesToKeep) return false
        held += uri
        return true
    }

    override fun releaseAccess(uri: String) {
        held -= uri
    }

    override fun heldAccess(): List<String> = held.toList()
}
