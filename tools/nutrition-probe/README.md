# Health Connect 实机探测工具

独立调试 App，包名 `app.scalemate.nutritionprobe`，不修改称伴主应用或称重数据库。

- 写入一条标记为测试的午餐：500 kcal，蛋白质 30 g，碳水 50 g，脂肪 20 g。固定 client ID，重复操作不新增副本。
- 删除按钮仅删除本工具的这条测试餐食。
- “授权并检测可读取的数据”按类别请求读取权限，逐页读取并统计数量、时间范围及来源包名。拒绝的权限单独标注，不当作零记录。
- 查询截至检测时刻的全部可访问历史；未获历史读取权限时，结果受平台历史访问限制。
- 检测概览保存在 App 私有目录 `files/read-summary.txt`，概览不保存原始健康记录。
- 显式使用 `--ez details true` 启动时，另行读取最近 14 天的睡眠阶段、心率采样、HRV、运动及步数，保存为私有文件 `files/health-details.jsonl`，用于用户授权的详细分析。它包含健康明细，不应提交到 Git。所有导出均在本地完成，无网络上传代码。

使用项目相同的 JDK 17 和 Android SDK 36，设置 `JAVA_HOME`、`ANDROID_HOME` 后运行：

```sh
gradle -p tools/nutrition-probe assembleDebug
adb install -r tools/nutrition-probe/build/outputs/apk/debug/NutritionProbe-debug.apk
adb shell am start -S -n app.scalemate.nutritionprobe/.MainActivity --ez read true
```

在手机上完成授权后，可提取检测概览：

```sh
adb shell run-as app.scalemate.nutritionprobe cat files/read-summary.txt
```

记录数是平台原始记录条数，不是天数或去重后的步数。零条仅代表此次查询未返回数据，不能证明 Google Fit 云端不存在此类记录。
