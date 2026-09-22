package app.kotowski.keepsimalive.work

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import app.kotowski.keepsimalive.BuildConfig
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.appLocaleContext
import app.kotowski.keepsimalive.util.appString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

sealed class SmsSendResult {
    data object Success : SmsSendResult()

    // Abandoned before the radio call: the stale check of a stalled claim took ownership
    // of the row — sending now would duplicate the occurrence.
    data object Superseded : SmsSendResult()

    data class Failure(
        val reason: String,
        val permanent: Boolean,
    ) : SmsSendResult()
}

// Completes the result wait only for the attempt it was created for: concurrent sends from
// other SIMs broadcast on the same action and must not cross-complete each other's results.
internal class SmsSentResultReceiver(
    private val attemptId: String,
    private val onResult: (resultCode: Int) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.getStringExtra(SmsSender.ATTEMPT_ID_EXTRA) == attemptId) {
            onResult(resultCode)
        }
    }
}

// The flag is a compile-time constant, so a release binary contains no simulation code at
// all and a debug binary never touches the radio.
@Singleton
class SmsSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val requestCodeCounter = AtomicLong(0)

        suspend fun send(
            simId: Int,
            recipient: String,
            message: String,
            // Optional pre-radio re-validation: lets a stalled-but-alive attempt (the stale
            // check resolved the row while the process was frozen) abort instead of
            // duplicating the SMS. Null keeps the plain send.
            validateBeforeRadio: (suspend () -> Boolean)? = null,
        ): SmsSendResult =
            if (BuildConfig.DEBUG) {
                simulate(simId, recipient, message)
            } else {
                realSend(simId, recipient, message, validateBeforeRadio)
            }

        // Internal (not private): unit tests call it directly in every build variant,
        // while send() takes the variant's compile-time branch.
        internal fun simulate(
            simId: Int,
            recipient: String,
            message: String,
        ): SmsSendResult {
            Logger.i("SmsSender", "simulated send simId=$simId messageChars=${message.length}")
            return SmsSendResult.Success
        }

        // Internal (not private): unit tests call it directly, as they do simulate().
        internal suspend fun realSend(
            simId: Int,
            recipient: String,
            message: String,
            validateBeforeRadio: (suspend () -> Boolean)? = null,
        ): SmsSendResult =
            withContext(Dispatchers.IO) {
                // First in the IO context, before any allocation: the claim can go stale
                // while this attempt is parked, and the stale check resolves the row and
                // advances the schedule — a radio call afterwards would duplicate the
                // occurrence.
                if (validateBeforeRadio != null && !validateBeforeRadio()) {
                    Logger.w(
                        "SmsSender",
                        "send aborted before the radio call: the occurrence is no longer a live SENDING attempt simId=$simId",
                    )
                    return@withContext SmsSendResult.Superseded
                }
                try {
                    val smsManager = smsManagerFor(simId)
                    val attemptId = UUID.randomUUID().toString()
                    val resultDeferred = CompletableDeferred<Int>()
                    // Unique requestCode per attempt: consecutive one-shot PendingIntents
                    // on the same SIM must not resolve to an already-consumed instance.
                    val pendingIntent =
                        PendingIntent.getBroadcast(
                            context,
                            requestCodeCounter.incrementAndGet().toInt(),
                            Intent(SMS_SENT_ACTION)
                                // Package-scoped: the status broadcast is app-internal, so
                                // an explicit target leaves no delivery ambiguity.
                                .setPackage(context.packageName)
                                .putExtra(SIM_ID_EXTRA, simId)
                                .putExtra(ATTEMPT_ID_EXTRA, attemptId),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    val receiver =
                        SmsSentResultReceiver(attemptId) { resultCode ->
                            resultDeferred.complete(resultCode)
                        }
                    registerSmsSentReceiver(receiver)
                    try {
                        Logger.i("SmsSender", "radio call simId=$simId")
                        smsManager.sendTextMessage(recipient, null, message, pendingIntent, null)
                        val resultCode =
                            try {
                                withTimeout(AppConfig.SEND_TIMEOUT_MS) { resultDeferred.await() }
                            } catch (e: TimeoutCancellationException) {
                                // No radio answer within the timeout: permanent failure,
                                // advance instead of retrying — a late response arriving
                                // after the retry would mean a duplicate send.
                                Logger.w("SmsSender", "send timeout simId=$simId")
                                return@withContext SmsSendResult.Failure(
                                    context.appString(R.string.error_send_timeout),
                                    permanent = true,
                                )
                            }
                        if (resultCode == Activity.RESULT_OK) {
                            SmsSendResult.Success
                        } else {
                            val reason = failureReasonForResult(context.appLocaleContext(), resultCode)
                            Logger.w("SmsSender", "send failed simId=$simId resultCode=$resultCode")
                            SmsSendResult.Failure(reason, permanent = false)
                        }
                    } finally {
                        try {
                            context.unregisterReceiver(receiver)
                        } catch (_: IllegalArgumentException) {
                        }
                        // Success/error paths consume the one-shot PendingIntent; timeout or
                        // a radio exception may leave it pending, so cancel it explicitly.
                        pendingIntent.cancel()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The detail stays in the log; the user-facing reason is the generic
                    // failure (raw exception text would leak non-i18n wording into the
                    // history and notification).
                    Logger.e("SmsSender", "send exception simId=$simId: ${e.message}", e)
                    SmsSendResult.Failure(
                        context.appString(R.string.error_send_failed),
                        permanent = false,
                    )
                }
            }

        // Internal (not private): unit tests call it directly, as they do simulate().
        // The static factory works on every API level the app supports; the API 31
        // instance method createForSubscriptionId does not exist on Android 10/11 and
        // throws NoSuchMethodError there.
        @Suppress("DEPRECATION")
        internal fun smsManagerFor(subId: Int): SmsManager = SmsManager.getSmsManagerForSubscriptionId(subId)

        // The 3-arg overload (with flags) is API 26+; below it the flags argument doesn't
        // exist, so the call can't specify RECEIVER_NOT_EXPORTED. The receiver is
        // unregistered right after the radio call, so the unflagged registration on older
        // devices is not a leak.
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        private fun registerSmsSentReceiver(receiver: BroadcastReceiver) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val receiverFlags =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Context.RECEIVER_NOT_EXPORTED
                    } else {
                        0
                    }
                context.registerReceiver(receiver, IntentFilter(SMS_SENT_ACTION), receiverFlags)
            } else {
                context.registerReceiver(receiver, IntentFilter(SMS_SENT_ACTION))
            }
        }

        companion object {
            const val SMS_SENT_ACTION = "app.kotowski.keepsimalive.SMS_SENT"
            const val SIM_ID_EXTRA = "simId"
            const val ATTEMPT_ID_EXTRA = "attemptId"
        }
    }

// The caller passes the locale-aware context, so the reason follows the app language
// the user picked.
internal fun failureReasonForResult(
    context: Context,
    resultCode: Int,
): String =
    when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> context.getString(R.string.error_send_failed)
        SmsManager.RESULT_ERROR_RADIO_OFF -> context.getString(R.string.error_radio_off)
        SmsManager.RESULT_ERROR_NULL_PDU -> context.getString(R.string.error_null_pdu)
        SmsManager.RESULT_ERROR_NO_SERVICE -> context.getString(R.string.error_no_service)
        else -> context.getString(R.string.error_send_failed)
    }
