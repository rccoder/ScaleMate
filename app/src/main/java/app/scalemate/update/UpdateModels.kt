package app.scalemate.update

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.json.JSONArray
import org.json.JSONObject

const val UPDATE_SCHEMA_VERSION = 1
const val UPDATE_PACKAGE_NAME = "app.scalemate"
const val UPDATE_APK_NAME = "ScaleMate.apk"
const val UPDATE_MANIFEST_NAME = "update.json"
const val UPDATE_SIGNATURE_NAME = "update.json.sig"
const val UPDATE_REPOSITORY_OWNER = "rccoder"
const val UPDATE_REPOSITORY_NAME = "ScaleMate"
const val UPDATE_RELEASE_INDEX_NAME = "releases.json"
const val UPDATE_SIGNATURE_ALGORITHM = "SHA256withRSA"
const val UPDATE_CERTIFICATE_SHA256 = "9836e7ea5e5dc782be63e7b21c39cb0fa9f6a9633d18952664e83cfaa8172ed5"

enum class UpdateChannel(val wireName: String) {
    STABLE("stable"),
    PREVIEW("preview");

    companion object {
        fun fromWire(value: String): UpdateChannel? = entries.firstOrNull { it.wireName == value }
    }
}

val UPDATE_TAG_PATTERN = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-preview\\.(\\d+))?$")

fun channelFromTag(tag: String): UpdateChannel? {
    val match = UPDATE_TAG_PATTERN.matchEntire(tag) ?: return null
    return if (match.groupValues[4].isEmpty()) UpdateChannel.STABLE else UpdateChannel.PREVIEW
}

fun isPreviewVersionName(versionName: String): Boolean =
    Regex("^\\d+\\.\\d+\\.\\d+-preview\\.\\d+$").matches(versionName)

data class ReleaseIndexEntry(
    val tag: String,
    val channel: UpdateChannel,
    val versionName: String,
    val versionCode: Int,
    val publishedAt: String,
    val prerelease: Boolean,
    val releaseUrl: String,
    val manifestUrl: String,
    val manifestSha256: String,
    val manifestSignatureUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val highlights: List<String> = emptyList(),
    val knownIssues: List<String> = emptyList(),
    val expectedVersionCode: Int = 0,
    val expectedSha256: String = ""
) {
    val isPreview: Boolean get() = channel == UpdateChannel.PREVIEW
}

data class ReleaseCandidate(
    val tag: String,
    val channel: UpdateChannel,
    val prerelease: Boolean,
    val publishedAt: String,
    val releaseUrl: String,
    val checkedAt: Long = 0L
) {
    fun toEntry(manifest: UpdateManifest): ReleaseIndexEntry {
        require(manifest.tag == tag) { "Manifest tag differs from release" }
        require(manifest.channel == channel) { "Manifest channel differs from release" }
        return ReleaseIndexEntry(
            tag = tag,
            channel = channel,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            publishedAt = manifest.publishedAt.ifBlank { publishedAt },
            prerelease = prerelease,
            releaseUrl = releaseUrl,
            manifestUrl = githubAssetUrl(tag, UPDATE_MANIFEST_NAME),
            manifestSha256 = "",
            manifestSignatureUrl = githubAssetUrl(tag, UPDATE_SIGNATURE_NAME),
            apkUrl = githubAssetUrl(tag, UPDATE_APK_NAME),
            apkSizeBytes = manifest.apkSizeBytes,
            apkSha256 = manifest.apkSha256,
            highlights = manifest.highlights,
            knownIssues = manifest.knownIssues
        )
    }
}

data class StoredRelease(
    val entry: ReleaseIndexEntry,
    val sourceId: String
)

data class ReleaseIndex(
    val schemaVersion: Int,
    val generatedAt: String,
    val releases: List<ReleaseIndexEntry>
) {
    fun latestEntry(channel: UpdateChannel): ReleaseIndexEntry? =
        releases.filter { it.channel == channel }
            .maxByOrNull { it.versionCode }

    companion object {
        fun parse(raw: String): ReleaseIndex {
            val json = try {
                JSONObject(raw)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid release index JSON", error)
            }
            require(json.optInt("schemaVersion", -1) == UPDATE_SCHEMA_VERSION) {
                "Unsupported release index schema"
            }
            val generatedAt = json.requiredString("generatedAt")
            val releaseArray = json.optJSONArray("releases")
                ?: throw IllegalArgumentException("Missing releases")
            require(releaseArray.length() in 1..20) { "Invalid release count" }
            val releases = buildList {
                for (index in 0 until releaseArray.length()) {
                    add(parseEntry(releaseArray.getJSONObject(index)))
                }
            }
            val tags = releases.map { it.tag }
            require(tags.size == tags.toSet().size) { "Duplicate release tag" }
            val codes = releases.map { it.versionCode }
            require(codes.size == codes.toSet().size) { "Duplicate release versionCode" }
            return ReleaseIndex(UPDATE_SCHEMA_VERSION, generatedAt, releases)
        }

        private fun parseEntry(json: JSONObject): ReleaseIndexEntry {
            val tag = json.requiredString("tag")
            val channel = UpdateChannel.fromWire(json.requiredString("channel"))
                ?: throw IllegalArgumentException("Invalid channel")
            require(channelFromTag(tag) == channel) { "Tag and channel differ" }
            val versionName = json.requiredString("versionName")
            require(tag == "v$versionName") { "Tag and versionName differ" }
            require(isPreviewVersionName(versionName) == (channel == UpdateChannel.PREVIEW)) {
                "Version name and channel differ"
            }
            val versionCode = json.optInt("versionCode", 0)
            require(versionCode >= 0) { "Invalid versionCode" }
            val releaseUrl = httpsUrl(json.requiredString("releaseUrl"), "releaseUrl")
            val manifestUrl = httpsUrl(json.requiredString("manifestUrl"), "manifestUrl")
            val manifestSignatureUrl = httpsUrl(
                json.requiredString("manifestSignatureUrl"),
                "manifestSignatureUrl"
            )
            val apkUrl = httpsUrl(json.requiredString("apkUrl"), "apkUrl")
            val apkSize = json.optLong("apkSizeBytes", 0)
            require(apkSize >= 0) { "Invalid APK size" }
            val manifestSha = optionalSha256(json.optString("manifestSha256", ""))
            val apkSha = optionalSha256(json.optString("apkSha256", ""))
            val prerelease = json.optBoolean("prerelease", channel == UpdateChannel.PREVIEW)
            require(prerelease == (channel == UpdateChannel.PREVIEW)) {
                "Prerelease flag and channel differ"
            }
            return ReleaseIndexEntry(
                tag = tag,
                channel = channel,
                versionName = versionName,
                versionCode = versionCode,
                publishedAt = json.requiredString("publishedAt"),
                prerelease = prerelease,
                releaseUrl = releaseUrl,
                manifestUrl = manifestUrl,
                manifestSha256 = manifestSha,
                manifestSignatureUrl = manifestSignatureUrl,
                apkUrl = apkUrl,
                apkSizeBytes = apkSize,
                apkSha256 = apkSha,
                highlights = json.stringList("highlights", 5),
                knownIssues = json.stringList("knownIssues", 10),
                expectedVersionCode = json.optInt("expectedVersionCode", 0),
                expectedSha256 = json.optString("expectedSha256", "").trim()
            )
        }
    }
}

data class UpdateManifestDocument(
    val manifest: UpdateManifest,
    val bytes: ByteArray
)

fun updateManifestBytes(
    channel: UpdateChannel,
    tag: String,
    versionName: String,
    versionCode: Int,
    publishedAt: String,
    highlights: List<String>,
    knownIssues: List<String>,
    apkSha256: String,
    apkSizeBytes: Long,
    sourceUrls: Map<String, String>
): ByteArray {
    require(channelFromTag(tag) == channel) { "Tag and channel differ" }
    require(tag == "v$versionName") { "Tag and versionName differ" }
    require(versionCode > 0) { "Invalid versionCode" }
    require(Regex("^[0-9a-fA-F]{64}$").matches(apkSha256)) { "Invalid APK SHA-256" }
    require(apkSizeBytes > 0) { "Invalid APK size" }
    require(sourceUrls.isNotEmpty()) { "At least one source is required" }
    if (channel == UpdateChannel.PREVIEW) {
        require(knownIssues.isNotEmpty()) { "Preview manifests require known issues" }
    }
    val sourceJson = JSONObject()
    sourceUrls.forEach { (id, url) ->
        require(id in setOf(
            "github-official",
            "gh-proxy-com",
            "ghfast-top",
            "ghproxy-net",
            "gh-llkk-cc",
            "gh-proxy-org"
        )) { "Unknown source id: $id" }
        require(httpsUrl(url, "sourceUrls.$id").endsWith("/$UPDATE_APK_NAME")) {
            "Source URL must point to ScaleMate.apk"
        }
        sourceJson.put(id, url)
    }
    return JSONObject().apply {
        put("schemaVersion", UPDATE_SCHEMA_VERSION)
        put("channel", channel.wireName)
        put("tag", tag)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("publishedAt", publishedAt)
        put("highlights", JSONArray(highlights.take(5)))
        put("knownIssues", JSONArray(knownIssues.take(10)))
        put("apk", JSONObject().apply {
            put("fileName", UPDATE_APK_NAME)
            put("sha256", apkSha256.lowercase())
            put("sizeBytes", apkSizeBytes)
        })
        put("sourceUrls", sourceJson)
    }.toString(2).toByteArray(Charsets.UTF_8)
}

/**
 * A minimal, deterministic index for the initial bootstrap. It uses the same
 * manifest bytes embedded in update.json and is signed with the release key.
 */
fun releaseIndexBytes(
    channel: UpdateChannel,
    tag: String,
    versionName: String,
    versionCode: Int,
    publishedAt: String,
    releaseUrl: String,
    manifestUrl: String,
    manifestBytes: ByteArray,
    manifestSignatureUrl: String,
    apkUrl: String,
    apkSizeBytes: Long,
    apkSha256: String,
    highlights: List<String>,
    knownIssues: List<String>
): ByteArray {
    val manifestSha = sha256Hex(manifestBytes)
    return JSONObject().apply {
        put("schemaVersion", UPDATE_SCHEMA_VERSION)
        put("generatedAt", publishedAt)
        put("releases", JSONArray().put(JSONObject().apply {
            put("tag", tag)
            put("channel", channel.wireName)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("publishedAt", publishedAt)
            put("prerelease", channel == UpdateChannel.PREVIEW)
            put("releaseUrl", httpsUrl(releaseUrl, "releaseUrl"))
            put("manifestUrl", httpsUrl(manifestUrl, "manifestUrl"))
            put("manifestSha256", manifestSha)
            put("manifestSignatureUrl", httpsUrl(manifestSignatureUrl, "manifestSignatureUrl"))
            put("apkUrl", httpsUrl(apkUrl, "apkUrl"))
            put("apkSizeBytes", apkSizeBytes)
            put("apkSha256", apkSha256.lowercase())
            put("highlights", JSONArray(highlights.take(5)))
            put("knownIssues", JSONArray(knownIssues.take(10)))
        }))
    }.toString(2).toByteArray(Charsets.UTF_8)
}

data class UpdateManifest(
    val schemaVersion: Int,
    val channel: UpdateChannel,
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val publishedAt: String,
    val highlights: List<String>,
    val knownIssues: List<String>,
    val apkFileName: String,
    val apkSha256: String,
    val apkSizeBytes: Long
) {
    val isPreview: Boolean get() = channel == UpdateChannel.PREVIEW

    companion object {
        fun parse(raw: String): UpdateManifest {
            val json = try {
                JSONObject(raw)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid update manifest JSON", error)
            }
            require(json.optInt("schemaVersion", -1) == UPDATE_SCHEMA_VERSION) {
                "Unsupported update manifest schema"
            }
            val channel = UpdateChannel.fromWire(json.requiredString("channel"))
                ?: throw IllegalArgumentException("Invalid update channel")
            val tag = json.requiredString("tag")
            require(channelFromTag(tag) == channel) { "Manifest tag and channel differ" }
            val versionName = json.requiredString("versionName")
            require(tag == "v$versionName") { "Manifest tag and versionName differ" }
            require(isPreviewVersionName(versionName) == (channel == UpdateChannel.PREVIEW)) {
                "Manifest version name and channel differ"
            }
            val versionCode = json.optInt("versionCode", 0)
            require(versionCode > 0) { "Invalid manifest versionCode" }
            val apk = json.optJSONObject("apk") ?: throw IllegalArgumentException("Missing APK")
            val fileName = apk.requiredString("fileName")
            require(fileName == UPDATE_APK_NAME) { "Unsupported APK file name" }
            val size = apk.optLong("sizeBytes", 0)
            require(size > 0) { "Invalid APK size" }
            val highlights = json.stringList("highlights", 5)
            val knownIssues = json.stringList("knownIssues", 10)
            if (channel == UpdateChannel.PREVIEW) {
                require(knownIssues.isNotEmpty()) { "Preview updates require known issues" }
            }
            return UpdateManifest(
                schemaVersion = UPDATE_SCHEMA_VERSION,
                channel = channel,
                tag = tag,
                versionName = versionName,
                versionCode = versionCode,
                publishedAt = json.requiredString("publishedAt"),
                highlights = highlights,
                knownIssues = knownIssues,
                apkFileName = fileName,
                apkSha256 = sha256(apk.requiredString("sha256"), "apk.sha256"),
                apkSizeBytes = size
            )
        }
    }
}

data class InstalledRelease(
    val versionName: String,
    val versionCode: Int,
    val isPreview: Boolean,
    val installerPackage: String?
)

data class UpdateCandidate(
    val entry: ReleaseIndexEntry,
    val manifest: UpdateManifest
)

fun manifestFromEntry(entry: ReleaseIndexEntry): UpdateManifest = UpdateManifest(
    schemaVersion = UPDATE_SCHEMA_VERSION,
    channel = entry.channel,
    tag = entry.tag,
    versionName = entry.versionName,
    versionCode = entry.versionCode,
    publishedAt = entry.publishedAt,
    highlights = entry.highlights,
    knownIssues = entry.knownIssues.ifEmpty {
        if (entry.channel == UpdateChannel.PREVIEW) {
            listOf("Preview builds may be unstable.")
        } else {
            emptyList()
        }
    },
    apkFileName = UPDATE_APK_NAME,
    apkSha256 = entry.apkSha256,
    apkSizeBytes = entry.apkSizeBytes
)

fun selectCandidate(
    index: ReleaseIndex,
    installed: InstalledRelease,
    channel: UpdateChannel
): ReleaseIndexEntry? {
    val compatible = index.releases.filter { release ->
        when (channel) {
            UpdateChannel.STABLE -> release.channel == UpdateChannel.STABLE
            UpdateChannel.PREVIEW -> true
        } && isChannelSwitchAllowed(release, installed, channel)
    }
    require(compatible.map { it.versionCode }.distinct().size == compatible.size) {
        "Duplicate update versionCode"
    }
    return compatible.maxByOrNull { it.versionCode }
}

private fun isChannelSwitchAllowed(
    release: ReleaseIndexEntry,
    installed: InstalledRelease,
    requestedChannel: UpdateChannel
): Boolean {
    if (release.versionCode <= installed.versionCode) return false
    if (installed.isPreview && requestedChannel == UpdateChannel.STABLE) {
        return release.channel == UpdateChannel.STABLE &&
            release.versionCode > installed.versionCode
    }
    return true
}

fun validateManifestAgainstIndex(
    manifest: UpdateManifest,
    entry: ReleaseIndexEntry
): UpdateManifest {
    require(manifest.tag == entry.tag) { "Manifest tag does not match index" }
    require(manifest.channel == entry.channel) { "Manifest channel does not match index" }
    require(manifest.versionName == entry.versionName) { "Manifest versionName does not match index" }
    require(manifest.versionCode == entry.versionCode) { "Manifest versionCode does not match index" }
    require(manifest.apkSha256 == entry.apkSha256) { "Manifest APK SHA-256 does not match index" }
    require(manifest.apkSizeBytes == entry.apkSizeBytes) { "Manifest APK size does not match index" }
    return manifest
}

fun validateCandidate(
    candidate: UpdateCandidate,
    installed: InstalledRelease,
    requestedChannel: UpdateChannel
) {
    val entry = candidate.entry
    require(entry.versionCode > installed.versionCode) { "Update is not newer than installed app" }
    require(requestedChannel == UpdateChannel.PREVIEW || entry.channel == UpdateChannel.STABLE) {
        "Stable channel cannot install preview releases"
    }
    require(entry.channel == UpdateChannel.PREVIEW || !entry.prerelease) {
        "Stable releases cannot be prereleases"
    }
    require(entry.apkUrl.endsWith("/$UPDATE_APK_NAME")) { "Unexpected APK URL" }
}

data class ApkMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val debuggable: Boolean,
    val installerPackage: String?
)

fun verifyApkFile(
    metadata: ApkMetadata,
    actualSha256: String,
    expected: UpdateManifest,
    installedVersionCode: Int,
    installedPackageName: String,
    signerCertificates: List<X509Certificate>
) {
    require(metadata.packageName == UPDATE_PACKAGE_NAME) { "Unexpected APK package" }
    require(installedPackageName == UPDATE_PACKAGE_NAME) { "Unexpected installed package" }
    require(metadata.versionName == expected.versionName) { "APK versionName differs from manifest" }
    require(metadata.versionCode == expected.versionCode) { "APK versionCode differs from manifest" }
    require(metadata.versionCode > installedVersionCode) { "APK is not newer than installed app" }
    require(!metadata.debuggable) { "Debug APK rejected" }
    require(actualSha256.equals(expected.apkSha256, ignoreCase = true)) {
        "APK SHA-256 differs from manifest"
    }
    require(signerCertificates.size == 1) { "APK must have exactly one signer" }
    val fingerprint = sha256Hex(signerCertificates.single().encoded)
    require(fingerprint == UPDATE_CERTIFICATE_SHA256) { "APK signer is not the fixed release signer" }
}

fun verifyApkArchive(
    metadata: ApkMetadata,
    expected: UpdateManifest,
    installedVersionCode: Int,
    installedPackageName: String,
    signerDigests: List<String>
) {
    require(metadata.packageName == UPDATE_PACKAGE_NAME) { "Unexpected APK package" }
    require(installedPackageName == UPDATE_PACKAGE_NAME) { "Unexpected installed package" }
    require(metadata.versionName == expected.versionName) { "APK versionName differs from manifest" }
    require(metadata.versionCode == expected.versionCode) { "APK versionCode differs from manifest" }
    require(metadata.versionCode > installedVersionCode) { "APK is not newer than installed app" }
    require(!metadata.debuggable) { "Debug APK rejected" }
    require(signerDigests.size == 1) { "APK must have exactly one signer" }
    require(signerDigests.single().equals(UPDATE_CERTIFICATE_SHA256, ignoreCase = true)) {
        "APK signer is not the fixed release signer"
    }
}

fun parseApkCertificates(encoded: ByteArray): List<X509Certificate> =
    CertificateFactory.getInstance("X.509")
        .generateCertificates(ByteArrayInputStream(encoded))
        .map { it as X509Certificate }

fun httpsUrl(value: String, field: String): String {
    val uri = try {
        URI(value)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid $field", error)
    }
    require(uri.scheme.equals("https", ignoreCase = true)) { "$field must use HTTPS" }
    require(!uri.host.isNullOrBlank()) { "$field must have a host" }
    require(uri.userInfo == null) { "$field must not include credentials" }
    require(uri.fragment == null) { "$field must not include a fragment" }
    return uri.toASCIIString()
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun sha256(value: String, field: String): String {
    require(Regex("^[0-9a-fA-F]{64}$").matches(value)) { "Invalid $field" }
    return value.lowercase()
}

private fun optionalSha256(value: String): String =
    if (value.isBlank()) "" else sha256(value, "SHA-256")

private fun JSONObject.requiredString(name: String): String {
    val value = optString(name, "").trim()
    require(value.isNotEmpty()) { "Missing $name" }
    require(value.length <= 4096) { "Invalid $name" }
    return value
}

private fun JSONObject.stringList(name: String, maxItems: Int): List<String> {
    val array = optJSONArray(name) ?: JSONArray()
    require(array.length() <= maxItems) { "Too many $name" }
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index, "").trim()
            require(value.isNotEmpty() && value.length <= 600) { "Invalid $name item" }
            add(value)
        }
    }
}
