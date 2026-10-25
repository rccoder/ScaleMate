package app.scalemate.food

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scalemate.R
import app.scalemate.SettingsGroup
import app.scalemate.SettingsDivider
import app.scalemate.SettingsRow
import app.scalemate.settingsSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable fun FoodScreen(
    vm:FoodViewModel,
    authorize:()->Unit,
    initialPage:String="home",
    initialMealId:String?=null,
    resetScroll:()->Unit={},
    initialSelector:String="",
    editorPanel:Boolean=false,
    onStartMeal:()->Unit={},
    onOpenEditor:(String)->Unit={},
    onCloseEditor:()->Unit={},
    onMealSaved:()->Unit={}
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var page by rememberSaveable(initialPage){mutableStateOf(initialPage)}
    val panelScroll=rememberScrollState()
    val panelScope=rememberCoroutineScope()
    fun resetContentScroll(){if(editorPanel)panelScope.launch {panelScroll.scrollTo(0)}else resetScroll()}
    var date by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var pendingCamera by rememberSaveable {mutableStateOf<String?>(null)}
    var libraryEditing by remember {mutableStateOf<FoodLibraryEntry?>(null)}
    var servicesReturn by rememberSaveable {mutableStateOf("editor")}
    var addChooser by remember {mutableStateOf(false)}
    var imageChooser by remember {mutableStateOf(false)}
    var reanalyze by remember {mutableStateOf(false)}
    var abandon by remember {mutableStateOf(false)}
    var mergeBatchId by rememberSaveable {mutableStateOf<String?>(null)}
    var manualReturn by rememberSaveable {mutableStateOf("picker")}
    var selectedMeal by remember {mutableStateOf<String?>(initialMealId)}
    var selector by remember(initialSelector){mutableStateOf(initialSelector)}
    var serviceEditor by remember {mutableStateOf<AiService?>(null)}
    var confirmServiceDelete by remember {mutableStateOf<AiService?>(null)}
    var consent by remember {mutableStateOf<AiService?>(null)}
    var search by rememberSaveable {mutableStateOf("")}
    var pickerTab by rememberSaveable {mutableStateOf("all")}
    var libraryPage by rememberSaveable {mutableIntStateOf(0)}
    var noVisualGuide by remember {mutableStateOf(false)}
    var syncGuide by remember {mutableStateOf(false)}
    var discardDialog by remember {mutableStateOf(false)}
    val draft=s.draft
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val number=remember(locale){NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=1}}
    val ratioNumber=remember(locale){NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=6}}
    fun fmt(n:Double?)=n?.let {number.format(it)} ?: "—"
    val dateFormatter=remember(locale){DateTimeFormatter.ofPattern("MM-dd HH:mm",locale).withZone(ZoneId.systemDefault())}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(FoodImages.MAX_PHOTOS)){uris->if(uris.isNotEmpty()){vm.importPhotos(uris);page="photo"}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->if(ok)pendingCamera?.let {path->vm.importPhoto(FileProvider.getUriForFile(context,"${context.packageName}.foodphotos",File(path)));page="photo"} else if(s.draft?.batch?.photos.isNullOrEmpty())vm.abandonBatch()}
    fun capture(){vm.beginPhoto();try {
        val dir=File(context.cacheDir,"food_capture").apply {mkdirs()};val f=File.createTempFile("meal-",".jpg",dir);pendingCamera=f.absolutePath
        camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.foodphotos",f))
    }catch(_:Exception){vm.showError(R.string.food_photo_error)}}
    fun openHealth(){try {context.startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))}catch(_:Exception){vm.showError(R.string.open_error)}}
    fun analyze(){val service=s.services.find {it.id==s.selectedService && it.images};if(service==null)noVisualGuide=true
        else if(service.consentVersion!=service.version)consent=service else vm.analyze()}
    BackHandler(!editorPanel&&serviceEditor!=null){vm.cancelAi();vm.clearMessage();serviceEditor=null}
    LaunchedEffect(serviceEditor?.id){resetContentScroll()}
    fun closeEditor() {
        if(s.saving)return
        if(!s.loaded){onCloseEditor();return}
        vm.cancelAi();vm.clearMessage()
        if(vm.state.value.draft?.isEmpty()==true)vm.discard()
        onCloseEditor()
    }
    fun goBack() {
        if(s.saving)return
        if(!s.loaded){onCloseEditor();return}
        if(serviceEditor!=null){vm.cancelAi();vm.clearMessage();serviceEditor=null;return}
        vm.cancelAi();vm.clearMessage()
        val action=foodBackAction(page,draft?.batch,manualReturn,servicesReturn)
        when {
            action.confirmDiscard->abandon=true
            action.page==null->closeEditor()
            else->{if(action.discardBatch)vm.abandonBatch();page=action.page}
        }
    }
    BackHandler(!editorPanel&&serviceEditor==null&&page!=initialPage){goBack()}
    DisposableEffect(Unit){onDispose{if(editorPanel&&vm.state.value.loaded)vm.cancelAi()}}
    LaunchedEffect(page){resetContentScroll()}
    LaunchedEffect(draft?.batch?.status){if(page=="photo"&&draft?.batch?.status=="review")resetContentScroll()}
    LaunchedEffect(s.loaded,draft==null,s.saving){if(editorPanel&&s.loaded&&draft==null&&!s.saving)onCloseEditor()}
    val screenContent:@Composable ()->Unit = {
        if(serviceEditor==null)Column(
            verticalArrangement=Arrangement.spacedBy(16.dp)
        ) {
            if(!editorPanel&&page!=initialPage)IconButton(onClick={goBack()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.back_food))}
            s.error?.let {resource->FoodCard {Text(stringResource(resource),color=MaterialTheme.colorScheme.error);TextButton(onClick=vm::clearMessage){Text(stringResource(R.string.done))}}}
            s.message?.let {if(it==R.string.food_analysis_ready)FoodAnalysisReady() else Text(stringResource(it),color=MaterialTheme.colorScheme.primary)}
            if(s.busy)FoodAnalysisStatus(s.analysisStage,s.analysisAttempt,s.analysisStartedAt,vm::cancelAi)
            when(page) {
                "home" -> {
                    val meals=s.meals.filter {!it.deleted && Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate().toString()==date}
                    val totals=Nutrients.sum(meals.map {it.nutrients})
                    val hasDraft=draft?.isEmpty()==false
                    FoodCard(color=MaterialTheme.colorScheme.primaryContainer,padding=PaddingValues(horizontal=18.dp,vertical=14.dp)){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(stringResource(if(date==LocalDate.now().toString())R.string.records_today_intake else R.string.food_day_intake),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                            TextButton(onClick={val day=LocalDate.parse(date);DatePickerDialog(context,{_,y,m,d->date=LocalDate.of(y,m+1,d).toString()},day.year,day.monthValue-1,day.dayOfMonth).show()}){Text(date)}
                        }
                        Text("${fmt(totals.kcal)} kcal",fontSize=42.sp,fontWeight=FontWeight.Light)
                        NutritionRow(totals)
                        Text(stringResource(R.string.food_day_incomplete),style=MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled=s.loaded,onClick=onStartMeal,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Icon(Icons.Default.Add,null);Text(stringResource(R.string.food_add))}
                    if(hasDraft) {
                        TextButton(onClick={onOpenEditor(foodDraftPage(draft))}){Text(stringResource(R.string.food_resume))}
                        TextButton(onClick={discardDialog=true}){Text(stringResource(R.string.food_discard))}
                    }
                    if(meals.isEmpty())Text(stringResource(R.string.food_empty),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    meals.forEach {meal->MealRow(meal,dateFormatter.format(Instant.ofEpochMilli(meal.time))){selectedMeal=meal.id}}
                    s.meals.filter {it.deleted}.forEach {meal->FoodCard {Text(meal.title);Text(stringResource(R.string.status_delete_failed),color=MaterialTheme.colorScheme.error);TextButton(onClick={if(s.permitted)vm.retry(meal.id) else authorize()}){Text(stringResource(if(s.permitted)R.string.retry else R.string.food_nutrition_authorize))}}}
                    if(!s.permitted||!s.healthAvailable)NutritionConnectionCard(s.healthAvailable,s.permitted,authorize){openHealth()}
                    Text(stringResource(R.string.food_nutrition_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "mealHistory" -> {
                    Text(stringResource(R.string.all_records),style=MaterialTheme.typography.headlineSmall)
                    if(s.meals.none {!it.deleted})Text(stringResource(R.string.food_empty))
                    s.meals.filter {!it.deleted}.forEach {meal->MealRow(meal,dateFormatter.format(Instant.ofEpochMilli(meal.time))){selectedMeal=meal.id}}
                }
                "editor" -> if(draft!=null) {
                    if(!editorPanel)Text(stringResource(R.string.food_add),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                    var editingTime by rememberSaveable(draft.meal.id){mutableStateOf(draft.meal.items.isEmpty()&&draft.batch==null)}
                    if(editingTime)MealTime(draft.meal){m->vm.updateDraft {it.copy(meal=m)}}
                    else TextButton(enabled=!s.busy&&!s.saving,onClick={editingTime=true}){Text(mealTimeLabel(draft.meal))}
                    if(draft.batch!=null) {
                        OutlinedButton(onClick={page=if(draft.batch.status=="review"||draft.batch.kind in listOf("manual","library","edit"))"review" else "photo"},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.food_pending_addition))}
                        TextButton(onClick=vm::abandonBatch){Text(stringResource(R.string.food_discard_addition))}
                    } else {
                        Button(enabled=!s.busy&&!s.saving,onClick={addChooser=true},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.food_add_food))}
                    }
                    if(draft.meal.advice.hasContent||draft.meal.usage.hasContent||draft.meal.analysisMs!=null)MealAdviceCard(draft.meal.advice,draft.meal.nutrients,draft.meal.usage,draft.meal.analysisMs)
                    draft.meal.items.forEach {food->key(food.id){
                        FoodItemCard(food=food,favorite=s.library.any {it.favorite&&FoodLibraryRules.nameKey(it.item.name)==FoodLibraryRules.nameKey(food.name)},
                            enabled=!s.busy&&!s.saving,editEnabled=draft.batch==null&&!s.busy&&!s.saving,
                            onFavorite={vm.favorite(food,it)},onEdit={vm.reviewItem(food);page="review"},onRemove={vm.removeItem(food.id)})
                    }}
                    if(draft.meal.items.isNotEmpty())FoodCard(color=MaterialTheme.colorScheme.secondaryContainer){Text(stringResource(R.string.food_meal_total),fontWeight=FontWeight.SemiBold);Text("${fmt(draft.meal.nutrients.kcal)} kcal",style=MaterialTheme.typography.headlineMedium);NutritionRow(draft.meal.nutrients);if(listOf(draft.meal.nutrients.kcal,draft.meal.nutrients.protein,draft.meal.nutrients.carbs,draft.meal.nutrients.fat).any {it==null})Text(stringResource(R.string.food_nutrition_partial),style=MaterialTheme.typography.bodySmall)}
                    Text(stringResource(R.string.food_draft_note),style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!s.saving,onClick={discardDialog=true}){Text(stringResource(R.string.food_discard))}
                } else TextButton(onClick={vm.newMeal()}){Text(stringResource(R.string.food_add))}
                "photo", "review" -> draft?.batch?.let {batch->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        if(!editorPanel)Text(stringResource(if(batch.kind=="manual")R.string.food_manual_action else if(batch.status=="review")R.string.food_review_results else R.string.food_add_photos),Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                        if(batch.kind=="photo") {
                            val service=s.services.find {it.id==s.selectedService}
                            Text(stringResource(R.string.food_powered_by,service?.name ?: stringResource(R.string.food_service_none)),modifier=Modifier.weight(.8f).clickable(enabled=!s.busy){selector="vision-current"}.padding(vertical=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(batch.kind=="manual") {
                        FilledTonalButton(onClick={vm.abandonBatch();page="picker"},modifier=Modifier.fillMaxWidth().heightIn(min=72.dp),shape=FoodCardShape){
                            Icon(Icons.Default.List,null,Modifier.size(26.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(stringResource(R.string.food_choose_library),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.food_library_shortcut_hint),style=MaterialTheme.typography.bodySmall)};Text("›",style=MaterialTheme.typography.headlineSmall)
                        }
                        Text(stringResource(R.string.food_or_enter_manually),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(batch.photos.isNotEmpty()||batch.kind=="photo"&&batch.status!="review")FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {batch.photos.forEachIndexed {index,path->
                        Box(Modifier.width(96.dp)){
                            PhotoPreview(path,compact=true)
                            if(batch.kind=="photo"&&batch.status!="review")IconButton(enabled=!s.busy,onClick={vm.removePhoto(path)},modifier=Modifier.align(Alignment.TopEnd).size(40.dp)){
                                Surface(shape=RoundedCornerShape(50),color=androidx.compose.ui.graphics.Color.Black.copy(alpha=.55f),modifier=Modifier.size(24.dp)){
                                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Default.Close,contentDescription=stringResource(R.string.food_remove_photo,index+1),modifier=Modifier.size(14.dp),tint=androidx.compose.ui.graphics.Color.White)}
                                }
                            }
                        }
                    }
                        if(batch.kind=="photo"&&batch.status!="review"&&batch.photos.size<FoodImages.MAX_PHOTOS)Surface(onClick={imageChooser=true},enabled=!s.busy,shape=FoodImageShape,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.width(96.dp).height(88.dp)){
                            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Default.Add,contentDescription=stringResource(R.string.food_add_images),modifier=Modifier.size(36.dp),tint=MaterialTheme.colorScheme.primary)}
                        }
                    }
                    if(batch.kind=="photo") {
                        if(batch.status!="review") {
                            Text(stringResource(R.string.food_multi_photo_hint),style=MaterialTheme.typography.bodySmall)
                            var sharingDialog by remember(batch.id){mutableStateOf(false)}
                            if(sharingDialog)SharingCountDialog(batch.shareCount,onDismiss={sharingDialog=false}){count->vm.updateBatch {it.copy(shareCount=count)};sharingDialog=false}
                            Text(stringResource(R.string.food_sharing_mode),style=MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                listOf(1 to R.string.food_tag_solo,2 to R.string.food_tag_two,3 to R.string.food_tag_three).forEach {(count,label)->FilterChip(enabled=!s.busy,selected=batch.shareCount==count,onClick={vm.updateBatch {it.copy(shareCount=count)}},label={Text(stringResource(label))})}
                                FilterChip(enabled=!s.busy,selected=batch.shareCount>3,onClick={sharingDialog=true},label={Text(if(batch.shareCount>3)stringResource(R.string.food_context_shared,batch.shareCount) else stringResource(R.string.food_sharing_other))})
                            }
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){(listOf(R.string.food_tag_oil,R.string.food_tag_sauce,R.string.food_tag_soup,R.string.food_tag_no_dip)).forEach {label->val tag=stringResource(label);FilterChip(enabled=!s.busy,selected=tag in batch.tags,onClick={vm.updateBatch {it.copy(tags=if(tag in it.tags)it.tags-tag else it.tags+tag)}},label={Text(tag)})}}
                            OutlinedTextField(enabled=!s.busy,value=batch.description,onValueChange={v->vm.updateBatch {it.copy(description=v.take(2000))}},label={Text(stringResource(R.string.food_description))},placeholder={Text(stringResource(R.string.food_description_hint))},minLines=3,modifier=Modifier.fillMaxWidth())
                            Text(stringResource(R.string.food_personal_rule),style=MaterialTheme.typography.bodySmall)
                            if(batch.status=="interrupted")Text(stringResource(R.string.food_analysis_interrupted),color=MaterialTheme.colorScheme.error)
                            Button(enabled=!s.busy&&batch.photos.isNotEmpty(),onClick={analyze()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.food_analyze))}
                        }
                    }
                    if(batch.status=="review") {
                        if(batch.kind=="photo"||batch.advice.hasContent||batch.usage.hasContent)MealAdviceCard(batch.advice,Nutrients.sum(batch.results.map {it.nutrients}),batch.usage,batch.analysisMs)
                        Text(stringResource(R.string.food_personal_results),color=MaterialTheme.colorScheme.primary)
                        if(batch.kind=="photo"&&batch.results.size>=2)OutlinedButton(enabled=!s.busy&&!s.saving,onClick={mergeBatchId=batch.id},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.food_merge_dish))}
                        if(batch.kind=="photo"&&batch.results.any {it.estimateContext?.resultMode!="personal"})Text(stringResource(R.string.food_portion_old_result),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        batch.results.forEach {food->key(food.id){
                            var expanded by rememberSaveable(batch.id,food.id){mutableStateOf(batch.kind in listOf("manual","edit"))}
                            FoodItemCard(food=food,favorite=s.library.any {it.favorite&&FoodLibraryRules.nameKey(it.item.name)==FoodLibraryRules.nameKey(food.name)},
                                enabled=!s.busy,expanded=expanded,showHeader=batch.kind!="manual"||food.name.isNotBlank(),
                                onFavorite={vm.favorite(food,it)},onEdit={expanded=!expanded},onRemove={vm.removeResult(food.id);if(batch.results.size==1)page="editor"},
                                editor={FoodFields(food,enabled=!s.busy,onChange=vm::updateResult)})
                        }}
                        if(batch.kind=="photo")TextButton(onClick={reanalyze=true}){Text(stringResource(R.string.food_change_context))}
                    }
                }
                "picker" -> {
                    if(!editorPanel)Text(stringResource(R.string.food_library),style=MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(search,{search=it;libraryPage=0},label={Text(stringResource(R.string.food_search))},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=pickerTab=="all",onClick={pickerTab="all";libraryPage=0},label={Text(stringResource(R.string.food_library_all))});FilterChip(selected=pickerTab=="favorites",onClick={pickerTab="favorites";libraryPage=0},label={Text(stringResource(R.string.food_favorites))})}
                    OutlinedButton(onClick={manualReturn="picker";vm.beginManual();page="review"},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.food_add_manual))}
                    Text(stringResource(R.string.food_library_rules),style=MaterialTheme.typography.bodySmall)
                    val entries=s.library.filter {(pickerTab!="favorites"||it.favorite)&&it.item.name.contains(search,true)}
                    LaunchedEffect(entries.size){libraryPage=libraryPage.coerceAtMost(((entries.size-1)/20).coerceAtLeast(0))}
                    if(entries.isEmpty())Text(stringResource(R.string.food_library_empty))
                    entries.drop(libraryPage*20).take(20).forEach {entry->key(entry.id){FoodCard {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){entry.item.photos.firstOrNull()?.let {path->Box(Modifier.width(72.dp)){PhotoPreview(path,compact=true)}};Column(Modifier.weight(1f)){Text(entry.item.name,style=MaterialTheme.typography.titleLarge);Text("${fmt(entry.item.nutrients.kcal)} kcal · ${entry.item.portion}");Text(stringResource(R.string.food_usage_count,entry.useCount),style=MaterialTheme.typography.labelSmall)}}
                        Row {TextButton(onClick={vm.beginLibrary(entry);page="review"}){Text(stringResource(R.string.food_add_to_meal))};TextButton(onClick={vm.favorite(entry.item,!entry.favorite)}){Text(stringResource(if(entry.favorite)R.string.food_unfavorite else R.string.food_to_favorites))};TextButton(onClick={libraryEditing=entry}){Text(stringResource(R.string.food_edit))}}
                    }}}
                    if(entries.size>20)Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                        TextButton(enabled=libraryPage>0,onClick={libraryPage--;resetScroll()}){Text(stringResource(R.string.food_previous_page))}
                        Text("${libraryPage+1} / ${(entries.size+19)/20}",style=MaterialTheme.typography.labelSmall)
                        TextButton(enabled=(libraryPage+1)*20<entries.size,onClick={libraryPage++;resetScroll()}){Text(stringResource(R.string.food_next_page))}
                    }
                }
                "services" -> {
                    Text(stringResource(R.string.food_service_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(s.services.isNotEmpty())SettingsGroup(stringResource(R.string.settings_ai_manage)) {
                        s.services.forEachIndexed {index,service->
                            if(index>0)SettingsDivider()
                            val badges=listOfNotNull(if(service.id==s.defaultVision)stringResource(R.string.ai_default_image_badge) else null,if(service.id==s.defaultText)stringResource(R.string.ai_default_text_badge) else null)
                            val detail=listOf(stringResource(if(service.format=="anthropic")R.string.ai_anthropic else R.string.ai_openai),service.model,stringResource(if(service.images)R.string.food_service_vision else R.string.food_service_text_only))+badges
                            val copyName=stringResource(R.string.ai_service_copy_name,service.name.take(64))
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)){SettingsRow(service.name,detail.joinToString(" · ")){vm.clearMessage();serviceEditor=service}}
                                TextButton(enabled=!s.busy,onClick={vm.copyService(service,copyName){serviceEditor=it}},modifier=Modifier.padding(end=8.dp)){Text(stringResource(R.string.ai_service_copy))}
                            }
                        }
                    }
                    else Text(stringResource(R.string.food_ai_unconfigured))
                    OutlinedButton(onClick={vm.clearMessage();serviceEditor=AiService()},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Text(stringResource(R.string.food_service_add))}
                    SettingsGroup(stringResource(R.string.food_services)) {
                        SettingsRow(stringResource(R.string.settings_image_default),s.services.find {it.id==s.defaultVision}?.let {it.name+" · "+it.model} ?: stringResource(R.string.food_not_set)){selector="vision-default"}
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_text_default),s.services.find {it.id==s.defaultText}?.let {it.name+" · "+it.model} ?: stringResource(R.string.food_not_set)){selector="text-default"}
                    }
                    Text(stringResource(R.string.food_test_note),style=MaterialTheme.typography.bodySmall)

                }
            }
        }
        else serviceEditor?.let {service->ServiceEditPage(service,s.services.any {it.id==service.id},s.error,s.message,s.busy,showNavigation=!editorPanel,onChanged=vm::clearMessage,onDismiss={vm.cancelAi();vm.clearMessage();serviceEditor=null},onTest={input,key->vm.testService(input,key)},onModels=vm::fetchModels,onCancelModels={vm.cancelAi();vm.clearMessage()},onDelete={serviceEditor=null;confirmServiceDelete=service}){input,key->vm.saveService(input,key){serviceEditor=null}}}
    }
    if(editorPanel) {
        FoodPanel(onDismiss={goBack()},onOutside={closeEditor()}) {
            Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                if(page!="editor"||serviceEditor!=null)IconButton(enabled=!s.saving,onClick={goBack()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.back_food))}
                val title=when {
                    serviceEditor!=null->if(s.services.any {it.id==serviceEditor?.id})R.string.food_edit_service else R.string.food_service_add
                    page=="picker"->R.string.food_library
                    page=="services"->R.string.food_services
                    page=="review"&&draft?.batch?.kind=="manual"->R.string.food_manual_action
                    page in listOf("photo","review")&&draft?.batch?.status=="review"->R.string.food_review_results
                    page=="photo"->R.string.food_add_photos
                    else->R.string.food_add
                }
                Text(stringResource(title),Modifier.weight(1f).padding(start=if(page=="editor")12.dp else 0.dp),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                IconButton(enabled=!s.saving,onClick={closeEditor()}){Icon(Icons.Default.Close,stringResource(R.string.food_close_keep_draft))}
            }
            Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                val hasSave=s.loaded&&serviceEditor==null&&foodSaveAction(page,draft,s.busy,s.saving)!=null
                Column(Modifier.fillMaxSize().verticalScroll(panelScroll).padding(horizontal=24.dp).padding(bottom=if(hasSave)160.dp else 24.dp)) {screenContent()}
                if(serviceEditor==null)FoodSaveButton(vm,s,page,panelScroll,onMealSaved,Modifier.align(Alignment.BottomEnd).padding(horizontal=24.dp,vertical=18.dp))
                if(s.saving)CircularProgressIndicator(Modifier.align(Alignment.BottomEnd).padding(24.dp))
            }
        }
    } else screenContent()
    if(addChooser||imageChooser)ModalBottomSheet(onDismissRequest={addChooser=false;imageChooser=false},containerColor=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=0.dp){
        val rowColors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)
        Text(stringResource(if(imageChooser)R.string.food_add_images else R.string.food_add_food),Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.titleLarge)
        ListItem(colors=rowColors,headlineContent={Text(stringResource(R.string.food_photo))},supportingContent={Text(stringResource(R.string.food_camera_action_hint))},modifier=Modifier.clickable {addChooser=false;imageChooser=false;if(s.services.none {it.images})noVisualGuide=true else capture()})
        ListItem(colors=rowColors,headlineContent={Text(stringResource(R.string.food_gallery))},supportingContent={Text(stringResource(R.string.food_album_action_hint))},modifier=Modifier.clickable {addChooser=false;imageChooser=false;if(s.services.none {it.images})noVisualGuide=true else photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))})
        if(!imageChooser)ListItem(colors=rowColors,headlineContent={Text(stringResource(R.string.food_manual_action))},supportingContent={Text(stringResource(R.string.food_manual_action_hint))},modifier=Modifier.clickable {addChooser=false;manualReturn="editor";vm.beginManual();page="review"})
        Spacer(Modifier.height(24.dp))
    }
    if(noVisualGuide)AlertDialog(onDismissRequest={noVisualGuide=false},title={Text(stringResource(R.string.food_no_visual_title))},text={Text(stringResource(R.string.food_no_visual))},confirmButton={TextButton(onClick={noVisualGuide=false;servicesReturn=page;page="services"}){Text(stringResource(R.string.food_configure_ai))}},dismissButton={TextButton(onClick={noVisualGuide=false}){Text(stringResource(R.string.cancel))}})
    if(reanalyze)AlertDialog(onDismissRequest={reanalyze=false},title={Text(stringResource(R.string.food_change_context))},text={Text(stringResource(R.string.food_reanalysis_replace))},confirmButton={TextButton(onClick={reanalyze=false;vm.updateBatch {it.copy(status="idle")};resetContentScroll()}){Text(stringResource(R.string.food_edit_analysis_context))}},dismissButton={TextButton(onClick={reanalyze=false}){Text(stringResource(R.string.cancel))}})
    if(abandon)AlertDialog(onDismissRequest={abandon=false},title={Text(stringResource(R.string.food_discard_addition))},text={Text(stringResource(R.string.food_pending_note))},confirmButton={TextButton(onClick={vm.abandonBatch();abandon=false;page="editor"}){Text(stringResource(R.string.food_discard_addition))}},dismissButton={TextButton(onClick={abandon=false}){Text(stringResource(R.string.cancel))}})
    libraryEditing?.let {entry->FoodEditDialog(entry.item,onDismiss={libraryEditing=null}){vm.updateLibrary(entry.id,it);libraryEditing=null}}
    draft?.batch?.takeIf {it.id==mergeBatchId&&it.kind=="photo"&&it.status=="review"&&it.results.size>=2&&!s.busy&&!s.saving}?.let {batch->
        FoodDishMergeDialog(batch,onDismiss={mergeBatchId=null}){ids,name,portion->if(vm.mergeDish(batch.id,ids,name,portion))mergeBatchId=null}
    }
    if(syncGuide)AlertDialog(onDismissRequest={syncGuide=false},title={Text(stringResource(R.string.food_local_saved_title))},text={Text(stringResource(if(s.healthAvailable)R.string.food_sync_guide else R.string.food_health_unavailable))},confirmButton={TextButton(onClick={syncGuide=false;if(s.healthAvailable)authorize() else openHealth()}){Text(stringResource(if(s.healthAvailable)R.string.food_nutrition_authorize else R.string.health_settings))}},dismissButton={TextButton(onClick={syncGuide=false}){Text(stringResource(R.string.food_continue_local))}})
    if(discardDialog)AlertDialog(onDismissRequest={discardDialog=false},title={Text(stringResource(R.string.food_discard))},text={Text(stringResource(R.string.food_draft_note))},confirmButton={TextButton(onClick={vm.discard();discardDialog=false;page="home"}){Text(stringResource(R.string.food_discard))}},dismissButton={TextButton(onClick={discardDialog=false}){Text(stringResource(R.string.cancel))}})
    if(selector.isNotEmpty()) {
        var default by remember(selector){mutableStateOf(false)}
        val visual=selector.startsWith("vision")
        val choosingDefault=selector.endsWith("-default")
        val eligible=s.services.filter {!visual||it.images}
        val choices=if(choosingDefault)eligible else s.services
        AlertDialog(onDismissRequest={selector=""},title={Text(stringResource(when(selector){"vision-default"->R.string.settings_image_default;"text-default"->R.string.settings_text_default;else->R.string.food_service_choose}))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            choices.forEach {service->TextButton(enabled=!visual||service.images,onClick={when(selector){"vision-current"->vm.selectService(service.id,default);"vision-default"->vm.defaultService(service.id,true);else->vm.defaultService(service.id,false)};selector=""}){Column{Text(service.name);Text(service.model+" · "+stringResource(if(service.format=="anthropic")R.string.ai_anthropic else R.string.ai_openai),style=MaterialTheme.typography.bodySmall);Text(stringResource(if(service.images)R.string.food_service_vision else R.string.food_service_text_only),style=MaterialTheme.typography.bodySmall)}}}
            if(selector=="vision-current")Row(verticalAlignment=Alignment.CenterVertically){Checkbox(default,{default=it});Text(stringResource(R.string.food_set_default))}
            if(eligible.isEmpty()) {
                Text(stringResource(if(visual)R.string.ai_no_image_choices else R.string.ai_no_text_choices))
                TextButton(onClick={selector="";servicesReturn=page;page="services";vm.clearMessage();serviceEditor=AiService()}){Text(stringResource(R.string.food_service_add))}
            } else if(!choosingDefault)TextButton(onClick={selector="";servicesReturn=page;page="services"}){Text(stringResource(R.string.food_services))}
        }},confirmButton={TextButton(onClick={selector=""}){Text(stringResource(R.string.cancel))}})
    }
    consent?.let {service->AlertDialog(onDismissRequest={consent=null},title={Text(stringResource(R.string.food_ai_consent_title))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(service.name+"\n"+service.baseUrl);Text(stringResource(R.string.food_ai_consent_note))}},confirmButton={TextButton(onClick={consent=null;vm.consent(service){vm.analyze()}}){Text(stringResource(R.string.food_send_agree))}},dismissButton={TextButton(onClick={consent=null}){Text(stringResource(R.string.cancel))}})}
    confirmServiceDelete?.let {service->AlertDialog(onDismissRequest={confirmServiceDelete=null},title={Text(stringResource(R.string.food_service_delete))},text={Text(service.name+"\n"+stringResource(R.string.food_delete_service_confirm))},confirmButton={TextButton(onClick={vm.deleteService(service.id);confirmServiceDelete=null}){Text(stringResource(R.string.food_service_delete))}},dismissButton={TextButton(onClick={confirmServiceDelete=null}){Text(stringResource(R.string.cancel))}})}
    selectedMeal?.let {id->SavedMealDetails(vm,id,onDismiss={selectedMeal=null},onEditDraft={onOpenEditor("editor")},authorize=authorize)}
}

@Composable internal fun SavedMealDetails(vm:FoodViewModel,mealId:String,onDismiss:()->Unit,onEditDraft:()->Unit,authorize:()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val meal=state.meals.find {it.id==mealId&&!it.deleted}
    LaunchedEffect(state.loaded,meal==null){if(state.loaded&&meal==null)onDismiss()}
    if(meal==null)return
    var deleting by remember(mealId){mutableStateOf(false)}
    var pendingAction by remember(mealId){mutableStateOf<String?>(null)}
    fun editDraft(repeat:Boolean) {
        if(repeat)vm.repeat(meal)else vm.edit(meal)
        onDismiss();onEditDraft()
    }
    fun requestEdit(repeat:Boolean) {
        if(state.draft?.isEmpty()==false)pendingAction=if(repeat)"repeat"else"edit"
        else editDraft(repeat)
    }
    MealDetailsSheet(
        meal=meal,onDismiss=onDismiss,onRepeat={requestEdit(true)},onEdit={requestEdit(false)},
        onRetry={if(state.permitted)vm.retry(meal.id)else authorize()},
        retryLabel=if(state.permitted)R.string.retry else R.string.food_nutrition_authorize,
        onDelete={deleting=true}
    )
    if(pendingAction!=null)AlertDialog(
        onDismissRequest={pendingAction=null},title={Text(stringResource(R.string.food_draft_exists))},
        text={Text(stringResource(R.string.food_draft_note))},
        confirmButton={TextButton(onClick={editDraft(pendingAction=="repeat")}){Text(stringResource(R.string.food_new_anyway))}},
        dismissButton={TextButton(onClick={onDismiss();onEditDraft()}){Text(stringResource(R.string.food_resume))}}
    )
    if(deleting)AlertDialog(
        onDismissRequest={deleting=false},title={Text(stringResource(R.string.food_delete_title))},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.food_delete_note))
            OutlinedButton(onClick={vm.delete(meal,false);onDismiss()},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.food_delete_local))}
            OutlinedButton(onClick={vm.delete(meal,true);onDismiss()},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.food_delete_both))}
        }},
        confirmButton={TextButton(onClick={deleting=false}){Text(stringResource(R.string.cancel))}}
    )
}

private val FoodStarOutline by lazy {
    ImageVector.Builder("FoodStarOutline",24.dp,24.dp,24f,24f).apply {
        path(fill=null,stroke=androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),strokeLineWidth=1.8f,strokeLineJoin=androidx.compose.ui.graphics.StrokeJoin.Round){
            moveTo(12f,3f);lineTo(14.78f,8.63f);lineTo(21f,9.54f);lineTo(16.5f,13.93f);lineTo(17.56f,20.14f);lineTo(12f,17.21f);lineTo(6.44f,20.14f);lineTo(7.5f,13.93f);lineTo(3f,9.54f);lineTo(9.22f,8.63f);close()
        }
    }.build()
}
private val FoodCardShape=RoundedCornerShape(22.dp)
private val FoodImageShape=RoundedCornerShape(12.dp)

@Composable private fun FoodCard(color:androidx.compose.ui.graphics.Color=lerp(MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.surfaceVariant,.42f),padding:PaddingValues=PaddingValues(18.dp),content:@Composable ColumnScope.()->Unit) {Surface(shape=FoodCardShape,color=color,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(padding),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
/** One food presentation shared by pending analysis and confirmed meal items. */
@Composable private fun FoodItemCard(
    food:FoodItem,favorite:Boolean,onFavorite:(Boolean)->Unit,onEdit:()->Unit,onRemove:()->Unit,
    enabled:Boolean=true,editEnabled:Boolean=enabled,expanded:Boolean=false,showHeader:Boolean=true,
    editor:@Composable ()->Unit={}
) {
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val number=remember(locale){NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=1}}
    FoodCard {
        if(showHeader)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(food.name.ifBlank {stringResource(R.string.food_manual_action)},style=MaterialTheme.typography.titleLarge)
                if(!expanded)Text("${food.nutrients.kcal?.let {number.format(it)} ?: "—"} kcal · ${food.portion}",style=MaterialTheme.typography.bodyMedium)
            }
            IconToggleButton(checked=favorite,enabled=enabled&&food.copy(needsPortionReview=false).valid(),onCheckedChange=onFavorite){
                Icon(if(favorite)Icons.Default.Star else FoodStarOutline,contentDescription=stringResource(if(favorite)R.string.food_unfavorite else R.string.food_to_favorites),tint=if(favorite)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(expanded)editor() else NutritionRow(food.nutrients)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(enabled=editEnabled,onClick=onEdit,modifier=Modifier.weight(1f)){Text(stringResource(if(expanded)R.string.food_collapse_fields else R.string.food_edit))}
            TextButton(enabled=enabled,onClick=onRemove){Text(stringResource(R.string.food_remove),color=MaterialTheme.colorScheme.error)}
        }
        if(food.assumptions.isNotBlank()||food.estimateContext!=null)FoodEstimateExplanation(food,number)
        if(food.hasEatingSuggestions)Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.food_eating_advice),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(food.eatingAdvice.isNotBlank())FoodSuggestion(R.string.food_advice_now,food.eatingAdvice)
            if(food.nextTimeAdvice.isNotBlank())FoodSuggestion(R.string.food_advice_next,food.nextTimeAdvice)
            if(food.eatingAdvice.isBlank()&&food.nextTimeAdvice.isBlank()&&food.positiveFeedback.isNotBlank())
                Text(food.positiveFeedback,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun FoodSuggestion(label:Int,text:String) {
    Text(stringResource(label)+"　"+text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun MealAdviceCard(advice:MealAdvice,nutrients:Nutrients,usage:AiUsage,analysisMs:Long?) {
    Surface(
        shape=FoodCardShape,
        color=lerp(MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.primaryContainer,.32f),
        contentColor=MaterialTheme.colorScheme.onSurface,
        modifier=Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Favorite,null,Modifier.padding(8.dp).size(18.dp),tint=MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.food_meal_advice),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            }
            MealNutritionSummary(nutrients)
            if(usage.hasContent||analysisMs!=null)FoodAnalysisUsage(usage,analysisMs)
            if(advice.hasContent)HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))
            if(advice.healthiness.isNotBlank())Text(advice.healthiness,style=MaterialTheme.typography.bodyLarge,lineHeight=25.sp)
            if(advice.balance.isNotBlank())Text(advice.balance,style=MaterialTheme.typography.bodyLarge,lineHeight=25.sp)
            if(advice.nextTime.isNotBlank()) {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.food_advice_next),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    Text(advice.nextTime,style=MaterialTheme.typography.bodyMedium,lineHeight=22.sp)
                }
            }
            if(advice.note.isNotBlank())Text(advice.note,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(advice.hasContent)Text(stringResource(R.string.food_meal_advice_note),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun MealNutritionSummary(nutrients:Nutrients) {
    val locale=LocalConfiguration.current.locales[0]
    val energy=remember(locale){NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=0}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.food_intake_estimate),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(nutrients.kcal?.let {energy.format(it)} ?: "—",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
            Text("kcal",modifier=Modifier.padding(bottom=4.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NutritionRow(nutrients)
        if(listOf(nutrients.kcal,nutrients.protein,nutrients.carbs,nutrients.fat).any {it==null})
            Text(stringResource(R.string.food_nutrition_partial),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun FoodPanel(onDismiss:()->Unit,onOutside:()->Unit=onDismiss,content:@Composable ColumnScope.()->Unit) {
    val colors=MaterialTheme.colorScheme
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        SideEffect {(view.parent as? DialogWindowProvider)?.window?.setWindowAnimations(0)}
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().clickable(interactionSource=remember {MutableInteractionSource()},indication=null,onClick=onOutside))
            Surface(
                color=colors.surface,shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),
                modifier=Modifier.align(Alignment.BottomCenter).widthIn(max=640.dp).fillMaxWidth().height(maxHeight*.9f)
                    .pointerInput(Unit){detectTapGestures {}}
            ) {
                Column(Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))
                    .padding(top=16.dp),content=content)
            }
        }
    }
}

@Composable private fun MealDetailsSheet(
    meal:Meal,onDismiss:()->Unit,onEdit:()->Unit,onRepeat:()->Unit,onRetry:()->Unit,retryLabel:Int,onDelete:()->Unit
) {
    val colors=MaterialTheme.colorScheme
    FoodPanel(onDismiss=onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.food_view_details),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text(mealTimeLabel(meal),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            }
            IconButton(onClick=onDismiss) {Icon(Icons.Default.Close,stringResource(R.string.done))}
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),
            verticalArrangement=Arrangement.spacedBy(18.dp)
        ) {
            Text(meal.title,style=MaterialTheme.typography.titleMedium,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(meal.advice.hasContent||meal.usage.hasContent||meal.analysisMs!=null)MealAdviceCard(meal.advice,meal.nutrients,meal.usage,meal.analysisMs)
            else FoodCard {MealNutritionSummary(meal.nutrients)}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(if(meal.status=="synced")Icons.Default.CheckCircle else Icons.Default.Info,null,Modifier.size(18.dp),tint=colors.onSurfaceVariant)
                Text(stringResource(foodStatus(meal.status)),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,modifier=Modifier.weight(1f))
                if(meal.status!="synced"&&meal.status!="writing")TextButton(onClick=onRetry){Text(stringResource(retryLabel))}
            }
            Text(stringResource(R.string.food_meal_items),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            meal.items.forEach {food->key(food.id){MealDetailFood(food)}}
            Spacer(Modifier.height(4.dp))
        }
        HorizontalDivider(color=colors.outlineVariant.copy(alpha=.6f))
        Column(Modifier.padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick=onEdit,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(stringResource(R.string.food_edit_meal))}
                FilledTonalButton(onClick=onRepeat,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(stringResource(R.string.food_repeat))}
            }
            TextButton(onClick=onDelete,colors=ButtonDefaults.textButtonColors(contentColor=colors.error),modifier=Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.food_delete_title))
            }
        }
    }
}

@Composable private fun MealDetailFood(food:FoodItem) {
    var expanded by rememberSaveable(food.id){mutableStateOf(false)}
    val locale=LocalConfiguration.current.locales[0]
    val number=remember(locale){NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=1}}
    Surface(shape=FoodCardShape,color=lerp(MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.surfaceVariant,.42f),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clip(FoodImageShape).clickable(role=Role.Button,onClick={expanded=!expanded}).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(food.name,style=MaterialTheme.typography.titleMedium)
                    Text(food.portion,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${food.nutrients.kcal?.let {number.format(it)} ?: "—"} kcal",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                Icon(if(expanded)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,stringResource(if(expanded)R.string.food_estimate_hide_details else R.string.food_estimate_show_details),Modifier.size(20.dp))
            }
            if(expanded) {
                NutritionRow(food.nutrients)
                if(food.assumptions.isNotBlank()||food.estimateContext!=null)FoodEstimateExplanation(food,number)
                if(food.eatingAdvice.isNotBlank())FoodSuggestion(R.string.food_advice_now,food.eatingAdvice)
                if(food.nextTimeAdvice.isNotBlank())FoodSuggestion(R.string.food_advice_next,food.nextTimeAdvice)
                if(food.eatingAdvice.isBlank()&&food.nextTimeAdvice.isBlank()&&food.positiveFeedback.isNotBlank())Text(food.positiveFeedback,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable private fun FoodAnalysisReady() {
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.CheckCircle,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.food_analysis_ready),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
@Composable private fun FoodAnalysisUsage(usage:AiUsage,analysisMs:Long?) {
    if(!usage.hasContent&&analysisMs==null)return
    val seconds=analysisMs?.let {(it/1000.0).coerceAtLeast(0.1)}
    val tokens=listOfNotNull(
        usage.inputTokens?.let {stringResource(R.string.food_usage_input,it)},
        usage.outputTokens?.let {stringResource(R.string.food_usage_output,it)},
        usage.totalTokens?.let {stringResource(R.string.food_usage_total,it)}
    )
    Text(
        listOfNotNull(seconds?.let {stringResource(R.string.food_analysis_time,it)},tokens.takeIf {it.isNotEmpty()}?.joinToString(" · "))
            .joinToString(" · "),
        style=MaterialTheme.typography.labelSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant
    )
}
@Composable private fun FoodAnalysisStatus(stageValue:String,attempt:Int,startedAt:Long,cancel:()->Unit) {
    val stage=when(stageValue) {
            "encoding"->R.string.food_analysis_encoding
            "uploading"->R.string.food_analysis_uploading
            "waiting"->R.string.food_analysis_waiting
            "parsing"->R.string.food_analysis_parsing
            "retrying"->R.string.food_analysis_retrying
            "ready"->R.string.food_analysis_ready
            else->R.string.food_analyzing
    }
    var elapsed by remember(startedAt){mutableLongStateOf(if(startedAt>0)(System.currentTimeMillis()-startedAt)/1000 else 0L)}
    val seconds=elapsed.toInt()
    LaunchedEffect(startedAt){
        while(startedAt>0&&elapsed<600) {
            delay(1000)
            elapsed=(System.currentTimeMillis()-startedAt)/1000
        }
    }
    FoodCard {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(if(stageValue=="retrying")stringResource(stage,attempt-1,3) else stringResource(stage),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            if(seconds>0)Text(stringResource(R.string.food_analysis_elapsed,seconds),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.food_analysis_stage_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick=cancel,contentPadding=PaddingValues(0.dp)){Text(stringResource(R.string.cancel))}
    }
}
private fun estimateRatio(value:Double,number:NumberFormat):String {
    for(denominator in 2..12)for(numerator in 1 until denominator) {
        if(kotlin.math.abs(value-numerator.toDouble()/denominator)<0.00001)return "$numerator/$denominator"
    }
    return number.format(value*100)+"%"
}
@Composable private fun FoodEstimateExplanation(food:FoodItem,number:NumberFormat) {
    var detailsExpanded by rememberSaveable(food.id){mutableStateOf(false)}
    val context=food.estimateContext
    val hasAdjustment=context!=null&&(context.shareCount>1||context.consumedFraction!=1.0)
    val summary=when {
        context?.resultMode=="personal"->context.summary.ifBlank {food.assumptions.take(80).ifBlank {stringResource(R.string.food_estimate_summary_default)}}
        context!=null&&hasAdjustment&&context.fractionBasis=="whole"->stringResource(R.string.food_estimate_summary_whole,estimateRatio(context.consumedFraction,number))
        context!=null&&context.shareCount>1&&context.consumedFraction==1.0->stringResource(R.string.food_estimate_summary_shared,context.shareCount)
        context!=null&&context.shareCount>1->stringResource(R.string.food_estimate_summary_shared_part,context.shareCount,estimateRatio(context.consumedFraction,number))
        context!=null&&context.consumedFraction!=1.0->stringResource(R.string.food_estimate_summary_personal,estimateRatio(context.consumedFraction,number))
        food.assumptions.isNotBlank()->food.assumptions.take(80).let {if(food.assumptions.length>80)"$it…" else it}
        else->stringResource(R.string.food_estimate_summary_default)
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.food_estimation_basis),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(context?.warning?.isNotBlank()==true)Text(context.warning,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(context!=null&&context.interpretation.isBlank()&&(context.tags.isNotEmpty()||context.description.isNotBlank()))
            Text(stringResource(R.string.food_context_missing),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick={detailsExpanded=!detailsExpanded},contentPadding=PaddingValues(horizontal=0.dp)) {
            Text(stringResource(if(detailsExpanded)R.string.food_estimate_hide_details else R.string.food_estimate_show_details))
            Spacer(Modifier.width(4.dp))
            Icon(if(detailsExpanded)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,contentDescription=null,modifier=Modifier.size(18.dp))
        }
        if(detailsExpanded) {
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            if(context!=null)FoodEstimateDetails(context,food.assumptions,number)
            else FoodEstimateSection(R.string.food_estimate_understanding){Text(food.assumptions,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
@Composable private fun FoodEstimateSection(title:Int,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
@Composable private fun FoodEstimateDetails(context:FoodEstimateContext,assumptions:String,number:NumberFormat) {
    FoodEstimateSection(R.string.food_estimate_inputs) {
        val inputs=listOf(stringResource(if(context.shareCount>1)R.string.food_context_shared else R.string.food_tag_solo,context.shareCount))+context.tags
        Text(inputs.joinToString(" · "),style=MaterialTheme.typography.bodySmall)
        if(context.description.isNotBlank())Text(context.description,style=MaterialTheme.typography.bodySmall)
    }
    FoodEstimateSection(R.string.food_estimate_understanding) {
        if(context.interpretation.isNotBlank())Text(context.interpretation,style=MaterialTheme.typography.bodySmall)
        if(assumptions.isNotBlank())Text(assumptions,style=MaterialTheme.typography.bodySmall)
        if(context.interpretation.isBlank()&&assumptions.isBlank())Text(stringResource(R.string.food_context_missing),style=MaterialTheme.typography.bodySmall)
    }
    FoodEstimateSection(if(context.resultMode=="personal")R.string.food_estimate_personal_intake else R.string.food_estimate_calculation) {
        if(context.resultMode=="personal") {
            context.reportedGrams?.let {Text("${number.format(it)} g",style=MaterialTheme.typography.bodySmall)}
            context.reportedNutrients.kcal?.let {Text("${number.format(it)} kcal",style=MaterialTheme.typography.bodySmall)}
            NutritionRow(context.reportedNutrients)
            Text(stringResource(R.string.food_estimate_direct_note),style=MaterialTheme.typography.bodySmall)
        } else {
        val division=if(context.fractionBasis=="own"&&context.shareCount>1)" ÷ ${context.shareCount}" else ""
        val fraction=if(context.consumedFraction!=1.0)" × ${estimateRatio(context.consumedFraction,number)}" else ""
        Text(stringResource(if(context.fractionBasis=="whole")R.string.food_context_whole_fraction else R.string.food_context_own_fraction,number.format(context.consumedFraction*100)),style=MaterialTheme.typography.bodySmall)
        context.wholeGrams?.let {Text("${number.format(it)} g$division$fraction ≈ ${number.format(it*context.factor)} g",style=MaterialTheme.typography.bodySmall)}
        context.wholeNutrients.kcal?.let {Text("${number.format(it)} kcal$division$fraction ≈ ${number.format(it*context.factor)} kcal",style=MaterialTheme.typography.bodySmall)}
    }
    }
    Text(stringResource(R.string.food_context_snapshot),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable internal fun NutritionRow(n:Nutrients) {val f=NumberFormat.getNumberInstance().apply {maximumFractionDigits=1};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(R.string.food_protein to n.protein,R.string.food_carbs to n.carbs,R.string.food_fat to n.fat).forEach {(label,value)->Column(Modifier.weight(1f)){Text(stringResource(label),style=MaterialTheme.typography.labelSmall);Text(value?.let {f.format(it)} ?: "—",style=MaterialTheme.typography.titleMedium)}}}}
@Composable private fun MealRow(meal:Meal,time:String,action:()->Unit){Card(onClick=action,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(time,style=MaterialTheme.typography.labelMedium);Text(meal.title,style=MaterialTheme.typography.titleMedium);Text("${meal.nutrients.kcal?.let {NumberFormat.getNumberInstance().format(it)} ?: "—"} kcal");Text(stringResource(foodStatus(meal.status)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}}}
private fun foodStatus(status:String)=when(status){"synced"->R.string.status_synced;"writing"->R.string.status_writing;"permission"->R.string.status_permission;"unavailable"->R.string.food_health_unavailable;"failed"->R.string.status_failed;"deleting"->R.string.status_deleting;"delete_failed"->R.string.status_delete_failed;else->R.string.status_pending}
@Composable private fun PhotoPreview(path:String,compact:Boolean=false){val bitmap by produceState<ImageBitmap?>(null,path){value=withContext(Dispatchers.IO){run {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(path,bounds)
        val options=BitmapFactory.Options().apply {inSampleSize=1;while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>if(compact)320 else 960)inSampleSize*=2}
        BitmapFactory.decodeFile(path,options)?.asImageBitmap()
    }}};bitmap?.let {Image(it,contentDescription=stringResource(R.string.food_photo),contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().height(if(compact)88.dp else 190.dp).clip(FoodImageShape))}}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MealTime(meal:Meal,change:(Meal)->Unit){
    val context=LocalContext.current
    val t=Instant.ofEpochMilli(meal.time).atZone(ZoneId.systemDefault())
    Text(t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
    Row {TextButton(onClick={DatePickerDialog(context,{_,y,m,d->val updated=t.withDayOfMonth(1).withYear(y).withMonth(m+1).withDayOfMonth(d);change(meal.copy(time=updated.toInstant().toEpochMilli(),offset=updated.offset.totalSeconds))},t.year,t.monthValue-1,t.dayOfMonth).show()}){Text(stringResource(R.string.food_choose_date))};TextButton(onClick={TimePickerDialog(context,{_,hour,minute->val updated=t.withHour(hour).withMinute(minute);change(meal.copy(time=updated.toInstant().toEpochMilli(),offset=updated.offset.totalSeconds))},t.hour,t.minute,true).show()}){Text(stringResource(R.string.food_choose_time))}}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(1 to R.string.food_breakfast,2 to R.string.food_lunch,3 to R.string.food_dinner,4 to R.string.food_snack,0 to R.string.food_unspecified).forEach {(type,label)->FilterChip(selected=meal.mealType==type,onClick={change(meal.copy(mealType=type))},label={Text(stringResource(label))})}}
}

@Composable private fun mealTimeLabel(meal:Meal):String {
    val time=Instant.ofEpochMilli(meal.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val type=when(meal.mealType){1->R.string.food_breakfast;2->R.string.food_lunch;3->R.string.food_dinner;4->R.string.food_snack;else->R.string.food_unspecified}
    return "$time · ${stringResource(type)}"
}

@Composable private fun FoodFields(original:FoodItem,enabled:Boolean=true,onChange:(FoodItem)->Unit) {
    val editKey=original.id
    val item=remember(editKey){original.reuse().copy(id=original.id,needsPortionReview=original.needsPortionReview)}
    var name by rememberSaveable(editKey){mutableStateOf(item.name)}
    var portion by rememberSaveable(editKey){mutableStateOf(item.portion)}
    fun display(n:Double?)=n?.let {if(it.isFinite())java.math.BigDecimal.valueOf(it).setScale(3,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() else it.toString()}.orEmpty()
    var gramValue by rememberSaveable(editKey){mutableStateOf(item.grams)}
    var referenceGrams by rememberSaveable(editKey){mutableStateOf(item.grams?.takeIf {it>0&&it.isFinite()})}
    var grams by rememberSaveable(editKey){mutableStateOf(display(item.grams))}
    var values by rememberSaveable(editKey){mutableStateOf(listOf(item.base.kcal,item.base.protein,item.base.carbs,item.base.fat))}
    var numbers by rememberSaveable(editKey){mutableStateOf(values.map {display(it)})}
    fun value(raw:String):Double?=if(raw.isBlank())null else raw.replace(',','.').toDoubleOrNull() ?: Double.NaN
    fun current()=item.copy(name=name.trim(),portion=portion.trim(),grams=gramValue,base=Nutrients(values[0],values[1],values[2],values[3]),needsPortionReview=false)
    fun publish(){onChange(current())}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(name,{name=it.take(120);publish()},enabled=enabled,label={Text(stringResource(R.string.food_name))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(portion,{portion=it.take(100);publish()},enabled=enabled,label={Text(stringResource(R.string.food_portion))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(grams,{v->
            val before=referenceGrams;val after=value(v)
            if(before!=null&&before.isFinite()&&before>0&&after!=null&&after.isFinite()&&after>0){values=values.map {it?.times(after/before)};numbers=values.map {display(it)}}
            gramValue=after;grams=v;if(after!=null&&after.isFinite()&&after>0){referenceGrams=after;portion=display(after)+" g"};publish()},enabled=enabled,label={Text(stringResource(R.string.food_grams))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
        Text(stringResource(R.string.food_personal_nutrition),style=MaterialTheme.typography.labelLarge)
        listOf(R.string.food_kcal,R.string.food_protein,R.string.food_carbs,R.string.food_fat).forEachIndexed {i,label->OutlinedTextField(numbers[i],{v->numbers=numbers.mapIndexed {j,x->if(j==i)v else x};values=values.mapIndexed {j,x->if(j==i)value(v) else x};publish()},enabled=enabled,label={Text(stringResource(label))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())}
        if((listOf(grams)+numbers).any {it.isNotBlank()&&value(it)?.let {n->!n.isFinite()||n<0}==true})Text(stringResource(R.string.food_bad_number),color=MaterialTheme.colorScheme.error)
    }
}
@Composable private fun SharingCountDialog(initial:Int,onDismiss:()->Unit,onSave:(Int)->Unit) {
    var count by remember {mutableStateOf(initial.coerceAtLeast(2).toString())}
    val number=count.toIntOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.food_sharing_other))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(stringResource(R.string.food_sharing_explanation))
        OutlinedTextField(count,{count=it.filter(Char::isDigit).take(2)},label={Text(stringResource(R.string.food_sharing_count))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
    }},confirmButton={TextButton(enabled=number!=null&&number in 2..20,onClick={onSave(number!!)}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}
@Composable private fun FoodEditDialog(original:FoodItem,onDismiss:()->Unit,save:(FoodItem)->Unit) {
    var edited by remember {mutableStateOf(original.reuse().copy(id=original.id))}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.food_edit))},text={Column(Modifier.verticalScroll(rememberScrollState())){FoodFields(edited,onChange={edited=it})}},confirmButton={TextButton(enabled=edited.valid(),onClick={save(edited)}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable private fun ServiceEditPage(service:AiService,existing:Boolean,error:Int?,message:Int?,busy:Boolean,showNavigation:Boolean=true,onChanged:()->Unit,onDismiss:()->Unit,onTest:(AiService,String)->Unit,onModels:(AiService,String,(AiModelCatalog)->Unit)->Unit,onCancelModels:()->Unit,onDelete:()->Unit,save:(AiService,String)->Unit){
    var name by remember(service.id){mutableStateOf(service.name)};var url by remember(service.id){mutableStateOf(service.baseUrl)}
    var model by remember(service.id){mutableStateOf(service.model)};var key by remember(service.id){mutableStateOf("")};var images by remember(service.id){mutableStateOf(service.images)}
    var format by remember(service.id){mutableStateOf(service.format)}
    var showModels by remember(service.id){mutableStateOf(false)}
    var catalog by remember(service.id){mutableStateOf<AiModelCatalog?>(null)}
    val hasSavedKey=existing||service.encryptedKey.isNotBlank()
    fun input()=service.copy(name=name,baseUrl=url,model=model,images=images,format=format)
    fun fetch(){catalog=null;onModels(input(),key){catalog=it}}
    if(showModels)AiModelPicker(catalog,model,busy,error,onRetry={fetch()},onDismiss={onCancelModels();showModels=false},onSelect={model=it;onChanged();showModels=false})
    Column(Modifier.fillMaxWidth().imePadding(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(showNavigation)IconButton(onClick=onDismiss){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.back_food))}
        if(showNavigation)Text(stringResource(if(existing)R.string.food_edit_service else R.string.food_service_add),style=MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name,{name=it;onChanged()},enabled=!busy,modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.food_service_name))})
        Text(stringResource(R.string.ai_format),style=MaterialTheme.typography.labelLarge)
        listOf("openai" to R.string.ai_openai,"anthropic" to R.string.ai_anthropic).forEach {(id,label)->Row(Modifier.fillMaxWidth().selectable(selected=format==id,enabled=!busy,role=Role.RadioButton,onClick={format=id;onChanged()}).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(format==id,null,enabled=!busy);Text(stringResource(label))}}
        Text(stringResource(if(format=="anthropic")R.string.ai_anthropic_hint else R.string.ai_openai_hint),style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(url,{url=it;onChanged()},enabled=!busy,modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.food_service_url))},placeholder={Text(if(format=="anthropic")"https://api.anthropic.com" else "https://api.openai.com/v1")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(model,{model=it;onChanged()},enabled=!busy,modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.food_service_model))},singleLine=true)
            TextButton(enabled=!busy&&url.isNotBlank()&&(hasSavedKey||key.isNotBlank()),onClick={showModels=true;fetch()}){Text(stringResource(R.string.ai_models_fetch))}
            Text(stringResource(R.string.ai_models_optional),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(key,{key=it;onChanged()},enabled=!busy,modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.food_service_key))},visualTransformation=PasswordVisualTransformation())
        if(hasSavedKey)Text(stringResource(if(existing)R.string.ai_key_update else R.string.ai_service_copied_key),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().selectable(selected=images,enabled=!busy,role=Role.Switch,onClick={images=!images;onChanged()}).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.food_service_images),Modifier.weight(1f));Switch(images,null,enabled=!busy,colors=settingsSwitchColors())}
        Text(stringResource(R.string.food_test_note),style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!busy&&name.isNotBlank()&&url.isNotBlank()&&model.isNotBlank()&&(hasSavedKey||key.isNotBlank()),onClick={onTest(input(),key)}){Text(stringResource(R.string.food_test_service))}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message==R.string.food_connection_ok)Text(stringResource(message),color=MaterialTheme.colorScheme.primary)
        if(existing)TextButton(enabled=!busy,onClick=onDelete){Text(stringResource(R.string.food_service_delete),color=MaterialTheme.colorScheme.error)}
        Text(stringResource(R.string.food_service_hint),style=MaterialTheme.typography.bodySmall)
        error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
        Button(enabled=!busy&&name.isNotBlank()&&url.isNotBlank()&&model.isNotBlank()&&(hasSavedKey||key.isNotBlank()),onClick={save(input(),key)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(R.string.save))}
    }
}

@Composable private fun AiModelPicker(catalog:AiModelCatalog?,selected:String,busy:Boolean,error:Int?,onRetry:()->Unit,onDismiss:()->Unit,onSelect:(String)->Unit) {
    var query by remember {mutableStateOf("")}
    val choices=catalog?.models.orEmpty().filter {it.id.contains(query,true)||it.name.contains(query,true)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.ai_models_choose))},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ai_models_capability_note),style=MaterialTheme.typography.bodySmall)
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.ai_models_loading))}
            else if(error!=null) {Text(stringResource(error),color=MaterialTheme.colorScheme.error);Text(stringResource(R.string.ai_models_manual_fallback),style=MaterialTheme.typography.bodySmall)}
            if(catalog!=null) {
                OutlinedTextField(query,{query=it},label={Text(stringResource(R.string.ai_models_search))},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(catalog.models.isEmpty())Text(stringResource(R.string.ai_models_empty))
                else if(choices.isEmpty())Text(stringResource(R.string.ai_models_no_match))
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
                    items(choices,key={it.id}) {option->
                        Row(Modifier.fillMaxWidth().selectable(selected=option.id==selected,role=Role.RadioButton,onClick={onSelect(option.id)}).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(option.id==selected,null)
                            Column(Modifier.weight(1f)) {
                                if(option.name.isNotBlank()&&option.name!=option.id)Text(option.name,style=MaterialTheme.typography.bodyMedium)
                                Text(option.id,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if(catalog.truncated)Text(stringResource(R.string.ai_models_partial),style=MaterialTheme.typography.bodySmall)
            }
        }
    },confirmButton={if(!busy)TextButton(onClick=onRetry){Text(stringResource(R.string.ai_models_retry))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.ai_models_manual))}})
}

@Composable fun NutritionConnectionCard(available:Boolean,permitted:Boolean,authorize:()->Unit,openSettings:()->Unit){
    FoodCard(color=MaterialTheme.colorScheme.secondaryContainer){
        Text(stringResource(R.string.food_title)+" · Health Connect",style=MaterialTheme.typography.titleLarge)
        Text(stringResource(if(!available)R.string.food_health_unavailable else if(permitted)R.string.records_nutrition_allowed else R.string.records_nutrition_local))
        if(available&&!permitted)OutlinedButton(onClick=authorize,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.food_nutrition_authorize))}
        TextButton(onClick=openSettings){Text(stringResource(R.string.health_settings))}
        Text(stringResource(R.string.food_nutrition_note),style=MaterialTheme.typography.bodySmall)
    }
}
