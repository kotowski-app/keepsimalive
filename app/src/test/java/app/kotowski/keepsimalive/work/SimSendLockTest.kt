package app.kotowski.keepsimalive.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// The lock is the whole fix's mutual-exclusion foundation: same-SIM sections never overlap,
// other SIMs never block, and a section that suspends, throws or is cancelled always releases
// the lock (a leaked lock would stall every send of that SIM until process death).
class SimSendLockTest {
    private val lock = SimSendLock()

    @Test
    fun `the same sim id is mutually exclusive`() =
        runBlocking {
            val events = mutableListOf<String>()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first =
                async {
                    lock.withLock(1) {
                        events += "first-in"
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        events += "first-out"
                    }
                }
            val second =
                async {
                    lock.withLock(1) {
                        events += "second-in"
                        events += "second-out"
                    }
                }
            firstEntered.await()
            delay(100)
            // The second caller waits on the lock, it never enters the section early.
            assertEquals(listOf("first-in"), events)
            releaseFirst.complete(Unit)
            first.await()
            second.await()
            assertEquals(listOf("first-in", "first-out", "second-in", "second-out"), events)
        }

    @Test
    fun `different sim ids are independent`() =
        runBlocking {
            val events = mutableListOf<String>()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first =
                async {
                    lock.withLock(1) {
                        events += "first-in"
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        events += "first-out"
                    }
                }
            val second =
                async {
                    lock.withLock(2) {
                        events += "second-in"
                        events += "second-out"
                    }
                }
            firstEntered.await()
            delay(100)
            // The other SIM's section runs while the first one is held.
            assertEquals(listOf("first-in", "second-in", "second-out"), events)
            releaseFirst.complete(Unit)
            first.await()
            second.await()
            assertEquals(listOf("first-in", "second-in", "second-out", "first-out"), events)
        }

    @Test
    fun `a cancelled section releases the lock`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val job =
                launch {
                    lock.withLock(1) {
                        entered.complete(Unit)
                        delay(Long.MAX_VALUE)
                    }
                }
            entered.await()
            job.cancelAndJoin()
            // The next acquisition must not block on the cancelled section's lock.
            withTimeout(1_000) {
                lock.withLock(1) { }
            }
        }

    @Test
    fun `a section that throws releases the lock`() {
        // The section's exception propagates to the parent scope on completion: the
        // assertion doubles as the propagation check.
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val entered = CompletableDeferred<Unit>()
                val job =
                    launch {
                        lock.withLock(1) {
                            entered.complete(Unit)
                            error("boom")
                        }
                    }
                entered.await()
                job.join()
                withTimeout(1_000) {
                    lock.withLock(1) { }
                }
            }
        }
    }

    @Test
    fun `the section's value is returned`() =
        runBlocking {
            assertEquals(7, lock.withLock(3) { 7 })
            assertEquals("ok", lock.withLock(3) { "ok" })
        }
}
