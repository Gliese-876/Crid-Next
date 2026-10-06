package cn.crid.next.data

import android.content.Context
import android.app.Application
import android.net.Network
import android.util.AtomicFile
import cn.crid.next.core.*
import cn.crid.next.platform.HolidayDefaults
import cn.crid.next.platform.HolidaySync
import cn.crid.next.platform.PlatformCoordinator
import cn.crid.next.platform.ReminderScheduler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

class AppRepository private constructor(private val context: Application) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val mutex = Mutex()
    private val dataFile = AtomicFile(File(context.filesDir, "timetables-v1.json"))
    private val holidayFile = AtomicFile(File(context.filesDir, "holidays-v1.json"))
    init { removeRetiredAssistantData(context) }
    private var loadFailure = false
    @Volatile internal var reminderRevision: Long = 0
        private set
    @Volatile internal var reminderCommitPending: Boolean = false
        private set
    private val transactions = AppStateTransactions(loadState(), mutex, onPublished = {
        synchronized(ReminderScheduler) { reminderCommitPending = false; reminderRevision++ }
    }) { next ->
        synchronized(ReminderScheduler) {
            // Invalidate before writing: an immediate reboot must not restore an edited/deleted class.
            ReminderScheduler.invalidateSnapshot(context)
            reminderCommitPending = true
            reminderRevision++
            try { write(dataFile, json.encodeToString(next)) }
            catch (error: Exception) { reminderCommitPending = false; reminderRevision++; throw error }
        }
    }
    val state: StateFlow<AppState> = transactions.state
    private val mutableHolidays = MutableStateFlow(runCatching {
        json.decodeFromString<HolidayCalendar>(holidayFile.readFully().toString(Charsets.UTF_8))
    }.getOrElse { HolidayDefaults.calendar() })
    val holidays: StateFlow<HolidayCalendar> = mutableHolidays.asStateFlow()
    val storageNeedsRecovery get() = loadFailure

    private fun loadState(): AppState {
        if (!dataFile.baseFile.exists() && !File(dataFile.baseFile.path + ".bak").exists()) return AppState()
        return try { json.decodeFromString<AppState>(dataFile.readFully().toString(Charsets.UTF_8)) }
        catch (_: Exception) { loadFailure = true; AppState() }
    }
    suspend fun update(transform: (AppState) -> AppState) = withContext(Dispatchers.IO) {
        val changed = transactions.update {
            check(!loadFailure) { "Saved data could not be read. The original file has been preserved." }
            transform(it)
        }
        // System scheduling must not turn a successful database commit into a failed import.
        if (changed) runCatching { PlatformCoordinator.refresh(context) }
    }
    suspend fun saveCourse(target: CourseEditTarget, draft: Course) {
        update { current ->
            requireCourseTargetSelected(current, target)
            CourseEditing.save(current, target, draft)
        }
    }
    suspend fun deleteCourse(target: CourseEditTarget) {
        update { current ->
            requireCourseTargetSelected(current, target)
            CourseEditing.delete(current, target)
        }
    }
    suspend fun deleteLesson(target: CourseEditTarget, courseId: String, lessonIndex: Int) {
        update { current ->
            requireCourseTargetSelected(current, target)
            CourseEditing.deleteLesson(current, target, courseId, lessonIndex)
        }
    }
    suspend fun refreshHolidays(network: Network? = null) = withContext(Dispatchers.IO) {
        val refreshed = HolidaySync.fetch(mutableHolidays.value, network) ?: return@withContext
        mutex.withLock {
            coroutineContext.ensureActive()
            synchronized(ReminderScheduler) {
                ReminderScheduler.invalidateSnapshot(context)
                reminderRevision++
                try {
                    write(holidayFile, json.encodeToString(refreshed))
                    mutableHolidays.value = refreshed
                } finally { reminderRevision++ }
            }
        }
        runCatching { PlatformCoordinator.refresh(context) }
    }
    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    companion object {
        @Volatile private var instance: AppRepository? = null
        fun get(context: Context): AppRepository = instance ?: synchronized(this) {
            instance ?: AppRepository(context.applicationContext as Application).also { instance = it }
        }
    }
}

internal fun requireCourseTargetSelected(state: AppState, target: CourseEditTarget) {
    if (state.selectedPlanId != target.planId || state.selectedSemesterId != target.semesterId)
        throw CourseEditConflictException("当前方案已切换，请切回原方案继续编辑")
}
