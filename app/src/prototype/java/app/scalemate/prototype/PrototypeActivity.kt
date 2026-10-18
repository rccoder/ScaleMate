package app.scalemate.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Future health AI design only. Legacy food branches are context, not the production contract.
 * Synthetic data and in-memory state; no health, camera, BLE or network calls. */
class PrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Prototype() }
    }
}

private val Green = Color(0xFF376A52)
private val Palette = lightColorScheme(primary=Green, onPrimary=Color.White,
    primaryContainer=Color(0xFFDCEBDD), onPrimaryContainer=Color(0xFF173E2D),
    secondaryContainer=Color(0xFFF0E8DA), background=Color(0xFFF8F9F5), surface=Color(0xFFF8F9F5),
    surfaceContainer=Color(0xFFEEF1EA), surfaceContainerLow=Color.White,
    onSurface=Color(0xFF202B24), onSurfaceVariant=Color(0xFF667369), outlineVariant=Color(0xFFDDE3D9))
private data class Food(val name: String, val portion: String, val kcal: String, val protein: String, val carbs: String, val fat: String)
private data class Service(val name: String, val model: String, val image: Boolean)
private val ExampleFoods = listOf(Food("水煮牛肉", "共享菜 · 分到 1/2", "380", "28", "10", "25"), Food("米饭", "照片这碗的 1/3", "78", "1.5", "17", "0.2"))
private val Questions = listOf("昨晚睡得怎么样？", "最近一周最值得改善什么？", "最近的活动量怎么样？", "睡眠期间的心率有变化吗？", "最近恢复相关指标怎样？", "今天已记录的饮食有哪些特点？")
private val DemoJson = """{
  "schemaVersion": "1.0",
  "exportId": "mock-preview",
  "mode": "summary",
  "timezone": "Asia/Shanghai",
  "synthetic": true,
  "metrics": [{
    "date": "2026-10-16",
    "sleepMinutes": 450,
    "sleepHeartRateMedian": 60,
    "hrvRmssdMedian": 42
  }],
  "units": {"sleepMinutes": "min", "sleepHeartRateMedian": "bpm", "hrvRmssdMedian": "ms"}
}"""

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun Prototype() {
    var route by remember { mutableStateOf("health") }
    var stack by remember { mutableStateOf(listOf<String>()) }
    fun go(next: String) { stack=stack+route; route=next }
    fun back() { route=stack.lastOrNull() ?: "food";stack=stack.dropLast(1) }
    var sheet by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(setOf("2 人均分")) }
    var note by remember { mutableStateOf("米饭只吃了 1/3，牛肉少油，不喝汤") }
    var foods by remember { mutableStateOf(ExampleFoods) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var saved by remember { mutableStateOf(false) }
    var savedFoods by remember { mutableStateOf(listOf<Food>()) }
    var favorites by remember { mutableStateOf(listOf(Food("原味酸奶", "1 杯 · 200 g", "126", "8", "16", "3.4"), Food("蛋白粉", "1 勺 · 30 g", "118", "24", "3", "1.2"))) }
    var services by remember { mutableStateOf(listOf(Service("日常视觉", "vision-model", true),Service("我的健康教练", "text-model", false))) }
    var foodService by remember { mutableStateOf("日常视觉") }
    var healthService by remember { mutableStateOf("我的健康教练") }
    var serviceTarget by remember { mutableStateOf("food") }
    var makeDefault by remember { mutableStateOf(false) }
    var defaultFood by remember { mutableStateOf("日常视觉") }
    var defaultHealth by remember { mutableStateOf("我的健康教练") }
    var serviceEdit by remember { mutableStateOf<Int?>(null) }
    var chatInput by remember { mutableStateOf("") }
    var chatQuestion by remember { mutableStateOf("") }
    var chatting by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    var reminder by remember { mutableStateOf(true) }
    var recovery by remember { mutableStateOf(true) }
    var sleepReminder by remember { mutableStateOf(true) }
    var dailyReminder by remember { mutableStateOf(false) }
    var exportMode by remember { mutableStateOf("摘要") }
    var exportRange by remember { mutableStateOf("最近 7 天") }
    var exportCategories by remember { mutableStateOf(setOf("睡眠", "心率", "HRV")) }
    var exportQuestion by remember { mutableStateOf(Questions.first()) }
    var exportPreview by remember { mutableStateOf("JSON") }
    var recordTab by remember { mutableStateOf("饮食") }
    var pickerTab by remember { mutableStateOf("常吃食物") }
    var search by remember { mutableStateOf("") }
    var selectedFavorites by remember { mutableStateOf(setOf<Int>()) }
    var analyzed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var detailMeal by remember { mutableStateOf(false) }
    val visualAvailable=services.any {it.name==foodService && it.image}
    val mealKcal=foods.sumOf {it.kcal.toDoubleOrNull() ?: 0.0}.toInt()
    val savedKcal=savedFoods.sumOf {it.kcal.toDoubleOrNull() ?: 0.0}.toInt()
    val pageScroll=rememberScrollState()
    LaunchedEffect(route) { pageScroll.scrollTo(0) }
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(notice) { if(notice.isNotEmpty()) {snackbar.showSnackbar(notice);notice=""} }
    LaunchedEffect(loading) {if(loading) {delay(1000);loading=false;analyzed=true;go("editor")} }
    LaunchedEffect(chatting) {if(chatting) {delay(1000);chatting=false;answered=true} }
    BackHandler(stack.isNotEmpty()) { back() }
    val root=route in listOf("weigh","food","health","records","settings")
    val titles=mapOf("weigh" to "称重", "food" to "饮食", "health" to "健康", "records" to "记录", "settings" to "设置", "capture" to "拍照记饮食", "describe" to "补充这餐的信息", "editor" to "确认这一餐", "picker" to "添加已记录食物", "saved" to "这餐已记录", "chat" to "健康教练", "report" to "本周健康观察", "export" to "导出给其他 AI", "exportPreview" to "导出预览", "services" to "AI 服务", "serviceEdit" to "编辑 AI 服务", "reminders" to "主动提醒", "data" to "健康数据")
    MaterialTheme(colorScheme=Palette) {
        Scaffold(containerColor=Palette.background,
            topBar={TopAppBar(title={Column {Text("称伴 · ${titles[route] ?: "预览"}",fontWeight=FontWeight.SemiBold,fontSize=20.sp);Text("AI 交互原型 · 全部为模拟数据",fontSize=11.sp,color=Palette.onSurfaceVariant)}},navigationIcon={if(!root)IconButton(onClick={back()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}},actions={Surface(color=Palette.primaryContainer,shape=CircleShape,modifier=Modifier.padding(end=16.dp)){Text("MOCK",Modifier.padding(horizontal=10.dp,vertical=5.dp),fontSize=10.sp,fontWeight=FontWeight.Bold,color=Green)}})},
            snackbarHost={SnackbarHost(snackbar)},
            bottomBar={
                if(root) NavigationBar(containerColor=Palette.surface) {
                    listOf(Triple("weigh","称重",Icons.Default.Home),Triple("food","饮食",Icons.Default.Add),Triple("health","健康",Icons.Default.Favorite),Triple("records","记录",Icons.AutoMirrored.Filled.List),Triple("settings","设置",Icons.Default.Settings)).forEach { (key,label,icon) ->
                        NavigationBarItem(selected=route==key,onClick={route=key;stack=emptyList()},icon={Icon(icon,label)},label={Text(label)})
                    }
                }
                if(route=="editor") BottomAction("确认保存 · $mealKcal kcal",foods.isNotEmpty()) {saved=true;savedFoods=foods;go("saved")}
                if(route=="describe") BottomAction(if(loading)"正在模拟分析…" else "开始分析") {if(!loading && visualAvailable)loading=true else if(!visualAvailable)notice="请先选择支持图片的服务，或返回手动录入"}
                if(route=="picker") BottomAction("添加 ${selectedFavorites.size} 项到这一餐",selectedFavorites.isNotEmpty()) {val source=if(pickerTab=="常吃食物")favorites else ExampleFoods;foods=foods+selectedFavorites.mapNotNull {source.getOrNull(it)};selectedFavorites=emptySet();back()}
                if(route=="chat") Surface(shadowElevation=4.dp) {Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal=22.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value=chatInput,onValueChange={chatInput=it},placeholder={Text("描述你的问题或补充感受…")},modifier=Modifier.fillMaxWidth(),minLines=2,shape=RoundedCornerShape(20.dp))
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={if(chatting){chatting=false;answered=false}else if(chatInput.isNotBlank()){chatQuestion=chatInput;chatInput="";chatting=true;answered=false}},Modifier.weight(1f)){Text(if(chatting)"停止生成" else "发送 · 模拟回答")};OutlinedButton(onClick={exportQuestion=chatInput.ifBlank {chatQuestion.ifBlank {Questions.first()}};go("export")}){Text("导出提问")}}
                }}
                if(route=="export") BottomAction("预览 JSON 与 Prompt") {go("exportPreview")}
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(pageScroll).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Spacer(Modifier.height(2.dp))
                when(route) {
                    "food" -> {
                        Eyebrow("今天 · 10 月 17 日，周六")
                        Text("每一餐，都算数。",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
                        Surface(shape=RoundedCornerShape(28.dp),color=Palette.primaryContainer) {Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                            Text("今日已记录摄入",color=Green)
                            Row(verticalAlignment=Alignment.Bottom) {Text(if(saved)(640+savedKcal).toString() else "640",fontSize=52.sp,fontWeight=FontWeight.Light);Text(" kcal",Modifier.padding(bottom=9.dp),color=Green)}
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("蛋白质",if(saved)"62 g" else "32 g");Metric("碳水",if(saved)"117 g" else "90 g");Metric("脂肪",if(saved)"41 g" else "16 g")}
                        }}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {Button(onClick={sheet="add"},Modifier.weight(1f).height(54.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text("记录食物")};OutlinedButton(onClick={foods=emptyList();analyzed=false;selectedFavorites=emptySet();go("editor");go("picker")},Modifier.height(54.dp)){Text("常吃食物")}}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){SectionTitle("今天的餐食");TextButton(onClick={notice="已标记今日记录完整（模拟）"}){Text("标记完整")}}
                        if(saved) MealCard("午餐", "12:30 · 刚刚记录", savedFoods.joinToString("、"){it.name}, "$savedKcal kcal",true){detailMeal=true;sheet="meal"}
                        MealCard("早餐", "08:45", "全麦面包、鸡蛋、牛奶", "420 kcal",true){detailMeal=false;sheet="meal"}
                        MealCard("上午加餐", "10:30", "拿铁、原味酸奶", "220 kcal",true){detailMeal=false;sheet="meal"}
                        Hint("这是已记录的部分，不代表全天实际摄入。")
                    }
                    "capture" -> {
                        Text("给这一餐拍张照",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
                        Text("尽量拍全食物，之后可以补充分食和实吃份量。",color=Palette.onSurfaceVariant)
                        Plate(Modifier.fillMaxWidth().height(310.dp))
                        Hint("模拟取景 · 不会打开相机或读取照片")
                        Primary("使用这张示例照片") {go("describe")}
                        Secondary("从相册选择示例") {go("describe")}
                    }
                    "describe" -> {
                        Plate(Modifier.fillMaxWidth().height(150.dp))
                        ServiceRow(foodService) {serviceTarget="food";sheet="service"}
                        SectionTitle("这餐怎么吃？")
                        Text("多选补充，让估算更贴近实际。",color=Palette.onSurfaceVariant)
                        Eyebrow("分食方式")
                        Chips(listOf("自己吃","2 人均分","3 人均分"),tags){tag -> tags=(tags-setOf("自己吃","2 人均分","3 人均分"))+tag}
                        Eyebrow("烹饪与实吃补充")
                        Chips(listOf("少油","少酱","不喝汤","只吃一半"),tags){tag->tags=if(tag in tags)tags-tag else tags+tag}
                        OutlinedTextField(value=note,onValueChange={note=it},label={Text("再补充一点")},placeholder={Text("例如：米饭只吃了 1/3")},modifier=Modifier.fillMaxWidth(),minLines=2,shape=RoundedCornerShape(18.dp))
                        Hint("标签和描述会一起交给所选 AI。份量不明确时，会在结果页让你确认。")
                        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "editor" -> {
                        if(analyzed) {
                            Surface(color=Palette.primaryContainer,shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("识别完成，核对后再保存",fontWeight=FontWeight.SemiBold);Text("2 人均分 · 少油 · 不喝汤",fontSize=13.sp);Text("份量与营养均为示例估算，可逐项修改。",fontSize=12.sp,color=Green)}}
                            if(!tags.contains("riceConfirmed")) CardBox {Text("米饭的 1/3，是哪一份？",fontWeight=FontWeight.SemiBold);Chips(listOf("照片这碗的 1/3","我分到那份的 1/3"),setOf(foods.find {it.name=="米饭"}?.portion ?: "")){choice->foods=foods.map {if(it.name=="米饭")it.copy(portion=choice,kcal=if(choice.startsWith("照片"))"78" else "39") else it};tags=tags+"riceConfirmed"};Text("后者会在均分后再取 1/3，不重复扣减。",fontSize=12.sp,color=Palette.onSurfaceVariant)}
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("午餐 · 今天 12:30",fontWeight=FontWeight.Medium);TextButton(onClick={sheet="mealTime"}){Text("修改")}}
                        foods.forEachIndexed { index, food ->
                            CardBox {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(food.name,fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text(food.portion,fontSize=12.sp,color=Palette.onSurfaceVariant)};Text("${food.kcal} kcal",color=Green,fontWeight=FontWeight.Bold);IconButton(onClick={editing=index}){Icon(Icons.Default.Edit,"修改 ${food.name}")}}
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("蛋白质","${food.protein} g");Metric("碳水","${food.carbs} g");Metric("脂肪","${food.fat} g")}
                                Row {TextButton(onClick={favorites=favorites+food;notice="已将${food.name}存为常吃（模拟）"}){Text("存为常吃")};TextButton(onClick={foods=foods.filterIndexed {i,_->i!=index}}){Text("移除",color=Palette.onSurfaceVariant)}}
                            }
                        }
                        if(foods.isEmpty())Hint("这一餐还没有食物，手动添加或从常吃食物选择。")
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={selectedFavorites=emptySet();go("picker")},Modifier.weight(1f)){Text("常吃 / 历史")};OutlinedButton(onClick={foods=foods+Food("新食物","1 份","0","0","0","0");editing=foods.lastIndex},Modifier.weight(1f)){Text("手动添加")}}
                        Hint("保存前不会写入。原型仅模拟本地保存与同步状态。")
                    }
                    "picker" -> {
                        Tabs(listOf("常吃食物","历史食物"),pickerTab){pickerTab=it;selectedFavorites=emptySet()}
                        OutlinedTextField(value=search,onValueChange={search=it},leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("搜索食物名称")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),singleLine=true)
                        Text("不用重拍，也不用再次调用 AI。",color=Palette.onSurfaceVariant)
                        val source=if(pickerTab=="常吃食物")favorites else ExampleFoods
                        source.forEachIndexed {i,food->if(search.isBlank()||food.name.contains(search))CardBox {
                            Row(Modifier.fillMaxWidth().clickable {selectedFavorites=if(i in selectedFavorites)selectedFavorites-i else selectedFavorites+i},verticalAlignment=Alignment.CenterVertically){Checkbox(i in selectedFavorites,{selectedFavorites=if(it)selectedFavorites+i else selectedFavorites-i});Column(Modifier.weight(1f)){Text(food.name,fontWeight=FontWeight.SemiBold);Text("${food.portion} · ${food.kcal} kcal",fontSize=12.sp,color=Palette.onSurfaceVariant)};Text("+",fontSize=24.sp,color=Green)}
                        }}
                        Hint("添加后可调整本次份量，不改变历史记录或常吃模板。")
                    }
                    "saved" -> {
                        Spacer(Modifier.height(28.dp));Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Surface(shape=CircleShape,color=Palette.primaryContainer){Icon(Icons.Default.Check,null,Modifier.padding(24.dp).size(40.dp),tint=Green)}}
                        Text("好好吃饭，也好好记录。",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
                        CardBox {Text("午餐 · 今天 12:30",color=Palette.onSurfaceVariant);Text("$savedKcal kcal",fontSize=42.sp,fontWeight=FontWeight.Light);Text(savedFoods.joinToString("、"){it.name});HorizontalDivider();LabelValue("本地记录","已保存（模拟）");LabelValue("Health Connect","已写入（模拟）")}
                        Hint("这是界面演示，没有执行真实健康写入。")
                        Primary("回到饮食") {route="food";stack=emptyList()}
                        Secondary("把这餐的第一项存为常吃") {savedFoods.firstOrNull()?.let {favorites=favorites+it};notice="已存为常吃（模拟）"}
                    }
                    "health" -> {
                        Eyebrow("今天的你 · 数据截至 10:15（模拟）")
                        Text("看见变化，慢慢调整。",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
                        CardBox(color=Palette.primaryContainer) {Eyebrow("本周值得关注");Text("作息规律，比偶尔睡很久更重要。",fontSize=23.sp,fontWeight=FontWeight.Medium);Text("近 7 晚平均睡眠 7 小时 30 分，有 2 晚低于你的目标。",color=Green);TextButton(onClick={go("report")}){Text("查看本周观察  →")}}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){SmallStat("昨夜睡眠","7 h 30 m",Modifier.weight(1f));SmallStat("夜间心率","60 bpm",Modifier.weight(1f))}
                        CardBox {LabelValue("HRV · RMSSD","42 ms");Text("与你个人近期范围比较，不与别人排名。",fontSize=12.sp,color=Palette.onSurfaceVariant)}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){SectionTitle("想了解什么？");TextButton(onClick={go("chat")}){Text("自由提问")}}
                        Questions.take(3).forEach {q->ActionRow(q,"结合已授权的数据",Icons.Default.Favorite){chatInput=q;go("chat")}}
                        Secondary("导出给其他 AI") {go("export")}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={go("data")}){Text("数据与来源")};TextButton(onClick={go("reminders")}){Text("主动提醒")}}
                    }
                    "report" -> {
                        Eyebrow("10 月 10 日 — 10 月 16 日 · 模拟周报")
                        Text("先稳定作息，再观察恢复。",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
                        CardBox {LabelValue("有效睡眠","7 / 7 晚");LabelValue("夜间心率覆盖","96%");LabelValue("HRV 可用","6 / 7 晚");Text("来自示例手表应用 · 所有值均为合成数据",fontSize=12.sp,color=Palette.onSurfaceVariant)}
                        SectionTitle("01  睡眠时长足够，时间有波动")
                        Text("平均 7 小时 30 分。入睡在 00:40—02:20 之间，起床时间相对固定。可以先试着提前 20 分钟准备入睡。")
                        Trend()
                        SectionTitle("02  心率与 HRV 没有持续同向变化")
                        Text("夜间心率中位数约 60 bpm，HRV 中位数 42 ms。当前示例不足以判断疲劳原因，也不作为疾病诊断。")
                        Hint("事实与建议分开呈现。食物记录尚不完整，本报告不判断实际摄入是否达标。")
                        Primary("继续问这份报告") {chatInput="帮我具体安排一下如何稳定作息";go("chat")}
                        Secondary("带着数据去其他 AI") {go("export")}
                    }
                    "chat" -> {
                        ServiceRow(healthService){serviceTarget="health";sheet="service"}
                        Hint("正在使用：最近 7 天睡眠、心率与 HRV 摘要（模拟）")
                        if(chatQuestion.isBlank()) {
                            Text("想从哪里开始？",fontSize=27.sp,fontWeight=FontWeight.SemiBold)
                            Text("聊聊身体的变化，也聊聊你的日常。",color=Palette.onSurfaceVariant)
                            Questions.forEach {q->Suggestion(q){chatInput=q}}
                        } else {
                            Surface(color=Palette.primaryContainer,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()){Text(chatQuestion,Modifier.padding(18.dp))}
                            if(chatting) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在生成示例回答…",color=Palette.onSurfaceVariant)}
                            if(answered) CardBox {Text("先看数据，再看你的感受。",fontSize=20.sp,fontWeight=FontWeight.SemiBold);Text("示例数据显示，最近 7 晚睡眠平均 7 小时 30 分，主要波动在入睡时间。夜间心率没有持续升高。\n\n可以先选一个容易坚持的睡前时间，连续观察一周。若你觉得累，也可以补充最近的加班、运动或身体感受。");TextButton(onClick={go("report")}){Text("查看依据 · 示例周报")};Hint("预置示例回答，未调用 AI；原型不针对你的真实健康状况给建议。")}
                        }

                    }
                    "export" -> {
                        Eyebrow("你的数据，你选择 AI")
                        Text("带着数据，去任何地方。",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
                        Text("不需要 API Key。把 JSON 与提问一起交给你信任的 AI。",color=Palette.onSurfaceVariant)
                        SectionTitle("时间范围")
                        Chips(listOf("最近 7 天","最近 28 天"),setOf(exportRange)){exportRange=it}
                        SectionTitle("导出内容")
                        Chips(listOf("摘要","原始明细"),setOf(exportMode)){exportMode=it}
                        Hint(if(exportMode=="摘要")"按日整理指标，适合直接让 AI 分析。示例大小 12 KB。" else "包含睡眠阶段与逐点心率等。示例大小 8.4 MB，可分片。")
                        SectionTitle("数据类别")
                        Chips(listOf("睡眠","心率","HRV","运动","步数","体重","饮食"),exportCategories){exportCategories=if(it in exportCategories)exportCategories-it else exportCategories+it}
                        OutlinedTextField(value=exportQuestion,onValueChange={exportQuestion=it},label={Text("想问其他 AI 的问题")},modifier=Modifier.fillMaxWidth(),minLines=2,shape=RoundedCornerShape(18.dp))
                        Hint("预览不调用 AI。本原型只展示合成数据，不导出手机真实记录。")
                    }
                    "exportPreview" -> {
                        CardBox {LabelValue("时间",exportRange);LabelValue("模式",exportMode);LabelValue("类别",exportCategories.joinToString("、"));Text("可读性优先 · 标准 JSON + 纯文本 Prompt",fontSize=12.sp,color=Palette.onSurfaceVariant)}
                        Tabs(listOf("JSON","Prompt"),exportPreview){exportPreview=it}
                        Surface(color=Color(0xFF23372C),shape=RoundedCornerShape(20.dp)){Text(if(exportPreview=="JSON")DemoJson else "请分析随附的健康 JSON，回答：$exportQuestion\n\n这些是称伴原型合成数据，仅用于演示。时区 Asia/Shanghai。\n请先核对单位、缺失与覆盖范围。区分事实、可能解释和可执行建议，不补造缺失、不诊断疾病。",Modifier.fillMaxWidth().padding(20.dp),color=Color(0xFFE0EDE2),fontSize=13.sp)}
                        Primary("保存 JSON + Prompt（模拟）") {notice="示例文件已准备：health-mock.json + prompt.txt（未写出真实文件）"}
                        Secondary("复制 Prompt（模拟）") {notice="已模拟复制 Prompt；没有读取真实数据"}
                        Secondary("分享给其他 AI（模拟）") {notice="此处将打开系统分享，本原型不发送文件"}
                    }
                    "settings" -> {
                        SectionTitle("让称伴按你的习惯工作")
                        ActionRow("AI 服务","${services.size} 个服务 · 按能力选择",Icons.Default.Settings){go("services")}
                        ActionRow("主动提醒","频率、主题与安静时段",Icons.Default.Favorite){go("reminders")}
                        ActionRow("健康数据","权限、来源与完整度",Icons.AutoMirrored.Filled.List){go("data")}
                        ActionRow("导出给其他 AI","不需要 Key，也不绑定模型",Icons.Default.Share){go("export")}
                        CardBox {Text("关于这个原型",fontWeight=FontWeight.SemiBold);Text("仅预览交互；不使用网络、蓝牙、相机或 Health Connect。所有修改只保存在内存，重启后重置。",color=Palette.onSurfaceVariant)}
                    }
                    "services" -> {
                        Text("AI 自己选，数据自己管。",fontSize=27.sp,fontWeight=FontWeight.SemiBold)
                        Text("同一服务的不同模型可以分别保存。原型不会存储真实密钥或测试网络。",color=Palette.onSurfaceVariant)
                        services.forEachIndexed {i,s->CardBox {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text(s.name,fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text(s.model,fontSize=12.sp,color=Palette.onSurfaceVariant)};IconButton(onClick={serviceEdit=i;go("serviceEdit")}){Icon(Icons.Default.Edit,"编辑服务")}};Text(if(s.image)"文本 + 图片" else "仅文本",color=Green,fontSize=13.sp);Text("API Key  •••••••• · 模拟配置",fontSize=12.sp,color=Palette.onSurfaceVariant)}}
                        Secondary("+ 添加 AI 服务") {serviceEdit=null;go("serviceEdit")}
                        SectionTitle("按能力设置默认服务")
                        ActionRow("食物图片识别",defaultFood,Icons.Default.Add){serviceTarget="food";makeDefault=true;sheet="service"}
                        ActionRow("健康分析与对话",defaultHealth,Icons.Default.Favorite){serviceTarget="health";makeDefault=true;sheet="service"}
                        Hint("只有文本模型？你仍可以手动记餐、用常吃食物、分析健康摘要。")
                    }
                    "serviceEdit" -> {
                        val original=serviceEdit?.let {services.getOrNull(it)}
                        var serviceName by remember(route,serviceEdit){mutableStateOf(original?.name ?: "")}
                        var model by remember(route,serviceEdit){mutableStateOf(original?.model ?: "")}
                        var url by remember(route,serviceEdit){mutableStateOf("https://api.example.com/v1")}
                        var key by remember(route,serviceEdit){mutableStateOf("")}
                        var images by remember(route,serviceEdit){mutableStateOf(original?.image ?: false)}
                        OutlinedTextField(serviceName,{serviceName=it},label={Text("服务名称")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(url,{url=it},label={Text("Base URL · 兼容接口")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(model,{model=it},label={Text("模型 ID")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(key,{key=it},label={Text("API Key（请勿输入真实密钥）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                        CardBox {SwitchRow("模型支持图片","请按实际模型能力勾选",images){images=it}}
                        Secondary("测试连接（模拟）") {notice="模拟测试成功，未发送任何网络请求"}
                        Primary("保存模拟配置") {val added=Service(serviceName.ifBlank {"新服务"},model.ifBlank {"example-model"},images);val ix=serviceEdit;if(ix==null)services=services+added else {val old=services[ix].name;services=services.mapIndexed {i,s->if(i==ix)added else s};if(foodService==old)foodService=added.name;if(healthService==old)healthService=added.name;if(defaultFood==old)defaultFood=added.name;if(defaultHealth==old)defaultHealth=added.name};back()}
                        if(serviceEdit!=null)TextButton(onClick={val old=services[serviceEdit!!].name;services=services.filterIndexed {i,_->i!=serviceEdit};if(defaultFood==old)defaultFood="未设置";if(defaultHealth==old)defaultHealth="未设置";if(foodService==old)foodService="未选择";if(healthService==old)healthService="未选择";back()}){Text("删除这个服务",color=MaterialTheme.colorScheme.error)}
                    }
                    "reminders" -> {
                        Text("值得关注时，再提醒。",fontSize=27.sp,fontWeight=FontWeight.SemiBold)
                        Text("没有明显变化时保持安静。你可以随时暂停。",color=Palette.onSurfaceVariant)
                        CardBox {SwitchRow("开启主动提醒","每日最多 1 条 · 同主题 72 小时去重",reminder){reminder=it}}
                        CardBox {SwitchRow("睡眠目标","连续多晚不足目标时提醒",sleepReminder){sleepReminder=it};HorizontalDivider();SwitchRow("恢复相关变化","结合个人基线、HRV 与夜间心率",recovery){recovery=it};HorizontalDivider();SwitchRow("睡前准备","你主动选择的时间提醒",dailyReminder){dailyReminder=it}}
                        CardBox {LabelValue("每日检查","10:30");LabelValue("安静时段","22:00 — 09:00");LabelValue("锁屏内容","隐藏健康细节")}
                        Secondary("预览一条提醒") {sheet="reminder"}
                        Hint("模拟后台读取可用。正式版会按系统支持与用户权限降级；原型不注册后台任务。")
                    }
                    "data" -> {
                        Text("先了解数据，再理解变化。",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
                        Hint("以下全部为合成示例，不是你手机里的实测数据。")
                        listOf("睡眠" to "7 / 7 晚 · 阶段完整","心率" to "96% 夜间分钟覆盖","HRV" to "6 / 7 晚 · RMSSD","运动" to "3 次 · 示例手表应用","步数" to "7 个完整日 · 已聚合","饮食" to "记录未标记完整").forEach {(name,desc)->ActionRow(name,desc,Icons.Default.CheckCircle){notice="$name：模拟已授权 · 来源为示例应用"}}
                        Secondary("刷新数据（模拟）") {notice="模拟刷新完成，没有调用 Health Connect"}
                        Secondary("导出 JSON 与 Prompt") {go("export")}
                    }
                    "records" -> {
                        Tabs(listOf("饮食","体重"),recordTab){recordTab=it}
                        Eyebrow("今天 · 10 月 17 日")
                        if(recordTab=="饮食") {MealCard("午餐","12:30","水煮牛肉、米饭","458 kcal",true){detailMeal=true;sheet="meal"};MealCard("早餐","08:45","面包、鸡蛋、牛奶","420 kcal",true){detailMeal=false;sheet="meal"}}
                        else {CardBox {LabelValue("71.20 kg","08:20");Text("家里的秤 · 已写入（模拟）",color=Palette.onSurfaceVariant)};CardBox {LabelValue("71.35 kg","昨天 08:15");Text("家里的秤 · 已写入（模拟）",color=Palette.onSurfaceVariant)}}
                    }
                    "weigh" -> {
                        Eyebrow("熟悉的称重体验")
                        CardBox(color=Palette.primaryContainer){Text("家里的秤",color=Green);Text("71.20",fontSize=72.sp,fontWeight=FontWeight.Light);Text("kg · 今日示例",color=Green);Text("上秤后自动接收，稳定后记录。",Modifier.padding(top=16.dp))}
                        Primary("模拟一次称重") {notice="示例体重已记录；没有连接蓝牙或写入 Health Connect"}
                        Hint("原有称重功能继续独立使用；这里仅保留视觉占位。")
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
        if(sheet.isNotEmpty()) ModalBottomSheet(onDismissRequest={sheet=""}) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                when(sheet) {
                    "add" -> {
                        SectionTitle("怎么记录这一餐？")
                        ActionRow("拍照识别",if(visualAvailable)"示例照片 + 标签与补充描述" else "当前无视觉服务，可先配置或手动录入",Icons.Default.Add){if(visualAvailable){sheet="";foods=ExampleFoods;analyzed=false;tags=setOf("2 人均分");go("capture")}else{sheet="";go("services")}}
                        ActionRow("从相册选择",if(visualAvailable)"使用一张示例食物图片" else "需要支持图片的 AI 服务",Icons.Default.Face){sheet="";if(visualAvailable){foods=ExampleFoods;analyzed=false;go("describe")}else go("services")}
                        ActionRow("常吃食物 / 历史","直接复用，无需 AI",Icons.Default.Favorite){sheet="";foods=emptyList();analyzed=false;selectedFavorites=emptySet();go("editor");go("picker")}
                        ActionRow("手动录入","没有视觉模型，也可以记录",Icons.Default.Edit){sheet="";analyzed=false;foods=listOf(Food("新食物","1 份","0","0","0","0"));go("editor");editing=0}
                    }
                    "service" -> {
                        SectionTitle(if(serviceTarget=="food")"选择食物视觉服务" else "选择健康分析服务")
                        Text("仅用于本次，勾选后才会更改默认。",color=Palette.onSurfaceVariant)
                        services.forEach {s->val allowed=serviceTarget!="food"||s.image;CardBox {Row(Modifier.fillMaxWidth().clickable(enabled=allowed){if(serviceTarget=="food") {foodService=s.name;if(makeDefault)defaultFood=s.name}else {healthService=s.name;if(makeDefault)defaultHealth=s.name};makeDefault=false;sheet=""},verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(s.name,fontWeight=FontWeight.SemiBold,color=if(allowed)Palette.onSurface else Palette.onSurfaceVariant);Text(if(allowed)s.model else "不支持图片 · 可用于健康分析",fontSize=12.sp,color=Palette.onSurfaceVariant)};if(allowed)Icon(Icons.Default.Check,null,tint=Green)}}}
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(makeDefault,{makeDefault=it});Text("设为此能力的默认服务")}
                        Secondary("管理 AI 服务") {sheet="";go("services")}
                    }
                    "meal" -> {
                        SectionTitle(if(detailMeal)"午餐 · 水煮牛肉、米饭" else "早餐 · 面包、鸡蛋、牛奶")
                        Text(if(detailMeal)"458 kcal" else "420 kcal",fontSize=38.sp,fontWeight=FontWeight.Light)
                        Text("已写入 Health Connect（模拟）",color=Green)
                        Primary("再吃一次") {sheet="";foods=ExampleFoods;analyzed=false;go("editor")}
                        Secondary("编辑这一餐") {sheet="";foods=ExampleFoods;analyzed=false;go("editor")}
                        TextButton(onClick={sheet="";if(detailMeal){saved=false;savedFoods=emptyList()};notice="已模拟删除这条餐食"}){Text("删除记录",color=MaterialTheme.colorScheme.error)}
                    }
                    "mealTime" -> {SectionTitle("餐别与时间");Chips(listOf("早餐","午餐","晚餐","加餐"),setOf("午餐")){notice="已选择$it（模拟）"};Primary("今天 12:30 · 确认") {sheet=""}}
                    "reminder" -> {SectionTitle("称伴有一条健康建议");Text("最近几晚的入睡时间有些波动，今晚要不要早点准备休息？",fontSize=20.sp);Text("这是一条合成示例，不根据你的实际健康数据触发。",color=Palette.onSurfaceVariant);Primary("查看依据与建议") {sheet="";go("report")};Secondary("减少这类提醒") {sheet="";notice="已模拟反馈：减少这类提醒"}}
                }
            }
        }
        editing?.let {ix->foods.getOrNull(ix)?.let {original->
            var item by remember(ix){mutableStateOf(original)}
            AlertDialog(onDismissRequest={editing=null},title={Text("修改食物")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                OutlinedTextField(item.name,{item=item.copy(name=it)},label={Text("食物名称")})
                OutlinedTextField(item.portion,{item=item.copy(portion=it)},label={Text("本次实际吃下的份量")})
                listOf("热量 kcal" to item.kcal,"蛋白质 g" to item.protein,"碳水 g" to item.carbs,"脂肪 g" to item.fat).forEachIndexed {i,(label,value)->OutlinedTextField(value,{v->item=when(i){0->item.copy(kcal=v);1->item.copy(protein=v);2->item.copy(carbs=v);else->item.copy(fat=v)}},label={Text(label)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))}
                Text("数值仅用于原型展示。",fontSize=12.sp,color=Palette.onSurfaceVariant)
            }},confirmButton={TextButton(onClick={foods=foods.mapIndexed {i,f->if(i==ix)item else f};editing=null}){Text("保存修改")}},dismissButton={TextButton(onClick={editing=null}){Text("取消")}})
        }}
    }
}

@Composable private fun Eyebrow(text:String){Text(text,fontSize=12.sp,fontWeight=FontWeight.Medium,color=Green,letterSpacing=.5.sp)}
@Composable private fun SectionTitle(text:String){Text(text,fontSize=19.sp,fontWeight=FontWeight.SemiBold)}
@Composable private fun Hint(text:String){Text(text,fontSize=12.sp,lineHeight=19.sp,color=Palette.onSurfaceVariant)}
@Composable private fun Metric(label:String,value:String){Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Text(label,fontSize=11.sp,color=Palette.onSurfaceVariant);Text(value,fontSize=17.sp,fontWeight=FontWeight.Medium)}}
@Composable private fun Primary(text:String,action:()->Unit){Button(onClick=action,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(18.dp)){Text(text)}}
@Composable private fun Secondary(text:String,action:()->Unit){OutlinedButton(onClick=action,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp),shape=RoundedCornerShape(18.dp)){Text(text)}}
@Composable private fun BottomAction(text:String,enabled:Boolean=true,action:()->Unit){Surface(shadowElevation=4.dp){Box(Modifier.navigationBarsPadding().padding(horizontal=22.dp,vertical=12.dp)){Button(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(18.dp)){Text(text)}}}}
@Composable private fun CardBox(color:Color=Color.White,content:@Composable ColumnScope.()->Unit){Surface(color=color,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
@Composable private fun LabelValue(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=Palette.onSurfaceVariant,modifier=Modifier.weight(1f));Text(value,fontWeight=FontWeight.Medium)}}
@Composable private fun SmallStat(label:String,value:String,modifier:Modifier){Surface(modifier=modifier,color=Color.White,shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(label,color=Palette.onSurfaceVariant,fontSize=12.sp);Text(value,fontSize=23.sp,fontWeight=FontWeight.Medium)}}}
@Composable private fun MealCard(title:String,time:String,foods:String,kcal:String,synced:Boolean,action:()->Unit){Surface(onClick=action,color=Color.White,shape=RoundedCornerShape(22.dp)){Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){Surface(shape=RoundedCornerShape(16.dp),color=Palette.secondaryContainer){Text(if(title=="早餐")"早" else if(title=="午餐")"午" else "食",Modifier.padding(15.dp),fontSize=20.sp,color=Green)};Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("$title · $time",fontSize=12.sp,color=Palette.onSurfaceVariant);Text(foods,fontWeight=FontWeight.Medium);Text(if(synced)"已写入 · 模拟" else "待写入",fontSize=11.sp,color=Green)};Text(kcal,fontSize=13.sp,fontWeight=FontWeight.Medium)}}}
@Composable private fun ActionRow(title:String,subtitle:String,icon:ImageVector,action:()->Unit){Surface(onClick=action,color=Color.White,shape=RoundedCornerShape(20.dp)){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){Icon(icon,null,tint=Green,modifier=Modifier.size(21.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontWeight=FontWeight.Medium);Text(subtitle,fontSize=12.sp,color=Palette.onSurfaceVariant)};Text("›",fontSize=24.sp,color=Palette.onSurfaceVariant)}}}
@Composable private fun ServiceRow(name:String,action:()->Unit){Surface(onClick=action,color=Palette.surfaceContainer,shape=RoundedCornerShape(16.dp)){Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("AI · $name",fontSize=13.sp,color=Green);Text("切换 ›",fontSize=13.sp,color=Green)}}}
@Composable private fun Tabs(options:List<String>,selected:String,action:(String)->Unit){Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surfaceContainer).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){options.forEach {text->Surface(onClick={action(text)},color=if(text==selected)Color.White else Color.Transparent,shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Box(Modifier.padding(vertical=12.dp),contentAlignment=Alignment.Center){Text(text,fontSize=14.sp,fontWeight=if(text==selected)FontWeight.SemiBold else FontWeight.Normal,color=if(text==selected)Green else Palette.onSurfaceVariant)}}}}}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Chips(options:List<String>,selected:Set<String>,action:(String)->Unit){FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){options.forEach {text->FilterChip(selected=text in selected,onClick={action(text)},label={Text(text,fontSize=13.sp)})}}}
@Composable private fun Suggestion(text:String,action:()->Unit){Surface(onClick=action,color=Color.White,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){Text(text,Modifier.padding(16.dp),fontSize=14.sp)}}
@Composable private fun SwitchRow(title:String,subtitle:String,checked:Boolean,action:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,fontWeight=FontWeight.Medium);Text(subtitle,fontSize=12.sp,color=Palette.onSurfaceVariant)};Switch(checked,action)}}
@Composable private fun Plate(modifier:Modifier){Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFFEDE5D5)),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize()){
    val c=Offset(size.width*.5f,size.height*.49f);val r=minOf(size.width*.32f,size.height*.43f)
    drawCircle(Color(0xFFD9CFBB),r*1.07f,c+Offset(3f,9f));drawCircle(Color(0xFFFCFAF1),r,c);drawCircle(Color(0xFFDF9B65),r*.79f,c)
    for(i in 0..11){val x=c.x+kotlin.math.cos(i*2.4f)*r*.55f;val y=c.y+kotlin.math.sin(i*2.4f)*r*.5f;drawOval(Color(0xFF88583D),Offset(x-r*.18f,y-r*.1f),androidx.compose.ui.geometry.Size(r*.4f,r*.21f))}
    for(i in 0..8){val x=c.x+kotlin.math.cos(i*1.8f)*r*.66f;val y=c.y+kotlin.math.sin(i*1.8f)*r*.62f;drawCircle(Color(0xFF527750),r*.09f,Offset(x,y))}
};Surface(color=Color(0xFFFCFAF1).copy(alpha=.9f),shape=CircleShape,modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp)){Text("示例餐食 · 插画占位",Modifier.padding(horizontal=12.dp,vertical=6.dp),fontSize=10.sp,color=Green)}}}
@Composable private fun Trend(){val values=listOf(.55f,.4f,.72f,.83f,.65f,.9f,.7f);Row(Modifier.fillMaxWidth().height(100.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Bottom){values.forEachIndexed {i,v->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.BottomCenter){Box(Modifier.fillMaxWidth().fillMaxHeight(v).clip(RoundedCornerShape(topStart=7.dp,topEnd=7.dp)).background(if(i==6)Green else Palette.primaryContainer))};Text("${10+i}",Modifier.padding(top=5.dp),fontSize=10.sp,color=Palette.onSurfaceVariant)}}}}
