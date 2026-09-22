package app.kotowski.keepsimalive.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
) {
    override fun toString(): String = "[$timestamp] $level $tag: $message"
}

object LogBuffer {
    private val _entries = java.util.concurrent.CopyOnWriteArrayList<LogEntry>()
    private val _refreshTrigger = MutableStateFlow(0L)

    val entries: List<LogEntry>
        get() = _entries.toList()

    val refreshTrigger: StateFlow<Long> = _refreshTrigger.asStateFlow()

    fun add(
        level: String,
        tag: String,
        message: String,
    ) {
        _entries.add(
            LogEntry(
                timestamp = DateUtil.formatLogTimestamp(System.currentTimeMillis()),
                level = level,
                tag = tag,
                message = message,
            ),
        )
        if (_entries.size > AppConfig.LOG_BUFFER_MAX_ENTRIES) {
            _entries.removeAt(0)
        }
        _refreshTrigger.update { it + 1 }
    }

    fun addInfo(
        tag: String,
        message: String,
    ) = add("I", tag, message)

    fun addWarn(
        tag: String,
        message: String,
    ) = add("W", tag, message)

    fun addError(
        tag: String,
        message: String,
    ) = add("E", tag, message)

    // The full stack rides in the message (the in-app LogViewer is the primary diagnostic
    // channel and must not be logcat-only). stackTraceToString, not android.util.Log: the
    // buffer is also fed from plain JVM unit tests where the framework is stubbed.
    fun addError(
        tag: String,
        message: String,
        throwable: Throwable,
    ) = add("E", tag, "$message: ${throwable.localizedMessage}\n${throwable.stackTraceToString()}")

    fun clear() {
        _entries.clear()
        _refreshTrigger.update { it + 1 }
    }
}
