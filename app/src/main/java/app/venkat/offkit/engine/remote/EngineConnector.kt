package app.venkat.offkit.engine.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How [RemoteEngine] reaches the engine process. Injectable for tests. */
interface EngineConnector {
    /**
     * Binds the engine service and blocks until it is connected, at most the connector's timeout.
     * Returns the service, or null on failure or timeout (in which case nothing stays bound).
     * [onLost] is called, on any thread and possibly more than once, if this connection later dies
     * or disconnects. Must not be called on the main thread.
     */
    fun connect(onLost: () -> Unit): IEngineService?

    /** Unbinds the current connection, if any. Never throws. */
    fun disconnect()

    /** Kills the engine process [pid] (same UID). */
    fun kill(pid: Int)
}

/**
 * [EngineConnector] over `bindService(BIND_AUTO_CREATE)`. A fresh [ServiceConnection] is used for
 * every [connect], so callbacks of an older binding never affect a newer one. Death is detected
 * both through `linkToDeath` (delivered on a binder thread, independent of the main thread) and
 * through [ServiceConnection.onServiceDisconnected] / [ServiceConnection.onBindingDied].
 *
 * @param bind starts binding with the given connection; false if the service cannot be bound.
 * @param unbind releases a connection passed to [bind].
 */
class BoundServiceConnector(
    private val bind: (ServiceConnection) -> Boolean,
    private val unbind: (ServiceConnection) -> Unit,
    private val killProcess: (Int) -> Unit = Process::killProcess,
    private val timeoutMs: Long = CONNECT_TIMEOUT_MS,
) : EngineConnector {
    private val lock = Any()
    private var current: ServiceConnection? = null

    override fun connect(onLost: () -> Unit): IEngineService? {
        disconnect()
        val connected = CountDownLatch(1)
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { onLost() }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = onLost()

            override fun onBindingDied(name: ComponentName?) {
                onLost()
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.countDown()
            }
        }
        synchronized(lock) { current = conn }
        val ok = try {
            bind(conn)
        } catch (e: RuntimeException) {
            false
        }
        if (!ok || !connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            disconnect()
            return null
        }
        val b = binder
        if (b == null) {
            disconnect()
            return null
        }
        try {
            b.linkToDeath(death, 0)
        } catch (e: RemoteException) {
            disconnect()
            return null
        }
        return IEngineService.Stub.asInterface(b)
    }

    override fun disconnect() {
        val conn = synchronized(lock) { current.also { current = null } } ?: return
        try {
            unbind(conn)
        } catch (e: RuntimeException) {
            // Already unbound or the context is gone.
        }
    }

    override fun kill(pid: Int) {
        if (pid > 0 && pid != Process.myPid()) killProcess(pid)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Connector for [EngineService] of [context]'s app, bound with `BIND_AUTO_CREATE`. */
        fun forService(context: Context): BoundServiceConnector {
            val app = context.applicationContext ?: context
            val intent = Intent(app, EngineService::class.java)
            return BoundServiceConnector(
                bind = { app.bindService(intent, it, Context.BIND_AUTO_CREATE) },
                unbind = { app.unbindService(it) },
            )
        }
    }
}
