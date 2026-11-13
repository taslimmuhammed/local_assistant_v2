package com.local.assistant.chat

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deletes chats a check on the phone made by hand: only those created after `-e since <epoch ms>`
 * whose title is exactly `-e title <title>`, and never more than `-e most <n>` (default 3). Lists
 * what it found first; with `-e delete true` it deletes them.
 *
 *     adb shell am instrument -w -e cleanup true -e since 1763048460000 -e title "Good evening!" \
 *         -e class com.local.assistant.chat.TestChatCleanup \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class TestChatCleanup {

    @Test
    fun deleteTestChats() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e cleanup true", args.getString("cleanup") == "true")
        val since = requireNotNull(args.getString("since")?.toLongOrNull()) { "-e since <epoch ms>" }
        val title = requireNotNull(args.getString("title")) { "-e title <title>" }
        val most = args.getString("most")?.toIntOrNull() ?: 3
        val container = InstrumentationRegistry.getInstrumentation().targetContext.appContainer
        val found = container.chatRepository.observeChats().first().filter { it.createdAt >= since && it.title == title }
        Log.i(TAG, "found ${found.size}: ${found.joinToString { "${it.id} at ${it.createdAt}" }}")
        check(found.size <= most) { "found ${found.size}, more than $most: nothing deleted" }
        if (args.getString("delete") == "true") {
            for (chat in found) {
                container.chatRepository.deleteChat(chat.id)
                container.conversations.forget(chat.id)
            }
            Log.i(TAG, "deleted ${found.size}")
        }
        val left = container.chatRepository.observeChats().first().count { it.createdAt >= since && it.title == title }
        println("found ${found.size}, left $left")
    }

    private companion object {
        const val TAG = "TestChatCleanup"
    }
}
