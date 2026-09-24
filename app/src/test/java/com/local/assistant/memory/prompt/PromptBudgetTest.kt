package com.local.assistant.memory.prompt

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.core.FactCategorizer
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptBudgetTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val budget = MemoryBudget.SIXTEEN_K
    private val estimator = HeuristicTokenEstimator
    private val assembler = PromptAssembler(budget, estimator, zone)
    private val now = ZonedDateTime.of(2026, 9, 21, 10, 42, 0, 0, zone)

    private var nextId = 1L

    private fun fact(attribute: String, value: String, core: Boolean = true, subject: String = "user") = FactEntity(
        id = nextId++,
        subject = subject,
        attribute = attribute,
        value = value,
        category = FactCategorizer.categorize(subject, attribute),
        core = core,
        origin = FactOrigin.CHAT,
        sourceMessageId = null,
        statedAt = 0,
        createdAt = 0,
        updatedAt = 0,
        lastConfirmedAt = nextId,
    )

    private fun message(role: Role, text: String) = MessageEntity(
        id = nextId++,
        chatId = 1,
        role = role,
        text = text,
        createdAt = 0,
        tokenEst = estimator.estimate(text),
    )

    private fun conversation(turns: Int, words: Int) = (1..turns).flatMap { i ->
        listOf(
            message(Role.USER, "question $i " + "lorem ".repeat(words)),
            message(Role.ASSISTANT, "answer $i " + "ipsum ".repeat(words)),
        )
    }

    private fun prefixInputs(
        core: List<FactEntity> = listOf(fact("pref.reply_style", "short answers"), fact("name", "Arjun")),
        summary: SummaryBlock? = null,
        agenda: List<AgendaItem> = emptyList(),
    ) = PrefixInputs(
        instructions = Instructions.render("You are a helpful assistant."),
        coreFacts = core,
        summary = summary,
        agendaDate = now.toLocalDate(),
        agenda = agenda,
    )

    private fun snippets(count: Int, words: Int) =
        (1..count).map { Snippet("snippet $it " + "dolor ".repeat(words), at = now.toInstant().toEpochMilli()) }

    // ---- Worst case ----

    @Test
    fun `worst-case inputs fit every profile once shed`() {
        for (profile in listOf(MemoryBudget.SIXTEEN_K, MemoryBudget.EIGHT_K, MemoryBudget.forWindow(4_096))) {
            val plan = worstCase(PromptAssembler(profile, estimator, zone), profile)
            assertTrue("${profile.name}: total ${plan.totalTokens}", plan.totalTokens <= profile.promptCeiling)
            assertTrue(
                "${profile.name}: reply and tool rounds fit",
                plan.totalTokens + profile.generationReserve + profile.toolRoundsReserve <= profile.contextTokens,
            )
            assertTrue(plan.history.tokens <= profile.historyCap)
            assertTrue(plan.prefix.summaryTokens <= profile.summaryCap)
            assertTrue(plan.prefix.agendaTokens <= profile.agendaCap)
        }
    }

    private fun worstCase(assembler: PromptAssembler, profile: MemoryBudget): PromptPlan {
        val core = (1..20).map { fact("pref.rule_$it", "always do thing number $it in a particular way") } +
            (1..60).map { fact("detail_$it", "some long remembered detail about the user, number $it") }
        return assembler.plan(
            prefixInputs = prefixInputs(
                core = core,
                summary = SummaryBlock(SummaryBlock.Kind.LAST_SESSION, "they talked about ".repeat(400)),
                agenda = (1..10).map { AgendaItem("Task number $it with a long descriptive title", now.plusDays(it.toLong()).toInstant().toEpochMilli()) },
            ),
            history = conversation(turns = 40, words = 150),
            envelopeInputs = EnvelopeInputs(
                now = now,
                showNow = true,
                facts = (1..10).map { fact("thing_$it", "value $it", core = false) },
                snippets = snippets(count = 10, words = 200),
                userText = "What should I do today?",
            ),
            toolTokens = profile.toolDeclarationsCap,
        )
    }

    @Test
    fun `worst-case inputs stay inside the ceiling and every part inside its cap`() {
        val oversizedCore = (1..20).map { fact("pref.rule_$it", "always do thing number $it in a particular way") } +
            (1..60).map { fact("detail_$it", "some long remembered detail about the user, number $it") }
        val plan = assembler.plan(
            prefixInputs = prefixInputs(
                core = oversizedCore,
                summary = SummaryBlock(SummaryBlock.Kind.LAST_SESSION, "they talked about ".repeat(400)),
                agenda = (1..10).map { AgendaItem("Task number $it with a long descriptive title", now.plusDays(it.toLong()).toInstant().toEpochMilli()) },
            ),
            history = conversation(turns = 40, words = 150),
            envelopeInputs = EnvelopeInputs(
                now = now,
                showNow = true,
                facts = (1..10).map { fact("thing_$it", "value $it", core = false) },
                snippets = snippets(count = 10, words = 200),
                userText = "What should I do today?",
            ),
            toolTokens = budget.toolDeclarationsCap,
        )

        assertTrue("total ${plan.totalTokens}", plan.totalTokens <= budget.promptCeiling)
        assertTrue("total + reply + tools must fit the window",
            plan.totalTokens + budget.generationReserve + budget.toolRoundsReserve <= budget.contextTokens)
        assertTrue(plan.prefix.summaryTokens <= budget.summaryCap)
        assertTrue(plan.prefix.agendaTokens <= budget.agendaCap)
        assertTrue(plan.history.tokens <= budget.historyCap)
        assertTrue(plan.history.messages.count { it.role == Role.USER } <= budget.maxVerbatimTurns)
        assertTrue(plan.envelope.snippets.size <= budget.maxSnippets)
        assertTrue(plan.envelope.facts.size <= budget.maxEnvelopeFacts)
        // Core had to leave facts out, and every preference is still there.
        val preferences = oversizedCore.filter { it.category == FactCategory.PREFERENCE }
        assertTrue(plan.prefix.core.included.containsAll(preferences))
        assertTrue(plan.prefix.core.omitted.isNotEmpty())
    }

    @Test
    fun `the envelope's app-added part is held to its cap, the user's text never cut`() {
        val longQuestion = "please help ".repeat(500)
        val envelope = assembler.buildEnvelope(
            EnvelopeInputs(
                now = now,
                showNow = true,
                facts = (1..5).map { fact("thing_$it", "value ".repeat(30), core = false) },
                snippets = snippets(4, 200),
                userText = longQuestion,
            ),
        )
        assertTrue(envelope.text.endsWith(longQuestion))
        val header = envelope.text.removeSuffix(longQuestion).trimEnd('\n')
        assertTrue(estimator.estimate(header) <= budget.envelopeCap)
    }

    // ---- Shedding order ----

    /** Room for everything but [spare] fewer tokens, so the plan has to shed something. */
    private fun planOverBy(spare: Int, history: List<MessageEntity>, envelope: EnvelopeInputs, summary: SummaryBlock?): PromptPlan {
        val full = assembler.plan(prefixInputs(summary = summary), history, envelope, toolTokens = 0)
        assertTrue(full.shed.isEmpty())
        val tools = budget.promptCeiling - full.totalTokens + spare
        return assembler.plan(prefixInputs(summary = summary), history, envelope, toolTokens = tools)
    }

    private val richEnvelope = EnvelopeInputs(
        now = now,
        showNow = true,
        facts = listOf(fact("dentist", "Dr. Rao", core = false), fact("ca", "Mr. Iyer", core = false)),
        snippets = snippets(3, 20),
        userText = "Call my dentist",
    )
    private val summary = SummaryBlock(SummaryBlock.Kind.LAST_SESSION, "We planned the trip to Kochi and the CA meeting.")

    @Test
    fun `a small overrun sheds snippets only`() {
        val plan = planOverBy(spare = 5, history = conversation(5, 10), envelope = richEnvelope, summary = summary)
        assertEquals(listOf(ShedStep.SNIPPETS), plan.shed)
        assertEquals(2, plan.envelope.facts.size)
        assertTrue(plan.prefix.summaryTokens > 0)
        assertEquals(0, plan.history.droppedCount)
    }

    @Test
    fun `facts go only after every snippet, and the summary after every fact`() {
        val snippetsCost = assembler.buildEnvelope(richEnvelope).tokens -
            assembler.buildEnvelope(richEnvelope.copy(snippets = emptyList())).tokens

        val factsToo = planOverBy(spare = snippetsCost + 3, history = conversation(5, 10), envelope = richEnvelope, summary = summary)
        assertEquals(listOf(ShedStep.SNIPPETS, ShedStep.FACTS), factsToo.shed)
        assertTrue(factsToo.envelope.snippets.isEmpty())
        assertTrue(factsToo.prefix.summaryTokens > 0)

        val allEnvelope = assembler.buildEnvelope(richEnvelope).tokens -
            assembler.buildEnvelope(richEnvelope.copy(snippets = emptyList(), facts = emptyList())).tokens
        val summaryToo = planOverBy(spare = allEnvelope + 3, history = conversation(5, 10), envelope = richEnvelope, summary = summary)
        assertEquals(listOf(ShedStep.SNIPPETS, ShedStep.FACTS, ShedStep.SUMMARY), summaryToo.shed)
        assertEquals(0, summaryToo.history.droppedCount)
    }

    @Test
    fun `history goes after the summary, oldest turns first, and core last`() {
        val history = conversation(8, 40)
        val plan = planOverBy(spare = 600, history = history, envelope = richEnvelope, summary = summary)
        assertEquals(listOf(ShedStep.SNIPPETS, ShedStep.FACTS, ShedStep.SUMMARY, ShedStep.HISTORY), plan.shed)
        assertEquals(0, plan.prefix.summaryTokens)
        assertEquals(history.takeLast(plan.history.messages.size), plan.history.messages)
        assertTrue(plan.prefix.core.omitted.isEmpty())
    }

    @Test
    fun `core is trimmed by priority last, keeping preferences`() {
        val core = listOf(fact("pref.reply_style", "short answers")) +
            (1..30).map { fact("detail_$it", "remembered detail number $it") }
        val inputs = prefixInputs(core = core)
        val base = assembler.plan(inputs, emptyList(), richEnvelope.copy(snippets = emptyList(), facts = emptyList()), 0)
        val plan = assembler.plan(
            inputs,
            emptyList(),
            richEnvelope.copy(snippets = emptyList(), facts = emptyList()),
            toolTokens = budget.promptCeiling - base.totalTokens + 150,
        )
        assertEquals(listOf(ShedStep.CORE), plan.shed)
        assertTrue(plan.prefix.core.included.any { it.attribute == "pref.reply_style" })
        assertTrue(plan.prefix.core.omitted.isNotEmpty())
        assertTrue(plan.totalTokens <= budget.promptCeiling)
    }

    // ---- Stability ----

    @Test
    fun `the system prefix is byte-identical across turns with different text and time`() {
        val inputs = prefixInputs(
            summary = summary,
            agenda = listOf(AgendaItem("Call the CA", now.plusDays(1).withHour(11).withMinute(0).toInstant().toEpochMilli())),
        )
        val first = assembler.plan(inputs, conversation(3, 5), EnvelopeInputs(now, true, userText = "hi"), 0)
        val later = assembler.plan(
            inputs,
            conversation(3, 5),
            EnvelopeInputs(now.plusHours(5).plusMinutes(17), true, userText = "something else entirely"),
            0,
        )
        assertEquals(first.prefix.text, later.prefix.text)
        assertFalse("no time of day in the prefix", first.prefix.text.contains("10:42"))
        assertTrue(first.prefix.text.contains("Agenda as of Mon 21 Sep 2026:\n- Tue 22 Sep 11:00 · Call the CA"))
    }

    @Test
    fun `now is shown first, then only when the day changes or fifteen minutes pass`() {
        assertTrue(assembler.shouldShowNow(null, now))
        assertFalse(assembler.shouldShowNow(now, now.plusMinutes(14)))
        assertTrue(assembler.shouldShowNow(now, now.plusMinutes(15)))
        val lateNight = now.withHour(23).withMinute(55)
        assertTrue(assembler.shouldShowNow(lateNight, lateNight.plusMinutes(6)))

        val shown = assembler.buildEnvelope(EnvelopeInputs(now, showNow = true, userText = "hi"))
        assertEquals("[Now: Mon 21 Sep 2026, 10:42]\nhi", shown.text)
        val omitted = assembler.buildEnvelope(EnvelopeInputs(now, showNow = false, userText = "hi"))
        assertEquals("hi", omitted.text)
    }

    // ---- History and live turns ----

    @Test
    fun `the history window starts at a user turn and keeps whole turns`() {
        val history = listOf(message(Role.ASSISTANT, "orphan reply")) + conversation(20, 5)
        val window = assembler.selectHistory(history, maxTurns = 12, budgetTokens = 100_000)
        assertEquals(Role.USER, window.messages.first().role)
        assertEquals(12, window.messages.count { it.role == Role.USER })
        assertEquals(history.size - window.messages.size, window.droppedCount)
    }

    @Test
    fun `a live conversation sheds envelope parts and asks for a rebuild when even that is not enough`() {
        val bare = assembler.buildEnvelope(richEnvelope.copy(snippets = emptyList(), facts = emptyList()))
        val fits = assembler.fitEnvelope(budget.promptCeiling - bare.tokens, richEnvelope)
        assertNotNull(fits)
        assertTrue(fits!!.snippets.isEmpty() && fits.facts.isEmpty())

        assertNull(assembler.fitEnvelope(budget.promptCeiling - bare.tokens + 1, richEnvelope))
    }

    // ---- Profiles ----

    @Test
    fun `each profile leaves room above its ceiling for the reply, tool rounds and slack`() {
        for (profile in listOf(MemoryBudget.SIXTEEN_K, MemoryBudget.EIGHT_K, MemoryBudget.forWindow(4_096))) {
            val slack = profile.contextTokens - profile.promptCeiling - profile.generationReserve - profile.toolRoundsReserve
            assertTrue("${profile.name}: slack $slack", slack >= profile.contextTokens * 0.05)
            assertTrue("${profile.name}: target under ceiling", profile.promptTarget <= profile.promptCeiling)
            assertTrue("${profile.name}: compaction below target", profile.compactionTrigger < profile.promptTarget)
        }
    }

    @Test
    fun `on 8K replies keep their full length`() {
        assertEquals(MemoryBudget.SIXTEEN_K.generationReserve, MemoryBudget.EIGHT_K.generationReserve)
    }

    @Test
    fun `the profile follows the measured window`() {
        assertEquals(MemoryBudget.SIXTEEN_K, MemoryBudget.forWindow(16_384))
        assertEquals(MemoryBudget.SIXTEEN_K, MemoryBudget.forWindow(32_768))
        assertEquals(MemoryBudget.EIGHT_K, MemoryBudget.forWindow(12_288))
        assertEquals(4_096, MemoryBudget.forWindow(4_096).contextTokens)
    }

    @Test
    fun `the 8K profile halves history, envelope, summary and agenda`() {
        val big = MemoryBudget.SIXTEEN_K
        val small = MemoryBudget.EIGHT_K
        assertEquals(big.historyCap / 2, small.historyCap)
        assertEquals(big.maxVerbatimTurns / 2, small.maxVerbatimTurns)
        assertEquals(big.envelopeCap / 2, small.envelopeCap)
        assertEquals(big.summaryCap / 2, small.summaryCap)
        assertEquals(big.agendaCap / 2, small.agendaCap)
        assertEquals(big.coreCap, small.coreCap)
    }
}
