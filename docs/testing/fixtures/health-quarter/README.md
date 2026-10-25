# 季度 Health Connect 合成数据

用于发现小样本无法覆盖的分页故障、反复全量读取及本地快照开销。只用于 API 36 模拟器，不代表手机或来源应用的真实数据分布。

复用已有合成数据源 `app.scalemate.healthseed`，橙色工具图标；脚本拒绝手机和未安装该固定工具的设备。它不是称伴产品版本，不安装到手机，不使用 `app.scalemate` 或用户数据库造数。已有工具的安装约定见 [模拟器验收](../../2026-10-25-04-Android%20模拟器能力验证.md)。

## 数据

首次运行时以上海时区今天为结束边界，保存这一边界供重复运行与清理使用，向前取三个月。本轮为 2026-07-26 至 2026-10-25，共 92 天、50,186 条原生记录、132,480 个心率样本。

| 类型 | 频率 | 本轮记录数 |
| --- | --- | ---: |
| 步数 | 每 5 分钟 | 26,496 |
| 距离、活动热量 | 各每 15 分钟 | 各 8,832 |
| 心率 | 每小时一条，含每分钟采样 | 2,208 |
| HRV | 每夜 36 次 | 3,312 |
| 饮食 | 每天三餐 | 276 |
| 睡眠、体重 | 每天一次 | 各 92 |
| 跑步 | 隔日一次 | 46 |

睡眠含阶段；来源名、食物名和运动名为正常合成样例。所有客户端 ID 使用 `quarter-20261026-` 前缀；重跑使用更高版本更新相同 ID，不增加重复记录。数据源以前的 `acceptance-20261025-` 等记录保持不动。

## 执行

仓库外准备 JDK、SDK 36、build-tools 36.0.0 与原 Android debug keystore。脚本临时构建 APK、正常覆盖固定数据源、仅在模拟器授予九类写权限，临时 APK 随后删除。显式填写实际模拟器 serial 和 SDK 路径：

```sh
python3 scripts/health_quarter_seed.py seed --serial emulator-5556 --sdk <SDK_PATH> --output /tmp/quarter-seed.json
./gradlew -PfoodDev :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --console=plain
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

在隔离测试版上授权当前全部 55 类读取及更早历史权限，保持 App 前台。基准测试显式检查这些条件，不用空返回冒充通过：

```sh
adb -s emulator-5556 shell am instrument -w -r \
  -e class app.scalemate.HealthQuarterDeviceTest \
  -e healthQuarterLive true -e quarterMode fixed -e quarterChange true \
  app.scalemate.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 exec-out run-as app.scalemate.fooddev cat files/health-quarter-fixed.json
```

测试使用真实平台和完整 `HealthViewModel` 同步、SQLite、指标与临时明细通路；数据库以 UUID 隔离，结束删除自己的数据库与快照。`baseline` 模式仅采集故障，不把 JUnit 的 OK 当作业务通过；`fixed` 模式检查完整度、失败状态、无变化／重启不再全量、范围查询不读平台，以及无变化复用明细。另用独立逐日聚合核对批量聚合与变更后的结果。

`quarterChange=true` 会修改昨日一条步数，把 45 天前的午餐移动到前一天并改热量，新增今日酸奶，删除昨日一条距离记录。它只操作上述前缀的样例。重新做完整前后对比前先执行 `cleanup` 再 `seed`，避免把已经变更的样例当作初始数据。跨日重跑需核对固定数据边界和测试使用的日期；本基准按本轮日期验收，不把时钟变化当成平台错误。

```sh
python3 scripts/health_quarter_seed.py cleanup --serial emulator-5556 --sdk <SDK_PATH> --output /tmp/quarter-cleanup.json
adb -s emulator-5556 uninstall app.scalemate.test
```

`prepare` 仅更新已有数据源 APK 和写权限，不造数；`change` 可单独执行稀疏变更。清理只删除本轮前缀，不卸载有其他样例的数据源或清空称伴。性能报告只归档合成统计、请求计数和耗时，不保存真实用户数据库、变更令牌或健康原始导出。

本轮前后数据与限制见 [季度同步性能验收](../../2026-10-26-02-季度健康数据同步性能验收.md)。
