package dev.yukaribox.vpn.vpn

import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.NodeFailure
import dev.yukaribox.vpn.core.TunnelController
import libcore.BoxInstance
import libcore.Libcore
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Thin wrapper over a single sing-box [BoxInstance]: builds it from a config
 * JSON, registers it as the main instance, starts it (which drives the core to
 * call back into [NativeInterface.openTun]) and tears it down.
 */
class BoxRunner {

    @Volatile
    private var box: BoxInstance? = null

    /**
     * Serialises **every** call that touches [box] against the call that closes it.
     *
     * A `close()` overlapping any other core call is undefined. `Box.Close()` cancels the
     * box context and tears down the router, the outbound manager and the connection
     * pools; a call still running through them dereferences what was just freed, and the
     * panic that follows lands on a goroutine **sing-box itself** spawned — a dialer, the
     * DNS router, quic-go, netpoll. libcore wraps only its own five entry points in
     * `device.DeferPanicToError`, so a panic out there is recovered by nobody, no Kotlin
     * `catch` can see it, and the Go runtime aborts the process with the TUN still open.
     * That is the one failure mode this service exists to prevent, and it is why the
     * guard covers [queryStats] and [probe] and not just [start]/[stop]: the previous
     * version's lock held only the latter pair, while the two calls that actually run
     * concurrently with a teardown — both from the stats thread — took no lock at all.
     *
     * Read/write rather than one monitor, because the callers are not symmetrical.
     * [queryStats] runs four times a second and [probe] blocks inside the core for up to
     * its timeout, so they must not exclude each other; [start] and [stop] must exclude
     * everything. A [stop] waiting on the write lock also bars *arriving* readers (the
     * non-fair implementation makes a queued writer block them), so a teardown cannot be
     * starved by a stats loop that keeps polling.
     *
     * The wait is deliberately unbounded. `Libcore.urlTest` is bounded by its own
     * `timeoutMs`, so the longest a Stop can queue behind is one in-flight probe — the
     * same cost measured for cancelling a connect (~2 s, all of it the in-flight
     * probe) — whereas the alternative, closing the core anyway once some bound expired,
     * is precisely the use-after-close this lock exists to remove. [stop] keeps a
     * lock-free fast path for the one caller that has no core to close at all.
     */
    private val lock = ReentrantReadWriteLock()

    /** Create and start the core. Blocks until the core is up or throws. */
    fun start(configJson: String) {
        lock.writeLock().lock()
        try {
            Logs.d("Box", "newSingBoxInstance")
            val instance = Libcore.newSingBoxInstance(configJson, LocalDns)
            box = instance
            instance.setAsMain()
            Logs.d("Box", "setAsMain done, starting core")
            instance.start()
            runCatching { instance.setV2rayStats("proxy\ndirect") }
            Logs.d("Box", "core start returned")
        } finally {
            lock.writeLock().unlock()
        }
    }

    /**
     * Cumulative byte counter for one outbound tag, or 0 when there is no core.
     *
     * Never throws — the stats loop treats a missing answer as "no data", not as an
     * error. Under the read lock, so a close cannot land between the null check and the
     * call: `runCatching` here catches a Java exception, and a Go-side fault is not one.
     */
    fun queryStats(tag: String, direction: String): Long {
        lock.readLock().lock()
        try {
            val instance = box ?: return 0L
            return runCatching { instance.queryStats(tag, direction) }.getOrDefault(0L)
        } finally {
            lock.readLock().unlock()
        }
    }

    /**
     * Probe the live tunnel: run the core's own URL test through the running box
     * (the same call NekoBox uses for its connection test). Returns the round-trip
     * latency in ms, or a non-positive value if nothing came back within
     * [timeoutMs] — i.e. a traffic timeout. Never throws.
     *
     * Held under the read lock for the whole native call, which is what stops a Stop
     * from closing the box out from under an HTTP request that is still being dialled
     * through it. See [lock] for why that mattered more than the wait it can cost.
     */
    fun probe(url: String, timeoutMs: Int): Int {
        lock.readLock().lock()
        try {
            val instance = box ?: return -1
            return runCatching { Libcore.urlTest(instance, url, timeoutMs) }.getOrDefault(-1)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun stop() {
        // Lock-free when there is nothing to close, so this never blocks a caller behind
        // a connect that is still starting — see [lock].
        if (box == null) return
        lock.writeLock().lock()
        try {
            val instance = box ?: return
            box = null
            Logs.d("Box", "closing core")
            runCatching { instance.close() }
        } finally {
            lock.writeLock().unlock()
        }
    }
}

/**
 * One supervised connect attempt: start the core, then probe the tunnel. Returns
 * null on success, or the [NodeFailure] that occurred — [NodeFailure.HandshakeError]
 * if the core failed to start, [NodeFailure.TrafficTimeout] if it came up but no
 * traffic flowed through the tunnel. Never throws; on failure the core is stopped
 * so the next attempt starts clean. Lives outside [YukariVpnService] so the
 * service's class stays under the function-count budget.
 */
@Suppress("TooGenericExceptionCaught") // the native core can throw Error as well as Exception
fun BoxRunner.attemptConnect(config: String, testUrl: String, probeTimeoutMs: Int): NodeFailure? = try {
    start(config)
    val rtt = probe(testUrl, probeTimeoutMs)
    if (rtt <= 0) {
        Logs.w("Tunnel", "traffic timeout: no response through tunnel")
        TunnelController.lastError = "traffic timeout"
        stop()
        NodeFailure.TrafficTimeout
    } else {
        Logs.i("Tunnel", "tunnel reachable (${rtt}ms)")
        null
    }
} catch (e: Throwable) {
    TunnelController.lastError = e.message ?: e.javaClass.simpleName
    Logs.e("Tunnel", "core start failed (handshake error)", e)
    stop()
    NodeFailure.HandshakeError
}
