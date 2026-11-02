package app.scalemate.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.scalemate.R
import java.time.LocalDate

internal fun coachFocusLabel(focus:HealthCoachFocus)=when(focus) {
    HealthCoachFocus.BALANCE->R.string.health_coach_focus_balance
    HealthCoachFocus.SLEEP->R.string.health_coach_focus_sleep
    HealthCoachFocus.ACTIVITY->R.string.health_coach_focus_activity
    HealthCoachFocus.WEIGHT->R.string.health_coach_focus_weight
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HealthCoachPreferences(
    profile:HealthCoachProfile,today:LocalDate,onSave:(HealthCoachProfile)->Unit,
    onGoals:()->Unit,onClear:()->Unit,onDismiss:()->Unit
) {
    var draft by remember {mutableStateOf(profile.forDay(today))}
    var confirmClear by remember {mutableStateOf(false)}
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(stringResource(R.string.health_coach_preferences))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.health_coach_focus_label),style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {HealthCoachFocus.entries.forEach {focus->
                FilterChip(selected=draft.focus==focus,onClick={draft=draft.copy(focus=focus)},label={Text(stringResource(coachFocusLabel(focus)))})
            }}
            Text(stringResource(R.string.health_coach_time_label),style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(5,15,30).forEach {minutes->
                FilterChip(selected=draft.minutes==minutes,onClick={draft=draft.copy(minutes=minutes)},label={Text(stringResource(R.string.health_coach_minutes,minutes))})
            }}
            FilterChip(selected=draft.energy==HealthCoachEnergy.TIRED,onClick={draft=draft.copy(energy=if(draft.energy==HealthCoachEnergy.TIRED)HealthCoachEnergy.NORMAL else HealthCoachEnergy.TIRED,energyDate=today.toString())},label={Text(stringResource(R.string.health_coach_tired_today))})
            Text(stringResource(R.string.health_coach_context_note),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={onSave(draft);onGoals()}){Text(stringResource(R.string.health_coach_numeric_goals))}
            TextButton(onClick={confirmClear=true}){Text(stringResource(R.string.health_coach_clear))}
        }},
        confirmButton={TextButton(onClick={onSave(draft)}){Text(stringResource(R.string.save))}},
        dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}}
    )
    if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text(stringResource(R.string.health_coach_clear))},text={Text(stringResource(R.string.health_coach_clear_note))},confirmButton={TextButton(onClick=onClear){Text(stringResource(R.string.health_coach_clear))}},dismissButton={TextButton(onClick={confirmClear=false}){Text(stringResource(R.string.cancel))}})
}
