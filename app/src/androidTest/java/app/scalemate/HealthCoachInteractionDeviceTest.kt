package app.scalemate

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.health.*
import java.io.File
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Synthetic UI only; no AI calls, business records or settings are changed. */
class HealthCoachInteractionDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val today=LocalDate.parse("2026-11-02")
    private val profile=mutableStateOf(HealthCoachProfile())
    private val plan=mutableStateOf(HealthCoachPlan(
        HealthMessage(role="assistant",text="最近的活动记录有起伏，先选一个容易坚持的安排。",snapshotId="old-snapshot"),
        today.toString(),"午饭后，按舒服的速度散步五分钟，不必赶步数。",profile.value,setOf("Steps")
    ))
    private val generating=mutableStateOf(false)
    private val showPreferences=mutableStateOf(false)
    private var scroll:ScrollState?=null
    private var openedEvidence=""
    private val fact=HealthEvidence("abcdef123456abcdef123456","Steps",today.toString(),"Synthetic source","count",3500.0,"steps",partial=true)
    private val brief=HealthCoachBrief(today.toString(),null,emptySet(),emptySet(),listOf(HealthCoachSignal.Activity(fact,6000.0,null)))
    private fun show(language:String="zh",dark:Boolean=false,fontScale:Float=1f) {
        plan.value=plan.value.copy(evidence=listOf(fact))
        if(language=="en")plan.value=plan.value.copy(message=plan.value.message.copy(text="Recent activity varies. Start with something easy to keep doing."),action="After lunch, take a comfortable five-minute walk without chasing a step total.")
        compose.activity.runOnUiThread {
            val config=Configuration(compose.activity.resources.configuration).apply {setLocale(Locale.forLanguageTag(language));this.fontScale=fontScale}
            val context=compose.activity.createConfigurationContext(config)
            compose.activity.setContent {
                CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides config,LocalDensity provides Density(LocalDensity.current.density,fontScale)) {
                    MaterialTheme(colorScheme=scaleMateColorScheme(dark,false)) {
                        Surface(Modifier.fillMaxSize()) {
                            val scrolling=rememberScrollState().also {scroll=it}
                            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(scrolling).padding(18.dp)) {
                                HealthCoachSection(brief,plan.value.message,true,generating.value,language=="zh",{}, {}, {},profile.value,listOf(plan.value),today,generating.value,"new-snapshot",{showPreferences.value=true},{_,feedback->plan.value=plan.value.copy(feedback=feedback)},{id,_->openedEvidence=id})
                            }
                            if(showPreferences.value)HealthCoachPreferences(profile.value,today,{profile.value=it;showPreferences.value=false},{},{},{showPreferences.value=false})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun screenshot(name:String) {
        compose.runOnIdle {runBlocking {scroll!!.scrollTo(0)}}
        compose.mainClock.advanceTimeBy(500);compose.waitForIdle()
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("coach-ui"),"$name.png")
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun userCanAdoptCompleteAndReviseAnActionWithoutCallingAi() {
        show();screenshot("01-action")
        compose.onNodeWithText("步数 · 3,500 步").performScrollTo().performClick()
        assertEquals(fact.id,openedEvidence)
        compose.onNodeWithText("今天试试").performScrollTo().performClick()
        assertEquals(HealthCoachFeedback.ACCEPTED,plan.value.feedback)
        compose.onNodeWithText("已完成").performScrollTo().performClick()
        compose.onNodeWithText("近 7 天完成行动 1 天 · 本人确认").performScrollTo().assertIsDisplayed()
        screenshot("02-done")
        compose.onNodeWithText("撤销反馈").performScrollTo().performClick()
        compose.onNodeWithText("太难了").performScrollTo().performClick()
        compose.onNodeWithText("生成更轻松的建议").performScrollTo().assertIsDisplayed()
        assertEquals(HealthCoachFeedback.TOO_HARD,plan.value.feedback)
        screenshot("03-too-hard")
    }
    @Test fun updatedDataAndGenerationKeepThePreviousActionVisible() {
        show()
        compose.onNodeWithText("数据或安排已更新；保留这次建议，重新生成后会替换。").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {generating.value=true}
        compose.onNodeWithText(plan.value.action).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新生成建议").performScrollTo().assertIsNotEnabled()
        screenshot("04-generating-keeps-action")
    }
    @Test fun preferencesAreEditableAndOldActionsAreNotPresentedAsTodaysTask() {
        show()
        compose.onNodeWithText("我的安排").performScrollTo().performClick()
        compose.onNodeWithText("睡得规律").performClick()
        compose.onNodeWithText("5 分钟").performClick()
        compose.onNodeWithText("今天有点累").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals(HealthCoachFocus.SLEEP,profile.value.focus)
        assertEquals(5,profile.value.minutes)
        assertEquals(HealthCoachEnergy.TIRED,profile.value.energy)
        compose.runOnIdle {plan.value=plan.value.copy(date=today.minusDays(1).toString())}
        compose.onNodeWithText("上次的行动").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("今天试试").assertDoesNotExist()
        screenshot("05-previous-day")
    }
    @Test fun englishDarkLargeTextKeepsActionAndControlsReadable() {
        show("en",true,1.3f)
        compose.onNodeWithText("One thing to try today").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("I will try this").performScrollTo().assertIsDisplayed()
        screenshot("06-english-dark-large-text")
    }
}
