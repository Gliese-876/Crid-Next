package cn.crid.next.data

import cn.crid.next.core.AppState
import cn.crid.next.core.DataValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Publishes a state only after the complete validated transaction has been persisted. */
internal class AppStateTransactions(
    initial: AppState,
    private val mutex: Mutex,
    private val onPublished: () -> Unit = {},
    private val persist: (AppState) -> Unit,
) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    suspend fun update(transform: (AppState) -> AppState): Boolean = mutex.withLock {
        val next = transform(mutableState.value)
        val semesters = next.semesters.associateBy { it.id }
        require(semesters.size == next.semesters.size)
        require(next.plans.mapTo(HashSet()) { it.id }.size == next.plans.size)
        require(next.plans.all { it.semesterId in semesters })
        next.semesters.forEach { require(DataValidator.validateSemester(it).isEmpty()) }
        next.plans.forEach { plan ->
            if (plan.courses.isNotEmpty()) {
                require(DataValidator.validateCourses(plan.courses, semesters.getValue(plan.semesterId)).isEmpty())
            }
        }
        // Validate even unchanged input, but do not rewrite storage or reschedule reminders for it.
        if (next == mutableState.value) return@withLock false
        persist(next)
        mutableState.value = next
        onPublished()
        true
    }
}
