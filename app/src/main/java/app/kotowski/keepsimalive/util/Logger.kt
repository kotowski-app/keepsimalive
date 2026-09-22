package app.kotowski.keepsimalive.util

import android.util.Log

object Logger {
    private const val TAG_PREFIX = " keepalive "

    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        LogBuffer.addInfo(tag, message)
        safeLog { Log.i("$TAG_PREFIX$tag", message, throwable) }
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        LogBuffer.addWarn(tag, message)
        safeLog { Log.w("$TAG_PREFIX$tag", message, throwable) }
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            LogBuffer.addError(tag, message, throwable)
        } else {
            LogBuffer.addError(tag, message)
        }
        safeLog { Log.e("$TAG_PREFIX$tag", message, throwable) }
    }

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}
