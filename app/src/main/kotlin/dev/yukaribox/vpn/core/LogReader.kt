package dev.yukaribox.vpn.core

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * Reads the core's `cache/neko.log` ring buffer that both sing-box and [Logs]
 * write to. Mirrors the reference clients' `SendLog.getNekoLog`: a tail read that
 * seeks to the last [maxBytes] so the viewer stays cheap even when the buffer is
 * large.
 */
object LogReader {

    private fun file() = File(AppContext.context.cacheDir, NEKO_LOG)

    /** Returns the tail of the log as text. [maxBytes] <= 0 reads the whole file. */
    fun read(maxBytes: Long = 256 * 1024): String = try {
        val f = file()
        if (!f.exists()) "" else FileInputStream(f).use { stream ->
            val len = f.length()
            if (maxBytes in 1 until len) stream.skip(len - maxBytes)
            stream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        "log read failed: ${e.message}"
    }

    /**
     * Length and last-modified time of the ring buffer, or `0 to 0` when it does not exist.
     *
     * The log screen polls once a second, and re-reading 256 KiB, decoding it as UTF-8 and
     * splitting it into a few thousand strings costs about a megabyte of garbage per tick
     * whether or not a single line was appended. Two `stat` calls answer that question first.
     */
    fun stamp(): Pair<Long, Long> = runCatching { file().let { it.length() to it.lastModified() } }
        .getOrDefault(0L to 0L)

    /**
     * Empties the core's ring buffer -- from Java, never through `Libcore.nekoLogClear()`.
     *
     * That call reaches `neko_log.(*logWriter).Truncate`, which takes no lock at all, while
     * libcore's own logger goroutines write through `(*logWriter).Write` under nothing but an
     * advisory `flock` -- which does nothing between goroutines of one process. Neither
     * `NekoLogClear` nor `NekoLogPrintln` is wrapped in `device.DeferPanicToError`, unlike the
     * five box entry points, so a panic there is not converted into an exception: the Go
     * runtime aborts the process. That is the crash this replaced, and it was not theoretical
     * -- `YukariApp.onCreate` calls [discardRecorded] on every cold start with journalling off,
     * which is the default, and the tombstone was a thread-directed SIGABRT raised from
     * `runtime.tgkill` on the `log-wipe` thread with a one-frame backtrace.
     *
     * A Java truncate cannot fault the Go runtime. The worst it can do is leave the core's own
     * handle at a stale offset, so its next line lands past a hole -- a cosmetic defect in a
     * diagnostic file, against a process abort that takes the TUN with it.
     */
    fun clear() {
        runCatching { File(AppContext.context.cacheDir, NEKO_LOG).writeText("") }
    }

    /**
     * Drop everything already journalled: the ring buffer *and* the core's own file log.
     *
     * Called when the user switches journalling off, and the reason it exists is that off has
     * to be retroactive. Leaving the previous session's node names and endpoints on disk after
     * someone asked for no journal is the opposite of what they asked for; `box.log` is already
     * truncated on every session start for exactly this reason (`YukariVpnService`).
     *
     * Safe from the main thread — it queues. `nekoLogClear` crosses into native code and the
     * truncate is IO, neither of which belongs on the frame budget of a switch being tapped.
     */
    fun discardRecorded() {
        io.execute {
            clear()
            // `writeText` is right here and wrong for the three data stores: truncating a log
            // to empty is the whole intent, where a torn `subscriptions.json` is data loss.
            runCatching { File(AppContext.context.filesDir, BOX_LOG).writeText("") }
        }
    }

    /** The core's ring buffer, shared by [Logs] and by sing-box's own logger. */
    private const val NEKO_LOG = "neko.log"

    /** The core's file log, whose path `ConfigBuilder` passes as `log.output`. */
    private const val BOX_LOG = "box.log"

    private val io = Executors.newSingleThreadExecutor(AppThreads.factory("log-wipe"))
}
