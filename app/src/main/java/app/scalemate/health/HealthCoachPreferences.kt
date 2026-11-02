package app.scalemate.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
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

internal fun coachGoalLabel(goal:HealthCoachGoalType)=when(goal) {
    HealthCoachGoalType.HABIT->R.string.health_coach_goal_habit
    HealthCoachGoalType.SLEEP->R.string.health_coach_goal_sleep
    HealthCoachGoalType.ACTIVITY->R.string.health_coach_goal_activity
    HealthCoachGoalType.WEIGHT->R.string.health_coach_goal_weight
    HealthCoachGoalType.BODY_FAT->R.string.health_coach_goal_body_fat
    HealthCoachGoalType.STRENGTH->R.string.health_coach_goal_strength
}

internal fun coachWeekdayLabel(day:Int)=when(day) {
    1->R.string.health_coach_weekday_mon
    2->R.string.health_coach_weekday_tue
    3->R.string.health_coach_weekday_wed
    4->R.string.health_coach_weekday_thu
    5->R.string.health_coach_weekday_fri
    6->R.string.health_coach_weekday_sat
    else->R.string.health_coach_weekday_sun
}

private fun focusFor(goal:HealthCoachGoalType)=when(goal) {
    HealthCoachGoalType.SLEEP->HealthCoachFocus.SLEEP
    HealthCoachGoalType.ACTIVITY,HealthCoachGoalType.STRENGTH->HealthCoachFocus.ACTIVITY
    HealthCoachGoalType.WEIGHT,HealthCoachGoalType.BODY_FAT->HealthCoachFocus.WEIGHT
    HealthCoachGoalType.HABIT->HealthCoachFocus.BALANCE
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HealthCoachPreferences(
    profile:HealthCoachProfile,today:LocalDate,onSave:(HealthCoachProfile)->Unit,
    onGoals:()->Unit,onClear:()->Unit,onDismiss:()->Unit,onMemory:()->Unit={}
) {
    var draft by remember {mutableStateOf(profile.forDay(today))}
    var confirmClear by remember {mutableStateOf(false)}
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(stringResource(R.string.health_coach_preferences))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.health_coach_goal_label),style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {HealthCoachGoalType.entries.forEach {goal->
                FilterChip(selected=draft.goalType==goal,onClick={draft=draft.copy(goalType=goal,focus=focusFor(goal))},label={Text(stringResource(coachGoalLabel(goal)))})
            }}
            OutlinedTextField(
                value=draft.target,onValueChange={draft=draft.copy(target=it.take(120))},
                label={Text(stringResource(R.string.health_coach_target_label))},
                modifier=Modifier.fillMaxWidth(),singleLine=true
            )
            OutlinedTextField(
                value=draft.baseline,onValueChange={draft=draft.copy(baseline=it.take(160))},
                label={Text(stringResource(R.string.health_coach_baseline_label))},
                modifier=Modifier.fillMaxWidth(),singleLine=true
            )
            OutlinedTextField(
                value=draft.deadline,onValueChange={draft=draft.copy(deadline=it.take(10))},
                label={Text(stringResource(R.string.health_coach_deadline_label))},
                placeholder={Text("YYYY-MM-DD")},
                modifier=Modifier.fillMaxWidth(),singleLine=true
            )
            OutlinedTextField(
                value=draft.successCriteria,onValueChange={draft=draft.copy(successCriteria=it.take(240))},
                label={Text(stringResource(R.string.health_coach_success_label))},
                modifier=Modifier.fillMaxWidth(),minLines=2
            )
            Text(stringResource(R.string.health_coach_focus_label),style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {HealthCoachFocus.entries.forEach {focus->
                FilterChip(selected=draft.focus==focus,onClick={draft=draft.copy(focus=focus)},label={Text(stringResource(coachFocusLabel(focus)))})
            }}
            Text(stringResource(R.string.health_coach_time_label),style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(5,15,30).forEach {minutes->
                FilterChip(selected=draft.minutes==minutes,onClick={draft=draft.copy(minutes=minutes)},label={Text(stringResource(R.string.health_coach_minutes,minutes))})
            }}
            Text(stringResource(R.string.health_coach_days_label),style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {(1..7).forEach {day->
                FilterChip(
                    selected=day in draft.days,
                    onClick={draft=draft.copy(days=if(day in draft.days)draft.days-day else draft.days+day)},
                    label={Text(stringResource(coachWeekdayLabel(day)))}
                )
            }}
            FilterChip(selected=draft.energy==HealthCoachEnergy.TIRED,onClick={draft=draft.copy(energy=if(draft.energy==HealthCoachEnergy.TIRED)HealthCoachEnergy.NORMAL else HealthCoachEnergy.TIRED,energyDate=today.toString())},label={Text(stringResource(R.string.health_coach_tired_today))})
            OutlinedTextField(
                value=draft.equipment,onValueChange={draft=draft.copy(equipment=it.take(120))},
                label={Text(stringResource(R.string.health_coach_equipment_label))},
                modifier=Modifier.fillMaxWidth(),singleLine=true
            )
            OutlinedTextField(
                value=draft.limitations,onValueChange={draft=draft.copy(limitations=it.take(240))},
                label={Text(stringResource(R.string.health_coach_limitations_label))},
                modifier=Modifier.fillMaxWidth(),minLines=2,
                keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)
            )
            Text(stringResource(R.string.health_coach_context_note),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=onMemory){Text(stringResource(R.string.health_coach_memory))}
            TextButton(onClick={onSave(draft);onGoals()}){Text(stringResource(R.string.health_coach_numeric_goals))}
            TextButton(onClick={confirmClear=true}){Text(stringResource(R.string.health_coach_clear))}
        }},
        confirmButton={TextButton(onClick={onSave(draft)}){Text(stringResource(R.string.save))}},
        dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}}
    )
    if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text(stringResource(R.string.health_coach_clear))},text={Text(stringResource(R.string.health_coach_clear_note))},confirmButton={TextButton(onClick=onClear){Text(stringResource(R.string.health_coach_clear))}},dismissButton={TextButton(onClick={confirmClear=false}){Text(stringResource(R.string.cancel))}})
}
