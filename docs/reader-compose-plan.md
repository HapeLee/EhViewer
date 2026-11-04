# 阅读器出壳方案（P8 / R 系列）

> 前置：`app-navigation-host-plan.md` 的 P7-a ~ P7-f 已全部落地，宿主、抽屉、Snackbar、
> 返回栈都已收敛到 NavHost。**阅读器是唯一没上 NavHost 的页面**，也是仓库里最后一块
> Activity 级 UI。
>
> 本文写于 2026-10-31，依据是仓库当前状态（第十五个到第十九个落地片之后）与
> `D:\Project\legado-with-MD3` 的漫画阅读器实现。

## 0. 结论摘要

1. **阅读器已经写好一半，但没接线。** `ui/reader/` 下有 `PageSource`（页数据源抽象）
   与 `ReaderScreen`（`HorizontalPager` + telephoto 缩放），全仓零调用；实际阅读仍走
   `GalleryActivity`（1300+ 行，`GLRootView` 自绘 GL 渲染）。
2. **参考 legado 的漫画阅读器（`ui/book/manga/`），不要参考它的 Compose 文本阅读器。**
   后者（`docs/dev/track-c-compose-reader-plan.md`）在真机上跑出 jank 50% vs 4.13%，
   2026-07-25 已整轨删除。这条反证比任何正面范本都值钱。
3. EhViewer 的阅读器**没有排版引擎**——一页就是一张图，不存在「逐行画字」。所以
   legado Track C 的性能结论不直接适用，但它的另一条教训适用：
   **双栈只允许存在一个落地片的时长。**
4. 拆成 R1–R8，R1 只做接线、不改任何交互，先让「新入口能打开、能翻页、能返回」跑通。
   R6 只补设置项接线（`read_theme` / `start_position` / `SCALE_FIXED` / 栏自动隐藏 /
   `volume_page_interval`），见 §15；R7 把「设置 → 阅读设置」这个第二入口与阅读器内面板
   对齐、清掉随 GL 栈一起失效的开关，见 §16；R8 补回点击区手势，见 §17。

## 1. 现状盘点（事实）

| 项 | 现状 |
| --- | --- |
| `ui/GalleryActivity.kt` | 1300+ 行；`setContentView(R.layout.activity_gallery)`；`GLRootView` + `GalleryView` + `GalleryProvider` 自绘 GL；`nativeTexImage` 512×512 纹理上传 |
| `ui/reader/ReaderScreen.kt` | 已实现：`Scaffold` + `HorizontalPager` + `ZoomableImage`；全仓零调用 |
| `ui/reader/PageSource.kt` | 只有 `ArchivePageSource`（压缩包 → 解到缓存文件）；**在线 / 已下载两种来源没有实现** |
| `ui/compose/nav/EhRoute.kt` | 14 个 route，**没有 Reader** |
| 入口 | **5 处**直接 `startActivity(GalleryActivity)`：历史、下载、画廊列表、画廊详情（带 `KEY_PAGE`）、预览图（带 `KEY_PAGE`） |
| 阅读设置 | `Settings.kt` 里 10 项：`screen_rotation`、`reading_direction`、`page_scaling`、`read_theme`、`show_page_interval`、`turn_page_interval`、`volume_page`、`volume_page_interval`、`reverse_volume_page`、`reading_fullscreen` |
| 进度 | **没有持久化**。`GalleryActivity` 只把 `mPage` / `mCurrentIndex` 存进 `savedInstanceState`（`KEY_PAGE` / `KEY_CURRENT_INDEX`）；`EhDB.putHistoryInfo()` 只记访问时间，不带页码 |

`GalleryActivity` 现有能力（从方法名清点，作为 parity 基线）：

- 翻页：`pageTurn()`、`autoTransfer()` / `startAutoTransfer()` / `stopAutoTransfer()`
- 进度：`updateProgress()`、`updateSlider()`、`showPageDialog()`（跳页）
- 手势：`onTapMenuArea()`、`onTapSliderArea()`、`onTapErrorText()`、`onLongPressPage()`
- 图片：`shareImage()`、`copyImage()`、`saveImage()`、`saveImageTo()`
- 显示：`setScreenLightness()`（亮度）、`setOrientation()`、`GalleryGuideView`（首次引导）
- 输入：`onKeyDown()` / `onKeyUp()`（音量键翻页）、`onProvideAssistContent()`
- 来源：`ACTION_EH` + `GalleryInfo` → `EhGalleryProvider`；`ACTION_VIEW` + `Uri` →
  `ArchiveGalleryProvider`（含密码对话框流程）

## 2. 参考：legado 的漫画阅读器

路径（都在 `D:\Project\legado-with-MD3\app\src\main\java\io\legado\app\`）：

| 文件 | 职责 |
| --- | --- |
| `ui/book/manga/MangaReaderRouteScreen.kt` | 路由入口：DI、生命周期、返回键，不含业务 |
| `ui/book/manga/MangaReaderContract.kt` | `MangaReaderUiState` / `MangaReaderIntent` / `MangaReaderEffect` / `MangaReaderSettings` |
| `ui/book/manga/MangaReaderScreen.kt`（113 KB） | 纯 UI：`HorizontalMangaPager` / `VerticalMangaPager` / `WebtoonMangaList` / `MangaHorizontalSpread`（双页） |
| `ui/book/manga/MangaReaderInteraction.kt` | 手势与点击区域 |
| `ui/book/manga/MangaReaderOverlays.kt` / `MangaReaderSheets.kt` | 菜单浮层 / 设置面板 |
| `ui/book/manga/MangaReaderViewModel.kt`（71 KB） | `onIntent()` 单入口，`uiState: StateFlow` + `effects: SharedFlow` |
| `domain/gateway/MangaReaderSession.kt` | 数据面接口 |
| `data/repository/manga/DefaultMangaReaderSession.kt` | 数据面实现 |

**值得照抄的七个点**：

1. **`pages` 是 sealed 的条目列表**，不是「图片数组 + 一堆独立状态」：

   ```kotlin
   sealed interface MangaReaderItemUi { val key: String
       data class Page(...) : MangaReaderItemUi
       data class ChapterEdge(...) : MangaReaderItemUi      // 章首/章尾占位
       data class ChapterTransition(...) : MangaReaderItemUi // 跨章过渡
   }
   ```

   错误占位、加载占位都是列表里的一等公民，pager 层面天然处理，不需要额外的 overlay 状态机。
2. **`currentItemIndex` 与 `footerItemIndex` 分离**：页脚（页码/进度条）滞后于当前项，
   跨条目时才更新，避免快速滑动时页码乱跳。
3. **`scrollRequest: MangaScrollRequest?` 带自增 `id`**：命令式滚动（跳页）必须带 id，
   否则重组时会重复触发。
4. **`navigationId: Long`**：区分「程序滚动」与「用户滚动」，防止回写进度时互相打断。
5. **`MangaPageLoadState` 四态**：`Queued` / `Loading(progress: Int?)` / `Ready` /
   `Failed(message)`。`progress` 为 `null` 时 UI 退化成不确定进度条——服务端不给
   `Content-Length` 就不会有数字。
6. **设置是单个 `MangaReaderSettings` 对象**，更新走 `UpdateSetting(key, value)`，
   而不是 N 个 `setBrightness` / `setScaleType`。
7. **预取放 UI 层**（`MangaChapterImagePrefetch`），VM 只管状态，不碰解码尺寸。

**必须记住的反面教材**：legado 的 Compose 文本阅读器尝试（`docs/dev/track-c-compose-reader-plan.md`）：

- 真机帧基线：jank **50% vs 4.13%**；50/90/95/99 分位 **30/42/44/53 ms vs 5/8/21/65 ms**
- 2026-07-25 把 C0–C5 全部删除，保留 Canvas View 作渲染核心
- 原文结论：「仿真卷曲/选择/滚动/自动翻页全量重写 = 高回归风险、低用户收益」

**为什么 EhViewer 仍然可以走 Compose**：legado 那套要把「排版引擎输出的 TextPage/TextLine
逐行画到 Canvas」重新声明式化，热路径是「每帧画几千行文本」；EhViewer 一页就是一张
位图，热路径是「一次纹理上传 + 缩放矩阵」，两者不是同一类问题。EhViewer 反倒是
**自绘 GL 的代价更大**（`GLRootView` 全家桶、纹理尺寸上限、跨机型兼容）。

## 3. 目标架构

```
EhRoute.Reader(gid, token, page, source)
  └─ ReaderRoute            @Composable 入口：参数解包、PageSource 构造与释放、返回处理
       └─ ReaderScreen      纯 UI：分页器 + 手势 + 菜单浮层
            ├─ ReaderContract     ReaderUiState / ReaderIntent / ReaderEffect / ReaderSettings
            ├─ ReaderViewModel    onIntent() 单入口
            └─ PageSource         数据面：Online / Download / Archive
```

建议的文件落点：

| 文件 | 内容 |
| --- | --- |
| `ui/compose/nav/EhRoute.kt` | 新增 `data class Reader(gid, token, page, source)` |
| `ui/compose/nav/EhRoutes.kt` | 注册 entry |
| `ui/compose/reader/ReaderRoute.kt` | 入口 + `rememberPageSource`（`DisposableEffect` 释放） |
| `ui/compose/reader/ReaderContract.kt` | `ReaderUiState` / `ReaderItemUi` / `ReaderPageLoadState` / `ReaderIntent` / `ReaderEffect` |
| `ui/compose/reader/ReaderViewModel.kt` | `onIntent` |
| `ui/compose/reader/ReaderScreen.kt` | 分页器 + 菜单（从 `ui/reader/ReaderScreen.kt` 迁移并扩展） |
| `ui/reader/PageSource.kt` | 保留；新增 `OnlinePageSource` / `DownloadPageSource` |

## 4. 关键决策

| 决策 | 选项 | 选 | 理由 |
| --- | --- | --- | --- |
| 分页 | `HorizontalPager` / 自绘 | **Pager** | 预取、手势交接、跳页都现成；不做仿真卷曲 |
| 缩放 | telephoto `ZoomableImage` / 自己 `graphicsLayer` | **telephoto** | 区域解码（`SubSamplingImageSource`）已在依赖里，大图不 OOM |
| 图片数据 | Coil 吃 `File` / 直喂流 | **File** | telephoto 区域解码需要可随机访问源；`PageSource` 已是 `File` |
| 纵向阅读 | 支持 / 不支持 | **支持**（R2 后置） | 条漫场景真实存在，`Settings.reading_direction` 本来就有 `TOP_TO_BOTTOM` |
| 跨画廊连续阅读 | 做 / 不做 | **不做** | `GalleryActivity` 也不做，保持「一次一个画廊」；省掉 `ChapterTransition` 整层 |
| 进度存储 | history 加列 / 独立表 / 先不做 | **R2 只做进程内恢复，持久化留到 R4** | 现状本来就没有持久化（只存 `savedInstanceState`），R2 先对齐现状；真要持久化得动 Room schema，不该和「换渲染栈」绑在同一片 |
| 双栈时长 | 并存 / 快速收敛 | **只允许 R1 一个片** | legado 的教训：双栈一旦超过一片，之后每加一个功能都要两边同步 |

因为不做跨画廊，legado 的 `ChapterTransition` / `ChapterEdge` 在 EhViewer 可以简化成：

```kotlin
sealed interface ReaderItemUi { val key: String
    data class Page(val page: Int, val loadState: ReaderPageLoadState) : ReaderItemUi
    data class Error(val page: Int, val message: String) : ReaderItemUi
}
```

`Error` 仍然是列表条目——这样「某页加载失败」在 pager 里就是一个可滑过的占位，
而不是一个打断翻页的全局错误态。

## 5. 分阶段

### R1 接线（不改任何交互）

- 新增 `EhRoute.Reader(gid, token, page, source)` 与 entry
- 实现 `OnlinePageSource`：包装现有的取图路径，`page(index)` 落临时文件
- 实现 `DownloadPageSource`：从 `DownloadManager` + `UniFile` 读已下载页
- `ReaderRoute` 负责 `PageSource` 的构造与 `close()`
- 5 个入口改走 `navigator.push(EhRoute.Reader(...))`：历史、下载、画廊列表、画廊详情、
  预览图；后两个把当前的 `KEY_PAGE` 值作为 `page` 传入
- `GalleryActivity` 保留，但**新入口不再使用它**；旧入口只在 debug 里留一个手工验证入口

验收：能从历史/详情进阅读器、左右翻页、返回；进程重建后回到同一页。

### R2 内核

- `ReaderContract` + `ReaderViewModel`（`onIntent` 单入口）
- 三态加载：`Queued` / `Loading(progress)` / `Ready` / `Failed`
- `Error` 条目 + 点击重试
- 跳页（`scrollRequest(id)`）、进度保存与恢复、`navigationId`
- 竖向阅读（`reading_direction`）

### R3 菜单与设置

- 顶栏/底栏：返回、页码、跳页、菜单
- 亮度（`setScreenLightness` 对应 Compose 版：调 `Window.attributes.screenBrightness`）
- 设置面板：阅读方向、缩放模式、屏幕方向、全屏、自动翻页间隔、音量键翻页
- 全屏：隐藏系统栏（`WindowInsetsController`）

### R4 图片操作

- 保存到相册 / 分享 / 复制图片 / 保存原图
- 首次引导（`GalleryGuideView`）

### R5 删旧栈

- `GalleryActivity`、`activity_gallery.xml`、`widget/GalleryHeader`、`widget/GalleryGuideView`
- `EhGalleryProvider` 若仅被旧阅读器使用则一并删；`SpiderQueen` 若仍服务下载则保留

## 6. parity 清单

| 能力 | 旧实现 | 新归属 | 阶段 |
| --- | --- | --- | --- |
| 左右翻页 | `GalleryView` + `pageTurn()` | `HorizontalPager` | R1 |
| 竖向阅读 | `GalleryView` layout | 竖向 pager / `LazyColumn` | R2 |
| 缩放/平移 | GL 矩阵 | telephoto | R1 |
| 加载进度 | `GalleryProvider.onPageDownload` | `Loading(progress)` | R2 |
| 失败重试 | `onTapErrorText()` | `Error` 条目 + 重试 | R2 |
| 跳页 | `showPageDialog()` | `scrollRequest(id)` | R2 |
| 进度记忆 | `savedInstanceState`（`KEY_PAGE` / `KEY_CURRENT_INDEX`） | `rememberSaveable` / `SavedStateHandle` | R2（持久化 R4） |
| 音量键翻页 | `onKeyDown()` | `Modifier.onKeyEvent` | R3 |
| 自动翻页 | `autoTransfer()` | `LaunchedEffect` + `turn_page_interval` | R3 |
| 亮度 | `setScreenLightness()` | `Window.screenBrightness` | R3 |
| 全屏 | `reading_fullscreen` | `WindowInsetsController` | R3 |
| 保存/分享/复制 | `saveImage` / `shareImage` / `copyImage` | `ReaderIntent.PageAction` | R4 |
| 首次引导 | `GalleryGuideView` | Compose 引导层 | R4 |
| 压缩包阅读 | `ArchiveGalleryProvider` + 密码框 | `ArchivePageSource` + 密码 Intent | R1（沿用） |

## 7. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 大图 OOM | `PageSource` 落文件 + telephoto 区域解码；不要 `BitmapFactory.decodeStream` 全量解 |
| 在线读图慢、无进度 | `Loading(progress)` 允许 `null`，无 `Content-Length` 时只转圈 |
| 双栈期入口分叉 | R1 之后所有入口只走新 route；`GalleryActivity` 只剩 debug 手工入口，R5 删 |
| 进程重建 | route 只带 `gid` / `token` / `page` / `source`，全部可序列化 |
| 亮度和全屏是 Window 级操作 | 在 `ReaderRoute` 用 `DisposableEffect` 进/出恢复，别写在 `ReaderScreen` 里 |
| 下载画廊与在线画廊页序可能不同 | `DownloadPageSource` 按 `DownloadManager` 的文件序，不按文件名排序 |
| `PageSource.close()` 泄漏 | `rememberPageSource` + `DisposableEffect { onDispose { close() } }` |
| 遥测/回归覆盖不到渲染 | 沿用第 7 节回归清单，每片真机过一遍翻页/缩放/返回 |

## 8. 每片验收

- 四道门禁：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、`:app:assembleDebug`
- 单测可覆盖的纯函数：`ReaderIntent` 归约（页码钳制、`navigationId` 回写过滤、加载态迁移）
- 真机回归：历史/详情进阅读器、翻页、缩放、跳页、返回、进程重建、下载画廊、压缩包画廊

## 9. 待确认（R1 已全部回答，见 10.6）

1. `EhGalleryProvider` 在「已下载画廊」场景下是否已经在读本地（决定 `DownloadPageSource`
   与 `OnlinePageSource` 的边界，还是干脆让 `OnlinePageSource` 内部自己判断）。
2. `SpiderQueen` 是否有「按 index 直接取字节/文件」的公开方法；若没有，`OnlinePageSource`
   需要在 `EhGalleryProvider` 之上加一层适配，而不是绕开它。
3. `GalleryActivity` 的 `KEY_FILENAME` 走的是哪条路径（疑似压缩包按文件打开），需要与
   `ArchivePageSource` 对齐参数。

## 10. R1 落地记录（2026-10-31）

R1 只接线、不改交互。改完之后**新入口全部走 `EhRoute.Reader`，不再起 `GalleryActivity`**；
`GalleryActivity` 只服务外部「用别的 App 打开压缩包」这一条路（R5 才删）。

### 10.1 新增

| 文件 | 内容 |
| --- | --- |
| `ui/compose/nav/EhRoute.kt` | `EhRoute.Reader(gid, token, page, source)` + `ReaderSource` 枚举（`ONLINE` / `DOWNLOAD`）；4 处穷尽 `when` 补分支 |
| `ui/compose/nav/EhRoutes.kt` | `targetOf` / `gidOf` 补分支 |
| `ui/compose/reader/ReaderRoute.kt` | 入口：造页源 → `prepare()` → 交给 `ReaderScreen`；`DisposableEffect` 里 `close()` |
| `ui/reader/PageSource.kt` | 接口加 `prepare()` / `startPage`；新增 `OnlinePageSource`、`DownloadPageSource`、`readerPageSource()` 工厂 |
| `app/src/test/.../EhRouteCodecTest.kt` | 阅读器 route 往返、`gidOf`、抽屉/守卫链归属 |

### 10.2 改造点（入口改线，共 7 处）

| 入口 | 原 | 现 |
| --- | --- | --- |
| 历史 `HistoryScene.openReader` | `startActivity(GalleryActivity)` | `push(EhRoute.Reader(gid, token))` |
| 下载 `DownloadsScene.readDownloaded` | 同上 | `push(... source = DOWNLOAD)` |
| 画廊列表 `GalleryListScene.onRead` | 同上 | `push(...)` |
| 详情 `GalleryDetailScene.read` | 同上 + `KEY_PAGE` | `push(... page = preview?.position ?: previewIndex ?: -1)` |
| 预览图 `GalleryPreviewsScene.onItemClick` | 同上 + `KEY_PAGE` | `push(... page = preview.position)` |
| `UrlOpener` 画廊页 URL | 同上 + `KEY_PAGE` | `startHostWithRoute(EhRoute.Reader(...))` |
| `UrlOpener` `#c` 锚点 | 同上 + `KEY_PAGE` | 同上 |

后四个入口的 `page` 与旧 `KEY_PAGE` 语义一致（0 基），`page = -1` 表示「沿用上次读到的位置」。

### 10.3 与计划的偏离

1. **`PageSource` 加了 `suspend prepare()` 与 `startPage`。** 在线页数要等 `SpiderInfo` 就绪
   才知道，而 `count` 是同步的，所以「知道有多少页」单独一步，由 `ReaderRoute` 在渲染
   分页器之前跑完；`startPage` 是为了对齐旧实现的 `mPage >= 0 ? mPage : provider.startPage`。
2. **`OnlinePageSource` 用 `preloadPages` 触发下载，不用 `request`。** `request` 会顺带
   `Image.decode` 出一张 Compose 阅读器根本不用的 `Image`（白占 native 内存还要负责回收）；
   `preloadPages` 只下载不解码，是唯一干净的落盘路径。
3. **在线 / 已下载不是非此即彼的两条独立实现。** `DOWNLOAD` 拿不到下载目录时退回在线页源——
   旧实现走 `EhGalleryProvider`（SpiderQueen 读模式）本来就是这么兜底的。
4. **入口是 7 处，不是盘点时的 5 处。** `UrlOpener` 里还有两处（画廊页 URL、`#c` 锚点），
   不改的话「新入口不再使用旧 Activity」这条就落空了。
5. **压缩包不在 R1。** `EhRoute` 全 `@Serializable`，`Uri` 表达不了；外部 `ACTION_VIEW`
   仍然直接进 `GalleryActivity`，`ArchivePageSource` 继续闲置，等 R2 一起处理。
6. **下载页源也拷一份到缓存目录**，不直接把 `UniFile` 喂给 Coil——下载位置可能是 SAF，
   拿不到 `File`。代价是每页一次本地拷贝（只在真正翻到时才发生，退出即删）。
7. **「进程重建后回到同一页」只对入口页码成立。** 翻到中途的页码要等 R2 的 ViewModel
   （R1 靠 `rememberPagerState` 自带的保存 + Nav3 的 entry 状态保存，属于真机验证项）。

### 10.4 验证

四道门禁全绿：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`
（`EhRouteCodecTest` 3 → 5 例）、`:app:assembleDebug`。

真机待跑（R1 验收）：历史 / 详情 / 预览图进阅读器、左右翻页、缩放、返回；下载画廊读本地；
在线画廊加载与失败重试；进程重建。

### 10.5 R2 前置

- `PageSource` 现在是「文件 + 三态」的最小形态，R2 的 `ReaderPageLoadState`
  （`Queued` / `Loading(progress: Int?)` / `Ready` / `Failed`）需要在这一层补进度回调：
  `OnSpiderListener.onPageDownload` 已经把 `contentLength` / `receivedSize` 送出来了，
  挂上去即可。
- `Error` 条目要接住「缺页」和「下载失败」两种：`DownloadPageSource.scan()` 已经为缺页
  保留了条目（到 `page()` 才抛错），R2 直接把异常映射成条目状态。
- 压缩包 route 化：`EhRoute.Reader` 需要能带 `Uri`，要么加 `String` 字段，要么单独一条
  `EhRoute.ArchiveReader`。

### 10.6 第 9 节三个待确认的答案

1. **是，已经在读本地。** `SpiderQueen.doPrepare()` 先挂
   `SpiderDen.getGalleryDownloadDir(gid)`，`readSpiderInfoFromLocal()` 优先读 `.ehviewer`，
   `SpiderDen.getImageSource()` 按「缓存 → 下载目录」取。所以在线 / 已下载共用 SpiderQueen
   读模式是安全的，`DownloadPageSource` 只是省掉「先建 SpiderInfo」这一步，纯离线更快。
2. **没有。** `SpiderQueen` 整个是 GL 回调式（`ImageWrapper` + `OnSpiderListener`），
   唯一落盘口子是 `save(index, file)`，但它要求状态已经是 `STATE_FINISHED`、且是同步的。
   R1 在 `EhGalleryProvider` 之外用 `preloadPages` + `OnSpiderListener` 把「下载完成」
   桥成 `suspend`，没有绕开 SpiderQueen 的 pToken / 509 / 重试逻辑。
3. **`KEY_FILENAME` 是死字段。** 只在 `handleIntent` / `onRestore` / `onSaveInstanceState`
   里存取，全仓没有写入方，随旧栈一起删，不需要与 `ArchivePageSource` 对齐。

## 11. R2 落地记录（2026-10-31）

R2 把「内核」补上：阅读器现在有状态机了，加载 / 失败 / 重试 / 跳页各就各位，
`ReaderScreen` 不再持有任何数据。

### 11.1 新增

| 文件 | 内容 |
| --- | --- |
| `ui/reader/ReaderContract.kt` | `ReaderItemUi`（`Page` / `Error`）、`ReaderPageLoadState`、`ReaderUiState`、`ReaderIntent`、`ReaderSettings`、`ReaderScrollRequest`，外加纯函数 `reduce` / `withPage` / `withProgress` |
| `ui/reader/ReaderViewModel.kt` | `onIntent` 单入口；构造时就把 `pages` 铺好（分页器第一帧必须知道有多少页） |
| `ui/reader/ReaderScreen.kt` | 重写为「只吃 `ReaderUiState` + 只发 `ReaderIntent`」；横向 / 竖向 pager、加载态、错误重试、页码栏 |
| `app/src/test/.../ReaderContractTest.kt` | 9 例：页码钳制、`navigationId` 过期回弹过滤、加载态迁移、预取窗口 |

`PageSource` 加两个成员：`progress: Flow<PageProgress>`（`percent` 可为 null）与
`putStartPage(page)`，都带默认实现，压缩包页源不用动。

### 11.2 关键设计（照抄 legado 漫画阅读器的四点）

1. **错误是分页器里的一等公民条目**，不是一个全局错误态：整条目换成
   `ReaderItemUi.Error(index, message)`，可滑过、可点重试。
2. **`scrollRequest` 带自增 id**：`ScrollTo` 每次 `navigationId + 1`，重组时靠 id
   区分「新请求」和「已处理过的旧请求」。
3. **`navigationId` 过滤过期回弹**：分页器滚完带着 id 回报，id 对不上就丢弃——
   否则上一个请求的迟来回声会把刚跳过去的页码写回来。
4. **预取策略放 UI 层**（`prefetchWindow` = 前一页 + 当前页 + 后两页），
   ViewModel 只管状态，不碰解码尺寸。

### 11.3 与计划的偏离

1. **`ReaderPageLoadState` 只有三态**：`Queued` / `Loading(progress: Int?)` / `Ready`。
   计划的四态里 `Failed` 没放进加载态，而是由 `ReaderItemUi.Error` 承担——
   否则「失败」有两种表达（`Page(Failed)` 与 `Error`），很快就分叉。
2. **跳页的 UI 不在这片**：底栏滑块 / 页码对话框属于 R3。R2 只交付
   `ScrollTo` → `scrollRequest(id)` → `navigationId` 这条状态链路，靠单测覆盖
   （第 8 节本来就是这么规划的：纯函数归约可测，UI 不可测）。
3. **竖向阅读是 `VerticalPager`（逐页），不是连续滚动列表**：对齐旧
   `GalleryView.LAYOUT_TOP_TO_BOTTOM` 的逐页行为。真要 webtoon 式连续卷动另开一片。
4. **阅读方向常量不进 contract**：`GalleryView.LAYOUT_*` → `ReaderSettings` 布尔的
   映射放在 `ReaderRoute`，这样 `ReaderContract` 不碰 Android、能直接单测。
5. **进度保存做了两层**：`rememberSaveable` 显式存页码（不依赖
   `rememberPagerState` 自己有没有保存），并沿旧行为调 `PageSource.putStartPage`
   ——在线画廊落在 `.ehviewer` 的 `SpiderInfo.startPage`，与
   `GalleryActivity.onUpdateCurrentIndex` 同款。Room 持久化仍然是 R4。
6. **`putStartPage` 只在在线页源上实现**：`DownloadPageSource` 是空实现，
   已下载画廊的阅读进度要等 R4 的持久化表。

### 11.4 验证

四道门禁全绿：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`
（新增 `ReaderContractTest` 9 例，全仓 37 → 46）、`:app:assembleDebug`。

真机待跑（R2 验收）：在线页加载与百分比进度、断网后错误占位 + 点重试、
竖向阅读、跳页、翻页后退出再进仍在同一页、下载画廊读本地。

### 11.5 R3 前置

- 顶栏 / 底栏：返回、页码、**跳页**（调 `ReaderIntent.ScrollTo`，链路已就位）、菜单。
- 设置面板：`ReaderSettings` 现在只在进阅读器时读一次，改成面板里可调需要新增
  `ReaderIntent.UpdateSetting` 并让 `ReaderScreen` 从头读 `uiState.settings`。
- 亮度（`Window.attributes.screenBrightness`）与全屏（`WindowInsetsController`）
  照第 7 节的纪律放 `ReaderRoute` 的 `DisposableEffect`，别写进 `ReaderScreen`。
- 音量键翻页：`Modifier.onKeyEvent` → `ScrollTo(currentPage ± 1)`。
- 自动翻页：`LaunchedEffect` + `Settings.turnPageInterval` → `ScrollTo(currentPage + 1)`。

## 12. R3 落地记录（2026-10-31）

R3 把菜单与设置补上：跳页 UI、设置面板、亮度、全屏、屏幕方向、音量键、自动翻页全部可用，
剩下的只有图片操作（R4）和删旧栈（R5）。

### 12.1 新增 / 改造

| 文件 | 内容 |
| --- | --- |
| `ui/reader/ReaderSettingsPanel.kt` | 新文件：设置面板（`AlertDialog` + `LazyColumn`）与跳页对话框 |
| `ui/reader/ReaderSettingsStore.kt` | 新文件：`Settings` ↔ `ReaderSettings` 互转 + `persist()` |
| `ui/reader/ReaderContract.kt` | `ReaderSettings` 扩到 11 个字段；新增 `ReaderIntent.UpdateSettings`；`dimAlpha` 纯计算 |
| `ui/reader/ReaderScreen.kt` | 顶栏（返回 / 页码可点 / 菜单）、底栏（滑块跳页）、遮罩、音量键、自动翻页、`pageSpacing`、缩放模式 |
| `ui/compose/reader/ReaderRoute.kt` | `ReaderWindowEffects`：屏幕方向 / 系统栏 / Window 亮度，离开时还原 |

### 12.2 面板覆盖的设置

阅读方向（3 项）、缩放模式（4 项）、屏幕方向（4 项）、全屏、显示页间隔、自动翻页间隔、
音量键翻页、反向音量键、自定义亮度、亮度（0-200）。

**没有搬过来的三项**（都是刻意的）：

- `read_theme`：旧实现靠 `recreate()` 生效，单一 NavHost 宿主下代价太大，另开一片；
- `volume_page_interval`：语义是「一次按几页」，与「音量键翻一页」不是一个维度，暂不支持；
- `start_position` 与 `SCALE_FIXED`：Compose 的 `ContentScale` 表达不了「固定倍率」，退回 `Fit`。
- 顶栏 / 底栏的自动隐藏：旧实现有 3 秒自动收起，R3 先常显。

### 12.3 与计划的偏离

1. **设置面板用 `AlertDialog` + `LazyColumn` 而不是 `ModalBottomSheet`**：仓库里目前没有
   用过的 bottom sheet，先走已经验证过的组件组合，样式以后再调。
2. **自动翻页没有独立的开关字段**：`autoPageInterval = 0` 就是关，与旧实现一致
   （旧实现也是每次进阅读器手动开，只持久化间隔）。关着的时候不写回 `Settings`，
   否则上次用的间隔会被 0 覆盖。
3. **亮度拆成两半**：`<= 100` 用 `ReaderScreen` 里的黑色遮罩（旧实现是 `mMaskView`），
   `> 100` 才动 `Window.screenBrightness`。遮罩不设 `clickable` / `pointerInput`，
   翻页手势照旧穿过去。
4. **音量键靠 `Modifier.onKeyEvent` + `FocusRequester`**：Compose 的 `KeyEvent` 在 1.12
   是包着 `android.view.KeyEvent` 的 value class，成员是 `nativeKeyEvent`，
   `type` / `key` 是扩展属性需要额外 import——直接用 `nativeKeyEvent` 少一层依赖。
   这条**只能真机验证**（模拟器/无焦点时不一定收得到）。
5. **`ReaderSettings` 依然不碰 Android**：`GalleryView.LAYOUT_*` / `SCALE_*` 的映射全在
   `ReaderSettingsStore`，contract 只放布尔与标量，所以 `dimAlpha`、`UpdateSettings`
   这些能直接单测。

### 12.4 验证

四道门禁全绿：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`
（`ReaderContractTest` 9 → 11 例，全仓 46 → 48）、`:app:assembleDebug`。

真机待跑（R3 验收）：菜单打开设置面板、改阅读方向立刻生效、跳页对话框、底栏滑块、
全屏开关、亮度两侧（遮罩 / 加亮）、音量键翻页、自动翻页与到末页自动停、
退出阅读器后系统栏与屏幕方向还原。

### 12.5 R4 前置

- 图片操作：保存 / 分享 / 复制 / 保存原图 —— 需要 `PageSource` 暴露「源文件」
  （`SpiderQueen.save` / `downloadOriginal`），现在的 `PageSource.page()` 只给缓存副本，
  原图要另开一个方法。
- 首次引导（`GalleryGuideView`）：Compose 引导层。
- 阅读进度持久化：`PageSource.putStartPage` 目前只有在线页源实现，
  已下载画廊要等 R4 的表。

## 13. R4 落地记录（2026-10-31）

R4 把图片操作、首次引导、进度持久化三件补上。到这一片为止，R1 现状盘点里
「只做拷贝，不做交互」的全部缺口都补完了，剩下的只有删旧栈（R5）。

### 13.1 新增 / 改造

| 文件 | 内容 |
| --- | --- |
| `dao/ReadingProgress.kt` + `dao/ReadingProgressDao.kt` | 新表 `READING_PROGRESS(GID, PAGE, TIME)` |
| `dao/EhDatabase.kt` | version 4 → 5，加 `MIGRATION_4_5` |
| `EhDB.kt` | `getReadingProgress` / `putReadingProgress` / `removeReadingProgress` |
| `ui/reader/ReaderImageOps.kt` | 新文件：分享 / 复制 / 存相册 / 写 SAF，全部从旧 `GalleryActivity` 搬过来 |
| `ui/reader/ReaderPageMenu.kt` | 新文件：长按页菜单（刷新 / 分享 / 复制 / 保存 / 另存为 / 下载原图） |
| `ui/reader/ReaderGuide.kt` | 新文件：Compose 版 `GalleryGuideView`，两步引导 |
| `ui/reader/PageSource.kt` | 加 `invalidate(index)` 与 `suspend original(index, dir)` |
| `ui/reader/ReaderContract.kt` | 加 `ReaderIntent.Refresh`、`ReaderUiState.online` |
| `ui/reader/ReaderViewModel.kt` | 处理 `Refresh`；`file()` / `original()`；进度写两份 |
| `ui/compose/reader/ReaderRoute.kt` | `rememberLauncherForActivityResult` 接「另存为」；起始页兜底顺序 |

### 13.2 进度的读写顺序

起始页：**入口带的页码 > 数据库里记的进度 > 页源自己的 `startPage`**。
第一条对应旧实现 `mPage >= 0 ? mPage : provider.startPage`——点缩略图进阅读器
就必须落在那一页，哪怕上次读到了第 30 页。

写入：`PageSettled` 时同时写 `.ehviewer`（`PageSource.putStartPage`，跟着缓存目录）
和 Room（`EhDB.putReadingProgress`，清缓存/重装都还在）。

### 13.3 与计划的偏离

1. **Room 迁移必须显式写。** `buildMainDB` 挂着 `fallbackToDestructiveMigration(dropAllTables = true)`，
   缺迁移就整库重建——历史、下载、书签全没。所以 R4 补了 `MIGRATION_4_5`（只 CREATE TABLE），
   而不是靠兜底。**以后每次动 schema 都得补一条，这行兜底不能当迁移用。**
2. **进度单独一张表，不给 `HISTORY` 加列。** 历史是「访问过什么」，进度是「读到哪一页」：
   清历史不该顺手清掉进度，反之亦然。
3. **相册保存删掉了权限分支。** minSdk 31 远超 Q，`WRITE_EXTERNAL_STORAGE` 那段
   （旧实现 882-891 + `requestStoragePermissionLauncher`）直接不存在了。
4. **「另存为」的 `ActivityResultContracts` 放 `ReaderRoute`。** 它要 Activity 级注册，
   而且得先把文件准备好再起 SAF，属于副作用链——按第 7 节的纪律不进 `ReaderScreen`。
5. **长按交给 telephoto 的 `onLongClick`**，不自己套 `combinedClickable`——
   后者跟缩放手势谁先吃到事件不好保证。
6. **`DownloadPageSource` 不实现 `invalidate` / `original`**：本地文件没有「重下」，
   也没有「原图」概念，`original` 返回 null 后页面菜单就不显示这一项。

### 13.4 验证

四道门禁全绿：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`
（`ReaderContractTest` 11 → 12 例）、`:app:assembleDebug`。

真机待跑（R4 验收）：长按弹菜单、分享、复制、保存到相册、另存为、下载原图、
刷新坏页；首次引导两步走完不再出现；翻到第 12 页退出再进仍在第 12 页；
**从 4 版升级上来历史与下载没被清掉**（迁移是新增的，必须实测）。

### 13.5 R5 前置

- 删 `GalleryActivity`、`GalleryProvider2` / `EhGalleryProvider` / `ArchiveGalleryProvider`、
  `GalleryMenuHelper`、`GalleryGuideView` 与两个引导布局。
- `AndroidManifest.xml` 里 `GalleryActivity` 的 `ACTION_VIEW` 入口换成 NavHost 的深链
  （`EhRoute.Reader` 现在表达不了 `Uri`，要么加 `String` 字段，要么单开 `EhRoute.ArchiveReader`）。
- `ArchivePageSource` 接上：压缩包阅读是唯一还留在旧栈的功能。
- `Image` / `nativeTexImage` / GL 相关代码随旧栈一起删。

## 14. R5 落地记录（2026-10-31）

R5 收尾：压缩包阅读接进新栈，旧 GL 阅读器整体删除。
**到这一片为止，`GalleryActivity` 与它背后的一整套 GL 栈都不存在了，双栈期结束。**

### 14.1 压缩包接入

`EhRoute.Reader` 加 `uri: String?` + `ReaderSource.ARCHIVE`；外部「用 EhViewer 打开压缩包」
的 `ACTION_VIEW` intent-filter 从 `GalleryActivity` 移到宿主 `MainActivity`，
`MainActivity.handleIntent` 里遇到 `file` / `content` 且不是画廊链接就压阅读器 route。

### 14.2 删掉的东西

| 范围 | 内容 |
| --- | --- |
| 阅读器 | `ui/GalleryActivity.kt`、`gallery/{GalleryProvider2,EhGalleryProvider,ArchiveGalleryProvider}.kt` |
| GL 栈 | `com/hippo/glgallery/`（11 文件）、`com/hippo/glview/`（39 文件） |
| GL 遗留 | `image/TexImage.kt`（`nativeTexImage` 的 Kotlin 侧）、`Image.texImage()` |
| 旧控件 | `widget/GalleryGuideView.java`、`widget/GalleryHeader.java`、`widget/SeekBarPanel.kt` |
| 资源 | `activity_gallery.xml`、`dialog_gallery_menu.xml`、两个引导布局、`AppTheme.Gallery` |

常量搬移：`GalleryView.LAYOUT_*` / `SCALE_*` → `ui/reader/ReaderLayoutMode.kt`（值保持原样，
`Settings` 里存的就是这些整数）；`SUPPORT_IMAGE_EXTENSIONS` → `SpiderDen.Companion`。

### 14.3 与计划的偏离

1. **给 `EhRoute.Reader` 加 `uri`，没单开 `EhRoute.ArchiveReader`。** 省掉一条 route 要补的
   6 处穷尽 `when`，`source = ARCHIVE` 足够区分。
2. **`ArchivePageSource` 一分为二**：类本身只认已打开的 session（可以拿假 session 单测），
   开包与密码在 `openArchivePageSource()` 里。原来的写法一个构造函数既要 `Context`/`Uri`
   又要能被单测，做不到。
3. **密码不再用 `suspendCancellableCoroutine` + `AtomicReference`。** 旧实现是把对话框塞进
   provider 里；这边换成「开包流程回调 → UI 弹框 → 结果喂 flow」，取消就是喂一个 null。
4. **`Settings` 的三个 `sanitizeXxx` + `@IntDef` 换成 `coerceIn`。** 那套 `@LayoutMode`
   `/ @ScaleMode` 注解定义在 `GalleryView` 里，随旧栈删了；钳位效果等价。
5. **`GalleryActivity` 的 `singleTask` 与 `AppTheme.Gallery` 一起没了。** 现在压缩包也走宿主，
   `launchMode` 用宿主的（manifest 里本来就是 `singleTask`）。

### 14.4 验证

四道门禁全绿：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`
（49 → 51 例，`ArchivePageSourceTest` 补了映射用例、`EhRouteCodecTest` 补了 archive route
往返）、`:app:assembleDebug`。

真机待跑（R5 验收）：外部 App 用 EhViewer 打开 zip / rar / 7z、加密包弹密码框、
密码错再弹一次、取消退出；以及**全部回归一遍**（旧栈删了，任何漏网都会变成崩溃）。

### 14.5 还没做的（都不阻塞换栈，单开）

> 这四条已在 **R6** 全部补完，见 §15。

- `read_theme`：要 recreate Activity，R3 就记录过。
- `start_position` 与 `SCALE_FIXED`（固定倍率）：Compose 的 `ContentScale` 表达不了，
  设置值保留但不生效。
- 顶栏 / 底栏的自动隐藏：R3 先常显。
- `volume_page_interval`：语义是「一次按几页」，与「音量键翻一页」不是一个维度。

## 15. R6：补完 14.5 的四条

R5 之后阅读器功能面已经齐了，剩下 14.5 那四条「设置项存在但没接线」的尾巴。
这一片把它们全部接通，顺便把 R3 留下的两个技术债（`Scaffold` 让顶/底栏占位、
`contentScaleOf` 退回 Fit）一起还掉。

### 15.1 `read_theme`——不 recreate，改局部色板

旧 `GalleryActivity` 在 `attachBaseContext` 里改 `delegate.localNightMode`，改完还得
`recreate()` 才会让新资源生效。换成 NavHost 单宿主后，recreate 会把整棵导航栈重建，
代价完全不成比例。

改成**在阅读器子树里换 `ColorScheme`**：

- `EhTheme.kt` 抽出 `ehColorScheme(dark, amoled)`（原来那段取色逻辑原样搬进去），
  `EhTheme` 与新的 `ReaderTheme` 共用，避免两份会漂移的副本。
- 新增 `ui/reader/ReaderTheme.kt`：`readTheme == 0` 直接透传外层主题；`1` / `2` 分别钉死
  深色 / 浅色。纯黑（AMOLED）只在深色分支叠。
- `ReaderRoute` 用 `ReaderTheme(vm.uiState.settings.readTheme) { ReaderScreen(...) }` 包住。
- `ReaderScreen` 根 `Box` 补了一层 `background(MaterialTheme.colorScheme.background)`：
  原来它不铺底，会漏出 Activity 的窗口底色，主题一换就露馅。

`Settings.readTheme` 顺手加了 `coerceIn(0, 2)`。

### 15.2 `start_position` 与 `SCALE_FIXED`

先纠正一条之前记错的结论：**`SCALE_FIXED` 不是「表达不了」，是旧实现里它本来就跟
`SCALE_ORIGIN` 一样。**

`ImageView.setScaleOffset()` 的 `default` 分支是 `mScale = scaleValue`，而
`scaleValue` 来自字段 `mScaleValue`，`GalleryView` / `GalleryActivity` 从来没有给它赋过
别的值（全仓 grep 只有初值 `1.0f` 与透传），所以固定倍率下 `targetWidth = textureWidth`，
与 `SCALE_ORIGIN` 完全等价。映射成 `ContentScale.None` 是**忠实还原**，不是降级。

`start_position` 也不再作废：`ZoomableImage` 有 `alignment` 参数，正好就是「内容比视口
小时往哪边靠」，与旧 `ImageView` 的五个 `START_POSITION_*` 一一对应。

两张映射表从 `ReaderScreen` 的私有函数挪到 `ReaderLayoutMode.kt`，变成 `internal` 的
`contentScaleOf(scaleMode)` / `alignmentOf(startPosition)`，配 `ReaderLayoutModeTest`
（4 例，含未知值兜底）钉住，因为这类映射错了不会有编译错误，只会表现成「设置点了没反应」。

> 顺带修掉一个真 bug：`Settings.startPosition` 的旧钳位是 `coerceIn(0, 3)`，把
> `START_POSITION_CENTER = 4` 直接钳掉了，选「居中」永远存不住。改成 `0..4`。

### 15.3 顶栏 / 底栏自动隐藏

- 3 秒后自动收起（`CHROME_AUTO_HIDE_MILLIS`，与旧 `GalleryActivity` 的 header 同款），
  翻页或点屏幕重新计时。
- **`Scaffold` 换成 `Box` + 叠加栏**。原来栏占位，收起时 pager 会跳一下；旧实现本来就是
  header 浮在 GLView 上。底栏因此自己带了 `surfaceContainerHigh` 底色。
- 「点一下叫回栏」用的是自定义的 `Modifier.pointerInputWake`：`awaitFirstDown(requireUnconsumed
  = false)` + `waitForUpOrCancellation()`，**只读不消费**。不能用 `clickable` /
  `detectTapGestures`——它们会抢走 down，telephoto 的双击缩放和分页器的拖动就都没了。
  滑动翻页时位置变化被 `scrollable` 消费，`waitForUpOrCancellation` 返回 null，不会误触。

### 15.4 `volume_page_interval`

语义确认：一次按音量键翻 N 页，与「音量键翻一页」是同一个轴的倍数，不是另一个维度。
`ReaderSettings` 加 `volumePageInterval`，面板给 `1..14` 的滑杆（R7 前是 1..10，见 §16.4），`onVolumeKey` 里
`delta = direction * interval`。落盘前 `coerceAtLeast(1)`——旧设置允许存 0，那等于按键失灵。

### 15.5 验证

四道门禁全绿：`:app:spotlessCheck`、`:app:compileDebugKotlin`、`:app:testDebugUnitTest`
（51 → 55 例，新增 `ReaderLayoutModeTest` 4 例）、`:app:assembleDebug`。

真机待跑（R6 验收）：

- 阅读器里切深/浅/跟随 App，面板与底栏跟着变色，退出阅读器不影响外层主题；
- `actual size` / `fixed scale` 下切五个起始位置，宽高都比屏幕小的图能看到靠角效果；
- 静置 3 秒栏自动收起，点一下回来；栏收起时双击仍能放大（不能被「点醒」手势吃掉）；
- 音量键间隔设 3，按一下前进 3 页。

## 16. R7：阅读设置「两个面」对齐，清掉死开关

R6 之后阅读器的设置项全接通了，但**设置有两个入口在写同一批 key**：

| 面 | 位置 | 形态 |
| --- | --- | --- |
| 阅读器内面板 | `ui/reader/ReaderSettingsPanel.kt` | Compose `AlertDialog`，改完立刻 `persist()` |
| 设置 → 阅读设置 | `res/xml/read_settings.xml` + `ui/fragment/ReadFragment.kt` | 经典 `PreferenceScreen`，`SimpleMenuPreference` / `SeekBarPreference` |

两个面写的是同一批 `SharedPreferences` key，所以任何一边多一项、少一项、取值范围
不一样，都会表现成「在 A 改了，B 显示的是另一个值」或者「B 里有个开关拨了没反应」。
R5 删 GL 栈时按「数组是否还被 Preference 引用」这个口径扫过资源，`read_settings.xml`
里的条目全被判成活的，于是一批**功能已经不存在**的开关活了下来。

### 16.1 `keep_screen_on` 接线

这是唯一**功能还没丢**的一项：旧 `GalleryActivity` 用 `window.addFlags(FLAG_KEEP_SCREEN_ON)`
实现，新阅读器漏了。补在 `ReaderWindowEffects` 里，与屏幕方向 / 系统栏 / 亮度同一个
`DisposableEffect` 家族。

细节：`onDispose` 只在「进来之前是关的」时候才 `clearFlags`，否则会把别的持有者的常亮
需求一起关掉。`ReaderSettings` 加 `keepScreenOn`，面板补开关。

### 16.2 删掉三个死开关

`gallery_show_clock` / `gallery_show_progress` / `gallery_show_battery` 控制的是
`GalleryHeader`（顶栏的时钟 / 页码 / 电量），随 R5 一起删了。现在：

- 页码在 `ReaderTopBar` 里常显，不需要开关；
- 时钟与电量没有对应控件，且与「沉浸阅读」的方向相反。

三处一起删：`Settings` 的三组 key + accessor、`read_settings.xml` 的三个
`SwitchPreferenceCompat`、5 份 `strings.xml` 里的 `settings_read_show_*`。

### 16.3 清 GL 遗留 dimen

`gallery_pager_interval` / `gallery_scroll_interval` / `gallery_page_min_height` /
`gallery_page_info_interval` / `gallery_progress_size` / `gallery_page_text_size` /
`gallery_error_text_size` 七个 dimen 只被旧 GL 阅读器用过，`dimens.xml` 里留了空壳。
`gallery_content_margin_h` / `_v` 仍在 `widget_content_layout.xml` 里用，保留。

### 16.4 取值范围对齐

- `volume_page_interval`：面板原来是 `1..10`，XML 是 `1..14`。XML 设 14、面板再动一下
  就会被写回 10。面板改成 `1..14`。
- `turn_page_interval`：面板是 `0..30`（0 = 关，与 R3 的记录一致），XML SeekBar 的
  `max` 原来是 15，面板设 20 会让 XML 那边显示被钳成 15。XML 的 `max` 改 30。

### 16.5 验证

四道门禁全绿：`:app:spotlessCheck`、`:app:compileDebugKotlin`、`:app:testDebugUnitTest`
（55 例不变，这一片只删不改逻辑）、`:app:assembleDebug`。

另做了一次残留引用扫描：12 个被删标识符（7 个 dimen、3 个字符串、2 个 accessor）
在 `app/src` 下零命中，只剩 `ReaderScreen.kt` 里一句历史注释。

真机待跑（R7 验收）：设置 → 阅读设置里不再有时钟 / 页码 / 电量三项；打开「屏幕常亮」
后阅读时熄屏时间到不再黑屏，退出阅读器后恢复系统的熄屏策略。

## 17. R8：点击区手势（引导文案承诺过，实现没做）

`ReaderGuide` 第一步画了四格：左 / 右 1/3「翻页」、中间上半「菜单」、中间下半「进度条」。
但 `ReaderScreen` 只接了长按（页面菜单）——**用户照引导点屏幕是没反应的**。

R6 加的 `pointerInputWake`（「随便点一下把收起的栏叫回来」）正好把这个缺口盖住了：
点哪儿都有反馈，只是反馈错了。这条属于「文案与实现不一致」，比缺功能更难被发现。

### 17.1 分区

`tapZoneOf(x, y, width, height, vertical, reverse)` 纯函数进 `ReaderLayoutMode.kt`：

- 沿**翻页轴**三分：前 1/3 上一页、后 1/3 下一页；
- 中间 1/3 再按**另一根轴**二分：横向是上下（上半菜单、下半进度，与引导图一致），
  纵向是左右（同一套规则顺过来，没有为纵向单独发明手势）；
- `reverseLayout`（从右往左）时左右两段对调——用户眼里的「前一页」在右边。

配 4 个单测：横向三分、RTL 对调、纵向、零尺寸兜底。

### 17.2 `PageStep`

点屏幕与音量键都是「相对翻 N 页」，新增 `ReaderIntent.PageStep(delta)`，`reduce` 里
直接转成 `ScrollTo(currentPage + delta)`，钳位与 `navigationId` 自增全部复用同一条路径。

省的不只是代码：UI 层不必读 `currentPage`，手势的 `pointerInput` key 就能收敛成
`(vertical, reverse)`。否则每翻一页都会重建手势检测器，把**正在等待的双击超时**取消掉，
双击缩放时灵时不灵。

### 17.3 单击必须等双击超时

`clickable` / `detectTapGestures` 不能用（会抢 down，telephoto 的缩放与分页拖动就废了），
所以要在不消费事件的观察器里手工复刻 `GestureDetector.onSingleTapConfirmed`：

1. `withTimeoutOrNull(longPressTimeout) { waitForUpOrCancellation() }`——拖动时位置变化被
   `scrollable` 消费、长按会超时，两者都判成「不是单击」。长按那条是必须的：telephoto 的
   长按会先于松手打开页面菜单，不拦的话松手会顺带翻一页。
2. 抬起后再等 `doubleTapTimeoutMillis`，期间又按下就说明是双击——留给 telephoto 放大，
   第二下的收尾吃掉，自己也退出。

旧的 `GalleryView` 用的就是 `onSingleTapConfirmed`，所以这次是「把延迟加回来」而不是新引入。

### 17.4 验证

四道门禁全绿：`:app:spotlessCheck`、`:app:compileDebugKotlin`、`:app:testDebugUnitTest`
（55 → 63 例：`ReaderContractTest` +4 个 `PageStep`，`ReaderLayoutModeTest` +4 个点击区）、
`:app:assembleDebug`。

真机待跑（R8 验收）：

- 左 / 右 1/3 各点一下，中间上半开菜单、中间下半收放栏；
- RTL 下左右对调；纵向阅读时上下翻页、中间左右分菜单与进度；
- 双击仍能放大，且**只放大不翻页**；
- 长按开页面菜单后松手不翻页；按住拖动不翻页；缩放到 3 倍后单击仍然只算一次。

## 18. R9：把「原始感」拆成六个能复现的 bug

R8 之后功能面已经齐了，但真机上仍然「很原始、很多 bug」。这一片不再加功能，
只把 `D:\Project\jmcomic`（JMComicX `ComicReaderScreen.kt`）与
`D:\Project\legado-with-MD3`（`ui/book/manga/`）的漫画阅读器里**已经被真实用户跑过**
的做法对着我们的代码逐条比，找出六处差异，逐条修掉。

六条的共性是：**都能写成一句「用户会怎么骂」，而不是「代码不够优雅」**。

### 18.1 旋转一次，阅读器就废了（真崩溃）

`AndroidManifest.xml` 里宿主只声明了 `android:configChanges="screenSize"`——**方向变化
会重建 Activity**，而阅读器是唯一允许锁横屏的页面（`screen_rotation`）。

重建时的顺序是这样的：

1. 组合被丢弃，`ReaderRoute` 的 `DisposableEffect(route)` 执行 `pageSource.close()`
   ——`SpiderQueen` 被 release、`cache/reader/<gid>` 被删；
2. 但 NavEntry 的 `ViewModelStore` 是**留着**的，`viewModel { }` 会把重建前那个
   `ReaderViewModel` 原样还回来；
3. 这个 ViewModel 手里握着步骤 1 已经关掉的页源，之后每取一页都失败。

修法：给 `viewModel` 打上「页源实例」的键（`"reader-${identityHashCode(source)}"`）。
页源一换就是新一代，旧实例留给 store 自然回收。**这里的键不能是 `route`**：route 在
旋转前后一模一样，正好是最不该变的东西。

`OnlinePageSource.close()` 同时补了一步：把还在 `awaitDownloaded` 里等的协程全部
`completeExceptionally` 叫醒。`queen` 都 release 了，再也不会有人调 `finish()`，
不叫醒的话它们要各自空等到 120s 超时——旋转正好会留下这种僵尸协程。

### 18.2 一次抛滑，把沿途十几页都写进了阅读进度

```kotlin
snapshotFlow { pagerState.currentPage }   // ← 旧代码
```

`currentPage` 在手指还没抬起时就跟着变，惯性滑行的每一页都会触发一次
`PageSettled` → 写 Room + 写 `.ehviewer`。后果：**「读到 30 页，退出去再进来落在 27 页」**
（最后一次写入的时机与惯性有关，不一定是终点页）。

照抄 legado 的三段式：

| 概念 | 状态字段 | 触发源 | 用途 |
| --- | --- | --- | --- |
| 滚到哪了（跟着手指） | `footerPage` | `snapshotFlow { pagerState.currentPage }` | 顶/底栏数字、预取锚点 |
| 读到哪了（落定） | `currentPage` | `snapshotFlow { pagerState.settledPage }` | 写进度、`navigationId` |
| 显示哪一页 | `displayPage`（派生） | `footerPage ?: currentPage` | UI 只读这一个 |

顺带把 `rememberSaveable` 存的那一份改成落定页——进程重建时不该恢复到「滑到一半的那页」。

### 18.3 翻页是瞬移的

`pagerState.scrollToPage(request.page)` 对**所有**跳页生效，包括「点屏幕右边 1/3」。
于是点一下翻页=画面直接换一张，没有滑动过程，手感像在翻 PPT。

`ReaderScrollRequest` 加 `animated`，并配一个纯函数卡住边界：

```kotlin
internal fun shouldAnimateScroll(request: ReaderScrollRequest, from: Int) =
    request.animated && abs(request.page - from) <= 1
```

- 点屏幕 / 音量键（`PageStep`）→ `animated = true` → `animateScrollToPage`；
- 底栏滑块 / 跳页框 → `animated = false` → `scrollToPage`；300 页的画廊要是也 animate，
  跳一页能扫半分钟（legado 的滑块拖动就固定 `animated = false`，注释写得很清楚）。

### 18.4 每页都淡入 + 刷新后显示旧图

全局 `ImageLoader` 是给封面配的：`crossfade(300)` + 磁盘缓存 `thumbCache`。阅读器直接
复用了它，于是：

- 每翻一页都从透明淡入 300ms，观感就是「卡一下」（JMComicX 对阅读器的请求显式
  `crossfade(false)`）；
- 长按「刷新」把**同一路径**的文件重写一遍，Coil 的内存键没变，于是把旧位图原样还回来，
  表现为「刷新点了没反应」；
- 源本来就是本地文件，还往 `thumbCache` 里塞一份纯属白占空间。

请求改成：

```kotlin
ImageRequest.Builder(context)
    .data(file)
    .memoryCacheKey(cacheKey)            // 键 = 路径 + length + lastModified
    .placeholderMemoryCacheKey(cacheKey) // 同键：切缩放模式时不先闪一张小图
    .diskCachePolicy(CachePolicy.DISABLED)
    .crossfade(false)
```

### 18.5 缓存命中也要先闪 150ms 转圈

`LoadingPage` 无条件画 `CircularProgressIndicator`。命中缓存 / 已预取的页解码只要几十毫秒，
所以翻页时总会看见一次「转圈一闪」。legado 用 150ms、JMComicX 用 180ms 的门槛把这个
闪烁藏掉（「150ms 内到位就当没这回事」）。

实现上有个坑：`Queued` 与 `Loading` 如果各自是一个 `LoadingPage(...)` 调用点，组合位置不同，
状态一迁移 `remember` 就被重置——第一个进度事件到达时转圈会**重新**再等 150ms，网速慢时
看起来像卡死。所以中间加了一层 `PageSlot`，让两个状态走**同一个**调用点。

错误占位同时补了底色（`surfaceContainerHigh` 90%）：它可能压在任何颜色的画面上。

### 18.6 按住音量键会以几十页/秒翻过去

`onKeyEvent` 只看 `ACTION_DOWN`，没看 `repeatCount`，而且是「吃按下、放抬起」——
按住不放会连翻，抬起又漏给系统弹音量条。补成：`repeatCount == 0` 才翻，`ACTION_UP`
也返回 true。

### 18.7 顺手补的四条

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 自动翻页 | 设置面板开着也照翻（人被挡在后面） | `overlayOpen` 时暂停，与旧 `GalleryActivity` 一致 |
| 底栏滑块 | 连续取值，松手会落在半页 | `steps` 一页一格 |
| 分页器 `key` | 没传，`ReaderItemUi.key` 是死字段 | 用上了：Error ↔ Page 切换时重建条目，缩放状态不串 |
| `cache/reader/*` | 进程被杀就永久残留（400 页画廊能留几百 MB） | 应用启动时整体清一次；`readerCacheDir()` 收口路径 |
| 预取 | 已滑出窗口的等待协程不撤 | 撤掉并退回 `Queued`（停在 `Loading` 会永远转圈） |
| 顶栏按钮 | `Text("\u2190")` / `Text("\u2261")` 两个字符 | 两个 `res/drawable/ic_reader_*.xml` 矢量 + `Icon` |

> 顶栏为什么不引 `material-icons-core`：Compose 1.13 这条线上它已经停在 1.x 不再跟进，
> 为了两个图标去引一个版本对不齐的依赖不划算。
>
> 更新（全局顶栏改造）：除阅读器浮栏外，其余顶栏已经统一成 `LargeTopAppBar` + `Icons.*`，
> 工程因此引入了 `material-icons-extended`（BOM 钉在 1.7.8，只提供矢量与常量，跟 1.13 主线混用安全）。
> 阅读器仍然用它自己的 `ic_reader_*.xml`：那一栏是叠在翻页区上的浮层，不在这次改造范围内。

还有一个不显眼但影响首帧的：两个分页器都给了 `beyondViewportPageCount = 1`。翻过去时
图片头已经解出来了，不会先出一帧空白；只给 1 是因为整图 fallback（`SCALE_ORIGIN`）下
每多组合一页就是一张全尺寸位图。

### 18.8 进度落盘的节流

`PageSettled` 每次都写 Room + `.ehviewer`。连翻十页就是十条写事务，还会顺带刷新历史列表。

`ProgressThrottle`（纯类，可直接单测）：第一次立即写，之后 2 秒最多一次，被压住的页记成
`pending`；退出时 `flushProgress()` 补上，`onCleared` 再兜一次底。

> **丢的必须是中间页，绝不能是最后一页。** 这条纪律在 `flushProgress` 里写成注释，
> 因为它正是 18.2 那个 bug 的反面。

`flushProgress()` 走 `launchIO`（应用级作用域）而不是 `viewModelScope`——它常常在
ViewModel 已经在销毁的过程中被调用，那时 `viewModelScope` 已经取消了。

### 18.9 与计划 / 参考实现的偏离

1. **没有抄 legado 的九宫格自定义点击区**（`clickActions: 9 个 int`）。EhViewer 的
   点击区是 3 分区 + 中段二分，引导文案已经写死了；做成可配置要动设置页与序列化，
   收益不足以放进这一片。
2. **没有抄 `MangaWebtoonTiles` 那套自绘瓦片高清层**。telephoto 的
   `ZoomableImageSource.coil` 内部就转成了 `SubSamplingImageSource`
   （`Resolver$toSubSamplingImageSource`，`zoomable-image-coil3-0.19.0`），区域解码这块
   我们本来就有；再叠一层自绘瓦片是重复实现。
3. **没有抄 `maxBitmapSize(4096)`**。那是 Coil 2 的 API，Coil3 的
   `ImageRequest.Builder` 上已经没有 `maxBitmapSize`（`coil-core` 3.6.2 实测），
   要拦只能自己写 interceptor 改 decode options；而 telephoto 走的是区域解码，
   真正需要这条兜底的只有 `SCALE_ORIGIN` 整图路径，留到真机复现再说。
4. **没有做「缩放到 >1 时单击不翻页」**。`ZoomableState.zoomFraction` 能拿到倍率，但
   缩放状态是**每页一份**的，要提到 pager 外面就得给每个条目回报、还得判断哪个才是
   当前页；这套同步本身比功能更容易出 bug，暂不做。
5. **自动翻页到末页依旧静默停止**（`ScrollTo(count)` 被钳回原地），与 R3 一致。

### 18.10 验证

四道门禁全绿：`:app:spotlessCheck`、`:app:compileDebugKotlin`、`:app:testDebugUnitTest`
（63 → 69 例：`ReaderContractTest` +6 条，覆盖动画边界、footer/settled 分离、
进度事件去重、节流与退出 flush）、`:app:assembleDebug`。

真机待跑（R9 验收）：

- 阅读器里锁横屏 / 转屏一次，仍然能继续翻页（18.1）；
- 从第 1 页一次抛滑到第 30 页，退出再进，**落在第 30 页**（18.2）；
- 点屏幕右边 1/3，页面是滑过去的；拖底栏滑块到第 100 页，是直接落位（18.3）；
- 缓存过的页翻过去不再闪转圈；长按「刷新」后画面真的换了（18.4 / 18.5）；
- 按住音量键不放只翻一页，且不弹系统音量条（18.6）；
- 设置面板开着时自动翻页暂停（18.7）。

### 18.11 本片刻意留下的功能缺口

条漫连续滚动（`VerticalPager` 装不下 800×4000 的长条页）留到了下一片，已在第 19 节落地。

## 19. R10：条漫连续滚动（第四个阅读方向）

R2 把 `LAYOUT_TOP_TO_BOTTOM` 落成了 `VerticalPager` **逐页**（11.3 第 3 条）。这在长条漫画上
是硬伤：`page_scaling = FIT` 下 800×4000 的页被压成一条细条，必须捏合放大才能读；而
`VerticalPager` 里套 `verticalScroll` 又一定会抢同一根轴上的手势——**分页器装不下条漫**。
JMComicX 的整个阅读器就是一条 `LazyColumn`，legado 也另留了 `WebtoonMangaList`。

R10 因此不改 2 号取值的行为，而是新增第四个方向。

### 19.1 设置层：多一个取值，不是多一个开关

| 层 | 改动 |
| --- | --- |
| `ReaderLayoutMode.kt` | 新增 `LAYOUT_WEBTOON = 3`（旧 GL 栈只有 0/1/2） |
| `Settings.readingDirection` | `coerceIn(0, 2)` → `coerceIn(0, 3)`，旧值含义不变 |
| `ReaderSettings` | 新增 `webtoon: Boolean`，优先级高于 `vertical` |
| `ReaderSettingsStore` | 双向映射收口成纯函数 `readingDirectionOf()`，可单测 |
| 设置面板 | 阅读方向从三选一变四选一，**一次把三个开关都写清楚** |

面板那一条值得单说：`onSelect` 必须同时写 `webtoon / vertical / reverseLayout` 三个字段。
只写其中一个的话会出现「webtoon=true 且 vertical=true」这种半新半旧的组合，而
`readingDirectionOf()` 里条漫优先的分支就是为这种情况留的兜底。

### 19.2 结构：把「谁拥有滚动状态」交还给容器

上一片的 `ReaderScreen` 自己在顶层 `rememberPagerState`，再往下分发分页器。条漫不需要
pager，于是在顶层留一个 pager 只会变成两套状态打架。现在拆成：

```
ReaderScreen            底色 / 点击区去向 / 顶底栏 / 面板 / 引导 / 自动翻页 / 音量键
├── PagedReader         HorizontalPager | VerticalPager + telephoto + 分页器手势
└── WebtoonReader       LazyColumn + 双指缩放 + 行命中
```

两边各自拥有滚动状态与副作用（footer / settled / scrollRequest 的执行），**只向上报两个事**：
`onZone(ReaderTapZone)` 与 `onSettled(page)`。页码因此由 `savedPage` 接力——换模式时
停在原页，进程重建后也能回到原页。

### 19.3 页码口径：可见面积最大

连续滚动下 `firstVisibleItemIndex` 不能用：一页常常比视口高好几倍，一屏里同时压着三页，
它只会报「还露着一角」的那页，页码与进度会来回跳。改成纯函数（`ReaderLayoutMode.kt`）：

```kotlin
internal fun webtoonCurrentRow(rows: List<ReaderVisibleRow>, viewportStart: Int, viewportEnd: Int): Int?
```

取交集高度最大的那一行；面积打平时保留**更靠前**的一页（否则两张短页会交替抢页码）；
完全没交集返回 null（列表还没测量，此时不该上报）。

「落定」沿用 R9 的语义，只是触发源换成 `listState.isScrollInProgress` 变 false 后再等
120ms——惯性刚停时 `layoutInfo` 还会再补一帧，立刻取会记成停稳前的那一页（表现为进度
永远慢半页）。

### 19.4 行高：先验估算 + 文件头精确值

首测高度是最容易翻车的地方（JMComicX 用一整段注释记过）：高度是 0 会让 `scrollToItem`
一路往后组合（整话请求雪崩），偏大则每出一张图就缩一次。

做法：

1. 每行 `Modifier.aspectRatio(1f / h/w)`，高度只由宽度与比例决定；
2. 比例未知时用**本章已测页的中位数**（`derivedStateOf`，条漫一话通常同一画幅），
   一页都没测过时退回 1.4（≈A4）；
3. 行一组合就用 `BitmapFactory.inJustDecodeBounds` 读文件头（几毫秒，只解头部）换成精确值，
   不等 Coil 解码；
4. 比例钳在 `0.2 .. 30`：极端长条（1:30 以上）会把 `Constraints` 压垮。

**没做**的是 JMComicX 的「比例按章节落盘」（`readerHeightPermille`）。差别只在冷启动首次
跳页的估算精度，而 `LazyListState.scrollToItem` 是按下标定位、不累加高度，所以跳页本身
不受影响；留到有真机数据再说。

### 19.4.1 「组合出来就要」：`EnsurePage`

`LazyColumn` 不预组合视口外的项，所以一屏边缘常常压着**预取窗口之外**的行。只靠窗口的话
这些行会长期空白（不是一闪，是一直到滑出屏幕）。

但这里不能顺手用 `Prefetch`：`Prefetch` 的语义是「这是新的窗口」，VM 会连带
`cancelOutOfWindow()` 把窗口外的任务取消——刚被取消的行下一帧又被自己组合出来，于是
取消/重来无限抖动。所以加了单独的意图：

```kotlin
data class EnsurePage(val index: Int) : ReaderIntent   // 只推这一页，不动窗口
```

归约只把 `Queued` 推成 `Loading`（`Loading` / `Ready` / `Error` 一律不动，`Error` 要等
显式重试），VM 只 `load(index)`。

还没完：这些行本来就在窗口外，下一次 `Prefetch` 又会把它们撤掉，撤掉之后行还在屏幕里、
`LaunchedEffect` 一看回到 `Queued` 又要一遍——**取消/重来会一直抖**。所以 VM 里给它们
打一个 `pinned` 标记，`cancelOutOfWindow()` 跳过：任务跑完（成功或失败）自动解除。

### 19.5 缩放：改宽度，不改 layer

- **不能 per-item**：`ZoomableImage` 在纵向 `LazyColumn` 里会和列表抢手势；
- **也不能只 `graphicsLayer` 缩放**：那样行高不变、纵向滚动速度变成 1/zoom，还得补一套
  锚点数学（legado 的 `preserveWebtoonZoomPosition` 就是干这个的）。

采用的是 legado 的第三条路：**缩放改列表宽度**。

```kotlin
BoxWithConstraints {
    LazyColumn(
        Modifier.requiredWidth(maxWidth * zoom)
            .graphicsLayer { translationX = panFraction * viewportWidthPx / 2f },
    )
}
```

宽度变了 → 行高随 `aspectRatio` 变 → 纵向滚动仍是 1:1；横向用 `translationX` 平移，
`panFraction` 夹在 `±(zoom-1)`，正好等于「超出视口的那半边」（拖得到两端，也拖不出空白）。
单指事件完全不消费，双指才 `consume()`。

### 19.6 手势：顺手修掉「一拖就误开页面菜单」

点击区复用 R8 的那套「不消费事件」的观察者，但补了长按：条漫里没有 telephoto 帮忙按行
命中，只能自己按 `y` 找 `visibleItemsInfo`。

写这里才发现 R8 的版本有个真 bug：`waitForUpOrCancellation()` 对「超时（长按）」和
「被滚动取消」**都返回 null**，光看它分不出这两件事。现在改成三态：

```kotlin
private suspend fun AwaitPointerEventScope.awaitPressOutcome(...): PressOutcome
// Up(position) / LongPress / Cancelled
```

只有「超时且移动没超过 touchSlop」才算长按；移动出 slop 一律判取消。`withTimeoutOrNull`
返回 null 才代表超时，这是唯一可靠的判据。

### 19.7 与参考实现的偏离

1. **没做 `MangaWebtoonTiles` 那种自绘瓦片**：telephoto 对分页模式已经够用，条漫这边用
   普通 `AsyncImage`（宽度铺满即原分辨率），没必要再叠一层区域解码；
2. **没做双击缩放**：双击目前是「什么都不做」（点击区会等一个 doubleTapTimeout 后吞掉）。
   要做得先定义「双击落在哪个锚点」，与上面的宽度缩放一起算才有意义；
3. **没做跨章连续拼接**（读到末尾直接接下一话）。EhViewer 是画廊语义，没有「下一话」；
4. **比例不落盘**，见 19.4；
5. **`page_scaling` 在条漫下不生效**（一律按宽度铺满）——条漫没有「适应高度」这种东西，
   设置面板里那一行仍然显示，读了不改行为。

### 19.8 验证

四道门禁全绿：`:app:spotlessCheck`、`:app:compileDebugKotlin`、`:app:testDebugUnitTest`
（69 → **75 例**：`ReaderLayoutModeTest` +5 条，覆盖方向映射的优先级、可见面积选取、
面积打平、未布局、完全滚出；`ReaderContractTest` +1 条 `EnsurePage`）、`:app:assembleDebug`。

真机待跑（R10 验收）：

- 设置里选「连续滚动」，长条页铺满宽度、首尾相接，竖向滑动没有卡顿与跳行；
- 一屏压着两页时，顶栏数字停在「占得多的那一页」，不会来回跳；
- 捏合放大到 2 倍：行变宽变高、纵向滑动速度不变，左右能拖到两端且不露空白；
- 放大状态下松手，单指仍然正常滚动（不会卡住）；
- 拖底栏滑块跳到第 100 页，落位即第 100 页（不扫中间页）；
- 长按弹页面菜单，**按住拖动不会误弹**；
- 切回「从右至左」再切回连续滚动，两次都停在原页。

## 20. R11：滑不动 + 图不居中（两个一进阅读器就能看见的 bug）

R9/R10 之后功能都在，但真机上「怎么滑都不翻页，图还贴在顶上」。两个 bug 互不相干，
一个是 Compose 命中测试的语义，一个是照抄了不该照抄的参数。

### 20.1 滑不动：手势检测器被放在了滚动容器的**兄弟节点**上

R8 把点击区写成「盖在分页器上的全屏 Box + pointerInput」，并在注释里断言
「不消费事件，所以翻页手势照旧穿过去」。**这条断言是错的。**

Compose 的命中测试在兄弟层是**独占**的（`InnerNodeCoordinator.hitTestChild`：某个兄弟
`hasHit()` 之后，只有语义源才会 `shareWithSiblings`；`PointerInputSource.shareWithSiblings`
走的是 `shouldSharePointerInputWithSiblings()`，默认 false）。所以那个覆盖层一上图：

- `down` 全被覆盖层收走，分页器 / `LazyColumn` / telephoto **根本没进场**；
- 单击由覆盖层自己处理 → 「点能翻页」；
- 拖动没人处理 → 「滑不翻页」；双击缩放也一并失效。

看起来像手势半死，实际是下层完全没收到事件。这也解释了为什么它「一直都有」：
R8 起就是这个结构，而 R8 的真机验收清单一直没跑。

修法：**把检测器挂成滚动容器的祖先 Modifier**，不再做覆盖层。

```kotlin
// 之前：兄弟节点，独占命中
Box { HorizontalPager(); ReaderTapZones(Modifier.matchParentSize()) }
// 现在：祖先 Modifier，子节点先拿到事件，这里只观察
Box(Modifier.fillMaxSize().pointerInputTaps(...)) { HorizontalPager() }
```

祖先在命中路径上而且不拦截后代（`hit()` 加完自己继续 `hitTestChild`），所以 pager /
telephoto / LazyColumn 与点击区可以同时工作。条漫那边的双指缩放框是同一个错误，一并改掉。

顺手补的一条纪律：`onZone` / `onLongPress` 这两个回调**不能**进 `pointerInput` 的 key
（它们每次重组都是新实例，key 一变检测器就重建，正在等的那个 `doubleTapTimeout` 会被
直接取消，表现成「双击的第一下被吞」）。改用 `rememberUpdatedState` 取。

> 另一条可行路线是给覆盖层显式打开「与兄弟共享命中」
> （`PointerInputModifierNode.sharePointerInputWithSiblings()`）。没选它：祖先方案不需要
> 维护一个「哪些覆盖层开了共享」的清单，也不会在以后再加浮层时复发。

### 20.2 图不居中：把 `start_position` 无脑喂给了 telephoto

`ZoomableImage(alignment = alignmentOf(startPosition))`。默认设置是
`SCALE_FIT` + `START_POSITION_TOP_RIGHT`，于是：

- `ContentScale.Fit` 只让一个轴向铺满，另一个轴有富余；
- 富余的那一轴被顶到 `TopEnd` → 竖屏看漫画就是「图贴在上/右，下/左留一条黑」。

旧 `ImageView` 里 `start_position` 只对「按原图尺寸绘制」（`SCALE_ORIGIN` / `SCALE_FIXED`）
有效，FIT 系列一律居中。现在收口成纯函数：

```kotlin
internal fun alignmentFor(scaleMode: Int, startPosition: Int): Alignment =
    if (scaleMode == SCALE_ORIGIN || scaleMode == SCALE_FIXED) alignmentOf(startPosition)
    else Alignment.Center
```

### 20.3 还剩一个已知限制：原图 / 适应宽度下的翻页

`SCALE_ORIGIN`、`SCALE_FIXED`、`SCALE_FIT_WIDTH` 这三种模式下图片本来就比视口大，
telephoto 的 `canConsumePanChange()`（内容边界内还有余量就返回 true）会把单指拖动全部
吃掉用来平移——分页器收不到，拖动翻不了页。这不是 bug 而是取舍：要么能平移，要么能翻页。

旧 `GalleryView` 的答案是「拖到边界再拖就翻页」（edge handoff）。Compose 里要复刻，得把
telephoto 的平移量接到 `nestedScroll` 的 `postScroll` 上，属于另一片的量级，先记在这里。

当前这三种模式下翻页走：点屏幕左右 1/3、音量键、底栏滑块。
默认的 `SCALE_FIT` 不受影响（图片装得进视口，telephoto 不消费）。

### 20.4 验证

四道门禁全绿：`:app:spotlessCheck`、`:app:compileDebugKotlin`、`:app:testDebugUnitTest`
（75 → **77 例**：`alignmentFor` 两组，分别钉住「FIT 必居中」与「只有原图尺寸才认锚点」）、
`:app:assembleDebug`。

真机待跑（R11 验收）：

- 左右拖动翻页、拖到一半松手会回弹（分页器正常）；
- 双击能放大、放大后拖动是平移而不是翻页；
- 单击左 / 右 1/3 仍然翻页（点击区没被祖先化搞坏）；
- 竖向逐页模式上下拖动翻页；
- 条漫模式竖向能滚、双指能缩放、长按出页面菜单；
- 默认设置下图片居中，不再贴顶/贴右。
