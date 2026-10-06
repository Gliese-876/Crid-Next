package cn.crid.next.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads no more than the caller's limit, including when a stream returns short or zero reads. */
internal fun InputStream.readAtMost(byteLimit: Int): ByteArray {
    require(byteLimit >= 0)
    if (byteLimit == 0) return ByteArray(0)
    val buffer = ByteArray(minOf(8192, byteLimit))
    val output = ByteArrayOutputStream(buffer.size)
    while (output.size() < byteLimit) {
        val count = read(buffer, 0, minOf(buffer.size, byteLimit - output.size()))
        when {
            count < 0 -> break
            count > 0 -> output.write(buffer, 0, count)
            else -> {
                val next = read()
                if (next < 0) break
                output.write(next)
            }
        }
    }
    return output.toByteArray()
}
