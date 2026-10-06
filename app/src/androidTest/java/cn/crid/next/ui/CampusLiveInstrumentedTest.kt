package cn.crid.next.ui

import android.net.Uri
import android.net.http.SslError
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.InputDevice
import android.view.inspector.WindowInspector
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.MainActivity
import cn.crid.next.core.Language
import cn.crid.next.core.importer.TimetableParser
import cn.crid.next.data.CampusPortals
import cn.crid.next.data.CampusDownload
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in only: the host supplies credentials through private adb stdin, never arguments. */
class CampusLiveInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val observation = ExportObservation()

    private class ExportObservation {
        val officialRequest = AtomicBoolean(false)
        val generationStarted = AtomicBoolean(false)
        val generationHttp = AtomicInteger(-9999)
        val generationStatus = AtomicInteger(-9999)
        val busyObserved = AtomicBoolean(false)
        val failure = AtomicReference<String?>()
        val exportPhase = AtomicBoolean(false)
        val jsAlert = AtomicBoolean(false)
        val jsConfirm = AtomicBoolean(false)
        val windowOpen = AtomicBoolean(false)
        val mixedContent = AtomicBoolean(false)
        val jsError = AtomicBoolean(false)
        val alertKind = AtomicReference("none")
        fun write(report: JSONObject, title: String?) {
            report.put("officialDownloadRequestObserved", officialRequest.get()).put("officialQueryTitlePresent", title != null)
                .put("generationStarted", generationStarted.get()).put("generationHttpStatus", generationHttp.get())
                .put("generationServiceStatus", generationStatus.get()).put("downloadBusyObserved", busyObserved.get())
                .put("downloadFailureObserved", failure.get() ?: "none").put("nativeJsAlertObserved", jsAlert.get())
                .put("nativeJsConfirmObserved", jsConfirm.get()).put("windowOpenObserved", windowOpen.get())
                .put("mixedContentObserved", mixedContent.get()).put("javascriptErrorObserved", jsError.get())
                .put("exportAlertCategory", alertKind.get())
        }
    }

    @Test fun nativeExportTapWorksInScaledIframeWithoutCredentials() {
        assumeTrue("Explicit campus-startup opt-in is required",
            InstrumentationRegistry.getArguments().getString("cridCampusSmoke") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = JSONObject().put("nativeFixture", true).put("credentialsRead", false).put("success", false)
        val reportFile = File(context.filesDir, "live-campus-native-tap-result.json")
        var view: WebView? = null
        var succeeded = false
        var reason = "not_completed"
        try {
            val root = """<!doctype html><meta charset="UTF-8"><meta name="viewport" content="width=980">
                <body style="margin:0;width:980px"><input type="radio" checked>按列表方式显示
                <iframe src="/native-tap-frame" style="position:absolute;left:120px;top:180px;width:700px;height:260px;border:6px solid black"></iframe></body>"""
            val frame = """<!doctype html><meta charset="UTF-8"><body><table><tr><th>任课教师</th><th>学分</th></tr></table>
                <button style="margin:60px 80px" onclick="window.tapCount=(window.tapCount||0)+1;window.tapTrusted=event.isTrusted">导出</button></body>"""
            instrumentation.runOnMainSync {
                view = WebView(compose.activity).apply {
                    settings.javaScriptEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                            val html = when (request.url.path) { "/native-tap-root" -> root; "/native-tap-frame" -> frame; else -> "" }
                            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                        }
                    }
                    compose.activity.setContentView(this)
                    loadUrl("https://jwxt.bnuzh.edu.cn/native-tap-root")
                }
            }
            val active = view!!
            var state = JSONObject()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                state = evaluate(active, "($PAGE_STEP)(false,true)")
                if (state.optBoolean("listTablePresent") && state.optInt("documentCount") == 2) break
                SystemClock.sleep(100)
            }
            checkSafe(state.optBoolean("listTablePresent") && state.optInt("documentCount") == 2, "fixture_not_ready")
            val target = evaluate(active, "($PAGE_STEP)(true,true)").optJSONObject("nativeTap")
                ?: throw SafeStop("export_target_invalid")
            performNativeExportTap(active, target)
            SystemClock.sleep(200)
            val hit = evaluate(active, "JSON.stringify({count:window.frames[0].tapCount||0,trusted:window.frames[0].tapTrusted===true})")
            checkSafe(hit.getInt("count") == 1 && hit.getBoolean("trusted"), "native_tap_not_trusted")
            report.put("nativeTapTrusted", true).put("nativeTapCount", 1).put("iframeVerified", true)
                .put("scaledViewport", kotlin.math.abs(target.getDouble("viewportWidth") - active.width) > 1)
            succeeded = true; reason = "verified"
        } catch (stop: SafeStop) { reason = stop.code }
        catch (_: Throwable) { reason = "operation_failed" }
        finally {
            runCatching { instrumentation.runOnMainSync { view?.stopLoading(); view?.destroy(); compose.activity.setContent { MaterialTheme {} } } }
            val clean = runCatching { clearWebSession() }.isSuccess
            report.put("success", succeeded && clean).put("stopReason", reason).put("cookiesAndStorageCleared", clean)
            reportFile.writeText(report.toString())
        }
        if (!report.optBoolean("success")) throw AssertionError("Native campus fixture stopped: $reason")
    }

    @Test fun officialBrowserLoadsLoginWithoutCredentials() {
        assumeTrue("Explicit campus-startup opt-in is required",
            InstrumentationRegistry.getArguments().getString("cridCampusSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(context.filesDir, "live-campus-startup-result.json")
        val report = JSONObject().put("startupSmoke", true).put("credentialsRead", false).put("success", false)
        var browser: WebView? = null
        var success = false
        var reason = "not_completed"
        try {
            clearWebSession()
            installBrowser {}
            val activeBrowser = awaitBrowser(report)
            browser = activeBrowser
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < deadline) {
                advanceBrowserUi()
                val state = evaluate(activeBrowser, "($PAGE_STEP)(false)")
                report.put("lastPage", state)
                if (state.optString("blocked").isNotEmpty()) throw SafeStop(state.getString("blocked"))
                if (state.optBoolean("casReady")) { success = true; reason = "login_ready"; break }
                SystemClock.sleep(500)
            }
            if (!success) throw SafeStop("login_page_timeout")
        } catch (stop: SafeStop) {
            reason = stop.code
        } catch (_: Throwable) {
            reason = "operation_failed"
        } finally {
            runCatching { compose.runOnUiThread { compose.activity.setContent { MaterialTheme {} } } }
            runCatching { advanceBrowserUi() }
            runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { browser?.clearCache(true); browser?.clearHistory() } }
            val cleaned = runCatching { clearWebSession() }.isSuccess
            report.put("success", success && cleaned).put("stopReason", reason).put("loginReady", success)
                .put("cookiesAndStorageCleared", cleaned)
            reportFile.writeText(report.toString())
        }
        if (!report.optBoolean("success")) throw AssertionError("Campus browser startup stopped: $reason")
    }

    @Test fun officialListExportUsesProductionBrowserAndReadableFilename() {
        assumeTrue("Explicit live-campus opt-in is required",
            InstrumentationRegistry.getArguments().getString("cridLiveCampus") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configFile = File(context.filesDir, "live-campus-config.json")
        val reportFile = File(context.filesDir, "live-campus-result.json")
        val savedState = File(context.filesDir, "timetables-v1.json").let { if (it.exists()) it.readBytes() else null }
        val downloaded = AtomicReference<Uri?>()
        val officialTitle = AtomicReference<String?>()
        val report = JSONObject().put("success", false).put("productionBrowser", true)
            .put("credentialSubmissionCount", 0).put("realFileSavedToPlan", false).put("actionHistory", JSONArray())
        var credentials: JSONObject? = null
        var browser: WebView? = null
        var observedBrowser: WebView? = null
        var phase = "configuration"
        var reason = "not_completed"
        var successful = false
        try {
            credentials = try { JSONObject(configFile.readText()) } finally { configFile.delete() }
            checkSafe(credentials!!.getString("account").isNotBlank() && credentials!!.getString("secret").isNotBlank(), "configuration_missing")
            phase = "open_official_browser"
            clearWebSession()
            installBrowser { downloaded.set(it) }
            browser = awaitBrowser(report)
            val deadline = SystemClock.elapsedRealtime() + 180_000
            var submitted = false
            var navigationActions = 0
            var idlePolls = 0
            var exportCallbackPending = false
            while (SystemClock.elapsedRealtime() < deadline && downloaded.get() == null) {
                val clockFailed = runCatching { advanceBrowserUi() }.isFailure
                if (downloaded.get() != null) {
                    if (exportCallbackPending) report.put("listExportClicked", true).put("exportCallbackRecovered", true).put("exportCallbackPending", false)
                    break
                }
                checkSafe(!clockFailed, "operation_failed")
                if (report.optBoolean("listExportClicked") || exportCallbackPending) {
                    phase = "production_download"
                    checkDownloadFailure()
                    SystemClock.sleep(500)
                    continue
                }
                browser = currentBrowser()
                if (browser == null && downloaded.get() != null) break
                checkSafe(browser != null, "browser_disappeared")
                if (observedBrowser !== browser) {
                    observeOriginalExport(browser!!, officialTitle)
                    observedBrowser = browser
                }
                val searchSubmitted = report.optBoolean("listSearchSubmitted")
                val snapshot = evaluate(browser!!, "($PAGE_STEP)(false,$searchSubmitted,true)")
                report.put("lastPage", snapshot)
                val blocked = snapshot.optString("blocked")
                if (blocked.isNotEmpty()) throw SafeStop(blocked)
                if (snapshot.optBoolean("casReady") && !submitted) {
                    phase = "official_login"
                    val payload = credentials!!.toString()
                    // Native school inputs and its observed login button execute the school's own RSA flow.
                    val login = evaluate(browser!!, """(function(){
                        if(location.protocol!=='https:'||location.hostname!=='cas.bnuzh.edu.cn')return JSON.stringify({blocked:'auth_origin_changed'});
                        var c=$payload,u=document.getElementById('un'),p=document.getElementById('pd'),b=document.getElementById('index_login_btn');
                        if(!u||!p||!b)return JSON.stringify({blocked:'auth_controls_missing'});
                        var r=document.getElementById('rememberName');if(r)r.checked=false;
                        u.value=c.account;p.value=c.secret;u.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('input',{bubbles:true}));
                        c=null;b.click();return JSON.stringify({submitted:true});
                    })()""")
                    checkSafe(login.optBoolean("submitted"), "auth_controls_missing")
                    credentials = null
                    submitted = true
                    report.put("credentialSubmissionCount", 1)
                    SystemClock.sleep(1500)
                    continue
                }
                if (snapshot.optBoolean("schoolPage")) {
                    phase = "read_only_timetable_navigation"
                    val dispatched = AtomicBoolean(false)
                    if (searchSubmitted && snapshot.optBoolean("listTablePresent")) observation.exportPhase.set(true)
                    val next = try { evaluate(browser!!, "($PAGE_STEP)(true,$searchSubmitted,true)", dispatched) }
                    catch (problem: Throwable) {
                        if (searchSubmitted && snapshot.optBoolean("listTablePresent")) {
                            runCatching { advanceBrowserUi() }
                            if (downloaded.get() != null) {
                                report.put("listExportClicked", true).put("exportCallbackRecovered", true)
                                break
                            }
                            // A dispatched export may outlive its JS context. Wait for its
                            // actual URI or production failure; absence of a callback is not success.
                            if (dispatched.get() && report.optBoolean("nativeExportTap")) {
                                exportCallbackPending = true
                                report.put("exportCallbackPending", true)
                                continue
                            }
                        }
                        throw (problem as? SafeStop ?: SafeStop("operation_failed"))
                    }
                    val target = next.optJSONObject("nativeTap")
                    next.remove("nativeTap")
                    if (target != null) {
                        performNativeExportTap(browser!!, target)
                        next.put("clicked", true)
                        report.put("nativeExportTap", true)
                    }
                    report.put("lastPage", next)
                    val nextBlocked = next.optString("blocked")
                    if (nextBlocked.isNotEmpty()) throw SafeStop(nextBlocked)
                    if (next.optBoolean("clicked")) {
                        navigationActions++
                        idlePolls = 0
                        checkSafe(navigationActions <= 10, "navigation_action_limit")
                        report.put("navigationActions", navigationActions)
                        report.getJSONArray("actionHistory").put(JSONObject()
                            .put("action", next.optString("action")).put("label", next.optString("actionLabel")))
                        if (next.optString("action") == "search_list") report.put("listSearchSubmitted", true)
                        if (next.optString("action") == "export_list") {
                            phase = "production_download"
                            report.put("listExportClicked", true)
                        }
                    } else idlePolls++
                    checkDownloadFailure()
                    if (!report.optBoolean("listExportClicked")) checkSafe(idlePolls <= 35, "navigation_unavailable")
                }
                SystemClock.sleep(1200)
            }
            val uri = downloaded.get() ?: throw SafeStop("download_timeout")
            if (exportCallbackPending) report.put("listExportClicked", true).put("exportCallbackRecovered", true).put("exportCallbackPending", false)
            phase = "verify_download"
            checkSafe(uri.scheme == "content" && uri.authority == "${context.packageName}.files", "file_provider_missing")
            val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                checkSafe(cursor.moveToFirst(), "filename_missing")
                cursor.getString(0)
            }
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val readable = filename.isNotBlank() && '\uFFFD' !in filename &&
                !filename.any { it.code in 0x80..0x9F } && !Regex("(?:%[0-9A-Fa-f]{2}){3,}").containsMatchIn(filename)
            report.put("downloadBytes", bytes.size).put("filenameReadable", readable)
                .put("filenameHasChinese", filename.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN })
                .put("fileProviderVerified", true)
            val fromPortal = officialTitle.get()
            val sourceReadable = fromPortal != null && '\uFFFD' !in fromPortal &&
                !fromPortal.any { it.code < 0x20 || it.code in 0x80..0x9F }
            report.put("officialQueryTitlePresent", fromPortal != null).put("officialQueryTitleReadable", sourceReadable)
                .put("filenameMatchesOfficialQueryTitle", fromPortal == filename)
            checkSafe(bytes.isNotEmpty() && bytes.size <= 15 * 1024 * 1024, "download_size_invalid")
            checkSafe(readable, "filename_unreadable")
            checkSafe(sourceReadable, "official_query_title_missing")
            checkSafe(fromPortal == filename, "official_query_title_mismatch")
            val parsed = TimetableParser.parse(bytes, filename)
            report.put("courseCount", parsed.courses.size).put("arrangementCount", parsed.courses.sumOf { it.lessons.size })
                .put("previewNameMatches", parsed.name == filename.substringBeforeLast('.'))
            checkSafe(parsed.valid && parsed.courses.isNotEmpty(), "download_parse_failed")
            checkSafe(parsed.name == filename.substringBeforeLast('.'), "preview_name_mismatch")
            report.put("authenticated", true).put("listExportVerified", report.optBoolean("listExportClicked"))
            checkSafe(report.optBoolean("listExportClicked"), "list_export_not_confirmed")
            successful = true
            reason = "verified"
        } catch (stop: SafeStop) {
            reason = stop.code
        } catch (_: Throwable) {
            // Framework exceptions can include page URLs or content. Never propagate their text.
            reason = "operation_failed"
        } finally {
            credentials = null
            configFile.delete()
            runCatching { checkDownloadFailure() }
            observation.write(report, officialTitle.get())
            runCatching { compose.runOnUiThread { compose.activity.setContent { MaterialTheme {} } } }
            runCatching { compose.waitForIdle() }
            runCatching { instrumentation.runOnMainSync { browser?.clearCache(true); browser?.clearHistory() } }
            val cleaned = runCatching { clearWebSession() }.isSuccess
            val downloads = File(context.cacheDir, "browser-downloads")
            val removed = !downloads.exists() || downloads.deleteRecursively()
            val currentState = File(context.filesDir, "timetables-v1.json").let { if (it.exists()) it.readBytes() else null }
            val stateUnchanged = if (savedState == null) currentState == null else currentState?.contentEquals(savedState) == true
            successful = successful && cleaned && removed && stateUnchanged
            report.put("success", successful).put("phase", phase).put("stopReason", reason)
                .put("cookiesAndStorageCleared", cleaned).put("downloadCacheCleared", removed)
                .put("privateCredentialFileDeleted", !configFile.exists()).put("planDataUnchanged", stateUnchanged)
            reportFile.writeText(report.toString())
        }
        if (!successful) throw AssertionError("Live campus check stopped: $reason")
    }

    private class SafeStop(val code: String) : RuntimeException()
    private fun checkSafe(condition: Boolean, code: String) { if (!condition) throw SafeStop(code) }

    private fun checkDownloadFailure() {
        var failure: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val model = ViewModelProvider(compose.activity).get("campus-browser", CampusBrowserViewModel::class.java)
            if (model.busy) observation.busyObserved.set(true)
            failure = model.failure?.name
            failure?.let { observation.failure.set("download_" + it.lowercase()) }
        }
        if (failure != null) throw SafeStop("download_" + failure!!.lowercase())
        val service = observation.generationStatus.get()
        if (service != -9999 && service != 200) throw SafeStop("generation_failed")
        if (observation.generationHttp.get() in 400..599) throw SafeStop("generation_failed")
    }

    private fun installBrowser(downloaded: (Uri) -> Unit) {
        compose.runOnUiThread {
            WebView.setWebContentsDebuggingEnabled(false)
            compose.activity.setContent {
                MaterialTheme {
                    CampusBrowser(CampusPortals.ZHUHAI, UiText(Language.ZH_CN), onDownloaded = downloaded, onDismiss = {})
                }
            }
        }
        // AppMotionDialog uses Compose animations. A sleeping instrumentation
        // thread does not advance the test clock or create its entering content.
        advanceBrowserUi()
    }

    private fun advanceBrowserUi() {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun awaitBrowser(report: JSONObject): WebView {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            advanceBrowserUi()
            currentBrowser()?.let { report.put("browserCreated", true); writeViewDiagnostics(report); return it }
            SystemClock.sleep(200)
        }
        report.put("browserCreated", false)
        writeViewDiagnostics(report)
        throw SafeStop("browser_not_created")
    }

    private fun writeViewDiagnostics(report: JSONObject) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            report.put("windowRootCount", WindowInspector.getGlobalWindowViews().size)
                .put("activityDecorAttached", compose.activity.window.decorView.isAttachedToWindow)
        }
    }

    private fun currentBrowser(): WebView? {
        var result: WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun find(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            result = find(compose.activity.window.decorView)
                ?: WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
        }
        return result
    }

    private fun evaluate(view: WebView, script: String, dispatched: AtomicBoolean? = null): JSONObject {
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { value -> result.set(value); done.countDown() }
            dispatched?.set(true)
        }
        checkSafe(done.await(10, TimeUnit.SECONDS), "script_timeout")
        val json = JSONTokener(result.get() ?: "null").nextValue() as? String ?: throw SafeStop("script_result_missing")
        return JSONObject(json)
    }

    private fun performNativeExportTap(view: WebView, target: JSONObject) {
        val x = target.getDouble("x"); val y = target.getDouble("y")
        val width = target.getDouble("viewportWidth"); val height = target.getDouble("viewportHeight")
        checkSafe(listOf(x, y, width, height).all { it.isFinite() } && width > 0 && height > 0 &&
            x > 0 && x < width && y > 0 && y < height, "export_target_invalid")
        val point = FloatArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            checkSafe(view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0, "export_target_invalid")
            val origin = IntArray(2); view.getLocationOnScreen(origin)
            point[0] = origin[0] + (x / width * view.width).toFloat()
            point[1] = origin[1] + (y / height * view.height).toFloat()
        }
        val started = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, point[0], point[1], 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(started, started + 80, MotionEvent.ACTION_UP, point[0], point[1], 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(down)
            SystemClock.sleep(80)
            InstrumentationRegistry.getInstrumentation().sendPointerSync(up)
        } finally { down.recycle(); up.recycle() }
    }

    /** Observe the original request while forwarding every callback overridden by CampusBrowser. */
    private fun observeOriginalExport(view: WebView, title: AtomicReference<String?>) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val production = view.webViewClient
            val chrome = view.webChromeClient
            view.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(webView: WebView, progress: Int) { chrome?.onProgressChanged(webView, progress) }
                override fun onJsAlert(webView: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    if (observation.exportPhase.get()) {
                        observation.jsAlert.set(true)
                        val value = message.orEmpty()
                        observation.alertKind.set(when {
                            Regex("没有.{0,8}数据|无.{0,6}数据|未查询到|未找到.{0,8}数据|no data", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "no_data"
                            Regex("生成.{0,10}失败|导出.{0,10}失败|export.{0,10}fail", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "generation_failed"
                            Regex("请.{0,6}登录|未登录|无权限|unauthorized", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "session_required"
                            else -> "unclassified_alert"
                        })
                    }
                    return chrome?.onJsAlert(webView, url, message, result) ?: false
                }
                override fun onJsConfirm(webView: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    if (observation.exportPhase.get()) observation.jsConfirm.set(true)
                    return chrome?.onJsConfirm(webView, url, message, result) ?: false
                }
                override fun onCreateWindow(webView: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                    if (observation.exportPhase.get()) observation.windowOpen.set(true)
                    return chrome?.onCreateWindow(webView, isDialog, isUserGesture, resultMsg) ?: false
                }
                override fun onConsoleMessage(console: ConsoleMessage): Boolean {
                    val message = console.message()
                    if (message.startsWith("__CRID_EXPORT_DIAG__") && message.length < 300) {
                        runCatching {
                            val data = JSONObject(message.removePrefix("__CRID_EXPORT_DIAG__"))
                            if (data.optBoolean("started")) observation.generationStarted.set(true)
                            if (data.has("http")) data.optInt("http", -9999).takeIf { it in 0..599 }?.let(observation.generationHttp::set)
                            if (data.has("status")) data.optInt("status", -9999).takeIf { it in -999..999 }?.let(observation.generationStatus::set)
                        }
                    } else if (observation.exportPhase.get()) {
                        if (message.contains("Mixed Content", true)) observation.mixedContent.set(true)
                        if (console.messageLevel() == ConsoleMessage.MessageLevel.ERROR) observation.jsError.set(true)
                    }
                    // Suppress raw console messages: they may contain private server responses.
                    return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (CampusDownload.isExportRequest(request.url.toString(), request.method) && request.url.path == "/frame/excel") {
                        observation.officialRequest.set(true)
                        runCatching {
                            // Uri decodes the query once; the school's public script applies encodeURIComponent twice.
                            val encoded = request.url.getQueryParameters("title").singleOrNull()
                            encoded?.let { URLDecoder.decode(it, "UTF-8") }
                        }.getOrNull()?.takeIf { it.length in 1..1024 }?.let(title::set)
                    }
                    return production.shouldInterceptRequest(webView, request)
                }
                override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest) = production.shouldOverrideUrlLoading(webView, request)
                override fun onPageStarted(webView: WebView, url: String?, favicon: Bitmap?) = production.onPageStarted(webView, url, favicon)
                override fun doUpdateVisitedHistory(webView: WebView, url: String?, isReload: Boolean) = production.doUpdateVisitedHistory(webView, url, isReload)
                override fun onPageFinished(webView: WebView, url: String?) = production.onPageFinished(webView, url)
                override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) = production.onReceivedError(webView, request, error)
                override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) = production.onReceivedSslError(webView, handler, error)
            }
        }
    }

    private fun clearWebSession() {
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().removeAllCookies { done.countDown() }
            WebStorage.getInstance().deleteAllData()
        }
        checkSafe(done.await(10, TimeUnit.SECONDS), "cookie_cleanup_timeout")
    }

    companion object {
        // Only fixed, visible labels are returned. No account, arbitrary text, HTML or URL leaves WebView.
        private val PAGE_STEP = """function(act,searchSubmitted,verifyTerm){
            var out={schoolPage:false,casReady:false,clicked:false},docs=[],seenDocs=[];
            function add(w,depth){try{if(depth>3||seenDocs.indexOf(w.document)>=0)return;seenDocs.push(w.document);docs.push(w.document);for(var i=0;i<w.frames.length;i++)add(w.frames[i],depth+1);}catch(e){}}
            add(window,0);
            function visible(e){return !!(e&&e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none');}
            function label(e){return (e.innerText||e.value||e.getAttribute('title')||'').replace(/\s+/g,' ').trim();}
            function buttons(d){return Array.from(d.querySelectorAll('a,button,input[type=button],input[type=submit],[onclick]')).filter(e=>visible(e)&&!e.disabled&&e.getAttribute('aria-disabled')!=='true');}
            var host=location.hostname;
            if(location.href==='about:blank'){out.loading=true;return JSON.stringify(out);}
            if(location.protocol!=='https:'||!['cas.bnuzh.edu.cn','jwxt.bnuzh.edu.cn','one.bnuzh.edu.cn'].includes(host)){out.blocked='unexpected_origin';return JSON.stringify(out);}
            out.schoolPage=host==='jwxt.bnuzh.edu.cn';
            out.documentCount=docs.length;
            for(var d of docs){
                var body=d.body?d.body.innerText:'',passwords=Array.from(d.querySelectorAll('input[type=password]')).filter(visible);
                if(passwords.length>=2){out.blocked='password_change_required';return JSON.stringify(out);}
                if(/二次认证|动态口令|短信验证|two.factor|one.time password/i.test(body)&&Array.from(d.querySelectorAll('input')).some(e=>visible(e)&&/otp|verify|sms|code/i.test(e.id+' '+e.name))){out.blocked='mfa_required';return JSON.stringify(out);}
                if(host==='cas.bnuzh.edu.cn'){
                    var error=d.getElementById('errorMsg');if(visible(error)&&error.innerText.trim()){out.blocked='authentication_error';return JSON.stringify(out);}
                    if(Array.from(d.querySelectorAll('input')).some(e=>visible(e)&&/captcha|validatecode|verifycode|codeValidate/i.test(e.id+' '+e.name))){out.blocked='captcha_required';return JSON.stringify(out);}
                    out.casReady=!!(d.getElementById('un')&&d.getElementById('pd')&&d.getElementById('index_login_btn'));
                }
            }
            if(!out.schoolPage)return JSON.stringify(out);
            var timetable=['学生个人课表','个人课表','我的课表','学生课表','学期课表','课表查询','查询课表','本学期课表','查看课表','Timetable','My timetable'];
            var parentMenus=['网上选课','教学安排','课程安排','我的学业','教学服务','学生个人中心','学生专区','课表'];
            var listModes=['按列表方式显示','列表模式','列表形式','列表显示','列表课表','列表格式','以列表显示','按列表显示','按列表','列表式','按课程列表','列表视图','列表样式','列表输出','列表','List','List view'];
            var gridModes=['按课表方式显示'];
            var exports=['导出Excel','导出 Excel','导出 EXCEL','导出列表','导出课表','导出','Excel','EXCEL','下载课表'];
            var roles=['本科学生','本科生','学生','Undergraduate','Student'];
            var visibleKnown=[];for(var d of docs)for(var e of buttons(d)){var s=label(e);if(timetable.concat(parentMenus,listModes,exports,roles,['检索']).includes(s)&&!visibleKnown.includes(s))visibleKnown.push(s);}
            out.visibleKnownLabels=visibleKnown;
            var formats=[],knownFormats=[];out.radioCount=0;out.selectCount=0;out.listRadioAvailable=false;out.listSelectAvailable=false;out.listLabelAvailable=false;out.gridRadioAvailable=false;out.gridModeSelected=false;
            function observeGeneration(w){try{
                var proto=w.XMLHttpRequest&&w.XMLHttpRequest.prototype;if(!proto||proto.__cridGenerationObserved)return;
                proto.__cridGenerationObserved=true;var originalOpen=proto.open,originalSend=proto.send;
                function send(data){w.console.info('__CRID_EXPORT_DIAG__'+JSON.stringify(data));}
                proto.open=function(method,url){var tracked=false;try{var u=new w.URL(url,w.document.baseURI);tracked=u.hostname==='jwxt.bnuzh.edu.cn'&&u.pathname==='/frame/excel'&&u.searchParams.get('method')==='toexcel'&&String(method).toUpperCase()==='POST';}catch(e){}
                    this.__cridGenerationTracked=tracked;this.__cridGenerationReported=false;
                    if(tracked)this.addEventListener('readystatechange',function(){if(this.readyState!==4||!this.__cridGenerationTracked||this.__cridGenerationReported)return;this.__cridGenerationReported=true;
                        var data={http:Number(this.status)||0};try{var status=JSON.parse(this.responseText).status;if(/^-?\d{1,3}$/.test(String(status)))data.status=Number(status);}catch(e){}send(data);
                    },true);return originalOpen.apply(this,arguments);};
                proto.send=function(){if(this.__cridGenerationTracked)send({started:true});return originalSend.apply(this,arguments);};
            }catch(e){}}
            for(var d of docs)observeGeneration(d.defaultView);
            function radioNames(r){var names=Array.from(r.labels||[]).filter(visible).map(label);names.push(r.getAttribute('aria-label')||'',r.getAttribute('title')||'');
                var node=r.nextSibling;for(var n=0;node&&n<4;n++,node=node.nextSibling){if(node.nodeType===3){var s=node.textContent.trim();if(s){names.push(s);break;}}
                    else if(node.nodeType===1){if(node.matches('input,select,button')||node.querySelector('input,select,button'))break;if(visible(node))names.push(label(node));}}
                if(r.parentElement)names.push(label(r.parentElement));return names;}
            for(var d of docs){
                for(var r of d.querySelectorAll('input[type=radio]')){
                    var labels=Array.from(r.labels||[]).filter(visible),names=radioNames(r);
                    var text=names.find(s=>listModes.includes(s)),grid=names.find(s=>gridModes.includes(s));
                    if(visible(r))out.radioCount++;if(text&&(visible(r)||labels.length)&&!r.disabled){formats.push({d:d,e:visible(r)?r:labels[0],text:text,selected:r.checked,type:'radio'});out.listRadioAvailable=true;}
                    if(grid&&(visible(r)||labels.length)){out.gridRadioAvailable=true;if(r.checked)out.gridModeSelected=true;if(!knownFormats.includes(grid))knownFormats.push(grid);}
                }
                for(var select of d.querySelectorAll('select'))if(visible(select)&&!select.disabled){out.selectCount++;for(var option of select.options){var text=label(option);if(listModes.includes(text)){formats.push({d:d,e:select,o:option,text:text,selected:option.selected,type:'select'});out.listSelectAvailable=true;}}}
                for(var e of d.querySelectorAll('label'))if(visible(e)&&listModes.includes(label(e))){out.listLabelAvailable=true;}
            }
            for(var format of formats)if(!knownFormats.includes(format.text))knownFormats.push(format.text);
            out.knownFormatLabels=knownFormats;out.listModeSelected=formats.some(f=>f.selected);
            function termOf(option){var value=option.textContent.replace(/\s+/g,'').replace(/[–—/]/g,'-'),year=/(20\d{2})[^\d]+(20\d{2})/.exec(value);if(!year)return null;
                var tail=value.slice(year.index+year[0].length),term=/秋|第一|第1|上学期|(?:^|\D)1(?:\D|$)/.test(tail)?1:/春|第二|第2|下学期|(?:^|\D)2(?:\D|$)/.test(tail)?2:0;
                return {start:Number(year[1]),end:Number(year[2]),term:term};}
            if(verifyTerm&&formats.length){var targetTerms=[];
                for(var d of docs)if(formats.some(f=>f.d===d))for(var select of d.querySelectorAll('select'))if(visible(select)&&!select.disabled){
                    var current=select.selectedOptions.length?termOf(select.selectedOptions[0]):null;if(current){out.selectedYearStart=current.start;out.selectedYearEnd=current.end;out.selectedTermNumber=current.term;}
                    for(var option of select.options){var term=termOf(option);if(term&&term.start===2026&&term.end===2027&&term.term===1)targetTerms.push({select:select,option:option});}}
                out.targetTermPresent=targetTerms.length===1;out.targetTermSelected=targetTerms.length===1&&targetTerms[0].option.selected;
                if(!out.targetTermPresent){out.blocked='target_term_unavailable';return JSON.stringify(out);}
                if(!out.targetTermSelected){if(searchSubmitted){out.blocked='target_term_changed';return JSON.stringify(out);}if(act){var target=targetTerms[0];target.select.selectedIndex=target.option.index;target.select.dispatchEvent(new Event('change',{bubbles:true}));out.clicked=true;out.action='select_term';out.actionLabel='target_term';}return JSON.stringify(out);}
            }
            function click(labels,key,predicate){for(var d of docs){var prior=d.defaultView.__cridLiveClicked||(d.defaultView.__cridLiveClicked={});if(prior[key])continue;
                var all=buttons(d).filter(e=>labels.includes(label(e))&&(!predicate||predicate(d)));
                if(all.length){var e=all[0];prior[key]=true;if(act){if(key==='search_list')d.defaultView.__cridLiveNeedsListSearch=false;out.actionLabel=label(e);out.clicked=true;out.action=key;setTimeout(()=>e.click(),0);}return true;}}
                return false;}
            function markers(d){var cells=Array.from(d.querySelectorAll('th,td,span,div,label')).filter(visible).map(e=>label(e).replace(/\s/g,''));return {course:cells.some(s=>['课程名称','课程名','Coursename','CourseName'].includes(s)),teacher:cells.some(s=>['任课教师','授课教师','教师姓名','教师','Teacher'].includes(s)),credits:cells.some(s=>['学分','Credits'].includes(s)),time:cells.some(s=>['上课时间地点','上课时间及地点','上课时间','Time','上课时间/地点'].includes(s))};}
            var allMarkers=docs.map(markers);out.courseNameMarker=allMarkers.some(m=>m.course);out.teacherMarker=allMarkers.some(m=>m.teacher);out.creditsMarker=allMarkers.some(m=>m.credits);out.timeMarker=allMarkers.some(m=>m.time);
            function listTable(d){var m=markers(d);return [m.course,m.teacher,m.credits,m.time].filter(Boolean).length>=2;}
            function nativeExport(){var candidates=[];
                for(var d of docs)if(listTable(d)||formats.some(f=>f.d===d&&f.selected))for(var e of buttons(d))if(exports.includes(label(e)))candidates.push(e);
                candidates=candidates.filter(e=>!candidates.some(other=>other!==e&&e.contains(other)));
                if(candidates.length!==1){if(candidates.length>1)out.blocked='ambiguous_export_control';return;}
                var e=candidates[0],d=e.ownerDocument,prior=d.defaultView.__cridLiveClicked||(d.defaultView.__cridLiveClicked={});if(prior.export_list)return;
                e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});
                var r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2,w=d.defaultView;
                var hit=d.elementFromPoint(x,y);if(!hit||!(e===hit||e.contains(hit))){out.blocked='export_target_obscured';return;}
                while(w!==window){var frame=w.frameElement;if(!frame||w.parent.getComputedStyle(frame).transform!=='none'){out.blocked='export_target_invalid';return;}
                    var box=frame.getBoundingClientRect();x+=box.left+frame.clientLeft;y+=box.top+frame.clientTop;w=w.parent;
                    var upper=w.document.elementFromPoint(x,y);if(!upper||!(frame===upper||frame.contains(upper))){out.blocked='export_target_obscured';return;}}
                var viewport=window.visualViewport,ox=viewport?viewport.offsetLeft:0,oy=viewport?viewport.offsetTop:0,vw=viewport?viewport.width:innerWidth,vh=viewport?viewport.height:innerHeight;
                x-=ox;y-=oy;if(!(x>0&&y>0&&x<vw&&y<vh)){out.blocked='export_target_invalid';return;}
                prior.export_list=true;out.action='export_list';out.actionLabel=label(e);out.nativeTap={x:x,y:y,viewportWidth:vw,viewportHeight:vh};
            }
            out.listTablePresent=docs.some(listTable);
            out.tableDataRowCount=docs.filter(listTable).reduce((total,d)=>total+Array.from(d.querySelectorAll('tr')).filter(row=>{
                if(!visible(row)||row.querySelector('table,select,button,input[type=button],input[type=radio],input[type=text]')||row.cells.length<2)return false;
                var cells=Array.from(row.cells).map(e=>label(e).replace(/\s/g,''));return !cells.some(s=>['课程名称','课程名','任课教师','授课教师','教师姓名','教师','学分','上课时间及地点','Teacher','Credits'].includes(s));}).length,0);
            out.listSearchPending=docs.some(d=>d.defaultView.__cridLiveNeedsListSearch===true);
            if(!act)return JSON.stringify(out);
            // Searching is mandatory even when the list radio was already selected on first load.
            // The host remembers submission across a frame's document reload.
            if(searchSubmitted){if(out.listTablePresent)nativeExport();return JSON.stringify(out);}
            if(out.listSearchPending||out.listModeSelected){click(['检索'],'search_list',d=>d.defaultView.__cridLiveNeedsListSearch===true||formats.some(f=>f.d===d&&f.selected));return JSON.stringify(out);}
            for(var format of formats)if(!format.selected){var prior=format.d.defaultView.__cridLiveClicked||(format.d.defaultView.__cridLiveClicked={});if(prior.list_view)continue;prior.list_view=true;format.d.defaultView.__cridLiveNeedsListSearch=true;
                if(format.type==='select'){format.e.value=format.o.value;format.e.dispatchEvent(new Event('change',{bubbles:true}));}else format.e.click();
                out.clicked=true;out.action='list_view';out.actionLabel=format.text;return JSON.stringify(out);}
            if(click(listModes,'list_view'))return JSON.stringify(out);
            if(click(timetable,'timetable'))return JSON.stringify(out);
            if(click(parentMenus,'open_timetable_menu'))return JSON.stringify(out);
            var roleControls=[];for(var d of docs)for(var e of buttons(d))if(roles.includes(label(e)))roleControls.push(e);
            if(roleControls.length>1){out.blocked='ambiguous_student_role';return JSON.stringify(out);}
            if(roleControls.length===1)click(roles,'student_role');
            return JSON.stringify(out);
        }""".trimIndent()
    }
}
