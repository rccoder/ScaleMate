package app.scalemate.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.scalemate.BuildConfig
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class InstalledApp(
    val versionName: String,
    val versionCode: Int,
    val isPreview: Boolean,
    val packageName: String,
    val installerPackage: String?
)

data class CheckResult(
    val entries: List<ReleaseIndexEntry>,
    val checkedAt: Long,
    val sourceId: String
)

class UpdateRepository(
    private val context: Context,
    private val transport: UpdateTransport = UpdateTransport()
) {
    private val cache = UpdateCache(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    private val packageManager = context.packageManager
    private val updatePublicKey: PublicKey by lazy { loadUpdatePublicKey() }

    fun installedApp(): InstalledApp {
        val info = packageManager.getPackageInfo(context.packageName, 0)
        return InstalledApp(
            versionName = info.versionName ?: "",
            versionCode = info.versionCodeCompat(),
            isPreview = isPreviewVersionName(info.versionName ?: ""),
            packageName = context.packageName,
            installerPackage = packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        )
    }

    fun initializeChannel() {
        val installed = installedApp()
        cache.initializeChannel(
            if (installed.isPreview) UpdateChannel.PREVIEW else UpdateChannel.STABLE
        )
    }

    fun selectedChannel(): UpdateChannel = cache.selectedChannel()
    fun selectedSource(): String = cache.selectedSource()
    fun lastCheckAt(): Long = cache.lastCheckAt()
    fun autoCheck(): Boolean = cache.autoCheck()
    fun autoDownload(): Boolean = cache.autoDownload()
    fun unmeteredOnly(): Boolean = cache.unmeteredOnly()

    suspend fun check(
        requestedChannel: UpdateChannel,
        onSourceFailure: (UpdateSource, String) -> Unit = { _, _ -> }
    ): CheckResult = withContext(Dispatchers.IO) {
        val (entries, sourceId) = ReleaseIndexLoader(
            transport,
            updatePublicKey
        )
            // The selected route controls APK transfer only. Discovery must
            // stay on API-capable routes; some working file proxies block API.
            .fetch(
                requestedChannel,
                cache.selectedSource(),
                UpdateSources.AUTO,
                onSourceFailure
            )
        val now = System.currentTimeMillis()
        cache.saveCheck(entries.maxByOrNull { it.versionCode }, "signed-manifest", sourceId, now)
        CheckResult(
            entries = entries,
            checkedAt = now,
            sourceId = sourceId
        )
    }

    suspend fun loadManifest(entry: ReleaseIndexEntry): UpdateManifest =
        withContext(Dispatchers.IO) {
            val selected = cache.selectedSource()
            val sources = UpdateSources.assetOrder(selected)
            var last: Exception? = null
            for (candidateSource in sources) {
                currentCoroutineContext().ensureActive()
                try {
                    val manifestUrl = UpdateSources.assetUrl(
                        candidateSource,
                        entry.tag,
                        UPDATE_MANIFEST_NAME
                    )
                    val signatureUrl = UpdateSources.assetUrl(
                        candidateSource,
                        entry.tag,
                        UPDATE_SIGNATURE_NAME
                    )
                    val raw = transport.downloadBytes(manifestUrl, 128 * 1024)
                    val manifest = UpdateManifestVerifier(updatePublicKey).verify(
                        raw,
                        transport.getText(signatureUrl, 64 * 1024)
                    )
                    validateManifestAgainstIndex(manifest, entry)
                    cache.saveManifest(manifest, raw.toString(Charsets.UTF_8), sha256Hex(raw))
                    return@withContext manifest
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    last = error
                }
            }
            throw last ?: UpdateFailure(UpdateFailure.Kind.INVALID, "Update manifest unavailable")
        }

    suspend fun download(
        candidate: UpdateCandidate,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
        onRoute: (UpdateSource) -> Unit = {},
        onVerifying: () -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val selected = cache.selectedSource()
        val sources = UpdateSources.assetOrder(selected)
        val file = File(context.filesDir, "updates/${candidate.entry.tag}/$UPDATE_APK_NAME")
        currentCoroutineContext().ensureActive()
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000L) {
            onVerifying()
            if (runCatching { verifyDownloadedApk(file, candidate.manifest) }.isSuccess) return@withContext file
        }
        File(context.filesDir, "updates").listFiles()?.filter { it != file.parentFile }
            ?.forEach { it.deleteRecursively() }
        val errors = mutableListOf<String>()
        for (source in sources) {
            try {
                onRoute(source)
                currentCoroutineContext().ensureActive()
                val url = UpdateSources.assetUrl(source, candidate.entry.tag)
                require(isAllowedReleaseAssetUrl(url)) { "Unknown download host" }
                transport.downloadTo(url, file, onProgress, isCancelled)
                currentCoroutineContext().ensureActive()
                onVerifying()
                verifyDownloadedApk(file, candidate.manifest)
                return@withContext file
            } catch (error: CancellationException) {
                file.delete()
                throw error
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                file.delete()
                errors += "${source.id}: ${error.message}"
            }
        }
        throw UpdateFailure(
            UpdateFailure.Kind.NETWORK,
            "All download sources failed: ${errors.joinToString("; ").take(500)}"
        )
    }

    suspend fun install(file: File, manifest: UpdateManifest) {
        withContext(Dispatchers.IO) { verifyDownloadedApk(file, manifest) }
        withContext(Dispatchers.Main) {
            if (!packageManager.canRequestPackageInstalls()) {
                requestInstallPermission()
                throw UpdateFailure(
                    UpdateFailure.Kind.INSTALL,
                    "Allow installs from ScaleMate, then retry."
                )
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.foodphotos", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun requestInstallPermission() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    fun updateSettings(autoCheck: Boolean, autoDownload: Boolean, unmeteredOnly: Boolean) =
        cache.saveSettings(autoCheck, autoDownload, unmeteredOnly)

    fun selectSource(id: String) {
        require(id == UpdateSources.AUTO || UpdateSources.find(id) != null)
        cache.saveSource(id)
    }

    fun selectChannel(channel: UpdateChannel) = cache.saveChannel(channel)

    fun saveError(error: String) = cache.saveError(error)

    fun cachedEntry(): ReleaseIndexEntry? = cache.cachedEntry()

    private fun verifyDownloadedApk(file: File, manifest: UpdateManifest) {
        require(file.length() == manifest.apkSizeBytes) { "APK size differs from signed manifest" }
        val digest = sha256File(file)
        require(digest == manifest.apkSha256) { "Downloaded APK SHA-256 differs from signed manifest" }
        val archive = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        ) ?: throw IllegalArgumentException("Android cannot read the downloaded APK")
        val installed = installedApp()
        val signerCertificates = apkSignerCertificates(archive.signingInfo)
        val signerDigests = signerCertificates
            ?.map { sha256Hex(it.toByteArray()) }
            .orEmpty()
        verifyApkArchive(
            metadata = ApkMetadata(
                packageName = archive.packageName,
                versionName = archive.versionName.orEmpty(),
                versionCode = PackageInfoCompat.getLongVersionCode(archive).toInt(),
                debuggable = archive.applicationInfo == null ||
                    archive.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
                installerPackage = null
            ),
            expected = manifest,
            installedVersionCode = installed.versionCode,
            installedPackageName = installed.packageName,
            signerDigests = signerDigests
        )
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadUpdatePublicKey(): PublicKey {
        val encoded = BuildConfig.UPDATE_PUBLIC_KEY_BASE64
        require(encoded.isNotBlank()) { "Update public key is not configured" }
        return KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(java.util.Base64.getDecoder().decode(encoded)))
    }

    private fun PackageInfo.versionCodeCompat(): Int =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode.toInt() else versionCode
}

// Some Android versions expose the current certificate through signing history.
internal fun apkSignerCertificates(info: SigningInfo?): Array<android.content.pm.Signature>? =
    info?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info?.signingCertificateHistory
