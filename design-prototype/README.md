# ScaleMate Material 3 交互视觉稿

使用 Kotlin、Jetpack Compose、官方 `androidx.compose.material3`，不是 HTML 仿制组件。

视觉：柔和绿色主题、暖浅色背景、大号体重读数、Material 卡片、按钮、开关、进度指示、对话框与底部导航。支持深色外观和系统字体缩放。后续正式版可增加壁纸动态配色。

包含首次介绍、健康连接引导、扫描并绑定秤、实时模拟称重、稳定后 5 秒确认与取消、写入成功、记录详情／失败重试和设置。所有数据为模拟，本原型不申请蓝牙或健康权限，也不执行健康写入。首页顶部一直标注交互视觉稿。

应用名：ScaleMate 视觉稿；包名：`local.scalemate.design`。独立安装，现有体重测试应用继续可用。

操作：首次设置或跳过预览 → 开始称重 → 模拟读数变化 → 5 秒倒计时 → 取消或模拟保存。离开称重页或应用进入后台取消待确认。设置里关闭自动确认后，稳定读数改为手动确认。

项目里的历史记录、设备信息和状态保留在原型内存中，不代表真实业务数据；重启可恢复到初始示例。设备扫描也只是模拟发现 M263。

## 构建

JDK 17、Gradle 8.13、Android SDK 36，AGP 8.13.2、Kotlin 2.2.21、Compose BOM 2025.12.01。

```bash
JAVA_HOME="$HOME/.local/share/health-connect-tools/jdk" \
  "$HOME/.local/share/health-connect-tools/gradle-8.13/bin/gradle" :app:assembleDebug
```

`local.properties` 指向本机 SDK，未纳入版本控制。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。截图与总览保存在 `../design/`。

参考：[Material 3 官方组件](https://developer.android.com/develop/ui/compose/designsystems/material3)。
