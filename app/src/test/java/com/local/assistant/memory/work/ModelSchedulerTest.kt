package com.local.assistant.memory.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

class ModelSchedulerTest {

    private var now = 100_000L
    private val scheduler = ModelScheduler(clock = { now })

    @Test
    fun `the user's generation pre-empts background work holding the model`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val background = async {
            scheduler.runBackground(ModelScheduler.Priority.EMBEDDING_BACKLOG) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val answer = withTimeout(2_000) { scheduler.runUser { "reply" } }
        assertEquals("reply", answer)
        assertNull("pre-empted work reports null so its owner retries", background.await())
    }

    @Test
    fun `background work does not start while the user is typing`() = runBlocking {
        scheduler.onUserActivity()
        assertNull(scheduler.runBackground(ModelScheduler.Priority.NIGHTLY) { "ran" })
        now += ModelScheduler.QUIET_MS
        assertEquals("ran", scheduler.runBackground(ModelScheduler.Priority.NIGHTLY) { "ran" })
    }

    @Test
    fun `typing cancels background work already running`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val background = async {
            scheduler.runBackground(ModelScheduler.Priority.COMPACTION) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        scheduler.onUserActivity()
        assertNull(withTimeout(2_000) { background.await() })
    }

    @Test
    fun `waiting jobs run by priority, not arrival order`() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val holder = launch { scheduler.runBackground(ModelScheduler.Priority.NIGHTLY) { gate.await() } }
        delay(50)
        val low = launch { scheduler.runBackground(ModelScheduler.Priority.EMBEDDING_BACKLOG) { order += "backlog" } }
        delay(20)
        val high = launch { scheduler.runBackground(ModelScheduler.Priority.QUERY_EMBEDDING) { order += "query" } }
        delay(20)
        gate.complete(Unit)
        listOf(holder, low, high).forEach { it.join() }
        assertEquals(listOf("query", "backlog"), order)
    }

    @Test
    fun `a failing job releases the model`() = runBlocking {
        runCatching { scheduler.runUser { error("boom") } }
        assertEquals("next", withTimeout(2_000) { scheduler.runUser { "next" } })
    }
}
