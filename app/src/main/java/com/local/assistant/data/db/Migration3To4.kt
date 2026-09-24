package com.local.assistant.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.local.assistant.memory.prompt.HeuristicTokenEstimator

/**
 * Adds the memory system: sessions, facts, tasks, events, the archive and their keyword indexes.
 *
 * Existing chats survive untouched. Each becomes one ended session (summary pending, so the
 * nightly job can give later chats something to start from), and every stored message gets its
 * token estimate filled in so the budgeter never has to re-estimate history.
 *
 * The CREATE statements are copied verbatim from `schemas/…/4.json`: Room validates the result
 * against its own schema after migrating, and FTS tables in particular must match exactly,
 * including the triggers that keep them in sync with their content tables.
 */
internal object Migration3To4 : Migration(3, 4) {

    override fun migrate(connection: SQLiteConnection) {
        CREATE_STATEMENTS.forEach(connection::execSQL)
        backfillSessions(connection)
        backfillTokenEstimates(connection)
    }

    private fun backfillSessions(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO sessions (chatId, startedAt, endedAt, summary, summaryStatus)
            SELECT id, createdAt, updatedAt, NULL, 'PENDING' FROM chats ORDER BY id
            """,
        )
        connection.execSQL(
            """
            UPDATE messages SET sessionId =
                (SELECT sessions.id FROM sessions WHERE sessions.chatId = messages.chatId)
            """,
        )
    }

    private fun backfillTokenEstimates(connection: SQLiteConnection) {
        val estimates = mutableListOf<Pair<Long, Int>>()
        connection.prepare("SELECT id, text FROM messages").use { select ->
            while (select.step()) {
                estimates += select.getLong(0) to HeuristicTokenEstimator.estimate(select.getText(1))
            }
        }
        connection.prepare("UPDATE messages SET tokenEst = ? WHERE id = ?").use { update ->
            for ((id, tokens) in estimates) {
                update.bindLong(1, tokens.toLong())
                update.bindLong(2, id)
                update.step()
                update.reset()
            }
        }
    }

    private val CREATE_STATEMENTS = listOf(
        "ALTER TABLE chats ADD COLUMN rollingSummary TEXT",
        "ALTER TABLE chats ADD COLUMN rollingUptoMessageId INTEGER",

        "ALTER TABLE messages ADD COLUMN sessionId INTEGER",
        "ALTER TABLE messages ADD COLUMN tokenEst INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)",
        "CREATE INDEX IF NOT EXISTS `index_messages_createdAt` ON `messages` (`createdAt`)",

        "CREATE TABLE IF NOT EXISTS `sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `summary` TEXT, `summaryStatus` TEXT NOT NULL, FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_sessions_chatId` ON `sessions` (`chatId`)",
        "CREATE INDEX IF NOT EXISTS `index_sessions_endedAt` ON `sessions` (`endedAt`)",

        "CREATE TABLE IF NOT EXISTS `facts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subject` TEXT NOT NULL, `attribute` TEXT NOT NULL, `value` TEXT NOT NULL, `category` TEXT NOT NULL, `core` INTEGER NOT NULL, `origin` TEXT NOT NULL, `sourceMessageId` INTEGER, `statedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastConfirmedAt` INTEGER NOT NULL, FOREIGN KEY(`sourceMessageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_facts_subject_attribute` ON `facts` (`subject`, `attribute`)",
        "CREATE INDEX IF NOT EXISTS `index_facts_sourceMessageId` ON `facts` (`sourceMessageId`)",
        "CREATE INDEX IF NOT EXISTS `index_facts_core` ON `facts` (`core`)",

        "CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(`subject` TEXT NOT NULL, `attribute` TEXT NOT NULL, `value` TEXT NOT NULL, tokenize=unicode61, content=`facts`)",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_facts_fts_BEFORE_UPDATE BEFORE UPDATE ON `facts` BEGIN DELETE FROM `facts_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_facts_fts_BEFORE_DELETE BEFORE DELETE ON `facts` BEGIN DELETE FROM `facts_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_facts_fts_AFTER_UPDATE AFTER UPDATE ON `facts` BEGIN INSERT INTO `facts_fts`(`docid`, `subject`, `attribute`, `value`) VALUES (NEW.`rowid`, NEW.`subject`, NEW.`attribute`, NEW.`value`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_facts_fts_AFTER_INSERT AFTER INSERT ON `facts` BEGIN INSERT INTO `facts_fts`(`docid`, `subject`, `attribute`, `value`) VALUES (NEW.`rowid`, NEW.`subject`, NEW.`attribute`, NEW.`value`); END",

        "CREATE TABLE IF NOT EXISTS `subject_aliases` (`alias` TEXT NOT NULL, `subject` TEXT NOT NULL, PRIMARY KEY(`alias`))",
        "CREATE INDEX IF NOT EXISTS `index_subject_aliases_subject` ON `subject_aliases` (`subject`)",

        "CREATE TABLE IF NOT EXISTS `forgotten` (`subject` TEXT NOT NULL, `attribute` TEXT NOT NULL, `forgottenAt` INTEGER NOT NULL, PRIMARY KEY(`subject`, `attribute`))",

        "CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT, `dueAt` INTEGER, `repeatRule` TEXT, `status` TEXT NOT NULL, `sourceMessageId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER, FOREIGN KEY(`sourceMessageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_tasks_status_dueAt` ON `tasks` (`status`, `dueAt`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_sourceMessageId` ON `tasks` (`sourceMessageId`)",

        "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `startsAt` INTEGER NOT NULL, `endsAt` INTEGER, `allDay` INTEGER NOT NULL, `recurrence` TEXT, `notes` TEXT, `sourceMessageId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`sourceMessageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_events_startsAt` ON `events` (`startsAt`)",
        "CREATE INDEX IF NOT EXISTS `index_events_sourceMessageId` ON `events` (`sourceMessageId`)",

        "CREATE TABLE IF NOT EXISTS `chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` INTEGER NOT NULL, `chatId` INTEGER NOT NULL, `sessionId` INTEGER, `text` TEXT NOT NULL, `embedding` BLOB, `modelId` TEXT, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_chunks_messageId` ON `chunks` (`messageId`)",
        "CREATE INDEX IF NOT EXISTS `index_chunks_chatId_messageId` ON `chunks` (`chatId`, `messageId`)",
        "CREATE INDEX IF NOT EXISTS `index_chunks_modelId` ON `chunks` (`modelId`)",
        "CREATE INDEX IF NOT EXISTS `index_chunks_createdAt` ON `chunks` (`createdAt`)",

        "CREATE VIRTUAL TABLE IF NOT EXISTS `chunks_fts` USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`chunks`)",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_BEFORE_UPDATE BEFORE UPDATE ON `chunks` BEGIN DELETE FROM `chunks_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_BEFORE_DELETE BEFORE DELETE ON `chunks` BEGIN DELETE FROM `chunks_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_AFTER_UPDATE AFTER UPDATE ON `chunks` BEGIN INSERT INTO `chunks_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_AFTER_INSERT AFTER INSERT ON `chunks` BEGIN INSERT INTO `chunks_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",

        "CREATE TABLE IF NOT EXISTS `app_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
    )
}
