package app.kotowski.keepsimalive.work

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// The single per-SIM lock that makes the send engine's claim-to-result section and the
// UI's check-then-act funnels (forget / delete / disable) mutually exclusive.
//
// Scope discipline: a section is gate check -> DB write -> that SIM's reconcile, nothing
// else (tens of ms), never unbounded work; WorkManager cancel IPC, the persistent-hint
// sync, toasts and UI state updates stay outside the sections.
//
// One lock per SIM, no nesting -> no deadlock. A racing send either finishes its result
// write before a UI section (which then cleans up the advanced state) or enters after it
// (its row re-creation is blocked by the config guard, see SimHistoryDao) — the section's
// effect survives in any ordering.
//
// The Mutex is in-process only, and WorkManager runs the send worker in the app process
// (default executor); a separate-process executor would silently break the exclusion, so
// SendWorker asserts the premise at its entry point (SendProcessGate) and fails loudly.
@Singleton
class SimSendLock
    @Inject
    constructor() {
        private val locks = ConcurrentHashMap<Int, Mutex>()

        suspend fun <T> withLock(
            simId: Int,
            block: suspend () -> T,
        ): T {
            val lock = locks.getOrPut(simId) { Mutex() }
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }
