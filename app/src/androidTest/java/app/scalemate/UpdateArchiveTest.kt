package app.scalemate

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.update.*
import java.io.File
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun androidReadsFixedSignerFromRealReleaseApk() {
        val file = File(context.filesDir, "update-validation.apk")
        assertTrue("Push the fixed-signed release fixture before running", file.isFile)
        try {
            val archive = context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            ) ?: error("Android could not read release APK")
            val digests = apkSignerCertificates(archive.signingInfo).orEmpty().map { sha256Hex(it.toByteArray()) }
            assertEquals(listOf(UPDATE_CERTIFICATE_SHA256), digests)
            assertEquals(UPDATE_PACKAGE_NAME, archive.packageName)
            assertFalse(archive.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val manifestFile = File(context.filesDir, "update-validation.json")
            val signatureFile = File(context.filesDir, "update-validation.json.sig")
            val key = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(BuildConfig.UPDATE_PUBLIC_KEY_BASE64))
            )
            val manifest = UpdateManifestVerifier(key).verify(manifestFile.readBytes(), signatureFile.readText())
            verifyApkArchive(ApkMetadata(archive.packageName, archive.versionName.orEmpty(),
                archive.longVersionCode.toInt(), false, null), manifest,
                manifest.versionCode - 1, UPDATE_PACKAGE_NAME, digests)
            assertEquals(manifest.apkSizeBytes, file.length())
            assertEquals(manifest.apkSha256, sha256Hex(file.readBytes()))
        } finally {
            file.delete()
            File(context.filesDir, "update-validation.json").delete()
            File(context.filesDir, "update-validation.json.sig").delete()
        }
    }

    @Test fun successfulEmptyCheckClearsStaleUpdateAndPersistsTime() {
        val prefs = context.getSharedPreferences("update-validation", 0)
        try {
            val cache = UpdateCache(prefs)
            prefs.edit().putString("update.entryJson", "stale").commit()
            cache.saveCheck(null, "", "github-official", 1234)
            assertEquals(1234, cache.lastCheckAt())
            assertNull(cache.cachedEntry())
            assertFalse(prefs.contains("update.entryJson"))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
