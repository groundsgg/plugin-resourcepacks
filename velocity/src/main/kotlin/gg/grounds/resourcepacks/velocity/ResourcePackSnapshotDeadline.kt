package gg.grounds.resourcepacks.velocity

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface ResourcePackSnapshotDeadline : AutoCloseable {
    fun schedule(action: () -> Unit): AutoCloseable
}

internal class ScheduledResourcePackSnapshotDeadline(
    private val delaySeconds: Long = 15,
) : ResourcePackSnapshotDeadline {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "resource-pack-snapshot-deadline").apply { isDaemon = true }
    }
    private val monitor = Any()
    private val tasks = mutableSetOf<ScheduledFuture<*>>()
    private var closed = false

    override fun schedule(action: () -> Unit): AutoCloseable = synchronized(monitor) {
        if (closed) return@synchronized AutoCloseable { }
        lateinit var task: ScheduledFuture<*>
        task = executor.schedule({
            synchronized(monitor) { tasks.remove(task) }
            action()
        }, delaySeconds, TimeUnit.SECONDS)
        tasks += task
        AutoCloseable { synchronized(monitor) { tasks.remove(task); task.cancel(false) } }
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            tasks.forEach { it.cancel(false) }
            tasks.clear()
        }
        executor.shutdownNow()
    }
}
