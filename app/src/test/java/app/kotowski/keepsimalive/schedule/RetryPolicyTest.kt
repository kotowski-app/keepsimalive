package app.kotowski.keepsimalive.schedule

import app.kotowski.keepsimalive.util.AppConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class RetryPolicyTest {
    @Test
    fun `backoff attempt 0 is about 5 seconds within 10 percent jitter`() {
        repeat(100) {
            val backoff = RetryPolicy.computeBackoffWithJitter(0)
            assertTrue("expected 5000 +/- 10%, got $backoff", backoff in 4_500L..5_500L)
        }
    }

    @Test
    fun `backoff attempt 1 is about 10 seconds within 10 percent jitter`() {
        repeat(100) {
            val backoff = RetryPolicy.computeBackoffWithJitter(1)
            assertTrue("expected 10000 +/- 10%, got $backoff", backoff in 9_000L..11_000L)
        }
    }

    @Test
    fun `backoff grows exponentially per attempt`() {
        repeat(5) { attempt ->
            val lower = List(100) { RetryPolicy.computeBackoffWithJitter(attempt) }
            val upper = List(100) { RetryPolicy.computeBackoffWithJitter(attempt + 1) }
            assertTrue(
                "attempt ${attempt + 1} should be larger than attempt $attempt",
                lower.max()!! < upper.min()!!,
            )
        }
    }

    @Test
    fun `backoff is capped at max retry period for large attempts`() {
        val max = AppConfig.MAX_RETRY_PERIOD_MS
        val bound = (max * 0.10).toLong()
        repeat(100) {
            val backoff = RetryPolicy.computeBackoffWithJitter(40)
            assertTrue("expected $max +/- 10%, got $backoff", backoff in (max - bound)..(max + bound))
        }
    }

    @Test
    fun `jitter stays within 10 percent of the uncapped exponential`() {
        for (attempt in 0..10) {
            val exponential = (AppConfig.BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong()
            val bound = (exponential * 0.10).toLong()
            repeat(20) {
                val backoff = RetryPolicy.computeBackoffWithJitter(attempt)
                assertTrue(
                    "attempt $attempt: $backoff not within $bound of $exponential",
                    backoff in (exponential - bound)..(exponential + bound),
                )
            }
        }
    }

    @Test
    fun `isExpiredByFirstAttempt true at or after 14 days false just below`() {
        val firstAttempt = 1_000_000L
        assertTrue(RetryPolicy.isExpiredByFirstAttempt(firstAttempt, firstAttempt + AppConfig.MAX_RETRY_PERIOD_MS))
        assertTrue(RetryPolicy.isExpiredByFirstAttempt(firstAttempt, firstAttempt + AppConfig.MAX_RETRY_PERIOD_MS + 1))
        assertTrue(!RetryPolicy.isExpiredByFirstAttempt(firstAttempt, firstAttempt + AppConfig.MAX_RETRY_PERIOD_MS - 1))
    }

    @Test
    fun `isExpiredByFirstAttempt false when no first attempt recorded`() {
        assertTrue(!RetryPolicy.isExpiredByFirstAttempt(null, System.currentTimeMillis()))
    }

    @Test
    fun `computeNextRetryTime is always after now`() {
        for (attempt in 0..40) {
            for (now in listOf(0L, 1_000_000L, System.currentTimeMillis())) {
                val next = RetryPolicy.computeNextRetryTime(attempt, now)
                assertTrue("next retry for attempt $attempt should be after $now, got $next", next > now)
            }
        }
    }
}
