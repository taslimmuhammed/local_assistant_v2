package com.local.assistant.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val form: ReportForm = ReportForm.DEVELOPER,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    /** Sends one report. Throws [ReportError], with words for the user, when it didn't arrive. */
    suspend fun send(report: ReplyReport) = withContext(Dispatchers.IO) {
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

    companion object {
        private const val TAG = "ReplyReporter"
    }
}

class ReportError(message: String) : Exception(message)

/**
 * Where a report is posted: a form with a multiple-choice question for the reason (with an
 * "Other" choice) and a paragraph question that takes the rest, written out.
 */
class ReportForm(
    val action: HttpUrl,
    private val reasonField: String,
    private val detailsField: String,
    /** The form's own wording of each reason; a reason without one goes in as "Other". */
    private val choices: Map<ReportReason, String>,
) {
    fun body(report: ReplyReport): FormBody {
        val body = FormBody.Builder()
        val choice = choices[report.reason]
        if (choice != null) {
            body.add(reasonField, choice)
        } else {
            body.add(reasonField, OTHER).add("$reasonField.other_option_response", report.reason.label)
        }
        return body.add(detailsField, details(report)).build()
    }

    companion object {
        /** How Google Forms names the "Other" choice of a multiple-choice question. */
        private const val OTHER = "__other_option__"

        /** Each long part, so the whole answer stays inside a Sheet's cell (50,000 characters). */
        private const val MAX_CHARS = 20_000

        /**
         * The developer's form, "Local Assistant" (developertaslim@gmail.com, 10 Nov 2026):
         * "Whats wrong with reply", a choice, and "Paste the contents of the reply here". Both are
         * required. The ids come from the form's page; they change if a question is replaced.
         */
        val DEVELOPER = ReportForm(
            action = "https://docs.google.com/forms/d/e/1FAIpQLSe4h3_bdggIdsxMakSRhTw8tjzXEI-sx19gDIPfpGmko0VBjg/formResponse".toHttpUrl(),
            reasonField = "entry.1028189503",
            detailsField = "entry.855371423",
            choices = mapOf(
                ReportReason.OFFENSIVE to "offensive or hateful",
                ReportReason.SEXUAL to "Sexual or explicit",
                ReportReason.DANGEROUS to "Dangerous or harmful advice",
                ReportReason.WRONG to "Wrong or made up",
            ),
        )

        /** The paragraph answer: what was asked, the reply, the user's note and the app, each labelled. */
        fun details(report: ReplyReport): String = listOfNotNull(
            "Question: ${report.question.ifBlank { "(none)" }.take(MAX_CHARS)}",
            "Reply: ${report.reply.take(MAX_CHARS)}",
            report.note.trim().takeIf { it.isNotEmpty() }?.let { "Note: ${it.take(MAX_CHARS)}" },
            "App: ${report.version}",
        ).joinToString("\n\n")
    }
}
