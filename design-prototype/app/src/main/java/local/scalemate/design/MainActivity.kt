package local.scalemate.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = intent.getStringExtra("screen") ?: "welcome"
        val screenshot = intent.getBooleanExtra("capture", false)
        setContent { ScaleMate(initial, screenshot) }
    }
}

private val Green = Color(0xFF376A52)
private val Light = lightColorScheme(
    primary = Green, onPrimary = Color.White, primaryContainer = Color(0xFFCEEBDA), onPrimaryContainer = Color(0xFF123B29),
    secondary = Color(0xFF52665A), secondaryContainer = Color(0xFFD6E8DA), onSecondaryContainer = Color(0xFF20352A),
    background = Color(0xFFF7F9F5), onBackground = Color(0xFF19221D),
    surface = Color(0xFFF7F9F5), onSurface = Color(0xFF19221D), surfaceVariant = Color(0xFFE2E9E1),
    onSurfaceVariant = Color(0xFF526158), outline = Color(0xFF738178), outlineVariant = Color(0xFFD8E1D8)
)
private val Dark = darkColorScheme(primary = Color(0xFFA2D2B5), primaryContainer = Color(0xFF214F38),
    background = Color(0xFF111914), surface = Color(0xFF111914), surfaceVariant = Color(0xFF26372C))
private data class Entry(val value: String, val time: String, val synced: Boolean = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScaleMate(initial: String, capture: Boolean) {
    var page by rememberSaveable { mutableStateOf(initial) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var auto by rememberSaveable { mutableStateOf(true) }
    var phase by rememberSaveable { mutableStateOf(if (initial == "countdown") "countdown" else if (initial == "success") "success" else "idle") }
    var seconds by rememberSaveable { mutableIntStateOf(5) }
    var reading by rememberSaveable { mutableStateOf("—") }
    var bound by rememberSaveable { mutableStateOf(initial != "welcome" && initial != "setup" && initial != "devices") }
    var device by rememberSaveable { mutableStateOf("家里的秤") }
    var found by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<Entry?>(null) }
    var healthReady by rememberSaveable { mutableStateOf(false) }
    var writeAllowed by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && phase in listOf("measuring", "countdown", "stable")) {
                phase = "cancelled"; reading = "—"
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    val records = remember { mutableStateListOf(Entry("71.30", "今天 · 08:32"), Entry("71.55", "昨天 · 08:16"), Entry("71.80", "10 月 9 日 · 08:40", false), Entry("71.65", "10 月 8 日 · 08:25")) }
    LaunchedEffect(phase, page) {
        if (capture) return@LaunchedEffect
        if (phase == "measuring" && page in listOf("home", "countdown")) {
            for (n in listOf("69.85", "70.90", "71.45", "71.35", "71.30")) { reading = n; delay(650) }
            delay(1400); seconds = 5; phase = if (auto) "countdown" else "stable"
        } else if (phase == "countdown" && page in listOf("home", "countdown")) {
            seconds = 5
            while (seconds > 0) { delay(1000); seconds-- }
            records.add(0, Entry("71.30", "今天 · 刚刚")); phase = "success"
        }
    }
    LaunchedEffect(scanning) { if (scanning) { delay(1700); found = true; scanning = false } }
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        val c = MaterialTheme.colorScheme
        val main = page in listOf("home", "countdown", "success", "records", "settings")
        Scaffold(
            topBar = {
                if (page != "welcome") TopAppBar(
                    title = { Column { Text(when(page) { "setup" -> "连接健康应用"; "devices" -> "我的体重秤"; "records" -> "称重记录"; "settings" -> "设置"; else -> "ScaleMate" }, fontWeight = FontWeight.SemiBold)
                        Text("交互视觉稿 · 模拟数据", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) } },
                    navigationIcon = { if (!main) IconButton(onClick = { page = if (page == "setup") "welcome" else "settings" }) { Icon(Icons.Default.ArrowBack, "返回") } },
                    actions = { if (main) IconButton(onClick = { dark = !dark }) { Icon(Icons.Default.Settings, "切换深浅主题") } }
                )
            },
            bottomBar = {
                if (main) NavigationBar(containerColor = c.surface) {
                    listOf(Triple("home", "称重", Icons.Default.Home), Triple("records", "记录", Icons.Default.List), Triple("settings", "设置", Icons.Default.Settings)).forEach { (key, label, icon) ->
                        NavigationBarItem(selected = if (key == "home") page in listOf("home", "countdown", "success") else page == key,
                            onClick = { if (phase in listOf("countdown", "measuring", "stable") && key != "home") phase = "cancelled"; page = key },
                            icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (page) {
                    "welcome" -> {
                        Spacer(Modifier.height(32.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { ScaleMark(40); Spacer(Modifier.width(12.dp)); Text("ScaleMate", fontSize = 25.sp, fontWeight = FontWeight.Bold) }
                        Text("交互视觉稿 · 所有数据均为模拟", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(40.dp), color = c.primaryContainer, modifier = Modifier.fillMaxWidth().height(205.dp)) {
                            Box(contentAlignment = Alignment.Center) { ScaleDrawing() }
                        }
                        Text("每一次称重，\n都有去处。", fontSize = 37.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
                        Text("连接你的体重秤，让体重记录自然汇入你常用的健康应用。", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("云麦 M263"); Pill("Health Connect") }
                        Feature(Icons.Default.Check, "站稳，然后交给 ScaleMate", "体重稳定后倒计时 5 秒，随时可以取消。")
                        Button(onClick = { page = "setup" }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("开始设置", fontSize = 16.sp) }
                        TextButton(onClick = { page = "home"; bound = true }) { Text("先看看称重体验") }
                    }
                    "setup" -> {
                        Text("准备好，\n让数据连起来。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        Text("首次设置只需三步。ScaleMate 无需注册账号。", color = c.onSurfaceVariant)
                        Step("1", "准备健康应用", "使用 Google Fit 或 Google Health。", if (healthReady) "✓ 已准备" else "标记为已准备", { healthReady = !healthReady })
                        Step("2", "允许写入体重", "通过 Health Connect 保存到本机。", if (writeAllowed) "✓ 模拟授权完成" else "模拟授权", { writeAllowed = !writeAllowed })
                        Step("3", "开启体重读取", "在 Google 健康应用中允许读取体重。", "查看说明", { dialog = true })
                        Surface(color = c.secondaryContainer, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("5 秒，留给你确认", fontWeight = FontWeight.SemiBold)
                            Text("自动确认仅在称重页前台运行。请确保站上秤的是你本人；取消后，本次不会保存。", style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("开启 5 秒自动确认", Modifier.weight(1f)); Switch(checked = auto, onCheckedChange = { auto = it }) }
                        } }
                        Button(onClick = { page = "devices"; found = false }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("继续 · 添加体重秤") }
                    }
                    "devices" -> {
                        Text(if (bound) "你的称重伙伴" else "找到你的秤", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text("轻踩唤醒体重秤，并让手机靠近。\n首版支持云麦 YMBS-M263。", color = c.onSurfaceVariant, lineHeight = 24.sp)
                        if (bound) DeviceCard(device, "云麦 YMBS-M263 · 已绑定", true) { page = "home" }
                        if (found) {
                            Text("附近发现", style = MaterialTheme.typography.labelLarge, color = c.primary)
                            DeviceCard("云麦 YMBS-M263", "信号良好 · 设备 …8E:CA", false) { dialog = true }
                            Text("广播型秤无需系统配对。确认型号后即可添加。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在寻找附近的体重秤…", color = c.onSurfaceVariant) }
                        OutlinedButton(onClick = { scanning = true; found = false }, enabled = !scanning, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(if (bound) "添加另一台秤" else "扫描附近体重秤") }
                        Feature(Icons.Default.Info, "只添加支持的秤", "我们会检查设备协议，避免把其他蓝牙设备识别成体重秤。")
                    }
                    "home", "countdown", "success" -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { Text("今天，也照顾好自己", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium); Text("准备好称重了吗？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                        }
                        DeviceCard(device, "云麦 YMBS-M263 · 点击切换设备", true) { dialog = true }
                        val active = phase in listOf("countdown", "stable", "success")
                        Surface(shape = RoundedCornerShape(32.dp), color = if (active) c.primaryContainer else c.surfaceVariant.copy(alpha = .55f), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Pill(when(phase) { "measuring" -> "正在测量"; "countdown", "stable" -> "体重已稳定"; "success" -> "本次称重已完成"; "cancelled" -> "本次已取消"; else -> "等待上秤" })
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(if (active) "71.30" else reading, fontSize = 68.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp)
                                    Text("kg", modifier = Modifier.padding(start = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge, color = c.onSurfaceVariant)
                                }
                                Text(when(phase) { "measuring" -> "请站稳，体重正在变化"; "countdown", "stable" -> "保持站稳，确认这次体重"; "success" -> "已保存 · 来源：$device"; "cancelled" -> "没有保存，也没有写入健康平台"; else -> "点击开始，然后站上体重秤" }, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                if (phase == "countdown") {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(progress = { seconds / 5f }, modifier = Modifier.size(52.dp), strokeWidth = 4.dp); Text("$seconds", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                                        Column { Text("$seconds 秒后自动保存", fontWeight = FontWeight.SemiBold); Text("不想记录？现在可以取消", style = MaterialTheme.typography.bodySmall) }
                                    }
                                    OutlinedButton(onClick = { phase = "cancelled"; reading = "—" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("取消本次称重") }
                                } else if (phase == "success") {
                                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Check, null, tint = c.primary); Spacer(Modifier.width(8.dp)); Text("已写入 Health Connect", fontWeight = FontWeight.SemiBold) }
                                    Text("目标应用的显示可能稍有延迟", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                    Button(onClick = { phase = "idle"; reading = "—" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("开始下一次") }
                                } else if (phase == "measuring") {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    TextButton(onClick = { phase = "cancelled"; reading = "—" }) { Text("停止称重") }
                                } else {
                                    Button(onClick = { if (phase == "stable") { phase = "success"; records.add(0, Entry("71.30", "今天 · 刚刚")) } else { phase = "measuring"; reading = "—" } }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (phase == "stable") "确认保存" else "开始称重") }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.Info, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp)); Text("称重时保持应用在前台，离开页面将停止。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("最近一次", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { page = "records" }) { Text("全部记录") } }
                        RecordRow(records.first()) { selectedEntry = records.first() }
                    }
                    "records" -> {
                        Text("每一小步，都算数", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("只展示你确认保存的称重记录。", color = c.onSurfaceVariant)
                        Surface(color = c.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("最近体重", style = MaterialTheme.typography.labelLarge); Text(records.first().value + " kg", fontSize = 32.sp, fontWeight = FontWeight.SemiBold) }
                            Column(horizontalAlignment = Alignment.End) { Text("较上次", style = MaterialTheme.typography.labelLarge); Text("−0.25 kg", fontSize = 24.sp, color = c.primary); Text("示例数据", style = MaterialTheme.typography.labelSmall) }
                        } }
                        Text("最近 7 天", style = MaterialTheme.typography.titleMedium)
                        records.forEach { e -> RecordRow(e) { selectedEntry = e } }
                        Text("“已写入”表示 Health Connect 已保存，不代表目标应用已完成显示。", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    "settings" -> {
                        Text("让称重更顺手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("设备与连接", style = MaterialTheme.typography.labelLarge, color = c.primary)
                        Card(colors = CardDefaults.cardColors(containerColor = c.surfaceVariant.copy(alpha = .45f)), shape = RoundedCornerShape(24.dp)) { Column {
                            SettingRow(Icons.Default.Home, "我的体重秤", "$device · 云麦 M263") { page = "devices" }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                            SettingRow(Icons.Default.Favorite, "健康平台", "Health Connect · 体重写入已就绪") { page = "setup" }
                        } }
                        Text("称重偏好", style = MaterialTheme.typography.labelLarge, color = c.primary)
                        Card(colors = CardDefaults.cardColors(containerColor = c.surfaceVariant.copy(alpha = .45f)), shape = RoundedCornerShape(24.dp)) { Column {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("5 秒自动确认", fontWeight = FontWeight.Medium); Text("关闭后，每次手动确认保存", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }; Switch(auto, { auto = it }) }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Text("深色外观", Modifier.weight(1f)); Switch(dark, { dark = it }) }
                        } }
                        Feature(Icons.Default.Info, "你的数据，由你掌握", "ScaleMate 无需账号。称重记录保存在本机，再由你授权的健康应用读取。")
                        TextButton(onClick = { page = "welcome" }) { Text("重新查看首次使用说明") }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text(if (page == "devices") "确认这台秤" else if (page == "setup") "允许读取体重" else "选择称重设备") },
            text = { Text(if (page == "devices") "云麦 YMBS-M263\n设备 …8E:CA\n\n请核对秤底部型号。这类秤通过蓝牙广播上报体重，无需系统配对。" else if (page == "setup") "在 Google 健康应用中启用 Health Connect，允许读取体重。\n\n视觉稿中的授权均为模拟，不会修改系统权限。" else "家里的秤 · 云麦 M263\n\n首版每次只读取选中的秤，其他设备不会触发自动保存。") },
            confirmButton = { TextButton(onClick = { if (page == "devices") { bound = true; device = "家里的秤"; page = "home" }; dialog = false }) { Text(if (page == "devices") "确认并添加" else "知道了") } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("取消") } })
        selectedEntry?.let { e -> AlertDialog(onDismissRequest = { selectedEntry = null }, title = { Text("称重详情") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("${e.value} kg", style = MaterialTheme.typography.headlineLarge); Text("${e.time}\n来源：家里的秤\n" + if (e.synced) "已写入 Health Connect" else "写入失败，可重试"); Text("此处为模拟数据。", style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { if (!e.synced) { val index = records.indexOf(e); if (index >= 0) records[index] = e.copy(synced = true) }; selectedEntry = null }) { Text(if (e.synced) "完成" else "重试写入") } }) }
    }
}

@Composable private fun Pill(label: String) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) } }
@Composable private fun Feature(icon: ImageVector, title: String, body: String) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp)); Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun Step(number: String, title: String, body: String, action: String, click: () -> Unit) { OutlinedCard(shape = RoundedCornerShape(22.dp)) { Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Text(number, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold) }; Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = click, contentPadding = PaddingValues(0.dp)) { Text(action) } } } } }
@Composable private fun DeviceCard(title: String, subtitle: String, selected: Boolean, click: () -> Unit) { OutlinedCard(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { ScaleMark(44); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(if (selected) Icons.Default.Check else Icons.Default.Add, "选择设备", tint = MaterialTheme.colorScheme.primary) } } }
@Composable private fun RecordRow(e: Entry, click: () -> Unit) { Card(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(e.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Row(verticalAlignment = Alignment.Bottom) { Text(e.value, fontSize = 27.sp, fontWeight = FontWeight.Medium); Text(" kg", Modifier.padding(bottom = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(if (e.synced) "✓ 已写入" else "待重试", color = if (e.synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium); Text("家里的秤", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
@Composable private fun SettingRow(icon: ImageVector, title: String, sub: String, click: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun ScaleMark(size: Int) { val c = MaterialTheme.colorScheme; Surface(shape = RoundedCornerShape(14.dp), color = c.primaryContainer, modifier = Modifier.size(size.dp)) { Canvas(Modifier.padding(10.dp)) { drawRoundRect(c.primary, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx())); drawLine(c.primary, Offset(this.size.width * .25f, this.size.height * .25f), Offset(this.size.width * .75f, this.size.height * .25f), 2.dp.toPx(), StrokeCap.Round) } } }
@Composable private fun ScaleDrawing() { val c = MaterialTheme.colorScheme; Canvas(Modifier.size(230.dp, 155.dp)) { val w = size.width; val h = size.height; drawOval(c.primary.copy(alpha = .1f), Offset(w * .12f, h * .78f), Size(w * .76f, h * .18f)); drawRoundRect(Color(0xFFF7FCF8), Offset(w * .12f, h * .05f), Size(w * .76f, h * .79f), androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())); drawRoundRect(c.primary.copy(alpha = .13f), Offset(w * .36f, h * .15f), Size(w * .28f, h * .16f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())); for (x in listOf(.23f, .63f)) for (y in listOf(.38f, .62f)) drawRoundRect(c.primary.copy(alpha = .19f), Offset(w * x, h * y), Size(w * .14f, h * .1f), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())); drawLine(c.primary, Offset(w * .4f, h * .23f), Offset(w * .6f, h * .23f), 3.dp.toPx(), StrokeCap.Round) } }
