package com.linuxify.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linuxify.app.pkg.AptManager
import com.linuxify.app.proj.ProjectsManager
import com.linuxify.app.runtime.CrashReporter
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RootfsInstaller
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.runtime.RuntimeMetadata
import com.linuxify.app.srv.ServiceManager
import com.linuxify.app.terminal.TerminalSessionsController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Application-level state holder. Single source of truth for:
 * runtime install state, home facts, projects, packages, services.
 * All screens observe this VM; screens never touch processes or the filesystem.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val installer = RootfsInstaller(app)
    val runtime = LinuxRuntime(app)
    val apt = AptManager(app)
    val projects = ProjectsManager(app)
    val services = ServiceManager(app)

    /**
     * Terminal sessions live at ViewModel scope: navigation and taps never
     * close, reset, or duplicate the shell (user-reported issue fix).
     */
    val terminalSessions = TerminalSessionsController(app, viewModelScope)

    private val ctx: Context get() = getApplication<Application>()

    /**
     * Background errors must degrade to a logged, visible message — never an
     * uncaught coroutine exception that kills the process ("app keeps stopping").
     */
    private val bgGuard = CoroutineExceptionHandler { _, e ->
        CrashReporter.logNonFatal(getApplication(), "viewModelScope", e)
    }

    /** The packaged native runtime must be present for ANY screen to work. */
    fun runtimeBinariesOk(): Boolean =
        RuntimeDirs.prootAvailable(ctx) && RuntimeDirs.prootLoaderAvailable(ctx)

    data class HomeFacts(
        val installed: Boolean,
        val distro: String? = null,
        val version: String? = null,
        val abi: String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        val rootfsSizeBytes: Long = 0,
        val projectsCount: Int = 0,
        val installedPackages: Int? = null,
        val freeBytes: Long = 0,
        val deviceModel: String = android.os.Build.MODEL,
        val prootOk: Boolean = false,
        val loaderOk: Boolean = false,
    )

    private val _homeFacts = MutableStateFlow<HomeFacts?>(null)
    val homeFacts: StateFlow<HomeFacts?> = _homeFacts

    private val _storageWarning = MutableStateFlow<String?>(null)
    val storageWarning: StateFlow<String?> = _storageWarning

    init {
        refreshHomeFacts()
        viewModelScope.launch(bgGuard) {
            // periodic refresh of free-space warning while the app is open (cheap check)
            while (true) {
                val free = RuntimeDirs.availableBytes(ctx)
                _storageWarning.value = if (free < 512L * 1024 * 1024) {
                    "Low storage: ${LinuxRuntime.humanSize(free)} free. Package installs and servers may fail."
                } else null
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    fun refreshHomeFacts() {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val facts = runCatching {
                val installed = installer.isReadyMarkerPresent()
                if (installed) {
                    val rootfs = RuntimeDirs.rootfsDir(ctx)
                    HomeFacts(
                        installed = true,
                        distro = RuntimeMetadata.DISTRO_NAME,
                        version = RuntimeMetadata.DISTRO_VERSION,
                        rootfsSizeBytes = RuntimeDirs.sizeRecursively(rootfs),
                        projectsCount = RuntimeDirs.projectsDir(ctx).listFiles()?.count { it.isDirectory } ?: 0,
                        installedPackages = try { apt.installedCount() } catch (_: Exception) { null },
                        freeBytes = RuntimeDirs.availableBytes(ctx),
                        prootOk = RuntimeDirs.prootAvailable(ctx),
                        loaderOk = RuntimeDirs.prootLoaderAvailable(ctx),
                    )
                } else {
                    HomeFacts(installed = false, freeBytes = RuntimeDirs.availableBytes(ctx))
                }
            }.getOrElse {
                CrashReporter.logNonFatal(getApplication(), "refreshHomeFacts", it)
                HomeFacts(installed = false, freeBytes = RuntimeDirs.availableBytes(ctx))
            }
            _homeFacts.value = facts
        }
    }

    fun startInstall() {
        viewModelScope.launch(bgGuard) {
            installer.install()
            refreshHomeFacts()
        }
    }

    // ---- Projects ----

    data class ProjectsUiState(
        val loading: Boolean = true,
        val projects: List<ProjectsManager.Project> = emptyList(),
        val message: String? = null,
        val busy: Boolean = false,
        val log: List<String> = emptyList(),
    )

    private val _projectsState = MutableStateFlow(ProjectsUiState())
    val projectsState: StateFlow<ProjectsUiState> = _projectsState

    fun refreshProjects() {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            _projectsState.value = _projectsState.value.copy(loading = true)
            val list = runCatching { projects.list() }
                .onFailure {
                    CrashReporter.logNonFatal(getApplication(), "refreshProjects", it)
                    _projectsState.value = _projectsState.value.copy(
                        message = "Could not list projects: ${it.message ?: it.javaClass.simpleName}",
                    )
                }
                .getOrDefault(emptyList())
            _projectsState.value = _projectsState.value.copy(loading = false, projects = list)
        }
    }

    fun createProject(name: String) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val res = runCatching { projects.create(name) }
                .getOrElse { Result.failure(it) }
            _projectsState.value = _projectsState.value.copy(
                message = res.fold({ "Project '$name' created" }, { it.message }),
            )
            refreshProjects()
        }
    }

    fun cloneProject(url: String) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            _projectsState.value = _projectsState.value.copy(busy = true, log = emptyList())
            val res = runCatching {
                projects.clone(url) { line ->
                    _projectsState.value = _projectsState.value.copy(log = _projectsState.value.log + line)
                }
            }.getOrElse { Result.failure(it) }
            _projectsState.value = _projectsState.value.copy(
                busy = false,
                message = res.fold({ "Cloned ${res.getOrNull()}" }, { "Clone failed: ${it.message}" }),
            )
            refreshProjects()
        }
    }

    // ---- Services ----

    data class ServicesUiState(
        val services: List<ServiceManager.ServiceRuntime> = emptyList(),
        val loading: Boolean = true,
    )

    private val _servicesState = MutableStateFlow(ServicesUiState())
    val servicesState: StateFlow<ServicesUiState> = _servicesState

    fun refreshServices() {
        val states = runCatching { services.runtimeStates() }
            .onFailure {
                CrashReporter.logNonFatal(ctx, "refreshServices", it)
            }
            .getOrDefault(emptyList())
        _servicesState.value = ServicesUiState(states, loading = false)
    }

    fun startService(def: ServiceManager.ServiceDef) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            runCatching { services.start(def) }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "startService", it) }
            refreshServices()
        }
    }

    fun stopService(def: ServiceManager.ServiceDef) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            runCatching { services.stop(def) }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "stopService", it) }
            refreshServices()
        }
    }

    // ---- Packages ----

    fun installPackages(vararg names: String, onDone: (AptManager.Operation) -> Unit) {
        viewModelScope.launch(bgGuard) {
            val op = runCatching { apt.install(*names) }.getOrElse {
                CrashReporter.logNonFatal(getApplication(), "apt install", it)
                AptManager.Operation(
                    AptManager.Operation.Kind.INSTALL,
                    AptManager.Operation.State.FAILED,
                    listOf(it.message ?: it.javaClass.simpleName),
                )
            }
            onDone(op)
            refreshHomeFacts()
        }
    }

    fun removePackages(vararg names: String, onDone: (AptManager.Operation) -> Unit) {
        viewModelScope.launch(bgGuard) {
            val op = runCatching { apt.remove(*names) }.getOrElse {
                CrashReporter.logNonFatal(getApplication(), "apt remove", it)
                AptManager.Operation(
                    AptManager.Operation.Kind.REMOVE,
                    AptManager.Operation.State.FAILED,
                    listOf(it.message ?: it.javaClass.simpleName),
                )
            }
            onDone(op)
            refreshHomeFacts()
        }
    }

    fun searchPackages(query: String, onResult: (List<AptManager.SearchHit>) -> Unit) {
        viewModelScope.launch(bgGuard) {
            onResult(runCatching { apt.search(query) }.getOrElse {
                CrashReporter.logNonFatal(getApplication(), "apt search", it)
                emptyList()
            })
        }
    }

    fun refreshPackageIndex() {
        viewModelScope.launch(bgGuard) {
            runCatching { apt.refreshIndex() }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "apt update", it) }
        }
    }

    // ---- Settings / storage ----

    fun clearAptCaches(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val archives = File(RuntimeDirs.rootfsDir(ctx), "var/cache/apt/archives")
            var freed = 0L
            archives.listFiles()?.forEach { f -> if (f.isFile && f.name.endsWith(".deb")) { freed += f.length(); f.delete() } }
            onDone("Freed ${LinuxRuntime.humanSize(freed)} of cached packages")
        }
    }

    fun deleteTextFileSafely(relative: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val rootfs = RuntimeDirs.rootfsDir(ctx).canonicalFile
            val target = File(rootfs, relative.trimStart('/'))
            val result = if (!target.canonicalFile.path.startsWith(rootfs.path) || target == rootfs) {
                Result.failure(IllegalArgumentException("Invalid path"))
            } else {
                runCatching {
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                    if (target.exists()) throw IllegalStateException("Delete failed")
                }
            }
            onDone(result)
        }
    }

    override fun onCleared() {
        services.shutdownAll()
        super.onCleared()
    }
}
