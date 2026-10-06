package com.regolith.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.spoof.SpoofMode
import java.io.File

/**
 * A real [SpoofMode] over a settings file of its own, for tests that build
 * a class needing one by hand. Off unless [on] is set: it is switched the
 * way the Settings row switches it, so the salt is picked the same way too.
 */
fun testSpoofMode(context: Context, on: Boolean = false): SpoofMode {
    val prefs = AppPreferences(PreferenceDataStoreFactory.create { File(context.filesDir, "spoof-${System.nanoTime()}.preferences_pb") })
    if (on) kotlinx.coroutines.runBlocking { prefs.setSpoofMode(true) }
    return SpoofMode(prefs)
}
