package cn.crid.next.data

import cn.crid.next.core.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class AppStateTransactionsTest {
    private val semester = Semester("semester", "秋季", "2026-09-07", "2026-10-18", 6,
        listOf(Period(1, "08:00", "08:45")))
    private val lesson = Lesson(weeks = listOf(1, 2), startPeriod = 1, endPeriod = 1)
    private val math = Course("math", "数学", color = -12345, lessons = listOf(lesson))
    private val physics = Course("physics", "物理", color = -23456, lessons = listOf(lesson))
    private val plan = Plan("plan", semester.id, "我的课表", listOf(math, physics))
    private val initial = AppState(listOf(semester), listOf(plan), semester.id, plan.id)

    @Test fun unchangedStateDoesNotWriteOrPublishButSubsequentEditsStillCommit() = runBlocking {
        var writes = 0
        var publications = 0
        val store = AppStateTransactions(initial, Mutex(), onPublished = { publications++ }) { writes++ }
        assertFalse(store.update { it.copy(plans = it.plans.toList()) })
        assertEquals(0, writes)
        assertEquals(0, publications)
        assertTrue(store.update { it.copy(settings = it.settings.copy(language = Language.EN)) })
        assertEquals(1, writes)
        assertEquals(1, publications)
        assertEquals(Language.EN, store.state.value.settings.language)
    }

    @Test fun unchangedInvalidStateStillFailsValidation() = runBlocking {
        val invalid = initial.copy(plans = listOf(plan.copy(semesterId = "missing")))
        val store = AppStateTransactions(invalid, Mutex()) { fail("Invalid state must not be written") }
        assertTrue(runCatching { store.update { it } }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun concurrentCourseEditsReadLiveStateInsideTheLock() = runBlocking {
        val writes = mutableListOf<AppState>()
        val store = AppStateTransactions(initial, Mutex()) { writes += it }
        val mathTarget = CourseEditing.editTarget(initial, plan.id, math.id)
        val physicsTarget = CourseEditing.editTarget(initial, plan.id, physics.id)
        val start = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            start.await()
            store.update { CourseEditing.save(it, mathTarget, math.copy(credits = "二学分")) }
        }
        val second = async(Dispatchers.Default) {
            start.await()
            store.update { CourseEditing.save(it, physicsTarget, physics.copy(lessons = listOf(lesson.copy(teacher = "李老师")))) }
        }
        start.complete(Unit)
        awaitAll(first, second)
        assertEquals(2, writes.size)
        assertEquals("二学分", store.state.value.plan!!.courses.first { it.id == math.id }.credits)
        assertEquals("李老师", store.state.value.plan!!.courses.first { it.id == physics.id }.lessons.single().teacher)
        assertEquals(writes.last(), store.state.value)
    }

    @Test fun concurrentEditsOfTheSameSnapshotCommitOnlyOnce() = runBlocking {
        var writes = 0
        val store = AppStateTransactions(initial, Mutex()) { writes++ }
        val target = CourseEditing.editTarget(initial, plan.id, math.id)
        val start = CompletableDeferred<Unit>()
        val results = listOf("王老师", "李老师").map { teacher ->
            async(Dispatchers.Default) {
                start.await()
                runCatching { store.update { CourseEditing.save(it, target, math.copy(lessons = listOf(lesson.copy(teacher = teacher)))) } }
            }
        }
        start.complete(Unit)
        val completed = results.awaitAll()
        assertEquals(1, writes)
        assertEquals(1, completed.count { it.isSuccess })
        assertTrue(completed.single { it.isFailure }.exceptionOrNull() is CourseEditConflictException)
        assertEquals(physics, store.state.value.plan!!.courses.last())
    }

    @Test fun cancellingBeforeTheCommitLockDoesNotPersistOrPublish() = runBlocking {
        val mutex = Mutex(locked = true)
        var writes = 0
        val store = AppStateTransactions(initial, mutex) { writes++ }
        val entered = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) {
            entered.complete(Unit)
            store.update { CourseEditing.delete(it, CourseEditing.editTarget(initial, plan.id, math.id)) }
        }
        entered.await()
        pending.cancel()
        pending.join()
        mutex.unlock()
        assertEquals(0, writes)
        assertEquals(initial, store.state.value)
    }

    @Test fun diskFailureDoesNotPublishTheDraft() = runBlocking {
        var published = false
        val store = AppStateTransactions(initial, Mutex(), onPublished = { published = true }) { throw IOException("disk full") }
        val target = CourseEditing.editTarget(initial, plan.id, math.id)
        val failure = runCatching { store.update { CourseEditing.save(it, target, math.copy(name = "高等数学")) } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(initial, store.state.value)
        assertFalse(published)
    }

    @Test fun observersSeeTheNewCourseOnlyAfterPersistence() = runBlocking {
        lateinit var store: AppStateTransactions
        var persisted: AppState? = null
        var published: AppState? = null
        store = AppStateTransactions(initial, Mutex(), onPublished = { published = store.state.value }) { next ->
            assertEquals(initial, store.state.value)
            persisted = next
        }
        val target = CourseEditing.editTarget(initial, plan.id, math.id)
        store.update { CourseEditing.deleteLesson(it, target, math.id, 0) }
        assertEquals(persisted, store.state.value)
        assertEquals(persisted, published)
        assertEquals(listOf(physics), store.state.value.plan!!.courses)
    }

    @Test fun invalidOtherPlanBlocksTheWholeCommit() = runBlocking {
        var writes = 0
        val store = AppStateTransactions(initial, Mutex()) { writes++ }
        val invalidCourse = physics.copy(lessons = listOf(lesson.copy(weeks = listOf(100))))
        val failure = runCatching {
            store.update { it.copy(plans = it.plans + Plan("invalid", semester.id, "损坏方案", listOf(invalidCourse))) }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, writes)
        assertEquals(initial, store.state.value)
    }

    @Test fun aPlanSwitchWhileWaitingPreventsTheCourseCommit() = runBlocking {
        var writes = 0
        val other = plan.copy(id = "other", name = "其他方案")
        val switched = initial.copy(plans = initial.plans + other, selectedPlanId = other.id)
        val store = AppStateTransactions(switched, Mutex()) { writes++ }
        val target = CourseEditing.editTarget(initial, plan.id, math.id)
        val failure = runCatching {
            store.update { current ->
                requireCourseTargetSelected(current, target)
                CourseEditing.delete(current, target)
            }
        }.exceptionOrNull()
        assertTrue(failure is CourseEditConflictException)
        assertEquals(0, writes)
        assertEquals(switched, store.state.value)
    }
}
