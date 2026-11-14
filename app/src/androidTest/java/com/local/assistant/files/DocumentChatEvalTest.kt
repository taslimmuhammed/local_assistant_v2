package com.local.assistant.files

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * A made-up bill attached in a chat and asked about, through the app's own [ChatSender], as the
 * user would: first in a new chat, then after a turn of chat in the same one (on 14 Nov 2026 the
 * second way read only the bill's last line). A request to act on it too, whose reminder is undone.
 * Every chat is hidden and deleted afterwards.
 *
 *     adb shell am instrument -w -e eval documentchat -e class com.local.assistant.files.DocumentChatEvalTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DocumentChatEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    private class Case(val name: String, val before: String?, val asked: String, val wants: List<String>, val acts: Boolean = false)

    private val cases = listOf(
        Case("question, new chat", null, "how much do I have to pay and by when?", listOf("1,846", "20 November")),
        Case("question, after a turn", "hi, I'm sorting out some bills today", "how much do I have to pay and by when?", listOf("1,846", "20 November")),
        Case("summary, after a turn", "hi, I'm sorting out some bills today", "summarise this", listOf("1,846")),
        Case("nothing said, new chat", null, "", listOf("1,846")),
        Case("command, new chat", null, "remind me to pay this bill on the due date", listOf(), acts = true),
    )

    @Test
    fun attachedBillIsReadRight() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval documentchat", InstrumentationRegistry.getArguments().getString("eval") == "documentchat")
        val only = InstrumentationRegistry.getArguments().getString("case")
        var passed = 0
        var ran = 0
        try {
            for (case in cases.filter { only == null || it.name.startsWith(only) }) {
                ran++
                if (run(case)) passed++
            }
        } finally {
            report("$passed of $ran passed")
        }
        assertTrue("$passed of $ran passed", passed == ran)
    }

    private suspend fun run(case: Case): Boolean {
        val bill = File(context.filesDir, "attachments/${UUID.randomUUID()}-LA_test_power_bill.pdf")
        bill.parentFile?.mkdirs()
        bill.writeBytes(pdf(BILL))
        var chatId: Long? = null
        try {
            case.before?.let { said -> chatId = send(chatId, said, null).first }
            val started = SystemClock.elapsedRealtime()
            val before = chatId?.let { id -> container.chatRepository.messagesFor(id).maxOfOrNull { it.id } } ?: 0L
            val (chat, reply) = send(chatId, case.asked, ChatSender.Attachment(bill.absolutePath, AttachmentKind.DOCUMENT))
            chatId = chat
            val took = SystemClock.elapsedRealtime() - started
            val tools = container.chatRepository.messagesFor(chat)
                .filter { it.role == Role.TOOL && it.id > before }
                .mapNotNull { row -> ChatToolLog.parse(row.text)?.let { row.id to it } }
            val ok = if (case.acts) {
                val task = tools.firstOrNull { it.second.tool == ToolCatalog.ADD_TASK }
                // The reminder is the test's: undone straight away, so it never rings.
                tools.filter { it.second.tool in ToolCatalog.WRITES }.forEach { (id, _) -> report("  undo ${container.toolExecutor.undo(id)}") }
                task != null && task.second.result.contains("20 Nov")
            } else {
                case.wants.all { it in reply }
            }
            val shown = tools.joinToString { "${it.second.tool}(${it.second.arguments}) -> ${it.second.result.take(120)}" }
            report("${if (ok) "PASS" else "FAIL"} ${case.name} in $took ms: $shown | ${reply.take(400).replace('\n', ' ')}")
            return ok
        } finally {
            chatId?.let {
                container.chatRepository.deleteChat(it)
                container.conversations.forget(it)
            }
            bill.delete()
        }
    }

    /** One message, sent and waited for: the chat it went to and the reply. */
    private suspend fun send(chatId: Long?, text: String, attachment: ChatSender.Attachment?): Pair<Long, String> {
        val created = CompletableDeferred<Long>()
        val finished = CompletableDeferred<String>()
        val started = container.chatSender.send(
            chatId = chatId,
            text = text,
            attachment = attachment,
            owner = this,
            hidden = true,
            listener = object : ChatSender.Listener {
                override fun onChatCreated(chatId: Long) {
                    created.complete(chatId)
                }

                override fun onFinished(reply: String, failure: String?) {
                    finished.complete(reply + (failure?.let { " [failed: $it]" } ?: ""))
                }
            },
        )
        assertTrue("the sender was busy", started)
        val chat = chatId ?: withTimeout(30_000) { created.await() }
        return chat to withTimeout(240_000) { finished.await() }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "document-chat-eval.txt").appendText("$line\n")
    }

    companion object {
        private const val TAG = "DocumentChatEval"

        private val BILL = listOf(
            "Sample Power Company",
            "Electricity bill - October 2026",
            "Consumer number: 4471 2290 18",
            "Bill date: 5 November 2026",
            "Units used: 214",
            "Amount due: Rs 1,846.00",
            "Pay by: 20 November 2026",
            "A late fee of Rs 50 applies after the due date.",
        )

        /**
         * A one-page PDF in Helvetica with a real text layer (Android's own PdfDocument leaves most
         * words out of it): the objects, then the cross-reference table their offsets go in.
         */
        fun pdf(lines: List<String>): ByteArray {
            fun esc(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            val stream = "BT /F1 14 Tf 60 780 Td 18 TL\n" + lines.joinToString("") { "(${esc(it)}) Tj T*\n" } + "ET"
            val objects = listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            )
            val body = StringBuilder("%PDF-1.4\n")
            val offsets = objects.mapIndexed { i, o -> body.length.also { body.append("${i + 1} 0 obj\n$o\nendobj\n") } }
            val xref = body.length
            body.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { body.append("%010d 00000 n \n".format(it)) }
            body.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return body.toString().toByteArray(Charsets.ISO_8859_1)
        }
    }
}
