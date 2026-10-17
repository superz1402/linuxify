package com.linuxify.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linuxify.app.pkg.AptManager
import com.linuxify.app.proj.ProjectsManager
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RootfsInstaller
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.runtime.RuntimeMetadata
import com.linuxify.app.srv.ServiceManager
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

    private val ctx: Context get() = getApplication<Application>()

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
    )

    private val _homeFacts = MutableStateFlow<HomeFacts?>(null)
    val homeFacts: StateFlow<HomeFacts?> = _homeFacts

    private val _storageWarning = MutableStateFlow<String?>(null)
    val storageWarning: StateFlow<String?> = _storageWarning

    init {
        refreshHomeFacts()
        viewModelScope.launch {
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
        viewModelScope.launch(Dispatchers.IO) {
            val installed = installer.isReadyMarkerPresent()
            val facts = if (installed) {
                val rootfs = RuntimeDirs.rootfsDir(ctx)
                HomeFacts(
                    installed = true,
                    distro = RuntimeMetadata.DISTRO_NAME,
                    version = RuntimeMetadata.DISTRO_VERSION,
                    rootfsSizeBytes = RuntimeDirs.sizeRecursively(rootfs),
                    projectsCount = RuntimeDirs.projectsDir(ctx).listFiles()?.count { it.isDirectory } ?: 0,
                    installedPackages = try { apt.installedCount() } catch (_: Exception) { null },
                    freeBytes = RuntimeDirs.availableBytes(ctx),
                )
            } else {
                HomeFacts(installed = false, freeBytes = RuntimeDirs.availableBytes(ctx))
            }
            _homeFacts.value = facts
        }
    }

    fun startInstall() {
        viewModelScope.launch {
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
        viewModelScope.launch(Dispatchers.IO) {
            _projectsState.value = _projectsState.value.copy(loading = true)
            val list = projects.list()
            _projectsState.value = _projectsState.value.copy(loading = false, projects = list)
        }
    }

    fun createProject(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = projects.create(name)
            _projectsState.value = _projectsState.value.copy(
                message = res.fold({ "Project '$name' created" }, { it.message }),
            )
            refreshProjects()
        }
    }

    fun cloneProject(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _projectsState.value = _projectsState.value.copy(busy = true, log = emptyList())
            val res = projects.clone(url) { line ->
                _projectsState.value = _projectsState.value.copy(log = _projectsState.value.log + line)
            }
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
        _servicesState.value = ServicesUiState(services.runtimeStates(), loading = false)
    }

    fun startService(def: ServiceManager.ServiceDef) {
        viewModelScope.launch(Dispatchers.IO) {
            services.start(def)
            refreshServices()
        }
    }

    fun stopService(def: ServiceManager.ServiceDef) {
        viewModelScope.launch(Dispatchers.IO) {
            services.stop(def)
            refreshServices()
        }
    }

    // ---- Packages ----

    fun installPackages(vararg names: String, onDone: (AptManager.Operation) -> Unit) {
        viewModelScope.launch {
            val op = apt.install(*names)
            onDone(op)
            refreshHomeFacts()
        }
    }

    fun removePackages(vararg names: String, onDone: (AptManager.Operation) -> Unit) {
        viewModelScope.launch {
            val op = apt.remove(*names)
            onDone(op)
            refreshHomeFacts()
        }
    }

    fun searchPackages(query: String, onResult: (List<AptManager.SearchHit>) -> Unit) {
        viewModelScope.launch {
            onResult(apt.search(query))
        }
    }

    fun refreshPackageIndex() {
        viewModelScope.launch { apt.refreshIndex() }
    }

    // ---- Settings / storage ----

    fun clearAptCaches(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val archives = File(RuntimeDirs.rootfsDir(ctx), "var/cache/apt/archives")
            var freed = 0L
            archives.listFiles()?.forEach { f -> if (f.isFile && f.name.endsWith(".deb")) { freed += f.length(); f.delete() } }
            onDone("Freed ${LinuxRuntime.humanSize(freed)} of cached packages")
        }
    }

    fun deleteTextFileSafely(relative: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
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
