package cn.crid.next.ui

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import cn.crid.next.data.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Takes ownership of a known export's first GET, before WebView consumes a one-time response. */
internal class CampusResponseCapture(
    private val view: WebView,
    private val directory: File,
    private val userAgent: String,
    private val downloads: CampusBrowserViewModel,
    private val currentPage: () -> String,
    private val transport: () -> CampusDownload = {
        val cookieManager = CookieManager.getInstance()
        CampusDownload(directory, cookiesFor = cookieManager::getCookie,
            receiveCookie = { source, cookie -> cookieManager.setCookie(source, cookie) })
    },
) {
    private val redirects = ConcurrentHashMap<String, List<String>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val streams = ConcurrentHashMap.newKeySet<InputStream>()
    private val active = ConcurrentHashMap.newKeySet<CampusDownloadCancellation>()
    private val disposed = AtomicBoolean(false)

    fun close() {
        disposed.set(true); redirects.clear()
        active.forEach { it.cancel() }; active.clear()
        streams.forEach { runCatching { it.close() } }; streams.clear()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val redirectedFrom = redirects.remove(url)
        if (disposed.get() || request.method != "GET" || !CampusDownload.isCampusUrl(currentPage()) ||
            (redirectedFrom == null && !CampusDownload.isExportRequest(url, request.method))) return null
        if (!inFlight.add(url)) return emptyResponse()
        val signal = runBlocking { withContext(Dispatchers.Main.immediate) { downloads.beginIntercept() } }
        if (signal == null) { inFlight.remove(url); return emptyResponse() }
        active.add(signal)
        if (disposed.get()) signal.cancel()
        try {
            val transfer = transport()
                .intercept(CampusDownloadRequest(url, currentPage(), userAgent, requestHeaders = request.requestHeaders), signal) { bytes, total ->
                    view.post { downloads.interceptProgress(signal, bytes, total) }
                }
            return when (transfer) {
                is CampusTransfer.Downloaded -> {
                    runBlocking { withContext(Dispatchers.Main.immediate) { downloads.endIntercept(signal, transfer.file) } }
                    emptyResponse()
                }
                is CampusTransfer.Redirect -> {
                    val history = (redirectedFrom ?: emptyList()) + url
                    if (history.size > 8 || transfer.url in history) throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
                    redirects[transfer.url] = history
                    runBlocking { withContext(Dispatchers.Main.immediate) { downloads.endIntercept(signal) } }
                    // The next request has its actual origin in WebView, including a possible CAS redirect.
                    view.post { if (!disposed.get()) view.loadUrl(transfer.url) }
                    emptyResponse()
                }
                is CampusTransfer.Page -> {
                    runBlocking { withContext(Dispatchers.Main.immediate) { downloads.endIntercept(signal) } }
                    val stream = object : FilterInputStream(transfer.stream) {
                        override fun close() { try { super.close() } finally { streams.remove(this) } }
                    }
                    streams.add(stream)
                    if (disposed.get()) { stream.close(); emptyResponse() }
                    else WebResourceResponse(transfer.mime, transfer.encoding, transfer.status, transfer.reason, transfer.headers, stream)
                }
            }
        } catch (_: CancellationException) {
            runBlocking { withContext(Dispatchers.Main.immediate) { downloads.endIntercept(signal) } }
        } catch (failure: CampusDownloadException) {
            runBlocking { withContext(Dispatchers.Main.immediate) { downloads.endIntercept(signal, failure = failure.reason) } }
        } catch (_: Exception) {
            runBlocking { withContext(Dispatchers.Main.immediate) { downloads.endIntercept(signal, failure = CampusDownloadFailure.CONNECTION) } }
        } finally {
            active.remove(signal)
            inFlight.remove(url)
        }
        return emptyResponse()
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(byteArrayOf()))
}
