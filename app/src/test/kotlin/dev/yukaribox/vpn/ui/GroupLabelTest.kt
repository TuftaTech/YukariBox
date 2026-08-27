package dev.yukaribox.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The group-name strip, which is the one rule `SKILL.md` calls absolute: nothing the app
 * draws may have a hue, and a regional-indicator flag emoji is the only chromatic element
 * a subscription feed (or a user copying its naming) can inject into a label.
 *
 * Two cases carry it. The ordinary one — a flag in front of a name — is `NodeGeo`'s and is
 * tested there; what is tested here is the case that function deliberately does not cover,
 * a name with *nothing but* a flag in it, where `plainName` hands the original back rather
 * than leave a row blank.
 */
class GroupLabelTest {

    @Test
    fun dropsTheFlagAndTheSeparatorBehindIt() {
        assertEquals("Tokyo QA", groupLabel("🇯🇵 Tokyo QA"))
        assertEquals("Frankfurt", groupLabel("🇩🇪 - Frankfurt"))
    }

    @Test
    fun aNameThatIsNothingButAFlagBecomesItsCountryCode() {
        // `plainName` alone would return the flag here, which is the one input that would
        // still paint colour on a group card — it has no plate and no second line to fall
        // back on, so the country the flag names is what it draws.
        assertEquals("JP", groupLabel("🇯🇵"))
        assertEquals("CA", groupLabel("🇨🇦"))
    }

    @Test
    fun leavesAnOrdinaryNameExactlyAsItIs() {
        assertEquals("scarlet-devil.vercel.app", groupLabel("scarlet-devil.vercel.app"))
        assertEquals("Test", groupLabel("Test"))
        assertEquals("", groupLabel(""))
    }

    @Test
    fun keepsHalfAPairRatherThanInventingACountry() {
        // A lone regional indicator is not a flag: the system draws it as a letter glyph,
        // so there is no hue to strip and nothing to decode. `plainName` empties the name
        // and falls back to the original; `codeFor` finds no pair, so it stays as typed.
        assertEquals("🇯", groupLabel("🇯"))
    }
}
