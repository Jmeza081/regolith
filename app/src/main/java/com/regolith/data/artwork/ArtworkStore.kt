package com.regolith.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The artwork directory (guardrail G5): `filesDir/artwork/{owner}/{id}/{kind}.jpg`.
 * App-private storage, so no permission is needed and uninstalling removes
 * it. Coil's own disk cache is deliberately not used: it evicts by size and
 * a frame grab over SMB is too expensive to lose to eviction, and the
 * offline "showing what's saved here" state depends on these surviving.
 *
 * Every image is stored at exactly its kind's size, centre-cropped, so a
 * tile never scales at draw time and a 40 MB poster.jpg on the share costs
 * 40 KB on the phone.
 */
@Singleton
class ArtworkStore @Inject constructor(@ApplicationContext context: Context) {
    val root: File = File(context.filesDir, "artwork")

    fun relPathFor(owner: ArtworkOwner, kind: ArtworkKind): String = "${owner.typeName}/${owner.id}/${kind.fileName}"

    fun fileFor(relPath: String): File = File(root, relPath)

    /** Decode [bytes] (a sidecar or embedded cover) at roughly the target size, then crop and save. */
    fun saveEncoded(bytes: ByteArray, owner: ArtworkOwner, kind: ArtworkKind): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val options = BitmapFactory.Options().apply {
            // Power-of-two downsampling while decoding keeps an 8 MB JPEG from
            // becoming a 100 MB bitmap in memory.
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, kind.width, kind.height)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        return try {
            save(decoded, owner, kind)
        } finally {
            decoded.recycle()
        }
    }

    /** Centre-crop [bitmap] to the kind's aspect, scale to its size, write as JPEG. */
    fun save(bitmap: Bitmap, owner: ArtworkOwner, kind: ArtworkKind): Boolean {
        val cropped = centerCrop(bitmap, kind.width, kind.height)
        val file = fileFor(relPathFor(owner, kind))
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        return try {
            FileOutputStream(tmp).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            tmp.renameTo(file)
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            if (cropped !== bitmap) cropped.recycle()
        }
    }

    fun delete(owner: ArtworkOwner) {
        File(root, "${owner.typeName}/${owner.id}").deleteRecursively()
    }

    fun clear() {
        root.deleteRecursively()
    }

    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val JPEG_QUALITY = 85

        fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
            var sample = 1
            while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) sample *= 2
            return sample
        }

        /** Scale so the target is covered, then crop the overflow evenly from both sides. */
        fun centerCrop(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
            if (src.width == dstW && src.height == dstH) return src
            val scale = maxOf(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
            val scaledW = Math.round(src.width * scale).coerceAtLeast(dstW)
            val scaledH = Math.round(src.height * scale).coerceAtLeast(dstH)
            val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
            val x = (scaledW - dstW) / 2
            val y = (scaledH - dstH) / 2
            val out = Bitmap.createBitmap(scaled, x, y, dstW, dstH)
            if (scaled !== src && scaled !== out) scaled.recycle()
            return out
        }
    }
}
