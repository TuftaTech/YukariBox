package dev.yukaribox.vpn

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import dev.yukaribox.vpn.core.AppContext
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.ui.kit.ArtCache
import dev.yukaribox.vpn.ui.kit.FlagArt
import dev.yukaribox.vpn.core.LogReader
import dev.yukaribox.vpn.data.AvatarStore
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.RouteRepository
import dev.yukaribox.vpn.vpn.AutoConnectManager
import dev.yukaribox.vpn.vpn.LocalDns
import dev.yukaribox.vpn.vpn.NativeInterface
import dev.yukaribox.vpn.vpn.syncStatusSurfaces
import libcore.Libcore
import java.io.File

class YukariApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        // Before any state can change, so no transition is missed by the tile or the widget.
        TunnelController.surfaceSync = ::syncStatusSurfaces
        SettingsStore.load()
        NodeRepository.load()
        RouteRepository.load()
        // One File.exists() so the profile card knows on its first frame whether the user
        // has an avatar. The alternative -- a boolean in settings.json -- is a second copy
        // of a truth the filesystem already holds, and the two could disagree.
        AvatarStore.init()
        // Off means nothing on disk, not just nothing new. Switching the journal off wipes what
        // it had, but that leaves two ways for a file to outlive the switch: upgrading from a
        // build that had no switch, and a `settings.json` edited by hand. Queued on a
        // background thread, so an install that has never journalled pays nothing visible.
        if (!SettingsStore.data.logging) LogReader.discardRecorded()
        // Home's two rasters, decoded on a background thread before the first screen asks
        // for them. `painterResource` used to decode them on the main thread inside the
        // composition of whichever screen drew them, and `ScreenHost`'s `AnimatedContent`
        // disposes a screen it animates away from, so that bill came due again on every
        // return to Home -- 3.9 MB of ARGB at this panel's density. See `ArtCache`.
        ArtCache.prewarm()
        initCore()
        // Optional: bring the tunnel up when a network appears (opt-in, OFF by default).
        AutoConnectManager.register(this)
    }

    /**
     * Give back the two bitmap caches when the system asks for memory.
     *
     * They are the app's only large retained allocations -- a byte-bounded LRU of decoded
     * flag plates, and the five artwork rasters -- and both are pure cache: every entry can
     * be decoded again, at the cost of one plate showing its two-letter fallback and one
     * decorative drawing arriving a frame late. Nothing else here holds anything worth
     * trimming.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_UI_HIDDEN is the one level API 34 left undeprecated, and it is the
        // right trigger anyway: the interface is gone, so nothing is about to draw a plate.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            FlagArt.clear()
            ArtCache.clear()
        }
    }

    private fun initCore() {
        val nativeInterface = NativeInterface()
        // Keep the core's "external" working dir inside app-private storage. The
        // reference clients point this at getExternalFilesDir (shared storage),
        // which is readable over adb/bugreport and by other apps on API <= 28 —
        // anything the core caches there (assets, dumps) would leak. A private
        // subdir keeps every byte the core writes under MODE_PRIVATE.
        val external = File(filesDir, "core").apply { mkdirs() }.absolutePath
        runCatching {
            Libcore.initCore(
                packageName,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                "$external/",
                50,
                // The core's own logger follows the user's switch, not the build type. With
                // `BuildConfig.DEBUG` here a debug build stood up `neko_log` whatever the
                // setting said, which broke the documented "off means all three writers" rule
                // outright -- and left sing-box's goroutines writing to the same ring buffer
                // that `LogReader.clear()` empties, which is the race that aborted the process.
                SettingsStore.data.logging,
                nativeInterface, // NB4AInterface
                nativeInterface, // BoxPlatformInterface
                LocalDns,
            )
            Log.i("YukariApp", "libcore initialized: ${Libcore.versionBox()}")
        }.onFailure {
            // Surfaced, not just logged. Without the flag the app carried on and handed
            // `newSingBoxInstance` to a core that was never initialized; `Log.e` (rather
            // than `Logs.e`) because the in-app journal ships off and this is the one
            // message that has to reach logcat regardless.
            TunnelController.coreReady = false
            Log.e("YukariApp", "libcore init failed", it)
        }
    }
}
