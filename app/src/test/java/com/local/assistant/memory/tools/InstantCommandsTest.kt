package com.local.assistant.memory.tools

import com.local.assistant.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class InstantCommandsTest {

    private fun alarm(`when`: String) = ToolCall(ToolCatalog.SET_ALARM, mapOf("when" to `when`))
    private fun timer(duration: String) = ToolCall(ToolCatalog.SET_TIMER, mapOf("duration" to duration))
    private fun setting(setting: String, value: String) = ToolCall(ToolCatalog.PHONE_SETTING, mapOf("setting" to setting, "value" to value))

    @Test
    fun `plain alarms are set with the time words as said`() {
        assertEquals(alarm("6:30 am"), InstantCommands.match("Set an alarm for 6:30 am"))
        assertEquals(alarm("6:30 am tomorrow"), InstantCommands.match("set alarm at 6:30 am tomorrow."))
        assertEquals(alarm("5"), InstantCommands.match("Hey Luna, wake me up at 5"))
        assertEquals(alarm("7.15pm"), InstantCommands.match("can you please set an alarm 7.15pm"))
        assertEquals(alarm("6:30 a.m"), InstantCommands.match("Set an alarm for 6:30 a.m."))
        assertEquals(alarm("6"), InstantCommands.match("Can you set an alarm for 6?"))
        assertEquals(alarm("20 minutes"), InstantCommands.match("set an alarm in 20 minutes"))
        assertEquals(alarm("6 tonight"), InstantCommands.match("alarm for 6 tonight please"))
    }

    @Test
    fun `alarms with anything more go to the model`() {
        // A name, a repeat, a place, a second request, a question: the model reads those.
        assertNull(InstantCommands.match("set an alarm for gym at 6"))
        assertNull(InstantCommands.match("set an alarm for 6:30 every weekday"))
        assertNull(InstantCommands.match("set an alarm when I reach Bangalore"))
        assertNull(InstantCommands.match("set an alarm for 6 and remind me to call amma"))
        assertNull(InstantCommands.match("alarm for 6?"))
        assertNull(InstantCommands.match("what alarms do I have tomorrow"))
        assertNull(InstantCommands.match("cancel the alarm for 6"))
        assertNull(InstantCommands.match("set an alarm for 25:00"))
        assertNull(InstantCommands.match("set an alarm for 6:75"))
        assertNull(InstantCommands.match("set an alarm"))
        assertNull(InstantCommands.match("remind me at 6 to stretch"))
    }

    @Test
    fun `plain timers are set with the length as said`() {
        assertEquals(timer("10 minutes"), InstantCommands.match("Set a timer for 10 minutes"))
        assertEquals(timer("10 minute"), InstantCommands.match("10-minute timer"))
        assertEquals(timer("5 min"), InstantCommands.match("start timer 5 min"))
        assertEquals(timer("1 hour 30 minutes"), InstantCommands.match("timer for 1 hour 30 minutes"))
        assertEquals(timer("an hour and a half"), InstantCommands.match("set a timer for an hour and a half"))
        assertEquals(timer("half an hour"), InstantCommands.match("set a timer for half an hour"))
        assertEquals(timer("90 seconds"), InstantCommands.match("set a 90 seconds timer"))
    }

    @Test
    fun `timers with anything more go to the model`() {
        assertNull(InstantCommands.match("set a timer for the pasta for 10 minutes"))
        assertNull(InstantCommands.match("set a timer"))
        assertNull(InstantCommands.match("how long is left on my timer"))
        assertNull(InstantCommands.match("stop the timer"))
        assertNull(InstantCommands.match("set a timer for 10 minutes and turn on the flashlight"))
    }

    @Test
    fun `switches the app can flip, and the panels it can open`() {
        assertEquals(setting("flashlight", "on"), InstantCommands.match("Turn on the flashlight"))
        assertEquals(setting("flashlight", "off"), InstantCommands.match("torch off"))
        assertEquals(setting("flashlight", "off"), InstantCommands.match("switch the flashlight off please"))
        assertEquals(setting("do_not_disturb", "on"), InstantCommands.match("turn on do not disturb"))
        assertEquals(setting("wifi", "off"), InstantCommands.match("turn off wifi"))
        assertEquals(setting("bluetooth", "on"), InstantCommands.match("bluetooth on"))
        assertEquals(setting("ringer", "silent"), InstantCommands.match("put my phone on silent"))
        assertEquals(setting("ringer", "vibrate"), InstantCommands.match("turn on vibrate mode"))
        assertEquals(setting("ringer", "normal"), InstantCommands.match("silent mode off"))
        assertEquals(setting("ringer", "silent"), InstantCommands.match("mute my phone"))
        assertEquals(setting("volume", "up"), InstantCommands.match("volume up"))
        assertEquals(setting("volume", "down"), InstantCommands.match("lower the volume"))
    }

    @Test
    fun `other settings and requests about them go to the model`() {
        assertNull(InstantCommands.match("is my wifi on?"))
        assertNull(InstantCommands.match("turn off the alarm"))
        assertNull(InstantCommands.match("turn on the flashlight in 10 minutes"))
        assertNull(InstantCommands.match("set the volume to 40%"))
        assertNull(InstantCommands.match("turn on location"))
        assertNull(InstantCommands.match("why does my bluetooth keep turning on"))
        assertNull(InstantCommands.match("turn on the flashlight\nand set a timer"))
    }

    private val now = ZonedDateTime.of(2026, 11, 13, 22, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test
    fun `replies say what was done as the model did`() {
        val at = now.plusHours(8).plusMinutes(30).toInstant().toEpochMilli()
        val alarmChip = MemoryChip(MemoryChip.Kind.ALARM, "Alarm", "", at = at)
        assertEquals("Alarm set for 6:30 AM tomorrow.", InstantCommands.reply(alarm("6:30"), mapOf("ok" to true, "alarm" to "Sat 14 Nov 06:30"), alarmChip, now))
        assertEquals("Timer set for 10 minutes.", InstantCommands.reply(timer("10 minutes"), mapOf("ok" to true, "timer" to "10 minutes", "ends" to "10:10 PM"), null, now))
        assertEquals(
            "Flashlight on.",
            InstantCommands.reply(setting("flashlight", "on"), mapOf("ok" to true, "flashlight" to "on"), MemoryChip(MemoryChip.Kind.SETTING, "Flashlight on", ""), now),
        )
        assertEquals("Ringer set to silent.", InstantCommands.reply(setting("ringer", "silent"), mapOf("ok" to true, "ringer" to "silent"), null, now))
        assertEquals(
            "Android doesn't let apps switch Wi-Fi themselves, so I've opened its settings for you.",
            InstantCommands.reply(setting("wifi", "off"), mapOf("ok" to true, "opened" to "the Wi-Fi settings", "note" to "Android doesn't let apps switch Wi-Fi themselves; the user switches it there"), null, now),
        )
    }

    @Test
    fun `a failure or a result with more to say is left to the model`() {
        assertNull(InstantCommands.reply(alarm("6"), mapOf("ok" to false, "error" to "'6' has already passed."), null, now))
        assertNull(InstantCommands.reply(setting("ringer", "silent"), mapOf("ok" to true, "ringer" to "vibrate", "note" to "silent needs Do Not Disturb access"), null, now))
    }
}
