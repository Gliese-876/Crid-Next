package cn.crid.next.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedStreamsTest {
    @Test fun limitLeavesUnconsumedBytesAndZeroReadsNothing() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(), input.readAtMost(0))
        assertArrayEquals(byteArrayOf(1, 2), input.readAtMost(2))
        assertEquals(3, input.read())
    }

    @Test fun shortAndZeroBulkReadsMakeProgressWithoutExceedingTheBudget() {
        val source = ByteArray(20_000) { (it % 251).toByte() }
        val stream = object : InputStream() {
            var offset = 0
            var reads = 0
            override fun read(): Int = if (offset == source.size) -1 else source[offset++].toInt() and 255
            override fun read(target: ByteArray, start: Int, length: Int): Int {
                if (++reads % 3 == 0) return 0
                if (offset == source.size) return -1
                val count = minOf(13, length, source.size - offset)
                source.copyInto(target, start, offset, offset + count)
                offset += count
                return count
            }
        }
        assertArrayEquals(source.copyOf(8193), stream.readAtMost(8193))
        assertEquals(source[8193].toInt() and 255, stream.read())
    }

    @Test fun eofIsRespectedWithoutPaddingOrTakingOwnershipOfTheStream() {
        var closed = false
        val stream = object : ByteArrayInputStream(byteArrayOf(7, 8)) {
            override fun close() { closed = true; super.close() }
        }
        assertArrayEquals(byteArrayOf(7, 8), stream.readAtMost(100))
        assertFalse(closed)
        assertArrayEquals(byteArrayOf(), stream.readAtMost(2))
    }
}
