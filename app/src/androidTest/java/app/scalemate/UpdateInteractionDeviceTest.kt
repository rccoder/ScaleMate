package app.scalemate

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.update.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Fixed UI states exercise production components; the opted-in network test uses real GitHub. */
class UpdateInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val state = mutableStateOf(UpdateUiState(
        loaded = true, installed = InstalledApp("0.4.1", 20, false, "app.scalemate", null)
    ))
    private var checks = 0
    private var downloads = 0
    private var installs = 0
    private val entry = ReleaseIndexEntry(
        tag = "v0.4.2-preview.3", channel = UpdateChannel.PREVIEW, versionName = "0.4.2-preview.3",
        versionCode = 23, publishedAt = "2026-11-01T00:00:00Z", prerelease = true,
        releaseUrl = githubReleaseUrl("v0.4.2-preview.3"),
        manifestUrl = githubAssetUrl("v0.4.2-preview.3", UPDATE_MANIFEST_NAME), manifestSha256 = "",
        manifestSignatureUrl = githubAssetUrl("v0.4.2-preview.3", UPDATE_SIGNATURE_NAME),
        apkUrl = githubAssetUrl("v0.4.2-preview.3", UPDATE_APK_NAME),
        apkSizeBytes = 26_055_652, apkSha256 = "a".repeat(64),
        highlights = List(5) { "健康页新增今日建议，结合已授权的近期健康数据与个人基线，展示可执行的日常建议。" },
        knownIssues = listOf("预览版可能不稳定。")
    )

    private fun show(language: String = "zh", dark: Boolean = false, fontScale: Float = 1f) {
        compose.activity.runOnUiThread {
            val configuration = Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language)); this.fontScale = fontScale
            }
            val localized = compose.activity.createConfigurationContext(configuration)
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, fontScale)) {
                    MaterialTheme(colorScheme = scaleMateColorScheme(dark, false)) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                                .verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(localized.getString(R.string.update_title), style = MaterialTheme.typography.headlineSmall)
                                UpdateStatusContent(state.value,
                                    onCheck = { checks++; state.value = state.value.copy(phase = UpdatePhase.CHECKING) },
                                    onDownload = { downloads++; state.value = state.value.copy(phase = UpdatePhase.DOWNLOADING, message = "") },
                                    onInstall = { installs++; state.value = state.value.copy(phase = UpdatePhase.INSTALLING) },
                                    onCancel = { state.value = state.value.copy(phase = UpdatePhase.AVAILABLE) },
                                    onOpenRelease = {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun update(phase: UpdatePhase, message: String = "") = compose.runOnIdle {
        state.value = state.value.copy(phase = phase, message = message)
    }

    private fun screenshot(name: String) {
        val title = if (compose.onAllNodesWithText("软件更新").fetchSemanticsNodes().isNotEmpty()) "软件更新" else "Software update"
        compose.onNodeWithText(title).performScrollTo()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir("update-ui"), "$name.png")
        compose.onRoot().captureToImage().asAndroidBitmap().useBitmap { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun primaryActionRemainsVisibleAndFollowsDownloadThenInstall() {
        show()
        compose.onNodeWithText("检查更新").assertIsDisplayed().performClick()
        compose.onNodeWithText("正在检查…").assertIsNotEnabled()
        screenshot("01-checking")
        compose.runOnIdle { state.value = state.value.copy(entry = entry, channel = UpdateChannel.PREVIEW, phase = UpdatePhase.AVAILABLE) }
        compose.onNodeWithText("下载更新").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertDoesNotExist()
        screenshot("02-available")
        compose.onNodeWithText("查看更新内容").performClick()
        compose.onNodeWithText("下载更新").assertIsDisplayed()
        screenshot("03-details-expanded")
        compose.onNodeWithText("下载更新").performClick()
        compose.onNodeWithText("正在下载…").assertIsNotEnabled()
        compose.onNodeWithText("取消下载").assertIsDisplayed().performClick()
        compose.onNodeWithText("下载更新").performClick()
        update(UpdatePhase.ERROR, "下载失败，请重试。")
        compose.onNodeWithText("重试下载").assertIsDisplayed()
        compose.onNodeWithText("重新检查更新").assertDoesNotExist()
        screenshot("04-download-error")
        compose.onNodeWithText("重试下载").performClick()
        update(UpdatePhase.VERIFYING)
        compose.onNodeWithText("正在校验更新包…").assertIsNotEnabled()
        update(UpdatePhase.WAITING_INSTALL)
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        compose.onNodeWithText("重新检查").assertDoesNotExist()
        assertEquals("Downloading must not open the installer", 0, installs)
        screenshot("05-ready-to-install")
        compose.onNodeWithText("安装更新").performClick()
        compose.onNodeWithText("正在打开安装确认…").assertIsNotEnabled()
        update(UpdatePhase.WAITING_INSTALL)
        compose.onNodeWithText("安装更新").assertIsDisplayed().performClick()
        assertEquals(1, checks); assertEquals(3, downloads); assertEquals(2, installs)
    }

    @Test fun checkFailureAndUpToDateHaveOnlyCheckAction() {
        show()
        update(UpdatePhase.ERROR, "无法连接更新服务。")
        compose.onNodeWithText("重新检查更新").assertIsDisplayed().performClick()
        compose.onNodeWithText("重试下载").assertDoesNotExist()
        update(UpdatePhase.UP_TO_DATE)
        compose.onNodeWithText("已是最新版本").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        screenshot("06-up-to-date")
    }

    @Test fun englishDarkAndLargeTextKeepDownloadAboveReleaseNotes() {
        state.value = state.value.copy(entry = entry, channel = UpdateChannel.PREVIEW, phase = UpdatePhase.AVAILABLE)
        show(language = "en", dark = true, fontScale = 1.3f)
        compose.onNodeWithText("Download update").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertDoesNotExist()
        screenshot("07-english-dark-large-text")
    }

    @Test fun realChecksUseFewRequestsAndReuseVerifiedManifests() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdateChecks") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val saved = prefs.all.filterKeys { it.startsWith("update.") }
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val transport = object : UpdateTransport() {
            override fun getText(url: String, maxBytes: Int): String {
                requests += url; return super.getText(url, maxBytes)
            }
            override fun downloadBytes(url: String, maxBytes: Int): ByteArray {
                requests += url; return super.downloadBytes(url, maxBytes)
            }
        }
        try {
            val editor = prefs.edit()
            saved.keys.forEach(editor::remove)
            editor.putString("update.source", "github-official").commit()
            val repository = UpdateRepository(context, transport)
            for ((label, channel, limit) in listOf(
                Triple("stable-cold", UpdateChannel.STABLE, 3),
                Triple("stable-cached", UpdateChannel.STABLE, 1),
                Triple("preview-current", UpdateChannel.PREVIEW, 1)
            )) {
                requests.clear()
                val start = System.nanoTime()
                val result = repository.check(channel)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                println("UPDATE_CHECK $label elapsedMs=$elapsed requests=${requests.size} entries=${result.entries.size}")
                assertTrue("Unexpected extra requests for $label", requests.size <= limit)
                assertTrue(requests.isNotEmpty())
            }
        } finally {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("update.") }.forEach(editor::remove)
            for ((key, value) in saved) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
            }
            editor.commit()
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
