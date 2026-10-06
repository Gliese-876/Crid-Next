package cn.crid.next.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CampusResponseCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val page = "https://jwxt.bnuzh.edu.cn/student/timetable"
    private val export = "https://jwxt.bnuzh.edu.cn/frame/excel?method=download&title=timetable.xls"
    private fun request() = CampusDownloadRequest(export, page, "WebView", requestHeaders = mapOf("Referer" to "$page?semester=1", "Accept" to "text/html,*/*"))
    private fun cache() = File(temporary.root, "downloads")

    @Test fun aOneTimeFileIsCapturedFromTheFirstResponse() {
        var requests = 0
        val bytes = "<html><table><tr><td>课程名称</td></tr></table></html>".toByteArray()
        val downloader = CampusDownload(cache(), { url ->
            requests++
            Response(url, body = if (requests == 1) bytes else byteArrayOf(),
                headers = mapOf("Content-Disposition" to "attachment; filename=timetable.xls", "Content-Type" to "application/vnd.ms-excel"))
        })
        val result = downloader.intercept(request(), CampusDownloadCancellation()) as CampusTransfer.Downloaded
        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals(1, requests)
    }

    @Test fun exportCandidatesAreReadOnlyEndpointsNotGeneralCampusRequests() {
        assertTrue(CampusDownload.isExportRequest(export, "GET"))
        assertTrue(CampusDownload.isExportRequest("https://jwxt.bnuzh.edu.cn/files/timetable.xlsx", "GET"))
        listOf(page, "https://jwxt.bnuzh.edu.cn/frame/excel?method=toexcel", "https://jwxt.bnuzh.edu.cn/cas/login.action",
            "https://jwxt.bnuzh.edu.cn/student/select?method=add", "https://jwxt.bnuzh.edu.cn/student/withdraw?excel=1",
            "https://jwxt.bnuzh.edu.cn/student/exportGrades", "https://unrelated.example/frame/excel?method=download").forEach {
            assertFalse(it, CampusDownload.isExportRequest(it, "GET"))
        }
        assertFalse(CampusDownload.isExportRequest(export, "POST"))
        assertFalse(CampusDownload.isExportRequest(export, "DELETE"))
        assertFalse(CampusDownload.isExportRequest("https://jwxt.bnuzh.edu.cn/frame/excel?method=select&method=download", "GET"))
        assertFalse(CampusDownload.isExportRequest("https://jwxt.bnuzh.edu.cn/frame/excel?method=download&METHOD=select", "GET"))
        assertFalse(CampusDownload.isExportRequest("https://jwxt.bnuzh.edu.cn/frame/excel?method=download&%6dethod=download", "GET"))
    }

    @Test fun ordinaryHtmlUsesTheOriginalResponseWithoutAnotherGet() {
        var requests = 0
        val response = Response(URL(export), body = "<p>Please sign in</p>".toByteArray(), headers = mapOf("Content-Type" to "text/html; charset=GBK"))
        val result = CampusDownload(cache(), { requests++; response }).intercept(request(), CampusDownloadCancellation()) as CampusTransfer.Page
        assertEquals("text/html", result.mime)
        assertEquals("GBK", result.encoding)
        assertFalse(response.disconnected)
        assertEquals("<p>Please sign in</p>", result.stream.bufferedReader().use { it.readText() })
        assertTrue(response.disconnected)
        assertEquals(1, requests)
        assertFalse(cache().exists())
        val json = CampusDownload(cache(), { Response(it, body = "{\"error\":\"login\"}".toByteArray(), headers = mapOf("Content-Type" to "application/json")) })
            .intercept(request(), CampusDownloadCancellation()) as CampusTransfer.Page
        assertEquals("application/json", json.mime)
        assertEquals("{\"error\":\"login\"}", json.stream.bufferedReader().use { it.readText() })
    }

    @Test fun downloadMimeWithoutDispositionNeverFallsBackToASecondRequest() {
        for (mime in listOf("application/octet-stream", "application/download", "application/force-download", "text/csv")) {
            var requests = 0
            val bytes = "timetable file".toByteArray()
            val result = CampusDownload(cache(), { url -> requests++; Response(url, body = bytes, headers = mapOf("Content-Type" to mime)) })
                .intercept(request(), CampusDownloadCancellation()) as CampusTransfer.Downloaded
            assertArrayEquals(bytes, result.file.readBytes())
            assertEquals(1, requests)
        }
    }

    @Test fun redirectIsGivenBackToWebViewBeforeFetchingTheNextOrigin() {
        var requests = 0
        val location = "https://cas.bnuzh.edu.cn/cas/login"
        val result = CampusDownload(cache(), { url -> requests++; Response(url, status = 302, headers = mapOf("Location" to location.replace("https://", "http://"))) })
            .intercept(request(), CampusDownloadCancellation()) as CampusTransfer.Redirect
        assertEquals(location, result.url)
        assertEquals(1, requests)
        assertFalse(cache().exists())
    }

    @Test fun unsafeRedirectNeverReachesWebView() {
        assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { url -> Response(url, status = 302, headers = mapOf("Location" to "https://attacker.test/timetable.xls")) })
                .intercept(request(), CampusDownloadCancellation())
        }
    }

    @Test fun iframeRefererAndAcceptAreRetainedWithoutUntrustedHeaders() {
        val response = Response(URL(export), body = byteArrayOf(1), headers = mapOf("Content-Type" to "application/vnd.ms-excel"))
        CampusDownload(cache(), { response }).intercept(request().copy(requestHeaders = request().requestHeaders + mapOf("Authorization" to "not-forwarded")), CampusDownloadCancellation())
        assertEquals("$page?semester=1", response.getRequestProperty("Referer"))
        assertEquals("text/html,*/*", response.getRequestProperty("Accept"))
        assertNull(response.getRequestProperty("Authorization"))
    }

    @Test fun compressedFileIsDecompressedAndComparedUsingDecodedLength() {
        val bytes = "<html>课表</html>".toByteArray()
        val zipped = ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(bytes) } }.toByteArray()
        val response = Response(URL(export), body = zipped, headers = mapOf("Content-Type" to "application/vnd.ms-excel",
            "Content-Encoding" to "gzip", "Content-Length" to zipped.size.toString()))
        val file = (CampusDownload(cache(), { response }).intercept(request(), CampusDownloadCancellation()) as CampusTransfer.Downloaded).file
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun anHttpErrorKeepsItsBodyAndStatusForThePage() {
        val response = Response(URL(export), status = 403, body = "<p>Access denied</p>".toByteArray(), headers = mapOf("Content-Type" to "text/html"))
        val result = CampusDownload(cache(), { response }).intercept(request(), CampusDownloadCancellation()) as CampusTransfer.Page
        assertEquals(403, result.status)
        assertEquals("<p>Access denied</p>", result.stream.bufferedReader().use { it.readText() })
        assertFalse(cache().exists())
    }

    private class Response(url: URL, private val status: Int = 200, private val body: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getResponseMessage() = if (status >= 400) "Error" else "OK"
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getHeaderFields(): Map<String, List<String>> = headers.mapValues { listOf(it.value) }
        override fun getContentType() = getHeaderField("Content-Type")
        override fun getContentLengthLong() = getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
    }
}
