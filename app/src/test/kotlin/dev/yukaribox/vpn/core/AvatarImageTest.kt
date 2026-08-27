package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The avatar is stored as one small square rather than as the picked original, so these
 * two functions decide what a 40-megapixel camera photo turns into before it reaches
 * `filesDir`. Both are arithmetic, and both have an off-by-one that only a test finds.
 */
class AvatarImageTest {

    @Test
    fun cropTakesTheMiddleOfALandscapePhoto() {
        assertEquals(AvatarImage.Crop(x = 50, y = 0, side = 100), AvatarImage.crop(200, 100))
    }

    @Test
    fun cropTakesTheMiddleOfAPortraitPhoto() {
        assertEquals(AvatarImage.Crop(x = 0, y = 50, side = 100), AvatarImage.crop(100, 200))
    }

    @Test
    fun cropOfASquareIsTheWholeImage() {
        assertEquals(AvatarImage.Crop(x = 0, y = 0, side = 80), AvatarImage.crop(80, 80))
    }

    @Test
    fun sampleSizeIsTheLargestPowerOfTwoThatKeepsTheShortSideBigEnough() {
        // 3000 is the short side: /4 leaves 750 >= 512, /8 would leave 375.
        assertEquals(4, AvatarImage.sampleSize(4000, 3000, target = 512))
    }

    @Test
    fun sampleSizeIsOneWhenTheImageIsAlreadySmallerThanTheTarget() {
        assertEquals(1, AvatarImage.sampleSize(300, 300, target = 512))
        assertEquals(1, AvatarImage.sampleSize(512, 512, target = 512))
    }
}
