package cn.crid.next.data

import org.junit.Assert.*
import org.junit.Test

class CampusPortalsTest {
    @Test fun officialEntriesUseHttps() {
        assertEquals(CampusPortals.BEIJING, CampusPortals.secureNavigationUrl(CampusPortals.BEIJING))
        assertEquals(CampusPortals.ZHUHAI, CampusPortals.secureNavigationUrl(CampusPortals.ZHUHAI))
    }
    @Test fun legacyCasRedirectUpgradesWithoutChangingEncodedService() {
        val suffix = "/cas/login?service=https%3A%2F%2Fss.bnu.edu.cn%2Fwww%3FredirectUrl%3D%252F#form"
        assertEquals("https://cas.bnu.edu.cn$suffix", CampusPortals.secureNavigationUrl("http://cas.bnu.edu.cn:80$suffix"))
        assertFalse(CampusDownload.isCampusUrl("http://cas.bnu.edu.cn$suffix"))
    }
    @Test fun navigationCannotUpgradeOutsideSchoolOrCredentialBearingUrls() {
        listOf("http://bnu.edu.cn.evil.example/file", "http://evilbnu.edu.cn/", "http://user@cas.bnu.edu.cn/",
            "http://cas.bnu.edu.cn:8080/", "file:///tmp/course.xls", "javascript:alert(1)").forEach {
            assertNull(CampusPortals.secureNavigationUrl(it))
        }
    }
}
