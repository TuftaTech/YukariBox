package dev.yukaribox.vpn.ui.kit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * The two pieces of artwork the reference is built around: the world map behind Home's
 * connect circle, and Yukari herself.
 *
 * Both are committed WebPs at five densities, and neither is drawn in Compose any more.
 * Yukari is traced out of the reference art by `design/tools/make_mascot_assets.py`: two
 * flat inks could not carry her — the reference draws fine line-art with a mid-grey hair
 * mass, layered strands and a legible shirt print, and at two tones that collapses into a
 * black helmet on a white oval. The map is *computed* rather than traced, by
 * `design/tools/make_wireframe_map.py`, from coastline data.
 *
 * She is **four drawings, one per slot** — [YukariHero] standing on Home, [YukariLean]
 * leaning into the Servers band, [YukariBust] in the drawer and the connected banner,
 * [YukariAvatar] in the profile circle — rather than three crops of two. Each slot frames
 * her differently, and the generator sizes them so her *hair mass* is comparable across
 * all four: matched on the drawing's box instead, she reads as standing at a different
 * distance on every screen.
 *
 * The difference between them is the tint. The map ships as a pure alpha mask and is
 * coloured here from `dot`, so one file serves both themes. Yukari ships as greyscale
 * with real alpha and is never tinted from the scheme: bound to `onSurface`/`surface` she
 * inverts in dark theme into a photographic negative rather than the same character.
 *
 * Every one of them is loaded through [rememberArt] and **not** through `painterResource`
 * — see [ArtCache] for what that fixed.
 */

/**
 * One of the three full-figure slots: her drawing at [id], or an empty box of exactly the
 * same measurement while the first decode lands.
 *
 * The placeholder is the point. All three callers size her through the modifier they pass
 * (`requiredSize`, `height` + `aspectRatio`), so handing that same modifier to a `Box`
 * reserves the identical space and the arriving bitmap cannot shift anything around it.
 */
@Composable
private fun ArtImage(@DrawableRes id: Int, modifier: Modifier) {
    val bitmap = rememberArt(id)
    if (bitmap == null) {
        Box(modifier)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

// ---- world map ---------------------------------------------------------------

/**
 * The world behind Home's connect circle: a **flat low-poly wireframe of the real Earth**,
 * computed rather than drawn. `design/tools/make_wireframe_map.py` renders it from Natural
 * Earth 1:110m coastlines through a Miller cylindrical projection over lat +84°..-56°, then
 * draws it the way a low-poly network diagram draws a map: the outline simplified
 * until it is straight runs with visible corners, a Delaunay mesh over each landmass, and a
 * dot on every other corner of it.
 *
 * No links, no nodes, no bend — the owner's brief, and each absence is a decision. A lattice
 * of routes over the map was built first and read as a second subject competing with the
 * ring; the same map bent over a horizon read as a globe, which is a picture rather than a
 * ground. What is left is geography as texture.
 *
 * The slot has three possible sources and they all write this one drawable, so the choice
 * never reaches the app — whichever generator ran last owns the look:
 * `make_wireframe_map.py` (this one), `make_network_map.py` (the route lattice, flat or
 * curved) and `make_worldmap_asset.py` (the same coastlines as a halftone).
 *
 * **One frame, never an animation.** Nothing on Home moves — the app's only continuous
 * motion is the busy sweep, which is information — and a looping background would also
 * decode and composite forever behind a screen the user leaves open.
 *
 * Alpha is the only tone control the asset has, and it is spent on reading order: the
 * coastline and the corner dots at full strength, the interior mesh at a third of it.
 * Decorative and non-interactive: no state, no content description.
 *
 * Draw it [fillMaxWidth][androidx.compose.foundation.layout.fillMaxWidth] and let the
 * painter set the height: the asset is emitted 407 dp wide **at every density, rasterised
 * from the geometry rather than resampled**, so nothing can beat against the pixel grid.
 * Its own aspect makes it 200.5 dp tall, the band this slot has held since the halftone, and
 * its weight on the device lands there too — 6.2 ink levels against the halftone's 7.5 over
 * the part of the band left of the portrait, with the same darkest value, `dot`.
 *
 * The file is a pure alpha mask — white RGB, alpha carrying the ink — tinted here from
 * `dot`, which is what keeps one asset correct in both themes and keeps a colour out of
 * the shipped pixels.
 *
 * This is the one slot with no placeholder box: it is absolutely positioned in Home's
 * stage, so its height reaches no sibling, and the asset's own aspect is the only thing
 * that could have stated a size before the bitmap is there.
 */
@Composable
fun YukariWorldMap(modifier: Modifier = Modifier) {
    val bitmap = rememberArt(R.drawable.yukari_worldmap) ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillWidth,
        colorFilter = ColorFilter.tint(MaterialTheme.yukari.dot),
    )
}

// ---- Yukari ------------------------------------------------------------------

/**
 * The full standing figure, arms crossed — the largest appearance, and Home's stage alone.
 *
 * The Servers header used to draw this same asset. It draws [YukariLean] now: that band is
 * 208 dp tall against this stage's 262, so a standing figure could only appear there at
 * half the head size Home gives her, which read as two different characters rather than
 * one at two distances.
 *
 * Decorative: no content description, no state, never interactive.
 */
@Composable
fun YukariHero(modifier: Modifier = Modifier) = ArtImage(R.drawable.yukari_hero, modifier)

/**
 * Chin on her hand — the Servers header band, leaning into the group strip's row and cut
 * by the tab underline.
 *
 * Her own drawing rather than a crop of [YukariHero]; see that function for why. The one
 * constraint the header puts on her size is horizontal: the strip's `+` and the actions
 * menu sit at 210 and 258 dp, and her ink has to stay clear of them or they become controls
 * users hunt for on her shirt. `ServersScreen`'s geometry carries the number.
 */
@Composable
fun YukariLean(modifier: Modifier = Modifier) = ArtImage(R.drawable.yukari_lean, modifier)

/**
 * Winking, a finger to her lips — the drawer header and the connected banner, where the
 * figure is clipped by the surface it sits on rather than framed by it.
 */
@Composable
fun YukariBust(modifier: Modifier = Modifier) = ArtImage(R.drawable.yukari_bust, modifier)

/**
 * The circular avatar: a square of the waving figure, face slightly above centre so the
 * hair mass fills the circle instead of the chin landing in the middle of it.
 *
 * The waving hand survives the crop, which is the reason this slot does not simply reuse
 * [YukariBust]: at 80 dp a face is all a circle holds, and two slots showing the same face
 * would have made the profile card look like the drawer header.
 *
 * The plate under her is `surface` rather than a fixed white. She carries her own light
 * area, so this only fills the corners the crop leaves transparent — the asset itself is
 * never tinted. It is also what stands in for her while the first decode lands: the circle
 * is drawn by this box, not by the bitmap.
 */
@Composable
fun YukariAvatar(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val bitmap = rememberArt(R.drawable.yukari_avatar)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
