# 季度同步验收证据

全部来自 API 36 模拟器的合成数据；手机文件只包含包身份、APK 摘要与数据保留布尔结果，不包含用户健康记录、数据库或游标。

| 文件 | 含义 |
| --- | --- |
| [seed.json](seed.json) | 92 天、50,186 条原生记录和 132,480 个心率点的造数结果 |
| [baseline.json](baseline.json)、[日志](baseline-instrumentation.txt) | `672de49` 基线加只读观察器及独立数据库入口；步数分页失败，反复重扫。JUnit OK 只代表采集完成 |
| [first-fix.json](first-fix.json)、[日志](first-fix-instrumentation.txt) | 修复分页与批量读取后，无变化仍需约 2.5 秒，尚未复用明细 |
| [revision-first-attempt.json](revision-first-attempt.json)、[失败日志](revision-first-attempt-instrumentation.txt) | 加版本校验后，造数工具暖启动没有执行 change，验证等待超时；不是同步通过 |
| [final.json](final.json)、[最终日志](final-instrumentation.txt) | 最终 APK 的完整季度回归与逐日平台聚合核对 |
| [同步回归](sync-regression-instrumentation.txt)、[无更早历史权限回归](no-history-instrumentation.txt) | 原有九类读取、缓存范围、真实增改删与无效令牌恢复，各两项通过 |
| [checks.json](checks.json) | 最终 JVM、Lint、翻译检查与 APK SHA-256 |
| [phone-install.json](phone-install.json) | 最终手机覆盖安装的包身份、APK SHA-256、七张业务表和偏好保留结果 |
| [cleanup.json](cleanup.json) | 仅删除本轮季度前缀记录；`heartSamples` 是构造清理 ID 时遍历的理论点数，不表示清理时新增了心率数据 |

`platformRequests` 是被注入 Reader 的操作尝试计数，包含尚未调用平台就构建失败的分页请求，医疗按类别计数，不是 Binder RPC 统计。总耗时包含完整 ViewModel 通路；`heapUsedBytes` 只是阶段结束时的堆用量，不是峰值。`after-restart` 指 ViewModel 与数据库重建，不是整个进程冷启动。

正式结论、失败与未验证范围见 [验收记录](../2026-10-26-02-季度健康数据同步性能验收.md)，复现与清理命令见 [季度夹具](../fixtures/health-quarter/README.md)。
