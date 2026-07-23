package com.tima.client.sync

import com.tima.client.database.TimaDatabase
import kotlinx.coroutines.CancellationException

fun interface OutboxSender {
    suspend fun send(
        requestPath: String,
        idempotencyKey: String,
        envelope: ByteArray,
    )
}

data class DrainResult(
    val sent: Int,
    val retried: Int,
)

class OutboxProcessor(
    private val database: TimaDatabase,
    private val sender: OutboxSender,
    private val baseRetryMillis: Long = 1_000,
    private val maxRetryMillis: Long = 60_000,
) {
    suspend fun recoverAndDrain(nowEpochMillis: Long, limit: Long = 50): DrainResult {
        val queries = database.phase1Queries
        queries.recoverSendingOutbox()
        val due = queries.selectDueOutbox(nowEpochMillis, limit).executeAsList()
        var sent = 0
        var retried = 0
        for (item in due) {
            queries.markOutboxSending(item.local_id)
            try {
                sender.send(item.request_path, item.idempotency_key, item.envelope)
                queries.markOutboxSent(item.local_id)
                sent++
            } catch (cancelled: CancellationException) {
                queries.markOutboxRetry(nowEpochMillis, item.local_id)
                throw cancelled
            } catch (_: Throwable) {
                val retryAt = nowEpochMillis + retryDelay(item.retry_count)
                queries.markOutboxRetry(retryAt, item.local_id)
                retried++
            }
        }
        return DrainResult(sent, retried)
    }

    private fun retryDelay(retryCount: Long): Long {
        var value = baseRetryMillis
        repeat(retryCount.coerceAtMost(30).toInt()) {
            value = (value * 2).coerceAtMost(maxRetryMillis)
        }
        return value
    }
}
