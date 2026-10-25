# Health Connect 数据能力与格式

> 本文回答三个不同层次的问题：Android Health Connect 平台公开了什么、各记录是什么格式，以及称伴当前实际读取和展示了什么。研究基线为 API 36、称伴 `compileSdk = 36 / minSdk = 34 / targetSdk = 36` 和当前工作区代码；平台类型存在不等于某台设备、某个来源应用一定写入了数据。

## 一、先看结论

- Health Connect 是手机上的健康数据交换平台，不是 Google Health／Google Fit／手表厂商云端的全量历史。称伴只能读取“来源应用已写入平台、用户已授权称伴读取、当前设备仍保留”的记录。
- 平台支持的记录类型很多，但各类数据的字段、时间粒度、单位、来源和可用性不同。读取前必须按类别申请权限，不能用写入权限代替读取权限。
- 同一种数据可能由多个来源同时写入。累计型指标应优先调用平台聚合接口，而不是把不同来源的原始记录直接相加。
- 返回空列表只能证明当前查询范围内没有可读记录，不能证明用户真实值为零。未授权、无记录、读取失败、平台不支持必须分开表达。
- 运动详情不要只看 `ExerciseSessionRecord`：它提供运动类型、起止时间、标题、备注、分段、圈和路线；距离、热量、步数、心率、速度、功率等是其他记录类型，平台没有“属于某次运动”的明确外键。

## 二、读取模型、权限与分页

### 2.1 读取链路

普通健康记录通过 `HealthConnectManager.readRecords()` 读取：

1. 按记录类型构造 `ReadRecordsRequestUsingFilters`。
2. 可设置时间范围、一个或多个 `DataOrigin`、排序和分页大小。
3. 每页返回 `ReadRecordsResponse.records` 和下一页令牌。
4. 普通记录无下一页时 `nextPageToken == -1`；医疗资源则用 `null` 表示没有下一页。普通记录默认每页 1000 条，允许设置 1–5000 条。

医疗记录通过 `readMedicalResources()` 读取，使用 `ReadMedicalResourcesInitialRequest` 和后续分页请求；称伴当前每页取 500 条。普通记录和医疗记录不是同一套返回结构，分页令牌的缺失值也不同。

累计值可通过 `aggregate()` 计算。平台负责支持来源下的去重和聚合规则，因此步数、距离、热量等累计量不应简单遍历全部原始记录求和。聚合结果仍需带上查询时间和时区。

前台和后台读取范围也可能不同。Android API 36 的普通记录接口说明：只有读取权限的应用在后台调用时，可能只能读到自己写入的记录；只有写权限而没有读取权限的应用，即使在前台也只能读到自身写入的记录。医疗资源在后台读取其他来源时还需要 `READ_HEALTH_DATA_IN_BACKGROUND`。因此，后台任务不能假定拥有和前台授权页面相同的可见范围，必须单独核对权限和查询结果。

### 2.2 权限不是只有一类

| 权限 | 作用 | 注意 |
| --- | --- | --- |
| `android.permission.health.READ_<类型>` | 读取对应普通记录 | 每个类型分别授权，例如 `READ_STEPS`、`READ_SLEEP` |
| `android.permission.health.READ_EXERCISE` | 读取运动记录 | 不能替代路线权限 |
| `android.permission.health.READ_EXERCISE_ROUTES` | 读取运动路线 | 通常还需要用户在系统界面逐条或按设置授权路线 |
| `android.permission.health.READ_MEDICAL_DATA_<类别>` | 读取对应医疗资源 | 返回 FHIR JSON，不是普通 Record |
| `READ_HEALTH_DATA_HISTORY` | 读取首次授权前 30 天以外的更早数据 | 默认最多可读首次授权前 30 天；可选权限，不代表已有全部云端历史 |
| `READ_HEALTH_DATA_IN_BACKGROUND` | 在后台读取 | 应仅在用户明确需要后台提醒等场景申请 |

称伴当前清单还保留 `WRITE_WEIGHT` 和 `WRITE_NUTRITION`，它们是写入权限，与读取权限互不替代。读取路线时，代码会先检查运动读取权限，再检查路线权限；路线权限被撤销并不表示运动会话本身不可读。

默认历史边界按“首次授予权限”的时间计算，而不是按设备数据存在的时间计算：没有 `READ_HEALTH_DATA_HISTORY` 时，平台默认最多允许读取首次授权前 30 天。删除并重装应用会撤销权限；再次授权后，30 天边界按新的授权时间重算。

### 2.3 路线为什么特殊

路线可能来自 GPS 轨迹，属于更敏感的数据。即使应用已声明 `READ_EXERCISE_ROUTES`，平台仍可能要求用户对具体路线再次同意：

1. 先通过运动记录发现存在路线且当前没有拿到路线对象。
2. 使用 `ACTION_REQUEST_EXERCISE_ROUTE` 和 `EXTRA_SESSION_ID` 发起系统授权。
3. 成功结果从 `EXTRA_EXERCISE_ROUTE` 取得 `ExerciseRoute`。
4. 用户拒绝时不能把“没有路线”显示成“路线为空”，应显示未共享或未授权。

称伴当前以 `route_consent_required` 表示这类状态，并在读取结果中区分“存在待授权路线”和“拿到空路线”。

## 三、Record 的公共格式

### 3.1 通用元数据

所有普通记录都继承 `Record`，通过 `record.metadata` 获得：

| 字段 | 类型／含义 |
| --- | --- |
| `id` | 平台记录 ID，字符串；只保证平台内记录身份，不是用户可见编号 |
| `clientRecordId` | 写入方提供的客户端 ID，可能为空 |
| `clientRecordVersion` | 写入方版本，冲突解决使用；未设置为 0 |
| `dataOrigin.packageName` | 数据来源应用包名 |
| `device` | 来源设备，含类型、厂商、型号；可能是未知 |
| `lastModifiedTime` | 最后修改时间；未设置时可能为 `Instant.EPOCH` |
| `recordingMethod` | `0` 未知、`1` 主动记录、`2` 自动记录、`3` 手动录入 |

同一数据可能同时来自手机、手表、体脂秤或第三方 App。做跨来源统计前先明确是否按来源筛选，否则会把重叠或不同量纲的记录混在一起。

### 3.2 时间形态

| 基类 | 字段 | 适用方式 |
| --- | --- | --- |
| `InstantRecord` | `time`、`zoneOffset` | 单个时刻的测量，如体重、血压、血糖、身高 |
| `IntervalRecord` | `startTime`、`endTime`、`startZoneOffset`、`endZoneOffset` | 一段时间或一段采样，如睡眠、步数、心率、运动、营养 |

平台原生时间是 `java.time.Instant`，另带用户当时的 `ZoneOffset`。称伴内部把时间归一化为毫秒时间戳，并以 ISO 8601 UTC 文本输出，同时保存 `zoneOffsetSeconds`。做“今天”或“某天”的统计必须指定报告时区；不能只按 UTC 日期切开。

### 3.3 采样、阶段和聚合

- 部分区间记录只有总和字段，例如 `StepsRecord.count`、`DistanceRecord.distance`、`ActiveCaloriesBurnedRecord.energy`。
- 心率、速度、功率、步频、骑行踏频、皮温变化等可能包含多个带时间的采样点；采样点的时间戳不能丢。
- 睡眠会话包含睡眠阶段列表，阶段类型使用平台整数枚举。称伴按阶段类型和有效覆盖计算睡眠时长，不能把未知阶段填成睡眠。
- 枚举值只能映射标签或按代码计数，不能求平均。例如运动类型 `33` 和 `45` 求平均没有意义。
- 累计指标应优先使用平台聚合结果；原始明细适合展示、导出和解释，不应在 UI 层重复求和。

## 四、称伴当前声明的数据范围

当前 `HealthCatalog` 声明 42 个普通记录类型、1 个运动路线读取入口和 12 个医疗类别，共 55 项。这里的“声明”表示应用可能请求读取，不表示系统版本、机型和来源应用一定提供对应数据。

### 4.1 日常活动

| 类型 | 主要字段 | 形态／单位 |
| --- | --- | --- |
| `Steps` | `count` | 区间；步数 |
| `Distance` | `distance` | 区间；米，平台聚合为总距离 |
| `ElevationGained` | `elevation` | 区间；米 |
| `FloorsClimbed` | `floors` | 区间；楼层数（double） |
| `ActiveCaloriesBurned` | `energy` | 区间；活动能量，平台聚合为总量 |
| `TotalCaloriesBurned` | `energy` | 区间；总能量，平台聚合为总量 |
| `WheelchairPushes` | `count` | 区间；次数 |
| `ActivityIntensity` | `activityIntensityType` | 区间；强度枚举，不是连续功率 |
| `ExerciseSession` | 运动类型、起止、分段、圈、路线等 | 区间；会话 |
| `PlannedExerciseSession` | 计划、块、完成会话引用 | 区间或按天计划 |
| `MindfulnessSession` | 类型、标题、备注 | 区间；会话 |
| `SleepSession` | 阶段、标题、备注 | 区间；阶段与睡眠会话时长 |
| `HeartRate` | 采样列表，每个采样有 `beatsPerMinute` | 区间 + 多采样；bpm |
| `HeartRateVariabilityRmssd` | `heartRateVariabilityMillis` | 即时；ms（RMSSD） |
| `RestingHeartRate` | `beatsPerMinute` | 即时；bpm |
| `Speed` | 采样列表 | 区间 + 多采样；m/s |
| `StepsCadence` | 采样列表，`rate` | 区间 + 多采样；步／分钟 |
| `CyclingPedalingCadence` | 采样列表，`revolutionsPerMinute` | 区间 + 多采样；rpm |
| `Power` | 采样列表，瓦特 | 区间 + 多采样；W |
| `Vo2Max` | `vo2MillilitersPerMinuteKilogram`、测量方法 | 即时；mL/min/kg |

### 4.2 身体与营养

| 类型 | 主要字段 | 形态／单位 |
| --- | --- | --- |
| `Weight` | `weight` | 即时；克 |
| `Height` | `height` | 即时；米 |
| `BodyFat` | `percentage` | 即时；百分比 |
| `BodyWaterMass` | `bodyWaterMass` | 即时；克 |
| `BoneMass` | `mass` | 即时；克 |
| `LeanBodyMass` | `mass` | 即时；克 |
| `BasalMetabolicRate` | `basalMetabolicRate` | 即时；瓦特；平台提供能量聚合 |
| `Hydration` | `volume` | 区间；升 |
| `Nutrition` | 能量、蛋白质、碳水、脂肪、纤维、糖、钠及维生素／矿物质 | 区间；餐次、餐名、完整营养字段按来源提供 |
| `OxygenSaturation` | `percentage` | 即时；百分比 |
| `RespiratoryRate` | `rate` | 即时；次／分钟 |
| `BodyTemperature` | `temperature` | 即时；摄氏度 |
| `BasalBodyTemperature` | `temperature` | 即时；摄氏度 |
| `SkinTemperature` | 基线、温度变化采样 | 区间；摄氏度／温度差 |
| `BloodPressure` | `systolic`、`diastolic`、测量位置、体位 | 即时；mmHg 与枚举 |
| `BloodGlucose` | `level`、进餐关系、餐次、样本来源 | 即时；mmol/L 与枚举 |

`NutritionRecord` 的字段名不一定代表完整的一餐：餐名、餐次和各项营养素都可能是空值。称伴当前映射会忽略餐名、标题和备注，只保留平台可解析的数值字段；因此从称伴导出的营养数据不应该反推“用户完整吃过什么”。

### 4.3 周期与敏感记录

| 类型 | 主要字段 | 形态／单位 |
| --- | --- | --- |
| `MenstruationFlow` | `flow` | 即时；流量枚举 |
| `MenstruationPeriod` | 起止 | 区间；经期 |
| `IntermenstrualBleeding` | 时间 | 即时记录 |
| `CervicalMucus` | `appearance`、`sensation` | 即时；枚举 |
| `OvulationTest` | `result` | 即时；枚举 |
| `SexualActivity` | `protectionUsed` | 即时；枚举 |

这些字段应按隐私等级处理。称伴把它们归入敏感或临床组，AI 输入也要先经过独立同意；不要把“已授权读取”解释为“已同意任何模型处理”。

### 4.4 运动路线

`ExerciseRoute` 不是独立普通 Record 类，而是 `ExerciseSessionRecord` 的可选关联对象。路线位置点通常包括：

| 字段 | 含义 |
| --- | --- |
| `time` | 位置采样时间 |
| `latitude` / `longitude` | WGS 84 纬经度；文档范围为纬度 -90..90、经度 -180..180 |
| `altitude` | 可选海拔，`Length`，米；源码未固定海拔基准面，不能自行假设成海平面 |
| `horizontalAccuracy` | 可选水平精度，`Length`，米 |
| `verticalAccuracy` | 可选垂直精度，`Length`，米 |

当前称伴在路线映射中读取位置点、计算逐点球面距离并保存为米；没有位置数据时用 `routeRequiresConsent` 标记待授权，不能直接显示为“0 米路线”。

### 4.5 医疗 FHIR 类别

平台医疗资源不是上面的普通记录表，而是 FHIR JSON。当前称伴声明的 12 个类别为：

`过敏与不耐受`、`健康状态`、`检验结果`、`用药记录`、`个人资料`、`医护人员资料`、`孕期`、`诊疗操作`、`社会史`、`疫苗`、`就诊`、`临床生命体征`。

读取结果外层是 `MedicalResource`，关键字段包括：

- `id`：医疗资源 ID；
- `type`：平台医疗类别；
- `dataSourceId`：医疗数据源 ID；
- `fhirVersion`：FHIR 版本；
- `fhirResource.data`：FHIR JSON 字符串，`fhirResource.type` 和 `id` 由平台提取。

称伴当前对 FHIR 只保留经过白名单清洗的少量字段和数值，原始个人身份、Practitioner、自由文本备注等不能直接导出到 UI、日志或公开证据。医疗记录的时间可能缺失，缺失时应保留为“未注明日期”，不能硬编码成某一天。

`MedicalResource.type` 是 12 类资源的上层分类；底层 `FhirResource.type` 还是另一套 FHIR 资源类型（例如 patient、observation、condition、procedure、medication、encounter、location、organization 等）。`FhirResource.id` 只取自 FHIR JSON 的 `id`，并不保证全局唯一；真正定位医疗资源时应组合 `MedicalResourceId`（数据源 ID + FHIR 资源 ID）或使用平台返回的外层 ID，不能只拿 FHIR `id` 去重。

## 五、运动详情：平台能拿到什么

### 5.1 运动会话本体

`ExerciseSessionRecord` 是 `IntervalRecord`，除公共元数据外有：

| 字段 | 含义 |
| --- | --- |
| `exerciseType` | 运动类型整数，对应 `ExerciseSessionType` |
| `startTime` / `endTime` | 会话起止时间 |
| `startZoneOffset` / `endZoneOffset` | 起止时区偏移 |
| `title` / `notes` | 可选文字信息 |
| `segments` | 运动分段列表，可为空 |
| `laps` | 圈列表，可为空 |
| `route` / `hasRoute()` | 路线对象和“是否存在路线”的标志 |
| `plannedExerciseSessionId` | 可选的计划会话引用 |

API 36 的 `ExerciseSessionType` 有 62 个常量（含 `UNKNOWN`），覆盖步行、跑步、室内跑、骑行、游泳、力量训练、瑜伽、球类、滑雪、划船、轮椅等。不要把类型数字直接当展示文案，必须做稳定的枚举映射。

### 5.2 分段与圈

`ExerciseSegment`：

- `startTime`、`endTime`；
- `segmentType`：动作、休息、暂停、泳姿等类型；
- `repetitionsCount`：可选重复次数。

API 36 的 `ExerciseSegmentType` 约有 68 个常量（含 `UNKNOWN`）。分段适合展示间歇训练、组数和休息，不应把相邻分段强制拼成一个连续运动。

`ExerciseLap`：

- `startTime`、`endTime`；
- 可选 `length`（米）。

圈数据可能来自泳池、跑道或手动计圈。没有圈数据时返回空列表，不代表用户没有运动。

### 5.3 距离、热量、心率等为什么不在会话里

平台把运动会话和指标拆成独立记录：

- `DistanceRecord`：总距离；
- `ActiveCaloriesBurnedRecord`：活动能量消耗；
- `TotalCaloriesBurnedRecord`：总能量消耗；
- `StepsRecord`：步数；
- `HeartRateRecord`：心率采样；
- `SpeedRecord`：速度采样；
- `PowerRecord`：功率采样；
- `StepsCadenceRecord`、`CyclingPedalingCadenceRecord`：步频／踏频；
- 其他生命体征记录。

`ExerciseSessionRecord` 本身没有“距离字段”或“热量字段”，普通记录也没有通用的 `exerciseSessionId` 外键。若来源应用没有把上述记录写入 Health Connect，就无法从会话本身推导出真实值。

### 5.4 只能做“同来源、同时段关联”

可以做的是：

1. 选定一条运动会话；
2. 取同一 `DataOrigin`（来源包名）；
3. 查询与会话时间有重叠的指标记录；
4. 将关联结果标成“同来源，时间重叠”，并展示原始记录时间；
5. 对重叠区间做明确的展示策略，而不是假装平台给了确定外键。

不能做的是：

- 把不同来源的步数、距离、热量直接相加；
- 用体重、时长或距离自行估算不存在的原始热量；
- 依据同名记录或相近时间断言“平台认定属于这次运动”；
- 把同来源重叠记录跨来源合并成一条“全量运动详情”。

## 六、数据格式、单位与时间的称伴约定

### 6.1 数值单位

| 平台类型 | 读取 getter | 平台单位 | 称伴归一化 |
| --- | --- | --- | --- |
| `Mass` | `getInGrams()` | g | g |
| `Energy` | `getInCalories()` | cal | kcal（除以 1000；待实机校验原始单位） |
| `Length` | `getInMeters()` | m | m |
| `Volume` | `getInLiters()` | L | L |
| `Power` | `getInWatts()` | W | W |
| `Velocity` | `getInMetersPerSecond()` | m/s | m/s |
| `Pressure` | `getInMillimetersOfMercury()` | mmHg | mmHg |
| `Temperature` / `TemperatureDelta` | `getInCelsius()` | °C | °C |
| `BloodGlucose` | `getInMillimolesPerLiter()` | mmol/L | mmol/L |
| `Percentage` | `getValue()` | % | % |

`Energy` 的源码 getter 名字是 `getInCalories()`，平台文档把它表述为 calories；当前称伴除以 1000 后标记为 `kcal`，这依赖平台实际返回值是否以“千卡语义”写入。该换算目前只有本地映射测试，仍属于需要真机来源样例核对的风险点。读取新字段或改单位时必须有回归测试；在核实前不要把称伴显示值当成平台原始单位。

### 6.2 采样点和枚举

采样记录通常不是单个数值，而是一个列表；每个采样点至少包含时间。导出时可以拆成独立 samples，但必须保留采样时间、值和单位。枚举字段（如 `exerciseType`、`segmentType`、`mealType`、`flow`）应输出代码或稳定标签，不能把代码当连续值做均值。

### 6.3 时间与“今天”

称伴在内部统一使用：

```json
{
  "start": "2026-10-25T07:00:00Z",
  "end": "2026-10-25T07:30:00Z",
  "zoneOffsetSeconds": 28800
}
```

时间戳用 UTC 表达绝对时刻，另存当时偏移。今日累计如果当天还没结束，只能写“截至当前”，不能把当前累计当成全天最终值；没有记录的日期保留缺失状态，不填 0。

## 七、称伴当前归一化 JSON 示例

以下是称伴内部记录格式的合成示例，不是 Health Connect 原生返回 JSON。实际平台对象通过 Java/Kotlin getter 读取，医疗记录才使用 FHIR JSON。

```json
{
  "recordId": "4f1b8a7c9d2e",
  "category": "ExerciseSession",
  "source": "示例运动应用 · 1a2b3c4d",
  "start": "2026-10-25T07:00:00Z",
  "end": "2026-10-25T07:30:00Z",
  "zoneOffsetSeconds": 28800,
  "values": {
    "exerciseType": { "value": 33, "unit": "enum" }
  },
  "samples": [],
  "stages": [],
  "detail": {
    "segments": [],
    "laps": []
  }
}
```

同一会话的距离、步数和活动热量如果是独立记录，应分别保存并标记来源和时间范围；不要在 JSON 中凭空生成 `distance` 或 `activeCalories`。

## 八、称伴当前实际映射行为

当前代码的主链路是：

- `app/src/main/java/app/scalemate/health/HealthCatalog.kt`：声明平台类型、权限、隐私组和医疗类别；
- `HealthReader.kt`：分页读取普通记录、运动路线和平台聚合；
- `HealthMedicalReader.kt`：读取医疗资源并分页；
- `HealthRecordMapper.kt`：把原生 Record 映射为称伴 `HealthRecord`；
- `HealthMetrics.kt`：按日期、来源、单位和算法生成证据；
- `HealthJson.kt`：输出称伴内部快照 JSON；
- `HealthScreen.kt`、`HealthViewModel.kt`：展示、刷新、路线授权、导出和 AI 输入。

映射器的几个关键取舍：

1. 只读取平台 getter 的数值、采样、阶段和有限的嵌套结构；
2. 不把平台记录 ID、客户端记录 ID、来源包名原样写入普通导出；
3. 忽略标题、备注、餐名和若干身份引用；
4. 路线单独计算位置点和距离；
5. 原始明细写入临时 `health_raw` JSONL，作为导出和排障证据，缓存最多 24 小时，不是长期业务数据库；
6. 今日指标中的运动会话目前只使用 `recordCount`（记录条数），所以 UI 现在看起来像“只有次数”；这不等于平台没有运动详情，也不等于运动详情已持久化展示。

如果后续要做运动详情卡片，必须持久化结构化会话摘要，并单独保留来源、起止、分段／圈、关联指标的查询窗口和授权状态，不能只依赖临时 raw 文件。

## 九、常见错误理解

| 错误说法 | 正确边界 |
| --- | --- |
| “Health Connect 能拿到 Google Health 的全部数据” | 只能得到来源写入平台且用户授权的那部分 |
| “没有返回记录就是 0” | 可能是未授权、无记录、读取失败、平台不支持或来源没写入 |
| “运动记录里应该有步数和热量” | 运动会话与指标是不同 Record；没有统一外键 |
| “把多个来源步数相加就是今日步数” | 应优先用平台聚合，避免来源重叠和去重问题 |
| “路线 `null` 就是没有路线” | 可能是路线未授权或用户拒绝单独授权 |
| “枚举数字平均值有含义” | 枚举只能映射或计数 |
| “今天的累计就是全天最终值” | 未结束的一天只能称“截至当前” |
| “FHIR JSON 可以原样导出” | 医疗数据需清洗身份、来源和自由文本；当前称伴只保留白名单字段 |

## 十、后续运动详情展示建议

如果目标是对齐常见运动类 App，建议按以下顺序做：

1. 先显示会话的可靠事实：运动类型、开始／结束、总时长、来源和记录方法。
2. 有分段时显示动作、组数、休息和暂停；有圈时显示圈时长和圈距离。
3. 单独显示同来源、时间重叠的距离、步数、活动热量、心率、速度和功率，并标出关联规则。
4. 路线只有在授权成功且位置点非空时显示地图；授权失败显示明确原因，不画空轨迹。
5. 会话标题／备注如来源确实写入，再考虑在用户允许后展示，不要从本地餐食或猜测补全。
6. 对每个指标保留缺失、部分、失败和未授权状态，缺少字段时不要用体重或公式补算。

## 十一、Sources 与维护

平台行为以 Android API 36 类型定义、源码和当前代码为准：

- [读取健康数据](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [运动路线](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes)
- [运动与训练体验](https://developer.android.com/health-and-fitness/health-connect/experiences/workouts)
- [聚合数据](https://developer.android.com/health-and-fitness/health-connect/aggregate-data)
- [数据类型](https://developer.android.com/health-and-fitness/health-connect/guides/health-connect/data-and-data-types/data-types)

维护规则：

- API 版本升级时，先核对 `android-stubs-src.jar` 的 Record 字段、AggregationType、权限和医疗常量；
- 新增称伴类型时，同步更新 `HealthCatalog`、权限清单、单位映射、测试、本文章的“当前声明范围”；
- 不把一次模拟器读取成功或多个来源的空结果当成真机完整性证明；
- 本页描述的是数据能力与格式，不替代健康 AI 分析 PRD、验收记录或具体 UI 设计。
