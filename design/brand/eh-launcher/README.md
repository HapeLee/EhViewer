# EH / 正式与 Debug 图标

正式版采用最终确认的同色系杏橙方案；Debug 版采用同色系蓝紫配色。造型、加大矩形镂空、圆角与材质完全共用。

## 设计文件

- `release-icon` / `debug-icon`：圆角图标，SVG 与 1024 px PNG。
- `release-dark` / `debug-dark`：深色底版本。
- `release-square` / `debug-square`：完整方形底板，供平台施加遮罩。
- `release-mark` / `debug-mark`：透明背景主体，矩形窗口贯穿两层。
- `release-adaptive-foreground` / `debug-adaptive-foreground`：Android 108 dp 前景，432 px PNG。
- `release-debug-board.png`：正式与 Debug 对照。
- `adaptive-mask-preview.png`：按实际 Android 前景尺寸生成的圆形、圆角裁切预览。
- `adaptive-spacing-preview.png`：结合设备反馈制作的桌面边距调整前后对照。

几何源为相邻 `eh-link-tonal/build.cjs`，正式配色保持最终确认版；Debug 通过显式色值映射生成，不改变形状。

## Android 接入

- `src/main` 提供正式图标；`src/debug` 同名资源覆盖为蓝紫色，不依赖运行时代码或 Gradle 新配置。
- 自适应图标的前景与背景分离，圆角由系统裁切。彩色前景采用 432 px 透明 PNG，以保留接触阴影与细微高光；渐变底板、单色主题图标采用原生 VectorDrawable。
- 原稿以 0.145 倍缩放、(16.88, 16.88) 偏移进入 108 dp 视口。主轮廓约 45.2 × 51.4 dp，占中央 72 dp 可见区域高度约 71%，上下分别保留约 14% 的视觉边距。彩色、单色与裁切预览共用同一组缩放和偏移。自适应前景不预烘焙外轮廓投影，局部接触阴影保留。
- 浅色与深色底板均有 main/debug 对应资源；Debug 继承主资源中的几何与自适应 XML。
- 两个构建类型共用单色几何；启用系统主题图标时，颜色由系统主题决定。
- 各密度普通/圆形 WebP 与浅色/深色商店图同步生成。未改动电视横幅。

## 生成

需要 Node.js 与可解析到的 `sharp` 包。

```powershell
# 仅生成设计导出
node design/brand/eh-launcher/build.cjs

# 同时写入正式/Debug Android 图标资源
node design/brand/eh-launcher/build.cjs --install
```

Android 自适应图标尺寸与分层依据：[Android Developers](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

## 验证

- `:app:processDebugResources` 与 `:app:processReleaseResources` 均执行并通过。
- 资源合并记录确认 Debug 采用 `src/debug` 的蓝紫色前景，Release 采用 `src/main` 的杏橙色前景。
- 正式版导出与已确认图稿逐像素一致；Debug 与正式版的透明度通道一致，镂空保持透明。
- 设备反馈显示原先 0.18 倍缩放虽位于安全区，但约占可见区域 89% 高度，视觉边距偏小；本次缩小约 19.4%，保持居中。实际边界与圆形/圆角裁切已再次检查。
- 已检查圆形/圆角裁切与多尺寸预览；未进行设备安装验证。
