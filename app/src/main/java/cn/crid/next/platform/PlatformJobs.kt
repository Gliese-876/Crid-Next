package cn.crid.next.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import cn.crid.next.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The system owns persistence, constraints and retries for these two bounded periodic tasks. */
internal object PlatformJobs {
    const val MAINTENANCE = 0x43524944
    const val HOLIDAY_SYNC = 0x43524945

    @Synchronized
    fun ensureScheduled(context: Context): Int {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val service = ComponentName(context, PlatformJobService::class.java)
        var scheduled = 0
        for ((id, interval) in listOf(MAINTENANCE to TimeUnit.HOURS.toMillis(6),
            HOLIDAY_SYNC to TimeUnit.DAYS.toMillis(1))) {
            val request = JobInfo.Builder(id, service)
                .setPeriodic(interval)
                .setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .apply { if (id == HOLIDAY_SYNC) setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) }
                .build()
            // Replacing an identical job resets its period and can stop an in-flight execution.
            if (scheduler.getPendingJob(id) != request) {
                check(scheduler.schedule(request) == JobScheduler.RESULT_SUCCESS)
                scheduled++
            }
        }
        return scheduled
    }

    /** Package updates can leave jobs whose service was removed; keep every valid service intact. */
    @Synchronized
    fun removeUnavailableServices(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val namespaces: Map<String?, List<JobInfo>> = if (Build.VERSION.SDK_INT >= 34)
            scheduler.pendingJobsInAllNamespaces.orEmpty() else mapOf(null to scheduler.allPendingJobs.orEmpty())
        namespaces.forEach { (namespace, jobs) ->
            val owner = if (Build.VERSION.SDK_INT >= 34 && namespace != null) scheduler.forNamespace(namespace) else scheduler
            jobs.forEach { job ->
                try {
                    context.packageManager.getServiceInfo(job.service, PackageManager.MATCH_DISABLED_COMPONENTS)
                } catch (_: PackageManager.NameNotFoundException) {
                    owner.cancel(job.id)
                }
            }
        }
    }
}

class PlatformJobService : JobService() {
    // Lifecycle callbacks and completion run on main; only the bounded work moves to IO.
    private val tasks = JobServiceTasks<JobParameters, Network>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        work = { id, network ->
            withContext(Dispatchers.IO) {
                when (id) {
                    PlatformJobs.MAINTENANCE -> PlatformCoordinator.refreshNow(applicationContext)
                    PlatformJobs.HOLIDAY_SYNC -> AppRepository.get(applicationContext)
                        .refreshHolidays(requireNotNull(network))
                }
            }
        },
        finished = { params, retry -> jobFinished(params, retry) },
    )

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId != PlatformJobs.MAINTENANCE && params.jobId != PlatformJobs.HOLIDAY_SYNC) return false
        tasks.start(params.jobId, params, params.network)
        return true
    }

    override fun onNetworkChanged(params: JobParameters) {
        if (params.jobId == PlatformJobs.HOLIDAY_SYNC) tasks.networkChanged(params.jobId, params.network)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        tasks.stop(params.jobId)
        return true
    }

    override fun onDestroy() {
        tasks.close()
        super.onDestroy()
    }
}
