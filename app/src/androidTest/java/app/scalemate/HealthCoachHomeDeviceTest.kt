package app.scalemate

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.health.HealthCoachFeedback
import app.scalemate.health.HealthCoachFocus
import app.scalemate.health.HealthCoachGoalType
import app.scalemate.health.HealthCoachPlan
import app.scalemate.health.HealthCoachPlans
import app.scalemate.health.HealthCoachProfile
import app.scalemate.health.HealthCoverage
import app.scalemate.health.HealthEvidence
import app.scalemate.health.HealthMessage
import app.scalemate.health.HealthScreen
import app.scalemate.health.HealthSnapshot
import app.scalemate.health.HealthStore
import app.scalemate.health.HealthViewModel
import app.scalemate.health.HealthWindow
import app.scalemate.health.healthHash
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking

class HealthCoachHomeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private var scroll:ScrollState?=null

    @Test fun localTodayActionLeadsHealthHomeAndWeeklyReviewCanBeConfirmed() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val name="coach-home-${System.nanoTime()}.db"
        val zone=ZoneId.systemDefault()
        val today=LocalDate.now(zone)
        val profile=HealthCoachProfile(
            focus=HealthCoachFocus.ACTIVITY,minutes=15,goalType=HealthCoachGoalType.ACTIVITY,
            target="每天活动一次",days=setOf(1,3,5),baseline="目前每周一次",
            successCriteria="每周完成三次轻量活动"
        )
        val copy=HealthCoachPlans.localAction(profile,true)
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${app.packageName} android.permission.health.READ_STEPS").close()
        }
        val snapshot=HealthSnapshot(
            window=HealthWindow.recent(7,zone=zone),categories=setOf("Steps"),
            evidence=listOf(HealthEvidence(
                healthHash("home-steps").take(24),"Steps",today.toString(),"Synthetic source","count",3500.0,"steps"
            )),
            coverage=listOf(HealthCoverage("Steps","available"))
        )
        val todayPlan=HealthCoachPlan(
            HealthMessage(role="assistant",text=copy.observation,snapshotId=snapshot.id),
            today.toString(),copy.action,profile,setOf("Steps"),HealthCoachFeedback.PROPOSED,
            kind=copy.kind,successCriteria=copy.successCriteria,fallback=copy.fallback,source="local"
        )
        val (reviewStart,_)=HealthCoachPlans.reviewWeek(today)
        val history=profile.days.sorted().mapIndexed {index,day->
            val feedback=when(index) {
                0->HealthCoachFeedback.DONE
                1->HealthCoachFeedback.PARTIAL
                else->HealthCoachFeedback.MISSED
            }
            HealthCoachPlan(
                HealthMessage(role="assistant",text="",createdAt=snapshot.readAt+index),
                reviewStart.plusDays(day-1L).toString(),copy.action,profile,emptySet(),feedback,
                kind=copy.kind,successCriteria=copy.successCriteria,fallback=copy.fallback,source="local"
            )
        }
        try {
            Store(app,name).use {db->
                val health=HealthStore(db)
                health.categories(setOf("Steps"));health.snapshot(snapshot);health.setting("activeSnapshot",snapshot.id)
                health.coachProfile(profile);health.coachPlans(listOf(todayPlan)+history,today)
            }
            val vm=HealthViewModel(app,databaseName=name)
            compose.activity.runOnUiThread {
                compose.activity.setContent {
                    MaterialTheme(colorScheme=scaleMateColorScheme(false,false)) {
                        Surface(Modifier.fillMaxSize()) {
                            val scrolling=rememberScrollState().also {scroll=it}
                            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(scrolling).padding(16.dp)) {
                                HealthScreen(vm,{},{},{})
                            }
                        }
                    }
                }
            }
            compose.waitUntil(15000){vm.state.value.loaded&&!vm.state.value.busy}
            compose.onNodeWithText("今日行动").assertExists()
            compose.onNodeWithText(copy.action).performScrollTo().assertExists()
            screenshot("01-today-action-first")

            compose.onNodeWithText("周复盘").performScrollTo().performClick()
            compose.onNodeWithText("周复盘").assertExists()
            compose.onNodeWithText("下周使用这个安排").performScrollTo().assertExists()
            screenshot("02-week-review")

            compose.onNodeWithText("下周使用这个安排").performClick()
            compose.waitUntil(5000){!vm.state.value.busy}
            assertEquals(
                HealthCoachPlans.weekReview(profile,listOf(todayPlan)+history,today).nextDays,
                vm.state.value.coachProfile.days
            )
            compose.runOnIdle {vm.cancel()}
        } finally {
            app.deleteDatabase(name)
        }
    }

    private fun screenshot(name:String) {
        compose.runOnIdle {runBlocking {scroll?.scrollTo(0)}}
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"coach-home").apply {mkdirs()}
        val file=File(root,"$name.png")
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
