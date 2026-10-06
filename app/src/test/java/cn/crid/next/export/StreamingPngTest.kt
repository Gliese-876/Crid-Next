package cn.crid.next.export

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

class StreamingPngTest {
    @Test fun oddWidthsAndFinalRowsDecodeWithStraightAlpha() {
        for ((width, height) in listOf(1 to 129, 301 to 257)) {
            val random = Random(42)
            val expected = List(height) { IntArray(width) { random.nextInt() } }
            val bytes = ByteArrayOutputStream().also { output ->
                StreamingPng.write(output, width, height) { append -> expected.forEach(append) }
            }.toByteArray()
            // Android's compile stubs omit java.desktop; the host JVM still supplies
            // this independent PNG decoder for local tests. No desktop API enters the app.
            val decoded = Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java)
                .invoke(null, ByteArrayInputStream(bytes))
            val type = decoded.javaClass
            assertEquals(width, type.getMethod("getWidth").invoke(decoded))
            assertEquals(height, type.getMethod("getHeight").invoke(decoded))
            val rgb = type.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            for (y in 0 until height) assertArrayEquals(expected[y], rgb.invoke(decoded, 0, y, width, 1, null, 0, width) as IntArray)
        }
    }

    @Test fun chunkCrcAndSingleDeflateStreamCoverMultipleIdatChunks() {
        val width = 301
        val height = 257
        val random = Random(800)
        val rows = List(height) { IntArray(width) { random.nextInt() } }
        val bytes = ByteArrayOutputStream().also { output -> StreamingPng.write(output, width, height) { append -> rows.forEach(append) } }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(0x89504E470D0A1A0AuL.toLong(), input.readLong())
        val kinds = mutableListOf<String>()
        val idat = ByteArrayOutputStream()
        while (input.available() > 0) {
            val length = input.readInt()
            val kindBytes = ByteArray(4).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            val kind = kindBytes.toString(Charsets.US_ASCII)
            kinds += kind
            val expected = CRC32().apply { update(kindBytes); update(data) }.value.toInt()
            assertEquals("Every PNG chunk has its own valid CRC", expected, input.readInt())
            when (kind) {
                "IHDR" -> DataInputStream(ByteArrayInputStream(data)).use {
                    assertEquals(width, it.readInt()); assertEquals(height, it.readInt())
                    assertEquals(8, it.readUnsignedByte()); assertEquals(6, it.readUnsignedByte())
                    assertEquals(0, it.readUnsignedByte()); assertEquals(0, it.readUnsignedByte()); assertEquals(0, it.readUnsignedByte())
                }
                "IDAT" -> idat.write(data)
                "IEND" -> assertEquals(0, length)
                else -> fail("Unexpected PNG chunk $kind")
            }
        }
        assertEquals("IHDR", kinds.first())
        assertEquals("IEND", kinds.last())
        assertTrue("The fixture must cross compressed chunk boundaries", kinds.count { it == "IDAT" } > 1)
        val raw = DataInputStream(InflaterInputStream(ByteArrayInputStream(idat.toByteArray())))
        rows.forEach { row ->
            assertEquals("Every scanline uses the declared None filter", 0, raw.readUnsignedByte())
            row.forEach { pixel ->
                val red = raw.readUnsignedByte(); val green = raw.readUnsignedByte(); val blue = raw.readUnsignedByte(); val alpha = raw.readUnsignedByte()
                assertEquals(pixel, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }
        assertEquals(-1, raw.read())
    }

    @Test fun cancellationAndIncompleteRowsCannotFinishAnImage() {
        assertThrows(CancellationException::class.java) {
            StreamingPng.write(ByteArrayOutputStream(), 1, 300) { append ->
                repeat(128) { append(intArrayOf(-1)) }
                throw CancellationException("Test cancellation")
            }
        }
        assertThrows(IllegalStateException::class.java) {
            StreamingPng.write(ByteArrayOutputStream(), 1, 2) { append -> append(intArrayOf(-1)) }
        }
    }
}
