package app.scalemate.health

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scalemate.BuildConfig
import app.scalemate.R
import app.scalemate.SettingsGroup
import app.scalemate.SettingsRow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable fun HealthScreen(vm:HealthViewModel,authorize:(Set<String>)->Unit,managePermissions:()->Unit,manageServices:()->Unit,initialReminderId:String="",consumeReminder:()->Unit={},resetScroll:()->Unit={}) {
    val s by vm.state.collectAsStateWithLifecycle();val context=LocalContext.current;val scope=rememberCoroutineScope()
    val chinese=LocalConfiguration.current.locales[0].language=="zh"
    val reportQuestion=stringResource(R.string.health_report_question)
    val shareLabel=stringResource(R.string.health_share)
    var page by rememberSaveable {mutableStateOf("home")};var question by rememberSaveable {mutableStateOf("")}
    var customStart by remember {mutableStateOf(s.window.start)};var customEnd by remember {mutableStateOf(s.window.end)};var customZone by remember {mutableStateOf(s.window.zone)}
    var deleteHistory by remember {mutableStateOf(false)};var localError by remember {mutableStateOf<Int?>(null)}
    var localMessage by remember {mutableStateOf<Int?>(null)}
    var selectedReport by rememberSaveable {mutableStateOf<String?>(null)};var selectedReminder by rememberSaveable {mutableStateOf<String?>(null)}
    var raw by rememberSaveable {mutableStateOf(false)};var exportingFile by remember {mutableStateOf<File?>(null)}
    var preview by remember {mutableStateOf("")}
    var visibleMessages by remember(s.conversation.id){mutableIntStateOf(50)}
    var loadedReminder by remember {mutableStateOf("")}
    var showAllMetrics by rememberSaveable {mutableStateOf(false)}
    var showAllTrends by rememberSaveable {mutableStateOf(false)}
    var view by rememberSaveable {mutableStateOf("cards")}
    var rangeWindow by remember {mutableStateOf(s.window)}
    var coachQuestion by rememberSaveable {mutableStateOf("")}
    var coachMemoryDraft by rememberSaveable {mutableStateOf("")}
    val today=LocalDate.now(ZoneId.of(s.window.zone))
    val todayMetrics=HealthOverview.today(s.snapshot,today)
    val coachBrief=remember(s.snapshot,s.goals,today){HealthCoach.brief(s.snapshot,s.goals,today)}
    val coachPlans=HealthCoachPlans.history(s.coachPlans,today).filter {plan->plan.categories.all {it in s.categories&&s.permissions[it]=="ready"}}
    val coachMessage=coachPlans.firstOrNull {it.date==today.toString()}?.message ?: coachPlans.firstOrNull()?.message ?: s.coachMessage?.takeIf {
        s.coachPlans.isEmpty()&&it.snapshotId==s.snapshot?.id&&it.evidenceIds.isNotEmpty()&&
            it.evidenceIds.all {id->s.snapshot?.evidence?.any {e->e.id==id&&s.permissions[e.kind]=="ready"}==true}&&
            Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.of(s.window.zone)).toLocalDate()==today
    }
    var coachPreferences by remember {mutableStateOf(false)}
    val configuredService=s.serviceId.takeIf {id->s.services.any {it.id==id}}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val file=exportingFile
        if(uri!=null&&file!=null)scope.launch {try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {out->file.inputStream().use {it.copyTo(out)}} ?: error("No output")}}catch(_:Exception){localError=R.string.health_export_error}}
    }
    fun date(time:Long?)=time?.let {DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))} ?: "—"
    fun go(next:String){if(next=="range"&&page!="custom")rangeWindow=s.window;page=next}
    fun disabledPage(next:String)=when {
        !BuildConfig.HEALTH_AI_ENABLED&&next in setOf("chat","conversations","reports","report","export","reminders","privacy")->true
        !BuildConfig.HEALTH_COACH_ENABLED&&next in setOf("goals","coach","coachMemory","coachReview")->true
        else->false
    }
    fun settings(){try {context.startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))}catch(_:Exception){localError=R.string.open_error}}
    fun share() {try {
        val uris=ArrayList(s.exports.map {FileProvider.getUriForFile(context,"${context.packageName}.foodphotos",it)})
        if(uris.isNotEmpty())context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type="application/octet-stream";putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData=ClipData.newRawUri("Health export",uris.first()).also {clip->uris.drop(1).forEach {clip.addItem(ClipData.Item(it))}}
        },shareLabel))
    }catch(_:Exception){localError=R.string.health_export_error}}
    LaunchedEffect(Unit){vm.reload(refreshIfStale=true)}
    LaunchedEffect(page){resetScroll()}
    LaunchedEffect(s.conversation.messages.lastOrNull()?.status,s.conversation.messages.lastOrNull()?.id){if(s.conversation.messages.lastOrNull()?.status=="complete"&&s.conversation.messages.lastOrNull()?.role=="assistant"&&s.conversation.messages.lastOrNull {it.role=="user"}?.text==question.trim())question=""}
    LaunchedEffect(s.coachConversation.messages.lastOrNull()?.status,s.coachConversation.messages.lastOrNull()?.id){if(s.coachConversation.messages.lastOrNull()?.status=="complete"&&s.coachConversation.messages.lastOrNull()?.role=="assistant"&&s.coachConversation.messages.lastOrNull {it.role=="user"}?.text==coachQuestion.trim())coachQuestion=""}
    LaunchedEffect(Unit){if(disabledPage(page))page="home"}
    LaunchedEffect(initialReminderId){if(initialReminderId.isNotEmpty()){if(BuildConfig.HEALTH_AI_ENABLED){selectedReminder=initialReminderId;page="reminders"};consumeReminder()}}
    LaunchedEffect(selectedReminder,s.reminders){s.reminders.find {it.id==selectedReminder&&it.id!=loadedReminder}?.let {r->loadedReminder=r.id;if(r.snapshotId.isNotEmpty())vm.openSnapshot(r.snapshotId)}}
    BackHandler(page!="home"){if(s.generating)vm.cancel();page="home"}
    // Sync belongs to the activity-scoped model and continues when another tab is shown.
    DisposableEffect(vm){onDispose {vm.cancelGeneration()}}
    val error=localError ?: s.error
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(page!="home")TextButton(onClick={if(s.generating)vm.cancel();page="home"}){Text("‹ "+stringResource(R.string.health_title))}
        if(error!=null)HealthCard {Text(stringResource(error),color=MaterialTheme.colorScheme.error);TextButton(onClick={localError=null;vm.clearError()}){Text(stringResource(R.string.done))}}
        if(localMessage!=null)HealthCard {Text(stringResource(localMessage!!),color=MaterialTheme.colorScheme.primary);TextButton(onClick={localMessage=null}){Text(stringResource(R.string.done))}}
        if(!s.available)HealthCard {Text(stringResource(R.string.health_unavailable));TextButton(onClick=::settings){Text(stringResource(R.string.health_open_settings))}}
        if(s.busy&&s.syncStatus!="reading_cache"){LinearProgressIndicator(Modifier.fillMaxWidth());if(s.progress.isNotEmpty())Text(s.progress,style=MaterialTheme.typography.bodySmall);TextButton(onClick=vm::cancel){Text(stringResource(if(s.generating)R.string.health_stop else R.string.cancel))}}
        when(page) {
            "home"->{
                HealthHomeHeader(s,readAt=date(s.snapshot?.readAt),asOf=date(s.snapshot?.dataAsOf),view=view,onView={view=it},onRange={go("range")},onRefresh=vm::refresh,onPermissions=managePermissions)
                if(BuildConfig.HEALTH_COACH_ENABLED) {
                    HealthCoachSection(
                        brief=coachBrief,
                        message=coachMessage,
                        configured=configuredService!=null,
                        busy=s.busy,
                        chinese=chinese,
                        onGenerate=vm::coach,
                        onConfigure=manageServices,
                        onEvidence={id->vm.evidence(id)},
                        profile=s.coachProfile.forDay(today),plans=coachPlans,today=today,
                        generating=s.coachGenerating,currentSnapshotId=s.snapshot?.id.orEmpty(),
                        onPreferences={coachPreferences=true},onFeedback=vm::coachFeedback,
                        onPlanEvidence={id,snapshotId->vm.evidence(id,snapshotId)},
                        onAsk={go("coach")},onMemory={go("coachMemory")},
                        onReview={go("coachReview")},
                        scheduleChanges=s.coachScheduleChanges
                    )
                }
                if(view=="cards") {
                    if(todayMetrics.isEmpty()&&!s.busy) {
                        HealthCard {
                            val hasRead=s.snapshot!=null&&s.categories.any {s.permissions[it]=="ready"}
                            Text(stringResource(if(hasRead)R.string.health_today_empty else R.string.health_no_data),fontWeight=FontWeight.Medium)
                            Text(stringResource(if(hasRead)R.string.health_today_empty_hint else R.string.health_no_data_today_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                if(hasRead)TextButton(onClick={view="trends"}){Text(stringResource(R.string.health_view_trends))}
                                else TextButton(onClick=managePermissions){Text(stringResource(R.string.health_manage_permissions))}
                                TextButton(enabled=!s.busy&&s.categories.isNotEmpty(),onClick=vm::refresh){Text(stringResource(R.string.health_refresh_short))}
                            }
                        }
                    } else if(todayMetrics.isNotEmpty()) {
                        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            todayMetrics.chunked(2).forEach {row->
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    row.forEach {metric->HealthMetricCard(metric,chinese,Modifier.weight(1f)){vm.evidence(metric.evidenceId)}}
                                    if(row.size==1)Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    s.snapshot?.let {snapshot->
                        Text(stringResource(R.string.health_last_synced,date(snapshot.readAt),date(snapshot.dataAsOf)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(showAllMetrics) {
                            val priority=listOf("sleepMinutes","sessionMinutes","sleepBpm","nightRmssd","count","weight","energy","volume","distance")
                            val rows=snapshot.evidence.filter {it.period(snapshot.window) in setOf("analysis","partial")}.filter {it.field!="recordCount"&&!it.algorithm.startsWith("range-")}.groupBy {it.kind}
                            val metrics=rows.map {(kind,kindRows)->
                                val field=priority.firstOrNull {p->kindRows.any {it.field==p}} ?: kindRows.first().field
                                kindRows.filter {it.field==field}.maxBy {it.date}
                            }
                            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                metrics.chunked(2).forEach {row->
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                        row.forEach {e->HealthDetailCard(e,chinese,Modifier.weight(1f)){vm.evidence(e.id)}}
                                        if(row.size==1)Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            TextButton(onClick={showAllMetrics=false}){Text(stringResource(R.string.health_metrics_fewer))}
                        } else if(todayMetrics.isNotEmpty()) {
                            TextButton(onClick={showAllMetrics=true}){Text(stringResource(R.string.health_metrics_all,snapshot.evidence.map {it.kind}.distinct().size))}
                        }
                    }
                } else {
                    val trends=HealthOverview.trendKinds.mapNotNull {kind->HealthOverview.trend(s.snapshot,kind)?.let {kind to it}}
                    if(trends.isEmpty()&&!s.busy) {
                        HealthCard {
                            Text(stringResource(R.string.health_trend_empty),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.health_no_data_today_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if(trends.isNotEmpty()) {
                        val visible=if(showAllTrends)trends else trends.take(4)
                        Text(stringResource(R.string.health_trend_missing),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        visible.forEach {(kind,trend)->HealthTrendCard(kind,trend,chinese){vm.evidence(trend.lastEvidenceId)}}
                        if(trends.size>4) {
                            TextButton(onClick={showAllTrends=!showAllTrends}) {
                                Text(stringResource(if(showAllTrends)R.string.health_trends_fewer else R.string.health_trends_all,trends.size-4))
                            }
                        }
                    }
                }
                if(BuildConfig.HEALTH_AI_ENABLED) {
                    HealthAiSection(enabled=!s.busy&&s.snapshot?.analysisEvidence()?.isNotEmpty()==true,configured=!configuredService.isNullOrEmpty(),onReport={vm.ask(reportQuestion,true);go("chat")},onChat={go("chat")},onConfigure=manageServices,onPreset={question=it;go("chat")},presets=listOf(
                        stringResource(R.string.health_preset_sleep),
                        stringResource(R.string.health_preset_activity),
                        stringResource(R.string.health_preset_intake)
                    ))
                }
                if(s.snapshot?.warnings?.isNotEmpty()==true)Text(stringResource(R.string.health_limitations)+" · "+stringResource(R.string.health_partial_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                s.snapshot?.let {snapshot->
                    if(snapshot.coverage.any {it.status=="partial"||it.status=="read_failed"}) {
                        Text(stringResource(R.string.health_partial),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    }
                }
                if(BuildConfig.HEALTH_AI_ENABLED||s.snapshot?.coverage?.any {it.sources.size>1}==true) {
                    SettingsGroup(stringResource(R.string.health_title)) {
                        if(BuildConfig.HEALTH_AI_ENABLED) {
                            SettingsRow(stringResource(R.string.health_reports),"${s.reports.size}"){go("reports")}
                            SettingsRow(stringResource(R.string.health_export),stringResource(R.string.health_export_note)){go("export")}
                            SettingsRow(stringResource(R.string.health_reminders),stringResource(R.string.health_reminders_note)){go("reminders")}
                            SettingsRow(stringResource(R.string.health_goals),stringResource(R.string.health_goals_note)){go("goals")}
                            SettingsRow(stringResource(R.string.health_privacy),stringResource(R.string.health_retention)){go("privacy")}
                        }
                        if(s.snapshot?.coverage?.any {it.sources.size>1}==true) {
                            SettingsRow(
                                stringResource(R.string.health_main_source),
                                stringResource(R.string.health_main_source_note)
                            ){go("sources")}
                        }
                    }
                }
            }
            "coach"->{
                Text(stringResource(R.string.health_coach_ask),style=MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.health_coach_ask_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(configuredService==null) {
                    HealthCard(MaterialTheme.colorScheme.primaryContainer) {
                        Text(stringResource(R.string.health_ai_not_configured),fontWeight=FontWeight.Medium)
                        Text(stringResource(R.string.health_ai_not_configured_hint),style=MaterialTheme.typography.bodySmall)
                        Button(onClick=manageServices,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_configure_ai))}
                    }
                } else {
                    val selectedService=s.services.find {it.id==s.serviceId}
                    if(selectedService!=null)Text(selectedService.name+" · "+selectedService.model,style=MaterialTheme.typography.labelSmall)
                    if(s.coachConversation.messages.isEmpty()) {
                        Text(stringResource(R.string.health_coach_conversation_empty),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    s.coachConversation.messages.takeLast(30).forEach {message->
                        HealthCard(if(message.role=="user")MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            if(message.role=="assistant")Text(message.serviceName+" · "+message.model,style=MaterialTheme.typography.labelSmall)
                            if(message.text.isNotEmpty())Text(message.text.replace(Regex("\\[[a-f0-9]{4,24}(?:-(?:median|min|max))?\\]"),""))
                            message.memoryCandidate?.let {candidate->
                                CoachMemoryCandidateCard(candidate,onAccept={vm.coachAcceptMemory(message.id)},onDismiss={vm.coachDismissCandidate(message.id)})
                            }
                            message.planChangeCandidate?.let {candidate->
                                CoachPlanCandidateCard(candidate,s.coachProfile.forDay(today),onAccept={vm.coachAcceptPlanChange(message.id)},onDismiss={vm.coachDismissCandidate(message.id)})
                            }
                            if(message.status!="complete"&&message.text.isBlank())Text(
                                stringResource(when(message.status){"generating"->R.string.health_pending;"cancelled"->R.string.health_cancelled;else->R.string.health_failed}),
                                style=MaterialTheme.typography.bodySmall
                            )
                            FlowRow {message.evidenceIds.forEach {id->val e=s.snapshot?.evidence?.find {it.id==id};TextButton(onClick={vm.evidence(id,message.snapshotId)}){Text(e?.let {HealthCatalog.kind(it.kind).label(chinese)+" · "+healthMetricDisplay(it)} ?: stringResource(R.string.health_detail))}}}
                        }
                    }
                    if(s.coachConversation.messages.lastOrNull()?.status in setOf("failed","cancelled")) {
                        TextButton(enabled=!s.busy,onClick=vm::coachRetry){Text(stringResource(R.string.health_coach_retry))}
                    }
                    val quickPrompts=listOf(
                        stringResource(R.string.health_coach_quick_goal),
                        stringResource(R.string.health_coach_quick_plan),
                        stringResource(R.string.health_coach_quick_week)
                    )
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        quickPrompts.forEach {prompt->AssistChip(enabled=!s.busy,onClick={vm.coachAsk(prompt)},label={Text(prompt)})}
                    }
                    OutlinedTextField(
                        value=coachQuestion,onValueChange={coachQuestion=it},enabled=!s.busy,
                        label={Text(stringResource(R.string.health_question))},modifier=Modifier.fillMaxWidth(),minLines=2
                    )
                    Button(
                        enabled=!s.busy&&coachQuestion.isNotBlank(),
                        onClick={vm.coachAsk(coachQuestion)},modifier=Modifier.fillMaxWidth()
                    ){Text(stringResource(R.string.health_coach_send))}
                    TextButton(enabled=!s.busy,onClick=vm::coachNewConversation){Text(stringResource(R.string.health_coach_new_conversation))}
                }
            }
            "coachMemory"->{
                Text(stringResource(R.string.health_coach_memory_title),style=MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.health_coach_memory_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value=coachMemoryDraft,onValueChange={coachMemoryDraft=it},enabled=!s.busy,
                    label={Text(stringResource(R.string.health_coach_memory_placeholder))},modifier=Modifier.fillMaxWidth(),minLines=2
                )
                Button(
                    enabled=!s.busy&&coachMemoryDraft.isNotBlank(),
                    onClick={vm.coachMemoryAdd(coachMemoryDraft);coachMemoryDraft=""},
                    modifier=Modifier.fillMaxWidth()
                ){Text(stringResource(R.string.health_coach_memory_add))}
                if(s.coachMemories.none {it.active(today)})Text(stringResource(R.string.health_coach_memory_empty))
                s.coachMemories.filter {it.active(today)}.sortedByDescending {it.createdAt}.forEach {memory->
                    HealthCard {
                        Text(memory.text)
                        TextButton(enabled=!s.busy,onClick={vm.coachMemoryRemove(memory.id)}){Text(stringResource(R.string.health_coach_memory_forget))}
                    }
                }
            }
            "coachReview"->{
                val profile=s.coachProfile.forDay(today)
                val review=HealthCoachPlans.weekReview(profile,coachPlans,today)
                val resources=LocalResources.current
                fun days(days:Set<Int>)=days.sorted().joinToString(" · ") {resources.getString(coachWeekdayLabel(it))}
                    .ifBlank {resources.getString(R.string.health_coach_weekly_empty_short)}
                Text(stringResource(R.string.health_coach_review_title),style=MaterialTheme.typography.headlineSmall)
                Text("${review.weekStart} — ${review.weekEnd}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HealthCard(MaterialTheme.colorScheme.primaryContainer.copy(alpha=.55f)) {
                    Text(
                        stringResource(R.string.health_coach_review_completion,review.completedDays,review.scheduledDays),
                        style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.health_coach_review_detail,review.partialDays,review.missedDays,review.tooHardDays),
                        style=MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(when(review.action) {
                            HealthCoachReviewAction.SET_UP->R.string.health_coach_review_setup
                            HealthCoachReviewAction.KEEP->R.string.health_coach_review_keep
                            HealthCoachReviewAction.EASE->R.string.health_coach_review_ease
                            HealthCoachReviewAction.ADVANCE->R.string.health_coach_review_advance
                        }),
                        style=MaterialTheme.typography.bodyMedium
                    )
                }
                Text(stringResource(R.string.health_coach_next_week),style=MaterialTheme.typography.titleMedium)
                HealthCard(MaterialTheme.colorScheme.surfaceVariant) {
                    Text(days(review.nextDays)+" · "+stringResource(R.string.health_coach_minutes,review.nextMinutes),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Medium)
                    Text(stringResource(R.string.health_coach_next_week_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled=!s.busy&&review.nextDays.isNotEmpty(),
                        onClick={vm.coachApplyWeekReview(review);page="home"},
                        modifier=Modifier.fillMaxWidth()
                    ){Text(stringResource(R.string.health_coach_confirm_next_week))}
                    TextButton(onClick={page="home"}){Text(stringResource(R.string.health_coach_keep_current_week))}
                }
                TextButton(onClick={go("coach")}){Text(stringResource(R.string.health_coach_ask_about_week))}
            }
            "sources"->{
                Text(stringResource(R.string.health_main_source),style=MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.health_main_source_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                s.snapshot?.let {snapshot->
                    snapshot.coverage.filter {it.kind in setOf("SleepSession","HeartRate","HeartRateVariabilityRmssd","Weight")&&it.sources.isNotEmpty()}.forEach {coverage->
                        HealthCard {
                            Text(HealthCatalog.kind(coverage.kind).label(chinese),style=MaterialTheme.typography.titleSmall)
                            coverage.sources.forEach {source->
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    RadioButton(selected=s.sources[coverage.kind]==source,onClick={vm.source(coverage.kind,source)})
                                    Text(source,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            TextButton(enabled=s.sources.containsKey(coverage.kind),onClick={vm.clearSource(coverage.kind)}){Text(stringResource(R.string.health_source_auto))}
                        }
                    }
                }
            }
            "range"->{
                Text(stringResource(R.string.health_range),style=MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for((days,label) in listOf(7L to R.string.health_seven,28L to R.string.health_twentyeight,90L to R.string.health_ninety))FilterChip(enabled=!s.busy,selected=!rangeWindow.allHistory&&rangeWindow==HealthWindow.recent(days),onClick={rangeWindow=HealthWindow.recent(days)},label={Text(stringResource(label))})
                    FilterChip(enabled=!s.busy,selected=rangeWindow.allHistory,onClick={rangeWindow=HealthWindow.recent(7).copy(allHistory=true)},label={Text(stringResource(R.string.health_all))})
                    FilterChip(enabled=!s.busy,selected=false,onClick={customStart=rangeWindow.start;customEnd=rangeWindow.end;customZone=rangeWindow.zone;go("custom")},label={Text(stringResource(R.string.health_custom))})
                }
                Text("${rangeWindow.start} — ${rangeWindow.end}");Text(stringResource(R.string.health_range_note))
                if(s.historySupported&&!s.historyGranted)Text(stringResource(R.string.health_history_disabled),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled=!s.busy&&s.categories.isNotEmpty(),onClick={vm.window(rangeWindow);go("home")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_view_period))}
            }
            "custom"->{
                Text(stringResource(R.string.health_custom),style=MaterialTheme.typography.headlineSmall)
                OutlinedTextField(customStart,{customStart=it},label={Text(stringResource(R.string.health_start))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(customEnd,{customEnd=it},label={Text(stringResource(R.string.health_end))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(customZone,{customZone=it},label={Text(stringResource(R.string.health_timezone))},modifier=Modifier.fillMaxWidth())
                Button(onClick={try {require(LocalDate.parse(customEnd)<=LocalDate.now().plusDays(1));rangeWindow=HealthWindow(customStart,customEnd,customZone.trim());go("range")}catch(_:Exception){localError=R.string.health_date_invalid}},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.done))}
            }
            "chat"->{
                Text(stringResource(R.string.health_chat),style=MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.health_service_switch_note),style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){s.services.forEach {service->FilterChip(enabled=!s.busy,selected=s.serviceId==service.id,onClick={vm.selectService(service.id)},label={Text(service.name+" · "+service.model)})}}
                Row {TextButton(onClick=manageServices){Text(stringResource(R.string.health_manage_services))};TextButton(enabled=!s.busy&&s.serviceId.isNotEmpty(),onClick={vm.selectService(s.serviceId,true)}){Text(stringResource(R.string.health_service_default))}}
                if(configuredService==null)HealthCard(MaterialTheme.colorScheme.primaryContainer) {
                    Text(stringResource(R.string.health_ai_not_configured),fontWeight=FontWeight.Medium)
                    Text(stringResource(R.string.health_ai_not_configured_hint),style=MaterialTheme.typography.bodySmall)
                    Button(onClick=manageServices,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_configure_ai))}
                }
                s.snapshot?.let {Text("${it.window.start} — ${it.window.end} · "+date(it.dataAsOf),style=MaterialTheme.typography.bodySmall)}
                if(s.snapshot?.analysisEvidence().isNullOrEmpty())Text(stringResource(R.string.health_no_personal_data))
                Row {TextButton(enabled=!s.busy,onClick=vm::newConversation){Text(stringResource(R.string.health_new_chat))};TextButton(onClick={go("conversations")}){Text(stringResource(R.string.health_conversations))}}
                if(s.conversation.messages.size>12)Text(stringResource(R.string.health_context_note),style=MaterialTheme.typography.bodySmall)
                if(s.conversation.messages.size>visibleMessages)TextButton(onClick={visibleMessages+=50}){Text(stringResource(R.string.health_earlier_messages))}
                s.conversation.messages.takeLast(visibleMessages).forEach {message->HealthCard(if(message.role=="user")MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    if(message.role=="assistant")Text(message.serviceName+" · "+message.model,style=MaterialTheme.typography.labelSmall)
                    if(message.text.isNotEmpty())Text(message.text.replace(Regex("\\[[a-f0-9]{4,24}(?:-(?:median|min|max))?\\]"),""))
                    if(message.status!="complete"&&message.text.isBlank())Text(stringResource(when(message.status){"generating"->R.string.health_pending;"cancelled"->R.string.health_cancelled;else->R.string.health_failed}),style=MaterialTheme.typography.bodySmall)
                    FlowRow {message.evidenceIds.forEach {id->val e=s.snapshot?.evidence?.find {it.id==id};TextButton(onClick={vm.evidence(id,message.snapshotId)}){Text(e?.let {HealthCatalog.kind(it.kind).label(chinese)+" · "+healthMetricDisplay(it)} ?: stringResource(R.string.health_detail))}}}
                }}
                OutlinedTextField(value=question,onValueChange={question=it},enabled=!s.busy,label={Text(stringResource(R.string.health_question))},modifier=Modifier.fillMaxWidth(),minLines=2)
                if(configuredService==null)Text(stringResource(R.string.health_no_service),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled=!s.busy&&question.isNotBlank()&&configuredService!=null,onClick={vm.ask(question)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_send))}
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
                s.reports.forEach {r->HealthCard {Text(date(r.message.createdAt));Text(r.message.text.take(200).replace(Regex("\\[[a-f0-9-]+\\]"),""));TextButton(enabled=!s.busy,onClick={selectedReport=r.id;vm.openSnapshot(r.snapshotId);go("report")}){Text(stringResource(R.string.health_detail))};TextButton(enabled=!s.busy,onClick={vm.deleteReport(r.id)}){Text(stringResource(R.string.health_delete))}}}
            }
            "report"->s.reports.find {it.id==selectedReport}?.let {r->
                Text(date(r.message.createdAt),style=MaterialTheme.typography.titleMedium)
                Text(r.message.text.replace(Regex("\\[[a-f0-9-]+\\]"),""))
                r.message.evidenceIds.forEach {id->TextButton(onClick={vm.evidence(id)}){Text(stringResource(R.string.health_detail)+" · "+(s.snapshot?.evidence?.find {it.id==id}?.date ?: ""))}}
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
                    TextButton(onClick={scope.launch {preview=withContext(Dispatchers.IO){f.bufferedReader().use {reader->val chars=CharArray(4000);val n=reader.read(chars);if(n>0)String(chars,0,n) else ""}}}}){Text(stringResource(R.string.health_preview))}
                }}}
                if(s.exports.isNotEmpty()) {
                    Button(onClick=::share,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_share))}
                    TextButton(onClick={s.exports.find {it.name=="prompt.txt"}?.let {f->scope.launch {val text=withContext(Dispatchers.IO){f.readText()};context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Health prompt",text));localMessage=R.string.health_copied}}}){Text(stringResource(R.string.health_copy_prompt))}
                }
            }
            "goals"->{
                var sleep by remember(s.goals){mutableStateOf(s.goals.sleepMinutes?.toString().orEmpty())};var steps by remember(s.goals){mutableStateOf(s.goals.steps?.toString().orEmpty())};var workouts by remember(s.goals){mutableStateOf(s.goals.workoutDays?.toString().orEmpty())}
                Text(stringResource(R.string.health_goals),style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.health_goals_note))
                OutlinedTextField(value=sleep,onValueChange={sleep=it},label={Text(stringResource(R.string.health_sleep_goal))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=steps,onValueChange={steps=it},label={Text(stringResource(R.string.health_steps_goal))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=workouts,onValueChange={workouts=it},label={Text(stringResource(R.string.health_workout_goal))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                Button(onClick={try {val g=HealthGoals(if(sleep.isBlank())null else sleep.toInt(),if(steps.isBlank())null else steps.toLong(),if(workouts.isBlank())null else workouts.toInt());vm.goals(g);go("home")}catch(_:Exception){localError=R.string.health_values_invalid}},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_save_goals))}
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
                    TextButton(onClick={vm.openSnapshot(r.snapshotId);selectedReminder=r.id}){Text(stringResource(R.string.health_detail))}
                    if(selectedReminder==r.id) {
                        r.evidenceIds.forEach {id->TextButton(onClick={vm.evidence(id)}){Text(stringResource(R.string.health_detail)+" · "+(s.snapshot?.evidence?.find {it.id==id}?.date ?: ""))}}
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
    if(coachPreferences)HealthCoachPreferences(s.coachProfile,today,
        onSave={vm.coachProfile(it);coachPreferences=false},
        onGoals={coachPreferences=false;go("goals")},
        onClear={vm.clearCoach();coachPreferences=false},onDismiss={coachPreferences=false},
        onMemory={coachPreferences=false;go("coachMemory")})
    s.consent?.let {pending->AlertDialog(onDismissRequest=vm::cancelConsent,title={Text(stringResource(R.string.health_consent_title))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(pending.service.name+" · "+pending.service.model);Text(pending.service.baseUrl);Text("${pending.window.start} — ${pending.window.end}");Text(pending.categories.map {HealthCatalog.kind(it).label(chinese)}.joinToString(" · "));Text(stringResource(when(pending.mode){HealthRequestMode.COACH->R.string.health_coach_consent_context;HealthRequestMode.COACH_FOLLOW_UP->R.string.health_coach_follow_up_consent;else->R.string.health_consent_note}))}},confirmButton={TextButton(onClick=vm::consent){Text(stringResource(R.string.health_consent_agree))}},dismissButton={TextButton(onClick=vm::cancelConsent){Text(stringResource(R.string.cancel))}})}
    s.selectedEvidence?.let {e->AlertDialog(onDismissRequest=vm::closeEvidence,title={Text(stringResource(R.string.health_detail_title))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(HealthCatalog.kind(e.kind).label(chinese),style=MaterialTheme.typography.titleMedium);Text(healthMetricDisplay(e),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold);HealthLabel(stringResource(R.string.health_date),e.date);HealthLabel(stringResource(R.string.health_source),e.source);HealthLabel(stringResource(R.string.health_metric),stringResource(metricLabelResource(e.kind,e.field)));if(s.selectedEvidenceRecordCount>0)HealthLabel(stringResource(R.string.health_records),s.selectedEvidenceRecordCount.toString());e.coverage?.let {HealthLabel(stringResource(R.string.health_coverage),"${(it*100).toInt()}%")};if(e.partial)Text(stringResource(R.string.health_partial),color=MaterialTheme.colorScheme.onSurfaceVariant);if(s.selectedEvidencePeriod=="baseline")Text(stringResource(R.string.health_baseline),color=MaterialTheme.colorScheme.onSurfaceVariant);HealthEvidenceRecords(e,s.selectedEvidenceRecords,s.selectedEvidenceReferences,chinese);Text(healthCalculationNote(e),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},confirmButton={TextButton(onClick=vm::closeEvidence){Text(stringResource(R.string.done))}})}
    if(preview.isNotEmpty())AlertDialog(onDismissRequest={preview=""},title={Text(stringResource(R.string.health_export))},text={Text(preview,modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={preview=""}){Text(stringResource(R.string.done))}})
}

private fun statusResource(status:String)=when(status){"unsupported"->R.string.health_status_unsupported;"not_authorized"->R.string.health_status_not_authorized;"route_consent_required"->R.string.health_route_consent_note;"ready"->R.string.health_status_ready;"available"->R.string.health_status_available;"no_records"->R.string.health_status_no_records;"partial"->R.string.health_status_partial;"read_failed"->R.string.health_status_read_failed;else->R.string.health_status_unknown}
private fun metricLabel(kind:String,field:String)=when {
    field=="sleepMinutes"->"sleep"
    field=="sessionMinutes"->"session"
    field=="exerciseDuration"->"exercise_duration"
    field=="sleepBpm"->"sleep_hr"
    field=="bpm"->"heart_rate"
    field=="nightRmssd"->"hrv"
    field=="count"&&kind=="Steps"->"steps"
    field=="weight"->"weight"
    field=="energy"->"energy"
    field=="distance"->"distance"
    field=="skinTemperatureBaseline"->"skin_temperature_baseline"
    field=="skinTemperatureDelta"->"skin_temperature_delta"
    field=="recordCount"->"records"
    else->"daily_median"
}
private fun metricLabelResource(kind:String,field:String)=when(metricLabel(kind,field)) {
    "sleep"->R.string.health_metric_sleep
    "session"->R.string.health_metric_session
    "exercise_duration"->R.string.health_metric_exercise_duration
    "sleep_hr"->R.string.health_metric_sleep_hr
    "heart_rate"->R.string.health_metric_heart_rate
    "hrv"->R.string.health_metric_hrv
    "steps"->R.string.health_metric_steps
    "weight"->R.string.health_metric_weight
    "energy"->R.string.health_metric_energy
    "distance"->R.string.health_metric_distance
    "skin_temperature_baseline"->R.string.health_metric_skin_temperature_baseline
    "skin_temperature_delta"->R.string.health_metric_skin_temperature
    "records"->R.string.health_metric_records
    else->R.string.health_metric_daily_median
}
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

@Composable private fun healthMetricDisplay(e:HealthEvidence):String {
    val number=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=2}
    val sleepNumber=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=1}
    return when {
        e.kind=="Weight"&&e.field=="weight"&&e.unit=="g"->number.format(e.value/1000)+" kg"
        e.kind=="SleepSession"&&e.unit=="min"->sleepNumber.format(e.value/60)+" "+stringResource(R.string.health_unit_hours)
        e.kind=="ExerciseSession"&&e.field=="exerciseDuration"->number.format(e.value)+" "+stringResource(R.string.health_unit_min)
        e.kind=="Distance"&&e.field=="distance"&&e.unit=="m"->number.format(e.value/1000)+" km"
        e.field=="skinTemperatureBaseline"->number.format(e.value)+"°C"
        e.field.startsWith("skinTemperatureDelta")->(if(e.value>0)"+" else "")+number.format(e.value)+"°C"
        e.kind=="Steps"&&e.field=="count"->number.format(e.value)+" "+stringResource(R.string.health_unit_steps)
        e.unit=="min"->number.format(e.value)+" "+stringResource(R.string.health_unit_min)
        e.field=="recordCount"->number.format(e.value)+" "+stringResource(R.string.health_metric_workouts)
        else->number.format(e.value)+" "+e.unit
    }
}
@Composable private fun HealthDetailCard(e:HealthEvidence,chinese:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(HealthCatalog.kind(e.kind).label(chinese),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(healthMetricDisplay(e),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun HealthEvidenceRecords(
    evidence:HealthEvidence,
    records:List<HealthRecord>,
    references:List<HealthDetailReference>,
    chinese:Boolean
) {
    if(records.isEmpty())return
    HorizontalDivider()
    Text(stringResource(R.string.health_record_samples),style=MaterialTheme.typography.labelLarge)
    if(evidence.kind=="ExerciseSession") {
        val sessions=records.filter {it.kind=="ExerciseSession"}
        if(sessions.isNotEmpty())Text(
            stringResource(
                R.string.health_exercise_session_summary,
                sessions.size,
                durationLabel(sessions.sumOf {it.end-it.start})
            ),
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    records.forEach {record->
        val start=Instant.ofEpochMilli(record.start).atZone(ZoneId.systemDefault())
        val end=Instant.ofEpochMilli(record.end).atZone(ZoneId.systemDefault())
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            when(evidence.kind) {
                "ExerciseSession"->{
                    val type=record.values["exerciseType"]?.value?.toInt()
                    Text(exerciseTypeLabel(type,chinese),fontWeight=FontWeight.Medium)
                    Text(start.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" – "+end.format(DateTimeFormatter.ofPattern("HH:mm"))+" · "+durationLabel(record.end-record.start),style=MaterialTheme.typography.bodySmall)
                    val segments=record.detail.takeIf {it.isNotEmpty()}?.let {runCatching {org.json.JSONObject(it).optJSONArray("segments")?.length() ?: 0}.getOrDefault(0)} ?: 0
                    if(segments>0)Text(stringResource(R.string.health_segment_count,segments),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val laps=record.detail.takeIf {it.isNotEmpty()}?.let {runCatching {org.json.JSONObject(it).optJSONArray("laps")?.length() ?: 0}.getOrDefault(0)} ?: 0
                    if(laps>0)Text(stringResource(R.string.health_lap_count,laps),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "SleepSession"->{
                    val asleep=record.sleepMinutes()
                    Text(stringResource(R.string.health_sleep_session_label),fontWeight=FontWeight.Medium)
                    Text(start.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" – "+end.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.health_sleep_asleep,formatHours(asleep)),style=MaterialTheme.typography.bodySmall)
                }
                else->{
                    Text(HealthCatalog.kind(record.kind).label(chinese),fontWeight=FontWeight.Medium)
                    Text(start.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if(references.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.health_overlapping_reference),style=MaterialTheme.typography.labelLarge)
        references.forEach {reference->
            Text(
                HealthCatalog.kind(reference.kind).label(chinese)+" · "+formatReference(reference),
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(stringResource(R.string.health_overlap_note),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun formatHours(minutes:Double):String=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=1}.format(minutes/60)+" "+stringResource(R.string.health_unit_hours)
@Composable private fun durationLabel(millis:Long):String {
    val minutes=(millis/60000).coerceAtLeast(0)
    return when {
        minutes>=60&&minutes%60>0L->"${minutes/60} "+stringResource(R.string.health_unit_hours)+" ${minutes%60} "+stringResource(R.string.health_unit_min)
        minutes>=60->"${minutes/60} "+stringResource(R.string.health_unit_hours)
        else->"$minutes "+stringResource(R.string.health_unit_min)
    }
}
private fun formatReference(reference:HealthDetailReference):String {
    val number=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=2}
    val minimum=when(reference.kind) { "Distance"->reference.minimum/1000;else->reference.minimum }
    val maximum=when(reference.kind) { "Distance"->reference.maximum/1000;else->reference.maximum }
    val unit=when(reference.kind) { "Distance"->"km";else->reference.unit }
    return if(reference.count<=1||minimum==maximum) number.format(maximum)+" "+unit
    else number.format(minimum)+"–"+number.format(maximum)+" "+unit
}
private fun exerciseTypeLabel(type:Int?,chinese:Boolean):String {
    val labels=mapOf(
        1 to ("Badminton" to "羽毛球"),2 to ("Baseball" to "棒球"),3 to ("Basketball" to "篮球"),
        4 to ("Cycling" to "骑行"),5 to ("Stationary cycling" to "室内骑行"),6 to ("Boot camp" to "训练营"),
        7 to ("Boxing" to "拳击"),8 to ("Calisthenics" to "自重训练"),9 to ("Cricket" to "板球"),
        10 to ("Dancing" to "舞蹈"),11 to ("Exercise class" to "健身课"),12 to ("Fencing" to "击剑"),
        18 to ("Gymnastics" to "体操"),20 to ("HIIT" to "高强度间歇训练"),21 to ("Hiking" to "徒步"),
        24 to ("Martial arts" to "武术"),27 to ("Pilates" to "普拉提"),29 to ("Rock climbing" to "攀岩"),
        31 to ("Rowing" to "划船"),33 to ("Running" to "跑步"),34 to ("Treadmill running" to "跑步机"),
        37 to ("Skating" to "滑冰"),38 to ("Skiing" to "滑雪"),39 to ("Snowboarding" to "单板滑雪"),
        41 to ("Soccer" to "足球"),44 to ("Stair climbing" to "爬楼"),45 to ("Strength training" to "力量训练"),
        46 to ("Stretching" to "拉伸"),48 to ("Open-water swimming" to "公开水域游泳"),49 to ("Pool swimming" to "泳池游泳"),
        50 to ("Table tennis" to "乒乓球"),51 to ("Tennis" to "网球"),52 to ("Volleyball" to "排球"),
        53 to ("Walking" to "步行"),55 to ("Weightlifting" to "举重"),56 to ("Wheelchair" to "轮椅运动"),
        57 to ("Yoga" to "瑜伽"),58 to ("Other workout" to "其他运动"),59 to ("Stair climber" to "爬楼机"),
        60 to ("Elliptical" to "椭圆机"),61 to ("Rowing machine" to "划船机")
    )
    val pair=labels[type]
    return if(pair==null) (if(chinese)"未知运动" else "Unknown exercise") else if(chinese)pair.second else pair.first
}

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable private fun HealthHomeHeader(s:HealthState,readAt:String,asOf:String,view:String,onView:(String)->Unit,onRange:()->Unit,onRefresh:()->Unit,onPermissions:()->Unit) {
    HealthViewTabs(view,onView)
    if(view=="trends") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(s.window.start+" — "+s.window.end,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            TextButton(enabled=!s.busy,onClick=onRange){Text(stringResource(R.string.health_range))}
        }
    }
    if(s.categories.isEmpty()||s.categories.any {s.permissions[it]!="ready"}) {
        HealthCard(MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(if(s.categories.isEmpty())R.string.health_permission_none else R.string.health_permission_needs_grant),fontWeight=FontWeight.Medium)
            Text(stringResource(R.string.health_permission_needed_hint),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=onPermissions){Text(stringResource(R.string.health_manage_permissions))}
        }
    }
}

@Composable private fun HealthViewTabs(view:String,onView:(String)->Unit) {
    val colors=MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant.copy(alpha=.28f))
    ) {
        HealthViewTab(stringResource(R.string.health_view_cards),view=="cards",Modifier.weight(1f)){onView("cards")}
        HealthViewTab(stringResource(R.string.health_view_trends),view=="trends",Modifier.weight(1f)){onView("trends")}
    }
}

@Composable private fun HealthViewTab(label:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val colors=MaterialTheme.colorScheme
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp))
            .background(if(selected)colors.primaryContainer.copy(alpha=.72f) else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected=selected,onClick=onClick,role=Role.Tab),
        contentAlignment=Alignment.Center
    ) {
        Text(label,style=MaterialTheme.typography.labelLarge,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,color=if(selected)colors.primary else colors.onSurfaceVariant)
    }
}

@Composable private fun HealthMetricCard(metric:HealthSummaryMetric,chinese:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(summaryMetricLabel(metric,chinese),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().height(32.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(metricValueLabel(metric),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(metricUnitLabel(metric),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            }
            metric.supplemental.firstOrNull()?.let {supplemental->
                val value=metricValueLabel(supplemental)+" "+metricUnitLabel(supplemental)
                Text(
                    stringResource(R.string.health_other_sleep,value.trim()),
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable private fun summaryMetricLabel(metric:HealthSummaryMetric,chinese:Boolean):String =
    if(metric.kind=="SleepSession"&&metric.field=="sleepMinutes")stringResource(R.string.health_metric_sleep_primary)
    else HealthCatalog.kind(metric.kind).label(chinese)

@Composable private fun metricValueLabel(metric:HealthSummaryMetric):String {
    val number=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=if(metric.kind in setOf("Weight","SleepSession","Distance","SkinTemperature"))1 else 0}
    return when {
        metric.kind=="Weight"&&metric.unit=="g"->number.format(metric.value/1000)
        metric.kind=="SleepSession"&&metric.unit=="min"->number.format(metric.value/60)
        metric.kind=="Distance"&&metric.field=="distance"&&metric.unit=="m"->number.format(metric.value/1000)
        metric.field.startsWith("skinTemperatureDelta")->(if(metric.value>0)"+" else "")+number.format(metric.value)
        metric.field=="skinTemperatureBaseline"->number.format(metric.value)
        else->number.format(metric.value)
    }
}

@Composable private fun metricUnitLabel(metric:HealthSummaryMetric)=when {
    metric.kind=="Weight"&&metric.unit=="g"->"kg"
    metric.kind=="SleepSession"&&metric.unit=="min"->stringResource(R.string.health_unit_hours)
    metric.kind=="Distance"&&metric.field=="distance"&&metric.unit=="m"->"km"
    metric.field.startsWith("skinTemperature")->"°C"
    metric.field=="count"&&metric.kind=="Steps"->stringResource(R.string.health_unit_steps)
    metric.unit=="min"->stringResource(R.string.health_unit_min)
    metric.unit=="records"->stringResource(R.string.health_metric_workouts)
    metric.field=="recordCount"->stringResource(R.string.health_metric_workouts)
    metric.unit.isEmpty()->""
    else->metric.unit
}

@Composable private fun HealthTrendCard(kind:String,trend:HealthTrendSeries,chinese:Boolean,onEvidence:()->Unit) {
    HealthCard {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(HealthCatalog.kind(kind).label(chinese),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(stringResource(metricLabelResource(kind,trend.field)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick=onEvidence){Text(stringResource(R.string.health_detail))}
        }
        HealthTrendChart(trend,Modifier.fillMaxWidth().height(190.dp))
    }
}

@Composable private fun HealthTrendChart(trend:HealthTrendSeries,modifier:Modifier=Modifier) {
    val primary=MaterialTheme.colorScheme.primary
    val muted=MaterialTheme.colorScheme.onSurfaceVariant
    val grid=MaterialTheme.colorScheme.outlineVariant
    val blank=MaterialTheme.colorScheme.onSurface.copy(alpha=.16f)
    if(trend.points.isEmpty()){Box(modifier,contentAlignment=Alignment.Center){Text(stringResource(R.string.health_trend_empty),color=muted)};return}
    val scale=trendScale(trend)
    val values=trend.points.map {scale(it.value)}
    val upper=values.maxOrNull()?.takeIf {it.isFinite()} ?: return
    val lower=values.minOrNull()?.takeIf {it.isFinite()} ?: return
    val span=(upper-lower).takeIf {it>0} ?: kotlin.math.max(kotlin.math.abs(upper)*.1,1.0)
    val top=if(trend.chartKind==HealthChartKind.BAR)kotlin.math.max(upper,0.0)+span*.10 else upper+span*.12
    val bottom=if(trend.chartKind==HealthChartKind.BAR)0.0 else lower-span*.12
    val range=(top-bottom).takeIf {it>0} ?: 1.0
    val labels=listOf(top,(top+bottom)/2,bottom).map {trendAxisLabel(trend,it)}
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(52.dp).fillMaxHeight(),verticalArrangement=Arrangement.SpaceBetween,horizontalAlignment=Alignment.End) {
                labels.forEach {Text(it,style=MaterialTheme.typography.labelSmall,color=muted,maxLines=1)}
            }
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val left=2.dp.toPx();val right=size.width-2.dp.toPx();val topY=7.dp.toPx();val bottomY=size.height-7.dp.toPx()
                repeat(3){i->
                    val y=topY+(bottomY-topY)*i/2f
                    drawLine(grid,Offset(left,y),Offset(right,y),strokeWidth=1.dp.toPx())
                }
                val dates=trendAxisDates(trend)
                val start=dates.first();val last=dates.last()
                val totalDays=ChronoUnit.DAYS.between(start,last).coerceAtLeast(0).toInt()
                val step=if(totalDays<=0)0f else (right-left)/totalDays
                fun x(date:LocalDate)=if(totalDays<=0)(left+right)/2 else left+step*ChronoUnit.DAYS.between(start,date).toInt()
                fun y(v:Double)=(bottomY-((v-bottom)/range*(bottomY-topY))).toFloat()
                if(trend.chartKind==HealthChartKind.BAR) {
                    val width=(if(totalDays>0)step*.58f else 18.dp.toPx()).coerceIn(5.dp.toPx(),28.dp.toPx())
                    trend.points.forEach {point->
                        val value=kotlin.math.max(scale(point.value),0.0)
                        val topValue=y(value)
                        val baseline=y(0.0)
                        drawRoundRect(
                            color=primary.copy(alpha=if(point.partial).30f else .78f),
                            topLeft=Offset(x(point.date)-width/2,kotlin.math.min(topValue,baseline)),
                            size=androidx.compose.ui.geometry.Size(width,kotlin.math.abs(baseline-topValue).coerceAtLeast(2.dp.toPx())),
                            cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                        )
                    }
                } else {
                    trend.segments().forEach {segment->
                        val path=Path()
                        segment.forEachIndexed {index,point->
                            val px=x(point.date);val py=y(scale(point.value))
                            if(index==0)path.moveTo(px,py) else path.lineTo(px,py)
                            drawCircle(primary,3.dp.toPx(),Offset(px,py))
                        }
                        if(segment.size>1)drawPath(path,primary,style=Stroke(width=2.5.dp.toPx(),cap=StrokeCap.Round))
                    }
                    trend.points.filter {it.partial}.forEach {point->
                        drawCircle(primary.copy(alpha=.38f),3.dp.toPx(),Offset(x(point.date),y(scale(point.value))))
                    }
                }
                trend.missingDates.forEach {date->drawCircle(blank,2.dp.toPx(),Offset(x(date),bottomY))}
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=3.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(trendAxisDates(trend).first().toString()+" — "+trend.points.last().date,style=MaterialTheme.typography.labelSmall,color=muted)
            Text(trendAxisUnit(trend),style=MaterialTheme.typography.labelSmall,color=muted)
        }
    }
}

private fun trendAxisDates(trend:HealthTrendSeries):List<LocalDate> {
    val firstMissing=trend.missingDates.firstOrNull()
    val start=if(firstMissing!=null&&firstMissing<trend.points.first().date)firstMissing else trend.points.first().date
    return listOf(start,trend.points.last().date)
}

private fun trendScale(trend:HealthTrendSeries):(Double)->Double = when {
    trend.kind=="Weight"&&trend.unit=="g"->{v->v/1000}
    trend.kind=="SleepSession"&&trend.unit=="min"->{v->v/60}
    trend.kind=="Distance"&&trend.unit=="m"->{v->v/1000}
    else->{v->v}
}

private fun trendAxisUnit(trend:HealthTrendSeries)=when {
    trend.kind=="Weight"&&trend.unit=="g"->"kg"
    trend.kind=="SleepSession"&&trend.unit=="min"->"h"
    trend.kind=="Distance"&&trend.unit=="m"->"km"
    else->trend.unit
}

@Composable private fun healthCalculationNote(e:HealthEvidence):String=when {
    e.kind=="ExerciseSession"&&e.field=="exerciseDuration"->stringResource(R.string.health_exercise_duration_note)
    e.kind=="SleepSession"->stringResource(R.string.health_sleep_detail_note)
    e.kind=="SkinTemperature"->stringResource(R.string.health_skin_temperature_detail_note)
    e.algorithm=="platform-aggregate-v1"->stringResource(R.string.health_aggregate_detail_note)
    e.field=="bpm"->stringResource(R.string.health_heart_rate_detail_note)
    e.field=="weight"->stringResource(R.string.health_weight_detail_note)
    else->stringResource(R.string.health_metric_detail_note)
}

private fun trendAxisLabel(trend:HealthTrendSeries,value:Double):String {
    val number=java.text.NumberFormat.getNumberInstance().apply {
        maximumFractionDigits=when {
            kotlin.math.abs(value)>=1000->0
            kotlin.math.abs(value)>=10->1
            else->2
        }
    }
    return number.format(value)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CoachMemoryCandidateCard(
    candidate:HealthCoachMemoryCandidate,onAccept:()->Unit,onDismiss:()->Unit
) {
    HorizontalDivider()
    Text(stringResource(R.string.health_coach_memory_candidate),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
    Text(candidate.text)
    if(candidate.expiresAt.isNotBlank())Text(
        stringResource(R.string.health_coach_memory_expires,candidate.expiresAt),
        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant
    )
    when(candidate.status) {
        "accepted"->Text(stringResource(R.string.health_coach_memory_candidate_accepted),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        "dismissed"->Text(stringResource(R.string.health_coach_candidate_dismissed),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else->FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick=onAccept){Text(stringResource(R.string.health_coach_memory_candidate_accept))}
            TextButton(onClick=onDismiss){Text(stringResource(R.string.health_coach_candidate_dismiss))}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CoachPlanCandidateCard(
    candidate:HealthCoachPlanChangeCandidate,profile:HealthCoachProfile,onAccept:()->Unit,onDismiss:()->Unit
) {
    val resources=LocalResources.current
    val currentDays=profile.days.sorted().joinToString(" · ") {resources.getString(coachWeekdayLabel(it))}
    HorizontalDivider()
    Text(
        stringResource(if(candidate.temporary)R.string.health_coach_plan_change_temporary else R.string.health_coach_plan_change_permanent),
        style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary
    )
    Text(candidate.date+" · "+candidate.action)
    candidate.minutes?.let {Text(stringResource(R.string.health_coach_minutes,it),style=MaterialTheme.typography.bodySmall)}
    if(currentDays.isNotBlank())Text(
        stringResource(R.string.health_coach_plan_change_current,currentDays,profile.minutes),
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant
    )
    if(candidate.reason.isNotBlank())Text(candidate.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(!candidate.temporary&&candidate.days.isNotEmpty()) {
        val days=candidate.days.sorted().joinToString(" · ") {resources.getString(coachWeekdayLabel(it))}
        Text(stringResource(R.string.health_coach_plan_change_days,days),style=MaterialTheme.typography.bodySmall)
    }
    when(candidate.status) {
        "accepted"->Text(
            stringResource(if(candidate.temporary)R.string.health_coach_plan_change_applied_temporary else R.string.health_coach_plan_change_applied_permanent),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary
        )
        "dismissed"->Text(stringResource(R.string.health_coach_candidate_dismissed),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else->FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick=onAccept){Text(stringResource(R.string.health_coach_plan_change_accept))}
            TextButton(onClick=onDismiss){Text(stringResource(R.string.health_coach_candidate_dismiss))}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HealthCoachSection(
    brief:HealthCoachBrief?,message:HealthMessage?,configured:Boolean,busy:Boolean,chinese:Boolean,
    onGenerate:()->Unit,onConfigure:()->Unit,onEvidence:(String)->Unit,
    profile:HealthCoachProfile,plans:List<HealthCoachPlan>,today:LocalDate,generating:Boolean,
    currentSnapshotId:String,onPreferences:()->Unit,onFeedback:(String,HealthCoachFeedback)->Unit,
    onPlanEvidence:(String,String)->Unit={_,_->},onAsk:()->Unit={},onMemory:()->Unit={},
    onReview:()->Unit={},scheduleChanges:List<HealthCoachScheduleChange> = emptyList()
) {
    val signals=brief?.signals.orEmpty()
    val hasData=signals.isNotEmpty()||(brief?.availableFactCount ?: 0)>0
    val plan=plans.firstOrNull {it.date==today.toString()} ?: plans.firstOrNull()
    val review=HealthCoachPlans.weekProgress(profile,plans,today)
    val todayChange=scheduleChanges.filter {it.active(today)}.minByOrNull {it.date}?.takeIf {it.date==today.toString()}
    var showAllSignals by remember {mutableStateOf(false)}
    val visibleSignals=if(showAllSignals)signals else emptyList()
    Surface(color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.42f),shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.health_coach_title),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                TextButton(enabled=!busy,onClick=onPreferences){Text(stringResource(R.string.health_coach_preferences))}
            }
            Text(stringResource(coachGoalLabel(profile.goalType))+(profile.target.takeIf {it.isNotBlank()}?.let {" · $it"}.orEmpty()),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(profile.energy==HealthCoachEnergy.TIRED)Text(stringResource(R.string.health_coach_tired_today),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val resources=LocalResources.current
            val dayLabels=profile.days.sorted().joinToString(" · ") {resources.getString(coachWeekdayLabel(it))}
            Text(
                if(dayLabels.isBlank())stringResource(R.string.health_coach_weekly_empty) else stringResource(R.string.health_coach_weekly_plan,dayLabels,profile.minutes),
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant
            )
            todayChange?.let {change->
                Text(stringResource(R.string.health_coach_today_change,change.action),style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium)
            }
            val upcomingChanges=scheduleChanges.filter {it.active(today)&&it.date!=today.toString()}.sortedBy {it.date}.take(3)
            if(upcomingChanges.isNotEmpty()) {
                Text(stringResource(R.string.health_coach_upcoming_changes),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                upcomingChanges.forEach {change->Text("${change.date} · ${change.action}",style=MaterialTheme.typography.bodySmall)}
            }
            if(message==null)Text(stringResource(R.string.health_coach_intro),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(message!=null&&(message.status=="complete"||plan!=null)) {
                HealthCard(MaterialTheme.colorScheme.surface.copy(alpha=.78f)) {
                    if(plan!=null&&plan.date!=today.toString())Text(stringResource(R.string.health_coach_plan_date,plan.date),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val display=message.text.replace(Regex("\\[[a-f0-9]{4,24}(?:-(?:median|min|max))?\\]"),"").trim()
                    if(display.isNotEmpty())Text(display)
                    if(plan!=null) {
                        if(plan.evidence.isNotEmpty())FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            plan.evidence.forEach {e->
                                val value=HealthPrompt.renderCoach("[${e.id}]",listOf(e),chinese).first
                                AssistChip(onClick={onPlanEvidence(e.id,plan.message.snapshotId)},label={Text(HealthCatalog.kind(e.kind).label(chinese)+" · "+(if(e.field.startsWith("recentTypical"))stringResource(R.string.health_coach_typical)+" " else "")+value)})
                            }
                        }
                        HorizontalDivider()
                        Text(stringResource(if(plan.date==today.toString())R.string.health_coach_action_today else R.string.health_coach_action_previous),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Text(plan.action.replace(Regex("\\[[a-f0-9]{4,24}(?:-(?:median|min|max))?\\]"),""),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Medium)
                        if(plan.successCriteria.isNotBlank())Text(stringResource(R.string.health_coach_success_criteria,plan.successCriteria),style=MaterialTheme.typography.bodySmall)
                        if(plan.fallback.isNotBlank())Text(stringResource(R.string.health_coach_fallback,plan.fallback),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        val status=when(plan.feedback) {
                            HealthCoachFeedback.PROPOSED->null
                            HealthCoachFeedback.ACCEPTED->R.string.health_coach_accepted
                            HealthCoachFeedback.DONE->R.string.health_coach_done_note
                            HealthCoachFeedback.PARTIAL->R.string.health_coach_partial_note
                            HealthCoachFeedback.MISSED->R.string.health_coach_missed_note
                            HealthCoachFeedback.TOO_HARD->R.string.health_coach_hard_note
                        }
                        status?.let {Text(stringResource(it),style=MaterialTheme.typography.bodySmall)}
                        if(plan.date==today.toString()) {
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(plan.feedback==HealthCoachFeedback.PROPOSED)FilledTonalButton(enabled=!busy,onClick={onFeedback(plan.message.id,HealthCoachFeedback.ACCEPTED)}){Text(stringResource(R.string.health_coach_accept))}
                                if(plan.feedback in setOf(HealthCoachFeedback.PROPOSED,HealthCoachFeedback.ACCEPTED)) {
                                    TextButton(enabled=!busy,onClick={onFeedback(plan.message.id,HealthCoachFeedback.DONE)}){Text(stringResource(R.string.health_coach_done))}
                                    TextButton(enabled=!busy,onClick={onFeedback(plan.message.id,HealthCoachFeedback.PARTIAL)}){Text(stringResource(R.string.health_coach_partial))}
                                    TextButton(enabled=!busy,onClick={onFeedback(plan.message.id,HealthCoachFeedback.MISSED)}){Text(stringResource(R.string.health_coach_missed))}
                                    TextButton(enabled=!busy,onClick={onFeedback(plan.message.id,HealthCoachFeedback.TOO_HARD)}){Text(stringResource(R.string.health_coach_too_hard))}
                                } else if(plan.feedback!=HealthCoachFeedback.PROPOSED)TextButton(enabled=!busy,onClick={onFeedback(plan.message.id,HealthCoachFeedback.PROPOSED)}){Text(stringResource(R.string.health_coach_undo))}
                            }
                        }
                        if(plan.message.snapshotId!=currentSnapshotId||plan.profile!=profile)Text(stringResource(R.string.health_coach_changed),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(message.status!="complete"&&!generating)Text(stringResource(if(message.status=="failed")R.string.health_failed else R.string.health_cancelled),style=MaterialTheme.typography.bodySmall)
                }
            }
            if(generating)Text(stringResource(R.string.health_coach_pending),style=MaterialTheme.typography.bodySmall)
            if(plans.isNotEmpty()||review.scheduledDays>0)Text(stringResource(R.string.health_coach_week_progress,review.completedDays,review.scheduledDays),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!hasData&&plan==null)Text(stringResource(R.string.health_coach_no_data),style=MaterialTheme.typography.bodySmall)
            if(plan==null) {
                FilledTonalButton(onClick=onPreferences,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_coach_set_weekly_plan))}
            }
            if(configured&&hasData) {
                if(plan==null)Button(enabled=!busy,onClick=onGenerate,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_coach_generate))}
                else TextButton(enabled=!busy,onClick=onGenerate) {
                    Text(stringResource(when {
                        plan.feedback==HealthCoachFeedback.TOO_HARD->R.string.health_coach_generate_easier
                        plan.feedback==HealthCoachFeedback.DONE&&plan.date==today.toString()&&plan.profile.focus==profile.focus->R.string.health_coach_review
                        else->R.string.health_coach_update_insight
                    }))
                }
            } else if(plan==null) {
                TextButton(onClick=onConfigure){Text(stringResource(R.string.health_coach_configure))}
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                AssistChip(enabled=!busy,onClick=onAsk,label={Text(stringResource(R.string.health_coach_ask))})
                if(plans.isNotEmpty()||review.scheduledDays>0)AssistChip(enabled=!busy,onClick=onReview,label={Text(stringResource(R.string.health_coach_review_short))})
                AssistChip(enabled=!busy,onClick=onMemory,label={Text(stringResource(R.string.health_coach_memory))})
            }
            if(signals.isNotEmpty())TextButton(onClick={showAllSignals=!showAllSignals},contentPadding=PaddingValues(0.dp)) {
                Text(stringResource(if(showAllSignals)R.string.health_coach_signals_less else R.string.health_coach_signals_all,signals.size))
            }
            visibleSignals.forEach {HealthCoachSignalRow(it,chinese,onEvidence)}
            brief?.dataAsOf?.let {Text(stringResource(R.string.health_coach_updated,Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            brief?.oldCategories?.takeIf {it.isNotEmpty()}?.let {kinds->Text(stringResource(R.string.health_coach_delayed,kinds.sorted().joinToString(" · "){HealthCatalog.kind(it).label(chinese)}),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(message==null)Text(stringResource(R.string.health_coach_privacy),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun HealthCoachSignalRow(signal:HealthCoachSignal,chinese:Boolean,onEvidence:(String)->Unit) {
    val number=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=1}
    val (title,value,detail,evidenceId)=when(signal) {
        is HealthCoachSignal.Sleep->{
            val primary=signal.primary
            val supplemental=signal.supplemental.firstOrNull()
            val extra=supplemental?.let {" · "+stringResource(R.string.health_other_sleep,healthMetricDisplay(it))}.orEmpty()
            val comparison=signal.baselineMedianMinutes?.let {stringResource(R.string.health_coach_recent_nights,coachHours(it))}.orEmpty()
            val goal=signal.goalMinutes?.let {stringResource(R.string.health_coach_goal,coachHours(it.toDouble()))}.orEmpty()
            listOf(
                stringResource(R.string.health_coach_sleep),
                coachHours(primary.value)+extra,
                listOf(comparison,goal).filter {it.isNotEmpty()}.joinToString(" · "),
                primary.id
            )
        }
        is HealthCoachSignal.Activity->{
            val comparison=signal.baselineMedianSteps?.let {stringResource(R.string.health_coach_recent_days,coachSteps(it))}.orEmpty()
            val goal=signal.goalSteps?.let {stringResource(R.string.health_coach_goal,coachSteps(it.toDouble()))}.orEmpty()
            listOf(
                stringResource(R.string.health_coach_activity),
                coachSteps(signal.latest.value),
                listOf(comparison,goal).filter {it.isNotEmpty()}.joinToString(" · "),
                signal.latest.id
            )
        }
        is HealthCoachSignal.Training->{
            val total=coachDuration(signal.totalMinutes)
            val detail=signal.goalDays?.let {stringResource(R.string.health_coach_training_detail,total,it)} ?: total
            listOf(
                stringResource(R.string.health_coach_training),
                stringResource(R.string.health_coach_active_days,signal.activeDays),
                detail,
                signal.rows.lastOrNull()?.id.orEmpty()
            )
        }
        is HealthCoachSignal.Recovery->{
            val value=number.format(signal.latest.value)+" "+signal.latest.unit
            val baseline=stringResource(R.string.health_coach_recent_nights,number.format(signal.baselineMedian)+" "+signal.latest.unit)
            listOf(
                stringResource(R.string.health_coach_recovery),
                value,
                baseline+(signal.sleepHeartRate?.let {" · "+stringResource(R.string.health_metric_sleep_hr)+" "+healthMetricDisplay(it)}.orEmpty()),
                signal.latest.id
            )
        }
        is HealthCoachSignal.Nutrition->listOf(
            stringResource(R.string.health_coach_nutrition),number.format(signal.latest.value)+" "+signal.latest.unit,
            stringResource(R.string.health_coach_nutrition_note),signal.latest.id
        )
        is HealthCoachSignal.Weight->{
            val change=(signal.latest.value-signal.earliestRecent.value)/1000
            val sign=if(change>0)"+" else ""
            listOf(
                stringResource(R.string.health_coach_weight),
                number.format(signal.latest.value/1000)+" kg",
                stringResource(R.string.health_coach_weight_change,sign+number.format(change)+" kg"),
                signal.latest.id
            )
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            if(detail.isNotEmpty())Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(evidenceId.isNotEmpty())TextButton(onClick={onEvidence(evidenceId)}){Text(stringResource(R.string.health_detail))}
    }
}

@Composable private fun coachHours(minutes:Double):String =
    java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=1}.format(minutes/60)+" "+stringResource(R.string.health_unit_hours)
@Composable private fun coachSteps(value:Double):String =
    java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=0}.format(value)+" "+stringResource(R.string.health_unit_steps)
@Composable private fun coachDuration(minutes:Double):String {
    val total=minutes.toInt().coerceAtLeast(0)
    val hours=total/60;val remainder=total%60
    return when {
        hours<=0->"$remainder "+stringResource(R.string.health_unit_min)
        remainder==0->"$hours "+stringResource(R.string.health_unit_hours)
        else->"$hours "+stringResource(R.string.health_unit_hours)+" $remainder "+stringResource(R.string.health_unit_min)
    }
}

@Composable private fun HealthAiSection(enabled:Boolean,configured:Boolean,onReport:()->Unit,onChat:()->Unit,onConfigure:()->Unit,onPreset:(String)->Unit,presets:List<String>) {
    Text(stringResource(R.string.health_ai_title),style=MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.health_ai_scope),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(configured) {
        Button(enabled=enabled,onClick=onReport,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_report))}
        OutlinedButton(onClick=onChat,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_chat))}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){presets.forEach {preset->AssistChip(onClick={onPreset(preset)},label={Text(preset)})}}
    } else {
        HealthCard(MaterialTheme.colorScheme.primaryContainer) {
            Text(stringResource(R.string.health_ai_not_configured),fontWeight=FontWeight.Medium)
            Text(stringResource(R.string.health_ai_not_configured_hint),style=MaterialTheme.typography.bodySmall)
            Button(onClick=onConfigure,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_configure_ai))}
            TextButton(onClick=onChat){Text(stringResource(R.string.health_chat))}
        }
    }
}
