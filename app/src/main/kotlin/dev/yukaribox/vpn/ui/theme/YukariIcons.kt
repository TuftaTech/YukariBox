package dev.yukaribox.vpn.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs the mockup uses that `material-icons-core` does not ship.
 *
 * Only the core icon artifact is on the classpath (the extended one is ~1000 more vectors
 * for the sake of a dozen), so anything outside its ~50-glyph set is here: the power symbol
 * in the connect circle, the shields on the security line, the tank on her shirt, and so
 * on. Where core does have a usable glyph — Menu, Home, Person, Star, Share, Settings, Add,
 * Search, Close, ArrowBack, KeyboardArrowRight, Refresh, MoreVert — the app uses that
 * rather than a near-duplicate here.
 *
 * **The geometry is Google's own.** Each glyph below carries the `d` attribute of the
 * matching [Material Symbols](https://github.com/google/material-design-icons) SVG,
 * verbatim, in that set's own 960-unit coordinate system (`viewBox="0 -960 960 960"`) —
 * which is why [symbol] declares a 960 viewport and one group translated 960 down instead
 * of transforming anything. Those files are Apache-2.0, which flows one way into this app's
 * GPL-3.0-or-later; keep this notice with them. Adding path data is not adding a
 * dependency: `material-icons-extended` still stays off the classpath.
 *
 * What that replaces is a set plotted by hand in this file, and the difference was visible
 * at 24 dp: the power symbol's ring had no gap under its stem, the globe had two meridians
 * and a bar, the help mark was a hook rather than a question mark, and the folder and the
 * page were unrounded boxes. Do not re-plot a glyph here — take it from the set, at the
 * fill the mockup draws, and note the name in its KDoc.
 *
 * [Tank] is the exception and stays hand-drawn: it is the print on Yukari's shirt rather
 * than an interface glyph, and there is no tank in Material.
 */
object YukariIcons {

    /**
     * One Material Symbols glyph, in its own space.
     *
     * The set draws in a 960x960 box whose y runs -960..0, so the group's translation is
     * what puts it back inside the viewport. [ICON_SIZE] then scales the whole thing to the
     * 24 dp every call site expects, exactly as a material icon does.
     */
    private fun symbol(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = SYMBOL_VIEWPORT,
        viewportHeight = SYMBOL_VIEWPORT,
    )
        .addGroup(translationY = SYMBOL_VIEWPORT)
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .clearGroup()
        .build()

    /** The hand-drawn exception's own builder: a 24 dp viewport, filled paths. */
    private inline fun icon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun ImageVector.Builder.filled(
        fillType: PathFillType = PathFillType.NonZero,
        pathBuilder: PathBuilder.() -> Unit,
    ) = path(fill = SolidColor(Color.Black), pathFillType = fillType, pathBuilder = pathBuilder)

    /** A circle as four cubics — the tank's road wheels. */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        val k = r * KAPPA
        moveTo(cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        close()
    }

    /** Closed polygon from flat x,y pairs. */
    private fun PathBuilder.polygon(vararg xy: Float) {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) {
            lineTo(xy[i], xy[i + 1])
            i += 2
        }
        close()
    }

    // ---- brand ---------------------------------------------------------------

    /** Panzer IV side silhouette — the print on Yukari's shirt, drawn here by hand. */
    val Tank: ImageVector by lazy {
        icon("Yukari.Tank") {
            filled {
                polygon(9f, 7f, 15f, 7f, 15.6f, 9.6f, 22f, 9.6f, 22f, 11f, 8.4f, 11f)
            }
            filled { polygon(5.5f, 11f, 18.5f, 11f, 19.8f, 14f, 4.2f, 14f) }
            filled(PathFillType.EvenOdd) {
                moveTo(6f, 14.5f)
                lineTo(18f, 14.5f)
                curveTo(20.2f, 14.5f, 21.5f, 16f, 21.5f, 17.25f)
                curveTo(21.5f, 18.5f, 20.2f, 20f, 18f, 20f)
                lineTo(6f, 20f)
                curveTo(3.8f, 20f, 2.5f, 18.5f, 2.5f, 17.25f)
                curveTo(2.5f, 16f, 3.8f, 14.5f, 6f, 14.5f)
                close()
                circle(6.5f, 17.25f, 1.15f)
                circle(12f, 17.25f, 1.15f)
                circle(17.5f, 17.25f, 1.15f)
            }
        }
    }

    // ---- tunnel state --------------------------------------------------------

    /**
     * The IEC power symbol: a broken ring with a stem through the gap. This is the
     * glyph inside the 152 dp connect circle on Home and inside the small round
     * button on the connected banner, and it is the only thing in the app that says
     * "this is the switch".
     *
     * Material Symbols `power_settings_new`, fill 1.
     */
    val Power: ImageVector by lazy {
        symbol(
            "Yukari.Power",
            "M440-440v-400h80v400h-80Zm40 320q-74 0-139.5-28.5T226-226q-49-49-77.5-114.5T120-480q0-80 33-151t93" +
                "-123l58 58q-50 38-77 95t-27 121q0 116 82 198t198 82q117 0 198.5-82T760-480q0-64-26.5-121T656-696l5" +
                "8-58q60 52 93 123t33 151q0 74-28.5 139.5t-77 114.5q-48.5 49-114 77.5T480-120Z",
        )
    }

    /**
     * Pause bars — the toggle's glyph while a session is live in a compact slot.
     *
     * Material Symbols `pause`, fill 1.
     */
    val Pause: ImageVector by lazy {
        symbol(
            "Yukari.Pause",
            "M560-200v-560h160v560H560Zm-320 0v-560h160v560H240Z",
        )
    }

    /**
     * Navigation arrow — the FAB while a session is live ("traffic is moving").
     *
     * Material Symbols `near_me`, fill 1.
     */
    val Nav: ImageVector by lazy {
        symbol(
            "Yukari.Nav",
            "M516-120 402-402 120-516v-56l720-268-268 720h-56Z",
        )
    }

    /**
     * The same arrow struck through — the FAB while the tunnel is off.
     *
     * Material Symbols `near_me_disabled`, fill 1.
     */
    val NavOff: ImageVector by lazy {
        symbol(
            "Yukari.NavOff",
            "M516-120 402-402 120-516v-56l195-73-203-203 57-57 736 736-57 57-203-203-73 195h-56Zm191-361L481-70" +
                "7l359-133-133 359Z",
        )
    }

    // ---- protection state ----------------------------------------------------

    /**
     * Shield outline — the security line under the location card. Outline rather than
     * filled because that line is a *statement*, not a badge: a solid black shield
     * next to "your connection is not protected" reads as reassurance.
     *
     * Material Symbols `shield`, outline.
     */
    val Shield: ImageVector by lazy {
        symbol(
            "Yukari.Shield",
            "M480-80q-139-35-229.5-159.5T160-516v-244l320-120 320 120v244q0 152-90.5 276.5T480-80Zm0-84q104-33 " +
                "172-132t68-220v-189l-240-90-240 90v189q0 121 68 220t172 132Zm0-316Z",
        )
    }

    /**
     * The same shield with nothing inside it, solid.
     *
     * "The kill switch is holding": every packet is being dropped on purpose, which is
     * the one state where the app is doing something rather than merely not protecting
     * anything. §9 of the design system reads the fill as the signal — outline for a
     * clean stop, solid for a block being enforced — so this and [Shield] are the pair
     * that keeps a fail-closed session from rendering as "off". No check inside it:
     * that is [ShieldOk]'s, and it means traffic is flowing.
     *
     * Material Symbols `shield`, fill 1.
     */
    val ShieldFilled: ImageVector by lazy {
        symbol(
            "Yukari.ShieldFilled",
            "M480-80q-139-35-229.5-159.5T160-516v-244l320-120 320 120v244q0 152-90.5 276.5T480-80Z",
        )
    }

    /**
     * The same shield with a check inside — "protected".
     *
     * Material Symbols `verified_user`, fill 1.
     */
    val ShieldOk: ImageVector by lazy {
        symbol(
            "Yukari.ShieldOk",
            "m438-338 226-226-57-57-169 169-84-84-57 57 141 141Zm42 258q-139-35-229.5-159.5T160-516v-244l320-12" +
                "0 320 120v244q0 152-90.5 276.5T480-80Z",
        )
    }

    /**
     * The shield outline struck through — "not protected at all".
     *
     * The third member of the set, and it exists for a reason that is not decorative:
     * [Shield] alone had to stand for both "the kill switch is holding, packets are
     * being dropped" and "the kill switch failed, packets are in the clear", which are
     * opposites. With no hue left in the palette a shared glyph left them
     * indistinguishable. Struck the way [NavOff] strikes [Nav], so the negation reads
     * as the same idea in both places.
     *
     * Material Symbols `remove_moderator`, fill 1.
     */
    val ShieldOff: ImageVector by lazy {
        symbol(
            "Yukari.ShieldOff",
            "M754-318 272-802l208-78 320 120v244q0 51-11.5 101T754-318Zm38 262L662-186q-38 39-84.5 65.5T480-80q" +
                "-139-35-229.5-159.5T160-516v-172L56-792l56-56 736 736-56 56Z",
        )
    }

    // ---- lists and rows -----------------------------------------------------

    /**
     * Favourite, unset. Drawn here rather than borrowed from core because the pair
     * has to share one geometry: core ships a filled `Star` but its outline
     * counterpart lives in the extended artifact, so an outline from elsewhere would
     * jump a pixel when the row is starred.
     *
     * Material Symbols `star`, outline.
     */
    val StarOutline: ImageVector by lazy {
        symbol(
            "Yukari.StarOutline",
            "m354-287 126-76 126 77-33-144 111-96-146-13-58-136-58 135-146 13 111 97-33 143ZM233-120l65-281L80-" +
                "590l288-25 112-265 112 265 288 25-218 189 65 281-247-149-247 149Zm247-350Z",
        )
    }

    /**
     * Favourite, set.
     *
     * Material Symbols `star`, fill 1.
     */
    val StarFilled: ImageVector by lazy {
        symbol(
            "Yukari.StarFilled",
            "m233-120 65-281L80-590l288-25 112-265 112 265 288 25-218 189 65 281-247-149-247 149Z",
        )
    }

    /**
     * Folder outline — "My Groups" in the drawer.
     *
     * Material Symbols `folder`, outline.
     */
    val Folder: ImageVector by lazy {
        symbol(
            "Yukari.Folder",
            "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h240l80 80h320q33 0 56.5 23.5T880-640v4" +
                "00q0 33-23.5 56.5T800-160H160Zm0-80h640v-400H447l-80-80H160v480Zm0 0v-480 480Z",
        )
    }

    /**
     * Page with a folded corner and three ruled lines — the log.
     *
     * Material Symbols `description`, outline.
     */
    val Document: ImageVector by lazy {
        symbol(
            "Yukari.Document",
            "M320-240h320v-80H320v80Zm0-160h320v-80H320v80ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T" +
                "240-880h320l240 240v480q0 33-23.5 56.5T720-80H240Zm280-520v-200H240v640h480v-440H520ZM240-800v200-" +
                "200 640-640Z",
        )
    }

    /**
     * Question mark in a ring — "Help & Support".
     *
     * Material Symbols `help`, fill 1.
     */
    val Help: ImageVector by lazy {
        symbol(
            "Yukari.Help",
            "M478-240q21 0 35.5-14.5T528-290q0-21-14.5-35.5T478-340q-21 0-35.5 14.5T428-290q0 21 14.5 35.5T478-" +
                "240Zm-36-154h74q0-33 7.5-52t42.5-52q26-26 41-49.5t15-56.5q0-56-41-86t-97-30q-57 0-92.5 30T342-618l" +
                "66 26q5-18 22.5-39t53.5-21q32 0 48 17.5t16 38.5q0 20-12 37.5T506-526q-44 39-54 59t-10 73Zm38 314q-" +
                "83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156" +
                " 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z",
        )
    }

    // ---- settings and tools -------------------------------------------------

    /**
     * Half-filled ring — "Appearance". The one glyph in the set that encodes its own
     * meaning geometrically: light on one side, dark on the other, which is what the
     * setting picks between.
     *
     * Material Symbols `contrast`, fill 1.
     */
    val Appearance: ImageVector by lazy {
        symbol(
            "Yukari.Appearance",
            "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880" +
                "q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm40-83q" +
                "119-15 199.5-104.5T800-480q0-123-80.5-212.5T520-797v634Z",
        )
    }

    /**
     * 2x2 app grid — the per-app proxy picker.
     *
     * Material Symbols `apps`, fill 1.
     */
    val Apps: ImageVector by lazy {
        symbol(
            "Yukari.Apps",
            "M240-160q-33 0-56.5-23.5T160-240q0-33 23.5-56.5T240-320q33 0 56.5 23.5T320-240q0 33-23.5 56.5T240-" +
                "160Zm240 0q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T48" +
                "0-160Zm240 0q-33 0-56.5-23.5T640-240q0-33 23.5-56.5T720-320q33 0 56.5 23.5T800-240q0 33-23.5 56.5T" +
                "720-160ZM240-400q-33 0-56.5-23.5T160-480q0-33 23.5-56.5T240-560q33 0 56.5 23.5T320-480q0 33-23.5 5" +
                "6.5T240-400Zm240 0q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5" +
                " 56.5T480-400Zm240 0q-33 0-56.5-23.5T640-480q0-33 23.5-56.5T720-560q33 0 56.5 23.5T800-480q0 33-23" +
                ".5 56.5T720-400ZM240-640q-33 0-56.5-23.5T160-720q0-33 23.5-56.5T240-800q33 0 56.5 23.5T320-720q0 3" +
                "3-23.5 56.5T240-640Zm240 0q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0" +
                " 33-23.5 56.5T480-640Zm240 0q-33 0-56.5-23.5T640-720q0-33 23.5-56.5T720-800q33 0 56.5 23.5T800-720" +
                "q0 33-23.5 56.5T720-640Z",
        )
    }

    /**
     * Globe with an equator and a meridian — servers, subscriptions, language.
     *
     * Material Symbols `language`, outline.
     */
    val Globe: ImageVector by lazy {
        symbol(
            "Yukari.Globe",
            "M480-80q-82 0-155-31.5t-127.5-86Q143-252 111.5-325T80-480q0-83 31.5-155.5t86-127Q252-817 325-848.5" +
                "T480-880q83 0 155.5 31.5t127 86q54.5 54.5 86 127T880-480q0 82-31.5 155t-86 127.5q-54.5 54.5-127 86" +
                "T480-80Zm0-82q26-36 45-75t31-83H404q12 44 31 83t45 75Zm-104-16q-18-33-31.5-68.5T322-320H204q29 50 " +
                "72.5 87t99.5 55Zm208 0q56-18 99.5-55t72.5-87H638q-9 38-22.5 73.5T584-178ZM170-400h136q-3-20-4.5-39" +
                ".5T300-480q0-21 1.5-40.5T306-560H170q-5 20-7.5 39.5T160-480q0 21 2.5 40.5T170-400Zm216 0h188q3-20 " +
                "4.5-39.5T580-480q0-21-1.5-40.5T574-560H386q-3 20-4.5 39.5T380-480q0 21 1.5 40.5T386-400Zm268 0h136" +
                "q5-20 7.5-39.5T800-480q0-21-2.5-40.5T790-560H654q3 20 4.5 39.5T660-480q0 21-1.5 40.5T654-400Zm-16-" +
                "240h118q-29-50-72.5-87T584-782q18 33 31.5 68.5T638-640Zm-234 0h152q-12-44-31-83t-45-75q-26 36-45 7" +
                "5t-31 83Zm-200 0h118q9-38 22.5-73.5T376-782q-56 18-99.5 55T204-640Z",
        )
    }

    /**
     * Branching route — the routing-rules screen.
     *
     * Material Symbols `alt_route`, fill 1.
     */
    val Routes: ImageVector by lazy {
        symbol(
            "Yukari.Routes",
            "M440-80v-200q0-56-17-83t-45-53l57-57q12 11 23 23.5t22 26.5q14-19 28.5-33.5T538-485q38-35 69-81t33-" +
                "161l-63 63-57-56 160-160 160 160-56 56-64-63q-2 143-44 203.5T592-425q-32 29-52 56.5T520-280v200h-8" +
                "0ZM248-633q-4-20-5.5-44t-2.5-50l-64 63-56-56 160-160 160 160-57 56-63-62q0 21 2 39.5t4 34.5l-78 19" +
                "Zm86 176q-20-21-38.5-49T263-575l77-19q10 27 23 46t28 34l-57 57Z",
        )
    }

    /**
     * Ascending bar chart — the Stats tab.
     *
     * Material Symbols `bar_chart`, fill 1.
     */
    val Stats: ImageVector by lazy {
        symbol(
            "Yukari.Stats",
            "M640-160v-280h160v280H640Zm-240 0v-640h160v640H400Zm-240 0v-440h160v440H160Z",
        )
    }

    /**
     * Radar sweep — a latency probe.
     *
     * Material Symbols `radar`, fill 1.
     */
    val Radar: ImageVector by lazy {
        symbol(
            "Yukari.Radar",
            "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880" +
                "q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q5" +
                "6 0 105.5-17.5T676-227l-57-57q-29 21-64.5 32.5T480-240q-100 0-170-70t-70-170q0-100 70-170t170-70q1" +
                "00 0 170 70t70 170q0 39-12 75t-33 65l57 57q32-41 50-91t18-106q0-134-93-227t-227-93q-134 0-227 93t-" +
                "93 227q0 134 93 227t227 93Zm0-160q22 0 42.5-5.5T561-342l-61-61q-5 2-10 2.5t-10 .5q-33 0-56.5-23.5T" +
                "400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 6-.5 11.5T557-458l60 60q11-18 17-38.5t6-43" +
                ".5q0-66-47-113t-113-47q-66 0-113 47t-47 113q0 66 47 113t113 47Z",
        )
    }

    /**
     * Supply crate with an inbound arrow — backup and restore.
     *
     * Material Symbols `archive`, fill 1.
     */
    val Backup: ImageVector by lazy {
        symbol(
            "Yukari.Backup",
            "m480-240 160-160-56-56-64 64v-168h-80v168l-64-64-56 56 160 160ZM200-120q-33 0-56.5-23.5T120-200v-4" +
                "99q0-14 4.5-27t13.5-24l50-61q11-14 27.5-21.5T250-840h460q18 0 34.5 7.5T772-811l50 61q9 11 13.5 24t" +
                "4.5 27v499q0 33-23.5 56.5T760-120H200Zm16-600h528l-34-40H250l-34 40Z",
        )
    }

    /**
     * QR finder pattern — import a node from an image.
     *
     * Material Symbols `qr_code_2`, fill 1.
     */
    val Qr: ImageVector by lazy {
        symbol(
            "Yukari.Qr",
            "M520-120v-80h80v80h-80Zm-80-80v-200h80v200h-80Zm320-120v-160h80v160h-80Zm-80-160v-80h80v80h-80Zm-4" +
                "80 80v-80h80v80h-80Zm-80-80v-80h80v80h-80Zm360-280v-80h80v80h-80ZM180-660h120v-120H180v120Zm-60 60" +
                "v-240h240v240H120Zm60 420h120v-120H180v120Zm-60 60v-240h240v240H120Zm540-540h120v-120H660v120Zm-60" +
                " 60v-240h240v240H600Zm80 480v-120h-80v-80h160v120h80v80H680ZM520-400v-80h160v80H520Zm-160 0v-80h-8" +
                "0v-80h240v80h-80v80h-80Zm40-200v-160h80v80h80v80H400Zm-190-90v-60h60v60h-60Zm0 480v-60h60v60h-60Zm" +
                "480-480v-60h60v60h-60Z",
        )
    }

    /**
     * Two sheets — copy to clipboard.
     *
     * Material Symbols `content_copy`, fill 1.
     */
    val Copy: ImageVector by lazy {
        symbol(
            "Yukari.Copy",
            "M360-240q-33 0-56.5-23.5T280-320v-480q0-33 23.5-56.5T360-880h360q33 0 56.5 23.5T800-800v480q0 33-2" +
                "3.5 56.5T720-240H360ZM200-80q-33 0-56.5-23.5T120-160v-560h80v560h440v80H200Z",
        )
    }

    /**
     * Eye — reveal a masked secret (the proxy-only password).
     *
     * Material Symbols `visibility`, fill 1.
     */
    val Eye: ImageVector by lazy {
        symbol(
            "Yukari.Eye",
            "M480-320q75 0 127.5-52.5T660-500q0-75-52.5-127.5T480-680q-75 0-127.5 52.5T300-500q0 75 52.5 127.5T" +
                "480-320Zm0-72q-45 0-76.5-31.5T372-500q0-45 31.5-76.5T480-608q45 0 76.5 31.5T588-500q0 45-31.5 76.5" +
                "T480-392Zm0 192q-146 0-266-81.5T40-500q54-137 174-218.5T480-800q146 0 266 81.5T920-500q-54 137-174" +
                " 218.5T480-200Z",
        )
    }

    /**
     * Eye with a stroke through it — hide a revealed secret.
     *
     * Material Symbols `visibility_off`, fill 1.
     */
    val EyeOff: ImageVector by lazy {
        symbol(
            "Yukari.EyeOff",
            "M792-56 624-222q-35 11-70.5 16.5T480-200q-151 0-269-83.5T40-500q21-53 53-98.5t73-81.5L56-792l56-56" +
                " 736 736-56 56ZM480-320q11 0 20.5-1t20.5-4L305-541q-3 11-4 20.5t-1 20.5q0 75 52.5 127.5T480-320Zm2" +
                "92 18L645-428q7-17 11-34.5t4-37.5q0-75-52.5-127.5T480-680q-20 0-37.5 4T408-664L306-766q41-17 84-25" +
                ".5t90-8.5q151 0 269 83.5T920-500q-23 59-60.5 109.5T772-302ZM587-486 467-606q28-5 51.5 4.5T559-574q" +
                "17 18 24.5 41.5T587-486Z",
        )
    }

    /** Every glyph here renders at the same size a material icon does. */
    private val ICON_SIZE = 24.dp

    /** Material Symbols' own drawing box. */
    private const val SYMBOL_VIEWPORT = 960f

    /** Circle-through-cubics constant, for the one glyph still drawn by hand. */
    private const val KAPPA = 0.5523f
}
