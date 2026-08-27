package dev.yukaribox.vpn.core

/**
 * The pure geometry behind the stored avatar: which square to take out of a picked
 * photo, and how coarsely to decode it.
 *
 * Separate from the store that uses it because `Bitmap` needs Android and this
 * arithmetic does not — the same reason `NodeGeo` and `PerAppRouting` live outside the
 * classes that reach the platform.
 */
object AvatarImage {

    /** Side of the square kept in `filesDir`, in pixels. */
    const val SIDE = 512

    /** A centred square inside a source image. */
    data class Crop(val x: Int, val y: Int, val side: Int)

    /** The centred square of a [width] x [height] image. */
    fun crop(width: Int, height: Int): Crop {
        val side = minOf(width, height)
        return Crop(x = (width - side) / 2, y = (height - side) / 2, side = side)
    }

    /**
     * `BitmapFactory.Options.inSampleSize` that keeps the short side at or above [target].
     *
     * Powers of two only, because that is the only family `BitmapFactory` honours exactly;
     * anything else it rounds down to one, which is how a decode meant to be cheap ends up
     * allocating the whole picked photo.
     */
    fun sampleSize(width: Int, height: Int, target: Int = SIDE): Int {
        val short = minOf(width, height)
        var sample = 1
        while (short / (sample * 2) >= target) sample *= 2
        return sample
    }
}
