package com.linuxify.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linuxify.app.LinuxifyApp
import com.linuxify.app.pkg.AptManager
import com.linuxify.app.pkg.CatalogEntry
import com.linuxify.app.pkg.EnvCore
import com.linuxify.app.pkg.EnvManager
import com.linuxify.app.pkg.SoftwareManager
import com.linuxify.app.proj.ProjectsManager
import com.linuxify.app.backup.BackupManager
import com.linuxify.app.maintenance.CacheTargets
import com.linuxify.app.maintenance.MaintenanceManager
import com.linuxify.app.runtime.CrashReporter
import com.linuxify.app.runtime.GuestPatches
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.PowerPolicy
import com.linuxify.app.runtime.RootfsInstaller
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.runtime.RuntimeMetadata
import com.linuxify.app.srv.ServiceManager
import com.linuxify.app.terminal.TerminalPersistence
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
    val env = EnvManager(app, apt)
    val software = SoftwareManager(app, apt, env)
    val projects = ProjectsManager(app)
    val services = ServiceManager(app)
    val maintenance = MaintenanceManager(app)
    val backup = BackupManager(app)

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

    /** True if a persisted crash report exists (shown on Home for one-tap access). */
    fun hasCrashReport(): Boolean = runCatching { CrashReporter.reportFile(ctx).exists() }.getOrDefault(false)

    /** User dismissed the crash-report card on Home — delete the persisted report. */
    fun dismissCrashReport(onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            runCatching { CrashReporter.reportFile(ctx).delete() }
            withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    data class HomeFacts(
        val installed: Boolean,
        val distro: String? = null,
        val version: String? = null,
        val abi: String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        val rootfsSizeBytes: Long = 0,
        val projectsCount: Int = 0,
        val projectsSizeBytes: Long = 0,
        val terminalLogsBytes: Long = 0,
        val appCacheBytes: Long = 0,
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
        // Terminal history (issue #23): load the session index BEFORE any
        // screen can spawn/register sessions, so previous-run records are in
        // memory when the Terminal screen reads TerminalPersistence.history().
        runCatching { TerminalPersistence.loadFromDisk(getApplication()) }
            .onFailure { CrashReporter.logNonFatal(getApplication(), "TerminalPersistence.loadFromDisk", it) }
        // Idempotent in-place upgrade for an ALREADY-extracted rootfs: group
        // resolution + sudo-as-admin marker + profile bell hook (alpha.11).
        // Runs on every start; a fully-patched rootfs makes it a cheap no-op.
        // Fresh installs get the same patches inside configureUserspace(), and
        // PtyTerminal re-checks before every spawn (memoized, ~free).
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val summary = runCatching { GuestPatches.applyAll(RuntimeDirs.rootfsDir(ctx)) }.getOrNull()
            if (summary == null) {
                // Non-fatal by design, but NEVER silent — an unpatched guest is
                // exactly the class of quiet misbehavior that cost us alpha.7's
                // "fixed" group warnings (the patch silently didn't apply).
                CrashReporter.logNonFatal(getApplication(), "GuestPatches.applyAll", IllegalStateException("guest patch failed (rootfs missing or not extracted)"))
            }
        }
        refreshHomeFacts()
        viewModelScope.launch(bgGuard) {
            // periodic refresh of free-space warning while the app is open.
            // Battery audit (alpha.17): when backgrounded this check is pure
            // waste — the warning card is not visible and RuntimeDirs does a
            // filesystem stat walk. Sleep through background ticks.
            while (true) {
                if (LinuxifyApp.isInForeground) {
                    val free = RuntimeDirs.availableBytes(ctx)
                    _storageWarning.value = if (free < 512L * 1024 * 1024) {
                        "Low storage: ${LinuxRuntime.humanSize(free)} free. Package installs and servers may fail."
                    } else null
                    kotlinx.coroutines.delay(30_000)
                } else {
                    kotlinx.coroutines.delay(60_000)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            // Terminal transcript persistence loop (issue #23): snapshot dirty
            // sessions periodically — the disk write itself is throttled to once
            // per 3s per session and skipped entirely when unchanged.
            // Battery audit (alpha.17): 10s cadence in the foreground keeps
            // crash loss small; in the background the ON_STOP flush already
            // captured everything, so the sweep drops to a 60s safety net
            // instead of waking the CPU every 10 seconds for nothing.
            while (true) {
                val fg = runCatching { LinuxifyApp.isInForeground }.getOrDefault(true)
                kotlinx.coroutines.delay(if (fg) 10_000 else 60_000)
                runCatching { TerminalPersistence.sweep(ctx) }
                    .onFailure { CrashReporter.logNonFatal(getApplication(), "TerminalPersistence.sweep", it) }
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
                        projectsSizeBytes = CacheTargets.sizeOfDirSafe(RuntimeDirs.projectsDir(ctx)),
                        terminalLogsBytes = CacheTargets.sizeOfDirSafe(RuntimeDirs.terminalDir(ctx)),
                        appCacheBytes = CacheTargets.sizeOfDirSafe(ctx.cacheDir),
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
            // Long-op marker: download/extract may run many minutes with no
            // session or service live — this is exactly when the CPU must not
            // be allowed to Doze-sleep mid-download (PowerPolicy, alpha.17).
            PowerPolicy.opBegin(ctx)
            try {
                installer.install()
                refreshHomeFacts()
                // Base environment (curl + ca-certificates) is part of the DEFAULT
                // install — the Dependency & Package Recovery Manager installs it
                // right after the rootfs is ready. Best-effort: if it fails (e.g.
                // offline), the Packages screen shows the pending state and the
                // same profile can be installed with one tap later.
                runCatching { env.installProfile(EnvCore.PROFILE_BASE.id) }
                    .onFailure { CrashReporter.logNonFatal(getApplication(), "env base install", it) }
            } finally {
                PowerPolicy.opEnd(ctx)
            }
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

    /** Surface a transient message on the Projects screen (used by cache clearing). */
    fun showMessage(text: String) {
        _projectsState.value = _projectsState.value.copy(message = text)
    }

    // ---- Cross-screen "open in" requests (issue #22) ----
    // Projects hands a target directory to the Terminal / Files screens via
    // these pending slots instead of nav route arguments: the values are guest
    // paths (no URL-encoding worries), consumed exactly once on first
    // composition, and never linger in the back stack.

    /** Guest path the next spawned terminal session should start in, or null. */
    private var pendingTerminalWorkdir: String? = null

    /** Directory the Files screen should start in when next composed, or null. */
    private var pendingFilesStartDir: String? = null

    /** "Open terminal" on a project: next terminal spawn starts in its dir. */
    fun requestTerminalInProject(projectName: String) {
        pendingTerminalWorkdir = "/root/projects/$projectName"
    }

    /** "Browse files" on a project: the Files screen opens at its directory. */
    fun requestFilesInProject(projectName: String) {
        pendingFilesStartDir = "/root/projects/$projectName"
    }

    /** Terminal-side consume-once (null = no request; plain spawn in /root). */
    fun consumePendingTerminalWorkdir(): String? =
        pendingTerminalWorkdir.also { pendingTerminalWorkdir = null }

    /** Files-side consume-once (null = no request; start at /root). */
    fun consumePendingFilesStartDir(): String? =
        pendingFilesStartDir.also { pendingFilesStartDir = null }

    /**
     * @param force bypass the 30s probe TTL cache (battery audit): mutations
     *              (create/clone/cache-clear) must always re-probe for real.
     */
    fun refreshProjects(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            _projectsState.value = _projectsState.value.copy(loading = true)
            val list = runCatching { projects.list(force) }
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
            refreshProjects(force = true)
        }
    }

    fun cloneProject(url: String) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            PowerPolicy.opBegin(ctx)
            try {
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
                refreshProjects(force = true)
            } finally {
                PowerPolicy.opEnd(ctx)
            }
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
            PowerPolicy.opBegin(ctx)
            try {
                // Dependency & Package Recovery Manager: repair the package manager
                // BEFORE installing anything else — but only when it is actually
                // inconsistent (never a blind dpkg --configure -a). A probe failure
                // must not block a plain install attempt.
                val healthy = runCatching { env.ensureHealthyOnly() }.getOrElse {
                    CrashReporter.logNonFatal(getApplication(), "env ensureHealthyOnly", it)
                    true
                }
                val op = if (!healthy) {
                    AptManager.Operation(
                        AptManager.Operation.Kind.INSTALL,
                        AptManager.Operation.State.FAILED,
                        listOf(env.state.value.failureHint ?: "package manager is inconsistent — repair it first (Software > Environment > Repair package manager)"),
                    )
                } else {
                    runCatching { apt.install(*names) }.getOrElse {
                        CrashReporter.logNonFatal(getApplication(), "apt install", it)
                        AptManager.Operation(
                            AptManager.Operation.Kind.INSTALL,
                            AptManager.Operation.State.FAILED,
                            listOf(it.message ?: it.javaClass.simpleName),
                        )
                    }
                }
                onDone(op)
                refreshHomeFacts()
            } finally {
                PowerPolicy.opEnd(ctx)
            }
        }
    }

    fun removePackages(vararg names: String, onDone: (AptManager.Operation) -> Unit) {
        viewModelScope.launch(bgGuard) {
            PowerPolicy.opBegin(ctx)
            try {
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
            } finally {
                PowerPolicy.opEnd(ctx)
            }
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
            PowerPolicy.opBegin(ctx)
            try {
                runCatching { apt.refreshIndex() }
                    .onFailure { CrashReporter.logNonFatal(getApplication(), "apt update", it) }
            } finally {
                PowerPolicy.opEnd(ctx)
            }
        }
    }

    // ---- Universal Software Management ----

    fun refreshCatalogState(entries: List<CatalogEntry>) {
        viewModelScope.launch(bgGuard) {
            runCatching { software.refreshInstalledState(entries) }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "refreshCatalogState", it) }
        }
    }

    fun installCatalogEntry(entry: CatalogEntry) {
        viewModelScope.launch(bgGuard) {
            PowerPolicy.opBegin(ctx)
            try {
                runCatching { software.install(entry) }
                    .onFailure { CrashReporter.logNonFatal(getApplication(), "installCatalogEntry", it) }
                refreshHomeFacts()
            } finally {
                PowerPolicy.opEnd(ctx)
            }
        }
    }

    fun removeCatalogEntry(entry: CatalogEntry) {
        viewModelScope.launch(bgGuard) {
            PowerPolicy.opBegin(ctx)
            try {
                runCatching { software.remove(entry) }
                    .onFailure { CrashReporter.logNonFatal(getApplication(), "removeCatalogEntry", it) }
                refreshHomeFacts()
            } finally {
                PowerPolicy.opEnd(ctx)
            }
        }
    }

    /** One-tap index refresh + full APT upgrade, after a health check. */
    fun upgradePackages() {
        viewModelScope.launch(bgGuard) {
            PowerPolicy.opBegin(ctx)
            try {
                runCatching {
                    if (!env.ensureHealthyOnly()) return@runCatching
                    apt.refreshIndex()
                    apt.fullUpgrade()
                }.onFailure { CrashReporter.logNonFatal(getApplication(), "upgradePackages", it) }
                refreshHomeFacts()
            } finally {
                PowerPolicy.opEnd(ctx)
            }
        }
    }

    fun addCustomTool(
        name: String,
        ecosystem: String,
        pkg: String,
        binary: String?,
        description: String?,
        onDone: (CatalogEntry?) -> Unit,
    ) {
        viewModelScope.launch(bgGuard) {
            val res = runCatching { software.addCustomTool(name, ecosystem, pkg, binary, description) }
                .getOrElse {
                    CrashReporter.logNonFatal(getApplication(), "addCustomTool", it)
                    null
                }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(res) }
        }
    }

    fun removeCustomTool(id: String) {
        viewModelScope.launch(bgGuard) {
            runCatching { software.removeCustomTool(id) }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "removeCustomTool", it) }
        }
    }

    // ---- Environment (Dependency & Package Recovery Manager) ----

    fun checkEnvironment() {
        viewModelScope.launch(bgGuard) {
            runCatching { env.checkEnvironment() }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "env check", it) }
        }
    }

    fun repairPackageManager() {
        viewModelScope.launch(bgGuard) {
            runCatching { env.ensureHealthyOnly() }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "env repair", it) }
        }
    }

    fun installEnvProfile(profileId: String) {
        viewModelScope.launch(bgGuard) {
            PowerPolicy.opBegin(ctx)
            try {
                runCatching { env.installProfile(profileId) }
                    .onFailure { CrashReporter.logNonFatal(getApplication(), "env install profile", it) }
                refreshHomeFacts()
            } finally {
                PowerPolicy.opEnd(ctx)
            }
        }
    }

    // ---- Settings / storage: maintenance, backup & restore (issue #23) ----

    /** Guard: is a rootfs install currently running (downloads/staging in use)? */
    private fun installerBusy(): Boolean {
        val s = installer.state.value
        return s is RootfsInstaller.InstallState.Checking ||
            s is RootfsInstaller.InstallState.Downloading ||
            s is RootfsInstaller.InstallState.Verifying ||
            s is RootfsInstaller.InstallState.Extracting ||
            s is RootfsInstaller.InstallState.Configuring ||
            s is RootfsInstaller.InstallState.VerifyingInstall
    }

    // -- per-project caches (Projects screen) --

    fun projectCacheReport(name: String, onDone: (MaintenanceManager.Report?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val report = runCatching { maintenance.projectReport(name) }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "projectCacheReport", it) }
                .getOrNull()
            withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(report) }
        }
    }

    fun clearProjectCaches(name: String, dirs: List<File>, onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val freed = runCatching { maintenance.clearProjectCaches(name, dirs) }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "clearProjectCaches", it) }
                .getOrDefault(0L)
            refreshHomeFacts()
            // Deleting caches inside a project changes its git dirty count —
            // bypass the probe TTL cache so the Projects screen shows reality.
            refreshProjects(force = true)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onDone("Cleared project caches: ${LinuxRuntime.humanSize(freed)} freed")
            }
        }
    }

    // -- system caches (Settings screen) --

    fun systemCacheReport(onDone: (MaintenanceManager.Report?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val report = runCatching {
                maintenance.systemReport(
                    liveSessions = TerminalSessionRegistryLive(),
                    installerBusy = installerBusy(),
                )
            }.onFailure { CrashReporter.logNonFatal(getApplication(), "systemCacheReport", it) }
                .getOrNull()
            withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(report) }
        }
    }

    fun clearSystemCaches(items: List<MaintenanceManager.CacheItem>, onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            val freed = runCatching { maintenance.clearSystem(items) }
                .onFailure { CrashReporter.logNonFatal(getApplication(), "clearSystemCaches", it) }
                .getOrDefault(0L)
            refreshHomeFacts()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onDone("Cleared ${LinuxRuntime.humanSize(freed)} of caches")
            }
        }
    }

    /** Terminal live-session check that never touches the UI thread. */
    private fun TerminalSessionRegistryLive(): Boolean =
        runCatching { com.linuxify.app.terminal.TerminalSessionRegistry.hasLiveSessions() }.getOrDefault(false)

    // -- backup & restore --

    data class BackupUiState(
        val busy: Boolean = false,
        val progress: String? = null,
        val message: String? = null,
    )

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState

    fun createBackup(uri: Uri, includeRootfs: Boolean) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            _backupState.value = BackupUiState(busy = true, progress = "preparing…")
            PowerPolicy.opBegin(ctx)
            val scopes = buildSet {
                add(BackupManager.Scope.PROJECTS)
                add(BackupManager.Scope.TERMINAL)
                if (includeRootfs) add(BackupManager.Scope.ROOTFS)
            }
            try {
                val result = runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        backup.createBackup(out, scopes) { p ->
                            _backupState.value = _backupState.value.copy(progress = p)
                        }
                    } ?: throw IllegalStateException("could not open the selected location for writing")
                }.getOrElse { Result.failure(it) }
                result.fold(
                    { m ->
                        _backupState.value = BackupUiState(
                            busy = false,
                            message = "Backup created — projects: ${m.projectNames.size}, terminal sessions: ${m.terminalCount}" +
                                if (m.rootfsIncluded) ", Linux environment included" else "",
                        )
                    },
                    { e ->
                        CrashReporter.logNonFatal(getApplication(), "createBackup", e)
                        _backupState.value = BackupUiState(busy = false, message = "Backup failed: ${e.message ?: e.javaClass.simpleName}")
                    },
                )
                refreshHomeFacts()
            } finally {
                PowerPolicy.opEnd(ctx)
            }
        }
    }

    fun inspectBackup(uri: Uri, onReady: (BackupManager.Manifest) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    backup.readManifest(input)
                } ?: throw IllegalStateException("could not open the selected file")
            }.getOrElse { Result.failure(IllegalStateException("reader closed", it)) }.fold(
                { m -> withContext(kotlinx.coroutines.Dispatchers.Main) { onReady(m) } },
                { e ->
                    CrashReporter.logNonFatal(getApplication(), "inspectBackup", e)
                    withContext(kotlinx.coroutines.Dispatchers.Main) { onError(e.message ?: e.javaClass.simpleName) }
                },
            )
        }
    }

    fun restoreBackup(uri: Uri, scopes: Set<BackupManager.Scope>, replaceProjects: Boolean) {
        viewModelScope.launch(Dispatchers.IO + bgGuard) {
            _backupState.value = BackupUiState(busy = true, progress = "preparing…")
            PowerPolicy.opBegin(ctx)
            try {
                val result = runCatching {
                    // Rootfs scope needs exclusive access to the environment.
                    if (BackupManager.Scope.ROOTFS in scopes) {
                        _backupState.value = _backupState.value.copy(progress = "stopping services & sessions…")
                        runCatching { services.shutdownAll() }
                        runCatching { com.linuxify.app.terminal.TerminalSessionRegistry.stopEverythingAndRelease() }
                    }
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        backup.restore(input, BackupManager.RestoreOptions(scopes, replaceProjects)) { p ->
                            _backupState.value = _backupState.value.copy(progress = p)
                        }
                    } ?: throw IllegalStateException("could not open the selected file")
                }.getOrElse { Result.failure(IllegalStateException("reader closed", it)) }
                result.fold(
                    { summary ->
                        // Terminal scope replaced the on-disk index — reload it so
                        // the in-memory view matches (only possible because the
                        // restore of terminal scope does not touch live sessions).
                        runCatching { TerminalPersistence.loadFromDisk(ctx) }
                        _backupState.value = BackupUiState(busy = false, message = summary)
                    },
                    { e ->
                        CrashReporter.logNonFatal(getApplication(), "restoreBackup", e)
                        _backupState.value = BackupUiState(busy = false, message = "Restore failed: ${e.message ?: e.javaClass.simpleName}")
                    },
                )
                refreshHomeFacts()
            } finally {
                PowerPolicy.opEnd(ctx)
            }
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
