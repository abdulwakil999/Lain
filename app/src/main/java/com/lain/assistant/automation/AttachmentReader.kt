package com.lain.assistant.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Something the user attached, in the form a model can actually use.
 *
 * [images] holds every frame or page that can be looked at — one for a photo,
 * several for a PDF or a video — and reaches the model only where it can see.
 * [extractedText] is set for anything readable as text, which now includes Word,
 * PowerPoint and OpenDocument files: they are zip archives with the text inside,
 * so reading them needs no library, only the willingness to open the archive.
 *
 * When neither is set the attachment is still described, because "that's an
 * encrypted archive, I can't look inside it" is a useful answer and silently
 * dropping the file is not.
 */
data class Attachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Base64 JPEGs: pages of a document, frames of a video, or the one photo. */
    val images: List<String> = emptyList(),
    val extractedText: String? = null,
    /** Set when the file was accepted but nothing could be made of its contents. */
    val limitation: String? = null
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")

    /** Whether anything at all came out of the file. */
    val isReadable: Boolean get() = images.isNotEmpty() || extractedText != null

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

        /**
         * Pages of a PDF rendered for a model to look at.
         *
         * Every page is an image and images are the expensive part of a request, so
         * this is a deliberate ceiling rather than "however many there are". Four
         * covers the documents people actually attach to an assistant — a letter, a
         * form, a receipt, a page of notes — matches the per-message picture ceiling
         * in ChatEngine so nothing is trimmed twice, and the reply says plainly when
         * a longer document was cut off.
         */
        private const val MAX_PDF_PAGES = 4

        /** Frames sampled across a video, evenly spaced. */
        private const val MAX_VIDEO_FRAMES = 4

        /** Longest edge for a rendered page. Small enough to send, large enough to read. */
        private const val MAX_PAGE_EDGE = 1400

        /** Types that are text under a different name. */
        private val TEXTUAL = listOf(
            "text/", "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/csv", "application/rtf"
        )

        private val TEXTUAL_EXTENSIONS = setOf(
            "txt", "md", "csv", "tsv", "json", "xml", "yml", "yaml", "log", "kt", "java",
            "py", "js", "ts", "html", "css", "sh", "rs", "go", "c", "h", "cpp", "sql", "ini", "conf"
        )

        /** Document formats that are really zip archives with XML inside. */
        private val OFFICE_EXTENSIONS = setOf(
            "docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt"
        )
    }

    suspend fun read(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val (name, size) = queryMetadata(uri)
        val mime = context.contentResolver.getType(uri)
            ?: guessMimeFromName(name)
            ?: "application/octet-stream"

        val extension = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") -> readImage(uri, name, size, mime)
            isTextual(mime, name) -> readText(uri, name, size, mime)
            mime == "application/pdf" || extension == "pdf" -> readPdf(uri, name, size, mime)
            isOfficeDocument(mime, extension) -> readOfficeDocument(uri, name, size, mime, extension)
            mime.startsWith("video/") -> readVideo(uri, name, size, mime)
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
        mime.startsWith("audio/") ->
            "Lain can't listen to an audio file. Read it out and she'll hear it live."
        mime.contains("zip") || mime.contains("compressed") ->
            "That's an archive — Lain can't look inside it."
        else ->
            "Lain can see the file but can't read this format's contents."
    }

    // ------------------------------------------------------------------- pdf

    /**
     * Reads a PDF by rendering its pages and letting the model look at them.
     *
     * PdfRenderer is in the framework and draws a page to a bitmap; it does not
     * expose the text layer, and pulling that out properly needs a font-aware parser
     * nobody should ship for this. Rendering sidesteps the whole problem: a scanned
     * page and a text page arrive the same way, and a vision model reads both.
     *
     * On a model that can't see, the pages are useless and saying so is the answer —
     * which is not the same as the old behaviour of refusing every PDF outright.
     */
    private fun readPdf(uri: Uri, name: String, size: Long, mime: String): Attachment {
        val pages = mutableListOf<String>()
        var total = 0
        val failure = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    total = renderer.pageCount
                    for (index in 0 until minOf(total, MAX_PDF_PAGES)) {
                        renderer.openPage(index).use { page ->
                            // A PDF page has no background of its own; without the white
                            // fill the render is black text on transparency, which
                            // encodes to a black rectangle.
                            val scale = (MAX_PAGE_EDGE.toFloat() / maxOf(page.width, page.height))
                                .coerceAtMost(2f)
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages += encode(bitmap)
                            bitmap.recycle()
                        }
                    }
                }
            }
            null
        }.exceptionOrNull()

        if (pages.isEmpty()) {
            return Attachment(
                uri, name, mime, size,
                limitation = when (failure) {
                    // PdfRenderer signals an encrypted document by throwing
                    // SecurityException from its constructor; the message is not
                    // documented and differs between builds, so the type is what to
                    // read. Naming the reason is the difference between "give me the
                    // password" and "send it again".
                    is SecurityException -> "That PDF is password-protected, so Lain can't open it."
                    else -> "Lain couldn't render that PDF — it may be damaged or an unsupported format."
                }
            )
        }

        return Attachment(
            uri, name, mime, size,
            images = pages,
            limitation = if (total > pages.size) {
                "$total pages; Lain is looking at the first ${pages.size}"
            } else null
        )
    }

    // ---------------------------------------------------------------- office

    private fun isOfficeDocument(mime: String, extension: String): Boolean =
        extension in OFFICE_EXTENSIONS ||
            mime.contains("officedocument") || mime.contains("opendocument") ||
            mime.contains("msword") || mime.contains("ms-excel") || mime.contains("ms-powerpoint")

    /**
     * Reads a Word, PowerPoint, Excel or OpenDocument file.
     *
     * All of them are zip archives with XML inside, which is why this needs no
     * library at all — `java.util.zip` opens the archive and the text is between the
     * tags. The old code refused the lot with "paste the text instead", which was a
     * capability gap dressed up as a format limitation.
     *
     * What comes out is the words, not the layout: no tables, no styling, no images.
     * That is stated rather than glossed, because a model handed a flattened
     * spreadsheet should say it is looking at cell text and not at a spreadsheet.
     */
    private fun readOfficeDocument(
        uri: Uri,
        name: String,
        size: Long,
        mime: String,
        extension: String
    ): Attachment {
        // The pre-2007 binary formats (.doc, .xls, .ppt) are not zip archives and
        // cannot be read this way. Saying which is more useful than failing vaguely.
        if (extension in setOf("doc", "xls", "ppt")) {
            return Attachment(
                uri, name, mime, size,
                limitation = "That's the old binary $extension format. Save it as .${extension}x and Lain can read it."
            )
        }

        val parts = StringBuilder()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && isTextBearing(entry.name)) {
                            val xml = zip.readBytes().decodeToString()
                            parts.append(stripXml(xml)).append('\n')
                        }
                        zip.closeEntry()
                        if (parts.length > MAX_TEXT_CHARS) break
                    }
                }
            }
        }

        val text = parts.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
        if (text.isBlank()) {
            return Attachment(
                uri, name, mime, size,
                limitation = "Lain opened that document but found no text in it — it may be all images."
            )
        }

        val clipped = text.length > MAX_TEXT_CHARS
        return Attachment(
            uri, name, mime, size,
            extractedText = text.take(MAX_TEXT_CHARS) + if (clipped) "\n… (truncated)" else "",
            limitation = buildString {
                append("Text only — no layout, tables or images")
                if (clipped) append(", and only the first $MAX_TEXT_CHARS characters")
            }
        )
    }

    /** The archive members that hold words, across the formats that use this shape. */
    private fun isTextBearing(entryName: String): Boolean = when {
        // Word
        entryName == "word/document.xml" -> true
        // PowerPoint. Read in whatever order the archive stores them, which is
        // usually but not always slide order — worth knowing before quoting "slide 3"
        // back at somebody.
        entryName.startsWith("ppt/slides/slide") && entryName.endsWith(".xml") -> true
        // Excel: the shared string table holds every piece of text in the workbook.
        entryName == "xl/sharedStrings.xml" -> true
        // OpenDocument — writer, calc and impress all use one content file.
        entryName == "content.xml" -> true
        else -> false
    }

    /**
     * The words out of an OOXML fragment.
     *
     * Paragraph and row ends become newlines before the tags go, or the whole
     * document arrives as one unbroken line and a model reads a CV as a sentence.
     */
    private fun stripXml(xml: String): String = xml
        .replace(Regex("</(w:p|a:p|text:p|text:h|w:tr|row)>"), "\n")
        .replace(Regex("<w:br\\s*/?>|<a:br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("[ \\t]{2,}"), " ")

    // ----------------------------------------------------------------- video

    /**
     * Reads a video as a handful of stills plus what the file says about itself.
     *
     * Lain cannot watch a video and this does not pretend otherwise — there is no
     * audio here and no motion. What it does is take frames spread across the
     * duration, which is enough to answer most of what people actually attach a
     * video to ask: what is in it, what does that sign say, is this the right clip.
     * The limitation names exactly that, so nothing is claimed that wasn't done.
     */
    private fun readVideo(uri: Uri, name: String, size: Long, mime: String): Attachment {
        val frames = mutableListOf<String>()
        var durationMs = 0L

        // release() rather than use{}: MediaMetadataRetriever only became AutoCloseable
        // in API 29, and this app runs from 26 — use{} compiles against the newer SDK
        // and then throws NoSuchMethodError on the phones it was meant to support.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            // Spread across the middle rather than from zero: the first frame of a
            // phone video is very often a blur or a black lead-in.
            for (i in 1..MAX_VIDEO_FRAMES) {
                val at = durationMs * i / (MAX_VIDEO_FRAMES + 1)
                val frame = retriever.getFrameAtTime(
                    at * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                val scaled = downscale(frame, MAX_IMAGE_EDGE)
                frames += encode(scaled)
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
            }
        } catch (t: Throwable) {
            AccessibilityMonitor.recordException("reading video attachment", t)
        } finally {
            runCatching { retriever.release() }
        }

        val seconds = durationMs / 1000
        val length = if (seconds > 0) "%d:%02d long".format(seconds / 60, seconds % 60) else "unknown length"

        if (frames.isEmpty()) {
            return Attachment(
                uri, name, mime, size,
                limitation = "Lain couldn't pull any frames out of that video ($length)."
            )
        }

        return Attachment(
            uri, name, mime, size,
            images = frames,
            limitation = "${frames.size} still frames from a video $length — no motion and no sound, " +
                "so Lain is looking at snapshots rather than watching it"
        )
    }

    /** One bitmap as the base64 JPEG the model wants. */
    private fun encode(bitmap: Bitmap): String = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
        val encoded = encode(scaled)
        if (scaled !== upright) scaled.recycle()
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()

        return Attachment(uri, name, mime, size, images = listOf(encoded))
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
