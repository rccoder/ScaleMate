package app.scalemate.update

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class UpdateSecurityTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun manifest(tag: String = "v0.3.0", code: Int = 6) = updateManifestBytes(
        UpdateChannel.STABLE, tag, tag.removePrefix("v"), code, "2026-10-18T00:00:00Z",
        listOf("Update"), emptyList(), "a".repeat(64), 100,
        mapOf("github-official" to githubAssetUrl(tag, UPDATE_APK_NAME))
    )
    private fun signature(bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance(UPDATE_SIGNATURE_ALGORITHM).run {
            initSign(keys.private); update(bytes); sign()
        }
    )

    @Test fun manifestTamperingAndWrongKeyAreRejected() {
        val bytes = manifest()
        val sig = signature(bytes)
        val verifier = UpdateManifestVerifier(keys.public)
        assertEquals(6, verifier.verify(bytes, sig).versionCode)
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(bytes.toString(Charsets.UTF_8).replace("0.3.0", "0.9.0").toByteArray(), sig)
        }
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestVerifier(other.public).verify(bytes, sig)
        }
    }

    @Test fun manualRouteDoesNotFallBackWhenManifestIsMissing() {
        val requests = mutableListOf<String>()
        val transport = object : UpdateTransport() {
            override fun getText(url: String, maxBytes: Int): String = """[{
                "tag_name":"v0.3.0","prerelease":false,"draft":false,
                "assets":[{"name":"ScaleMate.apk"},{"name":"update.json"},{"name":"update.json.sig"}]
            }]"""
            override fun downloadBytes(url: String, maxBytes: Int): ByteArray {
                requests += url
                throw UpdateFailure(UpdateFailure.Kind.NOT_FOUND, "404")
            }
        }
        assertThrows(UpdateFailure::class.java) {
            ReleaseIndexLoader(transport, keys.public).fetch(
                UpdateChannel.STABLE, "ghfast-top", UpdateSources.AUTO
            )
        }
        assertEquals(1, requests.size)
        assertTrue(requests.single().startsWith("https://ghfast.top/"))
    }

    @Test fun discoverySkipsLegacyReleasesAndFindsSignedMetadata() {
        val bytes = manifest()
        val sig = signature(bytes)
        val transport = object : UpdateTransport() {
            override fun getText(url: String, maxBytes: Int): String = if (url.endsWith(".sig")) sig else """[{
                "tag_name":"v0.3.0","prerelease":false,"draft":false,
                "assets":[{"name":"ScaleMate.apk"},{"name":"update.json"},{"name":"update.json.sig"}]
            },{"tag_name":"v0.2.1","assets":[{"name":"ScaleMate.apk"}]}]"""
            override fun downloadBytes(url: String, maxBytes: Int) = bytes
        }
        val entries = ReleaseIndexLoader(transport, keys.public).fetch(
            UpdateChannel.STABLE, UpdateSources.AUTO, UpdateSources.AUTO
        ).first
        assertEquals(listOf("v0.3.0"), entries.map { it.tag })
        assertEquals(6, entries.single().versionCode)
    }

    @Test fun updateReminderSurvivesErrorsAndClearsAfterUpgradeOrChannelChange() {
        val entry = ReleaseCandidate("v0.3.0", UpdateChannel.STABLE, false, "", "")
            .toEntry(UpdateManifest.parse(manifest().toString(Charsets.UTF_8)))
        val state = UpdateUiState(installed = InstalledApp("0.2.1", 5, false, UPDATE_PACKAGE_NAME, null),
            entry = entry, phase = UpdatePhase.AVAILABLE)
        for (phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING,
                UpdatePhase.WAITING_INSTALL, UpdatePhase.ERROR)) {
            assertTrue(state.copy(phase = phase).hasUpdate)
        }
        assertFalse(state.copy(installed = state.installed!!.copy(versionCode = 6)).hasUpdate)
        assertFalse(state.copy(entry = entry.copy(channel = UpdateChannel.PREVIEW)).hasUpdate)
    }

    @Test fun duplicateVersionCodesAreRejected() {
        val bytes = manifest()
        val entry = ReleaseCandidate("v0.3.0", UpdateChannel.STABLE, false, "", "")
            .toEntry(UpdateManifest.parse(bytes.toString(Charsets.UTF_8)))
        assertThrows(IllegalArgumentException::class.java) {
            selectCandidate(ReleaseIndex(1, "", listOf(entry, entry.copy(tag = "v0.3.1"))),
                InstalledRelease("0.2.1", 5, false, null), UpdateChannel.STABLE)
        }
    }
}
