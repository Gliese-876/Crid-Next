package cn.crid.next.data

import java.net.URLEncoder
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class FilenameDecoderTest {
    private fun name(header: String?, path: String = "/fallback.xls", mime: String? = null) =
        CampusDownload.safeFilename(header, path, mime)
    private fun escaped(value: String, charset: Charset = Charsets.UTF_8) =
        value.toByteArray(charset).joinToString("") { "%%%02X".format(it.toInt() and 0xFF) }

    @Test fun extendedUtf8UsesItsLanguageAndWinsInEitherOrder() {
        val expected = "学生选课课程表.xls"
        val encoded = escaped(expected)
        listOf("attachment; filename=fallback.xls; filename*=UTF-8'zh-CN'$encoded",
            "attachment; FILENAME*=utf-8''$encoded; filename=fallback.xls").forEach { assertEquals(expected, name(it)) }
    }

    @Test fun extendedCharsetIsRespectedIncludingLegacyChineseCharsets() {
        for (charset in listOf("GBK", "GB2312", "GB18030")) {
            val expected = "校园课表-春季学期.xls"
            assertEquals(expected, name("attachment; filename*=$charset'zh'${escaped(expected, Charset.forName(charset))}"))
        }
        assertEquals("café.xls", name("attachment; filename*=ISO-8859-1'fr'caf%E9.xls"))
        val supplementary = "课表𠀀.xls"
        assertEquals(supplementary, name("attachment; filename*=GB18030''${escaped(supplementary, Charset.forName("GB18030"))}"))
    }

    @Test fun invalidExtendedEncodingFallsBackWithoutReplacementCharacters() {
        listOf("UTF-8''%C0%AF.xls", "UTF-8''%ED%A0%80.xls", "UTF-8''%E8%AF.xls", "UTF-8''%GG.xls",
            "UTF-8''%", "UTF-8''", "UTF-8''%20", "UTF-8''%EF%BF%BD.xls", "Unknown''%E8%AF%BE.xls",
            "UTF-8'bad language'%E8%AF%BE.xls", "GBK''%81.xls", "bad-value", "UTF-8''literal space.xls").forEach {
            assertEquals(it, "正常课程.xls", name("attachment; filename*=$it; filename=正常课程.xls"))
        }
    }

    @Test fun percentEncodedOrdinaryNamesHandleUtf8GbkAndGb18030Bytes() {
        val expected = "学生选课课程表.xls"
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GBK"), Charset.forName("GB18030"))) {
            assertEquals(expected, name("attachment; filename=\"${escaped(expected, charset)}\""))
        }
        val extended = "课表𠀀.xls"
        assertEquals(extended, name("attachment; filename=${escaped(extended, Charset.forName("GB18030"))}"))
    }

    @Test fun javaFormFilenameSpacesAreDecodedButEscapedPlusesRemainLiteral() {
        val expected = "春季课表 A+B.xls"
        for (charset in listOf("UTF-8", "GBK")) {
            assertEquals(expected, name("attachment; filename=${URLEncoder.encode(expected, charset)}"))
        }
        assertEquals("A+B.xls", name("attachment; filename=A+B.xls"))
        assertEquals("A+B.xls", name("attachment; filename=A%2BB.xls"))
        assertEquals("Spring term.xls", name("attachment; filename=Spring%20term.xls"))
    }

    @Test fun urlAndExtendedNamesNeverTreatPlusAsFormSpaceOrDecodeTwice() {
        val encoded = "${escaped("课表")}+A%2BB.xls"
        assertEquals("课表+A+B.xls", name(null, "/$encoded"))
        assertEquals("课表+A+B.xls", name("attachment; filename*=UTF-8''$encoded"))
        assertEquals("课表%20A.xls", name("attachment; filename*=UTF-8''${escaped("课表")}%2520A.xls"))
        assertEquals("A%20B.xls", name("attachment; filename=A%2520B.xls"))
    }

    @Test fun rawUtf8HeaderBytesMisreadAsLatin1AreRecoveredLosslessly() {
        val expected = "学生选课课程表.xls"
        val wireBytes = expected.toByteArray(Charsets.UTF_8)
        val misread = String(wireBytes, Charsets.ISO_8859_1)
        assertNotEquals(expected, misread)
        assertEquals(expected, name("attachment; filename=\"$misread\""))
    }

    @Test fun rawUtf8HeaderBytesMisreadAsWindows1252AreRecoveredLosslessly() {
        // All bytes here have defined Windows-1252 mappings; no replacement has already lost data.
        val expected = "课表😀.xls"
        val wireBytes = expected.toByteArray(Charsets.UTF_8)
        val misread = String(wireBytes, Charset.forName("windows-1252"))
        assertFalse(misread.contains('\uFFFD'))
        assertEquals(expected, name("attachment; filename=\"$misread\""))
    }

    @Test fun healthyUnicodeAndLatinNamesAreNotGuessedAsChineseBytes() {
        listOf("学生选课课程表.xls", "Résumé été.xls", "Müller.xls", "Ångström.xls", "A+B.xls", "课表😀.xls",
            "中文 English 2026.xls", "100% complete.xls", "rate%Q1.xls", "café.xls", "ÖÐÎÄ.xls").forEach {
            assertEquals(it, name("attachment; filename=\"$it\""))
        }
    }

    @Test fun lossyOrMalformedNamesFallBackWithoutGuessingMissingBytes() {
        assertEquals("fallback.xls", name("attachment; filename=\"课\uFFFD表.xls\""))
        assertEquals("fallback.xls", name("attachment; filename*=UTF-8''%FF.xls"))
        assertEquals("fallback.xls", name("attachment; filename=\"\uD800.xls\""))
        assertEquals("timetable.csv", name(null, "/", "text/csv"))
    }

    @Test fun quotedParametersPreserveSemicolonsAndUnescapeQuotes() {
        assertEquals("课表;春季.xls", name("attachment; filename=\"课表;春季.xls\"; size=42"))
        assertEquals("课表_春季_.xls", name("attachment; filename=\"课表\\\"春季\\\".xls\""))
        assertEquals("课表.xls", name("attachment; filename=\"C:\\download\\课表.xls\""))
        assertEquals("课表.xls", name("attachment; filename=\"C:\\\\download\\\\课表.xls\""))
    }

    @Test fun badOrDuplicateParametersDoNotHideValidFallbacks() {
        assertEquals("正常.xls", name("attachment; filename*=\"unterminated; filename=正常.xls"))
        assertEquals("正常.xls", name("attachment; filename*=UTF-8''one.xls; filename*=UTF-8''two.xls; filename=正常.xls"))
        assertEquals("fallback.xls", name("attachment; filename=one.xls; filename=two.xls"))
        assertEquals("fallback.xls", name("attachment; filename=\"\""))
        assertEquals("正常.xls", name("attachment; filename*=\"UTF-8''bad.xls\"junk; filename=正常.xls"))
    }

    @Test fun malformedResponseDispositionCanUseListenerProvidedName() {
        val listener = "attachment; filename=${escaped("课程表.xls")}"
        assertEquals("课程表.xls", CampusDownload.safeFilename("attachment", "/download", null, listener))
        assertEquals("课程表.xls", CampusDownload.safeFilename("attachment; filename*=UTF-8''%GG.xls", "/download", null, listener))
        assertEquals("response.xls", CampusDownload.safeFilename("attachment; filename=response.xls", "/download", null, listener))
    }

    @Test fun urlFallbackSupportsChineseByteEncodingsAndKeepsLiteralPluses() {
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GBK"))) {
            assertEquals("课程表.xls", name(null, "/download/${escaped("课程表.xls", charset)}"))
        }
        assertEquals("A+B.xls", name(null, "/A+B.xls"))
        assertEquals("课表.xls", name(null, "/课表.xls"))
    }

    @Test fun decodedPathSeparatorsAndControlsCannotEscapeDestination() {
        assertEquals("课表.xls", name("attachment; filename*=UTF-8''..%2F..%2F${escaped("课表.xls")}"))
        assertEquals("课表.xls", name("attachment; filename=%2E%2E%5C${escaped("课表.xls")}"))
        assertEquals("a_b_c_.xls", name("attachment; filename*=UTF-8''a%00b%0Ac%E2%80%AE.xls"))
        assertEquals("timetable.xls", name("attachment; filename=..", mime = "application/vnd.ms-excel"))
        assertEquals("_CON.xls", name("attachment; filename=CON.xls"))
        assertFalse(name("attachment; filename=\".download.part\"").startsWith('.'))
    }

    @Test fun byteLimitKeepsExtensionAndNeverCutsSurrogatePairs() {
        for (longName in listOf("课".repeat(160) + ".xlsx", "😀".repeat(160) + ".xls", "a".repeat(400) + ".CSV")) {
            val result = name("attachment; filename=\"$longName\"")
            assertTrue(result.toByteArray(Charsets.UTF_8).size <= 240)
            assertTrue(result.endsWith(longName.substring(longName.lastIndexOf('.'))))
            assertEquals(result, String(result.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            assertFalse(result.contains('\uFFFD'))
        }
    }

    @Test fun mimeTypeControlsSuffixBeforeByteTruncation() {
        assertEquals("课表.xls", name("attachment; filename=课表.exe", mime = " Application/Vnd.Ms-Excel; charset=UTF-8"))
        val result = name("attachment; filename=${"课".repeat(160)}.wrong", mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertTrue(result.endsWith(".xlsx"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 240)
    }
}
