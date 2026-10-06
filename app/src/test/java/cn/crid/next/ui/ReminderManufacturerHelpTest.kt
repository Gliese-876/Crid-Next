package cn.crid.next.ui

import cn.crid.next.core.Language
import java.net.URI
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderManufacturerHelpTest {
    @Test fun manufacturerAliasesAndCaseUseTheSameInstructions() {
        val text = UiText(Language.ZH_CN)
        listOf("Xiaomi" to " REDMI ", "HUAWEI" to "huawei", "HONOR" to "Honor", "vivo" to "iQOO")
            .forEach { (manufacturer, alias) ->
                val guide = reminderManufacturerGuide(manufacturer, text)
                assertTrue("Known manufacturer $manufacturer", guide != null)
                assertEquals(guide, reminderManufacturerGuide(alias, text))
            }
        assertNotEquals(reminderManufacturer("HUAWEI"), reminderManufacturer("HONOR"))
    }

    @Test fun unrelatedManufacturersAndBrandSubstringsDoNotReceiveUnverifiedInstructions() {
        listOf("", "Google", "Samsung", "OPPO", "OnePlus", "realme", "unknown", "not-vivo",
            "Xiaomi emulator", "Huawei Technologies test", "honorable").forEach { manufacturer ->
            assertNull(manufacturer, reminderManufacturerGuide(manufacturer, UiText(Language.EN)))
        }
    }

    @Test fun brandMatchingDoesNotDependOnTheSystemLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(ReminderManufacturer.XIAOMI, reminderManufacturer("XIAOMI"))
            assertEquals(ReminderManufacturer.VIVO, reminderManufacturer("VIVO"))
            assertEquals(ReminderManufacturer.VIVO, reminderManufacturer("IQOO"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun helpLinksUseVerifiedOfficialHttpsHostsWithoutEmbeddedCredentials() {
        val officialHosts = mapOf("Xiaomi" to "www.mi.com", "Huawei" to "consumer.huawei.com",
            "Honor" to "www.honor.com", "vivo" to "kefu.vivo.com.cn")
        officialHosts.forEach { (manufacturer, officialHost) ->
            val url = URI(requireNotNull(reminderManufacturerGuide(manufacturer, UiText(Language.EN))).officialHelpUrl)
            assertEquals(manufacturer, "https", url.scheme)
            assertEquals(manufacturer, officialHost, url.host)
            assertEquals(manufacturer, -1, url.port)
            assertNull(manufacturer, url.userInfo)
            assertNull(manufacturer, url.query)
            assertTrue(manufacturer, url.path.length > 1)
        }
    }

    @Test fun allSupportedLanguagesHaveActionableCopyAndExplicitExternalNavigation() {
        listOf("Xiaomi", "Huawei", "Honor", "vivo").forEach { manufacturer ->
            val guides = listOf(Language.ZH_CN, Language.ZH_TW, Language.EN).map { language ->
                requireNotNull(reminderManufacturerGuide(manufacturer, UiText(language)))
            }
            assertEquals("Instructions are localized for $manufacturer", 3, guides.map { it.steps }.distinct().size)
            assertEquals("Language does not redirect help for $manufacturer", 1, guides.map { it.officialHelpUrl }.distinct().size)
            guides.forEach { guide ->
                assertTrue(guide.steps.any { "Crid Next" in it })
                assertTrue(guide.openLabel.contains("浏览器") || guide.openLabel.contains("瀏覽器") || guide.openLabel.contains("browser"))
                (guide.steps + guide.title + guide.openLabel + guide.openFailedLabel).forEach { copy ->
                    assertTrue(copy.isNotBlank())
                    assertFalse(copy, copy.endsWith('.') || copy.endsWith('。'))
                }
            }
        }
    }
}
