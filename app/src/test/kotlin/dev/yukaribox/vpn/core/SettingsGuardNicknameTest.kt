package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A nickname is the one free-text field the user sees drawn back at them, and it arrives
 * from three places that do not all validate: the dialog, a restored backup, and a
 * hand-edited `settings.json`. These bounds are what stop the last two from planting a
 * value that breaks a line of the interface or a line of the log.
 *
 * The awkward characters are built with [Char] from their code points rather than pasted
 * in: an invisible literal in a source file is unreadable in review, and an editor that
 * normalises whitespace can silently fix the very thing under test.
 */
class SettingsGuardNicknameTest {

    private val nul = Char(0)
    private val rtlOverride = Char(0x202E)
    private val rtlMark = Char(0x200F)

    @Test
    fun trimsAndTreatsWhitespaceOnlyAsUnset() {
        assertEquals("Vasya", SettingsGuard.nickname("  Vasya  "))
        assertEquals("", SettingsGuard.nickname("   "))
    }

    @Test
    fun keepsSpacesInsideTheName() {
        assertEquals("Vasya the Great", SettingsGuard.nickname("Vasya the Great"))
    }

    @Test
    fun dropsControlCharacters() {
        // A newline would let a nickname forge a line in the log the settings diff writes.
        assertEquals("VasyaAdmin", SettingsGuard.nickname("Vasya\nAdmin"))
        assertEquals("ab", SettingsGuard.nickname("a\tb"))
        assertEquals("ab", SettingsGuard.nickname("a${nul}b"))
    }

    @Test
    fun dropsBidiOverrides() {
        // U+202E reverses everything drawn after it, including the counts line beside it.
        assertEquals("abc", SettingsGuard.nickname("a${rtlOverride}bc"))
        assertEquals("abc", SettingsGuard.nickname("${rtlMark}abc"))
    }

    @Test
    fun isCappedByCodePoints() {
        val long = "x".repeat(SettingsGuard.NICKNAME_MAX + 10)
        assertEquals(SettingsGuard.NICKNAME_MAX, SettingsGuard.nickname(long).length)
    }

    @Test
    fun keepsEmojiWholeAtTheCap() {
        // Every emoji here is a surrogate pair: capping by chars would split the last one
        // and leave an unpaired surrogate, which renders as a replacement box.
        val emoji = "😀".repeat(SettingsGuard.NICKNAME_MAX)
        val result = SettingsGuard.nickname(emoji)
        assertEquals(SettingsGuard.NICKNAME_MAX, result.codePointCount(0, result.length))
        assertEquals(SettingsGuard.NICKNAME_MAX * 2, result.length)
    }

    @Test
    fun keepsAFlagEmoji() {
        // Node names are stripped of these (a feed must not inject colour); a nickname is
        // the user's own choice and stays as typed.
        val flag = "🇷🇺 Vasya"
        assertEquals(flag, SettingsGuard.nickname(flag))
    }
    @Test
    fun aLoadedSettingsFileIsSanitized() {
        val dirty = SettingsData(nickname = "  Vasya" + Char(10) + "Admin  ")
        assertEquals("VasyaAdmin", SettingsGuard.sanitize(dirty).nickname)
    }

    @Test
    fun aRestoredBackupKeepsItsNickname() {
        // Unlike the proxy password, a nickname is not a credential: it is a label the
        // author chose for themselves, and carrying it across a restore is the point.
        val restored = SettingsGuard.sanitizeRestored(SettingsData(nickname = "Vasya"), SettingsData())
        assertEquals("Vasya", restored.nickname)
    }

}
