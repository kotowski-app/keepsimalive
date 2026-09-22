package app.kotowski.keepsimalive.work

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Process-level memory of the armed off-schedule one-off sends (the per-SIM fire time), the
// copy of the pending state that survives the SIM details ViewModel's death: leaving the
// screen and re-visiting within the delay window must still show the card's Cancel.
//
// Deliberately in-memory only (no persistence): a process death inside the delay window is
// an accepted edge case — the re-visit shows the offer again, and the one-off still goes
// out as confirmed.
//
// The entry can go stale (the row resolved or was replaced while no ViewModel was alive to
// clear it): consumers validate it against the open PENDING row, so a stale entry can at
// most show the Cancel until the flow drops it, and a tap on it is a no-op.
@Singleton
class OffSchedulePendingRegistry
    @Inject
    constructor() {
        private val pending = ConcurrentHashMap<Int, Long>()

        fun markPending(
            simId: Int,
            atMillis: Long,
        ) {
            pending[simId] = atMillis
        }

        fun getPending(simId: Int): Long? = pending[simId]

        fun clearPending(simId: Int) {
            pending.remove(simId)
        }

        // Drops the entry only while it still remembers the given time: the history flow's
        // stale-entry cleanup runs outside the state update, so a racing newer arm must not
        // be wiped.
        fun clearPendingIf(
            simId: Int,
            atMillis: Long,
        ) {
            pending.remove(simId, atMillis)
        }
    }
