package com.regolith.data.artwork

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.artwork.ArtworkSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

/**
 * Teaches Coil what an [ArtworkRequest] is. Coil is the view layer only
 * (guardrail G5): it decodes and memory-caches; the bytes come from the
 * artwork directory, and a miss runs the resolver.
 *
 * Web analogy: a custom loader for an `<img>` whose `src` is an app object
 * rather than a URL.
 */
class ArtworkFetcher(
    private val request: ArtworkRequest,
    private val repository: ArtworkRepository,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val row = repository.resolve(request) ?: throw ArtworkUnavailable(request, transient = true)
        if (row.source == ArtworkSource.PLACEHOLDER.name) throw ArtworkUnavailable(request, transient = false)
        val file = repository.fileFor(row)
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory @Inject constructor(private val repository: ArtworkRepository) : Fetcher.Factory<ArtworkRequest> {
        override fun create(data: ArtworkRequest, options: Options, imageLoader: ImageLoader): Fetcher = ArtworkFetcher(data, repository)
    }
}

/** Memory-cache key. Without a keyer Coil would not cache a custom model at all. */
class ArtworkKeyer @Inject constructor() : Keyer<ArtworkRequest> {
    override fun key(data: ArtworkRequest, options: Options): String =
        "artwork:${data.owner.typeName}:${data.owner.id}:${data.owner.variant}:${data.kind.name.lowercase()}"
}

/** Coil reports this as the error state; the tile draws the wedge placeholder. */
class ArtworkUnavailable(request: ArtworkRequest, val transient: Boolean) :
    Exception("No artwork for ${request.owner} (${if (transient) "share unreachable" else "placeholder"})")
