package com.local.assistant.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import com.local.assistant.files.DocumentText.Read
import com.local.assistant.files.DocumentText.Why
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A PDF's text, by Android's own PDF renderer: its text layer page by page on Android 15 and up
 * (and on 12 to 14 where the PDF module is new enough), each page marked "[Page N]". A PDF with
 * no text (pictures of pages, as a scanned document is) has its first pages drawn as pictures
 * instead, for the vision model to read.
 */
object PdfText {

    /** [open] gives a fresh descriptor each time; the renderer closes the one it's given. */
    fun read(open: () -> ParcelFileDescriptor, pagesDir: File): Read {
        val text = try {
            when {
                Build.VERSION.SDK_INT >= 35 -> textLayer(open())
                Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13 -> textLayerPreV(open())
                else -> null
            }
        } catch (e: SecurityException) {
            return Read.Unreadable(Why.PASSWORD)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't read a PDF's text", e)
            return Read.Unreadable(Why.DAMAGED)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't read a PDF's text", e)
            return Read.Unreadable(Why.DAMAGED)
        }
        if (text != null) {
            val letters = text.text.replace(PAGE_MARK, "").count(Char::isLetterOrDigit)
            // Counts only: nothing of the document goes to the log.
            Log.i(TAG, "Text layer: $letters letters and digits over ${text.pages} pages")
            // A few letters a page is a page number or a stamp on a scan, not text.
            if (letters >= MIN_LETTERS_PER_PAGE * min(text.pages, 5).coerceAtLeast(1)) {
                val read = DocumentText.finish(text.text.toString(), text.pages, "page")
                // A short PDF with little text may have only some of it in its text layer: Android's
                // own PDF writer leaves words out (a receipt read as two of its six lines, without
                // the total, 14 Nov 2026). Its pages are looked at, the text kept for a phone that can't.
                if (read !is Read.Text || text.pages > DocumentText.MAX_SCANNED_PAGES || letters >= THIN_LETTERS_PER_PAGE * text.pages) return read
                return when (val drawn = pictures(open, pagesDir)) {
                    is Read.Scanned -> drawn.copy(text = read)
                    else -> read
                }
            }
        }
        return pictures(open, pagesDir)
    }

    private class Layer(val text: StringBuilder, val pages: Int)

    @RequiresApi(35)
    private fun textLayer(fd: ParcelFileDescriptor): Layer = PdfRenderer(fd).use { renderer ->
        val out = StringBuilder()
        val pages = renderer.pageCount
        for (i in 0 until min(pages, MAX_PAGES)) {
            val page = renderer.openPage(i)
            try {
                out.append("[Page ").append(i + 1).append("]\n")
                page.textContents.forEach { out.append(it.text).append('\n') }
                out.append('\n')
            } finally {
                page.close()
            }
            if (out.length > DocumentText.MAX_CHARS) break
        }
        Layer(out, pages)
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun textLayerPreV(fd: ParcelFileDescriptor): Layer = PdfRendererPreV(fd).use { renderer ->
        val out = StringBuilder()
        val pages = renderer.pageCount
        for (i in 0 until min(pages, MAX_PAGES)) {
            val page = renderer.openPage(i)
            try {
                out.append("[Page ").append(i + 1).append("]\n")
                page.textContents.forEach { out.append(it.text).append('\n') }
                out.append('\n')
            } finally {
                page.close()
            }
            if (out.length > DocumentText.MAX_CHARS) break
        }
        Layer(out, pages)
    }

    /** The first pages drawn as JPEGs in [dir] (emptied first), for the vision model. */
    private fun pictures(open: () -> ParcelFileDescriptor, dir: File): Read = try {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        PdfRenderer(open()).use { renderer -> drawn(renderer, dir) }
    } catch (e: SecurityException) {
        Read.Unreadable(Why.PASSWORD)
    } catch (e: IOException) {
        Log.w(TAG, "Couldn't draw a PDF's pages", e)
        Read.Unreadable(Why.DAMAGED)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Couldn't draw a PDF's pages", e)
        Read.Unreadable(Why.DAMAGED)
    }

    private fun drawn(renderer: PdfRenderer, dir: File): Read {
        val pages = renderer.pageCount
        if (pages == 0) return Read.Unreadable(Why.EMPTY)
        val files = (0 until min(pages, DocumentText.MAX_SCANNED_PAGES)).map { i ->
            val page = renderer.openPage(i)
            try {
                val scale = LONG_SIDE / maxOf(page.width, page.height).toFloat()
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(dir, "page-${i + 1}.jpg").also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                page.close()
            }
        }
        return Read.Scanned(files, pages)
    }

    private const val TAG = "PdfText"

    /** Pages read for text; a reply reads far fewer, and finding a question's part needs no more. */
    private const val MAX_PAGES = 400

    private const val MIN_LETTERS_PER_PAGE = 20

    /**
     * Below this many letters a page, a PDF of up to [DocumentText.MAX_SCANNED_PAGES] pages is
     * looked at too: a bill or letter page has several hundred, a whole short receipt under 100.
     */
    private const val THIN_LETTERS_PER_PAGE = 150

    /** The "[Page N]" each page's text starts with here, not counted as the page's text. */
    private val PAGE_MARK = Regex("\\[Page \\d+]")

    /** Pixels on a drawn page's long side: small print stays legible to the vision model. */
    private const val LONG_SIDE = 1600f

    private const val JPEG_QUALITY = 88
}
