package com.lain.assistant.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Something the user attached, in the form a model can actually use.
 *
 * [imageBase64] is set only for pictures, and only when the selected model can see.
 * [extractedText] is set for anything readable as text. When neither is set, the
 * attachment is still described — because "I can't read a .docx" is a useful answer
 * and silently dropping the file is not.
 */
data class Attachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val imageBase64: String? = null,
    val extractedText: String? = null,
    /** Set when the file was accepted but nothing could be made of its contents. */
    val limitation: String? = null
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")

    /** Human-readable size, for the chip and for telling the model what it's holding. */
    fun prettySize(): String = when {
        sizeBytes <= 0 -> ""
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
    }

    /** What the model is told about this file when it isn't an image it can see. */
    fun describeForModel(): String = buildString {
        append("[attached file: ").append(displayName)
        append(" — ").append(mimeType)
        prettySize().takeIf { it.isNotEmpty() }?.let { append(", ").append(it) }
        append("]")
        extractedText?.let {
            append("\nContents:\n").append(it)
        }
        limitation?.let { append("\n(").append(it).append(")") }
    }
}

/**
 * Turns a picked or captured file into something Lain can reason about.
 *
 * Accepts every type, which is what the user asked for, and is straight about what
 * it can do with each. An assistant that takes a file and then never mentions it
 * again is worse than one that says "that's a video, I can't watch it" — the first
 * looks like it worked.
 */
class AttachmentReader(private val context: Context) {

    companion object {
        /** Longest edge for an attached image. Beyond this is tokens, not detail. */
        private const val MAX_IMAGE_EDGE = 1200

        /** Ceiling on text pulled out of a file, so a huge log can't fill the window. */
        private const val MAX_TEXT_CHARS = 12_000

        /** Refuse to even try reading something this large as text. */
        private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

        /** Types that are text under a different name. */
        private val TEXTUAL = listOf(
            "text/", "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/csv", "application/rtf"
        )

        private val TEXTUAL_EXTENSIONS = setOf(
            "txt", "md", "csv", "tsv", "json", "xml", "yml", "yaml", "log", "kt", "java",
            "py", "js", "ts", "html", "css", "sh", "rs", "go", "c", "h", "cpp", "sql", "ini", "conf"
        )
    }

    suspend fun read(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val (name, size) = queryMetadata(uri)
        val mime = context.contentResolver.getType(uri)
            ?: guessMimeFromName(name)
            ?: "application/octet-stream"

        when {
            mime.startsWith("image/") -> readImage(uri, name, size, mime)
            isTextual(mime, name) -> readText(uri, name, size, mime)
            else -> Attachment(
                uri = uri,
                displayName = name,
                mimeType = mime,
                sizeBytes = size,
                // Named precisely rather than as a generic failure, so the model can
                // tell the user what it is and suggest the thing that would work.
                limitation = describeLimitation(mime)
            )
        }
    }

    private fun describeLimitation(mime: String): String = when {
        mime.startsWith("video/") ->
            "Lain can't watch video. A screenshot from it she can look at."
        mime.startsWith("audio/") ->
            "Lain can't listen to an audio file. Read it out and she'll hear it live."
        mime == "application/pdf" ->
            "Lain can't read inside a PDF. A screenshot of the page she can look at."
        mime.contains("word") || mime.contains("officedocument") || mime.contains("opendocument") ->
            "Lain can't open documents in this format. Paste the text, or send a screenshot."
        mime.contains("zip") || mime.contains("compressed") ->
            "That's an archive — Lain can't look inside it."
        else ->
            "Lain can see the file but can't read this format's contents."
    }

    private fun isTextual(mime: String, name: String): Boolean =
        TEXTUAL.any { mime.startsWith(it) } ||
            name.substringAfterLast('.', "").lowercase() in TEXTUAL_EXTENSIONS

    private fun readText(uri: Uri, name: String, size: Long, mime: String): Attachment {
        if (size > MAX_TEXT_BYTES) {
            return Attachment(
                uri, name, mime, size,
                limitation = "That file is ${size / (1024 * 1024)} MB — too big to read in one go."
            )
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().decodeToString()
            }
        }.getOrNull()

        return if (text.isNullOrBlank()) {
            Attachment(uri, name, mime, size, limitation = "Lain couldn't read that file.")
        } else {
            val clipped = text.length > MAX_TEXT_CHARS
            Attachment(
                uri, name, mime, size,
                extractedText = text.take(MAX_TEXT_CHARS) + if (clipped) "\n… (truncated)" else "",
                limitation = if (clipped) "Only the first $MAX_TEXT_CHARS characters were read" else null
            )
        }
    }

    private fun readImage(uri: Uri, name: String, size: Long, mime: String): Attachment {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
            ?: return Attachment(uri, name, mime, size, limitation = "That image couldn't be opened.")

        val upright = runCatching { applyOrientation(uri, bitmap) }.getOrDefault(bitmap)
        val scaled = downscale(upright, MAX_IMAGE_EDGE)
        val encoded = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        if (scaled !== upright) scaled.recycle()
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()

        return Attachment(uri, name, mime, size, imageBase64 = encoded)
    }

    /**
     * Rotates by the EXIF tag.
     *
     * Phone cameras record orientation rather than rotating pixels, so a photo taken
     * in portrait arrives sideways. A model looking at a sideways photo describes a
     * sideways scene, and it looks like it can't see properly.
     */
    private fun applyOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: return bitmap

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun downscale(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use fallback to 0L
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: fallback else fallback
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                name to size
            } ?: (fallback to 0L)
        }.getOrDefault(fallback to 0L)
    }

    private fun guessMimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { return null }
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
