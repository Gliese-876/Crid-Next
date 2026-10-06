package cn.crid.next.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import cn.crid.next.MainActivity
import cn.crid.next.core.importer.TimetableParser
import cn.crid.next.data.CampusDownload
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusCaptureInstrumentedTest {
    @Test fun anIframeExportCapturesTheFirstResponseWithoutDownloadReplay() = exercise(redirect = false)
    @Test fun redirectKeepsCorrectOriginAndDownloadsEachHopOnlyOnce() = exercise(redirect = true)

    @Test fun iframeExportDecodesAJavaPercentEncodedChineseFilename() = exercise(redirect = false,
        disposition = "attachment; filename=%E5%AD%A6%E7%94%9F+%E8%AF%BE%E8%A1%A8.xls", expectedName = "学生 课表.xls")

    @Test fun iframeExportHonorsAnExplicitGbkFilenameCharset() = exercise(redirect = false,
        disposition = "attachment; filename=fallback.xls; filename*=GBK'zh-CN'%BF%CE%B3%CC%B1%ED.xls", expectedName = "课程表.xls")

    @Test fun reportedLossyFilenameUsesNeutralNameWhenMetadataIsInvalid() = exercise(redirect = false,
        disposition = "attachment; filename=\"����ʦ����ѧ�麣У��2026-2027ѧ���＾ѧ��ѧ���α�.xls\"",
        fixtureName = "北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls",
        expectedName = "timetable.xls", portalTitle = "\uFFFD.xls")

    @Test fun iframeExportRecoversADoubleEncodedPortalTitleWhenTheHeaderIsLossy() = exercise(redirect = false,
        disposition = "attachment; filename=\"\uFFFD.xls\"",
        expectedName = "链接恢复课表.xls", portalTitle = "链接恢复课表.xls")

    @SuppressLint("SetJavaScriptEnabled")
    private fun exercise(redirect: Boolean, disposition: String = "attachment; filename=timetable.xls",
        fixtureName: String = "学生选课课程表.xls", expectedName: String = "timetable.xls", portalTitle: String = "timetable.xls") {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pageUrl = "https://jwxt.bnuzh.edu.cn/student/test-timetable?semester=1"
        val finalUrl = "https://zyfw.prsc.bnu.edu.cn/generated/course-file"
        val bytes = instrumentation.context.assets.open(fixtureName).use { it.readBytes() }
        val encodedTitle = URLEncoder.encode(URLEncoder.encode(portalTitle, "UTF-8").replace("+", "%20"), "UTF-8").replace("+", "%20")
        val requests = AtomicInteger()
        val connections = Collections.synchronizedList(mutableListOf<Response>())
        val originalDownloads = AtomicInteger()
        val iframeRequest = AtomicBoolean(false)
        val current = AtomicReference(pageUrl)
        val ready = CountDownLatch(1)
        val model = CampusBrowserViewModel()
        var webView: WebView? = null
        var capture: CampusResponseCapture? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val view = WebView(activity)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                val directory = File(activity.cacheDir, "browser-downloads")
                val owner = CampusResponseCapture(view, directory, view.settings.userAgentString, model, currentPage = current::get,
                    transport = {
                        CampusDownload(directory, openConnection = { url ->
                            val count = requests.incrementAndGet()
                            (if (redirect && count == 1) Response(url, 302, byteArrayOf(), mapOf("Location" to finalUrl))
                            else Response(url, 200, if (count == if (redirect) 2 else 1) bytes else byteArrayOf(),
                                mapOf("Content-Type" to "application/vnd.ms-excel", "Content-Disposition" to disposition))).also { connections += it }
                        })
                    })
                capture = owner
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { url?.let(current::set) }
                    override fun onPageFinished(view: WebView, url: String?) { if (url == pageUrl) ready.countDown() }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (request.url.path == "/frame/excel") iframeRequest.set(!request.isForMainFrame)
                        return owner.intercept(request) ?: WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(
                            (if (request.url.toString() == pageUrl) """
                                <!doctype html><html><body><iframe name="exportFrame"></iframe>
                                <a id="export" target="exportFrame" href="/frame/excel?method=download&amp;title=$encodedTitle">Export as list</a>
                                </body></html>
                            """ else "").toByteArray()))
                    }
                }
                view.setDownloadListener { _, _, _, _, _ -> originalDownloads.incrementAndGet() }
                activity.setContentView(view)
                view.loadUrl(pageUrl)
            }
            assertTrue("The local fixture page should load", ready.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { webView!!.evaluateJavascript("document.getElementById('export').click()", null) }
            val timeout = SystemClock.uptimeMillis() + 10_000
            var completed: File? = null
            while (SystemClock.uptimeMillis() < timeout && completed == null) {
                instrumentation.runOnMainSync { completed = model.completed }
                if (completed == null) SystemClock.sleep(20)
            }
            assertNotNull("Export should complete from its first response", completed)
            assertEquals(expectedName, completed!!.name)
            val sharedUri = FileProvider.getUriForFile(instrumentation.targetContext,
                "${instrumentation.targetContext.packageName}.files", completed!!)
            instrumentation.targetContext.contentResolver.query(sharedUri,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("The shared document exposes the recovered name", expectedName, cursor.getString(0))
            }
            assertArrayEquals(bytes, completed!!.readBytes())
            val preview = TimetableParser.parse(completed!!.readBytes(), completed!!.name)
            assertTrue(preview.courses.isNotEmpty())
            assertEquals(expectedName.substringBeforeLast('.'), preview.name)
            assertEquals(if (redirect) 2 else 1, requests.get())
            assertEquals(0, originalDownloads.get())
            assertTrue("Exports inside the campus iframe must be captured", iframeRequest.get())
            if (redirect) {
                assertEquals(finalUrl, connections.last().url.toExternalForm())
                assertEquals("https://jwxt.bnuzh.edu.cn/", connections.last().getRequestProperty("Referer"))
                // A captured attachment returns 204, so the existing portal document stays displayed.
                assertEquals(pageUrl, current.get())
            }
        } finally {
            instrumentation.runOnMainSync { capture?.close(); model.cancel(); webView?.destroy() }
            scenario.close()
        }
    }

    private class Response(url: URL, private val status: Int, private val body: ByteArray,
        private val headers: Map<String, String>) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getResponseMessage() = "OK"
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getHeaderFields(): Map<String, List<String>> = headers.mapValues { listOf(it.value) }
        override fun getContentType() = getHeaderField("Content-Type")
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }
}
