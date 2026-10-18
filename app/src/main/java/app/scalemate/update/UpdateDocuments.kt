package app.scalemate.update

import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** A signed release manifest is the source of truth for version and APK metadata. */
class UpdateManifestVerifier(
    private val publicKey: PublicKey,
    private val expectedCertificateSha256: String = UPDATE_CERTIFICATE_SHA256
) {
    fun verify(manifestBytes: ByteArray, signatureText: String): UpdateManifest {
        val signature = try {
            Base64.getDecoder().decode(signatureText.trim())
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid update manifest signature encoding", error)
        }
        val verifier = Signature.getInstance(UPDATE_SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        require(verifier.verify(signature)) { "Update manifest signature is invalid" }
        return UpdateManifest.parse(manifestBytes.toString(Charsets.UTF_8))
    }

    fun certificateMatches(encodedCertificate: ByteArray): Boolean =
        sha256Hex(encodedCertificate) == expectedCertificateSha256
}

/**
 * Discovers release tags, then authenticates every candidate through its
 * per-release signed update.json. GitHub's "latest" asset cannot represent
 * both stable and prerelease channels at the same time.
 */
class ReleaseIndexLoader(
    private val transport: UpdateTransport,
    private val publicKey: PublicKey
) {
    fun fetch(
        requestedChannel: UpdateChannel,
        preferredSourceId: String,
        discoverySourceId: String,
        onAttempt: (source: UpdateSource, error: String) -> Unit = { _, _ -> }
    ): Pair<List<ReleaseIndexEntry>, String> {
        val (candidates, discoverySource) =
            GitHubReleaseFetcher(transport).discover(discoverySourceId, onAttempt)
        val wanted = candidates.filter {
            when (requestedChannel) {
                UpdateChannel.STABLE -> it.channel == UpdateChannel.STABLE
                UpdateChannel.PREVIEW -> true
            }
        }.take(MAX_MANIFEST_CANDIDATES)
        if (wanted.isEmpty()) {
            return emptyList<ReleaseIndexEntry>() to discoverySource
        }
        val entries = mutableListOf<ReleaseIndexEntry>()
        var signedEntryCount = 0
        for (candidate in wanted) {
            try {
                val manifest = fetchManifest(
                    candidate.tag,
                    preferredSourceId,
                    onAttempt
                )
                entries += candidate.toEntry(manifest)
                signedEntryCount += 1
            } catch (error: Exception) {
                onAttempt(
                    UpdateSources.find(discoverySource) ?: UpdateSources.GITHUB_OFFICIAL,
                    error.message?.take(120) ?: "manifest failed"
                )
            }
        }
        if (signedEntryCount == 0) {
            throw UpdateFailure(UpdateFailure.Kind.NOT_FOUND, "No signed release found")
        }
        return entries to discoverySource
    }

    private fun fetchManifest(
        tag: String,
        preferredSourceId: String,
        onAttempt: (UpdateSource, String) -> Unit
    ): UpdateManifest {
        val sources = UpdateSources.assetOrder(preferredSourceId)
        var lastError: Exception? = null
        for (source in sources) {
            try {
                val manifestUrl = UpdateSources.assetUrl(source, tag, UPDATE_MANIFEST_NAME)
                val signatureUrl = UpdateSources.assetUrl(
                    source,
                    tag,
                    UPDATE_SIGNATURE_NAME
                )
                val raw = transport.downloadBytes(manifestUrl, 128 * 1024)
                val signature = transport.getText(signatureUrl, 64 * 1024)
                return UpdateManifestVerifier(publicKey)
                    .verify(raw, signature)
                    .also { manifest ->
                        require(manifest.tag == tag) { "Manifest tag differs from release" }
                    }
            } catch (error: Exception) {
                lastError = error
                onAttempt(source, error.message?.take(120) ?: "manifest failed")
            }
        }
        throw lastError ?: UpdateFailure(UpdateFailure.Kind.NOT_FOUND, "Manifest unavailable")
    }

    companion object {
        private const val MAX_MANIFEST_CANDIDATES = 20
    }
}

class GitHubReleaseFetcher(
    private val transport: UpdateTransport,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun discover(
        preferredSourceId: String,
        onAttempt: (source: UpdateSource, error: String) -> Unit = { _, _ -> }
    ): Pair<List<ReleaseCandidate>, String> {
        val errors = mutableListOf<String>()
        for (source in UpdateSources.discoveryOrder(preferredSourceId)) {
            try {
                val raw = transport.getText(
                    UpdateSources.releaseApiUrl(source),
                    maxBytes = 2 * 1024 * 1024
                )
                val candidates = parseGitHubReleases(raw, now())
                return candidates.sortedByDescending { it.publishedAt } to source.id
            } catch (error: Exception) {
                val reason = error.message?.take(120) ?: error.javaClass.simpleName
                errors += "${source.id}: $reason"
                onAttempt(source, reason)
            }
        }
        throw UpdateFailure(
            UpdateFailure.Kind.NETWORK,
            if (errors.isEmpty()) "No update source is enabled" else errors.joinToString("; ").take(600)
        )
    }

    companion object {
        fun parseGitHubReleases(raw: String, checkedAt: Long): List<ReleaseCandidate> {
            val array = try {
                JSONArray(raw)
            } catch (error: Exception) {
                throw IllegalArgumentException("GitHub Releases response is not an array", error)
            }
            require(array.length() in 0..100) { "Invalid GitHub Releases response" }
            return buildList {
                for (position in 0 until array.length()) {
                    parseRelease(array.optJSONObject(position) ?: continue, checkedAt)
                        ?.let { add(it) }
                }
            }
        }

        private fun parseRelease(json: JSONObject, checkedAt: Long): ReleaseCandidate? {
            val tag = json.optString("tag_name", "").trim()
            val channel = channelFromTag(tag) ?: return null
            if (json.optBoolean("draft", false)) return null
            val prerelease = json.optBoolean("prerelease", false)
            // Release semantics are part of the channel contract. A stable
            // tag must never point at a prerelease and vice versa.
            if ((channel == UpdateChannel.PREVIEW) != prerelease) return null
            // Releases before the updater do not carry the signed metadata.
            // Filter them here instead of probing every historical tag.
            val assets = json.optJSONArray("assets") ?: return null
            val assetNames = buildSet {
                for (index in 0 until assets.length()) {
                    assets.optJSONObject(index)
                        ?.optString("name", "")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
            if (!assetNames.containsAll(
                    setOf(UPDATE_APK_NAME, UPDATE_MANIFEST_NAME, UPDATE_SIGNATURE_NAME)
                )
            ) {
                return null
            }
            return ReleaseCandidate(
                tag = tag,
                channel = channel,
                prerelease = prerelease,
                publishedAt = json.optString("published_at", "")
                    .ifBlank { json.optString("created_at", "unknown") },
                releaseUrl = "https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/tag/$tag",
                checkedAt = checkedAt
            )
        }
    }
}

class UpdateFailure(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NETWORK, NOT_FOUND, INVALID, INSTALL }
}

class UpdateCache(private val prefs: android.content.SharedPreferences) {
    fun initializeChannel(default: UpdateChannel) {
        if (!prefs.contains("update.channel")) {
            prefs.edit().putString("update.channel", default.wireName).apply()
        }
    }

    fun selectedChannel(): UpdateChannel =
        UpdateChannel.fromWire(prefs.getString("update.channel", UPDATE_CHANNEL) ?: UPDATE_CHANNEL)
            ?: UpdateChannel.STABLE

    fun selectedSource(): String =
        prefs.getString("update.source", UpdateSources.AUTO).takeIf { it in sourceIds() }
            ?: UpdateSources.AUTO

    fun autoCheck(): Boolean = prefs.getBoolean("update.autoCheck", true)
    fun autoDownload(): Boolean = prefs.getBoolean("update.autoDownload", false)
    fun unmeteredOnly(): Boolean = prefs.getBoolean("update.unmeteredOnly", true)
    fun lastCheckAt(): Long = prefs.getLong("update.lastCheckAt", 0)
    fun lastCheckSource(): String = prefs.getString("update.lastCheckSource", "") ?: ""
    fun lastError(): String = prefs.getString("update.lastError", "") ?: ""
    fun lastManifestJson(tag: String): String? = prefs.getString("update.manifest.$tag", null)
    fun lastManifestSha(tag: String): String? = prefs.getString("update.manifestSha.$tag", null)

    fun saveChannel(channel: UpdateChannel) {
        prefs.edit().putString("update.channel", channel.wireName).apply()
    }

    fun saveSource(source: String) {
        prefs.edit().putString("update.source", source).apply()
    }

    fun saveSettings(autoCheck: Boolean, autoDownload: Boolean, unmeteredOnly: Boolean) {
        prefs.edit()
            .putBoolean("update.autoCheck", autoCheck)
            .putBoolean("update.autoDownload", autoDownload)
            .putBoolean("update.unmeteredOnly", unmeteredOnly)
            .apply()
    }

    fun saveCheck(entry: ReleaseIndexEntry?, signature: String, source: String, at: Long) {
        prefs.edit()
            .putString("update.entryJson", entry?.toJson()?.toString())
            .putString("update.entrySignature", signature)
            .putString("update.lastCheckSource", source)
            .putLong("update.lastCheckAt", at)
            .putString("update.lastError", "")
            .apply()
    }

    fun cachedEntry(): ReleaseIndexEntry? =
        prefs.getString("update.entryJson", null)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let(::entryFromJson)
        }

    fun saveManifest(manifest: UpdateManifest, raw: String, sha: String) {
        prefs.edit()
            .putString("update.manifest.${manifest.tag}", raw)
            .putString("update.manifestSha.${manifest.tag}", sha)
            .apply()
    }

    fun saveError(error: String) {
        prefs.edit().putString("update.lastError", error.take(300)).apply()
    }

    private fun sourceIds(): Set<String> =
        buildSet { add(UpdateSources.AUTO); UpdateSources.all.forEach { add(it.id) } }
}

private const val UPDATE_CHANNEL = "stable"

private fun ReleaseIndex.toJson(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("generatedAt", generatedAt)
    put("releases", JSONArray().apply {
        releases.forEach { release ->
            put(JSONObject().apply {
                put("tag", release.tag)
                put("channel", release.channel.wireName)
                put("versionName", release.versionName)
                put("versionCode", release.versionCode)
                put("publishedAt", release.publishedAt)
                put("prerelease", release.prerelease)
                put("releaseUrl", release.releaseUrl)
                put("manifestUrl", release.manifestUrl)
                put("manifestSha256", release.manifestSha256)
                put("manifestSignatureUrl", release.manifestSignatureUrl)
                put("apkUrl", release.apkUrl)
                put("apkSizeBytes", release.apkSizeBytes)
                put("apkSha256", release.apkSha256)
                put("highlights", JSONArray(release.highlights))
                put("knownIssues", JSONArray(release.knownIssues))
            })
        }
    })
}

private fun ReleaseIndexEntry.toJson(): JSONObject = JSONObject().apply {
    put("tag", tag)
    put("channel", channel.wireName)
    put("versionName", versionName)
    put("versionCode", versionCode)
    put("publishedAt", publishedAt)
    put("prerelease", prerelease)
    put("releaseUrl", releaseUrl)
    put("manifestUrl", manifestUrl)
    put("manifestSha256", manifestSha256)
    put("manifestSignatureUrl", manifestSignatureUrl)
    put("apkUrl", apkUrl)
    put("apkSizeBytes", apkSizeBytes)
    put("apkSha256", apkSha256)
    put("highlights", JSONArray(highlights))
    put("knownIssues", JSONArray(knownIssues))
}

private fun entryFromJson(json: JSONObject): ReleaseIndexEntry? = runCatching {
    val tag = json.getString("tag")
    val channel = UpdateChannel.fromWire(json.getString("channel")) ?: return null
    ReleaseIndexEntry(
        tag = tag,
        channel = channel,
        versionName = json.getString("versionName"),
        versionCode = json.getInt("versionCode"),
        publishedAt = json.getString("publishedAt"),
        prerelease = json.getBoolean("prerelease"),
        releaseUrl = json.getString("releaseUrl"),
        manifestUrl = json.getString("manifestUrl"),
        manifestSha256 = json.getString("manifestSha256"),
        manifestSignatureUrl = json.getString("manifestSignatureUrl"),
        apkUrl = json.getString("apkUrl"),
        apkSizeBytes = json.getLong("apkSizeBytes"),
        apkSha256 = json.getString("apkSha256"),
        highlights = json.optJSONArray("highlights")?.let { array ->
            buildList { for (index in 0 until array.length()) add(array.getString(index)) }
        }.orEmpty(),
        knownIssues = json.optJSONArray("knownIssues")?.let { array ->
            buildList { for (index in 0 until array.length()) add(array.getString(index)) }
        }.orEmpty()
    )
}.getOrNull()

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
    }.doFinal(data)

fun base64UrlNoPadding(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
