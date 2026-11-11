package net.blanchard.frameagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTest {

    @Test
    fun emptyAndClearPayloadsClear() {
        assertNull(MediaRequest.parse(""))
        assertNull(MediaRequest.parse("  \n"))
        assertNull(MediaRequest.parse("clear"))
    }

    @Test
    fun fitAndTimeoutHaveDefaults() {
        val request = MediaRequest.parse("""{"url":"http://ha/local/a.jpg","type":"image"}""")!!
        assertEquals(MediaRequest("http://ha/local/a.jpg", MediaType.IMAGE, MediaFit.COVER, 0L), request)
    }

    @Test(expected = Exception::class)
    fun garbageIsNotAClear() {
        MediaRequest.parse("not json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyHttpUrls() {
        MediaRequest.parse("""{"url":"file:///data/x.jpg","type":"image"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTypeRejected() {
        MediaRequest.parse("""{"url":"http://ha/a.gif","type":"gif"}""")
    }

    @Test
    fun smallImagesAreNotSampled() {
        assertEquals(1, MediaScaling.sampleSize(1600, 1200, 3_840_000L))
    }

    @Test
    fun largeImagesSampleDownToTheLimit() {
        assertEquals(2, MediaScaling.sampleSize(4000, 3000, 3_840_000L))
        assertEquals(4, MediaScaling.sampleSize(8000, 6000, 3_840_000L))
    }

    @Test
    fun matchingAspectNeedsNoScale() {
        assertEquals(1f to 1f, MediaScaling.fitScale(1600f, 1200f, 800f, 600f, MediaFit.COVER))
    }

    @Test
    fun coverOverflowsTheLongSide() {
        // 16:9 in a 4:3 view: full height, width overflows by 4/3.
        val (sx, sy) = MediaScaling.fitScale(1600f, 1200f, 1920f, 1080f, MediaFit.COVER)
        assertEquals(4f / 3f, sx, 0.001f)
        assertEquals(1f, sy, 0.001f)
    }

    @Test
    fun containLetterboxes() {
        // 16:9 in a 4:3 view: full width, bars top and bottom.
        val (sx, sy) = MediaScaling.fitScale(1600f, 1200f, 1920f, 1080f, MediaFit.CONTAIN)
        assertEquals(1f, sx, 0.001f)
        assertEquals(0.75f, sy, 0.001f)
    }
}
