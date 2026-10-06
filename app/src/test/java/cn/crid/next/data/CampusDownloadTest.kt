package cn.crid.next.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CampusDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val page = "https://jwxt.bnuzh.edu.cn/student/schedule?semester=1"
    private val first = "https://jwxt.bnuzh.edu.cn/export"
    private fun request(url: String = first) = CampusDownloadRequest(url, page, "CampusTest/1.0")
    private fun cache() = File(temporary.root, "browser-downloads")

    @Test fun campusDomainsAcceptCasHopsAndRejectLookalikes() {
        listOf("https://bnu.edu.cn/", "https://zyfw.prsc.bnu.edu.cn/cas/login.action", "https://jwxt.bnuzh.edu.cn/caslogin",
            "https://cas.bnu.edu.cn/login", "https://jwxt.bnuzh.edu.cn:443/export").forEach { assertTrue(it, CampusDownload.isCampusUrl(it)) }
        listOf("http://jwxt.bnuzh.edu.cn/", "https://bnuzh.edu.cn.attacker.test/", "https://fakebnuzh.edu.cn/",
            "https://jwxt.bnuzh.edu.cn:8443/", "https://user@jwxt.bnuzh.edu.cn/", "file:///timetable.xls", "blob:https://jwxt.bnuzh.edu.cn/id",
            "data:text/plain,test", "https://localhost/", "https://127.0.0.1/", "javascript:alert(1)").forEach {
            assertFalse(it, CampusDownload.isCampusUrl(it))
        }
    }

    @Test fun downloadedBytesAndReadableFilenameArePreserved() {
        val response = FakeConnection(URL(first), body = "timetable bytes".toByteArray(), headers = mapOf(
            "Content-Disposition" to listOf("attachment; filename*=UTF-8''%E8%AF%BE%E8%A1%A8.xls"),
            "Content-Type" to listOf("application/vnd.ms-excel")))
        val fractions = mutableListOf<Pair<Long, Long>>()
        val file = CampusDownload(cache(), { response }).download(request(), onProgress = { bytes, total -> fractions += bytes to total })
        assertEquals("课表.xls", file.name)
        assertEquals("timetable bytes", file.readText())
        assertEquals(file.length(), fractions.last().first)
        assertTrue(file.canonicalPath.startsWith(cache().canonicalPath + File.separator))
        assertFalse(file.parentFile.listFiles()!!.any { it.name.endsWith(".part") })
        assertTrue(response.disconnected)
    }

    @Test fun eachRedirectUsesItsOwnCookiesAndSafeReferrer() {
        val second = "https://zyfw.prsc.bnu.edu.cn/timetable.xls"
        val responses = listOf(
            FakeConnection(URL(first), 302, headers = mapOf("Location" to listOf(second))),
            FakeConnection(URL(second), body = byteArrayOf(1, 2, 3)),
        )
        val requested = mutableListOf<String>()
        val cookies = mapOf(first to "zhuhai=one", second to "beijing=two")
        val downloader = CampusDownload(cache(), { url -> responses.single { it.url.toExternalForm() == url.toExternalForm() } }, cookiesFor = { url -> requested += url; cookies[url] })
        downloader.download(request())
        assertEquals(listOf(first, second), requested)
        assertEquals("zhuhai=one", responses[0].getRequestProperty("Cookie"))
        assertEquals("beijing=two", responses[1].getRequestProperty("Cookie"))
        assertEquals(page, responses[0].getRequestProperty("Referer"))
        assertEquals("https://jwxt.bnuzh.edu.cn/", responses[1].getRequestProperty("Referer"))
        assertEquals("CampusTest/1.0", responses[1].getRequestProperty("User-Agent"))
        assertNull(responses[1].getRequestProperty("Authorization"))
        assertFalse(responses[0].instanceFollowRedirects)
    }

    @Test fun redirectsApplyResponseCookiesBeforeFollowingSameOrigin() {
        val second = "https://jwxt.bnuzh.edu.cn/download/timetable.xls"
        val responses = listOf(
            FakeConnection(URL(first), 303, headers = mapOf("Location" to listOf("/download/timetable.xls"), "Set-Cookie" to listOf("session=renewed"))),
            FakeConnection(URL(second), body = byteArrayOf(7)),
        )
        var currentCookie = "session=old"
        val received = mutableListOf<Pair<String, String>>()
        CampusDownload(cache(), { url -> responses.single { it.url.toExternalForm() == url.toExternalForm() } }, { currentCookie }, { url, cookie ->
            received += url to cookie; currentCookie = cookie
        }).download(request())
        assertEquals(listOf(first to "session=renewed"), received)
        assertEquals("session=renewed", responses[1].getRequestProperty("Cookie"))
    }

    @Test fun unrelatedHostRedirectIsRejectedBeforeAnyRequest() {
        val visited = mutableListOf<URL>()
        val failure = assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { url -> visited += url; FakeConnection(url, 302, headers = mapOf("Location" to listOf("https://example.org/steal"))) })
                .download(request())
        }
        assertEquals(CampusDownloadFailure.UNSUPPORTED_LINK, failure.reason)
        assertEquals(1, visited.size)
        assertFalse(cache().exists())
    }

    @Test fun legacyHttpRedirectIsUpgradedWithoutAnyCleartextRequest() {
        val visited = mutableListOf<String>()
        val file = CampusDownload(cache(), { url ->
            visited += url.toExternalForm()
            if (visited.size == 1) FakeConnection(url, 302, headers = mapOf("Location" to listOf("http://jwxt.bnuzh.edu.cn/plain")))
            else FakeConnection(url, body = byteArrayOf(1))
        }).download(request())
        assertEquals(listOf(first, "https://jwxt.bnuzh.edu.cn/plain"), visited)
        assertEquals(1L, file.length())
        val direct = assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { throw AssertionError("Direct HTTP must never connect") }).download(request("http://jwxt.bnuzh.edu.cn/plain"))
        }
        assertEquals(CampusDownloadFailure.UNSUPPORTED_LINK, direct.reason)
    }

    @Test fun redirectsCannotCycleOrRunForever() {
        var requests = 0
        assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { url -> requests++; FakeConnection(url, 302, headers = mapOf("Location" to listOf(first))) }).download(request())
        }
        assertEquals(1, requests)
        requests = 0
        assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { url -> requests++; FakeConnection(url, 302, headers = mapOf("Location" to listOf("/hop$requests"))) }).download(request())
        }
        assertEquals(9, requests)
    }

    @Test fun unauthorizedAndPartialResponsesNeverBecomeFiles() {
        for (status in listOf(401, 403, 404, 500, 206)) {
            assertThrows(CampusDownloadException::class.java) {
                CampusDownload(cache(), { FakeConnection(it, status, body = "private error".toByteArray()) }).download(request())
            }
            assertFalse(cache().exists())
        }
    }

    @Test fun oversizedReportedLengthIsRejectedWithoutWriting() {
        val failure = assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { FakeConnection(it, headers = mapOf("Content-Length" to listOf("${CampusDownload.MAX_BYTES + 1}"))) }).download(request())
        }
        assertEquals(CampusDownloadFailure.TOO_LARGE, failure.reason)
        assertFalse(cache().exists())
    }

    @Test fun oversizedListenerLengthAvoidsNetworkEntirely() {
        assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { throw AssertionError("Should not connect") })
                .download(request().copy(contentLength = CampusDownload.MAX_BYTES + 1))
        }
    }

    @Test fun unknownLengthStillEnforcesLimitAndRemovesPartial() {
        val body = ByteArray(CampusDownload.MAX_BYTES.toInt() + 1)
        val failure = assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { FakeConnection(it, body = body) }).download(request())
        }
        assertEquals(CampusDownloadFailure.TOO_LARGE, failure.reason)
        assertEquals(0, cache().listFiles()!!.size)
    }

    @Test fun exactSizeLimitSucceeds() {
        val body = ByteArray(CampusDownload.MAX_BYTES.toInt()) { 65 }
        val file = CampusDownload(cache(), { FakeConnection(it, body = body) }).download(request())
        assertEquals(CampusDownload.MAX_BYTES, file.length())
    }

    @Test fun truncatedStreamNeverBecomesImportable() {
        assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { FakeConnection(it, body = byteArrayOf(1), headers = mapOf("Content-Length" to listOf("100"))) }).download(request())
        }
        assertEquals(0, cache().listFiles()!!.size)
    }

    @Test fun emptyDownloadIsReportedAndRemoved() {
        val failure = assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { FakeConnection(it) }).download(request())
        }
        assertEquals(CampusDownloadFailure.EMPTY, failure.reason)
        assertEquals(0, cache().listFiles()!!.size)
    }

    @Test fun cancellationDisconnectsAndRemovesPartialBytes() {
        val signal = CampusDownloadCancellation()
        val response = FakeConnection(URL(first), body = ByteArray(50_000))
        assertThrows(CancellationException::class.java) {
            CampusDownload(cache(), { response }).download(request(), signal) { bytes, _ -> if (bytes > 0) signal.cancel() }
        }
        assertTrue(response.disconnected)
        assertEquals(0, cache().listFiles()!!.size)
    }

    @Test fun cancelledBeforeStartAvoidsNetworkAndDisk() {
        val signal = CampusDownloadCancellation().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            CampusDownload(cache(), { throw AssertionError("Should not connect") }).download(request(), signal)
        }
        assertFalse(cache().exists())
    }

    @Test fun connectionFailureDoesNotExposeUrlOrErrorBody() {
        val failure = assertThrows(CampusDownloadException::class.java) {
            CampusDownload(cache(), { throw IOException("https://jwxt.bnuzh.edu.cn/?token=private") }).download(request())
        }
        assertEquals("CONNECTION", failure.message)
        assertNull(failure.cause)
    }

    @Test fun filenamesCannotEscapeCacheAndRetainUnicode() {
        assertEquals("课表.xls", CampusDownload.safeFilename("attachment; filename=\"../../课表.xls\"", "/export", null))
        assertEquals("课表.xlsx", CampusDownload.safeFilename("attachment; filename=\"C:\\data\\课表.xlsx\"", "/export", null))
        assertEquals("A+B.xls", CampusDownload.safeFilename(null, "/A%2BB.xls", null))
        assertEquals("timetable.xls", CampusDownload.safeFilename("attachment; filename=\"..\"", "/export", "application/vnd.ms-excel"))
        assertEquals("a_b.xls", CampusDownload.safeFilename("attachment; filename=\"a\nb.xls\"", "/export", null))
        assertEquals("课表.xls", CampusDownload.safeFilename("attachment; filename=fallback.xls; filename*=UTF-8''%E8%AF%BE%E8%A1%A8.xls", "/export", null))
    }

    @Test fun injectedHeaderNewlinesAreOmitted() {
        val response = FakeConnection(URL(first), body = byteArrayOf(1))
        CampusDownload(cache(), { response }, { "session=a\r\nInjected:b" }).download(request().copy(userAgent = "agent\nInjected:b"))
        assertNull(response.getRequestProperty("Cookie"))
        assertNull(response.getRequestProperty("User-Agent"))
    }

    private class FakeConnection(
        target: URL,
        private val status: Int = 200,
        private val body: ByteArray = byteArrayOf(),
        private val headers: Map<String, List<String>> = emptyMap(),
    ) : HttpURLConnection(target) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderFields(): Map<String, List<String>> = headers
        override fun getHeaderField(name: String?): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        override fun getContentLengthLong() = getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        override fun getContentType(): String? = getHeaderField("Content-Type")
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }
}
