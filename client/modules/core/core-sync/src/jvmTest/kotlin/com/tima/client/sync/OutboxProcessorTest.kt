package com.tima.client.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tima.client.database.TimaDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxProcessorTest {
    @Test
    fun sendsDueItemsAndSchedulesFailuresForRetry() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TimaDatabase.Schema.create(driver)
        val database = TimaDatabase(driver)
        database.phase1Queries.insertOutbox(
            "first", "key-1", "chat", "/v1/first", byteArrayOf(1), 100, 100,
        )
        database.phase1Queries.insertOutbox(
            "second", "key-2", "chat", "/v1/second", byteArrayOf(2), 100, 101,
        )
        val sent = mutableListOf<String>()
        val processor = OutboxProcessor(
            database,
            OutboxSender { path, _, _ ->
                if (path.endsWith("second")) error("offline")
                sent += path
            },
        )

        val result = processor.recoverAndDrain(200)

        assertEquals(DrainResult(sent = 1, retried = 1), result)
        assertEquals(listOf("/v1/first"), sent)
        assertEquals("sent", database.phase1Queries.selectOutboxById("first").executeAsOne().state)
        val retry = database.phase1Queries.selectOutboxById("second").executeAsOne()
        assertEquals("retry", retry.state)
        assertEquals(1, retry.retry_count)
        assertEquals(1_200, retry.next_attempt_epoch_ms)
        driver.close()
    }
}
