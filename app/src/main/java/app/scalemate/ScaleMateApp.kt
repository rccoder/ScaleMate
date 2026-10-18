package app.scalemate

import androidx.activity.compose.BackHandler
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
import app.scalemate.food.FoodViewModel
import app.scalemate.food.FoodScreen
import app.scalemate.food.Meal
import app.scalemate.food.Nutrients
import app.scalemate.update.UpdateScreen
import app.scalemate.update.UpdateViewModel
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val Light=lightColorScheme(primary=Color(0xFF376A52),primaryContainer=Color(0xFFCEEBDA),onPrimaryContainer=Color(0xFF123B29),secondaryContainer=Color(0xFFD6E8DA),onSecondaryContainer=Color(0xFF123B29),background=Color(0xFFF7F9F5),surface=Color(0xFFF7F9F5),onSurface=Color(0xFF19221D),onSurfaceVariant=Color(0xFF526158),surfaceVariant=Color(0xFFE2E9E1))
private val Dark=darkColorScheme(primary=Color(0xFFA2D2B5),onPrimary=Color(0xFF0A3826),primaryContainer=Color(0xFF214F38),onPrimaryContainer=Color(0xFFCEEBDA),secondaryContainer=Color(0xFF263E30),onSecondaryContainer=Color(0xFFD6E8DA),background=Color(0xFF111914),surface=Color(0xFF111914),surfaceVariant=Color(0xFF26372C),onSurfaceVariant=Color(0xFFB8C7BC),outline=Color(0xFF819087),outlineVariant=Color(0xFF3D4D43))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ScaleMateApp(vm: ScaleViewModel, foodVm: FoodViewModel, updateVm: UpdateViewModel, authorizeNutrition: ()->Unit, bluetooth: (() -> Unit)->Unit, authorize: ()->Unit, healthSettings: ()->Unit, googleApp: ()->Unit, language: (String)->Unit, privacy: ()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("records") }
    val foodState by foodVm.state.collectAsStateWithLifecycle()
    val updateState by updateVm.state.collectAsStateWithLifecycle()
    var weightSyncGuide by remember {mutableStateOf(false)}
    var weightBaseline by remember {mutableStateOf<Set<String>?>(null)}
    var showAllRecords by rememberSaveable {mutableStateOf(false)}
    var foodServiceSelector by rememberSaveable {mutableStateOf("")}
    var foodStartPage by rememberSaveable {mutableStateOf("home")}
    var foodSelection by remember {mutableStateOf<String?>(null)}
    var devicesReturn by rememberSaveable {mutableStateOf("home")}
    var foodRecords by rememberSaveable {mutableStateOf(false)}
    var welcomeDone by rememberSaveable { mutableStateOf(false) }
    var candidate by remember {mutableStateOf<String?>(null)}
    var selected by remember {mutableStateOf<Measurement?>(null)}
    var rename by remember {mutableStateOf<BoundScale?>(null)}
    var name by remember {mutableStateOf("")}
    var choose by remember {mutableStateOf(false)}
    var autoDialog by remember {mutableStateOf(false)}
    var setupChoice by remember {mutableStateOf(false)}
    var languageDialog by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<Measurement?>(null)}
    var replacing by remember {mutableStateOf<BoundScale?>(null)}
    val mainScroll=rememberScrollState()
    val mainScope=rememberCoroutineScope()
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0]
    val model=ScaleCatalog.yunmai3Se
    val modelName=stringResource(model.displayNameResource)
    val number=remember(locale) {NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=2;maximumFractionDigits=2}}
    val foodNumber=remember(locale) {NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=1}}
    val dates=remember(locale) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())}
    val actualPage=if(!state.loaded)"loading" else if(!state.onboarded && !welcomeDone)"welcome" else page
    LaunchedEffect(actualPage,showAllRecords,foodRecords){mainScroll.scrollTo(0)}
    LaunchedEffect(actualPage,foodRecords){vm.foodPage(actualPage.startsWith("food") || actualPage=="records" && foodRecords)}
    LaunchedEffect(state.loaded,state.records) {
        if(state.loaded){val ids=state.records.map {it.id}.toSet();val baseline=weightBaseline;if(baseline!=null && (ids-baseline).isNotEmpty() && !state.healthAllowed)weightSyncGuide=true;weightBaseline=(baseline ?: emptySet())+ids}
    }
    val scale=state.scales.find {it.id==state.activeId}
    val settingUp=scale!=null && !scale.verified
    LaunchedEffect(state.activeId,state.session.phase) { if(state.session.phase!=Phase.STABLE)setupChoice=false }
    val colors=if(state.dynamic) {if(state.dark)dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)} else if(state.dark)Dark else Light
    SideEffect {
        (context as? android.app.Activity)?.window?.let {window->
            androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!state.dark
                isAppearanceLightNavigationBars=!state.dark
            }
        }
    }
    fun navigate(next: String) {if(next=="devices")devicesReturn=if(page=="settings")"settings" else "home";if(page=="devices"&&next!="devices")vm.stop();vm.foodPage(next.startsWith("food"));if(next!=page && state.weighing)vm.cancelWeighing();page=next}
    BackHandler(actualPage in listOf("devices","setup","food-services","sync-settings","update") || actualPage=="records" && showAllRecords) {
        when(actualPage){"devices"->navigate(devicesReturn);"setup"->navigate("sync-settings");"food-services","sync-settings","update"->navigate("settings");else->showAllRecords=false}
    }
    LaunchedEffect(state.weighing) {if(state.weighing){page="home";selected=null;choose=false;autoDialog=false;rename=null;candidate=null;deleting=null;languageDialog=false}}
    MaterialTheme(colorScheme=colors) {
        val c=MaterialTheme.colorScheme
        Scaffold(topBar={ if(actualPage !in listOf("welcome","loading")) TopAppBar(title={Text(stringResource(when(actualPage){"food"->R.string.food_title;"food-services"->R.string.food_services;"devices"->R.string.devices;"records"->R.string.records;"settings"->R.string.settings;"update"->R.string.update_title;"setup"->R.string.prepare_health;"sync-settings"->R.string.settings_sync;"home"->if(settingUp)R.string.finish_scale_setup else R.string.weigh;else->R.string.app_name}),fontWeight=FontWeight.SemiBold)},navigationIcon={if(actualPage in listOf("devices","setup","food-services","sync-settings","update"))IconButton(onClick={navigate(when(actualPage){"devices"->devicesReturn;"setup"->"sync-settings";else->"settings"})}){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.cancel))}}) },
            bottomBar={if(actualPage in listOf("home","food","records","settings"))NavigationBar(containerColor=c.surface){
                listOf(Triple("records",R.string.records,Icons.AutoMirrored.Filled.List),Triple("food",R.string.food_title,Icons.Default.Add),Triple("home",R.string.weigh,Icons.Default.Home),Triple("settings",R.string.settings,Icons.Default.Settings)).forEach{(key,label,icon)->NavigationBarItem(selected=actualPage==key,onClick={if(key=="food"){foodStartPage="home";foodSelection=null};navigate(key)},icon={BadgedBox(badge={if(key=="settings" && updateState.hasUpdate)Badge()}){Icon(icon,stringResource(label))}},label={Text(stringResource(label))})}
            }}
        ) {padding->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(mainScroll).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                when(actualPage) {
                    "loading"->CircularProgressIndicator()
                    "welcome"->{
                        Spacer(Modifier.height(32.dp));Text(stringResource(R.string.app_name),fontSize=32.sp,fontWeight=FontWeight.Bold)
                        Surface(shape=RoundedCornerShape(40.dp),color=c.primaryContainer,modifier=Modifier.fillMaxWidth().height(150.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Favorite,null,Modifier.size(64.dp),tint=c.primary)}}
                        Text(stringResource(R.string.records_welcome_title),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
                        Text(stringResource(R.string.records_welcome_body),color=c.onSurfaceVariant)
                        Action(R.string.records_start){vm.onboardingDone();navigate("records")}
                        Text(stringResource(R.string.records_welcome_privacy),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                    }
                    "setup"->{
                        Text(stringResource(R.string.health_setup_intro),color=c.onSurfaceVariant)
                        SetupStep(1,R.string.health_app_step,R.string.health_app_hint) {
                            OutlinedButton(onClick=googleApp,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.open_or_install_health))}
                        }
                        SetupStep(2,R.string.health_write_step,R.string.health_write_hint) {
                            Pill(if(state.healthAllowed)R.string.health_ready else R.string.health_required)
                            if(!state.healthAllowed)Action(R.string.authorize,authorize)
                            TextButton(onClick=healthSettings){Text(stringResource(R.string.health_settings))}
                        }
                        SetupStep(3,R.string.health_read_step,R.string.health_read_hint) {
                            OutlinedButton(onClick=googleApp,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.open_google))}
                            Text(stringResource(R.string.health_read_note),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.health_setup_note),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                        if(state.onboarded)Action(R.string.done){navigate("sync-settings")}
                        else {
                            Action(R.string.continue_add_scale){vm.onboardingDone();navigate("devices")}
                            TextButton(onClick={vm.onboardingDone();navigate("home")}){Text(stringResource(R.string.continue_local))}
                        }
                    }
                    "devices"->{
                        Text(stringResource(R.string.scan_hint,modelName),color=c.onSurfaceVariant)
                        state.scales.forEach{s->
                            val displayName=scaleDisplayName(s.name)
                            Card(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(scaleDisplayName(s.name),style=MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.device_model,model.internalModel),style=MaterialTheme.typography.bodySmall)
                                Text(stringResource(if(s.verified)R.string.verified else R.string.unverified),color=c.primary)
                                Row {TextButton(onClick={vm.select(s.id);navigate("home")}){Text(stringResource(if(s.verified)R.string.select_scale else R.string.finish_scale_setup))};TextButton(onClick={rename=s;name=displayName}){Text(stringResource(R.string.rename))}}
                                Row {TextButton(onClick={replacing=s;bluetooth{vm.discover()}}){Text(stringResource(R.string.rebind))};TextButton(onClick={vm.unbind(s.id)}){Text(stringResource(R.string.remove))}}
                            }}
                        }
                        if(state.discovering){LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.scanning));TextButton(onClick={vm.cancelWeighing()}){Text(stringResource(R.string.stop))}}
                        else Action(R.string.scan){bluetooth{vm.discover()}}
                        state.candidates.forEach{(address,rssi)->OutlinedCard(onClick={candidate=address},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(modelName);Text(stringResource(R.string.signal_without_address,rssi),style=MaterialTheme.typography.bodySmall)}}}
                    }
                    "home"->{
                        if(scale==null) {Text(stringResource(R.string.no_scale),style=MaterialTheme.typography.headlineMedium);Action(R.string.add_scale){navigate("devices")}}
                        else {
                            OutlinedCard(onClick={choose=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){Text(scaleDisplayName(scale.name),style=MaterialTheme.typography.titleMedium);Text(if(scaleDisplayName(scale.name)==modelName)stringResource(if(scale.verified)R.string.verified else R.string.unverified) else modelName,color=c.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}
                            if(settingUp) {
                                Text(stringResource(R.string.setup_trial_title),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                                Text(stringResource(R.string.setup_trial_hint),color=c.onSurfaceVariant)
                                Pill(R.string.trial_not_saved)
                            }
                            val session=state.session
                            val phase=session.phase
                            val savedWeight=state.confirmedMeasurement()
                            val phaseLabel=if(phase==Phase.CONFIRMED)when(savedWeight?.status) {
                                "synced"->R.string.weight_saved_synced
                                "writing"->R.string.weight_saved_syncing
                                "pending"->R.string.weight_saved_pending
                                "permission"->if(!foodState.healthAvailable)R.string.weight_saved_unavailable else if(state.healthAllowed)R.string.weight_saved_pending else R.string.weight_saved_permission
                                "failed"->R.string.weight_saved_failed
                                "deleting"->R.string.status_deleting
                                "delete_failed"->R.string.status_delete_failed
                                else->if(state.lastSavedRecordId.isNotBlank()&&savedWeight==null)R.string.weight_saved_deleted else R.string.phase_confirmed
                            } else phaseResource(phase)
                            Surface(color=if(phase in listOf(Phase.COUNTDOWN,Phase.STABLE))c.primaryContainer else c.surfaceVariant.copy(alpha=.45f),shape=RoundedCornerShape(32.dp),modifier=Modifier.fillMaxWidth()){
                                Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)){
                                    Text(stringResource(phaseLabel),style=MaterialTheme.typography.labelLarge)
                                    Row(verticalAlignment=Alignment.Bottom){Text((session.candidateKg ?: session.kg)?.let{number.format(it)} ?: "—",fontSize=64.sp,fontWeight=FontWeight.Light);Text(stringResource(R.string.unit_kg),Modifier.padding(start=8.dp,bottom=10.dp),color=c.onSurfaceVariant)}
                                    if(phase==Phase.CONFIRMED && savedWeight!=null) {
                                        when(savedWeight.status) {
                                            "failed"->TextButton(onClick={vm.retry(savedWeight.id)}){Text(stringResource(R.string.weight_retry_sync))}
                                            "permission"->if(!foodState.healthAvailable)TextButton(onClick=healthSettings){Text(stringResource(R.string.health_settings))}
                                                else if(!state.healthAllowed)TextButton(onClick=authorize){Text(stringResource(R.string.authorize))}
                                        }
                                    }
                                    if(phase==Phase.COUNTDOWN){
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                            Box(contentAlignment=Alignment.Center){CircularProgressIndicator(progress={session.seconds/5f},modifier=Modifier.size(52.dp));Text(session.seconds.toString(),fontSize=22.sp)}
                                            Text(stringResource(R.string.countdown,session.seconds))
                                        }
                                    }
                                    if(phase==Phase.STABLE) {
                                        if(settingUp) {
                                            Text(stringResource(R.string.verify_title),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                            Text(stringResource(R.string.verify_hint),style=MaterialTheme.typography.bodyMedium)
                                            Action(R.string.verify_scale){setupChoice=true}
                                            OutlinedButton(onClick={vm.cancelWeighing()}){Text(stringResource(R.string.readings_differ))}
                                        } else Action(R.string.confirm_weight){vm.confirm()}
                                    }
                                    if(state.weighing) {
                                        if(phase in listOf(Phase.WAITING,Phase.MEASURING))LinearProgressIndicator(Modifier.fillMaxWidth())
                                        OutlinedButton(onClick={vm.cancelWeighing()},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(R.string.cancel_weigh))}
                                    } else if(phase==Phase.SAVING)CircularProgressIndicator()
                                    else if(phase==Phase.SAVE_FAILED)Action(R.string.retry_save){vm.retrySave()}
                                    else Action(if(phase in listOf(Phase.CANCELLED,Phase.CONFIRMED))R.string.next_weigh else R.string.start_weigh){bluetooth{vm.start()}}
                                }
                            }
                            if(state.listening && !state.weighing)Text(stringResource(if(settingUp)R.string.setup_waiting else R.string.listening_hint),color=c.primary)
                            if(settingUp)TextButton(onClick={navigate("devices")}){Text(stringResource(R.string.finish_later))}
                            else Text(stringResource(R.string.foreground_hint),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                            state.records.firstOrNull()?.let{m->Text(stringResource(R.string.recent),style=MaterialTheme.typography.titleMedium);RecordCard(m,number.format(m.kg),dates.format(Instant.ofEpochMilli(m.time))){selected=m}}
                        }
                    }
                    "food","food-services"->{key(actualPage){FoodScreen(foodVm,authorizeNutrition,if(actualPage=="food-services")"services" else foodStartPage,foodSelection,resetScroll={mainScope.launch {mainScroll.scrollTo(0)}},initialSelector=if(actualPage=="food-services")foodServiceSelector else "")}}
                    "records"->{
                        if(!showAllRecords) {
                            val today=LocalDate.now()
                            val todayMeals=foodState.meals.filter {!it.deleted && Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate()==today}
                            val todayWeights=state.records.filter {Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate()==today}
                            Text(today.toString(),style=MaterialTheme.typography.labelLarge,color=c.primary)
                            Text(stringResource(R.string.records_today),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
                            Card(colors=CardDefaults.cardColors(containerColor=c.primaryContainer),shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                    val nutrition=Nutrients.sum(todayMeals.map {it.nutrients})
                                    Text(stringResource(R.string.food_today),style=MaterialTheme.typography.labelLarge)
                                    Text((nutrition.kcal?.let {foodNumber.format(it)} ?: "—")+" kcal",fontSize=38.sp)
                                    Text(stringResource(R.string.records_count,todayMeals.size,todayWeights.size))
                                    Text(stringResource(R.string.food_day_incomplete),style=MaterialTheme.typography.bodySmall)
                                }
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Button(onClick={if(foodState.draft==null)foodVm.newMeal();foodStartPage="editor";foodSelection=null;navigate("food")},modifier=Modifier.weight(1f).heightIn(min=52.dp)){Text(stringResource(if(foodState.draft==null)R.string.food_add else R.string.food_resume))}
                                OutlinedButton(onClick={navigate("home")},modifier=Modifier.weight(1f).heightIn(min=52.dp)){Text(stringResource(R.string.weigh))}
                            }
                            val pending=state.records.count {it.status!="synced"}+foodState.meals.count {it.status!="synced"}
                            OutlinedCard(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.records_pending,pending),fontWeight=FontWeight.Medium)
                                    Text(stringResource(R.string.records_sync_hint),style=MaterialTheme.typography.bodySmall)
                                    TextButton(onClick={navigate("sync-settings")}){Text(stringResource(R.string.records_sync_settings))}
                                }
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.records_timeline),style=MaterialTheme.typography.titleLarge);TextButton(onClick={showAllRecords=true}){Text(stringResource(R.string.all_records))}}
                            val entries=(todayWeights.map {TimelineEntry(it.time,weight=it)}+todayMeals.map {TimelineEntry(it.time,meal=it)}).sortedByDescending {it.at}
                            if(entries.isEmpty())Text(stringResource(R.string.records_empty_today),color=c.onSurfaceVariant)
                            entries.forEach {entry->
                                entry.weight?.let {m->RecordCard(m,number.format(m.kg),dates.format(Instant.ofEpochMilli(m.time))){selected=m}}
                                entry.meal?.let {meal->Card(onClick={foodStartPage="home";foodSelection=meal.id;navigate("food")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(dates.format(Instant.ofEpochMilli(meal.time)),style=MaterialTheme.typography.labelMedium);Text(meal.title,style=MaterialTheme.typography.titleLarge);Text((meal.nutrients.kcal?.let {foodNumber.format(it)} ?: "—")+" kcal");Text(stringResource(when(meal.status){"synced"->R.string.status_synced;"writing"->R.string.status_writing;"permission"->R.string.status_permission;"unavailable"->R.string.food_health_unavailable;"failed"->R.string.status_failed;else->R.string.status_pending}),style=MaterialTheme.typography.bodySmall,color=c.primary)}}}
                            }
                        } else {
                            TextButton(onClick={showAllRecords=false}){Text(stringResource(R.string.records_back_today))}
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=!foodRecords,onClick={foodRecords=false;vm.foodPage(false)},label={Text(stringResource(R.string.weigh))});FilterChip(selected=foodRecords,onClick={foodRecords=true;vm.foodPage(true)},label={Text(stringResource(R.string.food_title))})}
                            if(foodRecords) FoodScreen(foodVm,authorizeNutrition,"mealHistory",resetScroll={mainScope.launch {mainScroll.scrollTo(0)}})
                            else {
                                if(state.records.isEmpty())Text(stringResource(R.string.empty_records),color=c.onSurfaceVariant)
                                state.records.forEach{m->RecordCard(m,number.format(m.kg),dates.format(Instant.ofEpochMilli(m.time))){selected=m}}
                            }
                        }
                    }
                    "settings"->{
                        fun services(selector:String="") {foodServiceSelector=selector;navigate("food-services")}
                        fun modelName(id:String)=foodState.services.find {it.id==id}?.let {it.name+" · "+it.model}
                        SettingsGroup(stringResource(R.string.food_services)) {
                            SettingsRow(stringResource(R.string.settings_ai_manage),if(foodState.services.isEmpty())stringResource(R.string.food_ai_unconfigured) else stringResource(R.string.settings_service_count,foodState.services.size)){services()}
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_image_default),modelName(foodState.defaultVision) ?: stringResource(R.string.food_not_set)){services("vision-default")}
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_text_default),modelName(foodState.defaultText) ?: stringResource(R.string.food_not_set)){services("text-default")}
                        }
                        SettingsGroup(stringResource(R.string.settings_sync)) {
                            SettingsRow("Health Connect",if(!foodState.healthAvailable)stringResource(R.string.food_health_unavailable) else stringResource(R.string.settings_sync_summary,stringResource(if(state.healthAllowed)R.string.settings_allowed else R.string.settings_not_allowed),stringResource(if(foodState.permitted)R.string.settings_allowed else R.string.settings_not_allowed))){navigate("sync-settings")}
                        }
                        SettingsGroup(stringResource(R.string.weigh)) {
                            SettingsRow(stringResource(R.string.devices),scale?.let {scaleDisplayName(it.name)} ?: stringResource(R.string.add_scale)){navigate("devices")}
                            SettingsDivider()
                            SettingsToggle(stringResource(R.string.settings_auto_save),stringResource(if(scale==null)R.string.auto_needs_scale else if(!scale.verified)R.string.auto_needs_verification else R.string.settings_auto_hint),state.automatic&&scale?.verified==true,scale?.verified==true){if(it)autoDialog=true else vm.automatic(false)}
                        }
                        SettingsGroup(stringResource(R.string.settings_appearance)) {
                            SettingsToggle(stringResource(R.string.dark),stringResource(R.string.settings_dark_hint),state.dark,onChange=vm::dark)
                            SettingsDivider()
                            SettingsToggle(stringResource(R.string.dynamic),stringResource(R.string.settings_dynamic_hint),state.dynamic,onChange=vm::dynamic)
                            SettingsDivider()
                            val appLocales=context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
                            SettingsRow(stringResource(R.string.language),stringResource(if(appLocales.isEmpty)R.string.language_system else if(appLocales[0].language=="zh")R.string.language_zh else R.string.language_en)){languageDialog=true}
                        }
                        SettingsGroup(stringResource(R.string.settings_app)) {
                            SettingsRow(stringResource(R.string.privacy),stringResource(R.string.settings_local_privacy),privacy)
                            SettingsDivider()
                            val version=remember {context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()}
                            val versionSummary=when {
                                context.packageName.endsWith(".fooddev")->stringResource(R.string.settings_test_version,version)
                                updateState.hasUpdate->stringResource(R.string.settings_update_available,version)
                                else->version
                            }
                            SettingsRow(
                                stringResource(R.string.settings_version),
                                versionSummary,
                                onClick = if (context.packageName.endsWith(".fooddev")) null else {
                                    { navigate("update") }
                                },
                                showBadge = updateState.hasUpdate && !context.packageName.endsWith(".fooddev")
                            )
                        }
                    }
                    "sync-settings"->{
                        Text(stringResource(R.string.settings_sync_intro),style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
                        SettingsGroup(stringResource(R.string.settings_permissions)) {
                            SettingsRow(stringResource(R.string.settings_weight),stringResource(if(!foodState.healthAvailable)R.string.food_health_unavailable else if(state.healthAllowed)R.string.settings_permission_enabled else R.string.settings_permission_disabled)){if(!foodState.healthAvailable||state.healthAllowed)healthSettings() else authorize()}
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_nutrition),stringResource(if(!foodState.healthAvailable)R.string.food_health_unavailable else if(foodState.permitted)R.string.settings_permission_enabled else R.string.settings_permission_disabled)){if(!foodState.healthAvailable||foodState.permitted)healthSettings() else authorizeNutrition()}
                        }
                        SettingsGroup(stringResource(R.string.settings_sync_records)) {
                            SettingsRow(stringResource(R.string.settings_view_records),stringResource(R.string.records_pending,state.records.count {it.status!="synced"}+foodState.meals.count {it.status!="synced"})){showAllRecords=true;foodRecords=foodState.meals.any {it.status!="synced"};navigate("records")}
                        }
                        SettingsGroup(stringResource(R.string.settings_sync_help)) {
                            SettingsRow(stringResource(R.string.health_settings),"",healthSettings)
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_health_steps),stringResource(R.string.settings_health_steps_hint)){navigate("setup")}
                        }
                        Text(stringResource(R.string.records_sync_hint),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                    }
                    "update"->{
                        key(actualPage){UpdateScreen(updateVm)}
                    }
                }
                state.message?.let{Text(stringResource(it),color=c.error,style=MaterialTheme.typography.bodyMedium)}
                Spacer(Modifier.height(24.dp))
            }
        }
        if(weightSyncGuide)AlertDialog(onDismissRequest={weightSyncGuide=false},title={Text(stringResource(R.string.records_weight_saved))},text={Text(stringResource(R.string.records_weight_sync_guide))},confirmButton={TextButton(onClick={weightSyncGuide=false;authorize()}){Text(stringResource(R.string.authorize))}},dismissButton={TextButton(onClick={weightSyncGuide=false}){Text(stringResource(R.string.food_continue_local))}})
        if(candidate!=null)AlertDialog(onDismissRequest={candidate=null},title={Text(stringResource(R.string.confirm_scale))},text={Text(stringResource(R.string.confirm_model,modelName,model.internalModel))},confirmButton={TextButton(onClick={vm.bind(candidate!!,replacing?.name ?: model.id,replacing?.id);candidate=null;replacing=null;navigate("home")}){Text(stringResource(R.string.bind))}},dismissButton={TextButton(onClick={candidate=null}){Text(stringResource(R.string.cancel))}})
        if(rename!=null)AlertDialog(onDismissRequest={rename=null},title={Text(stringResource(R.string.rename))},text={OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text(stringResource(R.string.name))},singleLine=true)},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={vm.rename(rename!!.id,name);rename=null}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick={rename=null}){Text(stringResource(R.string.cancel))}})
        if(choose)AlertDialog(onDismissRequest={choose=false},title={Text(stringResource(R.string.select_scale))},text={Column{state.scales.forEach{s->TextButton(onClick={vm.select(s.id);choose=false}){Text(scaleDisplayName(s.name))}};TextButton(onClick={choose=false;navigate("devices")}){Text(stringResource(R.string.add_scale))}}},confirmButton={TextButton(onClick={choose=false}){Text(stringResource(R.string.done))}})
        if(setupChoice && settingUp && state.session.phase==Phase.STABLE)AlertDialog(
            onDismissRequest={setupChoice=false},
            title={Text(stringResource(R.string.choose_save_mode))},
            text={Text(stringResource(R.string.choose_save_mode_hint))},
            confirmButton={TextButton(onClick={setupChoice=false;vm.completeScaleSetup(true)}){Text(stringResource(R.string.setup_use_automatic))}},
            dismissButton={TextButton(onClick={setupChoice=false;vm.completeScaleSetup(false)}){Text(stringResource(R.string.setup_use_manual))}}
        )
        if(autoDialog)AlertDialog(onDismissRequest={autoDialog=false},title={Text(stringResource(R.string.auto_confirm))},text={Text(stringResource(R.string.auto_explain))},confirmButton={TextButton(onClick={vm.automatic(true);autoDialog=false}){Text(stringResource(R.string.enable))}},dismissButton={TextButton(onClick={autoDialog=false}){Text(stringResource(R.string.cancel))}})
        if(languageDialog)AlertDialog(onDismissRequest={languageDialog=false},title={Text(stringResource(R.string.language))},text={Column{listOf("" to R.string.language_system,"zh" to R.string.language_zh,"en" to R.string.language_en).forEach{(tag,label)->TextButton(onClick={languageDialog=false;language(tag)}){Text(stringResource(label))}}}},confirmButton={TextButton(onClick={languageDialog=false}){Text(stringResource(R.string.cancel))}})
        selected?.let{snapshot->val m=state.records.find{it.id==snapshot.id} ?: snapshot
            AlertDialog(onDismissRequest={selected=null},title={Text(stringResource(R.string.detail))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(number.format(m.kg)+" "+stringResource(R.string.unit_kg),style=MaterialTheme.typography.headlineLarge);Text(dates.format(Instant.ofEpochMilli(m.time)));Text(stringResource(R.string.source,scaleDisplayName(m.source)));Text(stringResource(statusResource(m.status)));if(m.status in listOf("pending","failed","permission"))TextButton(onClick={if(m.status=="permission")authorize() else vm.retry(m.id)}){Text(stringResource(if(m.status=="permission")R.string.authorize else R.string.retry))};TextButton(onClick={selected=null;deleting=m}){Text(stringResource(R.string.delete_title))}}},confirmButton={TextButton(onClick={selected=null}){Text(stringResource(R.string.done))}})
        }
        deleting?.let{m->AlertDialog(onDismissRequest={deleting=null},title={Text(stringResource(R.string.delete_title))},text={Column{Text(stringResource(R.string.delete_note));TextButton(onClick={vm.delete(m.id,false);deleting=null}){Text(stringResource(R.string.delete_local))};TextButton(onClick={vm.delete(m.id,true);deleting=null}){Text(stringResource(R.string.delete_both))}}},confirmButton={TextButton(onClick={deleting=null}){Text(stringResource(R.string.cancel))}})}
    }
}

@Composable private fun SetupStep(number: Int, title: Int, hint: Int, content: @Composable ColumnScope.()->Unit) {
    OutlinedCard(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(36.dp),contentAlignment=Alignment.Center){Text(number.toString(),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimaryContainer)}
                }
                Text(stringResource(title),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            }
            Text(stringResource(hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable private fun Action(label: Int, action: ()->Unit) {Button(onClick=action,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(stringResource(label))}}
@Composable private fun Pill(label: Int) {Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Text(stringResource(label),Modifier.padding(horizontal=14.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)}}
@Composable private fun RecordCard(m: Measurement, kg: String, date: String, action: ()->Unit) {Card(onClick=action,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(date,style=MaterialTheme.typography.labelMedium);Text(kg+" "+stringResource(R.string.unit_kg),fontSize=28.sp);Text(stringResource(statusResource(m.status)),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall);Text(scaleDisplayName(m.source),style=MaterialTheme.typography.bodySmall)}}}
private fun phaseResource(p: Phase)=when(p){Phase.IDLE->R.string.phase_idle;Phase.WAITING->R.string.phase_waiting;Phase.MEASURING->R.string.phase_measuring;Phase.STABLE->R.string.phase_stable;Phase.COUNTDOWN->R.string.phase_countdown;Phase.SAVING->R.string.phase_saving;Phase.SAVE_FAILED->R.string.storage_error;Phase.CONFIRMED->R.string.phase_confirmed;Phase.CANCELLED->R.string.phase_cancelled;Phase.EXPIRED->R.string.phase_expired}
private fun statusResource(s: String)=when(s){"synced"->R.string.status_synced;"writing"->R.string.status_writing;"permission"->R.string.status_permission;"failed"->R.string.status_failed;"deleting"->R.string.status_deleting;"delete_failed"->R.string.status_delete_failed;else->R.string.status_pending}

@Composable private fun scaleDisplayName(storedName: String): String {
    val model = ScaleCatalog.yunmai3Se
    return model.displayName(storedName, stringResource(model.displayNameResource))
}

private data class TimelineEntry(val at:Long,val weight:Measurement?=null,val meal:Meal?=null)
