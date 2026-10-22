package app.scalemate.health

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scalemate.R
import app.scalemate.SettingsGroup
import app.scalemate.SettingsRow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable fun HealthScreen(vm:HealthViewModel,authorize:(Set<String>)->Unit,managePermissions:()->Unit,manageServices:()->Unit,initialReminderId:String="",consumeReminder:()->Unit={},resetScroll:()->Unit={}) {
    val s by vm.state.collectAsStateWithLifecycle();val context=LocalContext.current;val scope=rememberCoroutineScope()
    val chinese=LocalConfiguration.current.locales[0].language=="zh"
    val reportQuestion=stringResource(R.string.health_report_question)
    val shareLabel=stringResource(R.string.health_share)
    var page by rememberSaveable {mutableStateOf("home")};var question by rememberSaveable {mutableStateOf("")}
    var customStart by remember {mutableStateOf(s.window.start)};var customEnd by remember {mutableStateOf(s.window.end)};var customZone by remember {mutableStateOf(s.window.zone)}
    var deleteHistory by remember {mutableStateOf(false)};var localError by remember {mutableStateOf<Int?>(null)}
    var selectedReport by rememberSaveable {mutableStateOf<String?>(null)};var selectedReminder by rememberSaveable {mutableStateOf<String?>(null)}
    var raw by rememberSaveable {mutableStateOf(false)};var exportingFile by remember {mutableStateOf<File?>(null)}
    var preview by remember {mutableStateOf("")}
    var visibleMessages by remember(s.conversation.id){mutableIntStateOf(50)}
    var loadedReminder by remember {mutableStateOf("")}
    var showAllCoverage by rememberSaveable {mutableStateOf(false)}
    var showAllMetrics by rememberSaveable {mutableStateOf(false)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val file=exportingFile
        if(uri!=null&&file!=null)scope.launch {try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {out->file.inputStream().use {it.copyTo(out)}} ?: error("No output")}}catch(_:Exception){localError=R.string.health_export_error}}
    }
    fun date(time:Long?)=time?.let {DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))} ?: "—"
    fun go(next:String){page=next}
    fun settings(){try {context.startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))}catch(_:Exception){localError=R.string.open_error}}
    fun share() {try {
        val uris=ArrayList(s.exports.map {FileProvider.getUriForFile(context,"${context.packageName}.foodphotos",it)})
        if(uris.isNotEmpty())context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type="application/octet-stream";putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData=ClipData.newRawUri("Health export",uris.first()).also {clip->uris.drop(1).forEach {clip.addItem(ClipData.Item(it))}}
        },shareLabel))
    }catch(_:Exception){localError=R.string.health_export_error}}
    LaunchedEffect(Unit){vm.reload()}
    LaunchedEffect(page){resetScroll()}
    LaunchedEffect(s.conversation.messages.lastOrNull()?.status,s.conversation.messages.lastOrNull()?.id){if(s.conversation.messages.lastOrNull()?.status=="complete"&&s.conversation.messages.lastOrNull()?.role=="assistant"&&s.conversation.messages.lastOrNull {it.role=="user"}?.text==question.trim())question=""}
    LaunchedEffect(initialReminderId){if(initialReminderId.isNotEmpty()){selectedReminder=initialReminderId;page="reminders";consumeReminder()}}
    LaunchedEffect(selectedReminder,s.reminders){s.reminders.find {it.id==selectedReminder&&it.id!=loadedReminder}?.let {r->loadedReminder=r.id;if(r.snapshotId.isNotEmpty())vm.openSnapshot(r.snapshotId)}}
    BackHandler(page!="home"){if(s.generating)vm.cancel();page="home"}
    DisposableEffect(Unit){onDispose {vm.cancel()}}
    val error=localError ?: s.error
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(page!="home")TextButton(onClick={if(s.generating)vm.cancel();page="home"}){Text("‹ "+stringResource(R.string.health_title))}
        if(error!=null)HealthCard {Text(stringResource(error),color=MaterialTheme.colorScheme.error);TextButton(onClick={localError=null;vm.clearError()}){Text(stringResource(R.string.done))}}
        if(!s.available)HealthCard {Text(stringResource(R.string.health_unavailable));TextButton(onClick=::settings){Text(stringResource(R.string.health_open_settings))}}
        if(s.busy){LinearProgressIndicator(Modifier.fillMaxWidth());if(s.progress.isNotEmpty())Text(s.progress,style=MaterialTheme.typography.bodySmall);TextButton(onClick=vm::cancel){Text(stringResource(if(s.generating)R.string.health_stop else R.string.cancel))}}
        when(page) {
            "home"->{
                Text(stringResource(R.string.health_intro),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
                Text(stringResource(R.string.health_analysis_source_note),color=MaterialTheme.colorScheme.onSurfaceVariant)
                HealthCard(MaterialTheme.colorScheme.primaryContainer) {
                    Text("${s.window.start} — ${s.window.end}",style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.health_range_note),style=MaterialTheme.typography.bodySmall)
                    if(s.snapshot!=null){HealthLabel(stringResource(R.string.health_read_at),date(s.snapshot!!.readAt));HealthLabel(stringResource(R.string.health_data_as_of),date(s.snapshot!!.dataAsOf))}
                    else Text(stringResource(R.string.health_no_data))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(enabled=!s.busy,onClick={go("range")}){Text(stringResource(R.string.health_range))};TextButton(enabled=!s.busy&&s.categories.isNotEmpty(),onClick=vm::refresh){Text(stringResource(R.string.health_refresh_short))}}
                }
                HealthCard {
                    Text(stringResource(R.string.health_permissions),fontWeight=FontWeight.Medium)
                    Text(permissionSummary(s),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick=managePermissions){Text(stringResource(R.string.health_manage_permissions))}
                }
                s.snapshot?.let {snapshot->
                    val priority=listOf("sleepMinutes","sessionMinutes","sleepBpm","nightRmssd","count","weight","energy","volume","distance")
                    val kindOrder=listOf("SleepSession","HeartRate","HeartRateVariabilityRmssd","Steps","ExerciseSession","Weight","Nutrition","ActiveCaloriesBurned","TotalCaloriesBurned","Distance")
                    val metricGroups=snapshot.evidence.filter {it.period(snapshot.window) in setOf("analysis","partial")}.filter {it.field!="recordCount"&&!it.algorithm.startsWith("range-")}.groupBy {it.kind}
                    val orderedMetrics=metricGroups.entries.sortedWith(compareBy({kindOrder.indexOf(it.key).let {index->if(index<0)Int.MAX_VALUE else index}},{it.key}))
                    val visibleMetrics=if(showAllMetrics)orderedMetrics else orderedMetrics.take(5)
                    visibleMetrics.forEach {(kind,rows)->
                        val field=priority.firstOrNull {p->rows.any {it.field==p}} ?: rows.first().field
                        val e=rows.filter {it.field==field}.maxBy {it.date}
                        HealthCard {
                            Text(HealthCatalog.kind(kind).label(chinese),style=MaterialTheme.typography.titleMedium)
                            Text(stringResource(when(e.field){"sleepMinutes"->R.string.health_metric_sleep;"sessionMinutes"->R.string.health_metric_session;"sleepBpm"->R.string.health_metric_sleep_hr;else->if(e.algorithm=="daily-median-v1")R.string.health_metric_daily_median else R.string.health_evidence}),style=MaterialTheme.typography.labelMedium)
                            Text(healthMetricDisplay(e),style=MaterialTheme.typography.headlineMedium)
                            if(e.partial)Text(stringResource(R.string.health_partial),style=MaterialTheme.typography.bodySmall)
                            Text(e.date+" · "+e.source,style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={vm.evidence(e.id)}){Text(stringResource(R.string.health_evidence))}
                        }
                    }
                    if(orderedMetrics.size>visibleMetrics.size)TextButton(onClick={showAllMetrics=true}){Text(stringResource(R.string.health_metrics_all,orderedMetrics.size-visibleMetrics.size))}
                    else if(showAllMetrics&&orderedMetrics.size>5)TextButton(onClick={showAllMetrics=false}){Text(stringResource(R.string.health_metrics_fewer))}
                    if(snapshot.warnings.isNotEmpty())Text(stringResource(R.string.health_limitations)+" · "+stringResource(R.string.health_range_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(enabled=!s.busy&&s.snapshot?.analysisEvidence()?.isNotEmpty()==true,onClick={vm.ask(reportQuestion,true);go("chat")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_report))}
                OutlinedButton(onClick={go("chat")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_chat))}
                for(preset in listOf(R.string.health_preset_sleep,R.string.health_preset_activity,R.string.health_preset_intake)){val presetQuestion=stringResource(preset);TextButton(onClick={question=presetQuestion;go("chat")}){Text(presetQuestion)}}
                SettingsGroup(stringResource(R.string.health_title)) {
                    SettingsRow(stringResource(R.string.health_reports),"${s.reports.size}"){go("reports")}
                    SettingsRow(stringResource(R.string.health_export),stringResource(R.string.health_export_note)){go("export")}
                    SettingsRow(stringResource(R.string.health_reminders),stringResource(R.string.health_reminders_note)){go("reminders")}
                    SettingsRow(stringResource(R.string.health_goals),stringResource(R.string.health_goals_note)){go("goals")}
                    SettingsRow(stringResource(R.string.health_privacy),stringResource(R.string.health_retention)){go("privacy")}
                }
                s.snapshot?.let {snapshot->
                    Text(stringResource(R.string.health_coverage),style=MaterialTheme.typography.titleLarge)
                    val notable=snapshot.coverage.filter {it.status in setOf("available","partial","read_failed")}
                    val shown=if(showAllCoverage)snapshot.coverage else notable
                    if(shown.isEmpty())HealthCard {Text(stringResource(R.string.health_coverage_none),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    shown.forEach {c->HealthCard {Text(HealthCatalog.kind(c.kind).label(chinese),fontWeight=FontWeight.Medium);Text(stringResource(statusResource(c.status)));HealthLabel(stringResource(R.string.health_records),c.records.toString());HealthLabel(stringResource(R.string.health_points),c.points.toString());if(c.sources.isNotEmpty())Text(c.sources.joinToString(" · "),style=MaterialTheme.typography.bodySmall)}}
                    if(snapshot.coverage.size>notable.size)TextButton(onClick={showAllCoverage=!showAllCoverage}){Text(stringResource(if(showAllCoverage)R.string.health_coverage_fewer else R.string.health_coverage_all,snapshot.coverage.size-notable.size))}
                }
            }
            "range"->{
                Text(stringResource(R.string.health_range),style=MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for((days,label) in listOf(7L to R.string.health_seven,28L to R.string.health_twentyeight,90L to R.string.health_ninety))FilterChip(enabled=!s.busy,selected=!s.window.allHistory&&s.window==HealthWindow.recent(days),onClick={vm.window(HealthWindow.recent(days))},label={Text(stringResource(label))})
                    FilterChip(enabled=!s.busy,selected=s.window.allHistory,onClick={vm.window(HealthWindow.recent(7).copy(allHistory=true))},label={Text(stringResource(R.string.health_all))})
                    FilterChip(enabled=!s.busy,selected=false,onClick={customStart=s.window.start;customEnd=s.window.end;customZone=s.window.zone;go("custom")},label={Text(stringResource(R.string.health_custom))})
                }
                Text("${s.window.start} — ${s.window.end}");Text(stringResource(R.string.health_range_note))
                if(s.historySupported&&!s.historyGranted)Text(stringResource(R.string.health_history_disabled),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled=!s.busy&&s.categories.isNotEmpty(),onClick={vm.refresh();go("home")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_refresh))}
            }
            "custom"->{
                Text(stringResource(R.string.health_custom),style=MaterialTheme.typography.headlineSmall)
                OutlinedTextField(customStart,{customStart=it},label={Text(stringResource(R.string.health_start))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(customEnd,{customEnd=it},label={Text(stringResource(R.string.health_end))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(customZone,{customZone=it},label={Text(stringResource(R.string.health_timezone))},modifier=Modifier.fillMaxWidth())
                Button(onClick={try {require(LocalDate.parse(customEnd)<=LocalDate.now().plusDays(1));vm.window(HealthWindow(customStart,customEnd,customZone.trim()));go("range")}catch(_:Exception){localError=R.string.health_date_invalid}},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.done))}
            }
            "chat"->{
                Text(stringResource(R.string.health_chat),style=MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.health_service_switch_note),style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){s.services.forEach {service->FilterChip(enabled=!s.busy,selected=s.serviceId==service.id,onClick={vm.selectService(service.id)},label={Text(service.name+" · "+service.model)})}}
                Row {TextButton(onClick=manageServices){Text(stringResource(R.string.health_manage_services))};TextButton(enabled=!s.busy&&s.serviceId.isNotEmpty(),onClick={vm.selectService(s.serviceId,true)}){Text(stringResource(R.string.health_service_default))}}
                s.snapshot?.let {Text("${it.window.start} — ${it.window.end} · "+date(it.dataAsOf),style=MaterialTheme.typography.bodySmall)}
                if(s.snapshot?.analysisEvidence().isNullOrEmpty())Text(stringResource(R.string.health_no_personal_data))
                Row {TextButton(enabled=!s.busy,onClick=vm::newConversation){Text(stringResource(R.string.health_new_chat))};TextButton(onClick={go("conversations")}){Text(stringResource(R.string.health_conversations))}}
                if(s.conversation.messages.size>12)Text(stringResource(R.string.health_context_note),style=MaterialTheme.typography.bodySmall)
                if(s.conversation.messages.size>visibleMessages)TextButton(onClick={visibleMessages+=50}){Text(stringResource(R.string.health_earlier_messages))}
                s.conversation.messages.takeLast(visibleMessages).forEach {message->HealthCard(if(message.role=="user")MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    if(message.role=="assistant")Text(message.serviceName+" · "+message.model,style=MaterialTheme.typography.labelSmall)
                    if(message.text.isNotEmpty())Text(message.text.replace(Regex("\\[[a-f0-9]{20,24}(?:-(?:median|min|max))?\\]"),""))
                    if(message.status!="complete"&&message.text.isBlank())Text(stringResource(when(message.status){"generating"->R.string.health_pending;"cancelled"->R.string.health_cancelled;else->R.string.health_failed}),style=MaterialTheme.typography.bodySmall)
                    FlowRow {message.evidenceIds.forEach {id->val e=s.snapshot?.evidence?.find {it.id==id};TextButton(onClick={vm.evidence(id,message.snapshotId)}){Text(e?.let {HealthCatalog.kind(it.kind).label(chinese)+" · "+healthMetricDisplay(it)} ?: stringResource(R.string.health_evidence))}}}
                }}
                OutlinedTextField(value=question,onValueChange={question=it},enabled=!s.busy,label={Text(stringResource(R.string.health_question))},modifier=Modifier.fillMaxWidth(),minLines=2)
                Button(enabled=!s.busy&&question.isNotBlank(),onClick={vm.ask(question)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_send))}
                TextButton(enabled=!s.busy&&s.conversation.messages.any {it.role=="user"},onClick={s.conversation.messages.lastOrNull {it.role=="user"}?.let {vm.ask(it.text)}}){Text(stringResource(R.string.health_retry))}
                TextButton(onClick={go("export")}){Text(stringResource(R.string.health_export))}
            }
            "conversations"->{
                Text(stringResource(R.string.health_conversations),style=MaterialTheme.typography.headlineSmall)
                if(s.conversations.isEmpty())Text(stringResource(R.string.health_empty))
                s.conversations.forEach {c->HealthCard {Text(c.title);TextButton(enabled=!s.busy,onClick={vm.openConversation(c);c.messages.lastOrNull {it.snapshotId.isNotEmpty()}?.let {vm.openSnapshot(it.snapshotId)};go("chat")}){Text(stringResource(R.string.health_chat))};TextButton(enabled=!s.busy,onClick={vm.deleteConversation(c.id)}){Text(stringResource(R.string.health_delete))}}}
            }
            "reports"->{
                Text(stringResource(R.string.health_reports),style=MaterialTheme.typography.headlineSmall)
                if(s.reports.isEmpty())Text(stringResource(R.string.health_empty))
                s.reports.forEach {r->HealthCard {Text(date(r.message.createdAt));Text(r.message.text.take(200).replace(Regex("\\[[a-f0-9-]+\\]"),""));TextButton(enabled=!s.busy,onClick={selectedReport=r.id;vm.openSnapshot(r.snapshotId);go("report")}){Text(stringResource(R.string.health_evidence))};TextButton(enabled=!s.busy,onClick={vm.deleteReport(r.id)}){Text(stringResource(R.string.health_delete))}}}
            }
            "report"->s.reports.find {it.id==selectedReport}?.let {r->
                Text(date(r.message.createdAt),style=MaterialTheme.typography.titleMedium)
                Text(r.message.text.replace(Regex("\\[[a-f0-9-]+\\]"),""))
                r.message.evidenceIds.forEach {id->TextButton(onClick={vm.evidence(id)}){Text(stringResource(R.string.health_evidence)+" · "+(s.snapshot?.evidence?.find {it.id==id}?.date ?: ""))}}
                Button(onClick={vm.openConversation(HealthConversation(messages=listOf(r.message)));go("chat")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_continue_report))}
            }
            "export"->{
                Text(stringResource(R.string.health_export),style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.health_export_note));Text(stringResource(R.string.health_export_warning),style=MaterialTheme.typography.bodySmall)
                Text("${s.window.start} — ${s.window.end}");TextButton(onClick={go("range")}){Text(stringResource(R.string.health_range))}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=!raw,onClick={raw=false},label={Text(stringResource(R.string.health_export_summary))});FilterChip(selected=raw,onClick={raw=true},label={Text(stringResource(R.string.health_export_raw))})}
                OutlinedTextField(value=question,onValueChange={question=it},label={Text(stringResource(R.string.health_question))},modifier=Modifier.fillMaxWidth(),minLines=2)
                Button(enabled=!s.busy&&s.snapshot!=null,onClick={vm.export(question.ifBlank {reportQuestion},raw)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_export_prepare))}
                s.exports.forEach {f->HealthCard {Text(f.name);Text("${f.length()/1024+1} KB",style=MaterialTheme.typography.bodySmall);Row {
                    TextButton(onClick={exportingFile=f;save.launch(f.name)}){Text(stringResource(R.string.health_save_file))}
                    TextButton(onClick={scope.launch {preview=withContext(Dispatchers.IO){f.bufferedReader().use {reader->val chars=CharArray(4000);val n=reader.read(chars);if(n>0)String(chars,0,n) else ""}}}}){Text(stringResource(R.string.health_evidence))}
                }}}
                if(s.exports.isNotEmpty()) {
                    Button(onClick=::share,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_share))}
                    TextButton(onClick={s.exports.find {it.name=="prompt.txt"}?.let {f->scope.launch {val text=withContext(Dispatchers.IO){f.readText()};context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Health prompt",text))}}}){Text(stringResource(R.string.health_copy_prompt))}
                }
            }
            "goals"->{
                var sleep by remember(s.goals){mutableStateOf(s.goals.sleepMinutes?.toString().orEmpty())};var steps by remember(s.goals){mutableStateOf(s.goals.steps?.toString().orEmpty())}
                Text(stringResource(R.string.health_goals),style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.health_goals_note))
                OutlinedTextField(value=sleep,onValueChange={sleep=it},label={Text(stringResource(R.string.health_sleep_goal))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=steps,onValueChange={steps=it},label={Text(stringResource(R.string.health_steps_goal))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                Button(onClick={try {val g=HealthGoals(if(sleep.isBlank())null else sleep.toInt(),if(steps.isBlank())null else steps.toLong());vm.goals(g);go("home")}catch(_:Exception){localError=R.string.health_values_invalid}},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_save_goals))}
            }
            "reminders"->{
                val settings=s.reminderSettings
                Text(stringResource(R.string.health_reminders),style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.health_reminders_note))
                HealthSwitch(R.string.health_reminders_enabled,settings.enabled){on->vm.reminderSettings(settings.copy(enabled=on));if(on)managePermissions()}
                HealthSwitch(R.string.health_reminder_sleep,settings.sleep){vm.reminderSettings(settings.copy(sleep=it))}
                HealthSwitch(R.string.health_reminder_recovery,settings.recovery){vm.reminderSettings(settings.copy(recovery=it))}
                HealthSwitch(R.string.health_reminder_activity,settings.activity){vm.reminderSettings(settings.copy(activity=it))}
                HealthSwitch(R.string.health_reminder_bedtime,settings.bedtime){vm.reminderSettings(settings.copy(bedtime=it))}
                HealthTime(R.string.health_check_time,settings.checkMinute){vm.reminderSettings(settings.copy(checkMinute=it))}
                HealthTime(R.string.health_quiet_start,settings.quietStart){vm.reminderSettings(settings.copy(quietStart=it))}
                HealthTime(R.string.health_quiet_end,settings.quietEnd){vm.reminderSettings(settings.copy(quietEnd=it))}
                HealthTime(R.string.health_bedtime_time,settings.bedtimeMinute){vm.reminderSettings(settings.copy(bedtimeMinute=it))}
                HealthSwitch(R.string.health_notification_details,settings.showDetails){vm.reminderSettings(settings.copy(showDetails=it))}
                Text(stringResource(R.string.health_reminder_frequency));if(!s.backgroundGranted)Text(stringResource(R.string.health_background_fallback))
                if(s.reminderSettings.enabled)OutlinedButton(
                    onClick={
                        val permissions=buildSet {
                            add("android.permission.POST_NOTIFICATIONS")
                            if(s.backgroundSupported)add(HealthCatalog.BACKGROUND)
                        }
                        authorize(permissions)
                    },
                    modifier=Modifier.fillMaxWidth()
                ){Text(stringResource(R.string.health_reminder_permissions))}
                HealthLabel(stringResource(R.string.health_last_check),date(s.lastCheck))
                s.reminders.sortedByDescending {it.id==selectedReminder}.forEach {r->HealthCard {
                    Text(date(r.createdAt));r.topics.forEach {Text(stringResource(HealthReminders.topicResource(it)),fontWeight=FontWeight.Medium)}
                    Text(stringResource(R.string.health_reminder_old),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={vm.openSnapshot(r.snapshotId);selectedReminder=r.id}){Text(stringResource(R.string.health_evidence))}
                    if(selectedReminder==r.id) {
                        r.evidenceIds.forEach {id->TextButton(onClick={vm.evidence(id)}){Text(stringResource(R.string.health_evidence)+" · "+(s.snapshot?.evidence?.find {it.id==id}?.date ?: ""))}}
                        TextButton(onClick={question=reportQuestion;go("chat")}){Text(stringResource(R.string.health_chat))}
                    }
                    FlowRow {TextButton(onClick={vm.feedback(r.id,"helpful")}){Text(stringResource(R.string.health_helpful))};TextButton(onClick={vm.feedback(r.id,"inaccurate")}){Text(stringResource(R.string.health_inaccurate))};TextButton(onClick={vm.feedback(r.id,"pause");vm.reminderSettings(settings.copy(sleep=if("sleep" in r.topics)false else settings.sleep,recovery=if("recovery" in r.topics)false else settings.recovery,activity=if("activity" in r.topics)false else settings.activity,bedtime=if("bedtime" in r.topics)false else settings.bedtime))}){Text(stringResource(R.string.health_less_often))}}
                }}
            }
            "privacy"->{Text(stringResource(R.string.health_privacy),style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.health_retention));Text(stringResource(R.string.health_analysis_source_note));Text(stringResource(R.string.health_consent_note));OutlinedButton(enabled=!s.busy,onClick={deleteHistory=true},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_clear_history))};TextButton(onClick=::settings){Text(stringResource(R.string.health_open_settings))}}
        }
        Spacer(Modifier.height(20.dp))
    }
    if(deleteHistory)AlertDialog(onDismissRequest={deleteHistory=false},title={Text(stringResource(R.string.health_clear_history))},text={Text(stringResource(R.string.health_clear_confirm))},confirmButton={TextButton(onClick={deleteHistory=false;vm.clearHistory()}){Text(stringResource(R.string.health_delete))}},dismissButton={TextButton(onClick={deleteHistory=false}){Text(stringResource(R.string.cancel))}})
    s.consent?.let {pending->AlertDialog(onDismissRequest=vm::cancelConsent,title={Text(stringResource(R.string.health_consent_title))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(pending.service.name+" · "+pending.service.model);Text(pending.service.baseUrl);Text("${pending.window.start} — ${pending.window.end}");Text(pending.categories.map {HealthCatalog.kind(it).label(chinese)}.joinToString(" · "));Text(stringResource(R.string.health_consent_note))}},confirmButton={TextButton(onClick=vm::consent){Text(stringResource(R.string.health_consent_agree))}},dismissButton={TextButton(onClick=vm::cancelConsent){Text(stringResource(R.string.cancel))}})}
    s.selectedEvidence?.let {e->AlertDialog(onDismissRequest=vm::closeEvidence,title={Text(stringResource(R.string.health_evidence))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(HealthCatalog.kind(e.kind).label(chinese));Text(e.date);Text("${e.field}: ${e.value} ${e.unit}");HealthLabel(stringResource(R.string.health_source),e.source);HealthLabel(stringResource(R.string.health_algorithm),e.algorithm);HealthLabel(stringResource(R.string.health_points),e.count.toString());e.coverage?.let {Text("${(it*100).toInt()}%")};if(e.partial)Text(stringResource(R.string.health_partial));if(s.selectedEvidencePeriod=="baseline")Text(stringResource(R.string.health_baseline))}},confirmButton={TextButton(onClick=vm::closeEvidence){Text(stringResource(R.string.done))}})}
    if(preview.isNotEmpty())AlertDialog(onDismissRequest={preview=""},title={Text(stringResource(R.string.health_export))},text={Text(preview,modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={preview=""}){Text(stringResource(R.string.done))}})
}

private fun statusResource(status:String)=when(status){"unsupported"->R.string.health_status_unsupported;"not_authorized"->R.string.health_status_not_authorized;"route_consent_required"->R.string.health_route_consent_note;"ready"->R.string.health_status_ready;"available"->R.string.health_status_available;"no_records"->R.string.health_status_no_records;"partial"->R.string.health_status_partial;"read_failed"->R.string.health_status_read_failed;else->R.string.health_status_unknown}
@Composable private fun permissionSummary(s:HealthState):String {
    if(s.categories.isEmpty())return stringResource(R.string.health_permission_none)
    val ready=s.categories.count {s.permissions[it]=="ready"}
    val status=if(ready==s.categories.size)stringResource(R.string.health_permission_ready) else stringResource(R.string.health_permission_needs_grant)
    return stringResource(R.string.health_permission_summary,s.categories.size,status)+
        "\n"+stringResource(if(s.historyGranted)R.string.health_history_enabled else R.string.health_history_disabled)+
        " · "+stringResource(if(s.backgroundGranted)R.string.health_background_enabled else R.string.health_background_disabled)
}
@Composable private fun HealthCard(color:androidx.compose.ui.graphics.Color=MaterialTheme.colorScheme.surfaceVariant,content:@Composable ColumnScope.()->Unit){Surface(color=color,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}}
@Composable private fun HealthLabel(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(label,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,Modifier.weight(1f))}}
@Composable private fun HealthSwitch(label:Int,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(stringResource(label),Modifier.weight(1f));Switch(value,onChange)}}
@Composable private fun HealthTime(label:Int,minute:Int,onChange:(Int)->Unit){val context=LocalContext.current;OutlinedButton(onClick={TimePickerDialog(context,{_,h,m->onChange(h*60+m)},minute/60,minute%60,true).show()},modifier=Modifier.fillMaxWidth()){Text(stringResource(label)+" · "+"%02d:%02d".format(minute/60,minute%60))}}

private fun healthMetricDisplay(e:HealthEvidence):String {
    val number=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=2}
    return if(e.kind=="Weight"&&e.field=="weight"&&e.unit=="g")number.format(e.value/1000)+" kg" else number.format(e.value)+" "+e.unit
}
