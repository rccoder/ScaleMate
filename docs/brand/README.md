# 称伴品牌图片

Logo 沿用 App 的 `app/src/main/res/drawable/ic_scale.xml` 路径与颜色。

- `logo.svg`：README 使用的矢量 Logo。
- `logo.png`：512 × 512 PNG。
- `social-preview.svg`：GitHub 分享预览图的可编辑矢量源文件。
- `social-preview.png`：1280 × 640 PNG，适合 GitHub Social Preview。

设置预览图：打开仓库 Settings → General → Social preview → Edit → Upload an image，选择 `social-preview.png`。将图片提交到仓库不会自动更新 Social Preview。

PNG 从 SVG 渲染，可用 librsvg 的 `rsvg-convert` 重新生成：

```sh
rsvg-convert -o docs/brand/logo.png docs/brand/logo.svg
rsvg-convert -o docs/brand/social-preview.png docs/brand/social-preview.svg
```

参考：[GitHub 社交预览图片说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview)。
