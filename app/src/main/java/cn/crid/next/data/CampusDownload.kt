package cn.crid.next.data

import java.io.File
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException

internal enum class CampusDownloadFailure { UNSUPPORTED_LINK, TOO_LARGE, CONNECTION, EMPTY }
internal class CampusDownloadException(val reason: CampusDownloadFailure) : IOException(reason.name)

internal data class CampusDownloadRequest(
    val url: String,
    val pageUrl: String,
    val userAgent: String,
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val contentLength: Long = -1,
    val requestHeaders: Map<String, String> = emptyMap(),
)

internal sealed interface CampusTransfer {
    data class Downloaded(val file: File) : CampusTransfer
    data class Redirect(val url: String) : CampusTransfer
    data class Page(val mime: String, val encoding: String, val status: Int, val reason: String,
        val headers: Map<String, String>, val stream: InputStream, val finalUrl: String, val redirected: Boolean) : CampusTransfer
}

/** A cancellation also disconnects a blocked read, without leaving an importable partial file. */
internal class CampusDownloadCancellation {
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>(null)
    fun cancel() { cancelled.set(true); active.getAndSet(null)?.disconnect() }
    fun check() { if (cancelled.get()) throw CancellationException("Download cancelled") }
    fun attach(connection: HttpURLConnection) { active.set(connection); if (cancelled.get()) { connection.disconnect(); check() } }
    fun detach(connection: HttpURLConnection) { active.compareAndSet(connection, null) }
}

/** No Android dependency: the transport and per-URL cookie store can be tested without an account. */
internal class CampusDownload(
    private val directory: File,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val cookiesFor: (String) -> String? = { null },
    private val receiveCookie: (String, String) -> Unit = { _, _ -> },
) {
    fun download(
        request: CampusDownloadRequest,
        cancellation: CampusDownloadCancellation = CampusDownloadCancellation(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = (transfer(request, cancellation, onProgress, false) as CampusTransfer.Downloaded).file

    /** Captures the *original* WebView GET response. The server is never asked to regenerate it. */
    fun intercept(request: CampusDownloadRequest, cancellation: CampusDownloadCancellation,
        onProgress: (Long, Long) -> Unit = { _, _ -> }): CampusTransfer = transfer(request, cancellation, onProgress, true)

    private fun transfer(request: CampusDownloadRequest, cancellation: CampusDownloadCancellation,
        onProgress: (Long, Long) -> Unit, returnPages: Boolean): CampusTransfer {
        cancellation.check()
        if (request.contentLength > MAX_BYTES) throw CampusDownloadException(CampusDownloadFailure.TOO_LARGE)
        val page = secureCampusUrl(request.pageUrl)
        var target = secureCampusUrl(request.url)
        val visited = mutableSetOf<String>()
        val deadline = System.nanoTime() + 90_000_000_000L
        var temporary: File? = null
        var downloadDirectory: File? = null
        try {
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                cancellation.check()
                if (System.nanoTime() > deadline || !visited.add(target.toExternalForm())) throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
                val connection = openConnection(target)
                cancellation.attach(connection)
                var returnedStream = false
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    safeHeader(request.userAgent)?.let { connection.setRequestProperty("User-Agent", it) }
                    request.requestHeaders.filterKeys { it.equals("Accept", true) || it.equals("Accept-Language", true) || it.equals("X-Requested-With", true) }
                        .forEach { (name, value) -> safeHeader(value)?.let { connection.setRequestProperty(name, it) } }
                    // Cookies are always looked up for this exact URL; never copy a previous host's headers.
                    safeHeader(cookiesFor(target.toExternalForm()))?.let { connection.setRequestProperty("Cookie", it) }
                    val originalReferer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                        ?.takeIf { isCampusUrl(it) }?.let(::URL) ?: page
                    connection.setRequestProperty("Referer", referer(originalReferer, target))
                    val status = connection.responseCode
                    connection.headerFields.entries.filter { it.key?.equals("Set-Cookie", true) == true }
                        .flatMap { it.value.orEmpty() }.forEach { receiveCookie(target.toExternalForm(), it) }
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        if (redirectCount == MAX_REDIRECTS) throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
                        val location = connection.getHeaderField("Location") ?: throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
                        val redirectedUrl = CampusPortals.secureNavigationUrl(URL(target, location).toExternalForm())
                            ?: throw CampusDownloadException(CampusDownloadFailure.UNSUPPORTED_LINK)
                        target = secureCampusUrl(redirectedUrl)
                        // Let WebView navigate the next hop, so a login page never executes under the export origin.
                        if (returnPages) return CampusTransfer.Redirect(target.toExternalForm())
                        return@repeat
                    }
                    val disposition = connection.getHeaderField("Content-Disposition")
                    val responseMime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    val isAttachment = disposition?.let { Regex("(?:^|;)\\s*(?:attachment\\b|filename\\*?\\s*=)", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
                    val isDownloadMime = responseMime in setOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/x-excel", "application/octet-stream", "application/download", "application/force-download", "text/csv")
                    if (returnPages && (status != 200 || !(isAttachment || isDownloadMime))) {
                    val raw = if (status >= 400) connection.errorStream ?: ByteArrayInputStream(ByteArray(0)) else connection.inputStream
                        val compressed = connection.contentEncoding?.equals("gzip", true) == true
                        val stream = object : FilterInputStream(if (compressed) GZIPInputStream(raw) else raw) {
                            override fun close() { try { super.close() } finally { connection.disconnect() } }
                        }
                        returnedStream = true
                        return CampusTransfer.Page(responseMime.ifBlank { "text/html" },
                            Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(connection.contentType.orEmpty())?.groupValues?.get(1)?.trim('"', '\'') ?: "UTF-8",
                            status, connection.responseMessage?.takeIf { it.isNotBlank() } ?: "OK",
                            connection.headerFields.filterKeys { it != null && (!compressed || !(it.equals("Content-Encoding", true) || it.equals("Content-Length", true))) }
                                .mapValues { it.value.joinToString(", ") }, stream,
                            target.toExternalForm(), redirectCount > 0)
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
                    val gzip = connection.contentEncoding?.equals("gzip", true) == true
                    val length = if (gzip) -1 else connection.contentLengthLong
                    if (length > MAX_BYTES) throw CampusDownloadException(CampusDownloadFailure.TOO_LARGE)
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Download directory unavailable")
                    val folder = File(directory, UUID.randomUUID().toString())
                    if (!folder.mkdir()) throw IOException("Download directory unavailable")
                    downloadDirectory = folder
                    val partial = File(folder, ".download.part")
                    temporary = partial
                    var received = 0L
                    onProgress(0L, length)
                    (if (gzip) GZIPInputStream(connection.inputStream) else connection.inputStream).use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(16_384)
                            while (true) {
                                cancellation.check()
                                if (System.nanoTime() > deadline) throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > MAX_BYTES) throw CampusDownloadException(CampusDownloadFailure.TOO_LARGE)
                                output.write(buffer, 0, count)
                                onProgress(received, length)
                            }
                        }
                    }
                    cancellation.check()
                    if (received == 0L) throw CampusDownloadException(CampusDownloadFailure.EMPTY)
                    if (length >= 0 && received != length) throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
                    val filename = FilenameDecoder.filename(disposition, target.path,
                        connection.contentType ?: request.mimeType, request.contentDisposition,
                        requestUrl = target.toExternalForm())
                    cancellation.check()
                    val complete = File(folder, filename)
                    if (!partial.renameTo(complete)) throw IOException("Download could not be completed")
                    temporary = null
                    return CampusTransfer.Downloaded(complete)
                } finally {
                    cancellation.detach(connection)
                    if (!returnedStream) connection.disconnect()
                }
            }
            throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
        } catch (exception: Exception) {
            temporary?.delete()
            downloadDirectory?.delete()
            cancellation.check()
            if (exception is CancellationException || exception is CampusDownloadException) throw exception
            // The original exception may contain a signed URL. Keep it out of diagnostics and UI.
            throw CampusDownloadException(CampusDownloadFailure.CONNECTION)
        }
    }

    companion object {
        const val MAX_BYTES = 15L * 1024 * 1024
        private const val MAX_REDIRECTS = 8

        fun isCampusUrl(value: String): Boolean = runCatching { secureCampusUrl(value); true }.getOrDefault(false)

        /** Only export requests are taken over; CAS, login and ordinary portal navigation stay in WebView. */
        fun isExportRequest(url: String, method: String): Boolean {
            if (method != "GET" || !isCampusUrl(url)) return false
            val uri = URI(url)
            val path = uri.path.lowercase(Locale.ROOT)
            if (Regex("(?:^|/)(?:cas|login|logout|auth)(?:[/.]|$)").containsMatchIn(path)) return false
            val pairs = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val split = pair.split('=', limit = 2)
                if (split.size == 2) decode(split[0])?.lowercase(Locale.ROOT)?.let { it to decode(split[1]).orEmpty().lowercase(Locale.ROOT) } else null
            }
            if (pairs.count { it.first == "method" } > 1) return false
            val parameters = pairs.toMap()
            // The school's public jkingo.noprint.js uses this read-only generated-file endpoint.
            return Regex("\\.(?:xls|xlsx|csv)(?:$|;)").containsMatchIn(path) ||
                (path == "/frame/excel" && parameters["method"] == "download")
        }

        private fun secureCampusUrl(value: String): URL {
            val uri = runCatching { URI(value) }.getOrNull()
            val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
            val campus = listOf("bnu.edu.cn", "bnuzh.edu.cn").any { host == it || host.endsWith(".$it") }
            if (uri == null || uri.scheme?.lowercase(Locale.ROOT) != "https" || !campus || uri.userInfo != null ||
                (uri.port != -1 && uri.port != 443)) throw CampusDownloadException(CampusDownloadFailure.UNSUPPORTED_LINK)
            return uri.toURL()
        }

        private fun safeHeader(value: String?): String? = value?.takeIf { it.isNotBlank() && it.length <= 16_384 && '\r' !in it && '\n' !in it }

        private fun referer(page: URL, target: URL): String = if (page.host.equals(target.host, true) && page.port == target.port) {
            page.toExternalForm().substringBefore('#')
        } else "https://${page.authority}/"

        fun safeFilename(disposition: String?, path: String, mimeType: String?, fallbackDisposition: String? = null): String =
            FilenameDecoder.filename(disposition, path, mimeType, fallbackDisposition)

        private fun decode(value: String): String? = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
}
