package dev.yukaribox.vpn.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the log gate must never lose: **off beats any level.**
 *
 * `Logs.emit` writes to two sinks at once — the core's `neko.log` ring buffer that the in-app
 * viewer reads, and the logcat mirror — and both hang off this single decision. The level
 * filter that predates it is still here, so the two have a precedence, and a future edit that
 * reorders them would quietly start recording again for a user who asked for nothing.
 */
class LogsTest {

    private val levels = listOf("trace", "debug", "info", "warn", "error", "panic")

    @Test
    fun nothingIsRecordedWhileLoggingIsOff() {
        for (level in levels) {
            assertFalse(
                "$level must be dropped when logging is off",
                Logs.records(logging = false, configured = "trace", level = level),
            )
        }
    }

    @Test
    fun everythingAtOrAboveTheLevelIsRecordedWhileLoggingIsOn() {
        assertFalse(Logs.records(logging = true, configured = "warn", level = "info"))
        assertTrue(Logs.records(logging = true, configured = "warn", level = "warn"))
        assertTrue(Logs.records(logging = true, configured = "warn", level = "error"))
    }

    @Test
    fun theMostVerboseLevelRecordsEverything() {
        for (level in levels) {
            assertTrue(level, Logs.records(logging = true, configured = "trace", level = level))
        }
    }

    @Test
    fun anUnreadableConfiguredLevelFallsBackToInfo() {
        // `settings.json` is untrusted input; SettingsGuard coerces this field, but the ranking
        // has always had its own fallback and the gate must not turn that into "record all".
        assertFalse(Logs.records(logging = true, configured = "nonsense", level = "debug"))
        assertTrue(Logs.records(logging = true, configured = "nonsense", level = "info"))
    }
}
