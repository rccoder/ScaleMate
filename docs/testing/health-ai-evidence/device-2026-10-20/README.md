# 2026-10-20 健康 AI 实机验收证据

以下截图来自 `app.scalemate.fooddev` 覆盖安装后的真实 Android 设备，设备为 Xiaomi 14 Ultra / Android 16，分辨率为 1080 × 2400。截图中的 Health Connect 指标是用户设备上的真实读取结果；AI 请求使用已配置服务，但本轮服务端在响应前中断，因此没有保存为成功回答。

这些真实截图与 [未来设计图集](../../../design/ai-prototype/README.md) 分开保存。设计图集全部为 Mock，不能作为本轮实机证据。

| 页面 | 截图 | 结论与边界 |
| --- | --- | --- |
| 设置 → 健康数据与授权 | [01-permissions-settings.png](01-permissions-settings.png) | 只显示“分析所需的数据”和“可选权限”两层；55 个已选类别、更早历史已授权；后台读取与通知未混入首次授权页 |
| 健康首页（关键指标与覆盖折叠） | [02-health-home-collapsed.png](02-health-home-collapsed.png) | 55 个类别只先显示 5 个关键指标，并显示“查看全部指标（另有 4 个类别）”；覆盖区默认隐藏无记录类别，显示“另有 45 个无记录” |
| AI 连接中断状态 | [03-ai-network-error.png](03-ai-network-error.png) | 请求在收到响应前中断，界面明确显示连接中断而非“回答未通过校验”；问题被保留，可重试 |

本轮实机通过：覆盖安装后旧记录仍在、设置授权页布局、健康首页折叠、真实 Health Connect 摘要、错误提示语义。未通过／未完成：当前 Magpie 服务从该手机的网络请求在响应阶段被断开，未能取得一次真实成功回答；因此本目录不能作为“真实 AI 成功回答”证据。日志只记录了 `phase=response`、事件数、异常类别 `IOException`，没有记录 URL、密钥、请求体或健康内容。
