package app.scalemate.food

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.scalemate.R
import kotlinx.coroutines.launch

@Composable internal fun FoodSaveButton(
    vm:FoodViewModel,state:FoodState,page:String,scroll:ScrollState,onSaved:()->Unit,modifier:Modifier=Modifier
) {
    if(!state.loaded)return
    val draft=state.draft ?: return
    val action=foodSaveAction(page,draft,state.busy,state.saving) ?: return
    val actionLabel=stringResource(if(draft.editing)R.string.food_save_changes else R.string.food_confirm_and_save)
    val scale=remember {Animatable(1f)}
    val rotation=remember {Animatable(0f)}
    val scope=rememberCoroutineScope()
    var lastNudged by rememberSaveable {mutableStateOf("")}
    var wasAtBottom by remember {mutableStateOf(false)}
    fun nudge() {
        scope.launch {
            launch {
                repeat(4){rotation.animateTo(if(it%2==0)-6f else 6f,tween(55))}
                rotation.animateTo(0f,tween(65))
            }
            scale.animateTo(1.12f,tween(90))
            scale.animateTo(1f,spring(dampingRatio=.62f,stiffness=430f))
        }
    }
    val readyKey=if(action==FoodSaveAction.CONFIRM_BATCH&&state.analysisStage=="ready")"${draft.batch?.id}:${draft.batch?.analysisMs}" else ""
    LaunchedEffect(readyKey){if(readyKey.isNotBlank()&&readyKey!=lastNudged){lastNudged=readyKey;nudge()}}
    LaunchedEffect(scroll.value,scroll.maxValue) {
        val atBottom=scroll.maxValue>0&&scroll.value>=scroll.maxValue-4
        if(atBottom&&!wasAtBottom)nudge()
        wasAtBottom=atBottom
    }
    Column(modifier,horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Surface(shape=CircleShape,color=Color(0xFF2F6B4F),contentColor=Color.White,shadowElevation=6.dp) {
            Text(actionLabel,Modifier.padding(horizontal=14.dp,vertical=7.dp),style=MaterialTheme.typography.labelLarge)
        }
        FloatingActionButton(
            onClick={
                scope.launch {scale.animateTo(.9f,tween(90));scale.animateTo(1f,spring(dampingRatio=.55f,stiffness=500f))}
                if(action==FoodSaveAction.CONFIRM_BATCH)vm.confirmAndSave(onSaved)else vm.saveLater(onSaved)
            },
            modifier=Modifier.size(72.dp).scale(scale.value).rotate(rotation.value)
                .border(3.dp,Color.White.copy(alpha=.72f),CircleShape).semantics {contentDescription=actionLabel},
            shape=CircleShape,containerColor=Color(0xFF2F6B4F),contentColor=Color.White,
            elevation=FloatingActionButtonDefaults.elevation(defaultElevation=10.dp,pressedElevation=4.dp)
        ) {Icon(Icons.Default.Check,null,Modifier.size(36.dp))}
    }
}
