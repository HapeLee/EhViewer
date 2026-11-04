# EH / 杏橙 × 薄荷青 · 圆孔版

沿用叠层几何与轻浮雕材质，将上层改成杏橙渐变、下层改成薄荷青渐变。右下角窗口改成贯穿两层的真实圆形镂空。

- 圆心为 (332, 292)，半径为 25，位于 512 × 512 原稿的右下区域。
- 上下两层均以 even-odd 路径开孔；主体和自身投影同时应用圆孔遮罩，避免阴影填进孔中。
- 独立主体 PNG 的圆孔中心完全透明；浅色和深色图标中分别透出对应底板。
- 保留圆角、连续渐变、轻薄侧边和接触阴影。

`eh-link-icon` / `eh-link-dark` 为浅色、深色图标；`eh-link-app-square` 为不预裁圆角的方形版；`eh-link-mark` 为透明背景主体；`eh-link-mono` 为单色版。均包含 SVG 和 1024 × 1024 PNG。

另有多尺寸 PNG、`eh-refinement-board` 展示板及 `eh-size-check` 尺寸检查。SVG 使用可编辑路径、渐变和滤镜，无嵌入位图。

需要 Node.js 和可解析到的 `sharp` 包，运行 `node design/brand/eh-link-apricot/build.cjs` 可重新生成。旧配色保留在相邻目录，应用资源未替换。
