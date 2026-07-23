package com.tima.client.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OutboxPersistenceTest {
    @Test
    fun pendingEnvelopeSurvivesDatabaseRestart() {
        val path = Files.createTempFile("tima-outbox", ".sqlite")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val envelope = byteArrayOf(1, 2, 3, 4)

        JdbcSqliteDriver(url).use { driver ->
            TimaDatabase.Schema.create(driver)
            TimaDatabase(driver).phase1Queries.insertOutbox(
                local_id = "local-1",
                idempotency_key = "00000000-0000-0000-0000-000000000001",
                chat_id = "00000000-0000-0000-0000-000000000002",
                request_path = "/v1/chats/00000000-0000-0000-0000-000000000002/messages",
                envelope = envelope,
                next_attempt_epoch_ms = 1,
                created_at_epoch_ms = 1,
            )
        }

        JdbcSqliteDriver(url).use { driver ->
            val due = TimaDatabase(driver).phase1Queries.selectDueOutbox(1, 10).executeAsList()
            assertEquals(1, due.size)
            assertEquals("pending", due.single().state)
            assertContentEquals(envelope, due.single().envelope)
        }
        Files.deleteIfExists(path)
    }
}
