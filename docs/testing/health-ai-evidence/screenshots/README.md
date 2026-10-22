# 健康功能本地渲染证据

这些图片由 2026-10-19 的 `HealthUiLocalTest` 使用 Robolectric Android 35、原生 Canvas 渲染当前业务界面生成，规格为 411 × 891 dp、xxhdpi。**没有连接手机，没有真实 Health Connect 服务，也没有健康记录、照片、API Key 或真实 AI 调用。** 不是实机验收，也不替代保留的未来设计图或 README 的真实截图。

| 文件 | 场景与边界 |
| --- | --- |
| [01-health-home-local.png](01-health-home-local.png) | 首次进入健康，平台服务不可用、未授权／未读取，仍可进入对话与其他本地入口 |
| [02-health-range-local.png](02-health-range-local.png) | 7／28／90 天、全部可读取历史、自定义日期入口；日期按运行环境动态生成 |
| [03-health-permissions-local.png](03-health-permissions-local.png) | 分类读取授权入口、选择全部／常用／取消全选；此模拟环境没有平台服务，因此各类别显示不支持，不代表手机支持状态 |
| [04-health-chat-local.png](04-health-chat-local.png) | 无密钥／无个人数据的对话入口、服务管理、输入与历史；没有生成模型回答 |

测试还覆盖自定义日期页和未配置服务时保留输入的问题。图片保留完整本地渲染结果，未修改数值或合成真实数据。运行方法及待实机项目见 [本地验证记录](../../2026-10-19-01-健康%20AI%20功能实现与本地验证.md)。
