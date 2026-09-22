package app.kotowski.keepsimalive.schedule

import app.kotowski.keepsimalive.util.AppConfig
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

object RetryPolicy {
    fun computeBackoffWithJitter(attempt: Int): Long {
        val exponential = (AppConfig.BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong()
        val capped = min(exponential, AppConfig.MAX_RETRY_PERIOD_MS)
        val jitterRange = (capped * AppConfig.BACKOFF_JITTER_FRACTION).toLong()
        val jitter = Random.nextLong(-jitterRange, jitterRange + 1)
        return capped + jitter
    }

    fun isExpiredByFirstAttempt(
        firstSendAttemptTime: Long?,
        now: Long,
    ): Boolean {
        val attemptTime = firstSendAttemptTime ?: return false
        return now - attemptTime >= AppConfig.MAX_RETRY_PERIOD_MS
    }

    fun computeNextRetryTime(
        attempt: Int,
        now: Long,
    ): Long = now + computeBackoffWithJitter(attempt)
}
