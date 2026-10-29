package com.local.assistant.gallery

import android.app.KeyguardManager
import android.util.Log
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import com.local.assistant.llm.ToolCallRepair
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.prompt.ConversationManager
import com.local.assistant.memory.prompt.MemoryBudget
import com.local.assistant.memory.prompt.TurnEvent
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.ReminderScheduler
import com.local.assistant.memory.tools.SystemAlarms
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.memory.tools.ToolLoop
import com.local.assistant.memory.work.AppForeground
import com.local.assistant.memory.work.ModelScheduler
import com.local.assistant.privacy.SensitiveText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Questions answered from the user's own photos (SSLC marks, licence, PAN, card numbers) with no
 * memory to fall back on: each in a new chat, through the app's own chat pipeline, model, photo
 * search and photo reading, but with memory in an empty database, so no fact, saved image or
 * earlier chat can supply the answer. As a new install would answer them.
 *
 * Nothing read is logged as it is: ID-like numbers are masked, and each run's value is reduced to
 * a short hash, so runs and wordings can be compared (the same document read the same way gives
 * the same hash). A card number is also checked against its Luhn digit, which a misread digit
 * almost always breaks.
 *
 *     adb shell am instrument -w -e eval photoread [-e runs 2] [-e only <words>] \
 *         -e class com.local.assistant.gallery.PhotoReadingEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to logcat (tag PhotoReadingEval) and `Android/data/<app>/files/photo-reading-eval.txt`.
 */
@RunWith(AndroidJUnit4::class)
class PhotoReadingEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "photo-reading-eval.txt").apply { writeText("") } }

    /** [kind]: the kind of number the answer should hold, when it should hold one. */
    private data class Case(val group: String, val question: String, val kind: SensitiveText.Kind? = null)

    private val cases = listOf(
        Case("sslc", "what was my SSLC score?"),
        Case("sslc", "get my sslc marks"),
        Case("sslc", "how much did I score in maths in SSLC?"),
        Case("sslc", "fetch my 10th marks"),
        Case("licence", "what's my driving licence number?", SensitiveText.Kind.DRIVING_LICENCE),
        Case("licence", "fetch my driving license number", SensitiveText.Kind.DRIVING_LICENCE),
        Case("licence", "my DL number?", SensitiveText.Kind.DRIVING_LICENCE),
        Case("licence-expiry", "when does my driving licence expire?"),
        Case("pan", "what is my PAN number?", SensitiveText.Kind.PAN),
        Case("pan", "fetch my pan number", SensitiveText.Kind.PAN),
        Case("pan", "give me my PAN card number", SensitiveText.Kind.PAN),
        Case("card", "what's my credit card number?", SensitiveText.Kind.CARD),
        Case("card", "fetch my credit card number", SensitiveText.Kind.CARD),
        Case("card-expiry", "when does my credit card expire?"),
        Case("aadhaar", "what's my Aadhaar number?", SensitiveText.Kind.AADHAAR),
        Case("passport", "what's my passport number?", SensitiveText.Kind.PASSPORT),
        Case("blood", "what's my blood group?"),
        Case("pan-dob", "what is my date of birth on my PAN card?"),
    )

    @Test
    fun answersFromPhotosAlone() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Loads the real model; run with -e eval photoread", args.getString("eval") == "photoread")
        val runs = args.getString("runs")?.toInt() ?: 2
        // "-e followup thanks!": said in the same chat after each answer, timed: a photo read closes
        // the chat's conversation, so this message rebuilds it.
        val followUp = args.getString("followup")
        val only = args.getString("only")?.lowercase()
        val container = context.appContainer
        check(container.photoSearchReady()) { "Photo search isn't set up on this phone" }

        // Memory with nothing in it: no facts, no saved images, no earlier chats.
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val memory = MemoryRepository(database)
        val chats = ChatRepository(database.chatDao(), SessionTracker(database.sessionDao(), AppForeground()), container.tokenEstimator)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var reader: PhotoReader? = null
        val photos = PhotoTools(
            container.galleryIndex,
            ready = container::photoSearchReady,
            faces = container.faceIndex,
            findPeople = { container.settings.findPeople },
            places = container.placeList,
            // As the app reads a found photo: its longest side at 1,024 px.
            readable = { mediaId ->
                withContext(Dispatchers.IO) {
                    container.photoLibrary.jpeg(mediaId, 1024)?.let { bytes ->
                        File(context.cacheDir, "photo-reading-eval.jpg").apply { writeBytes(bytes) }.absolutePath
                    }
                }
            },
            mayRead = { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true },
            // As the app answers a photo question: read on its own ("-e reader chat" for the old way, the chat reading it).
            reader = { path, question -> reader?.answer(path, question) },
        )
        val executor = ToolExecutor(
            store = memory,
            reminders = NoReminders,
            alarms = NoAlarms,
            log = ChatToolLog(chats),
            now = ZonedDateTime::now,
            photos = photos,
        )
        val llm = container.llmService
        val loop = ToolLoop(
            executor = executor,
            maxRounds = MemoryBudget.SIXTEEN_K.maxToolRounds,
            isOverflow = container.llmBackend::isContextOverflow,
            isUnreadable = container.llmBackend::isUnreadableToolCall,
            repair = ToolCallRepair(ToolCatalog.declarations(web = true, photos = true))::repair,
            groundNumbers = { !llm.exactDigits },
            seesImages = { (llm.capabilities.value?.visionTokensPerImage ?: 0) > 0 },
        )
        val web = container.webSearch.enabled
        val conversations = ConversationManager(
            backend = container.llmBackend,
            chats = chats,
            memory = memory,
            settings = container.settings,
            scheduler = ModelScheduler(),
            scope = scope,
            estimator = container.tokenEstimator,
            // The app's tool list: web search declared as the app declares it (with a key), but
            // not wired, so a question sent to the web is scored and goes nowhere.
            tools = { ToolCatalog.declarations(web = web, photos = true) },
            webSearch = { web },
            photoSearch = { true },
            retriever = null,
        )
        if (args.getString("reader") != "chat") reader = PhotoReader(container.llmBackend, conversations)
        val turns = TurnRunner(conversations, ModelScheduler(), loop)

        report("indexed ${container.galleryIndex.counts()} (indexed, not yet); web declared: $web; fp32 digits: ${llm.exactDigits}")
        // The app's own conversation is closed and held off meanwhile: never two at once.
        try {
            container.conversations.withModelFree {
                check(container.llmBackend.ensureReady()) { "No model loaded" }
                val values = LinkedHashMap<String, MutableList<String>>()
                var read = 0
                var asked = 0
                for (run in 1..runs) {
                    for (case in cases) {
                        if (only != null && only !in case.question.lowercase() && only != case.group) continue
                        asked++
                        val chatId = chats.createChat()
                        val messageId = chats.addMessage(chatId = chatId, role = Role.USER, text = case.question)
                        val reply = StringBuilder()
                        val started = System.currentTimeMillis()
                        val failure = runCatching {
                            withTimeout(TURN_TIMEOUT_MS) {
                                turns.run(chatId, messageId, case.question).collect { if (it is TurnEvent.Text) reply.append(it.delta) }
                            }
                        }.exceptionOrNull()
                        val seconds = (System.currentTimeMillis() - started) / 1000.0
                        // Kept for a follow-up, which is timed as the app would run it.
                        if (followUp == null) conversations.forget(chatId)
                        val calls = chats.messagesFor(chatId).filter { it.role == Role.TOOL }.mapNotNull { ChatToolLog.parse(it.text) }
                        val photoCall = calls.firstOrNull { it.tool == ToolCatalog.FIND_PHOTOS }
                        val wasRead = photoCall?.result?.let { "\"answered\"" in it || "best-matching photo is attached" in it } == true
                        if (wasRead) read++
                        val found = Regex("\"found\":(\\d+)").find(photoCall?.result.orEmpty())?.groupValues?.get(1)
                        val text = reply.toString()
                        val value = case.kind?.let { kind -> SensitiveText.find(text).firstOrNull { it.kind == kind }?.let { text.substring(it.start, it.end) } }
                        value?.let { values.getOrPut(case.group) { ArrayList() } += hash(it) }
                        val cardCheck = if (case.kind == SensitiveText.Kind.CARD) " luhn=${longDigitRuns(text).map(::luhn)}" else ""
                        if (followUp != null && failure == null) {
                            chats.addMessage(chatId = chatId, role = Role.ASSISTANT, text = text)
                            val next = chats.addMessage(chatId = chatId, role = Role.USER, text = followUp)
                            val asked = System.currentTimeMillis()
                            var first: Long? = null
                            val said = StringBuilder()
                            turns.run(chatId, next, followUp).collect { if (it is TurnEvent.Text) { if (first == null) first = System.currentTimeMillis(); said.append(it.delta) } }
                            conversations.forget(chatId)
                            report("  follow-up \"$followUp\": first words after ${"%.1f".format(((first ?: asked) - asked) / 1000.0)} s, done after ${"%.1f".format((System.currentTimeMillis() - asked) / 1000.0)} s | ${mask(said.toString()).replace('\n', ' ').take(200)}")
                        }
                        report(
                            "run $run [${case.group}] \"${case.question}\" ${"%.1f".format(seconds)} s | " +
                                "calls ${calls.map { "${it.tool}(${maskArgs(it.arguments)})" }} found=$found read=$wasRead | " +
                                (case.kind?.let { "value ${value?.let { v -> "${mask(v)} #${hash(v)}" } ?: "NONE"}$cardCheck | " } ?: "") +
                                (failure?.let { "FAILED ${it.message} | " } ?: "") +
                                mask(text).replace('\n', ' ').take(500),
                        )
                    }
                }
                report("read a photo on $read of $asked turns")
                values.forEach { (group, hashes) -> report("$group: ${hashes.size} values, ${hashes.toSet().size} distinct ${hashes.groupingBy { it }.eachCount()}") }
            }
        } finally {
            scope.cancel()
            database.close()
        }
    }

    /**
     * Why a number came out wrong, without looking at the photo: for each document, the photo the
     * app reads for it is described by the model (no numbers), then its number is read on its own
     * in short conversations of their own, three times as the app sends it and twice sharper.
     *
     *     adb shell am instrument -w -e eval photoprobe -e class com.local.assistant.gallery.PhotoReadingEvalTest \
     *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun readsNumbersOnTheirOwn() = runBlocking<Unit> {
        assumeTrue("Loads the real model; run with -e eval photoprobe", InstrumentationRegistry.getArguments().getString("eval") == "photoprobe")
        val container = context.appContainer
        var lastRead: Long? = null
        val photos = PhotoTools(
            container.galleryIndex,
            ready = container::photoSearchReady,
            readable = { mediaId ->
                lastRead = mediaId
                withContext(Dispatchers.IO) { container.photoLibrary.jpeg(mediaId, 1024)?.let { save(it, "probe-1024.jpg") } }
            },
        )
        data class Target(val name: String, val query: String, val field: String, val kind: SensitiveText.Kind)
        val targets = listOf(
            Target("card", "credit card", "the card number", SensitiveText.Kind.CARD),
            Target("pan", "PAN card", "the Permanent Account Number (PAN)", SensitiveText.Kind.PAN),
            Target("passport", "passport", "the passport number", SensitiveText.Kind.PASSPORT),
            Target("licence", "driving licence", "the driving licence number", SensitiveText.Kind.DRIVING_LICENCE),
            Target("aadhaar", "Aadhaar card", "the Aadhaar number", SensitiveText.Kind.AADHAAR),
        )
        container.conversations.withModelFree {
            check(container.llmBackend.ensureReady()) { "No model loaded" }
            report("probe; fp32 digits: ${container.llmService.exactDigits}")
            for (target in targets) {
                lastRead = null
                val found = photos.find(target.query, null, question = "what is ${target.field}")
                val normal = found.image ?: run { report("[${target.name}] nothing to read: ${found.result}"); null } ?: continue
                val mediaId = lastRead!!
                val sharp = container.photoLibrary.jpeg(mediaId, 2048)?.let { save(it, "probe-2048.jpg") }
                report("[${target.name}] photo $mediaId (of ${found.result["found"]}): ${mask(ask(DESCRIBE, normal)).replace('\n', ' ')}")
                if (target.kind == SensitiveText.Kind.CARD) report("[${target.name}] count: ${ask(COUNT, normal).trim()}")
                val reads = List(3) { "1024" to normal } + List(2) { "2048" to (sharp ?: normal) }
                for ((size, path) in reads) {
                    val value = ask("Copy ${target.field} from this image exactly as printed, every character. Reply with only the value.", path).trim()
                    val shape = SensitiveText.find(if (target.kind == SensitiveText.Kind.PASSPORT) "passport $value" else value).map { it.kind }
                    val digits = value.count(Char::isDigit)
                    report("[${target.name}] $size px: ${mask(value)} #${hash(value)} ${value.count(Char::isLetterOrDigit)} chars, $digits digits, recognised as $shape")
                }
            }
        }
    }

    /**
     * A reader for the found photo, tried on every question: the document named, then the answer,
     * in a short conversation of its own; and the card number read group by group.
     *
     *     adb shell am instrument -w -e eval photoreader -e class com.local.assistant.gallery.PhotoReadingEvalTest \
     *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun readerAnswers() = runBlocking<Unit> {
        assumeTrue("Loads the real model; run with -e eval photoreader", InstrumentationRegistry.getArguments().getString("eval") == "photoreader")
        val container = context.appContainer
        val photos = PhotoTools(
            container.galleryIndex,
            ready = container::photoSearchReady,
            readable = { mediaId -> withContext(Dispatchers.IO) { container.photoLibrary.jpeg(mediaId, 1024)?.let { save(it, "reader-$mediaId.jpg") } } },
        )
        container.conversations.withModelFree {
            check(container.llmBackend.ensureReady()) { "No model loaded" }
            // The queries the model sent for these in the chat run.
            val queries = mapOf(
                "licence" to "driving licence", "licence-expiry" to "driving licence", "pan" to "PAN card", "pan-dob" to "PAN card",
                "card" to "credit card", "card-expiry" to "credit card", "aadhaar" to "Aadhaar card", "passport" to "passport", "blood" to "blood group",
            )
            for (case in cases.distinctBy { it.question }) {
                val found = photos.find(queries[case.group], null, question = case.question)
                val path = found.image ?: run { report("[${case.group}] \"${case.question}\" nothing read: ${found.result}"); null } ?: continue
                repeat(2) {
                    val said = ask("My question: \"${case.question}\"", path, system = READER)
                    report("[${case.group}] \"${case.question}\" ${File(path).name} | ${mask(said).replace("\n", " / ")}")
                }
                if (case.group == "card") repeat(3) {
                    val groups = ask(GROUPS, path).trim()
                    val digits = groups.filter(Char::isDigit)
                    report("[card] by group: ${mask(groups).replace("\n", " / ")} -> ${digits.length} digits, luhn ${luhn(digits)} #${hash(digits)}")
                }
                if (case.group == "passport") report("[passport] title: ${mask(ask(TITLE, path)).trim()}")
            }
        }
    }

    /** One question about the image in a short conversation of its own, as `ImageDescriber` asks. */
    private suspend fun ask(prompt: String, path: String, system: String = "You are a careful assistant."): String {
        val backend = context.appContainer.llmBackend
        val reply = StringBuilder()
        backend.openChat(ChatSpec(systemPrefix = system, history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = 200)).use { session ->
            session.send(prompt, PromptAttachment(path, AttachmentKind.IMAGE)).collect { event ->
                when (event) {
                    is GenEvent.TextDelta -> reply.append(event.text)
                    is GenEvent.Error -> throw event.cause
                    else -> Unit
                }
            }
        }
        return reply.toString()
    }

    private fun save(bytes: ByteArray, name: String): String = File(context.cacheDir, name).apply { writeBytes(bytes) }.absolutePath

    /** Made-up numbers in the shapes a read can take: none may reach the log with more than two digits showing. */
    @org.junit.Before
    fun masksEveryShape() {
        for (sample in listOf("1234\n5678\n9012", "1234 / 5678 / 9012 / 3456", "4111 1111 1111 1111", "ABCDE1234F", "KL07 20110012345", "12/05/2030", "P1234567")) {
            val shown = mask(sample).count(Char::isDigit)
            check(shown <= 2) { "masking shows $shown digits of a sample" }
        }
    }

    /** Masked once more as a whole, whatever was masked before, so no line can carry a number read. */
    private fun report(line: String) {
        val safe = mask(line)
        Log.i(TAG, safe)
        out.appendText("$safe\n")
    }

    private companion object {
        const val TAG = "PhotoReadingEval"
        const val TURN_TIMEOUT_MS = 180_000L

        const val DESCRIBE = "What kind of document or object is this a photo of, and how much of the photo does it fill? " +
            "One short sentence. Do not read out any numbers, names or dates."
        const val COUNT = "How many digits are in the long number printed across this card, and in how many groups? Reply only like: 16 digits in 4 groups."

        val READER = """
            You read a photo from the user's own gallery to answer their question. Reply in exactly this form:
            Document: what the photo shows, by the name printed on it if it has one (for example "PAN card", "SSLC mark sheet", "credit card, back")
            Answer: the answer to their question, read from the photo exactly as printed, in a sentence or two; if the photo doesn't show it, say so.
        """.trimIndent()

        const val GROUPS = "Read the long card number on this card group by group, exactly as printed: one group of digits per line, nothing else."

        const val TITLE = "What is the title or heading printed on this document? Reply with just that."

        /** A short fingerprint of a value read, for telling runs apart without logging it. */
        fun hash(value: String): String {
            val normal = value.uppercase().filter { it.isLetterOrDigit() }
            // Letters only, so the masking leaves it alone.
            return MessageDigest.getInstance("SHA-256").digest(normal.toByteArray()).take(3)
                .joinToString("") { b -> "${'a' + (b.toInt() shr 4 and 15)}${'a' + (b.toInt() and 15)}" }
        }

        /**
         * [text] safe to log, recognised or not (a misread card number fails Luhn; a misread PAN
         * loses its shape): any group of five or more digits, and any code-like word with three or
         * more digits in it, down to its last two characters.
         */
        fun mask(text: String): String {
            // Not the app's own masking (SensitiveText.masked), which leaves a PAN's last four showing.
            val groups = DIGIT_GROUP.replace(text) { hide(it.value) }
            return CODE.replace(groups) { m -> m.value.let { if (it.count(Char::isDigit) >= 3 || (it.length >= 8 && it.any(Char::isDigit))) hide(it) else it } }
        }

        private fun hide(value: String): String =
            value.dropLast(2).map { if (it.isLetterOrDigit()) '•' else it }.joinToString("") + value.takeLast(2)

        fun maskArgs(arguments: Map<String, Any?>): String = arguments.entries.joinToString(", ") { (k, v) -> "$k=${mask(v.toString())}" }

        /**
         * Five or more digits with at most a few non-alphanumerics between each: "4111 1111 1111
         * 1111", "12/05/2030", and groups read one per line ("4111\n1111\n…"), which a pattern
         * allowing only a space between let through unmasked.
         */
        private val DIGIT_GROUP = Regex("\\d(?:[^A-Za-z\\d]{0,4}\\d){4,}")

        private val CODE = Regex("[A-Za-z0-9-]*\\d[A-Za-z0-9-]*")

        fun longDigitRuns(text: String): List<String> =
            Regex("\\d[\\d -]{10,}\\d").findAll(text).map { it.value.filter(Char::isDigit) }.filter { it.length in 12..19 }.toList()

        fun luhn(digits: String): Boolean {
            var sum = 0
            digits.reversed().forEachIndexed { i, c ->
                var d = c - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }
    }

    private object NoReminders : ReminderScheduler {
        override fun canScheduleExact() = true
        override fun schedule(taskId: Long, dueAt: Long) = Unit
        override fun cancel(taskId: Long) = Unit
        override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) = Unit
        override fun cancelEvent(eventId: Long) = Unit
    }

    private object NoAlarms : SystemAlarms {
        override fun available() = false
        override fun set(hour: Int, minute: Int, days: Set<DayOfWeek>, label: String?) = false
        override fun setTimer(seconds: Int, label: String?) = false
        override fun openClock() = Unit
    }
}
