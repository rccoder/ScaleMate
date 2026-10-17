# AI 功能交互原型

基于当前 `app/` 模块新增 `prototype` 构建类型，使用相同 Kotlin、Compose 和 Material 3 依赖，作为两份 AI PRD 的可点击体验稿。正式 `debug`／`release` 不包含原型源码或原型入口。

- 应用名：**称伴 AI 原型**。
- 包名：`app.scalemate.prototype`，可与称伴 0.1.1 同时安装，不接触其数据库。
- 源码：`app/src/prototype/java/app/scalemate/prototype/PrototypeActivity.kt`。
- 全部数据合成，所有交互修改仅存在内存，重启重置。
- 原型 Manifest 移除了正式版蓝牙、体重写入权限及其入口；无相机、网络、健康读取、通知或后台调度调用。
- 密钥表单只演示，**不要填写真实 API Key**。导出、复制和分享展示模拟反馈，不实际保存或发送文件。

[实机截图与设计归档](../../design/ai-prototype/README.md) · [截图总览](../../design/ai-prototype/index.html)

## 体验路径

1. 饮食 → 记录食物 → 拍照识别 → 使用示例照片 → 多选标签／补充描述 → 开始分析 → 确认米饭份量 → 编辑单项营养 → 保存。
2. 饮食 → 常吃食物，或记录食物 → 常吃／历史 → 多选添加 → 编辑 → 保存；可将识别项存为常吃。
3. 健康 → 预设问询／自由提问 → 改写问题 → 发送 → 查看模拟回答与示例依据。
4. 健康 → 导出给其他 AI → 范围、类别、摘要／明细、问题 → JSON／Prompt 预览 → 模拟保存／分享。
5. 设置 → AI 服务 → 新增／编辑／删除 → 勾选图片能力 → 按能力选择默认服务；在食物或健康请求处可临时切换。
6. 健康／设置 → 主动提醒 → 调整主题开关 → 查看提醒示例和报告。
7. 五个底部导航中的称重与体重记录仅为视觉占位，不执行真实称重。

## 构建

```sh
JAVA_HOME="$HOME/.local/share/health-connect-tools/jdk" \
ANDROID_HOME="$HOME/.local/share/health-connect-tools/sdk" \
"$HOME/.local/share/health-connect-tools/gradle-8.13/bin/gradle" :app:assemblePrototype
```

产物：`app/build/outputs/apk/prototype/app-prototype.apk`。

```sh
adb install -r app/build/outputs/apk/prototype/app-prototype.apk
adb shell am start -n app.scalemate.prototype/app.scalemate.prototype.PrototypeActivity
```

这是产品交互评审稿，不实现正式数据存储、AI 推理、健康统计、份量算法、文件导出、服务认证或平台同步。评审通过后按照 PRD 实现正式逻辑和自动化验证。
