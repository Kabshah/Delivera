package com.kabshah.delivra.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Decodes a small JPEG preview of an image file using the platform codecs.
 *
 * Why Kotlin and not the Node engine: Baileys' generateThumbnail runs sharp,
 * which on this runtime resolves to @img/sharp-wasm32 (an 8.9MB WASM libvips).
 * A WASM decode abort (OOM trap) is uncatchable JS-side — it killed the whole
 * app process on the first VN+image dispatch (2026-11 all-day crash loop).
 * The engine-side sharp path is patched out (minify-engine patch 6); Baileys
 * skips its own thumbnail entirely when jpegThumbnail is provided by the
 * sender payload.
 */
object ImageThumb {

    data class Thumb(val bytes: ByteArray, val width: Int, val height: Int)

    /** Target thumbnail edge (~WhatsApp preview size). Decode is scaled via
     *  inSampleSize before the bitmap ever exists, so even a 50MP photo costs
     *  a few hundred KB of RAM and ~15KB of JPEG. */
    private const val TARGET_EDGE_PX = 160
    private const val JPEG_QUALITY = 60

    /**
     * Returns the thumbnail bytes plus the ORIGINAL image dimensions (Baileys
     * uses the original dimensions for the ImageMessage proto), or null if the
     * file isn't a decodable image.
     */
    fun generate(path: String): Thumb? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_EDGE_PX) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            return Thumb(out.toByteArray(), bounds.outWidth, bounds.outHeight)
        } finally {
            bitmap.recycle()
        }
    }
}
