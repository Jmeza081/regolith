package com.regolith

import android.app.Application
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
 * fetcher), so `AsyncImage` anywhere in the tree finds it without a
 * parameter. Web analogy: the module that builds the DI container / root
 * context provider before rendering the app.
 */
@HiltAndroidApp
class RegolithApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var imageLoader: ImageLoader

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
