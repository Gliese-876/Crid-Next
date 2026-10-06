package cn.crid.next.data

import java.net.URI

/** Official HTTPS entry points; authentication remains inside the school's own pages. */
internal object CampusPortals {
    const val BEIJING = "https://ss.bnu.edu.cn/"
    const val ZHUHAI = "https://jwxt.bnuzh.edu.cn/caslogin"

    /** Upgrade legacy school navigation links without ever loading credentials over HTTP. */
    fun secureNavigationUrl(value: String): String? {
        if (CampusDownload.isCampusUrl(value)) return value
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", true) || uri.userInfo != null || uri.port !in listOf(-1, 80) || uri.host == null) return null
        val upgraded = buildString {
            append("https://"); append(uri.host)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?'); append(it) }
            uri.rawFragment?.let { append('#'); append(it) }
        }
        return upgraded.takeIf { CampusDownload.isCampusUrl(it) }
    }
}
