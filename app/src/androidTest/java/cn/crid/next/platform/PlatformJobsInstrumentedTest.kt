package cn.crid.next.platform

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exercises the real scheduler, including parcelled JobInfo equality used to preserve its timing. */
class PlatformJobsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val service get() = ComponentName(context, PlatformJobService::class.java)
    private val ownedIds = listOf(PlatformJobs.MAINTENANCE, PlatformJobs.HOLIDAY_SYNC)
    private var previous = emptyList<JobInfo>()
    private val unrelatedId = 0x43524946

    @Before fun isolateJobs() {
        previous = (ownedIds + unrelatedId).mapNotNull { scheduler.getPendingJob(it) }
        ownedIds.forEach(scheduler::cancel)
    }

    @After fun restoreJobs() {
        (ownedIds + unrelatedId).forEach(scheduler::cancel)
        previous.forEach { scheduler.schedule(it) }
    }

    @Test fun periodicTasksRetainIntervalsPersistenceNetworkAndRetryPolicy() = isolatedRegistration {
        assertEquals(2, PlatformJobs.ensureScheduled(context))
        val maintenance = requireNotNull(scheduler.getPendingJob(PlatformJobs.MAINTENANCE))
        val holiday = requireNotNull(scheduler.getPendingJob(PlatformJobs.HOLIDAY_SYNC))
        assertEquals(TimeUnit.HOURS.toMillis(6), maintenance.intervalMillis)
        assertEquals(TimeUnit.DAYS.toMillis(1), holiday.intervalMillis)
        assertNull(maintenance.requiredNetwork)
        assertTrue(requireNotNull(holiday.requiredNetwork).hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        listOf(maintenance, holiday).forEach {
            assertEquals(service, it.service)
            assertTrue(it.isPeriodic)
            assertTrue(it.isPersisted)
            assertEquals(it.intervalMillis, it.flexMillis)
            assertEquals(30_000L, it.initialBackoffMillis)
            assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, it.backoffPolicy)
            assertFalse(it.isRequireCharging)
            assertFalse(it.isRequireDeviceIdle)
        }
    }

    @Test fun repeatedRegistrationKeepsJobsAndRestoresOnlyTheMissingTask() = isolatedRegistration {
        assertEquals(2, PlatformJobs.ensureScheduled(context))
        assertEquals("Existing jobs must not be rescheduled on each app or widget launch",
            0, PlatformJobs.ensureScheduled(context))
        val maintenance = scheduler.getPendingJob(PlatformJobs.MAINTENANCE)
        scheduler.cancel(PlatformJobs.HOLIDAY_SYNC)
        assertEquals(1, PlatformJobs.ensureScheduled(context))
        assertEquals(maintenance, scheduler.getPendingJob(PlatformJobs.MAINTENANCE))
        assertNotNull(scheduler.getPendingJob(PlatformJobs.HOLIDAY_SYNC))
    }

    @Test fun outdatedConfigurationIsRepairedWithoutCancellingUnrelatedJobs() = isolatedRegistration {
        val outdated = JobInfo.Builder(PlatformJobs.MAINTENANCE, service)
            .setPeriodic(TimeUnit.HOURS.toMillis(12)).build()
        val unrelated = JobInfo.Builder(unrelatedId, service)
            .setMinimumLatency(TimeUnit.DAYS.toMillis(1)).build()
        assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(outdated))
        assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(unrelated))
        assertEquals(2, PlatformJobs.ensureScheduled(context))
        assertEquals(TimeUnit.HOURS.toMillis(6),
            requireNotNull(scheduler.getPendingJob(PlatformJobs.MAINTENANCE)).intervalMillis)
        assertEquals(unrelated, scheduler.getPendingJob(unrelatedId))
    }

    @Test fun regularRefreshRepairsDiscardedJobsWithoutReschedulingTheOtherTask() = runBlocking {
        PlatformJobs.ensureScheduled(context)
        val maintenance = scheduler.getPendingJob(PlatformJobs.MAINTENANCE)
        scheduler.cancel(PlatformJobs.HOLIDAY_SYNC)
        PlatformCoordinator.refreshNow(context)
        assertEquals(maintenance, scheduler.getPendingJob(PlatformJobs.MAINTENANCE))
        assertNotNull(scheduler.getPendingJob(PlatformJobs.HOLIDAY_SYNC))
        assertEquals(0, PlatformJobs.ensureScheduled(context))
    }

    @Test fun serviceIsSystemProtectedAndRequiredPermissionsSurviveDependencyRemoval() {
        val info = context.packageManager.getServiceInfo(service, 0)
        assertEquals(JobService.PERMISSION_BIND, info.permission)
        assertFalse(info.exported)
        assertFalse(info.directBootAware)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED))
    }

    private fun isolatedRegistration(block: () -> Unit) = synchronized(PlatformJobs) {
        // A real maintenance run may repair jobs concurrently; keep deliberate fixture damage
        // and the registration-count assertions atomic with respect to that same repair path.
        ownedIds.forEach(scheduler::cancel)
        block()
    }
}
