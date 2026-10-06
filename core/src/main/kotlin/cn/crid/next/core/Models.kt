package cn.crid.next.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun courseKey(name: String): String = courseIdentitySpacing(Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)).lowercase(Locale.ROOT)

@Serializable data class Period(val number: Int, val start: String, val end: String)
@Serializable data class Semester(val id: String = newId(), val name: String, val startDate: String, val endDate: String, val weeks: Int, val periods: List<Period>)
@Serializable data class Lesson(
    val weeks: List<Int> = emptyList(), val weekday: Int = 1,
    val startPeriod: Int? = null, val endPeriod: Int? = null,
    val date: String? = null, val startTime: String? = null, val endTime: String? = null,
    val location: String = "", val teacher: String = "", val note: String = "",
    /** The source explicitly lists teaching weeks without assigning a weekday or time. */
    val unscheduled: Boolean = false
)
@Serializable data class Course(val id: String = newId(), val name: String, val color: Int = 0, val credits: String = "", val extra: Map<String,String> = emptyMap(), val lessons: List<Lesson>)
@Serializable data class Plan(val id: String = newId(), val semesterId: String, val name: String, val courses: List<Course>)
@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class Language { SYSTEM, ZH_CN, ZH_TW, EN }
@Serializable enum class MakeupMode { OFF, HOLIDAYS_ONLY, ON }
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable data class Settings(val theme: ThemeMode = ThemeMode.SYSTEM, val language: Language = Language.SYSTEM, val weekStartsSunday: Boolean = false, val holidaysEnabled: Boolean = true, val makeupMode: MakeupMode = MakeupMode.HOLIDAYS_ONLY, val showOutOfWeek: Boolean = true, val remindersEnabled: Boolean = false, val reminderMinutes: Int = 15, val reminderAlarmClock: Boolean = false)
@Serializable data class AppState(val semesters: List<Semester> = emptyList(), val plans: List<Plan> = emptyList(), val selectedSemesterId: String? = null, val selectedPlanId: String? = null, val settings: Settings = Settings()) {
    val semester: Semester? get() = semesters.find { it.id == selectedSemesterId }
    val plan: Plan? get() = plans.find { it.id == selectedPlanId && it.semesterId == selectedSemesterId }
}
@Serializable data class PlanPackage(val format: String = "crid-next", val version: Int = 1, val name: String, val courses: List<Course>)
enum class OccurrenceStatus { OUT_OF_WEEK, HOLIDAY, MAKEUP }
data class Occurrence(val course: Course, val lesson: Lesson, val date: LocalDate, val start: LocalTime, val end: LocalTime, val week: Int, val statuses: Set<OccurrenceStatus> = emptySet(), val holidayName: String? = null) {
    val durationMinutes: Long get() = java.time.Duration.between(start, end).toMinutes()
    val isActual: Boolean get() = OccurrenceStatus.OUT_OF_WEEK !in statuses && OccurrenceStatus.HOLIDAY !in statuses
}
@Serializable data class HolidayDay(val date: String, val name: String, val statutory: Boolean = false, val extraRest: Boolean = false, val workday: Boolean = false, val teachingDate: String? = null)
@Serializable data class HolidayCalendar(val year: Int = 2026, val days: List<HolidayDay> = emptyList(), val syncedAt: String? = null, val source: String = "")
enum class ImportMode { NEW, MERGE, REPLACE }
data class ParseResult(val name: String, val courses: List<Course>, val warnings: List<String> = emptyList(), val errors: List<String> = emptyList(), val sourceSemester: String? = null, val unresolved: List<String> = emptyList()) { val valid get() = errors.isEmpty() && unresolved.isEmpty() && courses.isNotEmpty() }
data class ImportPreview(val courses: List<Course>, val warnings: List<String>, val errors: List<String>, val duplicates: Int, val conflicts: Int) { val valid get() = errors.isEmpty() }
