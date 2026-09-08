package com.regolith

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Process-wide entry point. Android creates exactly one of these before any
 * screen. `@HiltAndroidApp` makes it the root of the dependency-injection
 * graph: every `@Inject` in the app is resolved from here.
 *
 * Web analogy: the module that builds the DI container / root context
 * provider before rendering the app.
 */
@HiltAndroidApp
class RegolithApp : Application()
