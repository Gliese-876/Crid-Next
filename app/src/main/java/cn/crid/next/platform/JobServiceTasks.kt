package cn.crid.next.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Main-thread bridge from JobService callbacks to cancellable work; scheduling stays with Android. */
internal class JobServiceTasks<P, N>(
    private val scope: CoroutineScope,
    private val work: suspend (id: Int, network: N?) -> Unit,
    private val finished: (parameters: P, retry: Boolean) -> Unit,
) {
    private class Run<P, N>(val parameters: P, val network: N?, val job: Job, val retiring: List<Job>)
    private val running = mutableMapOf<Int, Run<P, N>>()

    fun start(id: Int, parameters: P, network: N?) {
        val previous = running.remove(id)
        previous?.job?.cancel()
        // Keep ancestors even when several network callbacks arrive before a replacement starts.
        val retiring = if (previous == null) emptyList() else
            (previous.retiring + previous.job).filterNot { it.isCompleted }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                // A cancelled blocking read must release its connection before its replacement starts.
                retiring.forEach { it.join() }
                work(id, network)
            } catch (cancelled: CancellationException) {
                retry = true
                throw cancelled
            } catch (_: Exception) {
                retry = true
            } finally {
                // A stopped or replaced execution must never finish a later run with the same ID.
                if (running[id]?.job === coroutineContext[Job]) {
                    running.remove(id)
                    finished(parameters, retry)
                }
            }
        }
        running[id] = Run(parameters, network, job, retiring)
        job.start()
    }

    fun networkChanged(id: Int, network: N?) {
        val active = running[id] ?: return
        if (active.network != network) start(id, active.parameters, network)
    }

    fun stop(id: Int) {
        running.remove(id)?.job?.cancel()
    }

    fun close() {
        running.clear()
        scope.cancel()
    }
}
