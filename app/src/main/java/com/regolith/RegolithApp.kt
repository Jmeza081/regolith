package com.regolith

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Process-wide entry point. Android creates exactly one of these before any
 * screen. `@HiltAndroidApp` makes it the root of the dependency-injection
 * graph: every `@Inject` in the app is resolved from here.
 *
 * It also hands Coil the app's one [ImageLoader] (the one with the artwork
 * fetcher), and hands WorkManager a factory that can build workers with
 * injected dependencies. Web analogy: the module that builds the DI
 * container / root context provider before rendering the app.
 */
@HiltAndroidApp
class RegolithApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
