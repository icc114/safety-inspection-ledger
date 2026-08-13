package cn.safetyledger.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cn.safetyledger.app.data.MediaEntity
import cn.safetyledger.app.data.MediaKind
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object Watermarker {
    fun apply(source: Bitmap, capturedAt: Long, location: String?, lat: Double?, lon: Double?): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val textSize = (bitmap.width / 34f).coerceAtLeast(28f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        val lines = buildList {
            add(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(capturedAt)))
            location?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            if (lat != null && lon != null) add("%.6f, %.6f".format(Locale.US, lat, lon))
        }
        val lineHeight = textSize * 1.25f
        val panelHeight = lineHeight * lines.size + 28f
        canvas.drawRect(0f, bitmap.height - panelHeight, bitmap.width.toFloat(), bitmap.height.toFloat(), Paint().apply { color = 0x72000000 })
        lines.forEachIndexed { index, value -> canvas.drawText(value, 20f, bitmap.height - panelHeight + 20f + lineHeight * (index + 1), paint) }
        return bitmap
    }
}

object MediaImporter {
    fun import(context: Context, uri: Uri, inspectionId: String, kind: MediaKind, location: String?): MediaEntity {
        val mediaRoot = File(context.filesDir, "media/$inspectionId").apply { mkdirs() }
        val original = File(mediaRoot, "${UUID.randomUUID()}-source")
        context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "无法读取照片" }.copyTo(original.outputStream()) }
        val exif = runCatching { ExifInterface(original) }.getOrNull()
        val capturedAt = exif?.dateTimeOriginal ?: exif?.dateTime ?: original.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        val latLong = exif?.latLong
        val source = BitmapFactory.decodeFile(original.absolutePath) ?: error("照片格式不支持")
        val scaled = scaleDown(source, 2200)
        val watermarked = Watermarker.apply(scaled, capturedAt, location, latLong?.getOrNull(0), latLong?.getOrNull(1))
        val output = File(mediaRoot, "${UUID.randomUUID()}.jpg")
        FileOutputStream(output).use { watermarked.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        original.delete()
        if (scaled !== source) scaled.recycle()
        if (watermarked !== scaled) watermarked.recycle()
        if (!source.isRecycled) source.recycle()
        return MediaEntity(
            id = UUID.randomUUID().toString(), inspectionId = inspectionId, kind = kind,
            localPath = output.absolutePath, sha256 = sha256(output), capturedAt = capturedAt,
            latitude = latLong?.getOrNull(0), longitude = latLong?.getOrNull(1),
        )
    }

    private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxSide) return source
        val ratio = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val read = input.read(buffer); if (read <= 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class MediaRetention {
    fun candidates(root: File, now: Long = System.currentTimeMillis()) = root.walkTopDown().filter { it.isFile && now - it.lastModified() > 180L * 24 * 60 * 60 * 1000 }.toList()
}
