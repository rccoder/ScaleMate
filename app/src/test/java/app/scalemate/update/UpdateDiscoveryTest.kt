package app.scalemate.update

import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateDiscoveryTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun tag(code: Int, preview: Boolean = true) = if (preview) "v0.5.0-preview.$code" else "v0.4.$code"
    private fun release(code: Int, preview: Boolean = true): JSONObject = JSONObject().apply {
        put("tag_name", tag(code, preview)); put("prerelease", preview); put("draft", false)
        put("published_at", Instant.ofEpochSecond(code.toLong()).toString())
        put("assets", JSONArray(listOf(UPDATE_APK_NAME, UPDATE_MANIFEST_NAME, UPDATE_SIGNATURE_NAME).map {
            JSONObject().put("name", it)
        }))
    }
    private fun signed(code: Int, preview: Boolean = true): SignedUpdateManifest {
        val tag = tag(code, preview)
        val bytes = updateManifestBytes(
            if (preview) UpdateChannel.PREVIEW else UpdateChannel.STABLE, tag, tag.removePrefix("v"), code,
            Instant.ofEpochSecond(code.toLong()).toString(), listOf("Changes"),
            if (preview) listOf("Preview") else emptyList(), "a".repeat(64), 100,
            mapOf("github-official" to githubAssetUrl(tag, UPDATE_APK_NAME))
        )
        return SignedUpdateManifest(bytes, Base64.getEncoder().encodeToString(Signature.getInstance(UPDATE_SIGNATURE_ALGORITHM).run {
            initSign(keys.private); update(bytes); sign()
        }))
    }
    private inner class Transport(val releases: List<JSONObject>) : UpdateTransport() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val manifests = releases.associate { r ->
            val preview = r.getBoolean("prerelease")
            val code = r.getString("tag_name").substringAfterLast('.').toInt()
            r.getString("tag_name") to signed(code, preview)
        }.toMutableMap()
        override fun getText(url: String, maxBytes: Int): String {
            requests += url
            if (url.endsWith("/latest")) return releases.first { !it.getBoolean("prerelease") }.toString()
            if (!url.endsWith(".sig")) return JSONArray(releases).toString()
            return manifests.getValue(url.substringAfter("/download/").substringBefore('/')).signature
        }
        override fun downloadBytes(url: String, maxBytes: Int): ByteArray {
            requests += url
            return manifests.getValue(url.substringAfter("/download/").substringBefore('/')).bytes
        }
    }
    private fun fetch(t: Transport, channel: UpdateChannel, installed: InstalledRelease? = null) =
        runBlocking { ReleaseIndexLoader(t, keys.public).fetch(channel, "github-official", UpdateSources.AUTO, installed = installed).first }

    @Test fun stableUsesLatestAndOnlyOneSignedManifest() {
        val t = Transport(listOf(release(20, false), release(19, false), release(21)))
        assertEquals(20, fetch(t, UpdateChannel.STABLE).single().versionCode)
        assertEquals(3, t.requests.size)
        assertEquals(githubLatestReleaseApiUrl(), t.requests.first())
        assertTrue(t.requests.drop(1).all { "/${tag(20, false)}/" in it })
    }

    @Test fun alreadyInstalledLatestNeedsOnlyDiscovery() {
        val t = Transport(listOf(release(20, false), release(19, false)))
        assertTrue(fetch(t, UpdateChannel.STABLE, InstalledRelease("0.4.20", 20, false, null)).isEmpty())
        assertEquals(1, t.requests.size)
    }

    @Test fun previewHistoryNeedsAtMostLatestPerChannel() {
        val t = Transport((100 downTo 1).map { release(it, it % 10 != 0) })
        assertEquals(setOf(99, 100), fetch(t, UpdateChannel.PREVIEW).map { it.versionCode }.toSet())
        assertEquals(5, t.requests.size)
    }

    @Test fun newestPreviewAlreadyInstalledDoesNotCheckHistory() {
        val t = Transport(listOf(release(21), release(20, false), release(19)))
        assertTrue(fetch(t, UpdateChannel.PREVIEW, InstalledRelease("0.5.0-preview.21", 21, true, null)).isEmpty())
        assertEquals(1, t.requests.size)
    }

    @Test fun previewCanDiscoverNewerStableUsingSignedCode() {
        val t = Transport(listOf(release(22, false), release(21), release(20, false)))
        val installed = InstalledRelease("0.5.0-preview.21", 21, true, null)
        val entries = fetch(t, UpdateChannel.PREVIEW, installed)
        assertEquals(22, selectCandidate(ReleaseIndex(1, "", entries), installed, UpdateChannel.PREVIEW)?.versionCode)
        assertEquals(3, t.requests.size)
    }

    @Test fun oldInstallJumpsDirectlyToLatestWithoutIntermediateUpdates() {
        val installed = InstalledRelease("0.3.0", 7, false, null)
        val t = Transport(listOf(release(23), release(22), release(21), release(20, false), release(19, false)))
        val stable = selectCandidate(ReleaseIndex(1, "", fetch(t, UpdateChannel.STABLE, installed)), installed, UpdateChannel.STABLE)
        assertEquals(20, stable?.versionCode)
        assertEquals(3, t.requests.size)
        t.requests.clear()
        val preview = selectCandidate(ReleaseIndex(1, "", fetch(t, UpdateChannel.PREVIEW, installed)), installed, UpdateChannel.PREVIEW)
        assertEquals(23, preview?.versionCode)
        assertEquals(tag(23), preview?.tag)
        assertEquals(5, t.requests.size)
        assertTrue(t.requests.none { "/${tag(22)}/" in it || "/${tag(21)}/" in it || "/${tag(19, false)}/" in it })
    }

    @Test fun verifiedManifestsAreReusedButReverified() {
        val t = Transport(listOf(release(20, false)))
        val cache = mutableMapOf<String, SignedUpdateManifest>()
        val loader = ReleaseIndexLoader(t, keys.public, cache::get) { tag, signed -> cache[tag] = signed }
        fun check() = runBlocking { loader.fetch(UpdateChannel.STABLE, "github-official", UpdateSources.AUTO).first.single() }
        assertEquals(20, check().versionCode)
        t.requests.clear()
        assertEquals(20, check().versionCode)
        assertEquals(1, t.requests.size)
        val valid = cache.getValue(tag(20, false))
        cache[tag(20, false)] = valid.copy(bytes = valid.bytes.toString(Charsets.UTF_8).replace("Changes", "Tampered").toByteArray())
        t.requests.clear()
        assertEquals(20, check().versionCode)
        assertEquals(3, t.requests.size)
    }

    @Test fun invalidNewestFallsBackWithoutWalkingAllHistory() {
        val t = Transport((20 downTo 1).map { release(it) })
        t.manifests[tag(20)] = signed(20).copy(signature = "invalid")
        assertEquals(19, fetch(t, UpdateChannel.PREVIEW).single().versionCode)
        assertEquals(5, t.requests.size)
    }

    @Test fun allInvalidCandidatesFailInsteadOfSayingUpToDate() {
        val t = Transport((20 downTo 1).map { release(it) })
        t.manifests.replaceAll { _, signed -> signed.copy(signature = "invalid") }
        assertThrows(UpdateFailure::class.java) { fetch(t, UpdateChannel.PREVIEW) }
        assertEquals(7, t.requests.size)
    }

    @Test fun unreadablePreviewIsNotHiddenByAnOlderValidStable() {
        val t = Transport(listOf(release(100), release(99), release(98), release(80, false)))
        listOf(100, 99, 98).forEach { code -> t.manifests[tag(code)] = signed(code).copy(signature = "invalid") }
        assertThrows(UpdateFailure::class.java) {
            fetch(t, UpdateChannel.PREVIEW, InstalledRelease("0.5.0-preview.95", 95, true, null))
        }
    }

    @Test fun cancellationDoesNotStartAnotherRoute() {
        val requests = mutableListOf<String>()
        val t = object : UpdateTransport() {
            override fun getText(url: String, maxBytes: Int): String {
                requests += url
                throw CancellationException("Cancelled")
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { ReleaseIndexLoader(t, keys.public).fetch(UpdateChannel.PREVIEW, UpdateSources.AUTO, UpdateSources.AUTO) }
        }
        assertEquals(1, requests.size)
    }

    @Test fun stableLatestProxyUrlUsesCorrectEndpoint() {
        assertEquals("https://gh-proxy.com/https://api.github.com/repos/rccoder/ScaleMate/releases/latest",
            UpdateSources.releaseApiUrl(UpdateSources.GH_PROXY_COM, latest = true))
    }

    @Test fun manifestAndSignatureRequestsCanMakeProgressTogether() = runBlocking {
        val bothStarted = java.util.concurrent.CountDownLatch(2)
        fun awaitOtherRequest() {
            bothStarted.countDown()
            assertTrue("Metadata downloads were serialized", bothStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
        val t = object : UpdateTransport() {
            override fun getText(url: String, maxBytes: Int): String {
                awaitOtherRequest(); return "signature"
            }
            override fun downloadBytes(url: String, maxBytes: Int): ByteArray {
                awaitOtherRequest(); return byteArrayOf(1, 2)
            }
        }
        val signed = t.signedManifest(githubAssetUrl("v0.4.1", UPDATE_MANIFEST_NAME), githubAssetUrl("v0.4.1", UPDATE_SIGNATURE_NAME))
        assertArrayEquals(byteArrayOf(1, 2), signed.bytes)
        assertEquals("signature", signed.signature)
    }
}
