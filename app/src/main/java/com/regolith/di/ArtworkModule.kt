package com.regolith.di

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.regolith.data.artwork.ArtworkFetcher
import com.regolith.data.artwork.ArtworkKeyer
import com.regolith.data.artwork.DurationProbe
import com.regolith.data.artwork.FrameSourceFactory
import com.regolith.data.artwork.RetrieverFrameSource
import com.regolith.data.transfer.TransferScheduler
import com.regolith.data.transfer.WorkManagerTransferScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Artwork and transfer wiring: which frame source, which transfer scheduler, and the one Coil ImageLoader. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ArtworkModule {
    @Binds abstract fun bindFrameSourceFactory(impl: RetrieverFrameSource.Factory): FrameSourceFactory
    @Binds abstract fun bindTransferScheduler(impl: WorkManagerTransferScheduler): TransferScheduler

    companion object {
        /** The artwork pipeline's last resort for a runtime: the container probe. */
        @Provides
        @Singleton
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun provideDurationProbe(probe: com.regolith.player.MediaProbe): DurationProbe =
            DurationProbe { fileId -> runCatching { probe.probe(fileId).durationMs }.getOrNull() }

        /**
         * Coil configured as a view layer only (guardrail G5): memory cache
         * on, its disk cache off because the artwork directory is the disk
         * cache, and our fetcher registered for [com.regolith.domain.artwork.ArtworkRequest].
         */
        @Provides
        @Singleton
        fun provideImageLoader(
            @ApplicationContext context: Context,
            fetcherFactory: ArtworkFetcher.Factory,
            keyer: ArtworkKeyer,
        ): ImageLoader = ImageLoader.Builder(context)
            .components {
                add(keyer)
                add(fetcherFactory)
            }
            .diskCache(null)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(150)
            .build()
    }
}
