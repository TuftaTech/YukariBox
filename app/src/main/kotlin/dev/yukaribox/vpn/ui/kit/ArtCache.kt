package dev.yukaribox.vpn.ui.kit

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.AppContext
import dev.yukaribox.vpn.core.AppThreads
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide cache of the five artwork rasters, decoded once per density.
 *
 * `painterResource` memoises its bitmap in `remember(path, id, theme)`, which is scoped to
 * the *call site's position in the composition*. Every screen in this app is rendered
 * inside `ScreenHost`'s `AnimatedContent`, which disposes the screen it animates away
 * from, so that `remember` died on every tab change and the next visit re-ran
 * `BitmapFactory.decodeResource` **on the main thread**. Measured on the dev panel
 * (density 3, so the xxhdpi bucket): entering Home decoded `yukari_worldmap` at
 * 1220 x 601 (2.80 MB of ARGB_8888) plus `yukari_hero` at 378 x 786 (1.13 MB), and
 * leaving it threw both back at the collector. At xxxhdpi the pair is 7.0 MB. That is the
 * whole of the "freeze on tab switch" this cache removes, and it is the same amount of
 * work whether the user has three servers or ten thousand.
 *
 * Held for the life of the process rather than per screen, because these are the app's
 * identity: the map and one of the four drawings are on screen on nearly every surface, so
 * a cache scoped any tighter is a cache that misses exactly when it matters. It is pure
 * cache all the same — `YukariApp.onTrimMemory` drops it, and the worst that costs is a
 * decorative frame arriving one beat late.
 *
 * A [ConcurrentHashMap] rather than [FlagArt]'s `synchronized(LinkedHashMap)`, and the
 * difference is deliberate: [hit] is called from composition, i.e. on the main thread, and
 * a monitor shared with a decoder is a monitor the main thread can wait on. There is also
 * no LRU to keep — five entries at one density is the whole set, and the trim callback
 * empties it wholesale rather than evicting a coldest member. The price is that a failed
 * decode cannot be memoised as `null` (the map takes no null values), so a missing
 * drawable is retried; a drawable committed to this tree failing to decode is a broken
 * build, not a runtime state worth caching.
 */
internal object ArtCache {

    private const val INITIAL_CAPACITY = 8

    /** Width of a resource id in the packed key. */
    private const val ID_SHIFT = 32

    private const val ID_MASK = 0xFFFFFFFFL

    /** Keyed by drawable id **and** density, so a display change re-decodes rather than scaling. */
    private val cache = ConcurrentHashMap<Long, ImageBitmap>(INITIAL_CAPACITY)

    private fun key(id: Int, densityDpi: Int): Long =
        (id.toLong() shl ID_SHIFT) or (densityDpi.toLong() and ID_MASK)

    /** What is already decoded, or `null`. Touches no disk, so it is safe from composition. */
    fun hit(@DrawableRes id: Int, densityDpi: Int): ImageBitmap? = cache[key(id, densityDpi)]

    /**
     * [id] at [densityDpi], decoding it if this is the first ask. Opens a resource stream,
     * so call it off the main thread — [rememberArt] and [prewarm] both do.
     *
     * The decode happens outside any lock, so two callers racing on the same drawable each
     * decode it and the loser's copy is dropped. That is the cheaper mistake: the
     * alternative is a lock the main thread's [hit] could end up waiting behind, which is
     * the very stall this class exists to remove.
     */
    fun load(res: Resources, @DrawableRes id: Int, densityDpi: Int): ImageBitmap? {
        val cacheKey = key(id, densityDpi)
        cache[cacheKey]?.let { return it }
        val decoded = BitmapFactory.decodeResource(res, id)?.asImageBitmap() ?: return null
        return cache.putIfAbsent(cacheKey, decoded) ?: decoded
    }

    /**
     * Decode Home's two rasters on a background thread at process start.
     *
     * Home is the first screen, so these are needed within a few hundred milliseconds of
     * `onCreate` either way — doing it here means the first frame of the first screen is
     * not the one that pays for it. The other three are left to [rememberArt]: the drawer
     * sheet and the servers band reach them soon enough, and prewarming all five would put
     * 5.5 MB into the heap before anything has asked for it.
     *
     * One thread from [AppThreads], not a pool: it runs once and exits, so there is nothing
     * worth keeping resident, and the factory is what makes it daemon and background
     * priority — a decode competing with the first composition on equal terms would defeat
     * the point.
     */
    fun prewarm() {
        AppThreads.factory("art").newThread {
            val res = AppContext.context.resources
            val dpi = res.displayMetrics.densityDpi
            load(res, R.drawable.yukari_worldmap, dpi)
            load(res, R.drawable.yukari_hero, dpi)
        }.start()
    }

    /** Drop everything; see the class header for why that is safe. */
    fun clear() = cache.clear()
}

/**
 * [ArtCache] at composition: a hit is returned synchronously so the artwork is on its own
 * first frame, and only a miss suspends.
 *
 * The same shape as [rememberFlag], for the same reason and with the same trade: one brief
 * frame without a decorative drawing, against a dropped frame every time a screen is
 * entered. The two differ only in what they fall back to — a flag has a two-letter plate to
 * show, artwork has nothing, so the callers below emit an empty box of the identical size
 * rather than no box at all, and no layout can shift as the bitmap lands.
 *
 * Keyed on the resources' own `densityDpi` and not on `LocalDensity`, because that is the
 * number the decoder picks its bucket from. Nothing in the manifest declares
 * `configChanges`, so a density change recreates the Activity and this is read again.
 */
@Composable
internal fun rememberArt(@DrawableRes id: Int): ImageBitmap? {
    val res = LocalContext.current.resources
    val dpi = res.displayMetrics.densityDpi
    ArtCache.hit(id, dpi)?.let { return it }
    // A keyed holder rather than `produceState`, whose backing state is remembered
    // *unkeyed* and would hand the previous drawable to the new slot for a frame.
    val loaded = remember(id, dpi) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, dpi) {
        loaded.value = withContext(Dispatchers.IO) { ArtCache.load(res, id, dpi) }
    }
    return loaded.value
}
