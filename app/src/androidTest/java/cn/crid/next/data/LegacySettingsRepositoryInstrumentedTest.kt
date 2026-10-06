package cn.crid.next.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySettingsRepositoryInstrumentedTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val semester = Semester("legacy-term", "Autumn", "2026-09-07", "2027-01-10", 18,
        listOf(Period(1, "08:00", "08:45")))
    private val course = Course("legacy-course", "Math", credits = "3", lessons = listOf(
        Lesson(weeks = listOf(1, 2, 3), weekday = 2, startPeriod = 1, endPeriod = 1,
            teacher = "Ada", location = "Lab A203")))
    private val expected = AppState(listOf(semester), listOf(Plan("legacy-plan", semester.id, "Main", listOf(course))),
        semester.id, "legacy-plan", Settings(theme = ThemeMode.DARK, language = Language.EN,
            holidaysEnabled = false, showOutOfWeek = false, remindersEnabled = true, reminderMinutes = 25))

    @Test fun legacyEnabledFlagLoadsWithoutRecoveryAndDisappearsAfterASave() = withIsolatedStorage { application ->
        val encoded = json.parseToJsonElement(json.encodeToString(expected)).jsonObject
        val oldSettings = JsonObject(encoded.getValue("settings").jsonObject + ("hdrControls" to JsonPrimitive(true)))
        val legacy = JsonObject(encoded + ("settings" to oldSettings)).toString()
        val file = File(application.filesDir, "timetables-v1.json").apply { writeText(legacy) }
        val repository = openRepository(application)
        assertFalse(repository.storageNeedsRecovery)
        assertEquals(expected, repository.state.value)
        assertEquals("Loading preserves the original file until the next save", legacy, file.readText())

        val updated = expected.copy(settings = expected.settings.copy(language = Language.ZH_TW))
        runBlocking { repository.update { updated } }
        assertFalse(file.readText().contains("hdrControls"))
        val reopened = openRepository(application)
        assertFalse(reopened.storageNeedsRecovery)
        assertEquals(updated, reopened.state.value)
    }

    @Test fun unrelatedUnknownSavedFieldStillRequestsRecoveryAndPreservesOriginalFile() = withIsolatedStorage { application ->
        val encoded = json.parseToJsonElement(json.encodeToString(expected)).jsonObject
        val malformed = JsonObject(encoded + ("unrecognizedTimetableField" to JsonPrimitive(true))).toString()
        val file = File(application.filesDir, "timetables-v1.json").apply { writeText(malformed) }
        val repository = openRepository(application)
        assertTrue(repository.storageNeedsRecovery)
        assertEquals(malformed, file.readText())
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.update { expected } } }
        assertEquals(malformed, file.readText())
    }

    @Test fun retiredAssistantFilesAndKeyAreRemovedWithoutChangingTimetables() = withIsolatedStorage { application ->
        // Never open old credentials: only synthetic bytes are placed in isolated fixture storage.
        removeRetiredAssistantData(application)
        val alias = "crid-next-ai"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val retiredFiles = listOf("assistant.secure", "assistant.secure.bak", "assistant.secure.new")
            .map { File(application.noBackupFilesDir, it).apply { writeText("synthetic-retired-storage") } }
        val saved = json.encodeToString(expected)
        val timetable = File(application.filesDir, "timetables-v1.json").apply { writeText(saved) }
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
            assertTrue(store.containsAlias(alias))
            val repository = openRepository(application)
            retiredFiles.forEach { assertFalse("Retired storage must be removed: ${it.name}", it.exists()) }
            assertFalse(store.containsAlias(alias))
            assertFalse(repository.storageNeedsRecovery)
            assertEquals(expected, repository.state.value)
            assertEquals(saved, timetable.readText())
            assertEquals("Repeated cleanup must remain safe", expected, openRepository(application).state.value)
        } finally {
            removeRetiredAssistantData(application)
        }
    }

    private fun openRepository(application: Application): AppRepository =
        AppRepository::class.java.getDeclaredConstructor(Application::class.java).apply { isAccessible = true }
            .newInstance(application)

    private fun withIsolatedStorage(block: (Application) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageId = "legacy-settings-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, storageId).apply { mkdirs() }
        val deviceContext = context.createDeviceProtectedStorageContext()
        val isolatedPreferenceNames = mutableSetOf<String>()
        val isolatedDeviceContext = object : ContextWrapper(deviceContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$storageId-$name"
                isolatedPreferenceNames += isolatedName
                return deviceContext.getSharedPreferences(isolatedName, mode)
            }
        }
        val application = object : Application() {
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
            override fun createDeviceProtectedStorageContext(): Context = isolatedDeviceContext
            // Repository saves catch platform-refresh failures; this fixture must not schedule
            // reminders or replace the running app's singleton repository.
            override fun getApplicationContext(): Context = error("Isolated repository has no platform services")
        }
        try { block(application) } finally {
            isolatedPreferenceNames.forEach(deviceContext::deleteSharedPreferences)
            directory.deleteRecursively()
        }
    }
}
