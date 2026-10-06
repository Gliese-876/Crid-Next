package cn.crid.next.export

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Lossless RGBA PNG; only one scanline and a bounded compressed chunk stay in memory. */
internal object StreamingPng {
    fun write(output: OutputStream, width: Int, height: Int, rows: (append: (IntArray) -> Unit) -> Unit) {
        require(width > 0 && height > 0)
        val sink = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
        sink.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray, count: Int = bytes.size) {
            val kind = type.toByteArray(Charsets.US_ASCII)
            sink.writeInt(count)
            sink.write(kind)
            sink.write(bytes, 0, count)
            val crc = CRC32().apply { update(kind); update(bytes, 0, count) }
            sink.writeInt(crc.value.toInt())
        }
        val header = java.io.ByteArrayOutputStream(13).also { buffer ->
            DataOutputStream(buffer).apply {
                writeInt(width); writeInt(height)
                writeByte(8); writeByte(6) // 8-bit RGBA.
                writeByte(0); writeByte(0); writeByte(0)
            }
        }.toByteArray()
        chunk("IHDR", header)
        val idat = object : OutputStream() {
            private val buffer = ByteArray(64 * 1024)
            private var count = 0
            override fun write(value: Int) {
                buffer[count++] = value.toByte()
                if (count == buffer.size) flush()
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var start = offset
                var remaining = length
                while (remaining > 0) {
                    val copied = minOf(buffer.size - count, remaining)
                    bytes.copyInto(buffer, count, start, start + copied)
                    count += copied; start += copied; remaining -= copied
                    if (count == buffer.size) flush()
                }
            }
            override fun flush() {
                if (count > 0) { chunk("IDAT", buffer, count); count = 0 }
            }
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            val compressed = DeflaterOutputStream(idat, deflater, 32 * 1024)
            val scanline = ByteArray(Math.addExact(Math.multiplyExact(width, 4), 1))
            var written = 0
            rows { pixels ->
                check(written < height && pixels.size >= width)
                scanline[0] = 0 // PNG filter None; pixels are straight RGBA, never premultiplied.
                for (x in 0 until width) {
                    val argb = pixels[x]
                    val index = 1 + x * 4
                    scanline[index] = (argb ushr 16).toByte()
                    scanline[index + 1] = (argb ushr 8).toByte()
                    scanline[index + 2] = argb.toByte()
                    scanline[index + 3] = (argb ushr 24).toByte()
                }
                compressed.write(scanline)
                written++
            }
            check(written == height)
            compressed.finish()
            idat.flush()
            chunk("IEND", byteArrayOf())
            sink.flush()
        } finally {
            deflater.end()
        }
        // The caller owns the destination stream, including cancellation/error cleanup.
    }
}
