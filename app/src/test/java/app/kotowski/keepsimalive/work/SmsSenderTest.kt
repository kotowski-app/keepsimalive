package app.kotowski.keepsimalive.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.telephony.SmsManager
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.util.LogBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SmsSenderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var sender: SmsSender

    @Before
    fun setup() {
        sender = SmsSender(context)
    }

    private fun broadcast(
        simId: Int,
        attemptId: String,
    ): Intent =
        Intent(SmsSender.SMS_SENT_ACTION)
            .putExtra(SmsSender.SIM_ID_EXTRA, simId)
            .putExtra(SmsSender.ATTEMPT_ID_EXTRA, attemptId)

    private fun deliver(
        receiver: SmsSentResultReceiver,
        simId: Int,
        attemptId: String,
    ) {
        receiver.onReceive(context, broadcast(simId, attemptId))
    }

    @Test
    fun `result receiver completes only for its own attempt`() {
        val received = mutableListOf<Int>()
        val receiver = SmsSentResultReceiver("attempt-1") { received.add(it) }

        // A concurrent send from another SIM broadcasts on the same action: ignored.
        deliver(receiver, 2, "attempt-2")
        deliver(receiver, 1, "attempt-2")
        assertTrue(received.isEmpty())

        deliver(receiver, 1, "attempt-1")
        // The callback receives the receiver's current result code (0 here; the framework
        // fills it in before dispatch in production).
        assertEquals(listOf(0), received)
    }

    @Test
    fun `result receiver ignores broadcasts without an attempt id`() {
        var received = false
        val receiver = SmsSentResultReceiver("attempt-1") { received = true }

        receiver.onReceive(context, Intent(SmsSender.SMS_SENT_ACTION))

        assertFalse(received)
    }

    @Test
    fun `each documented radio error code maps to its own reason`() {
        assertEquals(
            context.getString(R.string.error_send_failed),
            failureReasonForResult(context, SmsManager.RESULT_ERROR_GENERIC_FAILURE),
        )
        assertEquals(context.getString(R.string.error_radio_off), failureReasonForResult(context, SmsManager.RESULT_ERROR_RADIO_OFF))
        assertEquals(context.getString(R.string.error_null_pdu), failureReasonForResult(context, SmsManager.RESULT_ERROR_NULL_PDU))
        assertEquals(context.getString(R.string.error_no_service), failureReasonForResult(context, SmsManager.RESULT_ERROR_NO_SERVICE))
    }

    @Test
    fun `unknown result code falls back to the generic failure`() {
        assertEquals(context.getString(R.string.error_send_failed), failureReasonForResult(context, 99))
    }

    // Regression: the subscription manager must resolve without crashing on old supported
    // devices (minSdk is Android 8). The default Robolectric level (29, see
    // robolectric.properties) covers the pre-31 path; 34 pins the modern level.
    @Test
    fun `sms manager lookup resolves on the min sdk`() {
        assertNotNull(sender.smsManagerFor(1))
    }

    @Config(sdk = [34])
    @Test
    fun `sms manager lookup resolves on a modern sdk`() {
        assertNotNull(sender.smsManagerFor(1))
    }

    // The status broadcast is app-internal: a PendingIntent fired from within the app must
    // reach a dynamically registered RECEIVER_NOT_EXPORTED receiver — the same delivery the
    // real send waits on.
    @Config(sdk = [34])
    @Test
    fun `pi fired broadcast reaches the not exported receiver`() {
        val received = CompletableDeferred<Int>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    received.complete(resultCode)
                }
            }
        context.registerReceiver(receiver, IntentFilter(SmsSender.SMS_SENT_ACTION), Context.RECEIVER_NOT_EXPORTED)
        try {
            val pi =
                PendingIntent.getBroadcast(
                    context,
                    1,
                    Intent(SmsSender.SMS_SENT_ACTION).setPackage(context.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
            pi.send()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(received.isCompleted)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    // simulate() is called directly (not through send()): the debug/release branch of send()
    // is a compile-time constant per build variant, so this test must hold in both.
    @Test
    fun `simulation always succeeds`() =
        runBlocking {
            val result = sender.simulate(11, "+15550100", "keep alive")

            assertTrue(result is SmsSendResult.Success)
        }

    // realSend is called directly (not through send()): the debug/release branch of send()
    // is a compile-time constant per build variant, and the pre-radio validation only
    // exists in the real (radio) path, so these must hold in both variants.

    @Test
    fun `a false pre-radio validation aborts the attempt with Superseded`() =
        runBlocking {
            LogBuffer.clear()

            val result = sender.realSend(11, "+15550100", "keep alive") { false }

            assertTrue(result is SmsSendResult.Superseded)
            // The abort returns before any resource allocation: the radio call (and its
            // log) never happened, and the abort is logged instead.
            assertFalse(
                LogBuffer.entries.any { it.tag == "SmsSender" && it.message.startsWith("radio call") },
            )
            assertTrue(
                LogBuffer.entries.any { it.tag == "SmsSender" && it.message.startsWith("send aborted before the radio call") },
            )
        }

    // The radio path waits for a status broadcast the shadow never sends, so the tests do
    // not run the attempt to completion (that would burn the 30 s radio timeout): they
    // watch for the "radio call" log — the line realSend writes right before the radio
    // call — and cancel the attempt.

    private fun radioCallLogged(): Boolean = LogBuffer.entries.any { it.tag == "SmsSender" && it.message.startsWith("radio call simId=11") }

    private suspend fun assertRadioPathEntered(run: suspend () -> SmsSendResult) {
        LogBuffer.clear()
        // An independent scope: the attempt must not block the polling, and the test
        // cancels it once the radio path is proven entered.
        val job = CoroutineScope(Dispatchers.Default).launch { run() }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !radioCallLogged()) {
            delay(10)
        }
        job.cancel()
        assertTrue("the radio call was not reached", radioCallLogged())
    }

    @Test
    fun `a true pre-radio validation proceeds to the radio path`() =
        runBlocking {
            assertRadioPathEntered { sender.realSend(11, "+15550100", "keep alive") { true } }
        }

    @Test
    fun `a missing validation keeps the plain send behavior`() =
        runBlocking {
            assertRadioPathEntered { sender.realSend(11, "+15550100", "keep alive") }
        }
}
