package app.scalemate.health

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.scalemate.MainActivity
import app.scalemate.ScaleMateApp
import app.scalemate.ScaleViewModel
import app.scalemate.food.FoodViewModel
import app.scalemate.update.UpdateViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager
import org.robolectric.util.ReflectionHelpers

/** Real tab navigation and sync/cache pipeline, with a paused synthetic platform source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-xxhdpi")
class HealthSyncNavigationLocalTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private val models=ViewModelStore()
    private val environment=object:ExternalResource(){override fun before(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings",0).edit().putBoolean("update.autoCheck",false).putBoolean("onboarded",true).commit()
        Shadows.shadowOf(app.packageManager).setInstallSourceInfo(app.packageName,null,null)
        Shadows.shadowOf(app.packageManager).addPermissionInfo(android.content.pm.PermissionInfo().apply {
            name="android.permission.health.READ_STEPS";packageName="android"
        })
        Shadows.shadowOf(app).grantPermissions("android.permission.health.READ_STEPS")
        val service=Class.forName("android.health.connect.aidl.IHealthConnectService").asSubclass(android.os.IInterface::class.java)
        ShadowServiceManager.addBinderService("healthconnect",service,ReflectionHelpers.createNullProxy(service))
    }}
    @get:Rule val rules:TestRule=RuleChain.outerRule(environment).around(compose)
    @After fun cleanup(){compose.runOnIdle {models.clear()}}

    private class Gate {
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        var cancelled=false
        suspend fun await() {
            entered.complete(Unit)
            try {release.await()}catch(e:CancellationException){cancelled=true;throw e}
        }
    }
    private class Source:HealthSyncSource {
        var gate=Gate()
        var fullReads=0
        var changeReads=0
        var steps=6800.0
        private val time=maxOf(Instant.now().minusSeconds(120).toEpochMilli(),LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        private fun row()=HealthRecord("walk","Steps","watch",time,time+60000,mapOf("count" to HealthValue(steps,"steps")),platformId="walk-platform")
        override fun status(category:String)="ready"
        override fun fingerprint(category:String)="ready"
        override fun incremental(category:String)=true
        override suspend fun full(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,progress:(String)->Unit):HealthSyncRead {
            fullReads++;progress("Steps");gate.await()
            return HealthSyncRead(HealthSnapshot(window=window,categories=categories,evidence=emptyList(),coverage=listOf(healthCoverage("Steps",listOf(row()))),syncTokens=mapOf("Steps" to "full-token")),listOf(row()))
        }
        override suspend fun changes(category:String,token:String):HealthChanges {
            changeReads++;gate.await()
            return HealthChanges(listOf(row()),emptyList(),"changes-$changeReads")
        }
        override suspend fun totals(category:String,start:Instant,end:Instant,zone:ZoneId)=listOf(
            HealthRecord("aggregate:Steps:$start","Steps","Health Connect",start.toEpochMilli(),end.toEpochMilli(),mapOf("count" to HealthValue(steps,"steps")))
        )
    }
    private fun start(source:Source):HealthViewModel {
        lateinit var health:HealthViewModel
        compose.runOnIdle {
            health=HealthViewModel(RuntimeEnvironment.getApplication(),source)
            models.put("health-sync-navigation",health)
            val provider=ViewModelProvider(compose.activity)
            compose.activity.setContent {
                ScaleMateApp(
                    vm=provider[ScaleViewModel::class.java],foodVm=provider[FoodViewModel::class.java],
                    updateVm=provider[UpdateViewModel::class.java],healthVm=health,healthEntry="",
                    consumeHealthEntry={},authorizeHealth={},authorizeNutrition={},bluetooth={},
                    authorize={},healthSettings={},googleApp={},language={},privacy={}
                )
            }
        }
        await {health.state.value.loaded||health.state.value.error!=null}
        assertNull("Health settings must load before navigation: ${health.state.value.error}",health.state.value.error)
        assertEquals("The synthetic platform must expose readable steps (available=${health.state.value.available})","ready",health.state.value.permissions["Steps"])
        compose.runOnIdle {health.categories(setOf("Steps"))}
        tab("健康")
        compose.runOnIdle {health.refresh()}
        await {source.gate.entered.isCompleted}
        return health
    }
    private fun tab(label:String){compose.onNodeWithContentDescription(label,useUnmergedTree=true).performClick();compose.waitForIdle()}
    private fun await(condition:()->Boolean){compose.waitUntil(15000){compose.waitForIdle();condition()}}
    private fun finish(health:HealthViewModel,source:Source) {
        source.gate.release.complete(Unit)
        await {!health.state.value.busy}
        assertNull(health.state.value.error)
        assertEquals(source.steps,health.state.value.snapshot!!.evidence.single {it.field=="count"}.value,0.0)
    }

    @Test fun firstSyncFinishesOffscreenAndReturningShowsItsPersistedData() {
        val source=Source();val health=start(source)
        compose.onNodeWithText("今日暂无健康数据").assertDoesNotExist()
        compose.onNodeWithText("尚未读取健康数据，请先选择类别并授权读取。").assertDoesNotExist()
        tab("记录")
        assertTrue("Leaving the tab must keep the first sync running",health.state.value.busy)
        assertFalse(source.gate.cancelled)
        tab("健康")
        assertTrue(health.state.value.busy)
        tab("饮食")
        finish(health,source)
        val snapshot=health.state.value.snapshot
        tab("健康")
        compose.onNodeWithText("步数").assertExists()
        assertEquals(snapshot,health.state.value.snapshot)
        assertEquals(1,source.fullReads)
    }

    @Test fun incrementalSyncKeepsThePreviousCardsAcrossRepeatedTabSwitches() {
        val source=Source();val health=start(source);finish(health,source)
        val previous=health.state.value.snapshot
        source.gate=Gate();source.steps=7200.0
        compose.runOnIdle {health.refresh()}
        await {source.gate.entered.isCompleted}
        for(label in listOf("记录","健康","设置","健康")) {
            tab(label)
            assertTrue(health.state.value.busy)
            assertFalse(source.gate.cancelled)
            assertEquals(previous,health.state.value.snapshot)
        }
        finish(health,source)
        compose.onNodeWithText("步数").assertExists()
        assertEquals(1,source.fullReads)
        assertEquals(1,source.changeReads)
    }

    @Test fun explicitCancelStillStopsSyncAndTheNextAttemptCanFinish() {
        val source=Source();val health=start(source)
        compose.onNodeWithText("取消").performClick()
        await {!health.state.value.busy&&source.gate.cancelled}
        assertNull(health.state.value.snapshot)
        source.gate=Gate()
        compose.runOnIdle {health.refresh()}
        await {source.gate.entered.isCompleted}
        finish(health,source)
        assertEquals(2,source.fullReads)
    }

    @Test fun changingViewingPeriodsUsesCacheWithoutStartingAnotherPlatformRead() {
        val source=Source();val health=start(source);finish(health,source)
        val readAt=health.state.value.snapshot!!.readAt
        compose.onNodeWithText("近期").performClick()
        for(label in listOf("28 天","90 天","全部可读取历史","7 天")) {
            compose.onNodeWithText("查看时间范围").performScrollTo().performClick()
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText("查看").performClick()
            await {!health.state.value.busy}
            assertNotNull(health.state.value.snapshot)
            assertEquals(readAt,health.state.value.snapshot!!.readAt)
            assertEquals(1,source.fullReads)
            assertEquals(0,source.changeReads)
        }
    }
}
