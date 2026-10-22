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
        compose.onNodeWithText("看懂整体健康数据，再决定下一步。").assertExists()
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
        compose.onNodeWithText("分析时间范围").performClick()
        compose.onNodeWithText("90 天").assertExists()
        compose.onNodeWithText("全部可读取历史").assertExists()
        screenshot("02-health-range-local")
        compose.onNodeWithText("自定义日期").performClick()
        compose.onNodeWithText("结束日期（不含当天）").assertExists()
    }
    @Test fun categoryReadAuthorizationPageAndGeneralChatRemainAccessible() {
        enterHealth()
        compose.onNodeWithContentDescription("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("健康数据与授权").performClick()
        compose.onNodeWithText("授权读取所选类别").assertExists()
        compose.onNodeWithText("选择常用类别").assertExists()
        compose.onNodeWithText("授权读取更早历史").assertExists()
        compose.onNodeWithText("授权后台读取").assertDoesNotExist()
        screenshot("03-health-permissions-local")
        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithContentDescription("健康",useUnmergedTree=true).performClick()
        compose.onNodeWithText("健康对话").performScrollTo().performClick()
        compose.onNodeWithText("此对话尚未结合个人健康数据。").assertExists()
        compose.onNodeWithText("提问或补充你的感受").assertExists()
        screenshot("04-health-chat-local")
        compose.onNode(hasSetTextAction()).performTextInput("最近活动怎样")
        compose.onNodeWithText("发送").performScrollTo().performClick()
        compose.onNodeWithText("请先配置或选择 AI 服务；读取数据和导出不需要密钥。").assertExists()
        compose.onNode(hasSetTextAction()).assertTextContains("最近活动怎样")
    }
}
