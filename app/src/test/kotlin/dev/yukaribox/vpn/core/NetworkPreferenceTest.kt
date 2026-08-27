package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which interface the tunnel runs over is not a question to answer by inspection, which is why
 * the choice is a pure fold and why it has a test.
 *
 * The service used to let the platform answer it (`registerDefaultNetworkCallback`) and got the
 * wrong answer: our own TUN becomes this process's default the moment it is established, so the
 * callback fired with the VPN itself. Watching every INTERNET-capable non-VPN network instead
 * means two or three arrive at once and the app has to rank them.
 */
class NetworkPreferenceTest {

    private var next = 0L

    private fun net(
        transport: NetTransport,
        validated: Boolean = true,
        captivePortal: Boolean = false,
    ) = TrackedNetwork(
        handle = ++next,
        transport = transport,
        validated = validated,
        captivePortal = captivePortal,
        seq = next,
    )

    @Test
    fun nothingTrackedMeansNoNetwork() {
        assertNull(preferredNetwork(emptyList()))
    }

    @Test
    fun wifiIsPreferredOverCellular() {
        val cellular = net(NetTransport.Cellular)
        val wifi = net(NetTransport.Wifi)
        assertEquals(wifi, preferredNetwork(listOf(cellular, wifi)))
        // Arrival order must not decide it, so the reversed list gives the same answer.
        assertEquals(wifi, preferredNetwork(listOf(wifi, cellular)))
    }

    @Test
    fun aWiredLinkOutranksWifi() {
        val wifi = net(NetTransport.Wifi)
        val ethernet = net(NetTransport.Ethernet)
        assertEquals(ethernet, preferredNetwork(listOf(wifi, ethernet)))
    }

    @Test
    fun validationOutranksTheTransport() {
        // The case this ordering exists for: a hotel Wi-Fi behind a sign-in page is not
        // validated, and a tunnel built over it reaches nothing. LTE that works wins.
        val captiveWifi = net(NetTransport.Wifi, validated = false, captivePortal = true)
        val cellular = net(NetTransport.Cellular, validated = true)
        assertEquals(cellular, preferredNetwork(listOf(captiveWifi, cellular)))
    }

    @Test
    fun anUnvalidatedWifiStillWinsWhenNothingElseWorksEither() {
        val captiveWifi = net(NetTransport.Wifi, validated = false, captivePortal = true)
        val deadCellular = net(NetTransport.Cellular, validated = false)
        val chosen = preferredNetwork(listOf(deadCellular, captiveWifi))
        assertEquals(captiveWifi, chosen)
        // And the caller is told about the portal for the network it actually settled on, which
        // is what lets the service tell the user to sign in rather than stay silent.
        assertTrue(chosen!!.captivePortal)
    }

    @Test
    fun aNetworkWhoseCapabilitiesHaveNotArrivedYetLosesToOneThatHas() {
        // `onAvailable` tracks a network before `onCapabilitiesChanged` describes it, so a blank
        // entry must never displace a network already known to work.
        val blank = TrackedNetwork(handle = 99, seq = 99)
        val wifi = net(NetTransport.Wifi)
        assertEquals(wifi, preferredNetwork(listOf(wifi, blank)))
    }

    @Test
    fun theNewestWinsAmongOtherwiseIdenticalNetworks() {
        val first = net(NetTransport.Cellular)
        val second = net(NetTransport.Cellular)
        assertEquals(second, preferredNetwork(listOf(first, second)))
        assertEquals(second, preferredNetwork(listOf(second, first)))
    }

    // ---- what counts as a handover

    @Test
    fun movingBetweenTwoNetworksIsAHandover() {
        assertTrue(isHandover(previous = 1L, chosen = 2L))
    }

    @Test
    fun aHandoverSurvivesTheGapBetweenLosingOneAndGainingTheNext() {
        // Dropping Wi-Fi reports onLost before cellular's onAvailable, so the momentary null in
        // between must not turn the most ordinary handover on the device into two non-events.
        // The caller compares against the last network *used*, not the last value published.
        val wifi = 1L
        val cellular = 2L
        assertFalse("gap itself is not a handover", isHandover(previous = wifi, chosen = null))
        assertTrue("crossing the gap is", isHandover(previous = wifi, chosen = cellular))
    }

    @Test
    fun aFirstAttachIsNotAHandover() {
        // Nothing was dialled over a previous network, so there is no stale connection to reset.
        assertFalse(isHandover(previous = null, chosen = 1L))
        assertFalse(isHandover(previous = null, chosen = null))
    }

    @Test
    fun reappearingOnTheSameNetworkIsNotAHandover() {
        // A capabilities update on the network already in use re-publishes the same handle.
        assertFalse(isHandover(previous = 7L, chosen = 7L))
    }
}
