# 设置页整理与双协议服务

2026-10-17，按用户确认的信息架构调整当前 Android 项目。以下为真实开发版界面，截图在 `screenshots/`。

## 设置首页

| 分组 | 项目 |
| --- | --- |
| AI 服务 | 服务管理、默认图片模型、默认文本模型 |
| 健康数据同步 | Health Connect；分别摘要体重与饮食授权状态 |
| 称重 | 我的体重秤、稳定后自动保存 |
| 外观与语言 | 深色外观、跟随壁纸配色、语言 |
| 应用信息 | 隐私说明、当前版本；开发包标注开发版 |

统一为浅色分组底、列表行、副说明与右箭头。开关整行可操作。未绑秤或未完成试称时，自动保存置灰并说明原因；完成设置后保留原有 5 秒倒计时和首次启用确认。

## 二级页面

- 健康数据同步：体重与营养的独立写入权限、同步记录入口、系统 Health Connect、目标应用读取权限与连接帮助。授权后不宣称 Google Fit 已显示。
- AI 服务管理：服务列表显示格式、模型、图片能力与默认用途；点击编辑，新增使用独立编辑页。
- 服务编辑：名称、OpenAI／Anthropic 格式、Base URL、模型 ID、密钥、图片能力、连接测试、保存与删除。原密钥不回显；留空保留。连接测试使用当前表单，测试不会先保存配置，也不发送照片或健康数据。

## 接口与迁移

OpenAI 使用 Chat Completions、Bearer 认证及 `image_url` 消息；Anthropic 使用 Messages、`x-api-key`／`anthropic-version`、顶层 system 和 base64 image block。两种响应独立解析，拒绝截断或空回复，不静默回退格式或供应商。

远程服务要求 HTTPS；用户配置的私有 IPv4 HTTP 仍可用于局域网代理。Anthropic 根地址、`/v1` 地址和完整 `/v1/messages` 地址均可；编辑页按格式提示地址示例，切换格式不擅自改写已填地址。

SQLite 升到 v3，旧服务新增格式字段并默认 OpenAI，保留加密密钥、默认路由与已有记录；v1 可直接升级到 v3。修改格式与其他服务配置一样，使旧的图片发送同意失效，下一次识别重新确认。

协议依据：[Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create)、[Claude API 使用说明](https://platform.claude.com/docs/en/claude_api_primer)。具体兼容性以实际代理与模型测试为准。

## 截图

| 页面 / 状态 | 截图 |
| --- | --- |
| 调整前 | [00-before.png](screenshots/00-before.png) |
| 设置首页：AI、同步、称重 | [01-settings-top.png](screenshots/01-settings-top.png) |
| 设置首页：外观、语言、应用信息 | [02-settings-bottom.png](screenshots/02-settings-bottom.png) |
| 深色模式，包含状态栏与开关配色 | [03-settings-dark.png](screenshots/03-settings-dark.png) |
| 语言选择 | [04-language-choice.png](screenshots/04-language-choice.png) |
| 分类写入权限与同步入口 | [05-sync-settings.png](screenshots/05-sync-settings.png) |
| 健康应用连接说明 | [06-sync-help.png](screenshots/06-sync-help.png) |
| 设备管理入口 | [07-device-entry.png](screenshots/07-device-entry.png) |
| 默认图片模型选择 | [08-default-model-picker.png](screenshots/08-default-model-picker.png) |
| 服务管理列表 | [09-service-list.png](screenshots/09-service-list.png) |
| OpenAI 编辑页 | [10-service-openai.png](screenshots/10-service-openai.png) |
| 切换 Anthropic 格式 | [11-service-anthropic.png](screenshots/11-service-anthropic.png) |
| 当前表单连接测试成功，密钥留空保留 | [12-connection-success.png](screenshots/12-connection-success.png) |
| 保存后的 Anthropic 服务 | [13-anthropic-saved.png](screenshots/13-anthropic-saved.png) |
| 重开仍为 Anthropic | [14-anthropic-reopened.png](screenshots/14-anthropic-reopened.png) |
| 恢复原 OpenAI 配置 | [15-openai-restored.png](screenshots/15-openai-restored.png) |
| 默认文本模型，仅模型列表与取消 | [16-default-text-picker.png](screenshots/16-default-text-picker.png) |
| 没有图片模型时的添加提示 | [17-default-image-empty.png](screenshots/17-default-image-empty.png) |
| 从空状态直接进入添加服务 | [18-add-service-from-empty.png](screenshots/18-add-service-from-empty.png) |

## 验证记录

- 38 项单元测试通过，包含 Anthropic 地址与协议校验、本次称重反馈关联的记录 ID 校验。
- 13 项实机数据库与协议测试通过，包含旧服务 v2→v3 保留密钥和默认路由、v1 直接迁移、两种消息结构、文本块解析与截断拒绝。
- 代理端真实请求：OpenAI 文本约 2.5 秒、图片约 5.2 秒；Anthropic 文本约 1.3 秒、图片约 4.5 秒，均返回有效结果。图片为此前署名的公开米饭鸡蛋照片，不是私人相册照片。
- Android 客户端：解锁后复验，两种格式的文本连接、图片预处理与真实识别全部通过，测试方法总计约 12 秒。首次锁屏／休眠期间的超时尝试保留为失败记录，不计为通过。
- 界面实测：浅色与深色、语言选择、分类权限显示、同步帮助与设备入口返回、默认图片模型选择入口、服务编辑、当前表单测试连接、格式保存与重开均已检查。修正了默认紫色残留和状态栏明暗未跟随应用主题的问题。
- 格式切换验证结束后，服务恢复为原 OpenAI 配置，图片默认服务保留；深色模式恢复为原来的关闭状态。餐食与待删除任务均为零，这轮协议测试未写入 Health Connect。
- 最新开发包已安装，并放到手机 `Download/ScaleMate-Food-Dev.apk`。普通 Debug、独立饮食开发包和 Mock Prototype 均可构建；328 对中英文资源校验通过。

## 后续修正：模型弹窗与称重反馈

设置里的默认图片／文本模型弹窗去掉了混在模型列表中的「AI 服务」管理入口。默认图片仅列出支持图片的服务；有可选项时只显示模型列表与取消，没有可选项时显示说明和明确的「添加 AI 服务」按钮，可直接进入空白编辑页。饮食页临时选择中的管理入口保留。实机分别验证了图片、文本、有可选模型、无图片模型和添加跳转；测试后恢复了原图片能力与默认路由。

称重成功落库后，页面通过本次保存的记录 ID 跟随实际同步状态，分别显示「已同步到 Health Connect」「正在同步」「等待同步」「待授权同步」「同步失败」等；失败可在当前页重试，缺权限可在当前页授权。切换秤时不会把上一次保存的会话覆盖到新设备。不会把称伴本地保存成功当作健康平台写入成功，也不根据历史列表第一项猜测本次结果。

本次新增关联测试验证：即使列表里有另一条更新且已同步的记录，本次待授权记录仍显示自己的状态；本次记录状态变化可继续追踪，新称重和已删除记录不会误取别条结果。这部分未重新进行实秤测量，截图只覆盖模型选择的实机界面，不冒充真实称重验收。

本轮设备未绑定真实体重秤，未复测实际上秤与开启自动保存；设置中的禁用状态已实测，原有称重状态机测试通过。多模型供应商矩阵、不同系统机型及 Release 发布验收仍需后续覆盖。

本目录不保存密钥、原始健康数据或手机相册页面。其他饮食功能截图见 [饮食实机设计记录](../food-implementation/README.md)。


## 2026-10-18 可选模型查询与复制服务

服务行增加“复制”，进入独立的新建副本表单；保留格式、地址、模型与图片声明，密钥按新服务 ID 重新加密，不显示明文。取消不保存，保存后独立编辑或删除，原服务和默认图片／文本选择不变。模型 ID 继续支持手动输入，新增“获取模型列表”，可搜索并选择回填；不根据列表推断图片能力。查询失败／不支持时仍可手填，不覆盖原模型。

| 截图 | 内容 |
| --- | --- |
| [101-ai-services-copy-action](screenshots/101-ai-services-copy-action.png) | 已有服务右侧复制操作 |
| [102-copied-service-unsaved](screenshots/102-copied-service-unsaved.png) | 未保存的副本及密钥沿用提示 |
| [103-model-list-real-service](screenshots/103-model-list-real-service.png) | 从真实接口获取模型名称和 ID |
| [104-model-list-search-empty](screenshots/104-model-list-search-empty.png) | 搜索无匹配时保留手动填写路径 |
| [105-model-query-failure-fallback](screenshots/105-model-query-failure-fallback.png) | 查询失败后可重试或手动填写 |
| [106-service-copy-saved-independent](screenshots/106-service-copy-saved-independent.png) | 副本独立保存，默认模型仍是原服务 |
| [109-copied-service-connection](screenshots/109-copied-service-connection.png) | 副本保存、重开后密钥仍可用于连接 |

实机验证取消复制无新增、查询搜索／选择回填、失败关闭后可手动配置、保存副本、重开连接、删除副本；验证完成只保留原服务及原默认选择。截图来自验证时的实际配置，不固定模型名单。

自动验证覆盖两种格式 GET 鉴权、分页去重、空列表、404 不支持、401、拒绝重定向、格式异常与新 ID 密钥重加密；代理真实两种格式模型查询及复制后的文本调用通过。最终开发版 52 项单元测试通过，实机存储／协议及饮食场景共 17 项测试通过，412 对中英文资源一致。

对应最新饮食方案、标准展开图标与份量回归见[饮食设计记录](../food-flow-redesign/README.md)。
