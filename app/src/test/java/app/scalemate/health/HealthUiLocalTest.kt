package app.scalemate.health

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.scalemate.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Local Android rendering only: no device, Health Connect records, credentials or AI calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HealthUiLocalTest {
    val compose=createAndroidComposeRule<MainActivity>()
    private val environment=object:ExternalResource(){override fun before(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings",0).edit().putBoolean("update.autoCheck",false).commit()
        Shadows.shadowOf(app.packageManager).setInstallSourceInfo(app.packageName,null,null)
    }}
    @get:Rule val rules:TestRule=RuleChain.outerRule(environment).around(compose)
    private fun enterHealth() {
        compose.waitUntil(15000){compose.onAllNodesWithText("开始记录").fetchSemanticsNodes().isNotEmpty()||compose.onAllNodesWithContentDescription("健康",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        if(compose.onAllNodesWithText("开始记录").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithText("开始记录").performClick()
        compose.onNodeWithContentDescription("健康",useUnmergedTree=true).performClick()
        compose.onNodeWithText("今日").assertExists()
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val file=File("build/reports/health-ui/$name.png").apply {parentFile!!.mkdirs()}
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun everySelectableHealthCategoryHasADeclaredReadPermission() {
        val app=RuntimeEnvironment.getApplication()
        val declared=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val required=HealthCatalog.kinds.map {it.permission}.toSet()+setOf(HealthCatalog.HISTORY,HealthCatalog.BACKGROUND,android.Manifest.permission.POST_NOTIFICATIONS)
        org.junit.Assert.assertTrue("Missing manifest permissions: ${required-declared}",declared.containsAll(required))
    }
    @Test fun healthHomeAndRangeRenderWithoutPermissionsOrAnAiKey() {
        enterHealth();screenshot("01-health-home-local")
        compose.onNodeWithText("今日").assertExists()
        compose.onNodeWithText("近期").performClick()
        compose.onNodeWithText("近期").assertExists()
        compose.onNodeWithText("AI 分析").assertDoesNotExist()
        compose.onNodeWithText("尚未配置 AI 服务").assertDoesNotExist()
        compose.onNodeWithText("分析历史").assertDoesNotExist()
        compose.onNodeWithText("健康隐私与历史").assertDoesNotExist()
        compose.onNodeWithText("健康对话").assertDoesNotExist()
        compose.onNodeWithText("主动提醒").assertDoesNotExist()
        compose.onNodeWithText("分析时间范围").performScrollTo().performClick()
        compose.onNodeWithText("90 天").assertExists()
        compose.onNodeWithText("全部可读取历史").assertExists()
        screenshot("02-health-range-local")
        compose.onNodeWithText("自定义日期").performClick()
        compose.onNodeWithText("结束日期（不含当天）").assertExists()
    }
    @Test fun disabledReminderEntryDoesNotOpenReminderPage() {
        enterHealth()
        compose.activity.runOnUiThread {
            compose.activity.intent.putExtra("healthReminderId","legacy-reminder")
            compose.activity.recreate()
        }
        compose.waitForIdle()
        compose.onNodeWithText("今日").assertExists()
        compose.onNodeWithText("主动提醒").assertDoesNotExist()
        compose.onNodeWithText("睡眠目标").assertDoesNotExist()
    }
    @Test fun categoryReadAuthorizationPageAndGeneralChatRemainAccessible() {
        enterHealth()
        compose.onNodeWithContentDescription("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Health Connect").performScrollTo().performClick()
        compose.onNodeWithText("其他健康数据").performScrollTo().performClick()
        compose.onNodeWithText("授权读取所选类别").assertExists()
        compose.onNodeWithText("选择常用类别").assertExists()
        compose.onNodeWithText("日常活动").assertExists()
        compose.onNodeWithText("身体与营养").assertExists()
        compose.onNodeWithText("敏感或临床记录").assertExists()
        compose.onNodeWithText("授权读取更早历史").performScrollTo().assertExists()
        compose.onNodeWithText("授权后台读取").assertDoesNotExist()
        screenshot("03-health-permissions-local")
        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithContentDescription("健康",useUnmergedTree=true).performClick()
        compose.onNodeWithText("健康对话").assertDoesNotExist()
    }
}
