package app.scalemate

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scalemate.core.Phase
import app.scalemate.core.ScaleCatalog
import app.scalemate.data.*
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val Light=lightColorScheme(primary=Color(0xFF376A52),primaryContainer=Color(0xFFCEEBDA),onPrimaryContainer=Color(0xFF123B29),secondaryContainer=Color(0xFFD6E8DA),background=Color(0xFFF7F9F5),surface=Color(0xFFF7F9F5),onSurface=Color(0xFF19221D),onSurfaceVariant=Color(0xFF526158),surfaceVariant=Color(0xFFE2E9E1))
private val Dark=darkColorScheme(primary=Color(0xFFA2D2B5),primaryContainer=Color(0xFF214F38),background=Color(0xFF111914),surface=Color(0xFF111914),surfaceVariant=Color(0xFF26372C))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ScaleMateApp(vm: ScaleViewModel, bluetooth: (() -> Unit)->Unit, authorize: ()->Unit, healthSettings: ()->Unit, googleApp: ()->Unit, language: (String)->Unit, privacy: ()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    var welcomeDone by rememberSaveable { mutableStateOf(false) }
    var candidate by remember {mutableStateOf<String?>(null)}
    var selected by remember {mutableStateOf<Measurement?>(null)}
    var rename by remember {mutableStateOf<BoundScale?>(null)}
    var name by remember {mutableStateOf("")}
    var choose by remember {mutableStateOf(false)}
    var autoDialog by remember {mutableStateOf(false)}
    var languageDialog by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<Measurement?>(null)}
    var replacing by remember {mutableStateOf<BoundScale?>(null)}
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0]
    val model=ScaleCatalog.yunmai3Se
    val modelName=stringResource(model.displayNameResource)
    val number=remember(locale) {NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=2;maximumFractionDigits=2}}
    val dates=remember(locale) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())}
    val actualPage=if(!state.loaded)"loading" else if(!state.onboarded && !welcomeDone)"welcome" else page
    val scale=state.scales.find {it.id==state.activeId}
    val colors=if(state.dynamic) {if(state.dark)dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)} else if(state.dark)Dark else Light
    fun navigate(next: String) {if(next!=page)vm.stop();page=next}
    MaterialTheme(colorScheme=colors) {
        val c=MaterialTheme.colorScheme
        Scaffold(topBar={ if(actualPage !in listOf("welcome","loading")) TopAppBar(title={Text(stringResource(when(actualPage){"devices"->R.string.devices;"records"->R.string.records;"settings"->R.string.settings;"setup"->R.string.prepare_health;else->R.string.app_name}),fontWeight=FontWeight.SemiBold)},navigationIcon={if(actualPage in listOf("devices","setup"))IconButton(onClick={navigate("settings")}){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.cancel))}}) },
            bottomBar={if(actualPage in listOf("home","records","settings"))NavigationBar(containerColor=c.surface){
                listOf(Triple("home",R.string.weigh,Icons.Default.Home),Triple("records",R.string.records,Icons.AutoMirrored.Filled.List),Triple("settings",R.string.settings,Icons.Default.Settings)).forEach{(key,label,icon)->NavigationBarItem(selected=actualPage==key,onClick={navigate(key)},icon={Icon(icon,stringResource(label))},label={Text(stringResource(label))})}
            }}
        ) {padding->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                when(actualPage) {
                    "loading"->CircularProgressIndicator()
                    "welcome"->{
                        Spacer(Modifier.height(32.dp));Text(stringResource(R.string.app_name),fontSize=32.sp,fontWeight=FontWeight.Bold)
                        Surface(shape=RoundedCornerShape(40.dp),color=c.primaryContainer,modifier=Modifier.fillMaxWidth().height(150.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Favorite,null,Modifier.size(64.dp),tint=c.primary)}}
                        Text(stringResource(R.string.tagline),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
                        Text(stringResource(R.string.intro,modelName),color=c.onSurfaceVariant)
                        Action(R.string.get_started){welcomeDone=true;page="setup"}
                    }
                    "setup"->{
                        Text(stringResource(R.string.health_steps),lineHeight=25.sp,color=c.onSurfaceVariant)
                        Pill(if(state.healthAllowed)R.string.health_ready else R.string.health_required)
                        Action(R.string.authorize,authorize)
                        OutlinedButton(onClick=healthSettings,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_settings))}
                        OutlinedButton(onClick=googleApp,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.open_google))}
                        Text(stringResource(R.string.auto_explain),style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
                        Action(R.string.add_scale){vm.onboardingDone();navigate("devices")}
                        TextButton(onClick={vm.onboardingDone();navigate("home")}){Text(stringResource(R.string.continue_local))}
                    }
                    "devices"->{
                        Text(stringResource(R.string.scan_hint,modelName),color=c.onSurfaceVariant)
                        state.scales.forEach{s->
                            val displayName=scaleDisplayName(s.name)
                            Card(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(scaleDisplayName(s.name),style=MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.device_model_and_address,model.internalModel,s.address.takeLast(5)),style=MaterialTheme.typography.bodySmall)
                                Text(stringResource(if(s.verified)R.string.verified else R.string.unverified),color=c.primary)
                                Row {TextButton(onClick={vm.select(s.id);navigate("home")}){Text(stringResource(R.string.select_scale))};TextButton(onClick={rename=s;name=displayName}){Text(stringResource(R.string.rename))}}
                                Row {TextButton(onClick={replacing=s;bluetooth{vm.discover()}}){Text(stringResource(R.string.rebind))};TextButton(onClick={vm.unbind(s.id)}){Text(stringResource(R.string.remove))}}
                            }}
                        }
                        if(state.discovering){LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.scanning));TextButton(onClick={vm.stop()}){Text(stringResource(R.string.stop))}}
                        else Action(R.string.scan){bluetooth{vm.discover()}}
                        state.candidates.forEach{(address,rssi)->OutlinedCard(onClick={candidate=address},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(modelName);Text(stringResource(R.string.signal,rssi,address.takeLast(5)),style=MaterialTheme.typography.bodySmall)}}}
                    }
                    "home"->{
                        if(scale==null) {Text(stringResource(R.string.no_scale),style=MaterialTheme.typography.headlineMedium);Action(R.string.add_scale){navigate("devices")}}
                        else {
                            OutlinedCard(onClick={choose=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){Text(scaleDisplayName(scale.name),style=MaterialTheme.typography.titleMedium);Text(if(scaleDisplayName(scale.name)==modelName)stringResource(if(scale.verified)R.string.verified else R.string.unverified) else modelName,color=c.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}
                            val session=state.session
                            val phase=session.phase
                            Surface(color=if(phase in listOf(Phase.COUNTDOWN,Phase.STABLE))c.primaryContainer else c.surfaceVariant.copy(alpha=.45f),shape=RoundedCornerShape(32.dp),modifier=Modifier.fillMaxWidth()){
                                Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)){
                                    Text(stringResource(phaseResource(phase)),style=MaterialTheme.typography.labelLarge)
                                    Row(verticalAlignment=Alignment.Bottom){Text((session.candidateKg ?: session.kg)?.let{number.format(it)} ?: "—",fontSize=64.sp,fontWeight=FontWeight.Light);Text(stringResource(R.string.unit_kg),Modifier.padding(start=8.dp,bottom=10.dp),color=c.onSurfaceVariant)}
                                    if(phase==Phase.COUNTDOWN){
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                            Box(contentAlignment=Alignment.Center){CircularProgressIndicator(progress={session.seconds/5f},modifier=Modifier.size(52.dp));Text(session.seconds.toString(),fontSize=22.sp)}
                                            Text(stringResource(R.string.countdown,session.seconds))
                                        }
                                    }
                                    if(phase==Phase.STABLE) {
                                        if(!scale.verified){Text(stringResource(R.string.verify_hint),style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick={vm.verify()}){Text(stringResource(R.string.verify_scale))}}
                                        Action(R.string.confirm_weight){vm.confirm()}
                                    }
                                    if(state.weighing) {
                                        if(phase in listOf(Phase.WAITING,Phase.MEASURING))LinearProgressIndicator(Modifier.fillMaxWidth())
                                        OutlinedButton(onClick={vm.stop()},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(R.string.cancel_weigh))}
                                    } else if(phase==Phase.SAVING)CircularProgressIndicator()
                                    else if(phase==Phase.SAVE_FAILED)Action(R.string.retry_save){vm.retrySave()}
                                    else Action(if(phase in listOf(Phase.CANCELLED,Phase.CONFIRMED))R.string.next_weigh else R.string.start_weigh){bluetooth{vm.start()}}
                                }
                            }
                            Text(stringResource(R.string.foreground_hint),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                            state.records.firstOrNull()?.let{m->Text(stringResource(R.string.recent),style=MaterialTheme.typography.titleMedium);RecordCard(m,number.format(m.kg),dates.format(Instant.ofEpochMilli(m.time))){selected=m}}
                        }
                    }
                    "records"->{
                        if(state.records.isEmpty())Text(stringResource(R.string.empty_records),color=c.onSurfaceVariant)
                        state.records.forEach{m->RecordCard(m,number.format(m.kg),dates.format(Instant.ofEpochMilli(m.time))){selected=m}}
                    }
                    "settings"->{
                        Action(R.string.devices){navigate("devices")}
                        OutlinedButton(onClick={navigate("setup")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.prepare_health))}
                        Pill(if(state.healthAllowed)R.string.health_ready else R.string.health_required)
                        Preference(R.string.auto_confirm,state.automatic){if(it)autoDialog=true else vm.automatic(false)}
                        Text(stringResource(R.string.auto_explain),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                        Preference(R.string.dark,state.dark,vm::dark)
                        Preference(R.string.dynamic,state.dynamic,vm::dynamic)
                        OutlinedButton(onClick={languageDialog=true},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.language))}
                        TextButton(onClick=privacy){Text(stringResource(R.string.privacy))}
                    }
                }
                state.message?.let{Text(stringResource(it),color=c.error,style=MaterialTheme.typography.bodyMedium)}
                Spacer(Modifier.height(24.dp))
            }
        }
        if(candidate!=null)AlertDialog(onDismissRequest={candidate=null},title={Text(stringResource(R.string.confirm_scale))},text={Text(stringResource(R.string.confirm_model,modelName,model.internalModel))},confirmButton={TextButton(onClick={vm.bind(candidate!!,replacing?.name ?: model.id,replacing?.id);candidate=null;replacing=null;navigate("home")}){Text(stringResource(R.string.bind))}},dismissButton={TextButton(onClick={candidate=null}){Text(stringResource(R.string.cancel))}})
        if(rename!=null)AlertDialog(onDismissRequest={rename=null},title={Text(stringResource(R.string.rename))},text={OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text(stringResource(R.string.name))},singleLine=true)},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={vm.rename(rename!!.id,name);rename=null}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick={rename=null}){Text(stringResource(R.string.cancel))}})
        if(choose)AlertDialog(onDismissRequest={choose=false},title={Text(stringResource(R.string.select_scale))},text={Column{state.scales.forEach{s->TextButton(onClick={vm.select(s.id);choose=false}){Text(scaleDisplayName(s.name))}};TextButton(onClick={choose=false;navigate("devices")}){Text(stringResource(R.string.add_scale))}}},confirmButton={TextButton(onClick={choose=false}){Text(stringResource(R.string.done))}})
        if(autoDialog)AlertDialog(onDismissRequest={autoDialog=false},title={Text(stringResource(R.string.auto_confirm))},text={Text(stringResource(R.string.auto_explain))},confirmButton={TextButton(onClick={vm.automatic(true);autoDialog=false}){Text(stringResource(R.string.enable))}},dismissButton={TextButton(onClick={autoDialog=false}){Text(stringResource(R.string.cancel))}})
        if(languageDialog)AlertDialog(onDismissRequest={languageDialog=false},title={Text(stringResource(R.string.language))},text={Column{listOf("" to R.string.language_system,"zh" to R.string.language_zh,"en" to R.string.language_en).forEach{(tag,label)->TextButton(onClick={languageDialog=false;language(tag)}){Text(stringResource(label))}}}},confirmButton={TextButton(onClick={languageDialog=false}){Text(stringResource(R.string.cancel))}})
        selected?.let{snapshot->val m=state.records.find{it.id==snapshot.id} ?: snapshot
            AlertDialog(onDismissRequest={selected=null},title={Text(stringResource(R.string.detail))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(number.format(m.kg)+" "+stringResource(R.string.unit_kg),style=MaterialTheme.typography.headlineLarge);Text(dates.format(Instant.ofEpochMilli(m.time)));Text(stringResource(R.string.source,scaleDisplayName(m.source)));Text(stringResource(statusResource(m.status)));if(m.status in listOf("pending","failed","permission"))TextButton(onClick={if(m.status=="permission")authorize() else vm.retry(m.id)}){Text(stringResource(if(m.status=="permission")R.string.authorize else R.string.retry))};TextButton(onClick={selected=null;deleting=m}){Text(stringResource(R.string.delete_title))}}},confirmButton={TextButton(onClick={selected=null}){Text(stringResource(R.string.done))}})
        }
        deleting?.let{m->AlertDialog(onDismissRequest={deleting=null},title={Text(stringResource(R.string.delete_title))},text={Column{Text(stringResource(R.string.delete_note));TextButton(onClick={vm.delete(m.id,false);deleting=null}){Text(stringResource(R.string.delete_local))};TextButton(onClick={vm.delete(m.id,true);deleting=null}){Text(stringResource(R.string.delete_both))}}},confirmButton={TextButton(onClick={deleting=null}){Text(stringResource(R.string.cancel))}})}
    }
}

@Composable private fun Action(label: Int, action: ()->Unit) {Button(onClick=action,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(stringResource(label))}}
@Composable private fun Pill(label: Int) {Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Text(stringResource(label),Modifier.padding(horizontal=14.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)}}
@Composable private fun Preference(label: Int, value: Boolean, action: (Boolean)->Unit) {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(stringResource(label),Modifier.weight(1f));Switch(value,action)}}
@Composable private fun RecordCard(m: Measurement, kg: String, date: String, action: ()->Unit) {Card(onClick=action,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(date,style=MaterialTheme.typography.labelMedium);Text(kg+" "+stringResource(R.string.unit_kg),fontSize=28.sp);Text(stringResource(statusResource(m.status)),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall);Text(scaleDisplayName(m.source),style=MaterialTheme.typography.bodySmall)}}}
private fun phaseResource(p: Phase)=when(p){Phase.IDLE->R.string.phase_idle;Phase.WAITING->R.string.phase_waiting;Phase.MEASURING->R.string.phase_measuring;Phase.STABLE->R.string.phase_stable;Phase.COUNTDOWN->R.string.phase_countdown;Phase.SAVING->R.string.phase_saving;Phase.SAVE_FAILED->R.string.storage_error;Phase.CONFIRMED->R.string.phase_confirmed;Phase.CANCELLED->R.string.phase_cancelled;Phase.EXPIRED->R.string.phase_expired}
private fun statusResource(s: String)=when(s){"synced"->R.string.status_synced;"writing"->R.string.status_writing;"permission"->R.string.status_permission;"failed"->R.string.status_failed;"deleting"->R.string.status_deleting;"delete_failed"->R.string.status_delete_failed;else->R.string.status_pending}

@Composable private fun scaleDisplayName(storedName: String): String {
    val model = ScaleCatalog.yunmai3Se
    return model.displayName(storedName, stringResource(model.displayNameResource))
}
