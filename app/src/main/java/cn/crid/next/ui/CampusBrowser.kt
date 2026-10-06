package cn.crid.next.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import cn.crid.next.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class CampusPageState(val restored: Bundle? = null) {
    var view: WebView? = null
    companion object {
        val saver = Saver<CampusPageState, Bundle>(
            save = { state -> Bundle().also { state.view?.saveState(it) } },
            restore = { CampusPageState(it) },
        )
    }
}

internal class CampusBrowserViewModel : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(-1f)
        private set
    var failure by mutableStateOf<CampusDownloadFailure?>(null)
        private set
    var completed by mutableStateOf<File?>(null)
        private set
    private var job: Job? = null
    private var cancellation: CampusDownloadCancellation? = null

    fun beginIntercept(): CampusDownloadCancellation? {
        if (busy || completed != null) return null
        return CampusDownloadCancellation().also { cancellation = it; busy = true; failure = null; progress = -1f }
    }
    fun interceptProgress(signal: CampusDownloadCancellation, bytes: Long, total: Long) {
        if (cancellation === signal) progress = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else -1f
    }
    fun endIntercept(signal: CampusDownloadCancellation, file: File? = null, failure: CampusDownloadFailure? = null) {
        if (cancellation !== signal) { file?.let { it.delete(); it.parentFile?.delete() }; return }
        if (runCatching { signal.check() }.isFailure) {
            file?.let { it.delete(); it.parentFile?.delete() }
            busy = false; cancellation = null
            return
        }
        completed = file
        this.failure = failure
        busy = false
        cancellation = null
    }

    fun start(directory: File, request: CampusDownloadRequest) {
        if (busy) return
        failure = null
        busy = true
        progress = -1f
        val signal = CampusDownloadCancellation()
        cancellation = signal
        val cookieManager = CookieManager.getInstance()
        job = viewModelScope.launch {
            var receivedFile: File? = null
            try {
                completed = withContext(Dispatchers.IO) {
                    CampusDownload(directory, cookiesFor = { cookieManager.getCookie(it) },
                        receiveCookie = { url, cookie -> cookieManager.setCookie(url, cookie) })
                        .download(request, signal) { bytes, total ->
                            val fraction = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else -1f
                            viewModelScope.launch { if (cancellation === signal) progress = fraction }
                        }.also { receivedFile = it }
                }
            } catch (cancelled: CancellationException) {
                receivedFile?.let { it.delete(); it.parentFile?.delete() }
                throw cancelled
            } catch (error: CampusDownloadException) {
                failure = error.reason
            } catch (_: Exception) {
                failure = CampusDownloadFailure.CONNECTION
            } finally {
                if (cancellation === signal) { busy = false; cancellation = null }
            }
        }
    }

    fun takeCompleted(): File? = completed.also { completed = null }
    fun cancel() {
        cancellation?.cancel(); cancellation = null; job?.cancel(); busy = false; failure = null
        completed?.let { it.delete(); it.parentFile?.delete() }
        completed = null
    }
    override fun onCleared() { cancel() }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CampusBrowser(initialUrl: String, text: UiText, onDownloaded: (Uri) -> Unit, onDismiss: () -> Unit, origin: ModalOrigin? = null) {
    val context = LocalContext.current
    val downloads: CampusBrowserViewModel = viewModel(key = "campus-browser")
    val page = rememberSaveable(initialUrl, saver = CampusPageState.saver) { CampusPageState() }
    val currentPage = remember(initialUrl) { AtomicReference(initialUrl) }
    var responseCapture by remember { mutableStateOf<CampusResponseCapture?>(null) }
    var host by remember { mutableStateOf(Uri.parse(initialUrl).host.orEmpty()) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var pageFailed by remember { mutableStateOf(false) }
    var unsupportedLink by remember { mutableStateOf(false) }
    val downloadedCallback by rememberUpdatedState(onDownloaded)
    val close: () -> Unit = { downloads.cancel(); onDismiss() }
    DisposableEffect(page) {
        onDispose {
            responseCapture?.close()
            page.view?.apply {
                stopLoading()
                setDownloadListener(null)
                webChromeClient = null
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            page.view = null
            CookieManager.getInstance().flush()
        }
    }
    AnimatedAppDialog(onDismissRequest = close, fullScreen = true, origin = origin, backNavigatesContent = canBack,
        onContentBack = { page.view?.goBack() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, decorFitsSystemWindows = false)) { motion ->
        LaunchedEffect(downloads.completed) {
            downloads.completed?.let { file ->
                runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
                    .onSuccess { uri -> motion.finish { downloads.takeCompleted(); downloadedCallback(uri) } }
                    .onFailure { downloads.takeCompleted(); file.delete(); pageFailed = true }
            }
        }
        MatchDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(title = {
                        Column {
                            Text(text.t("教务系统", "Campus portal"), maxLines = 1)
                            Text(host, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, navigationIcon = {
                        GlyphAction("close", text.t("关闭教务系统", "Close campus portal"), motion.dismiss,
                            modifier = Modifier.testTag("campus_close"))
                    }, actions = {
                        GlyphAction("previous", text.t("网页后退", "Go back"), { page.view?.goBack() }, enabled = canBack)
                        GlyphAction("next", text.t("网页前进", "Go forward"), { page.view?.goForward() }, enabled = canForward)
                        GlyphAction("sync", text.t("刷新网页", "Reload page"), { page.view?.reload() })
                    })
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if (pageProgress in 0..99 && !pageFailed) LinearProgressIndicator(
                        progress = { pageProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    if (downloads.busy) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    VisualCenterText(text.t("正在接收课表…", "Receiving your timetable…"), style = MaterialTheme.typography.labelLarge)
                                    if (downloads.progress >= 0) LinearProgressIndicator(progress = { downloads.progress }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                Box(alignByVisualCenter()) {GlyphAction("close", text.t("取消下载", "Cancel download"), downloads::cancel)}
                            }
                        }
                    }
                    val failure = downloads.failure
                    if (pageFailed || unsupportedLink || failure != null) {
                        val message = when {
                            unsupportedLink || failure == CampusDownloadFailure.UNSUPPORTED_LINK -> text.t(
                                "请在教务系统中选择课表文件下载；也可下载后从本地文件导入。",
                                "Choose a timetable download in the campus portal, or import a saved file.")
                            failure == CampusDownloadFailure.TOO_LARGE -> text.t("文件超过 15 MB，请选择较小的课表文件。", "This file is over 15 MB. Choose a smaller timetable.")
                            failure == CampusDownloadFailure.EMPTY -> text.t("下载的文件为空，请重新导出课表。", "The downloaded file is empty. Export the timetable again.")
                            failure != null -> text.t("下载未完成，请检查网络并重试。", "The download did not finish. Check your connection and try again.")
                            else -> text.t("网页暂时无法打开，请检查网络后刷新。", "The page could not load. Check your connection and reload.")
                        }
                        Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    AndroidView(modifier = Modifier.fillMaxWidth().weight(1f).testTag("campus_webview"), factory = { viewContext ->
                        WebView(viewContext).apply {
                            page.view = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                safeBrowsingEnabled = true
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            val capture = CampusResponseCapture(this, File(context.cacheDir, "browser-downloads"), settings.userAgentString, downloads, currentPage = { currentPage.get() })
                            responseCapture = capture
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { pageProgress = newProgress }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = capture.intercept(request)
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val raw = request.url.toString()
                                    val normalized = CampusPortals.secureNavigationUrl(raw)
                                    if (request.method == "GET" && normalized != null && raw != normalized) {
                                        view.loadUrl(normalized)
                                        return true
                                    }
                                    val allowed = CampusDownload.isCampusUrl(raw)
                                    if (!allowed && request.isForMainFrame) unsupportedLink = true
                                    return !allowed
                                }
                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    if (url != null) currentPage.set(url)
                                    pageFailed = false
                                    unsupportedLink = false
                                    host = url?.let { Uri.parse(it).host }.orEmpty()
                                }
                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    if (url != null) currentPage.set(url)
                                    canBack = view.canGoBack(); canForward = view.canGoForward()
                                    host = url?.let { Uri.parse(it).host }.orEmpty()
                                }
                                override fun onPageFinished(view: WebView, url: String?) {
                                    canBack = view.canGoBack(); canForward = view.canGoForward()
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) pageFailed = true
                                }
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    handler.cancel()
                                    pageFailed = true
                                }
                            }
                            setDownloadListener { url, userAgent, disposition, mime, length ->
                                unsupportedLink = false
                                downloads.start(File(context.cacheDir, "browser-downloads"), CampusDownloadRequest(
                                    url = url, pageUrl = this.url ?: initialUrl, userAgent = userAgent ?: settings.userAgentString,
                                    contentDisposition = disposition, mimeType = mime, contentLength = length))
                            }
                            if (page.restored == null || restoreState(page.restored) == null) loadUrl(initialUrl)
                        }
                    })
                }
            }
        }
    }
}
