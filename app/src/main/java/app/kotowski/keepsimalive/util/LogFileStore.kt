package app.kotowski.keepsimalive.util

import java.io.File
import java.io.RandomAccessFile

// Size-capped rolling log file: one line per entry, plain text on purpose. A death
// mid-append loses at most the in-flight line; everything appended survives restarts.
class LogFileStore(
    private val file: File,
    private val maxBytes: Int = AppConfig.LOG_FILE_MAX_BYTES,
) {
    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) {
                rotate(maxBytes)
            }
            file.appendText("$line\n")
        }
    }

    // Keeps the last [tailSize] bytes, cut at a line boundary so no torn line survives.
    private fun rotate(tailSize: Int) {
        val bytes = file.readBytes()
        val tail = bytes.copyOfRange(maxOf(0, bytes.size - tailSize), bytes.size)
        val firstBreak = tail.indexOf(NEWLINE)
        val kept = if (firstBreak >= 0) tail.copyOfRange(firstBreak + 1, tail.size) else tail
        file.writeBytes(kept)
    }

    // The last [maxLines] lines in file order; empty when the file does not exist. The
    // read window is bounded by the per-line cap, never the file size.
    fun loadTail(maxLines: Int): List<String> =
        synchronized(lock) {
            if (!file.exists()) {
                emptyList()
            } else {
                val size = file.length()
                val window = tailWindowBytes(maxLines)
                val text =
                    if (size <= window) {
                        file.readText()
                    } else {
                        RandomAccessFile(file, "r").use { access ->
                            access.seek(size - window.toLong())
                            val bytes = ByteArray(window)
                            access.readFully(bytes)
                            String(bytes)
                        }
                    }
                // lineSequence() yields an element for the trailing terminator: drop the
                // trailing empty elements.
                val lines = text.lineSequence().toList().dropLastWhile { it.isEmpty() }
                // Past the window the first read line is torn: drop it (the window holds
                // one extra line, so [maxLines] whole lines remain).
                (if (size > window) lines.drop(1) else lines).takeLast(maxLines)
            }
        }

    // [maxLines] whole lines plus one for the torn line the window start can cut.
    private fun tailWindowBytes(maxLines: Int): Int = (maxLines + 1) * AppConfig.LOG_MAX_LINE_BYTES

    fun clear() {
        synchronized(lock) {
            file.delete()
        }
    }

    // Seeds an empty or absent file with the in-memory history (the enable backfill), then
    // applies the same cap-cut as the overflow rotation. A non-empty file is never
    // overwritten.
    fun writeAll(lines: Collection<String>) {
        synchronized(lock) {
            if (file.exists() && file.length() > 0) {
                return
            }
            file.parentFile?.mkdirs()
            lines.forEach { file.appendText("$it\n") }
            if (file.length() > maxBytes) {
                rotate(maxBytes)
            }
        }
    }
}

private val NEWLINE: Byte = '\n'.code.toByte()
