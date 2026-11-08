package id.helux.dsh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Frame geometry tests.
 *
 * These encode the two hardware details that silently break QR scanning when
 * wrong: padded rows, and a sensor frame rotated against the screen. The cases
 * use hand-checkable patterns so a transposed or flipped rotation fails loudly.
 */
class FrameRotationTest {

    /**
     * Build a packed image from rows given as strings of digits.
     * @param rows row strings, all the same length.
     * @return packed luminance data.
     */
    private fun image(vararg rows: String): ByteArray {
        val width = rows.first().length
        require(rows.all { it.length == width })
        return ByteArray(width * rows.size) { index ->
            rows[index / width][index % width].digitToInt().toByte()
        }
    }

    /**
     * Render a frame back to row strings for readable assertions.
     * @param frame frame to render.
     * @return rows as strings.
     */
    private fun render(frame: FrameRotation.Frame): List<String> =
        (0 until frame.height).map { row ->
            (0 until frame.width).joinToString("") { col -> frame.at(row, col).toString() }
        }

    @Test
    fun zeroDegreesKeepsTheImage() {
        val src = image("123", "456")
        val frame = FrameRotation.rotate(src, 3, 2, 0)
        assertEquals(3, frame.width)
        assertEquals(2, frame.height)
        assertEquals(listOf("123", "456"), render(frame))
    }

    @Test
    fun ninetyDegreesTurnsClockwise() {
        // 3x2 becomes 2x3; the left column becomes the top row reversed.
        val src = image("123", "456")
        val frame = FrameRotation.rotate(src, 3, 2, 90)
        assertEquals("width and height swap on a quarter turn", 2, frame.width)
        assertEquals(3, frame.height)
        assertEquals(listOf("41", "52", "63"), render(frame))
    }

    @Test
    fun oneEightyDegreesIsUpsideDown() {
        val src = image("123", "456")
        val frame = FrameRotation.rotate(src, 3, 2, 180)
        assertEquals(3, frame.width)
        assertEquals(2, frame.height)
        assertEquals(listOf("654", "321"), render(frame))
    }

    @Test
    fun twoSeventyDegreesTurnsCounterClockwise() {
        val src = image("123", "456")
        val frame = FrameRotation.rotate(src, 3, 2, 270)
        assertEquals(2, frame.width)
        assertEquals(3, frame.height)
        assertEquals(listOf("36", "25", "14"), render(frame))
    }

    @Test
    fun fourQuarterTurnsReturnTheOriginal() {
        val src = image("1234", "5678", "9012")
        var frame = FrameRotation.rotate(src, 4, 3, 90)
        frame = FrameRotation.rotate(frame.data, frame.width, frame.height, 90)
        frame = FrameRotation.rotate(frame.data, frame.width, frame.height, 90)
        frame = FrameRotation.rotate(frame.data, frame.width, frame.height, 90)
        assertEquals(4, frame.width)
        assertEquals(3, frame.height)
        assertArrayEquals(src, frame.data)
    }

    @Test
    fun negativeAndOversizedAnglesAreNormalised() {
        val src = image("123", "456")
        assertArrayEquals(
            FrameRotation.rotate(src, 3, 2, 90).data,
            FrameRotation.rotate(src, 3, 2, -270).data,
        )
        assertArrayEquals(
            FrameRotation.rotate(src, 3, 2, 90).data,
            FrameRotation.rotate(src, 3, 2, 450).data,
        )
    }

    @Test
    fun packDropsRowPadding() {
        // Two visible pixels per row, but rows are 5 bytes apart with padding
        // 7 and 9 that must never appear in the result.
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 99, 99, 99, 3, 4, 99, 99, 99))
        val packed = FrameRotation.pack(buffer, width = 2, height = 2, rowStride = 5, pixelStride = 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), packed)
    }

    @Test
    fun packHandlesPixelStride() {
        // Interleaved plane: every other byte belongs to this channel.
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0))
        val packed = FrameRotation.pack(buffer, width = 2, height = 2, rowStride = 4, pixelStride = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), packed)
    }

    @Test
    fun packPadsShortBuffersWithBlack() {
        // A truncated final row must read as 0 rather than throwing.
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        val packed = FrameRotation.pack(buffer, width = 2, height = 2, rowStride = 2, pixelStride = 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 0), packed)
    }

    @Test
    fun packDoesNotConsumeTheSourceBuffer() {
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        val before = buffer.position()
        FrameRotation.pack(buffer, width = 2, height = 2, rowStride = 2, pixelStride = 1)
        assertEquals("the camera owns this buffer", before, buffer.position())
    }

    @Test
    fun rotateRejectsMalformedInput() {
        val empty = ByteArray(0)
        val failures = listOf(
            runCatching { FrameRotation.rotate(empty, 0, 2, 0) }.isFailure,
            runCatching { FrameRotation.rotate(empty, 3, 2, 0) }.isFailure,
            runCatching { FrameRotation.pack(ByteBuffer.allocate(0), 0, 1, 0, 1) }.isFailure,
        )
        assertTrue("every malformed frame must be refused: $failures", failures.all { it })
    }
}
