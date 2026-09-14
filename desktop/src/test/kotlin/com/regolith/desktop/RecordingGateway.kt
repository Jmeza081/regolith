package com.regolith.desktop

import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway

/**
 * The fake share, recording every path opened. The real client (jcifs)
 * creates a file when asked to open one that is missing, which the fake
 * cannot imitate; tests use this to prove nothing is opened blind.
 */
class RecordingGateway(private val inner: FakeSmbGateway) : SmbGateway by inner {
    val opened = mutableListOf<String>()

    override fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): SeekableByteSource {
        opened += relPath
        return inner.open(host, credentials, share, relPath)
    }
}
