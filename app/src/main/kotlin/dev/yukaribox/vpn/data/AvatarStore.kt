package dev.yukaribox.vpn.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.yukaribox.vpn.core.AppContext
import dev.yukaribox.vpn.core.AvatarImage
import dev.yukaribox.vpn.core.Logs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The user's own avatar, stored privately as `files/avatar.png`.
 *
 * The picked photo is **not** kept. On import it is decoded coarsely, cropped to its
 * centred square and scaled down to [AvatarImage.SIDE], then written as one small PNG.
 * That is the difference between this and the user-background store it descends from
 * (removed in 2026-08, recoverable from commit `4ef6390`): a background needed the
 * original's full resolution, an 80 dp circle does not. So a 40-megapixel camera photo
 * never lands in `filesDir`, the file is a known size whatever was picked, and decoding it
 * back for the two surfaces that draw it is cheap enough to cache once.
 *
 * PNG rather than JPEG or WebP: it keeps alpha, so a cut-out portrait behaves on the card
 * the way the mascot derivative does instead of being composited onto a guessed colour;
 * and it avoids `CompressFormat.WEBP`, which is deprecated, against `WEBP_LOSSY`, which
 * needs API 30 while this app ships minSdk 28.
 *
 * **There is no setting for "an avatar is set".** The file's existence is the truth, and
 * [present] mirrors it for Compose. The store this replaced kept a `backgroundImage`
 * boolean in `settings.json` beside the file, and the two could disagree — a restored
 * backup or a cleared cache left the flag claiming an image that was not there.
 *
 * [set], [clear] and [bitmap] all touch the filesystem: **never call them from the main
 * thread, and never from composition.** `ui/Persona.kt` wraps [bitmap] the way
 * `ui/kit/FlagArt.kt` wraps a flag decode — a cache hit during composition, a suspend on a
 * miss — for the reason recorded there: decoding in composition drops a frame.
 */
object AvatarStore {

    /** Whether an avatar is stored. Mirrors the file; see the class KDoc. */
    var present by mutableStateOf(false)
        private set

    /**
     * Bumped whenever the stored bytes change, so a remembered decode is invalidated.
     * [present] alone cannot serve: replacing one avatar with another leaves it `true`.
     */
    var revision by mutableIntStateOf(0)
        private set

    /** Read the file's existence once, from `YukariApp.onCreate`. */
    fun init() {
        present = file().exists()
    }

    /**
     * Copy, validate and shrink the picked image, then adopt it. Returns false on any
     * failure, having changed nothing.
     *
     * The picked stream is copied to a temp file first rather than decoded in place. Two
     * reasons: [MAX_INPUT_BYTES] can only be enforced while reading, and the decode needs
     * two passes (bounds, then pixels) over something seekable, which a content stream is
     * not required to be.
     */
    fun set(uri: Uri): Boolean {
        val picked = File(AppContext.context.filesDir, PICKED)
        return try {
            copyBounded(uri, picked)
            val square = square(picked) ?: throw IOException("picked file does not decode as an image")
            write(square)
            square.recycle()
            adopt()
            true
        } catch (e: IOException) {
            abandon(e)
        } catch (e: SecurityException) {
            abandon(e)
        } catch (e: IllegalArgumentException) {
            abandon(e)
        } catch (e: OutOfMemoryError) {
            abandon(e)
        } finally {
            picked.delete()
        }
    }

    /** Forget the stored avatar. The built-in mascot takes the slot back. */
    fun clear() {
        file().delete()
        cached = null
        present = false
        revision++
        Logs.i("Avatar", "cleared")
    }

    /**
     * The decoded avatar **if it is already in memory**, and null otherwise — never any
     * IO. This is the half `ui/Persona.kt` may call during composition.
     */
    fun cached(): Bitmap? = cached?.takeIf { cachedAt == revision && present }

    /**
     * The stored avatar, decoded and cached. Null when none is set.
     *
     * One file of a known size, so a single slot is the whole cache — no LRU, unlike the
     * 252 country flags. It is invalidated by [revision] rather than cleared eagerly, so a
     * decode in flight for the previous image cannot install itself over the new one.
     */
    fun bitmap(): Bitmap? {
        if (!present) return null
        cached?.takeIf { cachedAt == revision }?.let { return it }
        val decoded = try {
            BitmapFactory.decodeFile(file().absolutePath)
        } catch (e: OutOfMemoryError) {
            Logs.e("Avatar", "decode failed", e)
            null
        }
        if (decoded == null) {
            // The flag said yes and the bytes say no. Trust the bytes.
            Logs.w("Avatar", "stored avatar is unreadable, dropping it")
            clear()
            return null
        }
        cached = decoded
        cachedAt = revision
        return decoded
    }

    private const val FILE = "avatar.png"
    private const val TEMP = "avatar.png.tmp"
    private const val PICKED = "avatar.picked"

    /** Bounds a hostile or simply enormous content stream before anything decodes it. */
    private const val MAX_INPUT_BYTES = 8L * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    /** PNG ignores the quality argument; the API requires one anyway. */
    private const val PNG_QUALITY = 100

    private var cached: Bitmap? = null
    private var cachedAt = -1

    private fun file() = File(AppContext.context.filesDir, FILE)

    /** Stream the picked image into [target], failing if it runs past [MAX_INPUT_BYTES]. */
    private fun copyBounded(uri: Uri, target: File) {
        target.delete()
        val source = AppContext.context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open the picked image")
        source.use { input -> target.outputStream().use { sink -> pump(input, sink) } }
    }

    /** The copy loop itself, split out only to keep [copyBounded] flat enough for detekt. */
    private fun pump(input: InputStream, sink: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > MAX_INPUT_BYTES) {
                throw IOException("picked image is over ${MAX_INPUT_BYTES / 1024 / 1024} MB")
            }
            sink.write(buffer, 0, read)
        }
    }

    /**
     * Decode [source] coarsely, take its centred square and bring it down to
     * [AvatarImage.SIDE]. Null when the bytes are not an image at all — which is the check
     * that stops a renamed `.zip` from being adopted as an avatar.
     */
    private fun square(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = AvatarImage.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
        val crop = AvatarImage.crop(decoded.width, decoded.height)
        val cut = Bitmap.createBitmap(decoded, crop.x, crop.y, crop.side, crop.side)
        if (cut !== decoded) decoded.recycle()
        // Never upscale. A 64 px picked image stays 64 px rather than becoming a soft 512.
        if (cut.width <= AvatarImage.SIDE) return cut
        val scaled = Bitmap.createScaledBitmap(cut, AvatarImage.SIDE, AvatarImage.SIDE, true)
        if (scaled !== cut) cut.recycle()
        return scaled
    }

    /** Stage then rename, so an interrupted write cannot leave a torn avatar in place. */
    private fun write(image: Bitmap) {
        val temp = File(AppContext.context.filesDir, TEMP)
        temp.delete()
        temp.outputStream().use { sink ->
            if (!image.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, sink)) {
                throw IOException("cannot encode the avatar")
            }
        }
        if (!temp.renameTo(file())) {
            temp.copyTo(file(), overwrite = true)
            temp.delete()
        }
    }

    private fun adopt() {
        cached = null
        present = true
        revision++
        Logs.i("Avatar", "set (${file().length()} bytes)")
    }

    private fun abandon(cause: Throwable): Boolean {
        Logs.e("Avatar", "set failed", cause)
        File(AppContext.context.filesDir, TEMP).delete()
        return false
    }
}
