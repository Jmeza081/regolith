package com.regolith.desktop

import com.regolith.data.media.SidecarWriter
import com.regolith.data.smb.JcifsGateway
import com.regolith.domain.smb.SmbGateway

/**
 * The app's long-lived objects, built once by hand.
 *
 * The phone uses Hilt for this. Here there are a handful of objects and one
 * window, so a plain class is the whole DI container: tests build their own
 * graph with fakes by passing them in. Web analogy: the object you hand to a
 * React context provider at the root.
 */
class AppGraph(
    /** Every network call goes through the phone's own SMB client. */
    val gateway: SmbGateway = JcifsGateway(),
) {
    /** Reads and writes `<basename>.chapters.txt`, exactly as the phone does. */
    val sidecars: SidecarWriter = SidecarWriter(gateway)
}
