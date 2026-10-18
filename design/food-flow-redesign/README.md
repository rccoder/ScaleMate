# 饮食流程重构设计记录

> 2026-10-17，小米 24031PN0DC，称伴饮食开发版实机截图。对应当前真实功能，不是 Mock 原型。

## 本轮规则

- 饮食首页专注当天汇总和记录；AI 只在图片识别使用处按需选择或配置。
- 一餐由拍照、多图相册、添加食物组合；结果先在当前页核对，再明确加入餐食。
- 常吃和历史合并为食物库。常吃只是标记；常吃优先，同组按次数、最近使用排序。
- 普通条目最近 500 个，常吃额外保留。同名合并，常吃基准只有主动编辑才改。
- 新餐成功保存才计数；同一餐同名一次，编辑旧餐、取消均不计数。
- 2 人均分后吃自己那份的 1/3，即整份的 1/6；明确整份 1/3 时按整份算。

## 截图索引

| 截图 | 场景 |
| --- | --- |
| [50-food-home-unified](screenshots/50-food-home-unified.png) | 饮食首页：不放 AI 或常吃／历史并列入口 |
| [51-meal-three-actions](screenshots/51-meal-three-actions.png) | 一餐入口：自动日期餐别与三个添加动作 |
| [52-food-library](screenshots/52-food-library.png) | 统一食物库：全部／常吃筛选与手动入口 |
| [53-multiple-photo-context](screenshots/53-multiple-photo-context.png) | 两张图片缩略图与分食标签 |
| [54-personal-portion-context](screenshots/54-personal-portion-context.png) | 补充说明与个人份量计算提示 |
| [55-analysis-progress](screenshots/55-analysis-progress.png) | 当前页分析状态与取消 |
| [56-analysis-results-top](screenshots/56-analysis-results-top.png) | 分析完成留在当前页，默认摘要、当页展开编辑 |
| [58-meal-confirmed-once](screenshots/58-meal-confirmed-once.png) | 明确确认后才加入这一餐 |
| [59-personal-serving-edited](screenshots/59-personal-serving-edited.png) | 清空重输克数：50g 对应 78kcal |
| [62-library-review-with-photos](screenshots/62-library-review-with-photos.png) | 复用常吃的基准与两张缩略图 |
| [64-analysis-canceled](screenshots/64-analysis-canceled.png) | 取消分析后保留照片和描述，显示中断 |
| [65-interrupted-draft-restored](screenshots/65-interrupted-draft-restored.png) | 重启恢复后仍显示中断，可主动重试 |
| [66-final-food-home](screenshots/66-final-food-home.png) | 清理临时餐食和草稿后的已同步样例 |
| [67-library-usage-sorted](screenshots/67-library-usage-sorted.png) | 水煮蛋使用 2 次排前，基准仍为 25g/39kcal |
| [68-manual-addition](screenshots/68-manual-addition.png) | 食物库进入手动添加，返回仍回食物库 |

## 复验与边界

实机从系统选择器勾选两张公开样例、追加与移除缩略图、当前页分析、存为常吃、按克数修改、保存同步、食物库复用、取消／重启／重新分析、确认不重复添加、已有项修改替换、手动取消返回均已操作。最后保留一餐水煮蛋 50g/78kcal 的开发样例，已同步；无未完成草稿。该餐仅用于功能验证，不代表用户实际摄入。

水煮蛋常吃基准保持 25g/39kcal，次数为 2，缩略图 2 张；删除旧样例餐食不会回退历史使用次数。旧版本已有的常吃样例按迁移保留。

两张照片是同一张公开图片的重复副本：实际返回米饭与水煮蛋两项，没有按照片重复计入；这不等于已完成不同角度、不同光线的去重准确率评测。未覆盖机型矩阵和物理相机拍摄，不作为正式发布验收。更多检查见 [功能验收](../../docs/testing/饮食功能实现与验收.md)，系统选图细节见 [内部测试操作](../../docs/testing/Android%20实机图片选择操作.md)。

## 照片许可

本轮含食物照片的截图使用 [Boiled egg with rice](https://commons.wikimedia.org/wiki/File:Boiled_egg_with_rice.jpg)，作者 Roderick Suñaz Sumalinog，[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)。照片经过压缩、裁剪显示及元数据移除；照片部分按原许可分享。未归档用户私人相册网格、密钥或原始响应。

## 图片圆角调整

饮食添加、分析／确认页和食物库图片统一使用与食物卡片相同的 22dp 圆角，复用同一形状定义。开发版构建并更新安装，食物库实机显示已核对：[圆角缩略图](screenshots/69-rounded-library-images.png)。

## 添加入口与返回逻辑优化

“记录食物”页统一入口与标题，添加方式收进底部三选一。手动记录直接填表，也可从食物库选择。未产生分析结果时返回丢弃本次图片批次，不影响已确认的其他食物；已有分析结果仍先提示。总计仅在有食物时出现。下表为这一轮最新截图，前面的截图保留历史状态。

| 截图 | 内容 |
| --- | --- |
| [71-record-food-editor](screenshots/71-record-food-editor.png) | 记录食物页面：单一添加按钮，无食物时没有总计卡 |
| [72-add-food-chooser](screenshots/72-add-food-chooser.png) | 底部三选一：拍照、相册、手动记录 |
| [73-photo-header-remove-icons](screenshots/73-photo-header-remove-icons.png) | 标题旁弱化 AI 服务说明、图片右上角 × |
| [74-back-clears-photo-addition](screenshots/74-back-clears-photo-addition.png) | 两张图未分析返回后清空本次添加 |
| [75-manual-after-photo-back](screenshots/75-manual-after-photo-back.png) | 返回后可以直接进入手动记录 |
| [76-meal-total-labeled](screenshots/76-meal-total-labeled.png) | 已有食物时明确显示本餐总计 |
| [77-empty-meal-no-total](screenshots/77-empty-meal-no-total.png) | 移除最后一项后隐藏总计 |

实机通过：两张图片未分析返回后转手动；取消系统相机不留下空批次；手动页可转食物库并加入食物；有食物显示总计、移除最后一项隐藏总计。本轮仅操作草稿，没有新增正式餐食或 Health Connect 写入。

## 2026-10-18 操作优先级优化

统一弹层底色，图片后以大“＋”追加拍照或相册；结果卡突出编辑营养和移除，收藏改为右上角星标；手动记录突出食物库入口。重新分析先进入分析说明编辑，不声称修改模型系统提示词。以下为最新实机截图，前文图片保留历史状态。

| 截图 | 内容 |
| --- | --- |
| [78-unified-add-sheet](screenshots/78-unified-add-sheet.png) | 添加弹层整面统一底色 |
| [79-prominent-library-entry](screenshots/79-prominent-library-entry.png) | 手动记录里的食物库整行入口 |
| [80-result-nutrition-actions](screenshots/80-result-nutrition-actions.png) | 结果卡编辑营养、移除与右侧收藏星标 |
| [81-inline-nutrition-editor](screenshots/81-inline-nutrition-editor.png) | 在当前卡片展开营养编辑 |
| [82-reanalysis-explanation](screenshots/82-reanalysis-explanation.png) | 修改说明再分析的明确提示 |
| [83-add-image-tile](screenshots/83-add-image-tile.png) | 图片序列末尾大加号追加块 |
| [84-add-images-sheet](screenshots/84-add-images-sheet.png) | 追加图片仅有拍照／相册两项 |
| [85-image-removed](screenshots/85-image-removed.png) | 图标移除一张后保留追加块 |
| [86-unselected-star](screenshots/86-unselected-star.png) | 未收藏时的空心星标 |

实测通过：添加弹层三选一、图片追加弹层两选一、右上角 × 删除、返回丢弃未分析批次、手动转食物库、当前页展开／收起营养、星标取消与恢复、移除最后一个待添加结果返回记录页。用户已确认原未保存草稿为测试数据并允许丢弃；测试结束不保留该草稿，也未新增正式餐食。重新分析说明弹窗已核对，本轮未重复调用模型，不新增营养准确率结论。

构建通过，45 项单元测试通过（新增移除待添加结果的原子状态测试），363 对中英文资源一致。图片署名沿用本页公开样例许可说明。

## 两阶段复用同一食物卡片

“确认吃了什么”和“记录食物”两页共用 `FoodItemCard`：食物名、热量与份量、宏量、右上角收藏星标，以及大“编辑营养”按钮和小“移除食物”按钮。估算说明也沿用同一排版。分析结果页编辑在卡内展开；记录页编辑仍进入核对页，确认后替换原项。

开发版构建、安装通过，实机核对了记录页现有草稿的卡片；未修改或清理用户餐食内容。[记录页共用卡片截图](screenshots/87-shared-meal-food-card.png)。

## 图片细节与系统语言验证

图片及追加块采用 12dp 圆角，卡片仍为 22dp；右上角移除改为内收的半透明深色小圆钮。实机选择两张公开样例、删除一张并重新分析，返回“米饭”“水煮鸡蛋”及中文估算说明，验证系统中文语言传入实际分析链路。

| 截图 | 内容 |
| --- | --- |
| [88-image-corners-and-close](screenshots/88-image-corners-and-close.png) | 两张图片的新圆角与移除按钮 |
| [89-small-close-removes-photo](screenshots/89-small-close-removes-photo.png) | 移除一张后保留追加入口 |
| [90-system-language-analysis](screenshots/90-system-language-analysis.png) | 新分析按系统语言输出中文名称与说明 |

49 项单元测试通过；OpenAI 与 Anthropic 两种格式分别验证中英文输出，共四次真实识图请求通过。测试结果仅作语言与数据合同验证。用户授权丢弃旧英文测试草稿；本轮新增分析也已放弃，未保存餐食或写入 Health Connect，公开相册夹具已清理。截图中的公开图片沿用本页许可署名。


## 2026-10-18 估算依据与食用建议

卡片下方分为“AI 估算依据”和“食用建议”。建议按内容显示“本次／下次”；没有建议但有依据充分的正向反馈时，直接显示反馈。全部为空隐藏区域。分析核对与记录食物沿用同一卡片，加入餐食后保留建议。

| 截图 | 内容 |
| --- | --- |
| [91-ai-basis-positive-feedback](screenshots/91-ai-basis-positive-feedback.png) | 估算依据小标题；只有正向反馈，不显示本次／下次标签 |
| [92-empty-advice-hidden](screenshots/92-empty-advice-hidden.png) | 鸡蛋未返回建议或反馈，隐藏整个建议区域 |
| [93-ai-now-and-next-advice](screenshots/93-ai-now-and-next-advice.png) | 补充测试说明后重新分析，分别展示本次／下次建议 |
| [94-meal-card-advice-reused](screenshots/94-meal-card-advice-reused.png) | 加入餐食后的记录页复用相同卡片与建议 |

以上为真实 AI 返回，使用已署名的公开样例图。第二次分析补充“准备加酱油、经常吃这餐”的测试情境，用于验证即时和下次建议，不代表用户实际习惯。实机通过反馈、空区隐藏、两类建议与确认加入流程；测试餐食草稿已丢弃，未保存正式餐食或写入 Health Connect，公开相册图片已清理。

50 项单元测试、11 项实机存储测试通过；OpenAI／Anthropic × 中文／英文四次识图语言与结构合同测试通过，367 对中英文资源校验通过。测试中发现输出夹杂另一语言后，补强语言政策及输入末尾的应用语言要求，并完成上述复测；不能据此保证任何模型始终遵循指令。截图不作为营养准确率评测。


## 2026-10-18 估算依据体现标签与补充说明

每份食物新增分析输入快照及 AI 对条件的理解。显示分食人数、标签、原始说明、比例基准及应用实际采用的整份 → 本人算式，保留原模型对做法／份量的不确定性描述。手动编辑不改写原快照，提示当前值以编辑后为准；旧记录不补造输入。

| 截图 | 内容 |
| --- | --- |
| [95-sharing-tags-input](screenshots/95-sharing-tags-input.png) | 选择两人均分、少酱，补充米饭吃自己那份的 1/3 |
| [96-sharing-result-overview](screenshots/96-sharing-result-overview.png) | 结果卡显示个人摄入及实际输入 |
| [97-sharing-input-and-calculation](screenshots/97-sharing-input-and-calculation.png) | AI 解释输入适用范围，应用展示整份 ÷ 2 × 1/3 的实际换算 |

实机公开图片测试：米饭整份估算 300g／390kcal，最终 50g／65kcal；鸡蛋仅按两人分食，不套用只针对米饭的 1/3。AI 明确少酱无法从照片确认，不当作视觉已验证事实。这里只核对输入解释及换算，不代表估算准确率。原始测试说明为英文，AI 解释仍遵循系统中文。用户已有餐食草稿保留，仅放弃此次未确认的测试批次，未保存餐食或写入 Health Connect。公开相册夹具已清理。

51 项单元测试、12 项实机存储／解析测试通过；两种 API 格式 × 中英文四次识图测试通过，均返回补充条件解释及分食快照。377 对中英文资源校验通过，开发版已安装并复制到手机 Download。公开图片许可沿用本页署名。


## 2026-10-18 默认摘要与按需展开（最新）

替代上一版全量展开：卡片默认只保留一句估算摘要，优先表达实际分食与食用比例，例如“分析时已按 2 人均分，再计入你那份的 1/3”。“查看估算详情”在当前卡片展开三组内容：你提供的信息 → AI 如何理解 → 如何算到这一份；输入原文、算式和历史快照提示移入详情。关键食用比例歧义有独立警示字段，保持默认可见；食用建议独立显示。编辑营养与详情展开互不绑定。

| 截图 | 内容 |
| --- | --- |
| [98-estimate-summary-collapsed](screenshots/98-estimate-summary-collapsed.png) | 默认一句摘要，保留查看详情入口与食用建议 |
| [100-estimate-details-three-groups](screenshots/100-estimate-details-three-groups.png) | 展开后按输入、理解和计算分组；底部保留分析快照说明 |

实机公开图片重新分析验证默认折叠、展开、收起；收起后不再展示详情分组。两页共用组件，记录页已有旧数据正常显示摘要与详情入口。仅放弃本轮未确认测试批次，用户已有餐食草稿保留，未保存正式餐食或写入 Health Connect。相册公开夹具已清理，图片许可沿用本页署名。

51 项单元测试、12 项实机存储／解析测试、两种 API 格式 × 中英文四次识图测试通过，387 对中英文资源一致。新版已安装并复制到手机 Download。本轮截图核对信息层级与操作，不评价照片营养估算准确率。


## 2026-10-18 AI 直接返回本人摄入（最新，替代应用分食换算）

根因核对：旧结果只有两人均分、无额外说明，但模型同时返回本人份额 1/2，应用再按人数相除，实际保存为整份 1/4。此前“应用依据结构化分食与比例计算”的方案由本节取代。

照片、人数、标签、个人份量／没吃成分等说明整体交给 AI，AI 直接返回 `personalGrams`、`personalNutrients` 及一句本人摄入摘要。应用只校验、显示、汇总、保存，不再自动除人数或乘食用比例。新 AI 食物的 factor 固定 1；旧比例字段即使被夹带也不改变返回的本人营养。用户主动改克数时仍可按现有营养密度编辑。

默认摘要改为 AI 返回的 `intakeSummary`；详情按“你提供的信息／AI 如何理解／本次记录的摄入”组织，最后一组展示 AI 本人摄入快照。展开、收起使用 Material 标准上下图标，替代文字符号。历史旧详情保留兼容，旧待确认结果提示重新分析。

| 截图 | 内容 |
| --- | --- |
| [107-direct-personal-intake-summary](screenshots/107-direct-personal-intake-summary.png) | 两人均分默认吃完本人份额；标准展开图标 |
| [108-direct-intake-details-native-icon](screenshots/108-direct-intake-details-native-icon.png) | 标准收起图标、输入与理解、AI 返回的本人摄入快照 |

实机使用原待确认餐食的照片和两人均分输入重新分析；旧结果在仓库外私有目录备份。重新分析后仍是 4 项待确认食物，均为 personal 合同，当前营养与返回快照一致且没有应用二次分食系数；未确认保存或写入 Health Connect。截图仅保留卡片文字，未归档私人原图、原始响应或数据库。

52 项单元测试、17 项实机存储／协议及场景测试通过；固定响应回归验证 AI 返回 360kcal 时不会再次变为 180kcal。真实两种格式各覆盖单独吃、两人均分、个人米饭／没吃蘸料三种情境，共六次识图；中英文四次语言测试最终通过，其中首次补充语言测试有一次返回格式不符合合同被拒绝，未改代码重试后通过，保留此不稳定性记录。以上验证数据通路、合同与场景理解，不是营养或菜名准确率评测，照片估算仍需用户确认。

AI 服务新功能截图见[设置设计记录](../settings-redesign/README.md)。
