package cz.adamkruzela.core2ota.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.adamkruzela.core2ota.ble.BondedCore2Devices
import cz.adamkruzela.core2ota.ble.Core2Device
import cz.adamkruzela.core2ota.catalog.CatalogEntry
import cz.adamkruzela.core2ota.catalog.DriveDownloads
import cz.adamkruzela.core2ota.catalog.FirmwareCatalog
import cz.adamkruzela.core2ota.catalog.ModifiedCatalog
import cz.adamkruzela.core2ota.catalog.ModifiedPackage
import cz.adamkruzela.core2ota.data.FlashMode
import cz.adamkruzela.core2ota.data.Prefs
import cz.adamkruzela.core2ota.mds.MdsEngine
import cz.adamkruzela.core2ota.ota.FlashService
import cz.adamkruzela.core2ota.ota.OtaProgress
import cz.adamkruzela.core2ota.ota.OtaSession
import cz.adamkruzela.core2ota.ota.SecretMenuSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val hasBtPermission: Boolean = false,
    val btEnabled: Boolean = false,
    val devices: List<Core2Device> = emptyList(),
    val selectedMac: String? = null,
    val mode: FlashMode = FlashMode.ORIGINAL,
    val firmwares: List<CatalogEntry> = emptyList(),
    val selectedFirmwareUri: String? = null,
    val modifiedPackages: List<ModifiedPackage> = emptyList(),
    val selectedModifiedName: String? = null,
    val versionNotes: String? = null,
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val flashing: Boolean = false,
    /** MDS busy for Secret menu (or other non-flash ops). */
    val secretBusy: Boolean = false,
    val status: String = "Idle",
    val downloadPercent: Int? = null,
    val transferPercent: Int? = null,
    val transferPart: String? = null,
    /** PROGRESS reported by the watch (0–100). */
    /** Version announced; the watch usually reboots before the first READ. */
    val waitingForWatch: Boolean = false,
    val watchPercent: Int? = null,
    /** mcumgr upload of the Bluetooth package to the radio. */
    val dfuPercent: Int? = null,
    val dfuStage: String? = null,
    val engine: EngineUi = EngineUi(),
    val fatalError: String? = null,
    val success: String? = null,
    /** Absolute path of last MDS communication transcript for firmware agent. */
    val commLogPath: String? = null,
) {
    val busy: Boolean get() = flashing || secretBusy
}

data class EngineUi(
    val activeKind: MdsEngine.Kind? = null,
    val activeDescription: String = "not loaded",
    val note: String? = null,
    val choice: MdsEngine.Choice = MdsEngine.Choice.AUTO,
    val restartNeeded: Boolean = false,
    /** Installed official Suunto app version, null when not installed. */
    val suuntoApp: String? = null,
)

class FlasherViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val catalog = FirmwareCatalog()
    private val modifiedCatalog = ModifiedCatalog()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var flashJob: Job? = null
    private var secretJob: Job? = null
    private var activeSession: OtaSession? = null
    private var activeSecret: SecretMenuSession? = null

    private val startupChoice = MdsEngine.choice(app)

    init {
        refreshEngine()
        viewModelScope.launch {
            prefs.lastDeviceMac.collect { mac ->
                _state.update { it.copy(selectedMac = mac ?: it.selectedMac) }
            }
        }
        viewModelScope.launch {
            // Restore last mode, then load matching catalog once at startup.
            prefs.flashMode.collect { mode ->
                val previous = _state.value.mode
                _state.update { it.copy(mode = mode) }
                // First emission (or external prefs change) loads; setMode() loads on user switch.
                if (previous != mode ||
                    (_state.value.firmwares.isEmpty() && _state.value.modifiedPackages.isEmpty() &&
                        !_state.value.catalogLoading)
                ) {
                    when (mode) {
                        FlashMode.ORIGINAL -> loadCatalog()
                        FlashMode.MODIFIED -> loadModified()
                    }
                }
            }
        }
    }

    fun refreshEngine() {
        val ctx = getApplication<Application>()
        val loaded = MdsEngine.current()
        val choice = MdsEngine.choice(ctx)
        _state.update {
            it.copy(
                engine =
                    EngineUi(
                        activeKind = loaded?.kind,
                        activeDescription = loaded?.description ?: "not loaded",
                        note = loaded?.note,
                        choice = choice,
                        restartNeeded = choice != startupChoice,
                        suuntoApp = MdsEngine.suuntoAppVersion(ctx),
                    ),
            )
        }
    }

    fun setEngine(choice: MdsEngine.Choice) {
        if (_state.value.busy) return
        MdsEngine.setChoice(getApplication(), choice)
        refreshEngine()
    }

    fun onPermissionsResult(granted: Boolean) {
        _state.update { it.copy(hasBtPermission = granted) }
        if (granted) refreshDevices()
    }

    fun refreshDevices() {
        val ctx = getApplication<Application>()
        val adapter = BondedCore2Devices.adapter(ctx)
        val enabled = adapter?.isEnabled == true
        val devices =
            if (_state.value.hasBtPermission) BondedCore2Devices.list(ctx) else emptyList()
        _state.update {
            val sel =
                when {
                    it.selectedMac != null && devices.any { d -> d.address == it.selectedMac } ->
                        it.selectedMac
                    devices.isNotEmpty() -> devices.first().address
                    else -> null
                }
            it.copy(btEnabled = enabled, devices = devices, selectedMac = sel)
        }
        val mac = _state.value.selectedMac
        if (mac != null) viewModelScope.launch { prefs.setLastDeviceMac(mac) }
    }

    fun ensureBluetoothOn(): Intent? {
        val adapter = BondedCore2Devices.adapter(getApplication())
        if (adapter == null) return null
        if (adapter.isEnabled) {
            _state.update { it.copy(btEnabled = true) }
            return null
        }
        return Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    }

    fun selectDevice(mac: String) {
        _state.update { it.copy(selectedMac = mac) }
        viewModelScope.launch { prefs.setLastDeviceMac(mac) }
    }

    fun setMode(mode: FlashMode) {
        val previous = _state.value.mode
        _state.update { it.copy(mode = mode) }
        viewModelScope.launch { prefs.setFlashMode(mode) }
        // Always (re)load on switch — Original ↔ Modified, and on re-select after start.
        if (mode != previous || _state.value.firmwares.isEmpty() && _state.value.modifiedPackages.isEmpty()) {
            when (mode) {
                FlashMode.ORIGINAL -> loadCatalog()
                FlashMode.MODIFIED -> loadModified()
            }
        }
    }

    fun selectFirmware(uri: String) {
        _state.update { it.copy(selectedFirmwareUri = uri, versionNotes = null) }
    }

    fun selectModified(name: String) {
        val pkg = _state.value.modifiedPackages.firstOrNull { it.name == name } ?: return
        _state.update {
            it.copy(
                selectedModifiedName = name,
                selectedFirmwareUri = pkg.downloadUri,
                versionNotes = "Loading Version.txt…",
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { modifiedCatalog.fetchVersionNotes(pkg) }
            }
                .onSuccess { notes ->
                    _state.update { s ->
                        s.copy(
                            versionNotes = notes,
                            modifiedPackages =
                                s.modifiedPackages.map {
                                    if (it.name == name) it.copy(versionNotes = notes) else it
                                },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(versionNotes = "Failed to load Version.txt: ${e.message}")
                    }
                }
        }
    }

    fun loadCatalog() {
        if (_state.value.mode != FlashMode.ORIGINAL) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    catalogLoading = true,
                    catalogError = null,
                    versionNotes = null,
                    modifiedPackages = emptyList(),
                    selectedModifiedName = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) { catalog.fetchHistory() }
            }
                .onSuccess { list ->
                    _state.update {
                        it.copy(
                            catalogLoading = false,
                            firmwares = list,
                            selectedFirmwareUri = list.firstOrNull()?.uri,
                            catalogError = if (list.isEmpty()) "Catalog returned no builds" else null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            catalogLoading = false,
                            firmwares = emptyList(),
                            catalogError = e.message ?: e.toString(),
                        )
                    }
                }
        }
    }

    fun loadModified() {
        if (_state.value.mode != FlashMode.MODIFIED) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    catalogLoading = true,
                    catalogError = null,
                    firmwares = emptyList(),
                    versionNotes = null,
                    status = "Scanning Drive for Corium_* packages…",
                )
            }
            runCatching {
                withContext(Dispatchers.IO) { modifiedCatalog.fetchIndex() }
            }
                .onSuccess { list ->
                    val first = list.firstOrNull()
                    _state.update {
                        it.copy(
                            catalogLoading = false,
                            modifiedPackages = list,
                            selectedModifiedName = first?.name,
                            selectedFirmwareUri = first?.downloadUri,
                            catalogError =
                                if (list.isEmpty()) {
                                    "No Corium_* folders in Drive mirror " +
                                        "(add Corium_X.Y.Z with Version.txt + ZIP)"
                                } else {
                                    null
                                },
                            status =
                                if (list.isEmpty()) {
                                    "No modified packages"
                                } else {
                                    "Found ${list.size} package(s) — select one"
                                },
                        )
                    }
                    first?.let { selectModified(it.name) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            catalogLoading = false,
                            modifiedPackages = emptyList(),
                            catalogError = e.message ?: e.toString(),
                            status = "Failed to load Drive index",
                        )
                    }
                }
        }
    }

    fun reloadCurrentCatalog() {
        when (_state.value.mode) {
            FlashMode.ORIGINAL -> loadCatalog()
            FlashMode.MODIFIED -> loadModified()
        }
    }

    fun startSecretMenu(exitToNormal: Boolean = false) {
        val s = _state.value
        if (s.busy) return
        val mac = s.selectedMac
        if (mac.isNullOrBlank()) {
            _state.update { it.copy(fatalError = "Select a bonded Suunto Core 2 first.") }
            return
        }
        secretJob?.cancel()
        secretJob =
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        secretBusy = true,
                        fatalError = null,
                        success = null,
                        status =
                            if (exitToNormal) {
                                "Exiting test mode…"
                            } else {
                                "Opening Secret / Test menu…"
                            },
                        // Keep prior log path until the new session file is ready.
                    )
                }
                val session = SecretMenuSession(getApplication())
                activeSecret = session
                _state.update { it.copy(commLogPath = session.commLogPath) }
                val target =
                    if (exitToNormal) {
                        SecretMenuSession.Target.NORMAL
                    } else {
                        SecretMenuSession.Target.TEST_MENU
                    }
                try {
                    session.setMode(mac, target) { progress ->
                        when (progress) {
                            is SecretMenuSession.Progress.Status ->
                                _state.update {
                                    it.copy(
                                        status = progress.message,
                                        commLogPath = session.commLogPath,
                                    )
                                }
                            is SecretMenuSession.Progress.Done ->
                                _state.update {
                                    it.copy(
                                        secretBusy = false,
                                        success = progress.message,
                                        status = progress.message,
                                        commLogPath = progress.logFile?.absolutePath
                                            ?: session.commLogPath,
                                    )
                                }
                            is SecretMenuSession.Progress.Error ->
                                _state.update {
                                    it.copy(
                                        secretBusy = false,
                                        fatalError = progress.message,
                                        status = "Secret menu failed",
                                        commLogPath = progress.logFile?.absolutePath
                                            ?: session.commLogPath,
                                    )
                                }
                        }
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    val log = runCatching { session.abort("cancelled") }.getOrNull()
                    _state.update {
                        it.copy(
                            secretBusy = false,
                            status = "Cancelled",
                            commLogPath = log?.absolutePath ?: session.commLogPath,
                        )
                    }
                } catch (t: Throwable) {
                    val log = runCatching { session.abort("error: ${t.message}") }.getOrNull()
                    _state.update {
                        it.copy(
                            secretBusy = false,
                            fatalError = t.message ?: t.toString(),
                            status = "Secret menu failed",
                            commLogPath = log?.absolutePath ?: session.commLogPath,
                        )
                    }
                } finally {
                    if (activeSecret === session) activeSecret = null
                }
            }
    }

    fun startFlash() {
        val s = _state.value
        if (s.busy) return
        val mac = s.selectedMac
        if (mac.isNullOrBlank()) {
            _state.update { it.copy(fatalError = "Select a bonded Suunto Core 2 first.") }
            return
        }

        when (s.mode) {
            FlashMode.ORIGINAL -> {
                val uri = s.selectedFirmwareUri
                if (uri.isNullOrBlank()) {
                    _state.update { it.copy(fatalError = "Select an Original firmware build.") }
                    return
                }
                runFlash(mac) { session, cache, onProgress ->
                    session.flashOriginal(
                        macAddress = mac,
                        downloadUri = uri,
                        cacheDir = cache,
                        onProgress = onProgress,
                    )
                }
            }
            FlashMode.MODIFIED -> {
                val name = s.selectedModifiedName
                val pkg = s.modifiedPackages.firstOrNull { it.name == name }
                if (pkg == null) {
                    _state.update { it.copy(fatalError = "Select a Modified Corium package.") }
                    return
                }
                runFlash(mac) { session, cache, onProgress ->
                    onProgress(OtaProgress.Status("Downloading ${pkg.name} from Drive…"))
                    val zip =
                        withContext(Dispatchers.IO) {
                            val dest = File(cache, pkg.zipFileName)
                            DriveDownloads.download(pkg.zipFileId, dest) { downloaded, total ->
                                val pct =
                                    if (total > 0) {
                                        ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                    } else {
                                        0
                                    }
                                onProgress(OtaProgress.Download(pct))
                            }
                        }
                    session.flashLocalZip(
                        macAddress = mac,
                        zipFile = zip,
                        packageLabel = pkg.name,
                        onProgress = onProgress,
                    )
                }
            }
        }
    }

    private fun runFlash(
        mac: String,
        block: suspend (
            session: OtaSession,
            cache: File,
            onProgress: (OtaProgress) -> Unit,
        ) -> Unit,
    ) {
        flashJob?.cancel()
        flashJob =
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        flashing = true,
                        fatalError = null,
                        success = null,
                        downloadPercent = 0,
                        transferPercent = null,
                        transferPart = null,
                        watchPercent = null,
                        dfuPercent = null,
                        dfuStage = null,
                        waitingForWatch = false,
                        status = "Starting…",
                        // Keep prior log path until the new session file is ready.
                    )
                }
                val app = getApplication<Application>()
                val cache = app.cacheDir
                val session = OtaSession(app)
                activeSession = session
                // Expose log path immediately so Share works even if Cancel mid-connect.
                _state.update { it.copy(commLogPath = session.commLogPath) }
                FlashService.start(app, "Updating the watch… keep this app open")
                try {
                    block(session, cache) { progress ->
                        when (progress) {
                            is OtaProgress.Status -> {
                                FlashService.start(app, progress.message)
                                _state.update {
                                    it.copy(
                                        status = progress.message,
                                        commLogPath = session.commLogPath,
                                    )
                                }
                            }
                            is OtaProgress.Waiting -> {
                                FlashService.start(app, progress.message)
                                _state.update {
                                    it.copy(
                                        waitingForWatch = true,
                                        status = progress.message,
                                        commLogPath = session.commLogPath,
                                    )
                                }
                            }
                            is OtaProgress.Download -> {
                                FlashService.start(app, "Downloading firmware ${progress.percent} %")
                                _state.update {
                                    it.copy(
                                        downloadPercent = progress.percent,
                                        status = "Downloading…",
                                        commLogPath = session.commLogPath,
                                    )
                                }
                            }
                            is OtaProgress.Transfer -> {
                                if (progress.percent % 10 == 0) {
                                    FlashService.start(app, "Watch reading ${progress.partLabel} ${progress.percent} %")
                                }
                                _state.update {
                                    it.copy(
                                        waitingForWatch = false,
                                        transferPart = progress.partLabel,
                                        transferPercent = progress.percent,
                                        status = "Watch is reading ${progress.partLabel}",
                                        commLogPath = session.commLogPath,
                                    )
                                }
                            }
                            is OtaProgress.WatchProgress -> {
                                FlashService.start(app, "Watch update ${progress.percent} %")
                                _state.update {
                                    it.copy(
                                        waitingForWatch = false,
                                        watchPercent = progress.percent,
                                        status = "Watch reports ${progress.percent} %",
                                        commLogPath = session.commLogPath,
                                    )
                                }
                            }
                            is OtaProgress.Dfu -> {
                                if (progress.percent % 10 == 0) {
                                    FlashService.start(app, "Bluetooth radio ${progress.stage} ${progress.percent} %")
                                }
                                _state.update {
                                    it.copy(
                                        dfuPercent = progress.percent,
                                        dfuStage = progress.stage,
                                        status = "Bluetooth radio DFU: ${progress.stage}",
                                        commLogPath = session.commLogPath,
                                    )
                                }
                            }
                            is OtaProgress.Done ->
                                _state.update {
                                    it.copy(
                                        flashing = false,
                                        success = progress.message,
                                        status = "Done — share the comm log with firmware agent",
                                        watchPercent = 100,
                                        commLogPath = progress.commLogFile?.absolutePath
                                            ?: session.commLogPath,
                                    )
                                }
                            is OtaProgress.Error ->
                                _state.update {
                                    it.copy(
                                        flashing = false,
                                        fatalError = progress.message,
                                        status = "Failed — share the comm log with firmware agent",
                                        commLogPath = progress.commLogFile?.absolutePath
                                            ?: session.commLogPath,
                                    )
                                }
                        }
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    val log = runCatching { session.abort("job cancelled") }.getOrNull()
                    _state.update {
                        it.copy(
                            flashing = false,
                            status = "Cancelled — share the comm log",
                            commLogPath = log?.absolutePath ?: session.commLogPath,
                        )
                    }
                } catch (t: Throwable) {
                    val log = runCatching { session.abort("error: ${t.message}") }.getOrNull()
                    _state.update {
                        it.copy(
                            flashing = false,
                            fatalError = t.message ?: t.toString(),
                            status = "Failed — share the comm log",
                            commLogPath = log?.absolutePath ?: session.commLogPath,
                        )
                    }
                } finally {
                    FlashService.stop(app)
                    if (activeSession === session) activeSession = null
                }
            }
    }

    fun cancelFlash() {
        val session = activeSession
        val secret = activeSecret
        // Disconnect BLE/MDS first so we are not stuck waiting for Whiteboard serial.
        val logFlash = runCatching { session?.abort("user Cancel") }.getOrNull()
        val logSecret = runCatching { secret?.abort("user Cancel") }.getOrNull()
        flashJob?.cancel()
        secretJob?.cancel()
        flashJob = null
        secretJob = null
        activeSession = null
        activeSecret = null
        _state.update {
            it.copy(
                flashing = false,
                secretBusy = false,
                status = "Cancelled — share the comm log",
                fatalError = null,
                commLogPath = logFlash?.absolutePath
                    ?: logSecret?.absolutePath
                    ?: session?.commLogPath
                    ?: secret?.commLogPath
                    ?: it.commLogPath,
            )
        }
    }
}
