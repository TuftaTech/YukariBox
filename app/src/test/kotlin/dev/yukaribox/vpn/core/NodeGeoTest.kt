package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Country inference from the only evidence a share link carries: the label its author
 * typed and the host it dials, plus the display-side counterpart that takes the flag
 * emoji back out of the label once the plate is carrying it.
 *
 * The interesting cases are all failure modes rather than successes — a wrong flag is
 * worse than no flag, so the `ordinaryEnglishWords` and `returnsNull` tests are the ones
 * that matter, and on the `plainName` side it is the two that guarantee a name is never
 * blanked or silently rewritten.
 */
class NodeGeoTest {

    @Test
    fun readsAFlagEmoji() {
        assertEquals("JP", NodeGeo.codeFor("🇯🇵 Tokyo 01"))
        assertEquals("DE", NodeGeo.codeFor("🇩🇪 Frankfurt"))
    }

    @Test
    fun theFlagEmojiWinsOverEverythingElse() {
        // The author said so explicitly; a stray city name must not override it.
        assertEquals("NL", NodeGeo.codeFor("🇳🇱 relay via london"))
    }

    @Test
    fun readsSpelledOutCountryNames() {
        assertEquals("NL", NodeGeo.codeFor("Netherlands 03"))
        assertEquals("GB", NodeGeo.codeFor("united kingdom - fast"))
        assertEquals("KR", NodeGeo.codeFor("South Korea Premium"))
        assertEquals("RU", NodeGeo.codeFor("Россия | Москва"))
    }

    @Test
    fun readsCitiesAndBareCodes() {
        assertEquals("JP", NodeGeo.codeFor("vmess-tokyo-01"))
        assertEquals("US", NodeGeo.codeFor("us-east-1.example.com"))
        assertEquals("SG", NodeGeo.codeFor("sg1.example.net"))
        // Digits are stripped, so a trailing index cannot hide the code.
        assertEquals("DE", NodeGeo.codeFor("de2.example.org"))
    }

    @Test
    fun aMultiWordNameBeatsASingleWordOne() {
        // "korea" alone would be the North; the qualifier has to be checked first.
        assertEquals("KR", NodeGeo.codeFor("south korea"))
        assertEquals("KP", NodeGeo.codeFor("north korea"))
    }

    @Test
    fun aSpelledOutNameBeatsABareCode() {
        // `ca` is Canada, but the word "singapore" is the stronger evidence.
        assertEquals("SG", NodeGeo.codeFor("singapore-ca-1"))
    }

    @Test
    fun ordinaryEnglishWordsAreNotCountryCodes() {
        // Every one of these would resolve to a country if bare two-letter codes were
        // accepted indiscriminately: IT, IN, NO, AT, BE, TO, MY.
        assertNull(NodeGeo.codeFor("it is in the list"))
        assertNull(NodeGeo.codeFor("no route to be found"))
        assertNull(NodeGeo.codeFor("my node"))
        assertNull(NodeGeo.codeFor("node to test at home"))
    }

    @Test
    fun returnsNullWhenNothingNamesAPlace() {
        assertNull(NodeGeo.codeFor(""))
        assertNull(NodeGeo.codeFor("   "))
        assertNull(NodeGeo.codeFor("premium-vless-9382"))
    }

    @Test
    fun isCaseInsensitive() {
        assertEquals("JP", NodeGeo.codeFor("JAPAN"))
        assertEquals("JP", NodeGeo.codeFor("Japan"))
        assertEquals("JP", NodeGeo.codeFor("jApAn"))
    }

    @Test
    fun plainNameDropsTheFlagAndTheSeparatorBehindIt() {
        assertEquals("CA | 216.128.176.166", NodeGeo.plainName("🇨🇦 CA | 216.128.176.166"))
        assertEquals("Frankfurt", NodeGeo.plainName("🇩🇪 - Frankfurt"))
        assertEquals("Tokyo 01", NodeGeo.plainName("🇯🇵Tokyo 01"))
    }

    @Test
    fun plainNameClosesTheGapAFlagLeavesMidString() {
        assertEquals("relay JP fast", NodeGeo.plainName("relay 🇯🇵 JP fast"))
    }

    @Test
    fun plainNameLeavesAnOrdinaryNameExactlyAsItIs() {
        // Byte-identical, not merely equal-looking: this runs on every visible row, and a
        // name that is silently re-spaced is a name the user can no longer search for.
        val name = "US  |  yahoo.com | VLESS"
        assertSame(name, NodeGeo.plainName(name))
        assertSame("", NodeGeo.plainName(""))
    }

    @Test
    fun plainNameKeepsAFlagOnlyNameRatherThanBlankingTheRow() {
        assertEquals("🇯🇵", NodeGeo.plainName("🇯🇵"))
        assertEquals("🇯🇵 |", NodeGeo.plainName("🇯🇵 |"))
    }
}
