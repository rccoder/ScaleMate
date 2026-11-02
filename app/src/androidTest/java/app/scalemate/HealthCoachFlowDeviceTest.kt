package app.scalemate

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.food.FoodStore
import app.scalemate.health.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real HealthScreen + ViewModel + AI, with an isolated temporary DB. Never changes saved services or health records. */
class HealthCoachFlowDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun realScreenGeneratesAdaptsPersistsAndKeepsPlanOnFailure() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHealthCoachChecks")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        require(app.packageName=="app.scalemate.fooddev")
        val model=InstrumentationRegistry.getArguments().getString("coachLiveModel").orEmpty()
        val name="coach-flow-${System.nanoTime()}.db"
        val saved=Store(app).use {db->
            val food=FoodStore(db);val health=HealthStore(db)
            val service=food.services().find {it.id==food.setting("text")} ?: error("No selected service")
            Triple(health.activeSnapshot() ?: error("No health snapshot"),service,health.goals())
        }
        val snapshot=saved.first.copy(id=healthId(),readAt=System.currentTimeMillis(),rawFile="")
        val service=if(model.isBlank())saved.second else saved.second.copy(model=model)
        try {
            Store(app,name).use {db->
                val health=HealthStore(db);health.categories(snapshot.categories);health.snapshot(snapshot);health.setting("activeSnapshot",snapshot.id);health.goals(saved.third)
                val food=FoodStore(db);food.service(service);food.setting("text",service.id)
            }
            val vm=HealthViewModel(app,databaseName=name)
            val shown=mutableStateOf(vm)
            compose.activity.runOnUiThread {compose.activity.setContent {
                MaterialTheme(colorScheme=scaleMateColorScheme(false,false)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            HealthScreen(shown.value,{},{},{})
                        }
                    }
                }
            }}
            compose.waitUntil(15000){vm.state.value.loaded&&!vm.state.value.busy}
            compose.onNodeWithText("生成 AI 解读").performScrollTo().performClick()
            compose.waitUntil(5000){vm.state.value.consent!=null||vm.state.value.coachGenerating}
            if(vm.state.value.consent!=null)compose.onNodeWithText("同意并发送").performClick()
            compose.waitUntil(60000){!vm.state.value.busy&&(vm.state.value.coachPlans.isNotEmpty()||vm.state.value.error!=null)}
            assertNull("Initial coach request failed",vm.state.value.error)
            val first=vm.state.value.coachPlans.first()
            compose.onNodeWithText("太难了").performScrollTo().performClick()
            compose.waitUntil(5000){vm.state.value.coachPlans.first().feedback==HealthCoachFeedback.TOO_HARD}
            assertFalse(vm.state.value.coachGenerating)
            compose.onNodeWithText("我的安排").performScrollTo().performClick()
            compose.onNodeWithText("坚持活动").performClick()
            compose.onNodeWithText("5 分钟").performClick()
            compose.onNodeWithText("今天有点累").performClick()
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("生成更轻松的建议").performScrollTo().performClick()
            compose.waitUntil(60000){!vm.state.value.busy&&(vm.state.value.coachPlans.first().message.id!=first.message.id||vm.state.value.error!=null)}
            assertNull("Adapted coach request failed",vm.state.value.error)
            val second=vm.state.value.coachPlans.first()
            assertTrue(second.action.isNotBlank())
            assertEquals(5,second.profile.minutes)
            compose.onNodeWithText("已完成").performScrollTo().performClick()
            compose.waitUntil(5000){vm.state.value.coachPlans.first().feedback==HealthCoachFeedback.DONE}

            // Recreate the model, then change the local snapshot revision without replacing the plan.
            val revised=snapshot.copy(id=healthId())
            Store(app,name).use {db->val health=HealthStore(db);health.snapshot(revised);health.setting("activeSnapshot",revised.id)}
            val reopened=HealthViewModel(app,databaseName=name)
            compose.waitUntil(15000){reopened.state.value.loaded}
            assertEquals(second.message.id,reopened.state.value.coachPlans.first().message.id)
            assertEquals(HealthCoachFeedback.DONE,reopened.state.value.coachPlans.first().feedback)
            compose.runOnIdle {shown.value=reopened}
            compose.onNodeWithText(second.action.replace(Regex("\\[[a-f0-9]{4,24}\\]"),"")).performScrollTo().assertIsDisplayed()

            // A known-invalid model exercises the production failure path without touching the user's service.
            Store(app,name).use {db->FoodStore(db).service(service.copy(model="invalid-coach-validation-model",version=service.version+1))}
            reopened.reload()
            compose.waitUntil(10000){reopened.state.value.services.any {it.version==service.version+1}}
            compose.onNodeWithText("更新数据解读").performScrollTo().performClick()
            compose.waitUntil(5000){reopened.state.value.consent!=null}
            compose.onNodeWithText("同意并发送").performClick()
            compose.waitUntil(60000){!reopened.state.value.busy&&reopened.state.value.error!=null}
            assertEquals(second.message.id,reopened.state.value.coachPlans.first().message.id)
            assertEquals(HealthCoachFeedback.DONE,reopened.state.value.coachPlans.first().feedback)
            compose.onNodeWithText(second.action.replace(Regex("\\[[a-f0-9]{4,24}\\]"),"")).performScrollTo().assertIsDisplayed()
            compose.runOnIdle {vm.cancel();reopened.cancel()}
        } finally {
            app.deleteDatabase(name)
        }
    }
}
