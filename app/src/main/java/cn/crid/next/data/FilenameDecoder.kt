package cn.crid.next.data

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** The portal uses both RFC 8187 and older Java/Chinese-server filename encodings. */
internal object FilenameDecoder {
    private const val MAX_FILENAME_BYTES = 240
    private val gb18030 = Charset.forName("GB18030")
    private val windows1252 = Charset.forName("windows-1252")

    fun filename(disposition: String?, path: String, mimeType: String?, fallbackDisposition: String? = null,
        requestUrl: String? = null): String {
        val raw = headerFilename(disposition) ?: headerFilename(fallbackDisposition)
            ?: portalTitle(requestUrl)
            ?: decodeUrlName(path.substringAfterLast('/'))?.takeIf {
                usable(it) && (hasHan(it) || Regex("(?i)\\.(xls|xlsx|html?|csv|json)$").containsMatchIn(it))
            }
            ?: "timetable"
        return safeName(raw, mimeType)
    }

    /** The school's public jkingo.noprint.js encodes the generated filename twice in title. */
    private fun portalTitle(url: String?): String? {
        if (url == null || !CampusDownload.isExportRequest(url, "GET")) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.path != "/frame/excel") return null
        val titles = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && parts[0] == "title") parts[1] else null
        }
        val encoded = titles.singleOrNull()?.takeIf { it.length <= 16_384 } ?: return null
        val once = percentBytes(encoded)?.let { decodeStrict(it, Charsets.UTF_8) } ?: return null
        val name = percentBytes(once)?.let { decodeStrict(it, Charsets.UTF_8) } ?: return null
        return name.takeIf { usable(it) && it.length <= 1024 &&
            Regex("(?i)\\.(xls|xlsx)$").containsMatchIn(it) }
    }

    private fun safeName(raw: String, mimeType: String?): String {
        var name = sanitize(raw).ifBlank { "timetable" }
        val mimeExtension = when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "application/vnd.ms-excel", "application/x-excel" -> ".xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
            "text/html", "application/xhtml+xml" -> ".html"
            "application/json" -> ".json"
            "text/csv" -> ".csv"
            else -> ""
        }
        if (mimeExtension.isNotEmpty() && !name.endsWith(mimeExtension, true)) {
            name = name.substringBeforeLast('.', name).ifBlank { "timetable" } + mimeExtension
        }
        // Reserve the suffix before shortening; Chinese and supplementary characters use several bytes.
        val extension = Regex("\\.[a-zA-Z0-9]{1,12}$").find(name)?.value.orEmpty()
        val stem = name.removeSuffix(extension)
        return takeUtf8(stem, MAX_FILENAME_BYTES - extension.toByteArray(Charsets.UTF_8).size) + extension
    }

    private fun headerFilename(header: String?): String? {
        if (header.isNullOrBlank() || header.length > 16_384) return null
        val parameters = parameters(header)
        // Duplicate declarations are ambiguous (RFC 6266); a valid ordinary name can still be used.
        val extended = parameters["filename*"]?.singleOrNull()?.let(::decodeExtended)?.takeIf(::usable)
        return extended ?: parameters["filename"]?.singleOrNull()
            ?.let { decodeLegacy(it, formSpaces = true) }?.takeIf(::usable)
    }

    /** Split parameters without splitting quoted semicolons, and retain legacy Windows path separators. */
    private fun parameters(header: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        var index = 0
        while (index < header.length) {
            while (index < header.length && (header[index] == ';' || header[index].isWhitespace())) index++
            val keyStart = index
            while (index < header.length && header[index] != '=' && header[index] != ';') index++
            if (index == header.length) break
            if (header[index] == ';') continue
            val key = header.substring(keyStart, index).trim().lowercase(Locale.ROOT)
            index++
            while (index < header.length && header[index].isWhitespace()) index++
            val valueStart = index
            var valid = true
            val value = if (index < header.length && header[index] == '"') {
                index++
                val decoded = StringBuilder()
                var closed = false
                while (index < header.length) {
                    val character = header[index++]
                    when {
                        character == '"' -> { closed = true; break }
                        character == '\\' && index < header.length && header[index] in "\\\"" -> decoded.append(header[index++])
                        else -> decoded.append(character)
                    }
                }
                if (!closed) {
                    valid = false
                    // Recover later parameters from an invalid, unterminated value.
                    index = header.indexOf(';', valueStart).takeIf { it >= 0 } ?: header.length
                } else {
                    while (index < header.length && header[index].isWhitespace()) index++
                    if (index < header.length && header[index] != ';') valid = false
                    while (index < header.length && header[index] != ';') index++
                }
                decoded.toString()
            } else {
                while (index < header.length && header[index] != ';') index++
                header.substring(valueStart, index).trim()
            }
            if (valid && key in setOf("filename", "filename*")) result.getOrPut(key, ::mutableListOf).add(value)
        }
        return result
    }

    private fun decodeExtended(value: String): String? {
        val parts = value.split('\'', limit = 3)
        if (parts.size != 3 || !parts[1].all { it.isLetterOrDigit() && it.code < 128 || it == '-' }) return null
        val charset = when (parts[0].lowercase(Locale.ROOT)) {
            "utf-8", "utf8" -> Charsets.UTF_8
            "iso-8859-1" -> Charsets.ISO_8859_1
            "gb18030" -> gb18030
            "gbk", "gb2312" -> Charset.forName(parts[0])
            else -> return null
        }
        val encoded = parts[2]
        if (encoded.any { it.code >= 128 || !(it.isLetterOrDigit() || it in "!#$&+-.^_`|~%") }) return null
        return percentBytes(encoded)?.let { decodeStrict(it, charset) }
    }

    private fun decodeLegacy(value: String, formSpaces: Boolean): String {
        var decoded = value
        // A literal '%' is legal in ordinary names. Decode only a completely valid escaped sequence.
        if ('%' in value) {
            val bytes = percentBytes(value)
            if (bytes != null) {
                val unicode = decodeStrict(bytes, Charsets.UTF_8)
                    ?: decodeStrict(bytes, gb18030)?.takeIf(::hasHan)
                if (unicode != null) {
                    // Java URLEncoder uses '+' for a space. Require encoded non-ASCII bytes as evidence;
                    // bare A+B, URL path '+', RFC 8187 '+', and escaped %2B must remain literal pluses.
                    val encodedNonAscii = Regex("%[89a-fA-F][0-9a-fA-F]").containsMatchIn(value)
                    decoded = if (formSpaces && encodedNonAscii && '+' in value) {
                        val spacedBytes = percentBytes(value.replace("+", " "))!!
                        decodeStrict(spacedBytes, Charsets.UTF_8) ?: decodeStrict(spacedBytes, gb18030) ?: unicode
                    } else unicode
                }
            }
        }
        return repairUtf8Header(decoded)
    }

    private fun decodeUrlName(value: String): String? {
        val bytes = percentBytes(value) ?: return null
        return (decodeStrict(bytes, Charsets.UTF_8) ?: decodeStrict(bytes, gb18030)?.takeIf(::hasHan))
            ?.let(::repairUtf8Header)
    }

    /** Undo a lossless header-byte interpretation only when it recovers Chinese or supplementary text. */
    private fun repairUtf8Header(value: String): String {
        if (hasHan(value) || value.any { it == '\uFFFD' }) return value
        for (charset in listOf(Charsets.ISO_8859_1, windows1252)) {
            val bytes = encodeStrict(value, charset) ?: continue
            val candidate = decodeStrict(bytes, Charsets.UTF_8) ?: continue
            if (candidate != value && usable(candidate) && (hasHan(candidate) || candidate.codePoints().anyMatch { it > 0xFFFF }) &&
                encodeStrict(candidate, Charsets.UTF_8)?.contentEquals(bytes) == true) return candidate
        }
        return value
    }

    private fun percentBytes(value: String): ByteArray? {
        val output = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length) return null
                val high = value[index + 1].digitToIntOrNull(16) ?: return null
                val low = value[index + 2].digitToIntOrNull(16) ?: return null
                output.write(high * 16 + low)
                index += 3
            } else {
                val next = value.indexOf('%', index).takeIf { it >= 0 } ?: value.length
                val literal = encodeStrict(value.substring(index, next), Charsets.UTF_8) ?: return null
                output.write(literal)
                index = next
            }
        }
        return output.toByteArray()
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun encodeStrict(value: String, charset: Charset): ByteArray? = runCatching {
        val buffer = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    }.getOrNull()

    private fun hasHan(value: String) = value.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
    private fun usable(value: String) = value.isNotBlank() && '\uFFFD' !in value && encodeStrict(value, Charsets.UTF_8) != null

    private fun sanitize(value: String): String {
        val name = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cc}\\p{Cf}<>:\"|?*]"), "_").trim { it.isWhitespace() || it == '.' }
        // These names also need to work when an exported file is copied to a desktop.
        return if (Regex("(?i)(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?").matches(name)) "_$name" else name
    }

    private fun takeUtf8(value: String, budget: Int): String {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val count = when { point <= 0x7F -> 1; point <= 0x7FF -> 2; point <= 0xFFFF -> 3; else -> 4 }
            if (bytes + count > budget) break
            bytes += count
            index += Character.charCount(point)
        }
        return value.substring(0, index).trimEnd()
    }
}
