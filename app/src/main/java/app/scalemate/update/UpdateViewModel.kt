package app.scalemate.update

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.scalemate.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update

enum class UpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    WAITING_INSTALL,
    INSTALLING,
    ERROR
}

data class UpdateUiState(
    val loaded: Boolean = false,
    val installed: InstalledApp? = null,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val sourceId: String = UpdateSources.AUTO,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val candidates: List<ReleaseIndexEntry> = emptyList(),
    val entry: ReleaseIndexEntry? = null,
    val manifest: UpdateManifest? = null,
    val lastCheckAt: Long = 0L,
    val checkedSource: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String = "",
    val autoCheck: Boolean = true,
    val autoDownload: Boolean = false,
    val unmeteredOnly: Boolean = true,
    val canInstallPackages: Boolean = false,
    val probingRoutes: Boolean = false,
    val probeTag: String = "",
    val routeProbes: Map<String, RouteProbeResult> = emptyMap(),
    val showChannelWarning: Boolean = false
) {
    val progress: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val hasUpdate: Boolean get() = entry?.let { candidate ->
        installed?.let { app ->
            candidate.versionCode > app.versionCode &&
                (channel == UpdateChannel.PREVIEW || candidate.channel == UpdateChannel.STABLE)
        }
    } == true

}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(UpdateUiState())
    val state = mutable.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var probeJob: Job? = null
    private var lastAutoCheckAt = 0L
    private var verifiedFile: File? = null
    private var pendingForegroundCheck = false

    init {
        viewModelScope.launch {
            if (getApplication<Application>().packageName.endsWith(".fooddev")) {
                mutable.value = UpdateUiState(loaded = true)
                return@launch
            }
            repository.initializeChannel()
            val installed = repository.installedApp()
            val channel = repository.selectedChannel()
            val cached = repository.cachedEntry()
            val available = cached?.let {
                selectCandidate(
                    ReleaseIndex(UPDATE_SCHEMA_VERSION, "", listOf(it)),
                    installed.toInstalledRelease(),
                    channel
                )
            }
            mutable.value = UpdateUiState(
                loaded = true,
                installed = installed,
                channel = channel,
                sourceId = repository.selectedSource(),
                candidates = listOfNotNull(cached),
                entry = available,
                phase = if (available != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE,
                lastCheckAt = repository.lastCheckAt(),
                autoCheck = repository.autoCheck(),
                autoDownload = repository.autoDownload(),
                unmeteredOnly = repository.unmeteredOnly(),
                canInstallPackages = repository.canInstallPackages()
            )
            if (pendingForegroundCheck) {
                pendingForegroundCheck = false
                foreground()
            }
        }
    }

    fun refreshInstallPermission() {
        mutable.value = mutable.value.copy(canInstallPackages = repository.canInstallPackages())
    }

    fun requestInstallPermission() {
        runCatching { repository.requestInstallPermission() }.onFailure {
            mutable.value = mutable.value.copy(
                message = getApplication<Application>().getString(R.string.update_error_install)
            )
        }
    }

    fun foreground() {
        if (!mutable.value.loaded) {
            pendingForegroundCheck = true
            return
        }
        if (mutable.value.installed?.packageName?.endsWith(".fooddev") == true) return
        if (mutable.value.installed == null) return
        val now = System.currentTimeMillis()
        val interval = if (mutable.value.channel == UpdateChannel.PREVIEW) {
            6 * 60 * 60 * 1000L
        } else {
            24 * 60 * 60 * 1000L
        }
        if (mutable.value.autoCheck &&
            now - maxOf(mutable.value.lastCheckAt, lastAutoCheckAt) >= interval
        ) {
            check()
        }
    }

    fun check() {
        if (!mutable.value.loaded || mutable.value.installed == null) return
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        mutable.value = mutable.value.copy(
            phase = UpdatePhase.CHECKING,
            message = "",
            downloadedBytes = 0,
            totalBytes = 0
        )
        val requestedChannel = mutable.value.channel
        checkJob = viewModelScope.launch {
            try {
                val result = repository.check(requestedChannel)
                val installed = mutable.value.installed ?: repository.installedApp()
                val selected = selectCandidate(
                    ReleaseIndex(UPDATE_SCHEMA_VERSION, "", result.entries),
                    installed.toInstalledRelease(),
                    mutable.value.channel
                )
                if (selected == null) {
                    mutable.value = mutable.value.copy(
                        phase = UpdatePhase.UP_TO_DATE,
                        candidates = result.entries,
                        entry = null,
                        manifest = null,
                        lastCheckAt = result.checkedAt,
                        checkedSource = result.sourceId,
                        message = ""
                    )
                } else {
                    mutable.value = mutable.value.copy(
                        phase = UpdatePhase.AVAILABLE,
                        candidates = result.entries,
                        entry = selected,
                        manifest = null,
                        lastCheckAt = result.checkedAt,
                        checkedSource = result.sourceId,
                        message = ""
                    )
                    if (mutable.value.autoDownload) autoDownloadIfAllowed()
                }
                lastAutoCheckAt = result.checkedAt
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                repository.saveError(error.message ?: "check failed")
                mutable.value = mutable.value.copy(
                    phase = if (mutable.value.entry != null) UpdatePhase.AVAILABLE else UpdatePhase.ERROR,
                    candidates = mutable.value.candidates,
                    message = localizedUpdateError(error)
                )
            }
        }
    }

    fun selectChannel(channel: UpdateChannel) {
        if (checkJob?.isActive == true || downloadJob?.isCompleted == false) return
        if (channel == mutable.value.channel) return
        if (channel == UpdateChannel.PREVIEW) {
            mutable.value = mutable.value.copy(showChannelWarning = true)
            return
        }
        selectStableIfAllowed()
    }

    fun confirmPreviewChannel() {
        repository.selectChannel(UpdateChannel.PREVIEW)
        mutable.value = mutable.value.copy(
            channel = UpdateChannel.PREVIEW,
            showChannelWarning = false,
            entry = null,
            manifest = null,
            phase = UpdatePhase.IDLE
        )
        check()
    }

    private fun selectStableIfAllowed() {
        val installed = mutable.value.installed ?: return
        val newerStable = selectCandidate(
            ReleaseIndex(UPDATE_SCHEMA_VERSION, "", mutable.value.candidates),
            installed.toInstalledRelease(),
            UpdateChannel.STABLE
        )
        if (installed.isPreview &&
            (newerStable == null || newerStable.versionCode <= installed.versionCode)
        ) {
            mutable.value = mutable.value.copy(
                message = getApplication<Application>().getString(R.string.update_stable_wait)
            )
            return
        }
        verifiedFile = null
        repository.selectChannel(UpdateChannel.STABLE)
        mutable.value = mutable.value.copy(
            channel = UpdateChannel.STABLE,
            entry = newerStable,
            manifest = null,
            phase = if (newerStable != null) UpdatePhase.AVAILABLE else UpdatePhase.UP_TO_DATE,
            message = ""
        )
        check()
    }

    fun dismissChannelWarning() {
        mutable.value = mutable.value.copy(showChannelWarning = false)
    }

    fun selectSource(id: String) {
        cancelRouteProbe()
        repository.selectSource(id)
        mutable.value = mutable.value.copy(sourceId = id, message = "")
    }

    fun probeRoutes() {
        if (probeJob?.isCompleted == false || downloadJob?.isCompleted == false) return
        val tag = mutable.value.entry?.tag ?: mutable.value.installed?.versionName?.let { "v$it" }
            ?: return
        if (channelFromTag(tag) == null) return
        mutable.value = mutable.value.copy(probingRoutes = true, probeTag = tag, routeProbes = emptyMap())
        probeJob = viewModelScope.launch {
            try {
                val slots = Semaphore(2)
                coroutineScope {
                    UpdateSources.all.map { source -> async(Dispatchers.IO) {
                        slots.withPermit {
                            val operation = currentCoroutineContext()
                            val result = try {
                                UpdateRouteProbe().measure(source, tag) { !operation.isActive }
                            } catch (error: Exception) {
                                operation.ensureActive()
                                RouteProbeResult(source.id, System.currentTimeMillis(), failed = true)
                            }
                            operation.ensureActive()
                            mutable.update { it.copy(routeProbes = it.routeProbes + (source.id to result)) }
                        }
                    } }.awaitAll()
                }
            } finally {
                mutable.update { it.copy(probingRoutes = false) }
            }
        }
    }

    fun cancelRouteProbe() {
        probeJob?.cancel()
        mutable.update { it.copy(probingRoutes = false) }
    }

    fun setAutoCheck(value: Boolean) {
        repository.updateSettings(value, mutable.value.autoDownload, mutable.value.unmeteredOnly)
        mutable.value = mutable.value.copy(autoCheck = value)
    }

    fun setAutoDownload(value: Boolean) {
        repository.updateSettings(mutable.value.autoCheck, value, mutable.value.unmeteredOnly)
        mutable.value = mutable.value.copy(autoDownload = value)
        if (value && mutable.value.phase == UpdatePhase.AVAILABLE) {
            autoDownloadIfAllowed()
        }
    }

    fun setUnmeteredOnly(value: Boolean) {
        repository.updateSettings(mutable.value.autoCheck, mutable.value.autoDownload, value)
        mutable.value = mutable.value.copy(unmeteredOnly = value)
    }

    fun downloadAndInstall() = startDownload(installAfterDownload = true)

    private fun autoDownloadIfAllowed() {
        val state = mutable.value
        if (state.entry == null || state.phase != UpdatePhase.AVAILABLE) return
        if (state.unmeteredOnly && !onUnmeteredNetwork()) return
        startDownload(installAfterDownload = false)
    }

    private fun startDownload(installAfterDownload: Boolean) {
        val entry = mutable.value.entry ?: return
        if (checkJob?.isActive == true && mutable.value.phase == UpdatePhase.CHECKING) return
        if (downloadJob?.isCompleted == false) return
        cancelRouteProbe()
        verifiedFile = null
        mutable.value = mutable.value.copy(
            phase = UpdatePhase.DOWNLOADING,
            message = "",
            downloadedBytes = 0,
            totalBytes = entry.apkSizeBytes
        )
        downloadJob = viewModelScope.launch {
            val operation = currentCoroutineContext()
            try {
                val manifest = repository.loadManifest(entry)
                operation.ensureActive()
                val file = repository.download(
                    UpdateCandidate(entry, manifest),
                    onProgress = { downloaded, total ->
                        if (operation.isActive) mutable.update { state -> state.copy(
                            downloadedBytes = downloaded,
                            totalBytes = total.takeIf { it > 0 } ?: entry.apkSizeBytes,
                            phase = if (downloaded >= entry.apkSizeBytes) UpdatePhase.VERIFYING
                                else UpdatePhase.DOWNLOADING
                        ) }
                    },
                    isCancelled = { !operation.isActive }
                )
                operation.ensureActive()
                verifiedFile = file
                mutable.value = mutable.value.copy(
                    phase = UpdatePhase.WAITING_INSTALL,
                    manifest = manifest,
                    downloadedBytes = file.length(),
                    totalBytes = file.length(),
                    message = ""
                )
                if (installAfterDownload) install(file)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operation.ensureActive()
                mutable.value = mutable.value.copy(
                    phase = if (verifiedFile != null) UpdatePhase.WAITING_INSTALL else UpdatePhase.ERROR,
                    message = localizedUpdateError(error)
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        mutable.value = mutable.value.copy(
            phase = if (mutable.value.entry != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE,
            downloadedBytes = 0,
            totalBytes = 0,
            message = getApplication<Application>().getString(R.string.update_error_cancelled)
        )
    }

    fun installVerified() {
        val file = verifiedFile ?: return
        viewModelScope.launch {
            try {
                install(file)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(
                    phase = UpdatePhase.WAITING_INSTALL,
                    message = localizedUpdateError(error)
                )
            }
        }
    }

    fun retry() {
        if (mutable.value.entry == null) check() else downloadAndInstall()
    }

    fun onNetworkChanged(unmetered: Boolean) {
        if (unmetered && mutable.value.autoDownload && mutable.value.phase == UpdatePhase.AVAILABLE) {
            autoDownloadIfAllowed()
        }
    }

    private suspend fun install(file: File) {
        mutable.value = mutable.value.copy(phase = UpdatePhase.INSTALLING)
        repository.install(file, mutable.value.manifest ?: error("Missing verified manifest"))
        mutable.value = mutable.value.copy(phase = UpdatePhase.WAITING_INSTALL)
    }

    private fun onUnmeteredNetwork(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun InstalledApp.toInstalledRelease() =
        InstalledRelease(versionName, versionCode, isPreview, installerPackage)

    private fun localizedUpdateError(error: Exception): String {
        val context = getApplication<Application>()
        return when (error) {
            is UpdateFailure -> when (error.kind) {
                UpdateFailure.Kind.NETWORK -> context.getString(R.string.update_error_network)
                UpdateFailure.Kind.NOT_FOUND -> context.getString(R.string.update_error_not_found)
                UpdateFailure.Kind.INVALID -> context.getString(R.string.update_error_invalid)
                UpdateFailure.Kind.INSTALL -> context.getString(R.string.update_error_install)
            }
            is IllegalArgumentException, is java.security.GeneralSecurityException -> context.getString(R.string.update_error_invalid)
            is InterruptedException -> context.getString(R.string.update_error_cancelled)
            else -> context.getString(R.string.update_error_generic)
        }
    }
}
