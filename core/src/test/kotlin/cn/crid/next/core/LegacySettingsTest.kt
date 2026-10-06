package cn.crid.next.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LegacySettingsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val semester = Semester("term", "Autumn", "2026-09-07", "2027-01-10", 18,
        listOf(Period(1, "08:00", "08:45")))
    private val course = Course("course", "Math", credits = "3", lessons = listOf(
        Lesson(weeks = listOf(1, 2, 3), weekday = 2, startPeriod = 1, endPeriod = 1,
            teacher = "Ada", location = "Lab A203")))
    private val expected = AppState(listOf(semester), listOf(Plan("plan", semester.id, "Main", listOf(course))),
        semester.id, "plan", Settings(theme = ThemeMode.DARK, language = Language.EN,
            weekStartsSunday = true, holidaysEnabled = false, makeupMode = MakeupMode.OFF,
            showOutOfWeek = false, remindersEnabled = true, reminderMinutes = 25))

    @Test fun obsoleteSavedSettingIsIgnoredWithoutChangingTimetableOrOtherPreferences() {
        val encoded = json.parseToJsonElement(json.encodeToString(expected)).jsonObject
        for (enabled in listOf(false, true)) {
            val legacySettings = JsonObject(encoded.getValue("settings").jsonObject +
                ("hdrControls" to JsonPrimitive(enabled)))
            val legacy = JsonObject(encoded + ("settings" to legacySettings)).toString()
            val restored = json.decodeFromString<AppState>(legacy)
            assertEquals(expected, restored)
            val saved = json.encodeToString(restored)
            assertFalse(saved.contains("hdrControls"))
            assertEquals(expected, json.decodeFromString<AppState>(saved))
        }
    }

    @Test fun settingsSavedBeforeTheRemovedOptionStillLoad() {
        val restored = Json.decodeFromString<Settings>("""{"theme":"DARK","language":"EN"}""")
        assertEquals(Settings(theme = ThemeMode.DARK, language = Language.EN), restored)
    }

    @Test fun unknownTimetableAndCourseFieldsStillFailStrictDecoding() {
        val stored = json.parseToJsonElement(json.encodeToString(expected)).jsonObject
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<AppState>(JsonObject(stored + ("unknownTimetableField" to JsonPrimitive(true))).toString())
        }
        val storedCourse = json.parseToJsonElement(json.encodeToString(course)).jsonObject
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<Course>(JsonObject(storedCourse + ("unknownCourseField" to JsonPrimitive(true))).toString())
        }
    }
}
