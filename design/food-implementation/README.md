# 饮食功能实机设计记录

日期：2026-10-17。以下为当前项目真实饮食开发版的实机截图，区别于 [`../ai-prototype/`](../ai-prototype/README.md) 中的纯 Mock 原型。

餐食使用正常名称，均为功能验收样例，不代表实际摄入。详细实现、测试结果与未覆盖范围见 [饮食功能实现与验收](../../docs/testing/饮食功能实现与验收.md)。图片未拼接或重绘；长页面按当前滚动位置截图。

| 页面 / 状态 | 截图 |
| --- | --- |
| 首次进入，记录与同步说明 | [00-record-welcome.png](screenshots/00-record-welcome.png) |
| 记录首页空状态 | [01-records-empty.png](screenshots/01-records-empty.png) |
| 称重未绑定，按需添加 | [02-weigh-unbound.png](screenshots/02-weigh-unbound.png) |
| 新建餐食 | [03-meal-editor-empty.png](screenshots/03-meal-editor-empty.png) |
| 自己那份的 1/3，折算 100 kcal | [08-own-serving-100kcal.png](screenshots/08-own-serving-100kcal.png) |
| 修改米饭后已同步 | [09-meal-updated-synced.png](screenshots/09-meal-updated-synced.png) |
| 公开照片与辅助标签 | [10-photo-context.png](screenshots/10-photo-context.png) |
| 发送图片前的用途同意 | [11-ai-consent.png](screenshots/11-ai-consent.png) |
| 真实服务返回米饭等估算 | [12-ai-estimates.png](screenshots/12-ai-estimates.png) |
| AI 食物的份量确认 | [13-ai-serving-review.png](screenshots/13-ai-serving-review.png) |
| 存为常吃 | [14-save-favorite.png](screenshots/14-save-favorite.png) |
| 图片餐食确认并写入 | [15-photo-meal-synced.png](screenshots/15-photo-meal-synced.png) |
| 未授权仍先保存本机 | [18-local-save-guide.png](screenshots/18-local-save-guide.png) |
| 本地保存，等待授权 | [19-pending-permission.png](screenshots/19-pending-permission.png) |
| 重启后的记录首页 | [20-records-after-restart.png](screenshots/20-records-after-restart.png) |
| 恢复权限后重试写入 | [21-sync-after-permission.png](screenshots/21-sync-after-permission.png) |
| 没有图片模型时的下一步 | [22-no-image-ai-guide.png](screenshots/22-no-image-ai-guide.png) |
| 无 AI 转手动录入 | [23-manual-without-ai.png](screenshots/23-manual-without-ai.png) |
| 服务配置与能力路由 | [24-ai-services.png](screenshots/24-ai-services.png) |
| 修正热量格式后的记录首页 | [25-final-records.png](screenshots/25-final-records.png) |
| 修正份量说明后的常吃食物 | [26-final-favorites.png](screenshots/26-final-favorites.png) |
| 复用已确认的 90 g 米饭，热量不重复折算 | [27-final-reused-food.png](screenshots/27-final-reused-food.png) |
| 同时删除本地与健康记录确认 | [28-delete-both-confirmation.png](screenshots/28-delete-both-confirmation.png) |

截图来自验收过程，后续修正了复用份量的说明与返回操作；不将过程截图当作最终发布版像素规范。

公开食物图片：[Boiled egg with rice](https://commons.wikimedia.org/wiki/File:Boiled_egg_with_rice.jpg)，Roderick Suñaz Sumalinog，许可 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)。展示前压缩并移除元数据；含照片截图的照片部分沿用原许可。没有归档手机相册页面或用户私人照片。
