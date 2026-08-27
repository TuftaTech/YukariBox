package dev.yukaribox.vpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.LatencyTier
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.NodeGeo
import dev.yukaribox.vpn.core.ServiceMode
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.RouteRepository
import dev.yukaribox.vpn.ui.kit.BrandTopBar
import dev.yukaribox.vpn.ui.kit.GhostButton
import dev.yukaribox.vpn.ui.kit.Notice
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.PowerButton
import dev.yukaribox.vpn.ui.kit.PrimaryButton
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.swapSpec
import dev.yukaribox.vpn.ui.kit.YukariHero
import dev.yukaribox.vpn.ui.kit.YukariWorldMap
import dev.yukaribox.vpn.ui.theme.CardTitle
import dev.yukaribox.vpn.ui.theme.MicroLabel
import dev.yukaribox.vpn.ui.theme.MicroLabelLarge
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.vpn.TunnelLauncher
import dev.yukaribox.vpn.vpn.splitTunnelInUse

/**
 * Home: one screen that answers "am I protected, and by what".
 *
 * The whole screen is arranged around one control. The connect circle is the only round
 * thing in the app and the only element that changes appearance with state, so a glance
 * at it is the answer; the words above and below it, and the line under the location
 * card, spell that answer out in three progressively more explicit forms.
 *
 * Nothing else competes for the space: the server list is its own screen, the traffic
 * figures are their own tab, and behind the circle sit the two pieces of artwork — the
 * network map, a computed frame of coastlines, hubs and routes in the palette's lightest
 * neutral, and Yukari as a greyscale raster derivative of the reference art rather than as
 * a drawn silhouette.
 */
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onOpenServers: () -> Unit,
    onToggleConnection: () -> Unit,
) {
    val view = statusView(
        TunnelController.state,
        TunnelController.failClosed,
        TunnelController.unprotected,
    )
    Column(Modifier.fillMaxSize()) {
        BrandTopBar(
            title = stringResource(R.string.app_name),
            onMenu = onOpenDrawer,
            menuContentDescription = stringResource(R.string.cd_menu),
        )
        StoreNotices()
        HeroStage(view = view, onToggleConnection = onToggleConnection, modifier = Modifier.weight(1f))
        Column(
            Modifier.padding(horizontal = ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            // A crossfade, not a cut. These three cards answer one question in three
            // states, and swapping them on the frame the tunnel changes state was the one
            // moment the interface looked like it had reloaded itself. The size travels on
            // the same spec, so the fail-closed card growing over her legs is one movement
            // rather than a jump followed by a fade; `clip = false` keeps the outgoing card
            // whole while it goes.
            // Hoisted: `transitionSpec` is not a composable scope, so the specs cannot be
            // asked for inside it.
            val fade = swapSpec<Float>()
            val resize = swapSpec<IntSize>()
            AnimatedContent(
                targetState = statusSlot(view),
                transitionSpec = {
                    ContentTransform(
                        targetContentEnter = fadeIn(fade),
                        initialContentExit = fadeOut(fade),
                        sizeTransform = SizeTransform(clip = false) { _, _ -> resize },
                    )
                },
                label = "statusCard",
            ) { slot ->
                when (slot) {
                    StatusSlot.FailClosed -> FailClosedCard(
                        view = view,
                        onReconnect = onToggleConnection,
                    )
                    StatusSlot.Connected -> ConnectedBanner(onOpenServers = onOpenServers)
                    StatusSlot.Location -> LocationCard(onClick = onOpenServers)
                }
            }
            SecurityLine(view)
        }
        Spacer(Modifier.height(TAIL_GAP))
    }
}

/**
 * The connect control, with the map behind it and the portrait beside it.
 *
 * A stack, not a column. The mockup runs the halftone *behind* the ring's lower edge and
 * sets the caption over its dots, with Yukari standing on the location card and her hair
 * level with the map's top edge; a column can express none of that. Three layers, painted
 * back to front — map, portrait, control — so no piece of artwork can land on top of the
 * two words that say what the circle is doing.
 *
 * Everything in the stack is anchored to the stage's **top**, and the block below it to the
 * bottom bar, which is what makes the composition survive the states that change the card's
 * height. The fail-closed card is three lines and two buttons taller than the location
 * card, and all that does now is raise the card over more of her legs. Anchoring the
 * artwork to the *bottom* of the stage instead — as the first overlaid version did — let a
 * taller card lift the portrait into the caption; the column that replaced it fixed the
 * collision by dropping the artwork below the caption, which cost the mockup's whole
 * composition, leaving the map under the circle rather than behind it and 150 dp lower than
 * the mockup puts it.
 *
 * Two things this cannot follow the reference on, both consequences of the screen rather
 * than of the design:
 *
 * - **The caption's length.** `TAP TO CONNECT` is 90 dp of ink; the Russian caption is
 *   212 dp and reaches x 307 of a 407 dp screen — through the column her head would
 *   occupy at the mockup's height. [HERO_TOP] therefore seats her below the caption's line
 *   box rather than level with the ring's centre, which puts her hair 51 dp under the map's
 *   top edge instead of 11 dp over it. Her ink clears the caption's in both locales by
 *   construction, so the two never have to be measured against each other again.
 * - **The surplus height.** The panel the numbers come from is 719 dp tall at this screen's
 *   width; the device is 904. None of the extra 185 dp can go into the artwork — the map is
 *   width-locked (full bleed, and within a pixel of 1:1 with the grid at that width) and
 *   the ring is a measured 148 dp — so it goes into the two gaps the mockup also has:
 *   [TOP_GAP] above the state label and [TAIL_GAP] under the security sentence.
 *
 * What that buys, against the mockup's own fractions of panel height: the portrait lands
 * at 41-70% of the screen against its 34-68%, the location card at 68-76% against 67-78%,
 * and the security sentence at 80% against 82%. The map is stated in dp instead — 240 dp
 * to 440.5 dp — because its height is now the projection's, not a crop's.
 */
@Composable
private fun HeroStage(view: StatusView, onToggleConnection: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        // Full bleed, and the painter takes the height from there: the asset is emitted at
        // exactly this width, so scaling it would be the one thing that can make a
        // hairline lattice beat against the pixel grid.
        YukariWorldMap(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = MAP_TOP)
                .fillMaxWidth(),
        )
        YukariHero(
            Modifier
                // Flush with the right edge, not bled past it, which is where the mockup
                // puts her too (`measured` — her ink ends at x 406.5 of a 406.8 dp panel).
                // This drawing stands square to the viewer, so its box is its silhouette
                // and any bleed at all cuts a shoulder or a hem corner off.
                .align(Alignment.TopEnd)
                .offset(y = HERO_TOP)
                .height(HERO_HEIGHT)
                .aspectRatio(HERO_ASPECT),
        )
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(TOP_GAP))
            // Both words on the circle crossfade for the same reason the card does:
            // the state changes in one place at one moment, and it should read as one
            // change. Crossfade rather than AnimatedContent — the box must not animate its
            // width, or a centred label would breathe sideways as the word changes.
            Crossfade(targetState = view, animationSpec = swapSpec(), label = "stateLabel") { state ->
                Text(
                    stringResource(stateLabelRes(state)).uppercase(),
                    style = stateLabelStyle(state),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(LABEL_GAP))
            PowerButton(
                onClick = onToggleConnection,
                contentDescription = stringResource(connectDescriptionRes(view)),
                filled = view.filled,
                busy = view.isTransitioning,
                // Tappable *while connecting*, and that tap cancels: a node that is slow or
                // dead otherwise costs the user all three attempts and their backoff before
                // the control comes back. The service has always supported it — `stopTunnel`
                // sets `stopRequested` before it queues anything and the connect loop polls
                // that flag between backoff slices — so what stood in the way was this line.
                //
                // `Disconnecting` stays disabled, and the asymmetry is the whole point: a tap
                // there is a *Start* (the state is not `isActive`), which is the case the
                // guard was written for — it used to un-cancel a Stop whose teardown was
                // still queued behind a sleeping slice and let the doomed loop arm the kill
                // switch anyway.
                enabled = view != StatusView.Disconnecting,
            )
            Spacer(Modifier.height(CAPTION_GAP))
            Crossfade(targetState = view, animationSpec = swapSpec(), label = "caption") { state ->
                Text(
                    stringResource(captionRes(state)).uppercase(),
                    style = MicroLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The state label's weight, and the whole of what the label spends on state.
 *
 * It used to be two hues — amber for "packets are being dropped on purpose", red for
 * "they are going out in the clear" — and there is no hue left anywhere in the
 * interface. The colour does not move either: the reference sets this label in ink in the
 * one state it shows (`measured` — its stroke cores bottom out at 0, which a mid-grey
 * cannot do), so ink is what every state gets and the two the user never asked for
 * escalate by **weight** instead, which is the mechanism `SKILL.md` §9 names for the
 * fail-closed pair. What separates *blocked* from *unprotected* is the wording, the
 * shield glyph ([statusGlyph]) and the fail-closed card that replaces the location card.
 */
private fun stateLabelStyle(view: StatusView) = when (view) {
    StatusView.Blocked, StatusView.Unprotected -> MicroLabelLarge.copy(fontWeight = FontWeight.Bold)
    else -> MicroLabelLarge
}

/**
 * The server slot while the tunnel is off.
 *
 * It shows the node the toggle would actually dial, not an aspiration: `Optimal
 * Location / Fastest Server` is what it says only when nothing is selected, because
 * that is the one case where the app has no answer yet. With a node selected it names
 * that node and its group, so the circle above it and this card can never disagree.
 */
@Composable
private fun LocationCard(onClick: () -> Unit) {
    val selected = NodeRepository.selected()
    val group = NodeRepository.subscriptions.firstOrNull { it.id == NodeRepository.selectedSubId }
    val rawName = selected?.node?.displayName
    // Keyed on the raw label: a feed sets no bound on how long a node name is, and this
    // card recomposes on every stats tick.
    val plainName = remember(rawName) { rawName?.let { NodeGeo.plainName(it) } }
    // The group's name is a name too, and feeds prefix those with a flag emoji as
    // readily as they do a node's. Remembered for the same reason as the line above.
    val rawGroup = group?.name
    val groupName = remember(rawGroup) { rawGroup?.let { groupLabel(it) } }
    val title = plainName ?: stringResource(R.string.home_location_empty_title)
    val subtitle = when {
        selected == null -> stringResource(R.string.home_location_empty_hint)
        else -> listOfNotNull(
            groupName,
            latencySummary(selected.latencyMs),
        ).joinToString(SEPARATOR)
    }
    PaperCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = LOCATION_CARD_HEIGHT),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A bare glyph, not a plated one: measured, the reference's location card
            // has no plate behind its globe (189 plate-grey pixels in a 6,624 px
            // region is antialiasing), and a 44 dp circle here would be a second round
            // control on the one screen whose subject is a circle.
            Icon(
                YukariIcons.Globe,
                null,
                Modifier.size(GLOBE_SIZE),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = CardTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** The measured latency as a phrase, or nothing at all when it has never been probed. */
@Composable
private fun latencySummary(latencyMs: Int): String? = when (LatencyTier.of(latencyMs)) {
    LatencyTier.Untested -> null
    LatencyTier.Testing -> stringResource(R.string.lat_testing)
    LatencyTier.Failed -> stringResource(R.string.lat_timeout)
    else -> stringResource(R.string.lat_ms, latencyMs)
}

/**
 * The plain-language verdict, centred under the card.
 *
 * Four sentences for four situations, and the shield changes shape with them — filled
 * with a check when traffic is going through the tunnel, filled plain when the kill
 * switch is holding it, outlined when the tunnel is simply off, struck through when the
 * kill switch itself failed. The glyph comes from [statusGlyph] so the blocked and
 * unprotected readings cannot collapse into one, which with no hue in the palette is
 * half of what tells them apart here.
 *
 * The other half is weight. The sentence is one ink in every state — `measured`, both the
 * bold word and the words around it bottom out at 15 on the reference's own unprotected
 * panel, so the lighter *look* of the regular run is stroke weight, not a second colour —
 * with the state word alone set Bold. That is why the five `security_*` strings carry
 * `<b>` markup (escaped, so `getString` hands the tags through) and are parsed with
 * [AnnotatedString.fromHtml]. Parsing is `remember`ed on the sentence: this row
 * recomposes once a second while a session is up.
 *
 * The shield beside it is the one element that *is* muted (`measured` — 107–123, i.e.
 * `iconMuted`, against a sentence at 15). It can afford to be: its fill carries the
 * state, and fill survives any grey.
 */
@Composable
private fun SecurityLine(view: StatusView) {
    // The scope is read here rather than folded into `view`, because it is a property of
    // the configuration and not of the tunnel's lifecycle: `statusView` is shared with the
    // servers screen and the banner, which have no security sentence to qualify.
    val settings = SettingsStore.data
    val context = LocalContext.current
    val scope = tunnelScope(
        proxyOnly = settings.serviceMode == ServiceMode.ProxyOnly,
        splitTunnel = splitTunnelInUse(
            settings.perAppProxyInclude,
            settings.perAppPackages,
            context.packageName,
        ),
    )
    val sentence = stringResource(securityRes(view, scope))
    val marked = remember(sentence) { AnnotatedString.fromHtml(sentence) }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            statusGlyph(view),
            null,
            Modifier.padding(end = 8.dp).size(SHIELD_SIZE),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            marked,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The two fail-closed states, and the only place the block can be released.
 *
 * Dropping the blocking TUN trades "nothing leaves the device" for an unprotected
 * connection, so it must never be a stray tap on a persistent control — it is a
 * deliberate, labelled decision taken on the screen that explains what is happening.
 * The two states get different wording because they mean opposite things: one is the
 * kill switch working, the other is the kill switch having failed.
 *
 * Which is also why only *one* of them offers the release. `unprotected` means the
 * blocking TUN could **not** be installed: the service has already torn itself down and
 * there is nothing being held, so an "unblock" button there offers to undo something that
 * does not exist — and, wired to `TunnelLauncher.stop`, it reached a service that is gone
 * and left the card exactly as it was. The reconnect action is the whole card in that
 * state, and it goes through the screen's own toggle (the connect branch, with its
 * VPN-consent re-prompt).
 */
@Composable
private fun FailClosedCard(view: StatusView, onReconnect: () -> Unit) {
    val context = LocalContext.current
    PaperCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(stateLabelRes(view)).uppercase(),
            style = MicroLabelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (view == StatusView.Blocked) {
                    R.string.home_blocked_actions
                } else {
                    R.string.home_unprotected_actions
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The toggle's own connect path, not TunnelLauncher.reconnect: the state
            // machine reads Disconnected in both fail-closed views, and `reconnect` is a
            // documented no-op when the tunnel is idle — wired to it, this button logged
            // one line and did nothing, leaving "Unblock" as the only control that
            // responded. Going through the toggle also keeps the VPN-consent re-prompt.
            PrimaryButton(
                text = stringResource(R.string.btn_reconnect_short),
                onClick = { Logs.tap("home:re-arm"); onReconnect() },
                icon = Icons.Default.Refresh,
            )
            if (view == StatusView.Blocked) {
                GhostButton(
                    text = stringResource(R.string.btn_release_short),
                    onClick = { Logs.tap("unblock"); TunnelLauncher.stop(context) },
                )
            }
        }
    }
}

/**
 * The three `loadFailed` flags, surfaced together.
 *
 * A store whose file could not be parsed has silently reset to defaults, and an empty
 * server list looks exactly like a fresh install — which is the failure mode
 * `DurableFile` exists to prevent. The flags are read-only, so the notice has no action
 * beyond naming the file that was quarantined.
 *
 * Emphasised, which with no hue left means a heavier outline and a Bold sentence: a
 * quarantined store is the one condition on this screen that has to outrank everything
 * around it.
 */
@Composable
private fun StoreNotices() {
    val notices = listOfNotNull(
        // First, because nothing else matters if the core never came up: with it down every
        // connect fails and no other notice explains why.
        R.string.notice_core_failed.takeIf { !TunnelController.coreReady },
        R.string.notice_nodes_corrupt.takeIf { NodeRepository.loadFailed },
        R.string.notice_settings_corrupt.takeIf { SettingsStore.loadFailed },
        R.string.notice_routes_corrupt.takeIf { RouteRepository.loadFailed },
    )
    if (notices.isEmpty()) return
    Column(
        Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        notices.forEach { res -> Notice(text = stringResource(res), emphasis = true) }
    }
}

/**
 * Where the artwork sits. Both numbers are offsets from the top of the stage — i.e. from
 * the app bar's bottom edge — because the stage's own height moves with the card below it,
 * and a composition measured from the bottom is a composition that changes with the state.
 *
 * [TOP_GAP] is the mockup's own gap from the app bar to the state label (61 dp there,
 * `measured`); the 86 dp it replaced is most of why everything below sat too low. [MAP_TOP]
 * lands the map's top edge on the ring's bottom arc, which is both the fraction of
 * screen height the mockup puts it at (0.378, ±4 dp) and as much overlap as this screen can
 * take before the map's centre climbs out of the middle of it; the mockup itself overlaps by
 * 48 dp, which only its shorter panel can afford. [HERO_TOP] is the caption's line box plus
 * a hair of clearance ([HeroStage] explains why it cannot be higher).
 */
private val TOP_GAP = 62.dp
private val MAP_TOP = 240.dp
private val HERO_TOP = 273.dp

/**
 * The portrait's height — and with [HERO_ASPECT], everything about where she lands.
 *
 * Width is the dimension that maps 1:1 off the mockup: its panel is 406.8 dp wide against
 * this screen's 406.7, and the mockup's own figure is `measured` there at 167 dp of ink
 * from x 239.6 to the panel's right edge, her elbow 2.6 px short of the ring's stroke.
 * What ships is a **standing** figure with her arms crossed rather than that
 * hands-behind-head pose, and its silhouette is far narrower for its height (0.481 against
 * 0.705). So the number held constant here is the **height**, not the width: 262 dp keeps
 * the composition the mockup states — her hair up in the map's band, her thighs cut by the
 * location card's top edge at 613 dp — and spends 126 dp of width on it instead of 167.
 *
 * Where that puts her, `measured` off the asset: ink x 281–406.5, head x 284–402.6 and
 * y 371–471. The ring's stroke ends at 277.5 dp, so she clears it by 3.5 — the same
 * relationship the mockup has at 2.6 px — and her head lands wholly below the ring's bottom
 * arc at 333 dp rather than beside it, which keeps the screen's two round masses off one
 * line. Her ink still reaches into the column the Russian caption occupies (it ends at
 * x 307), so [HERO_TOP] seating her under the caption is load-bearing at this width too.
 */
private val HERO_HEIGHT = 262.dp

/**
 * Width over height of `yukari_hero` (378x786 px at xxhdpi). Explicit rather than left to
 * the painter's intrinsics so the geometry above is readable here — but it has to *match*
 * the asset: with `ContentScale.Fit` a box of the wrong ratio letterboxes her, which is a
 * silent few dp of drift between what this file computes and what the screen draws.
 * `make_mascot_assets.py` emits this figure at `dp_height = 262` for the same reason —
 * change a slot's size there and here in one commit.
 */
private const val HERO_ASPECT = 378f / 786f

/**
 * Gaps between the circle and its two labels. `PowerButton` is 166 dp around a 148 dp
 * ring, so 9 dp of halo sits inside its box on each side and these are what is left of
 * the measured 11 dp above and 13 dp below (`SKILL.md` §7.6).
 */
private val LABEL_GAP = 2.dp
private val CAPTION_GAP = 4.dp

/**
 * The card block's own rhythm, and what is left under it.
 *
 * [CARD_GAP] is the mockup's 30 dp from the card to the security sentence (`measured` — 550
 * to 580 on its panel); at the 10 dp it replaced, the sentence read as part of the card
 * rather than as the verdict on it. [TAIL_GAP] is the surplus the mockup's shorter panel
 * never has to place, and it is what fixes the card's top edge: from the bar's top at 824
 * dp, less 82, 22, 30 and 77, the card starts at 613 dp — 68% of the screen, where the
 * mockup puts it, and 90 dp above where a 12 dp tail left it.
 */
private val CARD_GAP = 30.dp
private val TAIL_GAP = 82.dp

/** The location card: 378 x 77 dp with a bare 30 dp globe, both `measured`. */
private val LOCATION_CARD_HEIGHT = 77.dp
private val GLOBE_SIZE = 30.dp

/** The shield beside the security sentence. `measured` — 18 px in a 24 dp box. */
private val SHIELD_SIZE = 18.dp

/** Between the group name and the latency on the location card. */
private const val SEPARATOR = " · "
