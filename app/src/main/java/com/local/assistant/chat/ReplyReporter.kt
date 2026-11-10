package com.local.assistant.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why the user flagged a reply, as the report sheet offers it. */
enum class ReportReason(val label: String) {
    OFFENSIVE("Offensive or hateful"),
    SEXUAL("Sexual or explicit"),
    DANGEROUS("Dangerous or harmful advice"),
    WRONG("Wrong or made up"),
    OTHER("Something else"),
}

/**
 * One flagged reply, as it is sent: the question and the reply as the chat shows them, with
 * sensitive details hidden (see SensitiveText.masked), and nothing that says who sent it.
 */
data class ReplyReport(
    val reason: ReportReason,
    val note: String,
    val question: String,
    val reply: String,
    /** The app's version and the model's file, e.g. "0.3.0 (5), gemma-4-E4B-it.litertlm". */
    val version: String,
)

/**
 * "Report this reply", which Play asks of every app whose AI writes what it shows: the user
 * flags a reply from inside the app and it reaches the developer. The app has no server, so a
 * report is the answer to a Google Form the developer owns, and lands in its Google Sheet.
 *
 * Only what the report sheet shows is sent; no name, account, chat or device id. Google sees the
 * phone's IP address, as with any request.
 */
class ReplyReporter(
    private val form: ReportForm? = ReportForm.parse(FORM_LINK),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    /** Sends one report. Throws [ReportError], with words for the user, when it didn't arrive. */
    suspend fun send(report: ReplyReport) {
        val form = form ?: throw ReportError("Reporting isn't set up in this build of the app.")
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(form.action).post(form.body(report)).build()
            val (code, host) = try {
                http.newCall(request).execute().use { it.code to it.request.url.host }
            } catch (e: IOException) {
                Log.w(TAG, "Report not sent: ${e.javaClass.simpleName}")
                throw ReportError("The report couldn't be sent; the phone may be offline.")
            }
            // A form that asks for a Google sign-in redirects to one, which also answers 200.
            if (code != 200 || host != form.action.host) {
                Log.w(TAG, "Report not accepted: $code from $host")
                throw ReportError("The report couldn't be sent ($code). Try again later.")
            }
        }
    }

    companion object {
        private const val TAG = "ReplyReporter"

        /**
         * The form's pre-filled link (Google Forms → ⋮ → Get pre-filled link), with each answer
         * filled in with the name of what goes there: reason, question, reply, note, version.
         * The link holds no secret; anyone with it can only add answers to the form.
         */
        const val FORM_LINK = ""
    }
}

class ReportError(message: String) : Exception(message)

/** Where a report is posted, and which of the form's fields takes which part of it. */
class ReportForm(val action: HttpUrl, private val fields: Map<String, String>) {

    fun body(report: ReplyReport): FormBody = FormBody.Builder()
        .add(fields.getValue(REASON), report.reason.label)
        .add(fields.getValue(QUESTION), report.question.take(MAX_CHARS))
        .add(fields.getValue(REPLY), report.reply.take(MAX_CHARS))
        .add(fields.getValue(NOTE), report.note.trim().take(MAX_CHARS))
        .add(fields.getValue(VERSION), report.version)
        .build()

    companion object {
        private const val REASON = "reason"
        private const val QUESTION = "question"
        private const val REPLY = "reply"
        private const val NOTE = "note"
        private const val VERSION = "version"
        private val NAMES = setOf(REASON, QUESTION, REPLY, NOTE, VERSION)

        /** A long answer fits in a Sheet's cell (50,000 characters) with room to spare. */
        private const val MAX_CHARS = 20_000

        /**
         * From a pre-filled link, `…/forms/d/e/<id>/viewform?usp=pp_url&entry.123=reason&…`.
         * Null unless it is a Google Form's link with all five fields named.
         */
        fun parse(link: String): ReportForm? {
            val url = link.trim().toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments
            if (url.host != "docs.google.com" || segments.firstOrNull() != "forms" || segments.lastOrNull() != "viewform") return null
            val fields = url.queryParameterNames
                .filter { it.startsWith("entry.") }
                .associateBy { url.queryParameter(it).orEmpty().trim().lowercase() }
            if (!fields.keys.containsAll(NAMES)) return null
            val action = url.newBuilder().query(null).setPathSegment(segments.lastIndex, "formResponse").build()
            return ReportForm(action, fields)
        }
    }
}
