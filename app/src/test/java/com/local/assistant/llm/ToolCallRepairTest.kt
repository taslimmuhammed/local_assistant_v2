package com.local.assistant.llm

import com.local.assistant.memory.tools.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallRepairTest {

    private val repair = ToolCallRepair(ToolCatalog.declarations(web = true))

    /** As the runtime words it, the rejected block followed by the whole response. */
    private fun failure(call: String) =
        "failed: Status Code: 3. Message: Failed to parse tool calls from code block: $call full response: <|tool_call>$call<tool_call|>"

    @Test
    fun theFullResponseAfterALineBreakIsIgnored() {
        val call = "call:send_message{app:<|\"|>email<|\"|>,<|\"|>to:landlord<|\"|>,text:<|\"|>The kitchen tap is leaking.<|\"|>}"
        val message = "Failed to parse tool calls from code block: $call\nfull response: <|tool_call>$call<tool_call|>"
        assertEquals("landlord", repair.repair(message)?.arguments?.get("to"))
    }

    @Test
    fun `a key quoted with its value`() {
        val call = repair.repair(failure("call:send_message{app:<|\"|>email<|\"|>,<|\"|>to:landlord<|\"|>,text:<|\"|>The kitchen tap is leaking.<|\"|>}"))
        assertEquals(ToolCall("send_message", mapOf("app" to "email", "to" to "landlord", "text" to "The kitchen tap is leaking.")), call)
    }

    @Test
    fun `a value with its key left out goes to the next parameter`() {
        assertEquals(
            ToolCall("add_task", mapOf("title" to "Renew passport", "when" to "next month")),
            repair.repair(failure("call:add_task{title:<|\"|>Renew passport<|\"|>, next month<|\"|>}")),
        )
        assertEquals(
            ToolCall("add_task", mapOf("title" to "Renew passport", "when" to "next month")),
            repair.repair(failure("call:add_task{title:<|\"|>Renew passport<|\"|>, next_month}")),
        )
    }

    @Test
    fun `a well formed call reads as it is, numbers and booleans included`() {
        assertEquals(
            ToolCall("save_fact", mapOf("subject" to "user", "attribute" to "reply_style", "value" to "short answers", "core" to true)),
            repair.repair(failure("call:save_fact{subject:<|\"|>user<|\"|>,attribute:<|\"|>reply_style<|\"|>,value:<|\"|>short answers<|\"|>,core:true}")),
        )
    }

    @Test
    fun `unknown tools, unknown parameters and too many loose values are not guessed at`() {
        assertNull(repair.repair(failure("call:book_cab{to:<|\"|>airport<|\"|>}")))
        assertNull(repair.repair(failure("call:set_timer{<|\"|>10 minutes<|\"|>, pasta, extra, more}")))
        assertNull(repair.repair("Some other error"))
    }
}
