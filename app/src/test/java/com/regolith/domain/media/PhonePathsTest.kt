package com.regolith.domain.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phone rows are keyed by absolute path; people read them as the folder they know. */
class PhonePathsTest {

    @Test fun `internal storage drops the volume prefix`() {
        assertEquals("DCIM/Camera", PhonePaths.display("storage/emulated/0/DCIM/Camera"))
        assertEquals("Telegram/Telegram Video", PhonePaths.display("storage/emulated/0/Telegram/Telegram Video"))
    }

    @Test fun `the volume root itself has a name`() {
        assertEquals("Internal storage", PhonePaths.display("storage/emulated/0"))
    }

    @Test fun `anything else is an SD card`() {
        assertEquals("SD card · Movies", PhonePaths.display("storage/1A2B-3C4D/Movies"))
        assertEquals("SD card", PhonePaths.display("storage/1A2B-3C4D"))
    }
}
