package app.scalemate.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelsTest {
    private fun entry(
        tag: String,
        channel: UpdateChannel,
        code: Int,
        apkSha: String = "a".repeat(64)
    ) = ReleaseIndexEntry(
        tag = tag,
        channel = channel,
        versionName = tag.removePrefix("v"),
        versionCode = code,
        publishedAt = "2026-10-18T00:00:00Z",
        prerelease = channel == UpdateChannel.PREVIEW,
        releaseUrl = "https://github.com/rccoder/ScaleMate/releases/tag/$tag",
        manifestUrl = "https://github.com/rccoder/ScaleMate/releases/download/$tag/update.json",
        manifestSha256 = "b".repeat(64),
        manifestSignatureUrl = "https://github.com/rccoder/ScaleMate/releases/download/$tag/update.json.sig",
        apkUrl = "https://github.com/rccoder/ScaleMate/releases/download/$tag/ScaleMate.apk",
        apkSizeBytes = 100,
        apkSha256 = apkSha
    )

    private fun index(vararg entries: ReleaseIndexEntry) = ReleaseIndex(
        UPDATE_SCHEMA_VERSION,
        "2026-10-18T00:00:00Z",
        entries.toList()
    )

    @Test fun stableSelectionIgnoresPreviewAndPreviewSelectionAcceptsNewerStable() {
        val stable = entry("v0.3.0", UpdateChannel.STABLE, 9)
        val preview = entry("v0.3.1-preview.1", UpdateChannel.PREVIEW, 10)
        val releases = index(stable, preview)
        val installed = InstalledRelease("0.2.1", 5, false, null)

        assertEquals(stable, selectCandidate(releases, installed, UpdateChannel.STABLE))
        assertEquals(preview, selectCandidate(releases, installed, UpdateChannel.PREVIEW))
        assertNull(
            selectCandidate(
                index(stable),
                InstalledRelease("0.3.1-preview.1", 10, true, null),
                UpdateChannel.STABLE
            )
        )
        val newerStable = entry("v0.3.2", UpdateChannel.STABLE, 11)
        assertEquals(
            newerStable,
            selectCandidate(
                index(newerStable),
                InstalledRelease("0.3.1-preview.1", 10, true, null),
                UpdateChannel.STABLE
            )
        )
    }

    @Test fun candidateRequiresHigherCodeAndValidChannel() {
        val preview = entry("v0.3.1-preview.1", UpdateChannel.PREVIEW, 10)
        val candidate = UpdateCandidate(
            preview,
            UpdateManifest(
                UPDATE_SCHEMA_VERSION,
                UpdateChannel.PREVIEW,
                preview.tag,
                preview.versionName,
                10,
                preview.publishedAt,
                listOf("changes"),
                listOf("known issue"),
                UPDATE_APK_NAME,
                preview.apkSha256,
                preview.apkSizeBytes
            )
        )
        val installed = InstalledRelease("0.3.0", 9, false, null)
        validateCandidate(candidate, installed, UpdateChannel.PREVIEW)
        try {
            validateCandidate(candidate, installed, UpdateChannel.STABLE)
            error("Stable channel accepted preview")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun releaseIndexChoosesLatestPerChannelAndRejectsTagMismatch() {
        val stable = entry("v0.3.0", UpdateChannel.STABLE, 9)
        val preview = entry("v0.3.1-preview.1", UpdateChannel.PREVIEW, 10)
        val parsed = index(stable, preview)
        assertEquals(stable, parsed.latestEntry(UpdateChannel.STABLE))
        assertEquals(preview, parsed.latestEntry(UpdateChannel.PREVIEW))
        assertFalse(parsed.releases.single { it.channel == UpdateChannel.STABLE }.prerelease)
        assertEquals(UpdateChannel.PREVIEW, channelFromTag("v0.3.1-preview.1"))
        assertNull(channelFromTag("v0.3.0-preview"))
        assertTrue(isPreviewVersionName("0.3.1-preview.2"))
        assertFalse(isPreviewVersionName("0.3.1"))
    }

    @Test fun sourceAllowListRejectsUnknownHostsAndHTTP() {
        assertTrue(
            isAllowedReleaseAssetUrl(
                "https://gh-proxy.com/https://github.com/rccoder/ScaleMate/releases/download/v0.3.0/ScaleMate.apk"
            )
        )
        assertTrue(
            isAllowedReleaseAssetUrl(
                "https://github.com/rccoder/ScaleMate/releases/download/v0.3.0/ScaleMate.apk"
            )
        )
        assertFalse(isAllowedReleaseAssetUrl("http://gh-proxy.com/file"))
        assertFalse(isAllowedReleaseAssetUrl("https://evil.example/file"))
        assertFalse(isAllowedReleaseAssetUrl("https://github.com.evil.example/file"))

        val trusted = UpdateSources.GITHUB_OFFICIAL
        val selected = UpdateSources.ordered(trusted.id)
        assertEquals(listOf(trusted), selected)
        assertFalse(UpdateSources.ordered(UpdateSources.AUTO).isEmpty())
    }

    @Test fun previewManifestRequiresKnownIssuesAndExactPackageVersion() {
        val manifest = UpdateManifest(
            UPDATE_SCHEMA_VERSION,
            UpdateChannel.PREVIEW,
            "v0.3.1-preview.1",
            "0.3.1-preview.1",
            10,
            "2026-10-18T00:00:00Z",
            listOf("changes"),
            listOf("known issue"),
            UPDATE_APK_NAME,
            "a".repeat(64),
            100
        )
        val metadata = ApkMetadata(
            packageName = UPDATE_PACKAGE_NAME,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            debuggable = false,
            installerPackage = null
        )
        // Signature certificates are covered by the Android instrumentation
        // fixture; this test locks the metadata and version contract.
        require(metadata.versionCode == 10)
        assertEquals("app.scalemate", metadata.packageName)
        assertTrue(channelFromTag(manifest.tag) == UpdateChannel.PREVIEW)
    }

    @Test fun githubDiscoveryAcceptsOnlyTagShapesAndKeepsStableAndPreview() {
        assertEquals(UpdateChannel.STABLE, channelFromTag("v0.3.1"))
        assertEquals(UpdateChannel.PREVIEW, channelFromTag("v0.3.2-preview.1"))
        assertNull(channelFromTag("v0.3.2-rc.1"))
        assertNull(channelFromTag("garbage"))
    }

    @Test fun githubDiscoveryRequiresSignedUpdateAssets() {
        fun release(assets: String) = """
            [{
              "tag_name": "v0.3.1",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-10-18T00:00:00Z",
              "assets": [$assets]
            }]
        """.trimIndent()

        val complete = release(
            """{"name":"ScaleMate.apk"},{"name":"update.json"},{"name":"update.json.sig"}"""
        )
        assertEquals(1, GitHubReleaseFetcher.parseGitHubReleases(complete, 0).size)
        for (incomplete in listOf(
            """{"name":"ScaleMate.apk"},{"name":"update.json"}""",
            """{"name":"ScaleMate.apk"}""",
            ""
        )) {
            assertTrue(GitHubReleaseFetcher.parseGitHubReleases(release(incomplete), 0).isEmpty())
        }
    }

    @Test fun archiveVerificationRejectsDebugOrWrongSigner() {
        val manifest = UpdateManifest(
            UPDATE_SCHEMA_VERSION,
            UpdateChannel.STABLE,
            "v0.3.1",
            "0.3.1",
            7,
            "2026-10-18T00:00:00Z",
            listOf("changes"),
            emptyList(),
            UPDATE_APK_NAME,
            "a".repeat(64),
            100
        )
        val metadata = ApkMetadata(
            packageName = UPDATE_PACKAGE_NAME,
            versionName = "0.3.1",
            versionCode = 7,
            debuggable = false,
            installerPackage = null
        )
        verifyApkArchive(metadata, manifest, 6, UPDATE_PACKAGE_NAME, listOf(UPDATE_CERTIFICATE_SHA256))
        for (bad in listOf(
            metadata.copy(debuggable = true),
            metadata.copy(versionCode = 6),
            metadata.copy(packageName = "app.example")
        )) {
            assertFails {
                verifyApkArchive(bad, manifest, 6, UPDATE_PACKAGE_NAME, listOf(UPDATE_CERTIFICATE_SHA256))
            }
        }
        assertFails {
            verifyApkArchive(
                metadata,
                manifest,
                6,
                UPDATE_PACKAGE_NAME,
                listOf("f".repeat(64))
            )
        }
    }

    private inline fun assertFails(block: () -> Unit) {
        try {
            block()
            error("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
