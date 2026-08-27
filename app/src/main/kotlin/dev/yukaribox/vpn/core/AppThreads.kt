package dev.yukaribox.vpn.core

import android.os.Process
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The thread factory every background executor in this app is built on.
 *
 * Two properties the JDK's default factory does not give, and both of them showed up as
 * dropped frames on the servers list.
 *
 * **Background priority.** A thread created from the main thread inherits
 * `Thread.NORM_PRIORITY`, which Android maps to the same nice value and the same cpuset
 * as the UI thread — so the URL-test workers, each running a whole sing-box instance's
 * TLS handshake, competed with rendering for CPU on equal terms. `THREAD_PRIORITY_BACKGROUND`
 * moves them to the background cpuset, where they take what is left instead. It is set
 * from inside the thread rather than through `Thread.setPriority`, because Android's
 * scheduler reads the Linux nice value [Process.setThreadPriority] writes, not the Java
 * priority.
 *
 * **Daemon.** The default factory's threads are not, and every executor here belongs to
 * an `object` singleton with nothing to shut it down, so each pool stayed resident for
 * the life of the process.
 *
 * The priority call is guarded: it is an Android platform call, and these executors are
 * created when their singleton is first touched, which a JVM unit test may do.
 */
object AppThreads {

    /**
     * A factory whose threads are named `<name>-<n>`, daemon, and run at Android's
     * background priority.
     */
    fun factory(name: String): ThreadFactory {
        val counter = AtomicInteger(0)
        return ThreadFactory { runnable ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runnable.run()
            }, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
