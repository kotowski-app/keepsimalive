package app.kotowski.keepsimalive.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
) {
    override fun toString(): String = "[$timestamp] $level $tag: $message"
}

object LogBuffer {
    private val _entries = CopyOnWriteArrayList<LogEntry>()
    private val _refreshTrigger = MutableStateFlow(0L)
    private val bufferLock = Any()

    // @Volatile: the "Log to file" toggle attaches and detaches at runtime while every
    // add() reads it. Appends run on the writer thread, never the caller's.
    @Volatile
    private var fileStore: LogFileStore? = null

    @Volatile
    private var fileWriter: Executor? = null

    val entries: List<LogEntry>
        get() = _entries.toList()

    val refreshTrigger: StateFlow<Long> = _refreshTrigger.asStateFlow()

    // Attaches [store] and loads its tail back in. Called from Application.onCreate;
    // tests may re-wire.
    fun init(
        store: LogFileStore,
        executor: Executor = defaultWriter(),
    ) {
        fileWriter = executor
        attach(store)
    }

    // Attach plus backfill are ONE task on the single-thread writer, so they serialize
    // with in-flight appends and the file stays chronological. A buffer add inside that
    // window can duplicate one line in the file; best-effort, no loss.
    fun enableFileStore(file: File) {
        val store = LogFileStore(file)
        fileWriter = fileWriter ?: defaultWriter()
        writeToStore {
            attach(store)
            store.writeAll(_entries.map { it.toString() })
        }
    }

    // Clear and detach are one task on the writer, so the file can never respawn with a
    // ghost line. The identity check keeps a stale disable from detaching a newer store.
    fun disableFileStore() {
        val store = fileStore ?: return
        writeToStore {
            store.clear()
            if (fileStore === store) {
                fileStore = null
            }
        }
    }

    private fun attach(store: LogFileStore) {
        fileStore = store
        // Prepend, not append: the loaded tail is older history, while entries logged before
        // the attach are newer and never reach the file.
        _entries.addAll(0, store.loadTail(AppConfig.LOG_BUFFER_MAX_ENTRIES).mapNotNull { lineToEntry(it) })
        _refreshTrigger.update { it + 1 }
    }

    fun add(
        level: String,
        tag: String,
        message: String,
    ) {
        val entry =
            LogEntry(
                timestamp = DateUtil.formatLogTimestamp(System.currentTimeMillis()),
                level = level,
                tag = tag,
                // "\\\\n": the replacement string escapes backslashes; one fewer would
                // collapse to a bare "n".
                message = truncate(message.replace(newlines, "\\\\n")),
            )
        // One critical section: concurrent adds must not both drop an entry, and the
        // while drains any pre-existing over-cap backlog.
        synchronized(bufferLock) {
            _entries.add(entry)
            while (_entries.size > AppConfig.LOG_BUFFER_MAX_ENTRIES) {
                _entries.removeAt(0)
            }
        }
        _refreshTrigger.update { it + 1 }
        writeToStore { fileStore?.append(entry.toString()) }
    }

    // Wipes the buffer and the file. The file clear is queued behind in-flight appends
    // on the writer, so nothing survives as a ghost line and nothing is lost.
    fun clear() {
        _entries.clear()
        _refreshTrigger.update { it + 1 }
        val store = fileStore ?: return
        writeToStore { store.clear() }
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

    // The in-task catch matters: an exception escaping the writer thread would kill the
    // process, so the file stays best-effort and the buffer keeps working.
    private fun writeToStore(block: () -> Unit) {
        val writer = fileWriter ?: return
        try {
            writer.execute {
                try {
                    block()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    // All line terminators (including the Unicode U+0085/U+2028/U+2029) would break the
    // one-line-per-entry format; the literal "\n" escape keeps entries single-line.
    private val newlines = Regex("[\r\n\u0085\u2028\u2029]+")

    private fun truncate(message: String): String =
        if (message.length <= AppConfig.LOG_MAX_MESSAGE_LENGTH) {
            message
        } else {
            message.take(AppConfig.LOG_MAX_MESSAGE_LENGTH) + "…"
        }

    // The inverse of LogEntry.toString; a torn line from a mid-append crash just doesn't
    // match.
    private val entryLine = Regex("""^\[([^\]]+)\] ([DIWE]) (\S+): (.*)$""")

    internal fun lineToEntry(line: String): LogEntry? =
        entryLine.matchEntire(line)?.let { match ->
            LogEntry(
                timestamp = match.groupValues[1],
                level = match.groupValues[2],
                tag = match.groupValues[3],
                message = match.groupValues[4],
            )
        }

    private fun defaultWriter(): Executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "log-file-writer").apply { isDaemon = true }
        }

    internal fun resetForTests() {
        fileStore = null
        fileWriter = null
        _entries.clear()
    }
}
