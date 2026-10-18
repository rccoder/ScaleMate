# 健康 AI 未来设计

> 用户明确要求保留，供后续健康 AI 分析开发使用。此处是未上线的设计原型，所有睡眠、心率、活动、回答与导出均为合成示例；不能作为正式能力或真实验证证据。当前业务以 `app/src/main/` 为准。

[打开设计图集](index.html) · [健康 AI PRD](../../docs/称伴健康%20AI%20分析产品需求文档.md) · [文档索引](../../docs/文档索引.md)

## 保留的设计

- 健康首页与数据覆盖、周报／观察、证据入口。
- 预设问询、自由提问、示例对话及报告继续问。
- JSON／Prompt 导出选择、预览和模拟分享。
- 多 AI 服务、能力默认、主动提醒及健康数据来源。

图集保留原实机原型截图 13–28，共 16 张，图片字节不变。早期饮食流程、旧比例编辑、称重占位等图集已清理。截图来自 2026-10-17 的模拟设计，不是当前业务 App 的健康功能。

## 可点击原型源码

源码为 [PrototypeActivity.kt](../../app/src/prototype/java/app/scalemate/prototype/PrototypeActivity.kt)，Manifest 也在 `app/src/prototype/`；与正式业务源码分开，正常 debug／release 不包含原型入口。保留共享导航和服务配置上下文；源码中的旧饮食分支仅用于当时原型演示，不定义现行饮食规则，不能据此恢复客户端分食计算。

明确需要演示该设计时，在根目录用相同 JDK／SDK 构建，不带隔离测试参数：

```sh
./gradlew :app:assemblePrototype --console=plain
```

输出 `app/build/outputs/apk/prototype/app-prototype.apk`，包名 `app.scalemate.prototype`，名称“称伴健康 AI 设计”，版本后缀 `-health-design`，橙色图标。原型默认进入健康页，不调用 AI／Health Connect、不读真实记录，不保存密钥或导出文件；不是公开预览版或第四种产品版本。仅在明确的设计演示任务中安装，完成按当轮授权清理，不能重装为日常测试。

旧截图脚本已删除，避免其在其他页面前台时误操作。之后验收按当前 UI 手动或受控操作，不照抄旧坐标。
