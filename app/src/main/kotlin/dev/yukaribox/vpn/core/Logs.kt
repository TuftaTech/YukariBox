package dev.yukaribox.vpn.core

import android.util.Log
import libcore.Libcore

/**
 * Process-wide logger with two sinks, so every action is observable both in-app
 * and over adb:
 *
 *  - `Libcore.nekoLogPrintln` — appends to the core's `cache/neko.log` ring buffer
 *    in the same `[Level] [area] message` shape the reference clients use, so our
 *    lines and sing-box's own `INFO[...]` lines colour-code uniformly in the
 *    in-app viewer ([dev.yukaribox.vpn.ui.LogScreen]).
 *  - `android.util.Log` under a single [TAG], so `adb logcat -s YukariBox` mirrors
 *    everything the app does.
 *
 * **Both sinks are off by default.** [SettingsData.logging] ships false, so a user who never
 * asks for a journal never gets one: no ring-buffer line, no logcat line, and — through
 * `ConfigOptions.logging` — no core log either. The level below it still filters what is
 * recorded once logging is on, and both take effect immediately for these two sinks; the core
 * only learns about it on the next connect, because its config is built there.
 *
 * (An earlier version of this note claimed the native buffer always receives the message and
 * only the logcat mirror was gated. That was never what the code did — the level check has
 * always sat above both.)
 */
object Logs {

    const val TAG = "YukariBox"

    /**
     * sing-box log levels, lowest (most verbose) to highest (most severe).
     *
     * `internal` because it is the single home for that vocabulary: the settings picker and
     * [SettingsGuard]'s load-time reset both read it, where each used to carry its own copy.
     * This one is the *ordered* list, which [rank] needs and the other two do not care about,
     * so it is the copy that has to survive.
     */
    internal val ORDER = listOf("trace", "debug", "info", "warn", "error", "panic")

    private fun rank(level: String): Int =
        ORDER.indexOf(level.lowercase()).let { if (it < 0) ORDER.indexOf("info") else it }

    /**
     * Whether a line at [level] is recorded at all, given the two settings that govern it.
     *
     * Pure and public so the precedence is unit-tested: **off wins over any level.** The two
     * conditions are easy to reorder into `configured` deciding on its own, which would start
     * recording again for a user who asked for nothing.
     */
    fun records(logging: Boolean, configured: String, level: String): Boolean =
        logging && rank(level) >= rank(configured)

    fun v(area: String, msg: String, t: Throwable? = null) = emit("trace", Log.VERBOSE, area, msg, t)
    fun d(area: String, msg: String, t: Throwable? = null) = emit("debug", Log.DEBUG, area, msg, t)
    fun i(area: String, msg: String, t: Throwable? = null) = emit("info", Log.INFO, area, msg, t)
    fun w(area: String, msg: String, t: Throwable? = null) = emit("warn", Log.WARN, area, msg, t)
    fun e(area: String, msg: String, t: Throwable? = null) = emit("error", Log.ERROR, area, msg, t)

    /**
     * Trace line whose message is built only if it will actually be recorded.
     *
     * The gate in [emit] is correct, but the argument is evaluated before the call, and the
     * two hottest producers in the app are trace lines: the stats loop's, once a second for
     * a whole session, and `NativeInterface.autoDetectInterfaceControl`'s, once per socket
     * the core dials. With the journal off by default, every one of those strings was built
     * and dropped.
     */
    fun v(area: String, msg: () -> String) = lazily("trace", Log.VERBOSE, area, msg)

    /**
     * Info line whose message is built only if it will actually be recorded.
     *
     * The same reason as [v]'s lambda overload, for the lines that cost more than a string
     * concatenation to produce: `NodeRepository.select` interpolated a
     * `nodes.firstOrNull { it.id == id }` into its message, i.e. walked the whole working set
     * on the UI thread on every tap on a server row, and `SettingsStore.update` built a
     * forty-field `diff()` on the caller's thread before handing it to a sink that was going
     * to drop it. With the journal off by default, that is work no user ever asked for.
     */
    fun i(area: String, msg: () -> String) = lazily("info", Log.INFO, area, msg)

    /** Shared body of the two lambda overloads: gate first, build the message second. */
    private fun lazily(level: String, priority: Int, area: String, msg: () -> String) {
        val settings = SettingsStore.data
        if (!records(settings.logging, settings.logLevel, level)) return
        emit(level, priority, area, msg(), null)
    }


    /** Record a UI interaction — "see absolutely every action down to each tap". */
    fun tap(label: String) = i("UI", "tap: $label")

    /**
     * Turn journalling on or off. **The only way to change [SettingsData.logging].**
     *
     * It is a function rather than a plain `SettingsStore.update` at three call sites because
     * switching *off* has to take what is already recorded with it, and a caller that forgets
     * leaves the last session on disk under a switch that says nothing is being kept. The
     * settings screen, the log screen's empty state and the debug receiver all come through
     * here.
     *
     * Switching on is *not* symmetric: there is nothing to clean, and the diff line
     * `logging false->true` lands in the fresh journal as its first entry, which is a useful
     * thing to find at the top of a log someone is about to send you.
     */
    fun setEnabled(on: Boolean) {
        SettingsStore.update { it.copy(logging = on) }
        if (!on) LogReader.discardRecorded()
    }

    private fun emit(level: String, priority: Int, area: String, msg: String, t: Throwable?) {
        // One snapshot of the settings, not two reads: the pair has to be judged together, the
        // same reason `SettingsStore.configOptions` takes a snapshot of its own.
        val settings = SettingsStore.data
        if (!records(settings.logging, settings.logLevel, level)) return
        val safe = oneLine(msg)
        val line = "[${level.replaceFirstChar { it.uppercase() }}] [$area] $safe"
        val full = if (t != null) "$line\n${Log.getStackTraceString(t)}" else line
        runCatching { Libcore.nekoLogPrintln(full) }
        if (t != null) Log.println(priority, TAG, line).also { Log.println(priority, TAG, Log.getStackTraceString(t)) }
        else Log.println(priority, TAG, "[$area] $safe")
    }

    /**
     * Collapse newlines in a message body so one call can only ever write one line.
     *
     * Node display names come from subscription feeds and are logged by name throughout
     * (an accepted trade-off — they only reach app-private storage). The in-app viewer
     * renders `neko.log` line by line and colours each line by its `[Level]` prefix, so
     * a name containing a newline could forge an entry that looks like the app's own —
     * an `[Error] [Tunnel] fail-closed released`, say. The stack trace is appended after
     * this deliberately: that one is ours, and its shape is what the viewer expects.
     */
    private fun oneLine(msg: String): String =
        if (msg.any { it == '\n' || it == '\r' }) msg.replace('\n', ' ').replace('\r', ' ') else msg
}
