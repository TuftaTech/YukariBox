package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Crash-safety of the store writes. The bug this class exists for: all three
 * stores used `writeText`, which truncates first, so an interrupted write left a
 * torn file that parsed as "empty" and was then written back — one lost write
 * destroyed every saved node and credential.
 */
class DurableFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun primary() = File(folder.root, "subscriptions.json")
    private fun durable() = DurableFile(primary())

    /** Parser that mimics a JSON decoder: refuses anything not wrapped in braces. */
    private fun parse(text: String): String {
        if (!text.startsWith("{") || !text.endsWith("}")) error("malformed")
        return text
    }

    @Test
    fun readsBackWhatItWrote() {
        durable().write("{\"a\":1}")
        val read = durable().read(::parse)
        assertTrue(read is DurableFile.Read.Ok)
        assertEquals("{\"a\":1}", (read as DurableFile.Read.Ok).value)
    }

    @Test
    fun absentFileIsMissingNotCorrupt() {
        // A first run must not look like data loss.
        assertTrue(durable().read(::parse) is DurableFile.Read.Missing)
    }

    @Test
    fun secondWriteKeepsThePreviousVersionAsBackup() {
        durable().write("{\"v\":1}")
        durable().write("{\"v\":2}")
        assertEquals("{\"v\":2}", primary().readText())
        assertEquals("{\"v\":1}", File(folder.root, "subscriptions.json.bak").readText())
    }

    @Test
    fun tornPrimaryRecoversFromBackup() {
        durable().write("{\"v\":1}")
        durable().write("{\"v\":2}")
        // Simulate a write interrupted after truncation.
        primary().writeText("{\"v\":")
        val read = durable().read(::parse)
        assertTrue(read is DurableFile.Read.Recovered)
        assertEquals("{\"v\":1}", (read as DurableFile.Read.Recovered).value)
    }

    @Test
    fun emptyPrimaryRecoversFromBackup() {
        durable().write("{\"v\":1}")
        durable().write("{\"v\":2}")
        // Zero-length is the classic result of a truncate that never got its bytes.
        primary().writeText("")
        val read = durable().read(::parse)
        assertTrue(read is DurableFile.Read.Recovered)
        assertEquals("{\"v\":1}", (read as DurableFile.Read.Recovered).value)
    }

    @Test
    fun missingPrimaryRecoversFromBackup() {
        durable().write("{\"v\":1}")
        durable().write("{\"v\":2}")
        // The window between rotating primary into backup and renaming tmp over it.
        assertTrue(primary().delete())
        assertTrue(durable().read(::parse) is DurableFile.Read.Recovered)
    }

    @Test
    fun bothCopiesUnreadableReportsCorruptAndDoesNotLoseTheBytes() {
        durable().write("{\"v\":1}")
        durable().write("{\"v\":2}")
        primary().writeText("garbage-primary")
        File(folder.root, "subscriptions.json.bak").writeText("garbage-backup")

        val read = durable().read(::parse)
        assertTrue(read is DurableFile.Read.Corrupt)
        // The unreadable bytes survive for manual recovery...
        assertEquals("garbage-primary", File(folder.root, "subscriptions.json.corrupt").readText())
        // ...and the live pair is cleared so the next write starts clean.
        assertFalse(primary().exists())
        assertFalse(File(folder.root, "subscriptions.json.bak").exists())
    }

    @Test
    fun corruptWithNoBackupIsStillQuarantined() {
        primary().writeText("not json at all")
        assertTrue(durable().read(::parse) is DurableFile.Read.Corrupt)
        assertEquals("not json at all", File(folder.root, "subscriptions.json.corrupt").readText())
    }

    @Test
    fun writingAfterCorruptionWorks() {
        primary().writeText("garbage")
        assertTrue(durable().read(::parse) is DurableFile.Read.Corrupt)
        durable().write("{\"fresh\":true}")
        val read = durable().read(::parse)
        assertTrue(read is DurableFile.Read.Ok)
        assertEquals("{\"fresh\":true}", (read as DurableFile.Read.Ok).value)
    }

    @Test
    fun noTempFileIsLeftBehind() {
        durable().write("{\"v\":1}")
        // A leftover .tmp means a rename that never happened.
        assertFalse(File(folder.root, "subscriptions.json.tmp").exists())
    }

    @Test
    fun repeatedWritesNeverLeaveBothCopiesBad() {
        // The invariant the class buys: after any completed write, at least one of
        // primary/backup parses.
        repeat(5) { i ->
            durable().write("{\"v\":$i}")
            val parses = listOf(primary(), File(folder.root, "subscriptions.json.bak"))
                .filter { it.isFile }
                .count { runCatching { parse(it.readText()) }.isSuccess }
            assertTrue("no readable copy after write $i", parses >= 1)
        }
    }

    @Test
    fun writeCreatesTheParentDirectory() {
        val nested = File(folder.root, "sub/dir/settings.json")
        DurableFile(nested).write("{\"a\":1}")
        assertTrue(nested.isFile)
    }
}
