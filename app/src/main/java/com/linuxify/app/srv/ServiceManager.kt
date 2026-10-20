package com.linuxify.app.srv

import android.content.Context
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeForegroundService
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.runtime.RuntimeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Managed local services: user-defined commands executed inside the Linux userspace.
 *
 * State model is strictly derived from reality:
 *  - a process that was spawned is STARTING until it is proven alive (or its port is
 *    proven reachable) — never "Running" merely because Start was pressed
 *  - unexpected exit is reported with the real exit code and recent output
 *
 * Persistence: service definitions in runtime/services.json. Process liveness after
 * app death is NOT claimed: on app restart, services are reported Stopped (honest
 * reporting of Android process semantics).
 */
class ServiceManager(private val ctx: Context) {

    data class ServiceDef(
        val id: String,
        val name: String,
        val command: String,
        val port: Int?,
    )

    enum class State { STOPPED, STARTING, RUNNING, FAILED, STOPPING }

    data class ServiceRuntime(
        val def: ServiceDef,
        val state: State,
        val pid: Int? = null,
        val exitCode: Int? = null,
        val portReachable: Boolean = false,
        val recentOutput: List<String> = emptyList(),
        val startedAtMs: Long? = null,
    )

    private val runtime = LinuxRuntime(ctx)
    private val processLock = Mutex()
    private val running = HashMap<String, RunningProcess>()

    companion object {
        /** Live managed-service processes — consulted by the foreground service
            so it cannot stop while any service still runs. */
        @Volatile
        var activeCount: Int = 0
            private set
    }

    private class RunningProcess(
        val process: Process,
        val output: MutableList<String> = mutableListOf(),
        val startedAtMs: Long = System.currentTimeMillis(),
    ) {
        /** Set when the USER stopped the service — its exit is then expected and
            must not raise a "run completed" notification. */
        @Volatile
        var userStopped: Boolean = false
    }

    private val defsFile: File get() = File(com.linuxify.app.runtime.RuntimeDirs.runtimeDir(ctx), "services.json")

    fun listDefinitions(): List<ServiceDef> {
        if (!defsFile.isFile) return defaultDefinitions()
        return try {
            val arr = JSONArray(defsFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServiceDef(o.getString("id"), o.getString("name"), o.getString("command"),
                    if (o.isNull("port")) null else o.optInt("port"))
            }
        } catch (_: Exception) {
            defaultDefinitions()
        }
    }

    private fun defaultDefinitions(): List<ServiceDef> = listOf(
        ServiceDef("python-http", "Python HTTP server", "python3 -m http.server {port} --directory /root/projects", 8000),
    )

    fun runtimeStates(): List<ServiceRuntime> =
        listDefinitions().map { def ->
            val rp = synchronized(running) { running[def.id] }
            if (rp == null) {
                ServiceRuntime(def, State.STOPPED)
            } else {
                val alive = rp.process.isAlive
                val reachable = if (alive && def.port != null) isPortReachable(def.port) else false
                val state = when {
                    !alive -> State.FAILED
                    def.port != null && reachable -> State.RUNNING
                    def.port != null -> State.STARTING
                    else -> State.RUNNING
                }
                val recent = synchronized(rp.output) { rp.output.takeLast(50) }
                ServiceRuntime(def, state, pid = pidOf(rp), portReachable = reachable,
                    recentOutput = recent, startedAtMs = rp.startedAtMs)
            }
        }

    private fun pidOf(rp: RunningProcess): Int? = try {
        val m = rp.process.javaClass.methods.firstOrNull { it.name == "pid" }
        m?.invoke(rp.process) as? Int
    } catch (_: Exception) {
        null
    }

    /** Reachability check against the real loopback socket — never inferred from config. */
    fun isPortReachable(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 700)
            true
        }
    } catch (_: Exception) {
        false
    }

    suspend fun start(def: ServiceDef): ServiceRuntime = withContext(Dispatchers.IO) {
        processLock.withLock {
            synchronized(running) {
                if (running[def.id]?.process?.isAlive == true) {
                    return@withContext runtimeStates().first { it.def.id == def.id }
                }
            }
            val actualCommand = def.command.replace("{port}", (def.port ?: 8080).toString())
            val argv = mutableListOf(RuntimeDirs.prootBinary(ctx).absolutePath)
            argv += runtime.buildProotArgs()
            argv += listOf("/bin/bash", "-lc", actualCommand)

            val pb = ProcessBuilder(argv)
            pb.environment().putAll(runtime.buildEnv())
            pb.redirectErrorStream(true)
            val process = pb.start()

            val rp = RunningProcess(process)
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(rp.output) {
                            rp.output.add(line)
                            while (rp.output.size > 200) rp.output.removeAt(0)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()

            synchronized(running) { running[def.id] = rp }
            RuntimeForegroundService.start(ctx)
            activeCount += 1

            // Exit watcher: the single place that decrements activeCount. An
            // exit the user did not cause becomes a "run completed" notification
            // — exactly what you want after leaving the app while a server runs.
            Thread {
                val code = try {
                    process.waitFor()
                } catch (_: Exception) {
                    return@Thread
                }
                activeCount = (activeCount - 1).coerceAtLeast(0)
                if (!rp.userStopped) {
                    RuntimeNotifier.runCompleted(
                        ctx,
                        title = "Run completed — ${def.name} stopped",
                        text = "Process exited (code $code). Tap to open Linuxify.",
                    )
                }
                RuntimeForegroundService.stopIfIdle(ctx)
            }.apply { isDaemon = true }.start()

            // honest state: give the process a short grace period, then report reality
            Thread.sleep(600)
            val alive = process.isAlive
            val reachable = def.port?.let { isPortReachable(it) } ?: false
            ServiceRuntime(
                def,
                when {
                    !alive -> State.FAILED
                    def.port != null && reachable -> State.RUNNING
                    else -> State.STARTING
                },
                pid = pidOf(rp),
                exitCode = if (!alive) process.exitValue() else null,
                portReachable = reachable,
                recentOutput = synchronized(rp.output) { rp.output.takeLast(50) },
                startedAtMs = rp.startedAtMs,
            )
        }
    }

    suspend fun stop(def: ServiceDef): ServiceRuntime = withContext(Dispatchers.IO) {
        processLock.withLock {
            val rp = synchronized(running) { running.remove(def.id) }
            if (rp != null) {
                rp.userStopped = true
                try {
                    rp.process.destroy()
                    if (!rp.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        rp.process.destroyForcibly()
                    }
                } catch (_: Exception) {
                }
            }
            ServiceRuntime(def, State.STOPPED)
        }
    }

    fun shutdownAll() {
        synchronized(running) {
            running.values.forEach { rp ->
                rp.userStopped = true
                try {
                    rp.process.destroyForcibly()
                } catch (_: Exception) {
                }
            }
            running.clear()
        }
    }
}
