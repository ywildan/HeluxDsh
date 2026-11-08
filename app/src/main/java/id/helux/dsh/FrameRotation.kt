package id.helux.dsh

import java.nio.ByteBuffer

/**
 * Converts a CameraX luminance plane into the upright, tightly packed image
 * ZXing expects.
 *
 * Two mismatches make this necessary, and both silently break scanning when
 * ignored:
 *
 * - the Y plane is normally padded, so `rowStride > width` and reading it as a
 *   packed buffer shears the image;
 * - the sensor frame is rotated relative to the screen (usually 90 degrees in
 *   portrait), while ZXing decodes an upright image only.
 *
 * Pure data in, pure data out, so the behaviour is unit tested on the JVM.
 */
object FrameRotation {

    /** A packed luminance image; [data] has exactly `width * height` bytes. */
    class Frame(val data: ByteArray, val width: Int, val height: Int) {

        /**
         * Read one pixel.
         * @param row row index.
         * @param col column index.
         * @return luminance value.
         */
        fun at(row: Int, col: Int): Byte = data[row * width + col]
    }

    /**
     * Copy a padded plane into a packed buffer, ignoring row padding.
     * @param source plane buffer; read absolutely, its position is untouched.
     * @param width visible width in pixels.
     * @param height visible height in pixels.
     * @param rowStride bytes between the start of consecutive rows.
     * @param pixelStride bytes between consecutive pixels in a row.
     * @return packed luminance data of `width * height` bytes.
     */
    fun pack(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        val stride = if (pixelStride > 0) pixelStride else 1
        val rowSpan = if (rowStride > 0) rowStride else width * stride
        val limit = source.limit()
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            val rowStart = row * rowSpan
            val outRow = row * width
            for (col in 0 until width) {
                val index = rowStart + col * stride
                out[outRow + col] = if (index in 0 until limit) source.get(index) else 0
            }
        }
        return out
    }

    /**
     * Rotate a packed image clockwise by a multiple of 90 degrees.
     * @param source packed luminance data of `width * height` bytes.
     * @param width source width.
     * @param height source height.
     * @param degrees rotation reported by the camera; any value is accepted.
     * @return the rotated frame, with swapped dimensions for quarter turns.
     */
    fun rotate(source: ByteArray, width: Int, height: Int, degrees: Int): Frame {
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(source.size >= width * height) { "buffer is smaller than the frame" }
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            90 -> {
                // Quarter turn clockwise: new width = height, new height = width.
                val out = ByteArray(width * height)
                for (row in 0 until width) {
                    for (col in 0 until height) {
                        out[row * height + col] = source[(height - 1 - col) * width + row]
                    }
                }
                Frame(out, height, width)
            }
            180 -> {
                val out = ByteArray(width * height)
                for (row in 0 until height) {
                    for (col in 0 until width) {
                        out[row * width + col] = source[(height - 1 - row) * width + (width - 1 - col)]
                    }
                }
                Frame(out, width, height)
            }
            270 -> {
                // Quarter turn counter-clockwise.
                val out = ByteArray(width * height)
                for (row in 0 until width) {
                    for (col in 0 until height) {
                        out[row * height + col] = source[col * width + (width - 1 - row)]
                    }
                }
                Frame(out, height, width)
            }
            else -> Frame(source.copyOf(width * height), width, height)
        }
    }
}
