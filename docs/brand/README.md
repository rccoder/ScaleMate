# 称伴品牌图片

Logo 沿用 App 的 `app/src/main/res/drawable/ic_scale.xml` 路径与颜色。

- `logo.svg`：独立矢量 Logo。
- `logo.png`：512 × 512 PNG。
- `social-preview.svg`：分享预览图的可编辑源文件，内嵌真实实机截图。
- `social-preview.png`：3840 × 1920 高清 PNG（1280 × 640 画布的 3 倍导出），用于 README 首屏和 GitHub Social Preview。

当前分享图以“记下体重，也记下每一餐”为主文案，副文案明确“上秤自动读取，拍照估算餐食营养”，并以编辑式文字说明饮食与体重写入 Health Connect；Google Fit 等应用获得读取权限后即可查看。蓝牙称重与 AI 饮食并列，AI 仅对应饮食功能。横版与竖版小红书素材共用单一浅绿背景、无卡片信息区和前后错叠实机页面；左侧文字按“主张 → 同步能力 → 使用条件”收紧为连续节奏。2026-10-18 更新。

右侧使用两张实机截图，按同一比例缩放，截图铺满统一圆角的展示外框，不保留额外白色底边；不修改页面文案、体重或营养数据：

- [称重稳定后自动保存](../screenshots/current/20-weight-countdown.png)：当前开发版实机截图，显示 71.00 kg、5 秒倒计时、最近一次已写入 Health Connect 的记录，以及当前的「记录 / 饮食 / 称重 / 设置」导航。
- [AI 饮食营养确认](../screenshots/current/19-food-photo-nutrition.png)：用户指定的手机相册内实机截图，展示多张餐食照片、青椒炒牛肉的本人摄入、编辑营养、估算依据与食用建议。用户于 2026-10-18 确认用于分享图与 README；营养数值属于估算示例。

README 的称重截图组另用 [称重记录](../screenshots/current/23-weight-records-current.png)，只展示体重、测量时间与 Health Connect 状态，避免在称重介绍里混入饮食内容；[设备管理](../screenshots/current/22-devices-current.png) 只展示型号和操作，不展示蓝牙地址。

SVG 将两张 1080 × 2400 原始截图嵌入为 data URI，渲染时无需外部图片文件；不要嵌入已经缩小过的分享图或预览图。PNG 以 3 倍画布尺寸导出，保留高清屏与放大查看所需的像素；缩略卡片中的小字受展示面积限制，不能用增加像素保证全部可读。README 引用渲染后的 PNG；更新源图后同时重新生成 PNG，并检查全尺寸与 640 像素宽度下的文字、截图和版面。

设置预览图：打开仓库 Settings → General → Social preview → Edit → Upload an image，选择 `social-preview.png`。将图片提交到仓库不会自动更新 Social Preview。

PNG 从 SVG 渲染，可用 librsvg 的 `rsvg-convert` 重新生成：

```sh
rsvg-convert -o docs/brand/logo.png docs/brand/logo.svg
rsvg-convert -w 3840 -h 1920 -o docs/brand/social-preview.png docs/brand/social-preview.svg
```

参考：[GitHub 社交预览图片说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview)。

## 小红书竖版素材

- `xiaohongshu-3x4.svg`：可编辑源文件，内嵌称重与饮食实机截图。
- `xiaohongshu-3x4.png`：2160 × 2880 高清 PNG，竖版 3:4，适合小红书首图。

这张图把蓝牙自动称重与 AI 饮食记录放在同一张画面里。两张完整实机页面上下错落并适度重叠；前置饮食页使用柔和投影与白色边缘高光，与后置称重页形成空间层次。页面自身标题已经说明功能，不再叠加额外标签。右上角采用无卡片的编辑式排版，说明饮食与体重写入 Health Connect，Google Fit 等应用获得读取权限后即可查看。文案为“记下体重，也记下每一餐”。AI 只对应饮食记录；截图中的营养数值为估算示例。

更新截图或文案后重新导出：

```sh
rsvg-convert -w 2160 -h 2880 -o docs/brand/xiaohongshu-3x4.png docs/brand/xiaohongshu-3x4.svg
```
