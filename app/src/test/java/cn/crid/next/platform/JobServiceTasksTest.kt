package cn.crid.next.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class JobServiceTasksTest {
    @Test fun successfulAndFailedTasksReportTheirOwnCompletionAndRetry() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val results = mutableListOf<Pair<String, Boolean>>()
        val tasks = JobServiceTasks<String, String>(CoroutineScope(coroutineContext + SupervisorJob()),
            work = { id, _ -> if (id == 2) error("temporary failure") },
            finished = { parameters, retry ->
                results += parameters to retry
                if (results.size == 2) completed.complete(Unit)
            })
        try {
            tasks.start(1, "maintenance", null)
            tasks.start(2, "holiday", "network")
            withTimeout(5_000) { completed.await() }
            assertEquals(setOf("maintenance" to false, "holiday" to true), results.toSet())
        } finally { tasks.close() }
    }

    @Test fun stoppingOneTaskCancelsItWithoutCompletingOrStoppingTheOtherTask() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondMayFinish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val results = mutableListOf<Pair<String, Boolean>>()
        val tasks = JobServiceTasks<String, String>(CoroutineScope(coroutineContext + SupervisorJob()),
            work = { id, _ ->
                if (id == 1) {
                    firstStarted.complete(Unit)
                    try { awaitCancellation() } finally { firstCancelled.complete(Unit) }
                } else {
                    secondStarted.complete(Unit)
                    secondMayFinish.await()
                }
            }, finished = { parameters, retry -> results += parameters to retry; completed.complete(Unit) })
        try {
            tasks.start(1, "maintenance", null)
            tasks.start(2, "holiday", "network")
            withTimeout(5_000) { firstStarted.await(); secondStarted.await() }
            tasks.stop(1)
            withTimeout(5_000) { firstCancelled.await() }
            assertTrue(results.isEmpty())
            secondMayFinish.complete(Unit)
            withTimeout(5_000) { completed.await() }
            assertEquals(listOf("holiday" to false), results)
        } finally { tasks.close() }
    }

    @Test fun networkHandoverWaitsForCancelledDownloadAndFinishesUsingOriginalParameters() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldCancelling = CompletableDeferred<Unit>()
        val releaseOldConnection = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val newMayFinish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val networks = mutableListOf<String?>()
        val results = mutableListOf<Pair<String, Boolean>>()
        val tasks = JobServiceTasks<String, String>(CoroutineScope(coroutineContext + SupervisorJob()),
            work = { _, network ->
                networks += network
                if (network == "old") {
                    oldStarted.complete(Unit)
                    try { awaitCancellation() } finally {
                        oldCancelling.complete(Unit)
                        withContext(NonCancellable) { releaseOldConnection.await() }
                    }
                } else {
                    newStarted.complete(Unit)
                    newMayFinish.await()
                }
            }, finished = { parameters, retry -> results += parameters to retry; completed.complete(Unit) })
        try {
            tasks.start(2, "original parameters", "old")
            withTimeout(5_000) { oldStarted.await() }
            tasks.networkChanged(2, "intermediate")
            tasks.networkChanged(2, "new")
            withTimeout(5_000) { oldCancelling.await() }
            yield()
            assertFalse(newStarted.isCompleted)
            assertTrue(results.isEmpty())
            releaseOldConnection.complete(Unit)
            withTimeout(5_000) { newStarted.await() }
            assertTrue("Old completion cannot finish the replacement", results.isEmpty())
            tasks.networkChanged(2, "new")
            yield()
            assertEquals(listOf("old", "new"), networks)
            newMayFinish.complete(Unit)
            withTimeout(5_000) { completed.await() }
            assertEquals(listOf("original parameters" to false), results)
        } finally { releaseOldConnection.complete(Unit); tasks.close() }
    }

    @Test fun stoppingDuringHandoverPreventsTheReplacementAndLateNetworkCallbacks() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val networks = mutableListOf<String?>()
        val results = mutableListOf<Boolean>()
        val tasks = JobServiceTasks<String, String>(CoroutineScope(coroutineContext + SupervisorJob()),
            work = { _, network ->
                networks += network
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    cancelling.complete(Unit)
                    withContext(NonCancellable) { releaseConnection.await() }
                    ended.complete(Unit)
                }
            }, finished = { _, retry -> results += retry })
        try {
            tasks.start(2, "parameters", "old")
            withTimeout(5_000) { started.await() }
            tasks.networkChanged(2, "new")
            withTimeout(5_000) { cancelling.await() }
            tasks.stop(2)
            tasks.networkChanged(2, "latest")
            releaseConnection.complete(Unit)
            withTimeout(5_000) { ended.await() }
            yield()
            assertEquals(listOf("old"), networks)
            assertTrue(results.isEmpty())
        } finally { releaseConnection.complete(Unit); tasks.close() }
    }

    @Test fun aNewSystemExecutionCannotBeCompletedByTheCancelledOldExecution() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val newFinished = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        val tasks = JobServiceTasks<String, String>(CoroutineScope(coroutineContext + SupervisorJob()),
            work = { _, network -> if (network == "old") { oldStarted.complete(Unit); awaitCancellation() } },
            finished = { parameters, _ -> results += parameters; newFinished.complete(Unit) })
        try {
            tasks.start(2, "old parameters", "old")
            withTimeout(5_000) { oldStarted.await() }
            tasks.start(2, "new parameters", "new")
            withTimeout(5_000) { newFinished.await() }
            assertEquals(listOf("new parameters"), results)
        } finally { tasks.close() }
    }

    @Test fun serviceDestructionCancelsAllTasksWithoutCompletingThem() = runBlocking {
        val started = List(2) { CompletableDeferred<Unit>() }
        val cancelled = List(2) { CompletableDeferred<Unit>() }
        val results = mutableListOf<String>()
        val tasks = JobServiceTasks<String, String>(CoroutineScope(coroutineContext + SupervisorJob()),
            work = { id, _ ->
                started[id].complete(Unit)
                try { awaitCancellation() } finally { cancelled[id].complete(Unit) }
            }, finished = { parameters, _ -> results += parameters })
        try {
            tasks.start(0, "maintenance", null)
            tasks.start(1, "holiday", "network")
            withTimeout(5_000) { started.forEach { it.await() } }
            tasks.close()
            withTimeout(5_000) { cancelled.forEach { it.await() } }
            assertTrue(results.isEmpty())
        } finally { tasks.close() }
    }
}
