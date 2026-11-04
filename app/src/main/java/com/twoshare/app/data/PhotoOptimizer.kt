package com.twoshare.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shrink new photos before uploading them. Existing Storage objects are never changed.
 * Converts supported still images to bounded-size JPEG while preserving EXIF orientation.
 * Animated GIFs and unsupported image formats retain their original data/extension.
 */
object PhotoOptimizer {
    data class EncodedPhoto(val bytes: ByteArray, val extension: String)

    suspend fun prepare(
        context: Context,
        uri: Uri,
        maxInputBytes: Int = 15 * 1024 * 1024,
        maxDimension: Int = 1600
    ): EncodedPhoto = withContext(Dispatchers.Default) {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        require(mime.startsWith("image/")) { "Please choose an image." }
        val originalExtension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mime)?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{2,6}")) }
            ?: "jpg"
        val original = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxInputBytes) {
                    "Photo must be " + (maxInputBytes / 1024 / 1024) + " MB or smaller."
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Unable to read selected photo.")
        require(original.isNotEmpty()) { "Selected photo is empty." }

        if (mime == "image/gif") return@withContext EncodedPhoto(original, originalExtension)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext EncodedPhoto(original, originalExtension)
        }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@withContext EncodedPhoto(original, originalExtension)

        val exifOrientation = runCatching {
            ExifInterface(ByteArrayInputStream(original)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.postRotate(90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.postRotate(270f)
                transform.postScale(-1f, 1f)
            }
        }
        val oriented = if (transform.isIdentity) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
        val scale = maxDimension.toFloat() / max(oriented.width, oriented.height)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                oriented,
                max(1, (oriented.width * scale).roundToInt()),
                max(1, (oriented.height * scale).roundToInt()),
                true
            )
        } else oriented

        // A white background prevents transparent PNGs turning black when JPEG encoded.
        val opaque = if (resized.hasAlpha()) {
            Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(resized, 0f, 0f, null)
                }
            }
        } else resized
        val compressed = ByteArrayOutputStream()
        if (!opaque.compress(Bitmap.CompressFormat.JPEG, 82, compressed)) {
            return@withContext EncodedPhoto(original, originalExtension)
        }
        val jpeg = compressed.toByteArray()
        if (jpeg.isNotEmpty() && (jpeg.size < original.size ||
                    originalExtension !in setOf("jpg", "jpeg"))) {
            EncodedPhoto(jpeg, "jpg")
        } else EncodedPhoto(original, originalExtension)
    }
}
