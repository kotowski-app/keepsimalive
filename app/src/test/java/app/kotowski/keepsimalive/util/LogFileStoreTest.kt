package app.kotowski.keepsimalive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogFileStoreTest {
    private fun tempFile(): File = File.createTempFile("logstore", ".log").apply { delete() }

    @Test
    fun `append writes the line to the file`() {
        val file = tempFile()
        LogFileStore(file).append("line one")
        assertEquals(listOf("line one"), file.readLines())
    }

    @Test
    fun `appends keep lines in order`() {
        val file = tempFile()
        val store = LogFileStore(file)
        repeat(5) { store.append("line $it") }
        assertEquals((0 until 5).map { "line $it" }, file.readLines())
    }

    @Test
    fun `append creates the missing parent directory`() {
        val base = File.createTempFile("logstore", ".dir").apply { delete() }
        val file = File(base, "nested/deep/store.log")
        LogFileStore(file).append("line")
        assertTrue(file.readLines().contains("line"))
    }

    @Test
    fun `append creates nested parent directories`() {
        val base = File.createTempFile("logstore", ".dir").apply { delete() }
        val file = File(base, "a/b/c/deep.log")
        LogFileStore(file).append("deep line")
        assertEquals(listOf("deep line"), file.readLines())
    }

    @Test
    fun `loadTail returns the last lines in file order`() {
        val file = tempFile()
        val store = LogFileStore(file)
        repeat(10) { store.append("line $it") }
        assertEquals(listOf("line 8", "line 9"), store.loadTail(2))
        assertEquals((0 until 10).map { "line $it" }, store.loadTail(50))
    }

    @Test
    fun `loadTail is empty for a missing file`() {
        assertTrue(LogFileStore(tempFile()).loadTail(10).isEmpty())
    }

    @Test
    fun `loadTail with zero max lines returns empty list`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.append("line one")
        store.append("line two")
        assertTrue(store.loadTail(0).isEmpty())
    }

    @Test
    fun `writeAll seeds an absent file with the given lines in order`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.writeAll(listOf("line one", "line two", "line three"))
        assertEquals(listOf("line one", "line two", "line three"), file.readLines())
    }

    @Test
    fun `writeAll creates parent directory if missing`() {
        val base = File.createTempFile("logstore", ".dir").apply { delete() }
        val file = File(base, "nested/write.log")
        val store = LogFileStore(file)
        store.writeAll(listOf("line one", "line two"))
        assertEquals(listOf("line one", "line two"), file.readLines())
    }

    @Test
    fun `writeAll on a non-empty file is a no-op`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.append("existing")
        store.writeAll(listOf("line one", "line two"))
        assertEquals(listOf("existing"), file.readLines())
    }

    @Test
    fun `writeAll on an empty file (length 0) does write the lines`() {
        val file = tempFile()
        file.createNewFile()
        val store = LogFileStore(file)
        store.writeAll(listOf("appears on empty"))
        assertEquals(listOf("appears on empty"), file.readLines())
    }

    @Test
    fun `writeAll over-cap content is cut to the tail like the overflow rotation`() {
        // 10 lines of 12 bytes (120 total) cross the 64-byte cap: the post-write cut
        // keeps the last 64 bytes at a line boundary (lines 5..9).
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64)
        store.writeAll((0 until 10).map { "line-$it-xxxx" })
        assertEquals(listOf("line-5-xxxx", "line-6-xxxx", "line-7-xxxx", "line-8-xxxx", "line-9-xxxx"), file.readLines())
    }

    @Test
    fun `clear removes the file`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.append("line")
        store.clear()
        assertTrue(!file.exists())
        assertTrue(store.loadTail(10).isEmpty())
    }

    @Test
    fun `appending past the size cap rotates to the tail instead of deleting the file`() {
        // Each line is 12 bytes (line-10 is 13): the cap is crossed after the 6th and
        // 10th appends, so the 7th and 11th rotate to the line-boundary cut before landing.
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64)
        repeat(11) { store.append("line-$it-xxxx") }
        assertEquals(
            listOf("line-5-xxxx", "line-6-xxxx", "line-7-xxxx", "line-8-xxxx", "line-9-xxxx", "line-10-xxxx"),
            file.readLines(),
        )
    }

    @Test
    fun `rotation starts at a line boundary so no torn line survives`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64)
        repeat(11) { store.append("line-$it-xxxx") }
        val lines = file.readLines()
        assertTrue(lines.isNotEmpty())
        assertTrue("every surviving line must be whole: $lines", lines.all { it.startsWith("line-") })
    }

    @Test
    fun `rotation with a tail bigger than the file still drops the oldest lines`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 32)
        store.append("first")
        store.append("x".repeat(40))
        store.append("last")
        val lines = file.readLines()
        assertTrue("the old content must be rotated out: $lines", !lines.contains("first"))
        assertEquals("last", lines.last())
    }

    @Test
    fun `the file never grows past the cap plus one line`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64)
        repeat(50) { store.append("line-$it-xxxx") }
        val longestLine = ("line-49-xxxx" + "\n").length
        assertTrue("file grew to ${file.length()} bytes", file.length() <= 64 + longestLine)
    }

    @Test
    fun `loadTail of a file beyond the read window returns the last lines intact`() {
        // 40 KB of lines exceeds the loadTail(2) window: the torn first window line is
        // dropped, the last two come back whole.
        val file = tempFile()
        val store = LogFileStore(file)
        val lines = (0 until 40).map { "line-$it-".padEnd(999, 'x') }
        lines.forEach { file.appendText("$it\n") }
        assertEquals(lines.takeLast(2), store.loadTail(2))
    }

    @Test
    fun `loadTail drops the first window line when the window starts on a line boundary`() {
        // The window start lands exactly on a line start: the first whole window line is
        // still dropped, and the extra window line keeps the result at maxLines.
        val file = tempFile()
        val store = LogFileStore(file)
        val lines = (0 until 39).map { "line-$it-".padEnd(999, 'x') } + "line-39-".padEnd(767, 'x')
        lines.forEach { file.appendText("$it\n") }
        assertEquals(lines.takeLast(2), store.loadTail(2))
    }

    @Test
    fun `loadTail decodes multi-byte lines when the window starts mid-character`() {
        // The leading byte shifts the window start into a two-byte UTF-8 sequence: the
        // torn fragment is dropped, the rest decodes intact.
        val file = tempFile()
        val store = LogFileStore(file)
        file.appendText("a")
        val lines = (0 until 40).map { "строка-$it-${"я".repeat(489)}" }
        lines.forEach { file.appendText("$it\n") }
        val tail = store.loadTail(2)
        assertEquals(lines.takeLast(2), tail)
        assertTrue("no replacement char may leak: $tail", tail.all { !it.contains("\uFFFD") })
    }
}
