package app.kotowski.keepsimalive.ui.simsettings

import app.kotowski.keepsimalive.data.SendHistoryEntity
import app.kotowski.keepsimalive.data.SendOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimDetailInFlightRowTest {
    private fun entity(
        outcome: SendOutcome = SendOutcome.PENDING,
        scheduledForMillis: Long = 1000L,
        retryCount: Int = 0,
    ) = SendHistoryEntity(
        simId = 1,
        scheduledForMillis = scheduledForMillis,
        occurrenceBaseMillis = scheduledForMillis,
        outcome = outcome.name,
        recipient = "+15550100",
        message = "m",
        retryCount = retryCount,
    )

    @Test
    fun emptyHistoryHasNoInFlightRow() {
        assertNull(inFlightRow(emptyList()))
    }

    @Test
    fun scheduledPendingRowHasNoInFlightRow() {
        // A plain PENDING row owns nothing to display; a retry row (PENDING, retryCount > 0)
        // does.
        assertNull(inFlightRow(listOf(entity(SendOutcome.PENDING))))
        assertNull(inFlightRow(listOf(entity(SendOutcome.PENDING), entity(SendOutcome.PENDING, scheduledForMillis = 2000L))))
    }

    @Test
    fun terminalRowsHaveNoInFlightRow() {
        assertNull(inFlightRow(listOf(entity(SendOutcome.SENT), entity(SendOutcome.FAILED), entity(SendOutcome.SKIPPED))))
    }

    @Test
    fun sendingRowIsInFlight() {
        assertEquals(entity(SendOutcome.SENDING), inFlightRow(listOf(entity(SendOutcome.SENDING))))
    }

    @Test
    fun retryRowIsInFlight() {
        // A retry row is a PENDING row with retryCount > 0.
        val retryRow = entity(SendOutcome.PENDING, retryCount = 1)
        assertEquals(retryRow, inFlightRow(listOf(entity(SendOutcome.PENDING), retryRow)))
    }

    @Test
    fun sendingWinsOverRetryRow() {
        val sending = entity(SendOutcome.SENDING, scheduledForMillis = 2000L)
        val retryRow = entity(SendOutcome.PENDING, retryCount = 1)
        assertEquals(sending, inFlightRow(listOf(retryRow, sending)))
    }
}
