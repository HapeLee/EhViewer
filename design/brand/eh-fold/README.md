# EH / Fold · 折面

以 E 和 H 组合成一个等距视角的六边形标志。左侧 E 的三条横臂沿折面下倾，右侧 H 沿折面上倾；共用中轴，顶部用菱形负空间保留通透感。三档蓝色取自 Matter 的 Fresh blue 方案。

## 文件

- `eh-design-board.png` / `.svg`：主方案、深色底、单色及实心备选的展示板。
- `eh-mark.svg`：透明背景的彩色主标志，512 × 512，三个可编辑路径。
- `eh-mono.svg` / `eh-reversed.svg`：深色单色、白色反白标志，透明背景。
- `eh-icon.svg` / `eh-icon-dark.svg`：浅色、深色圆角图标；对应 PNG 为 1024 × 1024。
- `eh-icon-48.png` / `96` / `192` / `512`：不同尺寸的浅色图标。
- `eh-solid-study.svg`：封闭顶面的实心备选。
- `eh-size-check.png` / `.svg`：24、32、48、64 px 与桌面形状预览。
- `android/eh_launcher_foreground.xml`：与主标志同源的 Android 彩色 VectorDrawable。
- `android/eh_launcher_monochrome.xml`：同源的 Android 单色 VectorDrawable。

## 几何与颜色

原稿视口 512 × 512，轮廓外接范围 x=104…408、y=80…432；构图中心 (256, 256)。顶部留白、E 的横臂间距和 H 的竖笔间距均为真实透明负空间，无白色补丁、位图、字体、滤镜或渐变。

| 折面 | 色值 |
| --- | --- |
| 顶面 | `#B4D1EB` |
| E / 左面 | `#82AFD6` |
| H / 右面 | `#567FA9` |
| 单色 | `#263F56` |
| 深色背景 | `#172531` |

Android 前景使用 108 × 108 视口，缩放 0.18、偏移 (7.92, 7.92)，标志所有顶点均位于以 (54, 54) 为圆心、半径 33 的圆内。背景应由 adaptive icon 单独提供；VectorDrawable 本身不含圆角背景。此目录只交付设计资源，未替换应用的 launcher 资源，也未进行设备安装验证。

## 重新生成

需要 Node.js 和可由 Node 解析到的 `sharp` 包。运行：

```powershell
node design/brand/eh-fold/build.cjs
```

`build.cjs` 中的几何数据是 SVG、PNG 和 Android VectorDrawable 的共同来源。修改几何或色值后重新运行即可。
