package cn.crid.next.data

import cn.crid.next.core.importer.TimetableParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CampusDownloadFilenameTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://jwxt.bnuzh.edu.cn/frame/excel?method=download"
    private val page = "https://jwxt.bnuzh.edu.cn/student/timetable"
    private val request = CampusDownloadRequest(url, page, "FilenameTest")
    private val actualGarbled = "����ʦ����ѧ�麣У��2026-2027ѧ���＾ѧ��ѧ���α�"
    private val actualTitle = "北京师范大学珠海校区2026-2027学年秋季学期学生课表"
    private val fixtures = sequenceOf(File(".."), File(".")).map { File(it, "tests/测试用例") }.first(File::isDirectory)

    @Test fun reportedLossyNameUsesTheOfficialExportTitleForBothDownloadPathsAndImportName() {
        val bytes = File(fixtures, "$actualTitle.xls").readBytes()
        for (capture in listOf(false, true)) {
            val file = transfer(bytes, "attachment; filename=\"$actualGarbled.xls\"", capture,
                target = "$url&title=${encodeTwice("$actualTitle.xls")}")
            assertEquals("$actualTitle.xls", file.name)
            assertArrayEquals(bytes, file.readBytes())
            val preview = TimetableParser.parse(file.readBytes(), file.name)
            assertEquals(actualTitle, preview.name)
            assertEquals(11, preview.courses.size)
            assertTrue(preview.errors.isEmpty())
        }
    }

    @Test fun actualGbkHeaderBytesDecodedAsUtf8RecoverFromTheOfficialQueryTitle() {
        val title = "测试校区2026-2027学年秋季学期学生课表"
        val gbk = Charset.forName("GBK")
        val header = "attachment; filename=\"$title.xls\"".toByteArray(gbk).toString(Charsets.UTF_8)
        assertTrue(header.contains('\uFFFD'))
        val body = """<!doctype html><html><head><meta charset="GBK"></head><body><table>
            <tr><td colspan="4">$title</td></tr>
            <tr><td>课程名称</td><td>任课教师</td><td>学分</td><td>上课时间地点</td></tr>
            <tr><td>测试课程</td><td>测试教师</td><td>2</td><td>1-4周 星期一[1-2] 教室101</td></tr>
            </table></body></html>""".toByteArray(gbk)
        val file = transfer(body, header, capture = true, target = "$url&title=${encodeTwice("$title.xls")}")
        assertEquals("$title.xls", file.name)
        assertArrayEquals(body, file.readBytes())
        val preview = TimetableParser.parse(body, file.name)
        assertEquals(title, preview.name)
        assertEquals(1, preview.courses.size)
        assertTrue(preview.errors.isEmpty())
    }

    @Test fun lossyHeaderWithoutOfficialTitleUsesNeutralNameInsteadOfGuessingAYear() {
        val bytes = File(fixtures, "学生选课课程表.xls").readBytes()
        val file = transfer(bytes, "attachment; filename=\"$actualGarbled.xls\"", capture = true)
        assertEquals("timetable.xls", file.name)
        assertTrue(TimetableParser.parse(bytes, file.name).courses.isNotEmpty())
    }

    @Test fun missingFilenameMetadataNeverGuessesFromMalformedOrValidBodyContent() {
        val bytes = "not a spreadsheet".toByteArray()
        val file = transfer(bytes, "attachment; filename=\"$actualGarbled.xls\"", capture = false)
        assertEquals("timetable.xls", file.name)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("timetable.xls", transfer(bytes, "attachment; filename=\"\uFFFD.xls\"", capture = true).name)
        val workbook = File(fixtures, "$actualTitle.xls").readBytes()
        assertEquals("timetable.xls", transfer(workbook, null, capture = true).name)
    }

    @Test fun listenerFilenameSurvivesAnUnusableResponseHeaderBeforeAnyBodyFallback() {
        val bytes = File(fixtures, "$actualTitle.xls").readBytes()
        val file = transfer(bytes, "attachment; filename=\"$actualGarbled.xls\"", capture = false,
            listenerHeader = "attachment; filename*=UTF-8''%E8%AF%BE%E8%A1%A8.xls")
        assertEquals("课表.xls", file.name)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun aUsableUrlFilenameWinsOverTheWorkbookTitle() {
        val bytes = File(fixtures, "$actualTitle.xls").readBytes()
        val file = transfer(bytes, "attachment; filename=\"$actualGarbled.xls\"", capture = true,
            target = "https://jwxt.bnuzh.edu.cn/files/%E8%AF%BE%E8%A1%A8.xls")
        assertEquals("课表.xls", file.name)
    }

    @Test fun utf8PercentAndLosslessHeaderEncodingsRemainReadableThroughBothTransportPaths() {
        val expected = "课程表.xls"
        val bytes = "original body".toByteArray()
        val headers = listOf(
            "attachment; filename=\"${expected.toByteArray().toString(Charsets.ISO_8859_1)}\"",
            "attachment; filename=%E8%AF%BE%E7%A8%8B%E8%A1%A8.xls",
            "attachment; filename*=GBK'zh-CN'%BF%CE%B3%CC%B1%ED.xls",
        )
        for (capture in listOf(false, true)) for (header in headers) {
            val file = transfer(bytes, header, capture)
            assertEquals(expected, file.name)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun repeatedFriendlyNamesNeverOverwritePriorDownloadsOrLeavePartialFiles() {
        val one = transfer(byteArrayOf(1, 2), "attachment; filename=课表.xls", capture = false)
        val two = transfer(byteArrayOf(3, 4), "attachment; filename=课表.xls", capture = true)
        assertEquals(one.name, two.name)
        assertNotEquals(one.canonicalPath, two.canonicalPath)
        assertArrayEquals(byteArrayOf(1, 2), one.readBytes())
        assertArrayEquals(byteArrayOf(3, 4), two.readBytes())
        assertTrue(one.canonicalPath.startsWith(temporary.root.canonicalPath + File.separator))
        assertFalse(temporary.root.walk().any { it.name == ".download.part" })
    }

    @Test fun publicPortalDoubleEncodedTitleRecoversLossyHeaderWithoutGuessingFromBody() {
        val bytes = "body without a timetable title".toByteArray()
        val name = "$actualTitle.xls"
        for (capture in listOf(false, true)) {
            val file = transfer(bytes, "attachment; filename=\"$actualGarbled.xls\"", capture,
                target = "$url&title=${encodeTwice(name)}&fileSavePath=opaque")
            assertEquals(name, file.name)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun portalTitleRetainsLiteralPlusPercentAndAmpersandAfterExactlyTwoDecodes() {
        val name = "课程 C++ & 100% %20.xls"
        val file = transfer(byteArrayOf(1), null, capture = true,
            target = "$url&title=${encodeTwice(name)}")
        assertEquals(name, file.name)
    }

    @Test fun validResponseHeaderHasPriorityOverPortalTitle() {
        val file = transfer(byteArrayOf(1), "attachment; filename=响应课表.xls", capture = false,
            target = "$url&title=${encodeTwice("链接课表.xls")}")
        assertEquals("响应课表.xls", file.name)
    }

    @Test fun officialPortalTitleIsPreservedEvenWhenWorkbookHasADifferentTitle() {
        val bytes = File(fixtures, "$actualTitle.xls").readBytes()
        for (generic in listOf("timetable", "Schedule", "courses", "export", "download")) {
            val file = transfer(bytes, "attachment; filename=\"$actualGarbled.xls\"", capture = true,
                target = "$url&title=${encodeTwice("$generic.xls")}")
            assertEquals("$generic.xls", file.name)
        }
    }

    @Test fun malformedAmbiguousAndUnrelatedQueryTitlesAreNeverUsed() {
        val title = encodeTwice("不应使用.xls")
        val targets = listOf(
            "$url&title=$title&title=$title",
            "$url&title=%25FF.xls",
            "$url&title=${encodeTwice("\uFFFD.xls")}",
            "https://jwxt.bnuzh.edu.cn/other?method=download&title=$title",
            "https://jwxt.bnuzh.edu.cn/frame/excel?method=other&title=$title",
        )
        for (target in targets) {
            val file = transfer(byteArrayOf(1), null, capture = false, target = target)
            assertEquals("timetable.xls", file.name)
        }
    }

    private fun encodeTwice(value: String): String {
        fun encode(input: String) = URLEncoder.encode(input, "UTF-8").replace("+", "%20")
        return encode(encode(value))
    }

    private fun transfer(bytes: ByteArray, disposition: String?, capture: Boolean,
        listenerHeader: String? = null, target: String = url): File {
        var connections = 0
        val downloader = CampusDownload(temporary.root, { responseUrl ->
            connections++
            object : HttpURLConnection(responseUrl) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentType() = "application/vnd.ms-excel"
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getHeaderFields(): Map<String, List<String>> = disposition?.let { mapOf("Content-Disposition" to listOf(it)) }.orEmpty()
                override fun getHeaderField(name: String?) = if (name.equals("Content-Disposition", true)) disposition else null
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        })
        val input = request.copy(url = target, contentDisposition = listenerHeader)
        val result = if (capture) (downloader.intercept(input, CampusDownloadCancellation()) as CampusTransfer.Downloaded).file
            else downloader.download(input)
        assertEquals(1, connections)
        return result
    }
}
