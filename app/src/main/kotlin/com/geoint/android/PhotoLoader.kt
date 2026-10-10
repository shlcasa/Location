package com.geoint.android

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.geoint.core.EncodedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

class LoadedPhoto(
    val preview: Bitmap,
    val images: List<EncodedImage>,
    val metadata: String,
    val gps: Pair<Double, Double>?,
    val fileName: String?,
)

/**
 * Reads a photo the way GeoInt's recon step starts: metadata first (GPS, time, UTC offset, camera, file name),
 * then the full frame plus overlapping zoomed tiles so small signs and plates stay legible to the model.
 */
object PhotoLoader {
    /** Claude's recommended long edge for one image; larger inputs are downscaled server-side anyway. */
    private const val MAX_EDGE = 1568
    private const val TILE_SOURCE_EDGE = MAX_EDGE * 2
    private const val TILE_OVERLAP = 0.15f

    fun load(context: Context, uri: Uri, withTiles: Boolean): LoadedPhoto {
        val resolver = context.contentResolver
        val fileName = displayName(resolver, uri)
        val exifUri = originalUri(context, uri)
        val exif = runCatching { open(resolver, exifUri).use { ExifInterface(it) } }
            .recoverCatching { open(resolver, uri).use { ExifInterface(it) } }
            .getOrNull()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Not a readable image")

        val longEdge = max(bounds.outWidth, bounds.outHeight)
        val tiles = withTiles && longEdge >= MAX_EDGE + MAX_EDGE / 4
        val wanted = if (tiles) TILE_SOURCE_EDGE else MAX_EDGE
        var sample = 1
        while (longEdge / (sample * 2) >= wanted) sample *= 2
        val decoded = open(resolver, uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Could not decode the image")
        val source = rotate(decoded, exif?.rotationDegrees ?: 0, exif?.isFlipped ?: false)

        val full = scaleDown(source, MAX_EDGE)
        val images = mutableListOf(EncodedImage("Full photo (${source.width}x${source.height} source, downscaled):", encode(full)))
        if (tiles) {
            val tw = (source.width * (0.5f + TILE_OVERLAP / 2)).roundToInt()
            val th = (source.height * (0.5f + TILE_OVERLAP / 2)).roundToInt()
            val names = listOf("top-left", "top-right", "bottom-left", "bottom-right")
            names.forEachIndexed { i, name ->
                val x = if (i % 2 == 0) 0 else source.width - tw
                val y = if (i < 2) 0 else source.height - th
                val tile = scaleDown(Bitmap.createBitmap(source, x, y, tw, th), MAX_EDGE)
                images += EncodedImage("Zoomed tile $name quarter (overlapping, for reading small text):", encode(tile))
            }
        }

        val gps = exif?.latLong?.let { it[0] to it[1] }?.takeIf { (lat, lon) -> !(lat == 0.0 && lon == 0.0) }
        return LoadedPhoto(
            preview = scaleDown(source, 1080),
            images = images,
            metadata = describe(exif, gps, fileName, bounds, exifUri != uri),
            gps = gps,
            fileName = fileName,
        )
    }

    private fun open(resolver: ContentResolver, uri: Uri) =
        resolver.openInputStream(uri) ?: throw IllegalArgumentException("Cannot open $uri")

    /** Android hides EXIF location unless we hold ACCESS_MEDIA_LOCATION and ask for the original bytes. */
    private fun originalUri(context: Context, uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) return uri
        return runCatching {
            val media = if (uri.authority == MediaStore.AUTHORITY) uri else MediaStore.getMediaUri(context, uri)
            media?.let { MediaStore.setRequireOriginal(it) } ?: uri
        }.getOrDefault(uri)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun rotate(bitmap: Bitmap, degrees: Int, flipped: Boolean): Bitmap {
        if (degrees == 0 && !flipped) return bitmap
        val m = Matrix()
        if (flipped) m.postScale(-1f, 1f)
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun scaleDown(bitmap: Bitmap, edge: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= edge) return bitmap
        val f = edge.toFloat() / long
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt(), (bitmap.height * f).roundToInt(), true)
    }

    private fun encode(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun describe(
        exif: ExifInterface?,
        gps: Pair<Double, Double>?,
        fileName: String?,
        bounds: BitmapFactory.Options,
        triedOriginal: Boolean,
    ): String = buildString {
        fun line(label: String, value: String?) {
            if (!value.isNullOrBlank()) append("- ").append(label).append(": ").append(value.trim()).append('\n')
        }
        line("File name", fileName)
        line("Original size", "${bounds.outWidth}x${bounds.outHeight} ${bounds.outMimeType ?: ""}")
        if (exif == null) {
            append("- EXIF: unreadable or absent\n")
            return@buildString
        }
        if (gps != null) {
            line("EXIF GPS", "%.6f, %.6f".format(java.util.Locale.US, gps.first, gps.second))
            line("GPS altitude (m)", exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { "%.0f".format(java.util.Locale.US, it) })
            line("GPS date (UTC)", exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
            line("GPS direction", exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION))
        } else {
            append("- EXIF GPS: none")
            append(if (triedOriginal) " (file has no location)\n" else " (Android may have hidden it, or the sharing app stripped it)\n")
        }
        line("Taken (local time)", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
        line("UTC offset", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME))
        line("Camera", listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" "))
        line("Lens", exif.getAttribute(ExifInterface.TAG_LENS_MODEL))
        line("Focal length (35 mm equiv.)", exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM))
        line("Software", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        line("Description", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        line("Artist", exif.getAttribute(ExifInterface.TAG_ARTIST))
        line("Copyright", exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
    }
}
