package com.local.assistant.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Where to set the bar for sending photos with every question (`PhotoRecall`): how the best photos
 * score for whole questions, those the gallery should answer and those it shouldn't, and whether
 * the model, shown each of the two best, says it helps. Nobody looks at the photos: the model
 * answers yes or no, and only scores, ids and those answers are logged.
 *
 *     adb shell am instrument -w -e eval photorecall -e class com.local.assistant.gallery.PhotoRecallCalibrationTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to logcat (tag PhotoRecallCalibration) and `Android/data/<app>/files/photo-recall-calibration.txt`.
 */
@RunWith(AndroidJUnit4::class)
class PhotoRecallCalibrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "photo-recall-calibration.txt").apply { writeText("") } }

    /** Asked on 30 Oct 2026, answered without the photos that had it; and others the photos hold. */
    private val gallery = listOf(
        "did I ever participate in push to prod hackathon?",
        "when is corniche resto's anniversary celebration",
        "what was my SSLC score?",
        "what is my PAN number?",
        "when does my driving licence expire?",
        "what's my Aadhaar number?",
        "when does my credit card expire?",
        "what tests did the doctor advise?",
        "what did I score in maths in plus two?",
        "what's on my ceraglow care card?",
        "have I been to munnar?",
        "what did we eat at the beach?",
        "do I have any photos with shiyas?",
        "what was my CGPA in 10th?",
        "what's my date of birth?",
        "which hackathons have I been to?",
    )

    /** Nothing in anyone's photos answers these. */
    private val general = listOf(
        "what is the capital of France?",
        "how do I make masala chai?",
        "explain recursion in simple words",
        "who won the cricket world cup in 2011?",
        "what's a good name for a cat?",
        "how far is the moon from the earth?",
        "tell me a joke",
        "why is the sky blue?",
        "how do I reverse a linked list in kotlin?",
        "what should I cook for dinner tonight?",
        "what does API stand for?",
        "how many days are there in a leap year?",
        "is coffee bad for health?",
        "translate good morning into malayalam",
        "what's the difference between RAM and ROM?",
        "can you help me write an email to my manager asking for leave?",
        "what is the meaning of life?",
        "how do I improve my sleep?",
        "summarize the plot of inception",
        "what is 15 percent of 2400?",
        "what's the best way to learn guitar?",
        "who is the prime minister of india?",
        "how does a vaccine work?",
        "what are some good exercises for back pain?",
        "how was your day?",
        "what did we talk about yesterday?",
        "what is my wife's name?",
        "when is my next meeting?",
        "what's the weather like today?",
        "recommend a good book on productivity",
    )

    @Test
    fun scoresAndHelpfulness() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Loads the photo and chat models; run with -e eval photorecall", args.getString("eval") == "photorecall")
        // "-e scores only": how the best photo stands against the rest, without asking the model.
        val scoresOnly = args.getString("scores") == "only"
        val container = context.appContainer
        check(container.photoSearchReady()) { "Photo search isn't set up on this phone" }
        val index = container.galleryIndex
        val videos = index.videoIds().toHashSet()
        val files = ArrayList<File>()
        try {
            container.conversations.withModelFree {
                check(container.llmBackend.ensureReady()) { "No model loaded" }
                for ((set, questions) in listOf("gallery" to gallery, "general" to general)) {
                    for (question in questions) {
                        val started = System.currentTimeMillis()
                        val all = index.scoreAll(question)?.filter { it.mediaId !in videos && it.atMs == null } ?: error("Photo model won't load")
                        val best = all.take(3)
                        val ms = System.currentTimeMillis() - started
                        if (scoresOnly) {
                            val mean = all.map { it.similarity.toDouble() }.average()
                            val sd = kotlin.math.sqrt(all.sumOf { (it.similarity - mean) * (it.similarity - mean) } / all.size)
                            val z = (best[0].similarity - mean) / sd
                            val tenth = all[9].similarity
                            val sends = PhotoRecall.passes(all.take(PhotoRecall.COMPARED).map { it.similarity })
                            report("$set | ${if (sends) "SENDS" else "none "} | ${best.joinToString(" ") { "%.3f".format(it.similarity) }} | mean %.3f sd %.3f z %.1f | 1st-10th %.4f | $question".format(mean, sd, z, best[0].similarity - tenth))
                            continue
                        }
                        val helps = best.take(2).map { hit ->
                            val bytes = withContext(Dispatchers.IO) { container.photoLibrary.jpeg(hit.mediaId, 1024) } ?: return@map "?"
                            val file = File(context.cacheDir, "recall-calibration.jpg").apply { writeBytes(bytes) }.also(files::add)
                            ask("A photo from my gallery. Does it help answer this question: \"$question\"? Reply with only yes or no.", file.path)
                                .trim().lowercase().takeWhile { it.isLetter() }
                        }
                        report("$set | ${best.joinToString(" ") { "%.3f".format(it.similarity) }} | helps ${helps.joinToString("/")} | ${ms} ms | $question")
                    }
                }
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    /**
     * Questions through the app's own chat sender, with the user's real memory, in hidden chats
     * deleted afterwards: whether the photos went with each (the rule, applied again here) and
     * what came back, with numbers masked; and what memory recall costs a command, which no longer
     * gets it (nothing is sent for those: no alarm is set, no one is called).
     *
     *     adb shell am instrument -w -e eval photoanswers -e class com.local.assistant.gallery.PhotoRecallCalibrationTest \
     *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun answersThroughTheApp() = runBlocking<Unit> {
        assumeTrue("Sends real chat turns; run with -e eval photoanswers", InstrumentationRegistry.getArguments().getString("eval") == "photoanswers")
        val container = context.appContainer
        val questions = listOf(
            // First, so loading the model after the test's restart isn't counted against a photo question.
            "tell me a joke",
            "did I ever participate in push to prod hackathon?",
            "when is corniche resto's anniversary celebration",
            "which hackathons have I been to?",
            "what tests did the doctor advise?",
            "what is the capital of France?",
        )
        for (question in questions) {
            val best = container.galleryIndex.bestPhotos(question, PhotoRecall.COMPARED).orEmpty()
            val sends = PhotoRecall.passes(best.map { it.similarity })
            val created = kotlinx.coroutines.CompletableDeferred<Long>()
            val finished = kotlinx.coroutines.CompletableDeferred<String>()
            val started = System.currentTimeMillis()
            check(container.chatSender.send(null, question, null, this@PhotoRecallCalibrationTest, hidden = true, listener = object : com.local.assistant.chat.ChatSender.Listener {
                override fun onChatCreated(chatId: Long) { created.complete(chatId) }
                override fun onFinished(reply: String, failure: String?) { finished.complete(reply + (failure?.let { " [failed: $it]" } ?: "")) }
            })) { "the sender was busy" }
            val chatId = kotlinx.coroutines.withTimeout(30_000) { created.await() }
            val reply = kotlinx.coroutines.withTimeout(180_000) { finished.await() }
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            val stats = container.llmService.lastGenerationStats.value
            val tools = container.chatRepository.messagesFor(chatId).filter { it.role == com.local.assistant.data.db.Role.TOOL }
                .mapNotNull { com.local.assistant.memory.tools.ChatToolLog.parse(it.text)?.tool }
            container.chatRepository.deleteChat(chatId)
            container.conversations.forget(chatId)
            report("${if (sends) "photos" else "none  "} | %.1f s, last reading %d tokens | tools $tools | $question | ${mask(reply).replace("\n", " ").take(300)}".format(seconds, stats?.prefillTokens ?: -1))
        }

        // What a command's recall costs, as the app's retriever runs it.
        val retriever = com.local.assistant.memory.retrieval.Retriever(
            facts = container.memoryRepository,
            archive = container.archive,
            embedder = container.embedder,
            notes = container.savedImages,
        )
        val budget = com.local.assistant.memory.prompt.MemoryBudget.EIGHT_K
        for (command in listOf(
            "set an alarm for 6 am tomorrow", "remind me to call amma at 7 pm", "call shiyas", "text priya I'm running late",
            "open youtube", "turn on the flashlight", "set a timer for 10 minutes", "add milk to my shopping list",
        )) {
            check(com.local.assistant.memory.retrieval.QueryText.isCommand(command)) { "not taken for a command: $command" }
            val started = System.nanoTime()
            val recall = retriever.recall(-1, command, Long.MAX_VALUE, budget.maxEnvelopeFacts, budget.maxSnippets)
            val ms = (System.nanoTime() - started) / 1_000_000
            val tokens = recall.snippets.sumOf { container.tokenEstimator.estimate(it.text) } + recall.facts.sumOf { container.tokenEstimator.estimate(it.value) + 4 }
            report("command | recall $ms ms: ${recall.facts.size} facts, ${recall.snippets.size} snippets, about $tokens tokens | $command")
        }
    }

    /** Groups of five or more digits, down to their last two. */
    private fun mask(text: String): String =
        Regex("\\d(?:[^A-Za-z\\d]{0,4}\\d){4,}").replace(text) { m -> m.value.dropLast(2).map { if (it.isDigit()) '•' else it }.joinToString("") + m.value.takeLast(2) }

    private suspend fun ask(prompt: String, path: String): String {
        val reply = StringBuilder()
        context.appContainer.llmBackend.openChat(
            ChatSpec(systemPrefix = "You are a careful assistant.", history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = 8),
        ).use { session ->
            session.send(prompt, PromptAttachment(path, AttachmentKind.IMAGE)).collect { if (it is GenEvent.TextDelta) reply.append(it.text) }
        }
        return reply.toString()
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        out.appendText("$line\n")
    }

    private companion object {
        const val TAG = "PhotoRecallCalibration"
    }
}
