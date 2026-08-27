package dev.yukaribox.vpn.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe persistence of one small text (JSON) file.
 *
 * Every store in the app holds data the user cannot reproduce — node credentials
 * above all — and all three used to persist with a plain `writeText`, which
 * truncates the file before writing. A process death or power loss inside that
 * window leaves a truncated file, the loader parses it as "empty", and the very
 * next mutation writes that emptiness back: a single interrupted write silently
 * destroyed every saved node and password. This class applies the
 * stage-fsync-rotate-rename pattern to the data that actually matters, and adds
 * the recovery half.
 *
 * Layout next to [primary]: `<name>.tmp` (staging), `<name>.bak` (previous good
 * copy), `<name>.corrupt` (quarantined unreadable bytes). Writes go
 * tmp → fsync → rotate primary into backup → atomic rename tmp over primary, so
 * at every instant at least one of primary/backup is a complete file. Reads
 * prefer primary and fall back to backup, which covers both a torn primary and
 * the brief window where the rotation has run but the rename has not.
 *
 * Durability limit: the file contents are fsynced, but Java cannot fsync the
 * *directory*, so on a hard power loss the rename itself may not have reached
 * the platter. The pair still recovers to one consistent version — the older one
 * in the worst case, never a torn one.
 */
class DurableFile(private val primary: File) {

    private val parent: File? = primary.parentFile
    private val temp = File(parent, primary.name + ".tmp")
    private val backup = File(parent, primary.name + ".bak")
    private val quarantine = File(parent, primary.name + ".corrupt")

    /** Outcome of [read]: what was found and whether the caller should complain. */
    sealed interface Read<out T> {
        /** Primary parsed cleanly. */
        data class Ok<out T>(val value: T) : Read<T>

        /** Primary was missing or torn; [value] came from the backup copy. */
        data class Recovered<out T>(val value: T) : Read<T>

        /** Nothing persisted yet — a first run, not a failure. */
        data object Missing : Read<Nothing>

        /**
         * Neither copy could be parsed. The bytes are preserved in
         * `<name>.corrupt` and both live copies are cleared, so the caller starts
         * from defaults *and* the user can be told, instead of losing the data to
         * a silent reset.
         */
        data class Corrupt(val cause: Exception?) : Read<Nothing>
    }

    /**
     * Load and [parse] the file, falling back to the backup copy. [parse] is
     * expected to throw on malformed input; a parse that returns normally is
     * treated as valid.
     */
    fun <T> read(parse: (String) -> T): Read<T> {
        val primaryAttempt = attempt(primary, parse)
        primaryAttempt.getOrNull()?.let { return Read.Ok(it) }
        val backupAttempt = attempt(backup, parse)
        backupAttempt.getOrNull()?.let { return Read.Recovered(it) }
        // Distinguish "nothing saved yet" from "saved bytes are unreadable": only
        // the latter is an error worth surfacing, and only it needs quarantining.
        if (!primary.exists() && !backup.exists()) return Read.Missing
        val cause = (primaryAttempt.exceptionOrNull() ?: backupAttempt.exceptionOrNull()) as? Exception
        quarantine()
        return Read.Corrupt(cause)
    }

    /** Parse [file], or fail. Absent/blank counts as failure so the backup is tried. */
    private fun <T> attempt(file: File, parse: (String) -> T): Result<T> = runCatching {
        if (!file.isFile) error("absent")
        val text = file.readText()
        if (text.isBlank()) error("empty")
        parse(text)
    }

    /**
     * Move the unreadable bytes aside and clear the live pair. Preserves the
     * primary if present (the newer of the two), otherwise the backup, so the
     * data remains recoverable by hand while the app can write again.
     */
    private fun quarantine() {
        val source = if (primary.isFile) primary else backup
        runCatching { move(source, quarantine) }
        runCatching { primary.delete() }
        runCatching { backup.delete() }
    }

    /**
     * Persist [text], leaving primary and backup mutually consistent at every
     * point.
     *
     * @throws java.io.IOException if the data could not be written or published, so
     * callers can report it rather than assume the write landed.
     */
    @Throws(java.io.IOException::class)
    fun write(text: String) {
        parent?.mkdirs()
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            // Force the bytes out before the rename: renaming a file whose content
            // is still in the page cache would publish a name pointing at nothing.
            runCatching { out.fd.sync() }
        }
        if (primary.isFile) move(primary, backup)
        move(temp, primary)
    }

    /** Rename [from] over [to], atomically where the platform allows it. */
    private fun move(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            // Not every filesystem accepts both options together (Windows, some
            // FUSE mounts). A replacing move still beats truncate-in-place.
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
