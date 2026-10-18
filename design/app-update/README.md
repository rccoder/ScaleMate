# 应用更新界面实机记录

2026-10-18，Xiaomi 24031PN0DC，1080×2400，均为手机实机截图。01–04、06–10 使用含更新器的本地固定签名构建 `0.2.1 / code 5`，与公开同版本号原版不同，不替换既有 Release 附件；11 为公开预览 code 6，12 为独立饮食开发版 code 7，13–15 记录公开预览到正式 code 7 的真实升级。

| 图片 | 场景与结果 |
| --- | --- |
| [01 软件更新](screenshots/01-update-page.png) | 设置 → 当前版本；沿用与设置页相同的 24dp 横向边距；检查结束显示当前通道无可安装更高版本 |
| [02 预览确认](screenshots/02-preview-warning.png) | 点击预览版显示风险；取消后不改变正式通道 |
| [03 线路列表](screenshots/03-download-routes.png) | 官方＋五条公共代理；B／C 提示较慢；此图为加入低流量测速前的列表 |
| [04 小流量测速结果](screenshots/04-route-probe-results.png) | 新测速弹窗：样本 Tag、时间、64 KB 速度与耗时；本轮 A 最快、B 暂不可用；自动选线保持不变 |
| [06 预览更新](screenshots/06-preview-update-available.png) | 本地 code 5 通过签名清单发现公开预览 code 6 |
| [07 设置提醒](screenshots/07-settings-update-reminder.png) | 当前版本行与设置导航显示新版本提醒 |
| [08 安装授权](screenshots/08-update-install-permission.png) | 正常 Android 未知应用安装授权页，初始为关闭 |
| [09 系统来源确认](screenshots/09-preview-system-confirmation.png) | 系统显示称伴尝试安装更新，可取消并重试已下载文件 |
| [10 系统覆盖升级](screenshots/10-preview-system-upgrade.png) | 系统显示 0.2.1 → 0.3.0-preview.1，来源称伴 |
| [11 预览安装完成](screenshots/11-preview-installed-no-downgrade.png) | 显示预览 code 6，阻止降级切回较旧正式版 |
| [12 橙色开发版图标](screenshots/12-fooddev-orange-icon.png) | 系统应用信息显示橙色秤形图标与“称伴 · 饮食开发版”；同包名同签名覆盖到 0.3.0，未卸载或清数据 |
| [13 正式更新候选](screenshots/13-stable-update-available.png) | 预览 code 6 在预览通道发现更高正式 code 7 |
| [14 正式覆盖升级完成](screenshots/14-stable-system-upgrade.png) | 正常系统安装完成页显示 0.3.0-preview.1 → 0.3.0，绿色图标，来源称伴 |
| [15 正式版与偏好恢复](screenshots/15-stable-installed-restored.png) | 正式 code 7 显示已是最新；恢复正式通道和自动选择线路 |

更新器协议与早期界面实测见 [实现验收](../../docs/testing/应用更新实现与验收-2026-10-18.md)，公开附件、两次升级与清理结果见 [双通道发布验收](../../docs/testing/应用更新双通道发布与升级验收-2026-10-18.md)。截图不含餐食、体重、私人照片或凭据；测试后已恢复正式通道、自动选线与原屏幕超时。

20:54–20:57 补验测速弹窗，确认结果完整显示、可重测，点击最快 A 后页面回显 A，完成后恢复“自动选择”。试图验证停止时，测速均已在 UI 自动化再次抓取节点前结束，未实际点到停止；取消逻辑由已有 JVM 反例覆盖，本轮不宣称停止按钮已实操通过。
