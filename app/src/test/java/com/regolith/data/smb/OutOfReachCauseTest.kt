package com.regolith.data.smb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** [outOfReachCause]: the sheet names the real reason, however deep jcifs buries it. */
class OutOfReachCauseTest {
    @Test fun `a name that would not resolve says so, under jcifs's wrapping`() =
        assertEquals("Couldn't find it by name", outOfReachCause(IOException("Failed to connect to server", UnknownHostException("mac.local"))))

    @Test fun `each kind of failure has its words`() {
        assertEquals("No route to it from this network", outOfReachCause(NoRouteToHostException()))
        assertEquals("It refused the connection", outOfReachCause(ConnectException()))
        assertEquals("It stopped answering", outOfReachCause(SocketTimeoutException()))
        assertEquals("The connection dropped", outOfReachCause(IOException("reset")))
    }
}
