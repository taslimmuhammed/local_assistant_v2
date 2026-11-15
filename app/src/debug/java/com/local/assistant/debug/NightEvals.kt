package com.local.assistant.debug

import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.local.assistant.appContainer
import dalvik.system.PathClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The phone evals, run by the app itself from the "Night evals" icon (debug builds only), for a
 * night away from the computer. `am instrument` needs adb, and Android lets no app start
 * instrumentation on a user build, so the installed test APK's classes are loaded into the app's
 * own process and run with JUnit, each with the arguments `-e` would have given it
 * ([AppInstrumentation] stands in for the runner's instrumentation).
 *
 * Unlike the computer's runner the evals share one process, so each starts with the app's models
 * let go, as in a fresh one ([freeModels]), and the riskiest (model reloads, two conversations,
 * another precision) go last: a native crash there closes the app and ends the run, and the page
 * then offers to resume after it. Between evals it waits for the page to be on
 * screen and unlocked, for the phone to cool to thermal status "light" or less (up to 15 min), as
 * timings on a hot phone aren't comparable, and it pauses by itself below 20% battery unless
 * charging. [pause] stops the eval under way at once; [resume] runs it again from its start.
 *
 * Results: files/night/<start time>/<eval>/ (the eval's own report files, moved there) and
 * summary.txt, in the app's external storage.
 */
object NightEvals {

    data class Spec(val name: String, val test: String, val args: Map<String, String>)

    val SPECS = listOf(
        Spec("instant", "com.local.assistant.memory.prompt.InstantCommandEvalTest", mapOf("eval" to "instant")),
        Spec("tooltokens", "com.local.assistant.llm.ToolResponseProbeTest#toolTokens", mapOf("probe" to "tooltokens")),
        Spec("recall", "com.local.assistant.memory.MemoryEvalTest#recallTiming", mapOf("eval" to "memory")),
        Spec("honesty", "com.local.assistant.llm.HonestyEvalTest", mapOf("eval" to "honesty")),
        Spec("multistep", "com.local.assistant.llm.MultiStepEvalTest", mapOf("eval" to "multistep")),
        // The tools main had before files and messages, to compare with earlier runs.
        Spec("routing", "com.local.assistant.llm.RoutingEvalTest", mapOf("eval" to "true", "tools" to "basic")),
        // Without "blank": that case sends the phone Home, which would leave this page.
        Spec("latency", "com.local.assistant.assist.AssistLatencyEvalTest", mapOf("eval" to "latency")),
        Spec("routingall", "com.local.assistant.llm.RoutingEvalTest", mapOf("eval" to "true")),
        Spec("loadlog", "com.local.assistant.llm.ToolResponseProbeTest#loadWithRuntimeLog", mapOf("probe" to "loadlog")),
        Spec("clone", "com.local.assistant.llm.ToolResponseProbeTest#cloneCost", mapOf("probe" to "clone")),
        Spec("activation", "com.local.assistant.memory.MemoryEvalTest#activationModes", mapOf("eval" to "memory")),
    )

    enum class Outcome { WAITING, RUNNING, PASSED, FAILED, SKIPPED, CLOSED_THE_APP }

    data class Row(val spec: Spec, val outcome: Outcome = Outcome.WAITING, val detail: String = "", val lines: List<String> = emptyList())

    data class State(
        val running: Boolean = false,
        val status: String = "Not started",
        val rows: List<Row> = SPECS.map { Row(it) },
        val folder: File? = null,
        val paused: Boolean = false,
    ) {
        /** A run that stopped part way: it goes on, and an eval the app closed during is run again. */
        val canContinue get() = !running && folder != null && rows.any { it.outcome == Outcome.WAITING || it.outcome == Outcome.CLOSED_THE_APP }
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState

    @Volatile
    private var worker: Thread? = null

    /** An eval is under way on [worker], so a pause interrupts it. */
    @Volatile
    private var inEval = false

    /** The eval under way was interrupted by [pause]: it goes back in the queue. */
    @Volatile
    private var pausing = false

    /** Stops the eval under way at once (it runs again from its start on [resume]) and holds the rest. */
    fun pause() {
        val state = mutableState.value
        if (!state.running || state.paused) return
        mutableState.value = state.copy(paused = true, status = "Paused")
        if (inEval) {
            pausing = true
            worker?.interrupt()
        }
    }

    /** Goes on from where it paused, also after the app closed. */
    fun resume(context: Context) {
        val state = mutableState.value
        when {
            state.running -> if (state.paused) mutableState.value = state.copy(paused = false, status = "Resuming")
            state.canContinue -> start(context, carryOn = true)
        }
    }

    /** Set by the page: evals start only while it's on screen. */
    @Volatile
    var onScreen = false

    /** The last run's rows, read back from its folder when the page opens; a check from adb never hides a run. */
    fun load(context: Context) {
        if (mutableState.value.running) return
        val folders = runs(context).sortedByDescending { it.name }
        val folder = folders.firstOrNull { !File(it, CHECK_MARK).exists() } ?: folders.firstOrNull() ?: return
        val rows = readProgress(folder)
        val closed = rows.filter { it.outcome == Outcome.CLOSED_THE_APP }.map { it.spec.name }
        writeProgress(folder, rows)
        mutableState.value = State(
            status = when {
                closed.isNotEmpty() -> "Stopped: the app closed during ${closed.joinToString()}; Resume runs ${if (closed.size == 1) "it" else "them"} again (${folder.name})"
                rows.all { it.outcome != Outcome.WAITING } -> "Finished: ${folder.name}"
                else -> "Stopped part way (${folder.name})"
            },
            rows = rows,
            folder = folder,
        )
    }

    /** [only]: a few evals by name, for a short check from adb (`--es only tooltokens,instant --el at <epoch ms>`). */
    fun start(context: Context, carryOn: Boolean, only: Set<String>? = null) {
        if (mutableState.value.running) return
        val app = context.applicationContext
        val previous = mutableState.value.takeIf { carryOn && it.canContinue }
        val folder = previous?.folder ?: File(app.getExternalFilesDir(null), "night/" + STAMP.format(Date())).apply { mkdirs() }
        // Report files left from an earlier run, so they aren't taken for the first eval's.
        reportFiles(app).forEach { it.renameTo(File(File(folder, "earlier").apply { mkdirs() }, it.name)) }
        if (only != null) File(folder, CHECK_MARK).createNewFile()
        // An eval the app closed during goes back in the queue, its earlier start kept in the detail.
        val rows = previous?.rows?.map { if (it.outcome == Outcome.CLOSED_THE_APP) it.copy(outcome = Outcome.WAITING, detail = "closed the app (${it.detail}); again") else it }
        mutableState.value = State(running = true, status = "Starting", rows = rows ?: SPECS.filter { only == null || it.name in only }.map { Row(it) }, folder = folder)
        writeProgress(folder)
        worker = thread(name = "night-evals") {
            try {
                runAll(app, folder)
            } catch (e: Throwable) {
                Log.e(TAG, "Night run stopped", e)
                update(status = "Stopped: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            } finally {
                mutableState.value = mutableState.value.copy(running = false, paused = false)
                writeSummary(folder)
            }
        }
    }

    private fun runAll(app: Context, folder: File) {
        val testPackage = app.packageName + ".test"
        val tests = app.createPackageContext(testPackage, 0)
        val loader = PathClassLoader(app.packageManager.getApplicationInfo(testPackage, 0).sourceDir, app.classLoader)
        val began = SystemClock.elapsedRealtime()
        var index = 0
        while (index < mutableState.value.rows.size) {
            val row = mutableState.value.rows[index]
            if (row.outcome != Outcome.WAITING) {
                index++
                continue
            }
            waitUntilReady(app, row.spec.name)
            freeModels(app)
            val conditions = "thermal ${thermal(app)}, battery ${battery(app)}% ${batteryTemp(app)} °C, PSS ${Debug.getPss() / 1024} MB"
            setRow(index, row.copy(outcome = Outcome.RUNNING, detail = "started ${CLOCK.format(Date())}, $conditions"), folder)
            update(status = "Running ${index + 1} of ${mutableState.value.rows.size}: ${row.spec.name}")
            Log.i(TAG, "${row.spec.name}: started, $conditions")
            val started = SystemClock.elapsedRealtime()
            Thread.interrupted() // a pause while waiting can leave the flag set
            inEval = true
            val (outcome, note) = try {
                runOne(app, tests, loader, row.spec)
            } catch (e: Throwable) {
                Outcome.FAILED to "${e.javaClass.simpleName}: ${e.message?.take(300)}"
            } finally {
                inEval = false
            }
            if (pausing) {
                pausing = false
                Thread.interrupted()
                // What it wrote before the pause, kept aside; it runs again from its start.
                val partial = File(folder, "${row.spec.name}-paused-${CLOCK.format(Date()).replace(":", "")}").apply { mkdirs() }
                reportFiles(app).forEach { it.renameTo(File(partial, it.name)) }
                setRow(index, row.copy(detail = "paused at ${CLOCK.format(Date())}: runs again from its start"), folder)
                Log.i(TAG, "${row.spec.name}: paused")
                continue
            }
            val minutes = "%.1f min".format((SystemClock.elapsedRealtime() - started) / 60_000.0)
            val reports = File(folder, row.spec.name).apply { mkdirs() }
            reportFiles(app).forEach { it.renameTo(File(reports, it.name)) }
            setRow(index, row.copy(outcome = outcome, detail = "$minutes, $conditions" + (note?.let { " · $it" } ?: ""), lines = headline(reports)), folder)
            Log.i(TAG, "${row.spec.name}: $outcome in $minutes")
            writeSummary(folder)
            index++
        }
        update(status = "Finished at ${CLOCK.format(Date())}, after ${(SystemClock.elapsedRealtime() - began) / 60_000} min")
    }

    /** One eval through JUnit, from the test APK's own copy of it, with its `-e` arguments. */
    private fun runOne(app: Context, tests: Context, loader: ClassLoader, spec: Spec): Pair<Outcome, String?> {
        val arguments = Bundle().apply { spec.args.forEach { (key, value) -> putString(key, value) } }
        loader.loadClass("androidx.test.platform.app.InstrumentationRegistry")
            .getMethod("registerInstance", Instrumentation::class.java, Bundle::class.java)
            .invoke(null, AppInstrumentation(app, tests), arguments)
        val testClass = loader.loadClass(spec.test.substringBefore('#'))
        val method = spec.test.substringAfter('#', "")
        val requests = loader.loadClass("org.junit.runner.Request")
        val request = if (method.isEmpty()) {
            requests.getMethod("aClass", Class::class.java).invoke(null, testClass)
        } else {
            requests.getMethod("method", Class::class.java, String::class.java).invoke(null, testClass, method)
        }
        val junit = loader.loadClass("org.junit.runner.JUnitCore")
        val result = junit.getMethod("run", requests).invoke(junit.getDeclaredConstructor().newInstance(), request)!!
        fun call(name: String) = result.javaClass.getMethod(name).invoke(result)
        val ran = call("getRunCount") as Int
        val skipped = call("getAssumptionFailureCount") as Int
        val failures = call("getFailures") as List<*>
        return when {
            failures.isNotEmpty() -> Outcome.FAILED to failures.joinToString(" | ") { failure ->
                val trace = failure!!.javaClass.getMethod("getTrace").invoke(failure) as String
                trace.lineSequence().take(4).joinToString(" ⏎ ").take(400)
            }
            skipped >= ran -> Outcome.SKIPPED to "its opt-in argument didn't reach it"
            else -> Outcome.PASSED to (if (skipped > 0) "$skipped of $ran skipped" else null)
        }
    }

    /**
     * The app's chat model and memory search let go before each eval. Multistep and routing load a
     * model of their own; beside the app's, loaded by the evals before them, the phone closed the
     * app in all three, in both runs of 14 Nov 2026.
     */
    private fun freeModels(app: Context) = runBlocking {
        val container = app.appContainer
        container.conversations.withModelFree { container.llmService.unload() }
        container.embedder.unload()
    }

    private fun waitUntilReady(app: Context, name: String) {
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val power = app.getSystemService(BatteryManager::class.java)
        while (true) {
            val low = battery(app) < LOW_BATTERY && !power.isCharging
            if (low && !mutableState.value.paused) mutableState.value = mutableState.value.copy(paused = true)
            update(status = when {
                low -> "Paused: battery ${battery(app)}%, below $LOW_BATTERY% and not charging (next: $name)"
                mutableState.value.paused -> "Paused (next: $name)"
                !onScreen || keyguard.isKeyguardLocked -> "Waiting for this page to be on screen, unlocked (next: $name)"
                else -> break
            })
            SystemClock.sleep(5_000)
        }
        val until = SystemClock.elapsedRealtime() + COOL_MS
        while (thermal(app) > PowerManager.THERMAL_STATUS_LIGHT && SystemClock.elapsedRealtime() < until && !mutableState.value.paused) {
            update(status = "Cooling before $name: thermal status ${thermal(app)} (waits up to 15 min)")
            SystemClock.sleep(10_000)
        }
        // Paused while it cooled: wait for the resume, and for the page, all over again.
        if (mutableState.value.paused) waitUntilReady(app, name)
    }

    private fun thermal(app: Context) = app.getSystemService(PowerManager::class.java).currentThermalStatus

    private fun battery(app: Context) = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun batteryTemp(app: Context): String {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return "%.1f".format((battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
    }

    private fun reportFiles(app: Context) = app.getExternalFilesDir(null)?.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().toList()

    /** A few lines that say how an eval went: its summary lines, else its last two. */
    private fun headline(reports: File): List<String> = reports.listFiles().orEmpty().sortedBy { it.name }.flatMap { file ->
        val lines = file.readLines().filter { it.isNotBlank() }
        val telling = lines.filter { SUMMARY.containsMatchIn(it) }.takeLast(4)
        (telling.ifEmpty { lines.takeLast(2) }).map { "${file.name}: ${it.take(220)}" }
    }

    private fun update(status: String) {
        mutableState.value = mutableState.value.copy(status = status)
    }

    private fun setRow(index: Int, row: Row, folder: File) {
        mutableState.value = mutableState.value.copy(rows = mutableState.value.rows.toMutableList().also { it[index] = row })
        writeProgress(folder)
    }

    private fun runs(context: Context) = File(context.getExternalFilesDir(null), "night").listFiles { f -> f.isDirectory }.orEmpty().toList()

    /** One line an eval: name, outcome, detail — read back when the page opens after the app closed. */
    private fun writeProgress(folder: File, rows: List<Row> = mutableState.value.rows) {
        File(folder, "progress.tsv").writeText(rows.joinToString("") { "${it.spec.name}\t${it.outcome}\t${it.detail.replace('\t', ' ').replace('\n', ' ')}\n" })
    }

    private fun readProgress(folder: File): List<Row> {
        val saved = File(folder, "progress.tsv").takeIf { it.exists() }?.readLines().orEmpty()
            .map { it.split('\t') }.filter { it.size >= 3 }.associateBy { it[0] }
        return SPECS.filter { saved.isEmpty() || it.name in saved }.map { spec ->
            val line = saved[spec.name] ?: return@map Row(spec)
            // Still "running" in the file: the app closed during it, so it isn't run again.
            val outcome = Outcome.valueOf(line[1]).let { if (it == Outcome.RUNNING) Outcome.CLOSED_THE_APP else it }
            Row(spec, outcome, line[2], headline(File(folder, spec.name)))
        }
    }

    private fun writeSummary(folder: File) {
        val state = mutableState.value
        File(folder, "summary.txt").writeText(buildString {
            appendLine("Night evals ${folder.name}: ${state.status}")
            for (row in state.rows) {
                appendLine("${row.spec.name}: ${row.outcome} ${row.detail}")
                row.lines.forEach { appendLine("    $it") }
            }
        })
    }

    /** What the evals ask of their instrumentation, from the app: its contexts and the main thread. */
    private class AppInstrumentation(private val app: Context, private val tests: Context) : Instrumentation() {
        override fun getTargetContext(): Context = app
        override fun getContext(): Context = tests
        override fun runOnMainSync(runner: Runnable) {
            if (Looper.myLooper() == Looper.getMainLooper()) return runner.run()
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { try { runner.run() } finally { done.countDown() } }
            done.await()
        }
        override fun waitForIdleSync() = runOnMainSync {}
    }

    private const val TAG = "NightEvals"
    private const val COOL_MS = 15 * 60_000L
    /** In a run's folder when it was a short check started from adb. */
    private const val CHECK_MARK = "adb-check"
    const val LOW_BATTERY = 20
    private val SUMMARY = Regex("(?i)summary|total|routed|claims|exact|first word|tokens|\\b\\d+ ?/ ?\\d+\\b")
    private val STAMP = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
    private val CLOCK = SimpleDateFormat("HH:mm", Locale.US)
}
