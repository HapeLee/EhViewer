# 应用级导航宿主方案（P7）

> 状态：P7-a、P7-b 前置（宿主接口隔离）、P7-b 主体（宿主换 NavDisplay）、P7-c 第一子片
> （登录 / 守卫组出壳）与第二子片（历史 / 下载出壳）已落地（见第 10 节）；
> P7-c 剩余页面（收藏、画廊列表、详情族、阅读器）与 P7-d / P7-e 仍是设计稿。
> 前置：15 个 Scene 已全部迁到 `EhComposeScene`，XML Scene 清零（见 `compose-kotlin-refactor-plan.md` 第 2 节）。
> 目标：把「Activity 场景栈 + 每页内部 Nav3 栈」收敛成「单 Activity + 单一 Nav3 栈」，然后删除 `com.hippo.scene`。

## 0. 结论摘要

- 现在是**两套栈**：`StageActivity` 的 Fragment 场景栈，加上每个 `EhComposeScene` 内部的 `NavDisplay`。返回键、参数传递、结果回传都在这两层之间来回倒手。
- 收敛路线是「先加一张网，再逐页搬家，最后拆网」：`P7-a` 引入唯一导航门面 `EhNavigator`（实现仍走旧栈，行为零变化）→ `P7-b` 宿主换成单一 `NavDisplay`，旧 Scene 以兼容 entry 形式挂着 → `P7-c` 逐页出壳 → `P7-d` 宿主 UI（抽屉 / Snackbar / 顶栏）归位 → `P7-e` 删 `Scene`/`Stage`/`Announcer`。
- 每一片都能独立编译、独立回归、独立回滚；`P7-a` 不动 `MainActivity`，风险最低，也是后续所有改动的前置。

## 1. 现状盘点（事实）

| 项 | 现状 |
| --- | --- |
| 旧导航层 | `com.hippo.scene`：`StageActivity` 521 行、`StageLayout` 135 行、`SceneFragment` 133 行、`Announcer` 36 行、`SceneApplication` 42 行、`TransitionHelper` 22 行，合计 889 行 |
| 宿主 | `MainActivity` 602 行，继承 `StageActivity`；持有 `DrawerLayout` / `NavigationView` / `StageLayout` / `DrawerView` / Snackbar 容器；负责 Intent、剪贴板 URL、启动页、`processAnnouncer` |
| 页面 | 15 个 `EhComposeScene` 子类，每个内部 `rememberNavBackStack(startRoute)` + `NavDisplay` |
| 页面能力 | 仍在 `BaseScene`：`toggleDrawer` / `needShowLeftDrawer` / `getNavCheckedItem` / `setNavCheckedItem` / `showTip` / `onCreateDrawerView` / `setLightStatusBar` |
| 调用面 | `startScene(` 43 处、`Announcer(` 53 处、`startSceneForCheckStep` 7 处、`setRequestCode` 4 处、`StageActivity` 引用 33 处、`topActivity` 5 处 |

## 2. 为什么必须收敛

1. **双栈**：返回键要判两层（`EhComposeScene.onBack` 里的 `backStack.size > 1` + `SceneFragment.onBackPressed`），15 个页面各写各的 `onBackPressed`，双击退出的判定散落在页面里。
2. **结果回传靠 tag 列表**：`SceneFragment.addRequest` / `returnResult` 遍历 `mRequestSceneTagList` 找请求方；页面一被销毁就断链（评论列表回填、站点切换都吃这个亏）。
3. **宿主 UI 寄生在页面**：左侧抽屉、导航菜单勾选、Snackbar 都从 `BaseScene` 上取，页面生命周期与宿主壳耦合，平板双栏 / 多窗口这类改造无从下手。
4. **转场不可用**：`TransitionHelper` 只在 Scene 栈内有效；共享元素要求两端在同一棵 Compose 树里，这是列表→详情共享元素转场一直补不回来的根因。
5. **不可测**：导航意图是 `Announcer(Class) + Bundle`，没法在没有 Activity 的环境里断言「点这个按钮应该去哪」。

## 3. 目标架构

### 3.1 分层

```
MainActivity : ComponentActivity
└── EhAppScaffold              // 宿主壳：左侧导航抽屉 + SnackbarHost + NavDisplay
    ├── LocalEhHostState       // 页面可声明：是否显示抽屉、导航勾选项、tip
    ├── LocalEhNavigator       // 唯一导航入口
    └── NavDisplay(backStack)  // 全应用唯一的返回栈
```

- **`EhNavigator`**：`push(route)` / `pop()` / `popTo(route)` / `replaceTop(route)`，只此一个入口；页面不再接触 `Fragment`、`Announcer`、`Class`。
- **Route**：`@Serializable` 的 data class，参数即字段；`EhRoute` 现在那 15 个 `data object` 逐步换成带参 data class（见 3.2）。
- **结果回传**：取消 request/result 机制。原则是**数据单一来源**——凡是被「返回」影响的状态（评论、收藏槽位、下载状态）都已落库/落缓存，页面重新进入时自己读；只有「登录成功」这类一次性事件走 `navResultBus`（宿主持有的 `MutableStateFlow`，消费即清）。
- **页面内抽屉**（快速搜索 / 下载标签 / 收藏分类）**继续留在页面内**：手势归属已经在迁移里定死（左侧边缘给宿主、页面内抽屉 `gesturesEnabled = false`），搬进宿主只会重新引入抢手势的问题。

### 3.2 Route 草案

| Route | 参数 | 说明 |
| --- | --- | --- |
| `GalleryList` | `mode`、`category`、`keyword`、`advanceSearch`、`minRating`、`pageFrom`、`pageTo`、`hash` | 现在传 `ListUrlBuilder`（Parcelable），改成拆字段后天然可序列化、可深链 |
| `GalleryDetail` | `gid`、`token`、`page` | **不再传整个 `GalleryInfo`/`GalleryDetail`**；进页先查 `galleryDetailCache`，未命中再请求 |
| `Reader` | `gid`、`page`、`mode` | 阅读器内部 PageViewer 状态不属于导航 |
| `Progress` | `gid`、`pToken`、`page` | 分页链接建议直接落这里 |
| `Previews` | `gid`、`scrollTo` | |
| `Info` | `gid` | 从缓存取 `GalleryDetail` |
| `Comments` | `gid`、`apiUid`、`apiKey`、`token` | 评论列表改由页面自己拉取/读缓存，不再靠 result 回填 |
| `Favorites` / `History` / `Downloads` | `action`（或 `gid` 定位） | 与现状一致 |
| `SelectSite` / `SignIn` / `CookieSignIn` / `Security` / `WebViewSignIn` | 无参或 `action` | |

序列化约束：Nav3 的返回栈需要可保存，route 必须是 `@Serializable`（kotlinx.serialization 已在用）。`ListUrlBuilder`、`GalleryInfo` 这类 Parcelable 一律拆成字段或只留 id。

## 4. 分阶段实施

| 片 | 内容 | 产出 | 验收 |
| --- | --- | --- | --- |
| **P7-a** 导航门面 | 新增 `EhNavigator` + 参数化 `EhRoute`；43 处 `startScene(Announcer(...))` 改走 `navigator.push(route)`，内部仍委托 `StageActivity.startScene` | 导航意图类型化，行为零变化 | 编译/格式/单测/打包绿；route 序列化往返单测；手工回归同现状 |
| **P7-b** 单栈宿主 | `EhAppScaffold` + 宿主 `NavDisplay`；旧页面以 `Route.CompatScene(clazz, args)` 形式承载（`AndroidFragment` 包一层） | 返回栈只剩一层，返回键判定集中 | 所有页面进出、双击退出、`KEY_GID` 定位、剪贴板 URL 正常 |
| **P7-c** 页面出壳 | 逐页把 `registerDestinations` 提升为宿主 entry，删掉 `startRoute` 与内部 `NavDisplay` | 页面成为真正的 Nav3 entry | 每页独立回归（见第 7 节）；顺序：登录类 → 信息类 → 列表/收藏/下载 → 详情 → 阅读器 |
| **P7-d** 宿主 UI 归位 | 左侧抽屉、导航勾选、Snackbar、状态栏收进 `EhAppScaffold`；`BaseScene` 的那批方法改成 `LocalEhHostState` | 页面不再依赖 `BaseScene` | 抽屉手势/勾选/Snackbar/tip 行为不变 |
| **P7-e** 删遗留层 | 删 `com.hippo.scene.*`、`BaseScene`、`Announcer`、`EhComposeScene`；`MainActivity` 精简 | 单栈、单宿主 | 全量回归；`StageActivity` / `Announcer` 引用归零 |

## 5. 关键决策（需拍板）

| 决策 | 选项 | 建议 | 理由 |
| --- | --- | --- | --- |
| 结果回传 | repository 单一来源 / `navResultBus` / 手写 SavedStateHandle | **repository 为主，`navResultBus` 只服务一次性事件** | 评论、收藏、下载都已落库；删掉回填逻辑最干净，也天然支持进程重建 |
| 参数传递 | `@Serializable` route / `Bundle` | **route** | 可测、可深链、返回栈可保存 |
| 详情/阅读器参数 | 只传 `gid`+`token` / 传 Parcelable | **只传 id** | 进程重建安全；重复打开同一画廊天然复用缓存 |
| 抽屉归属 | 宿主左侧 + 页面内右侧 / 全收进宿主 | **沿用现状** | 手势归属已经验证过，避免返工 |
| 页面内 `NavDisplay` | 保留 / 不保留 | **不保留** | 保留就没有真正消除双栈 |
| 外部 Intent | 各页自己解析 / 宿主统一解析成 route | **宿主统一** | 与 `processAnnouncer` 合并成一处 |

## 6. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 返回栈在进程重建后丢状态 | 每片都跑「开发者选项 → 不保留活动」回归；route 全部可序列化 |
| `GalleryInfo` / `ListUrlBuilder` 序列化工作量 | 只存 id / 拆字段；必要时加一个 `GalleryInfoRecord` DTO |
| 过渡动画期望落空（`Announcer.tranHelper`） | P7-c 前明确统一用 `NavDisplay.transitionSpec`，逐页动画一并删掉 |
| `topActivity` / `MainActivity` 强引用（5 处） | 先抽 `EhHostActivity` 接口（drawer / snackbar / 下载入口），页面依赖接口而非实现 |
| 新旧共存期 Scene 的 View 生命周期 | `CompatScene` 用 `AndroidFragment` 承载，Scene 仍是 Fragment，生命周期不变 |
| 改动面大（43+53 处调用点） | 全部经由 P7-a 门面一次性收敛，之后不再扩散 |

## 7. 回归清单（每片必跑）

- 构建：`compileDebugKotlin`、`spotlessCheck`、`testDebugUnitTest`、`assembleDebug`
- 导航：首页 → 详情 → 评论 → 返回；详情 → 预览 → 阅读器 → 返回；下载页 `KEY_GID` 定位；收藏多选修改；列表粘贴链接建议（详情 / 分页两种）
- 宿主：左侧抽屉手势与勾选、双击退出、Snackbar 位置、状态栏颜色
- 登录：Cookie 登录、WebView 登录成功回传、站点切换
- 异常：进程重建（不保留活动）、外部 Intent / 剪贴板 URL 启动、离线打开详情
- 单测新增点：route 序列化往返、`route → nav item` 映射、返回栈决策纯函数

## 8. 工作量估算

| 片 | 预估（人日） | 风险 |
| --- | --- | --- |
| P7-a 导航门面 | 1.5 ~ 2 | 低：只改调用点，不改宿主 |
| P7-b 单栈宿主 | 2 ~ 3 | 中：兼容 entry 的 View 生命周期、返回键 |
| P7-c 页面出壳（15 页） | 6 ~ 8 | 中高：每页的 `onBackPressed` / 参数 / 结果回填要逐一改 |
| P7-d 宿主 UI 归位 | 2 ~ 3 | 中：抽屉手势、状态栏、Snackbar |
| P7-e 删遗留层 | 2 ~ 3 | 中：`topActivity` 等强引用要换成接口 |
| 合计 | 14 ~ 19 | |

## 9. 第一步（P7-a，已完成，见第 10 节）

先做 **P7-a**，只做三件事：

1. 新增 `EhNavigator`（实现暂时直接转调 `StageActivity.startScene`，保持行为零变化）；
2. 把 `EhRoute` 的 15 个 `data object` 换成带参 `@Serializable` data class，43 处 `startScene(Announcer(...))` 改成 `navigator.push(route)`；
3. 补三个纯函数单测：route 序列化往返、`route → nav item` 映射、`onBackPressed` 的返回栈决策。

这一步不需要动 `MainActivity`、不需要改任何页面布局，但它决定了后面每一片是否「只替换实现」。

## 10. P7-a 落地记录（2026-10-30）

### 10.1 新增（`ui/compose/nav`、`ui/compose/state`）

| 文件 | 职责 |
| --- | --- |
| `nav/EhRoute.kt` | 15 条 route 全部参数化；`GalleryQuery` 取代 `ListUrlBuilder` 作为列表页入参 |
| `nav/EhRoutes.kt` | route ↔ 旧 `Bundle` 的互转；`galleryQueryOf` / `toListUrlBuilder` 是纯函数，可单测 |
| `nav/EhNavigator.kt` | 唯一导航入口：`push` / `pop` / `replaceTop`；`Fragment.ehNavigator()` |
| `nav/NavResultBus.kt` | 一次性事件（`NavSignal.SignedIn`），`StateFlow` + `consume()` 消费即清 |
| `nav/EhBackAction.kt` | 返回键判定纯函数 `ehBackAction(size)` |
| `state/GalleryCommentsStore.kt` | 评论的单一数据来源，写时同步 `galleryDetailCache`，上限 32 条 |

### 10.2 改造点

- **起始路由不再硬编码**：`EhComposeScene` 改为从 `arguments` 经 `legacyRouteOf` 还原 `startRoute`（缓存一次解析结果），15 个页面删掉 `override val startRoute`。同一页面在旧栈与新栈里语义一致，P7-b 换宿主不用再动页面。
- **业务导航全部类型化**：列表→详情/进度、详情→信息/预览/评论/新版本、历史/收藏/下载→详情、登录→Cookie/WebView、换 token→详情（`replaceTop`）都改成 `ehNavigator().push(route)`；`GalleryListScene.startScene(scene, lub)` 换成 `GalleryListScene.open(scene, lub)`（`MainActivity` 的 Intent 入口仍用 `getStartAnnouncer`）。
- **评论回传改仓库**：`GalleryCommentsScene.publishResult()`（`setResult` + `KEY_COMMENT_LIST`）→ `GalleryCommentsStore.publish`；`GalleryDetailScene.onSceneResult` 删除，改在 `onResume` 读仓库。详情页被回收/重建也不会丢更新。
- **登录回传改一次性事件**：`setRequestCode` / `onSceneResult` 全部移除（`SignInScene` 的 `REQUEST_CODE_*` 一并删掉），Cookie / WebView 子页成功后 `NavResultBus.publish(NavSignal.SignedIn(...))`，`SignInScene.onResume` 取一次决定补 `getProfile()` 还是 `finishSignIn()`。登录态本身仍在 `Settings` + `EhCookieStore`。

### 10.3 与计划的偏离

| 计划 | 实际 | 原因 |
| --- | --- | --- |
| `EhNavigator.popTo(route)` | 未实现 | 旧 Fragment 栈不支持遍历返回栈；等 P7-b 有了 Nav3 返回栈再加，避免半成品 API |
| route 名 `Previews` / `Info` / `Comments` | 沿用 `GalleryPreviews` / `GalleryInfo` / `GalleryComments` | 减少 15 个页面 `entry<>` 与 import 的连锁改动，语义无损 |
| 43 处调用点全部走门面 | 业务导航已全部走门面；`MainActivity`（Intent / 导航菜单 / 启动页）与 `SolidScene` 检查链仍走旧 `startScene` | 这两处是宿主职责，P7-b / P7-d 才能整体替换；现在改只会把 `key_action` 纠缠搬到新层 |
| 详情/信息/预览/评论只带 id | 已做到，但 `toLegacyArgs` 会用 `galleryDetailCache` 补一份 `GalleryDetail` | 沿用旧页面的「先出骨架」体验；缓存未命中时退化为按 id 请求 |

### 10.4 验证

- `:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、`:app:assembleDebug` 全绿；新增 6 个单测（`EhRouteCodecTest` 4 个：CBOR 往返 / `ListUrlBuilder` 往返 / uploader 前缀 / `gidOf`；`EhBackStackTest` 2 个）。
- 仍未跑真机回归（见第 7 节清单），特别是「评论回填」与「Cookie / WebView 登录回传」两条链路。

### 10.5 P7-b 前置：宿主接口隔离（第二个落地片）

目的：让 P7-b 变成「只替换实现」。改完这一片，`SceneFragment` / `BaseScene` / `EhNavigator` 里**没有任何** `activity is StageActivity` 或 `activity is MainActivity` 的判断。

新增接口：

| 接口 | 位置 | 职责 | 现有实现 |
| --- | --- | --- | --- |
| `SceneHost` | `com.hippo.scene` | 栈能力：`sceneCount` / `topSceneClass` / `topScene` / `startScene` / `startSceneFirstly` / `finishScene` / `indexOfScene` / 三个页面生命周期钩子 | `StageActivity` |
| `EhHost` | `com.hippo.ehviewer.ui.host` | 壳能力：抽屉开合与锁定、Snackbar、导航勾选、头像、`aboveSnackView`、右侧抽屉重建 | `MainActivity` |

改造：

- `SceneFragment` 的 `startScene` / `finish` / `stackIndex` / 生命周期通知全部走 `sceneHost`。
- `BaseScene` 的 10 个壳方法改成 `host?.xxx`，页面不再认识 `MainActivity`（`mainActivity` 仍保留给对话框/剪贴板/下载这类需要 Activity 的调用）。
- `EhNavigator` 改依赖 `SceneHost`；新增 `Activity.ehNavigator()`，主界面的剪贴板 URL 入口改成 `push(EhRoute.GalleryDetail/Progress)`。
- **删除死代码**：`SceneFragment.result` / `setResult` / `addRequest` / `returnResult` / `onSceneResult` / `RESULT_*`、`Announcer.setRequestCode` / `requestFrom` / `requestCode`、`StageActivity` 里登记请求与 `returnResult` 的调用。`com.hippo.scene` 至此不再有结果回传机制。

P7-b 主体待做清单（顺序即风险顺序）：

1. 新增 `EhNavHostActivity : EhActivity(), SceneHost, EhHost`：`ComposeView` + `NavDisplay(backStack)`，entry 内容用 `AndroidFragment` 包 `sceneClassOf(route)` 的实例（`arguments = route.toLegacyArgs(缓存补的 detail)`）。
2. `MainActivity` 改为继承它；`activity_main.xml` 的 `EhStageLayout` 换成 `ComposeView`，`DrawerLayout` / `NavigationView` / Snackbar 容器保留。
3. `onBackPressed` 换成 `ehBackAction(backStack.size)`；`startSceneFirstly` 换成「清栈后 push」；`registerLaunchMode` 的 SINGLE_TOP / SINGLE_TASK 语义要显式实现（登录页复用、导航菜单）。
4. `SolidScene` 的检查链（security → sign-in → select-site → `target_scene` 反射）是**唯一**还在用 Bundle 反射的地方，需要 route 化（新增 `EhRoute.CheckStep` 或把守卫提到宿主），做完才能真正删 `SolidScene`。
5. `BaseScene.onViewCreated` 里的抽屉锁 / 导航勾选 / 状态栏逻辑迁到宿主或 `LocalEhHostState`。

### 10.6 守卫链 route 化（第三个落地片）

把 `target_scene` + `Class.forName` 那套反射串联彻底删掉，守卫链改成一个纯函数表达。

- `EhRoute.Security` / `EhRoute.SignIn` / `EhRoute.SelectSite` 从 `data object` 变成带 `target: EhRoute?` 的 data class——「链走完要去哪」现在是 route 的一部分，进程重建也不会丢。
- `EhRoutes` 新增：
  - `CHECK_STEP_SECURITY` / `CHECK_STEP_SIGN_IN` / `CHECK_STEP_SELECT_SITE`；
  - `checkStepRoute(step, target, needSignedIn, needSelectSite, homepage)`——**纯函数**，登录态 × 站点选择 × 有无目标 8 种组合全部可断言；
  - `initialCheckRoute(target)`（冷启动第一跳）、`nextCheckRoute(step, target)`（跑完一跳问下一跳）、`targetOf(route)`。
- `toLegacyArgs` / `legacyRouteOf` 支持 target 的 `target_scene` / `target_args` 往返，过渡期旧页面仍能从 `arguments` 拿到目标。
- `SolidScene.startSceneForCheckStep`、`KEY_TARGET_SCENE`、`KEY_TARGET_ARGS`、`CHECK_STEP_*` 常量全部删除；`SolidScene` 现在只剩一个标记作用（宿主用它判断「栈顶是不是守卫页」）。
- `EhUrlOpener.parseUrl` 返回 `EhRoute?` 而不是 `Announcer`。
- 主界面：`launchAnnouncer` 改由 `initialCheckRoute()` 生成，`processAnnouncer` 换成 `processRoute(route)`；导航菜单、剪贴板、外部链接（`VIEW`）、分享（文本 / 图片）、下载通知入口全部经 route 表达。`GalleryListScene.getStartAnnouncer` 随最后一个调用点一起删除。

至此，直接构造 `Announcer` 的地方只剩两处：`MainActivity.announcerFor`（适配旧宿主的临时适配器）与 `EhNavigator.push`（route → 旧协议）。P7-b 把宿主换成 `NavDisplay` 时，这两个函数一起删掉，调用点不用动。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、`:app:assembleDebug` 全绿；`nav` 包单测从 6 个增加到 14 个（新增 `CheckStepRouteTest` 8 个用例）。

### 10.7 P7-b 主体：宿主换成 NavDisplay（第四个落地片）

`MainActivity` 不再继承 `StageActivity`，改成继承新的 `EhNavHostActivity`；`activity_main.xml`
里承载旧 Fragment 栈的 `EhStageLayout`（`@+id/fragment_container`）换成
`androidx.compose.ui.platform.ComposeView`（`@+id/nav_host`）。

新增两个文件：

- `ui/host/EhNavController.kt`——**返回栈只有一份状态**：一条 `MutableList<NavKey>`。
  组合还没起来时（`Activity.onCreate` 里解析启动 Intent）压栈先攒在 `pending` 队列，
  `attach()` 之后按序回放，冷启动跳转不会被吃掉。对外只暴露 `push` / `pop` /
  `replaceTop` / `popTo` / `popRoute` / `pushAsRoot` / `clearAboveRoot` / `refreshTop`。
- `ui/host/EhNavHostActivity.kt`——`abstract class EhNavHostActivity : EhActivity(), SceneHost`。
  `onCreate` 的顺序：先塞根页面 → `setupContent()`（子类 `setContentView` 并绑定抽屉）→
  给 `R.id.nav_host` 挂上 `EhTheme { EhSceneDisplay(navController) }` → 解析启动 Intent。
  子类要实现的抽象成员从 `containerViewId` / `launchAnnouncer` / `onCreate2` 换成
  `initialRoute()` / `setupContent()`；`onStartSceneFromIntent` 的返回类型从 `Announcer?`
  换成 `EhRoute?`。返回键：`onInterceptBack()` → 栈顶 `SceneFragment.onBackPressed()`
  （默认实现走 `finish()`）→ 只剩根页面时 `super.onBackPressed()`。

迁移期所有页面仍走 `CompatSceneEntry`：每个 entry 一个 `FragmentContainerView`，
fragment 由 `FragmentFactory` 反射创建，入参由 `route.toLegacyArgs(cachedGalleryDetail(route))`
得到，entry 离栈即 `remove`。P7-c 把页面换成纯 `@Composable` 之后整体删掉。

**坑（已修）**：容器 id 不能用 `View.generateViewId()`。旋转重建时 FragmentManager 会先把
旧 fragment 恢复出来，它记着的 `mContainerId` 必须能在新视图树里找到，否则 fragment 一直拿不到
容器、页面一片空白。改成由 route 类名哈希推导出稳定 id（`compatContainerIdOf`，高位固定
`0x01` 避开 `R.id.*`），并且在 `post` 回调里判断 `fragment.view?.parent !== container`
决定要不要重新 `add`——这样恢复出来的 fragment 也能在新容器上重建 View。
（Fragment 1.9 的 `createView()` 对「恢复态 + 容器缺失」是跳过建 View 而不是抛异常，
所以这个时机是安全的。）

顺带清掉的东西：

- `MainActivity` 里 `Announcer` 适配器 `announcerFor` 删除；`startRoute` 的活交给宿主的 `pushRoute`。
- `ACTION_START_SCENE` / `KEY_SCENE_NAME` / `KEY_SCENE_ARGS` 迁到 `EhNavHostActivity` 的
  companion，`UrlOpener` / `DownloadService` 的引用同步改掉。
- 不再有 Activity 继承 `StageActivity`，于是 `StageActivity.kt` / `StageLayout.kt` /
  `SceneApplication.kt` / `EhStageLayout.kt` 一并删除（−904 行），`EhApplication` 父类改回
  `Application()`。`com.hippo.scene` 包保留：`Announcer` / `SceneHost` / `SceneFragment` /
  `TransitionHelper` 仍是 `EhNavHostActivity` 与 `EhNavigator` 的公共协议。
- `MainActivity` 的 `init { registerLaunchMode(...) }` 整块删除：launch mode 现在是
  `EhNavController.push` 的统一语义（已在栈里就弹出其上所有页面），不再按类注册。
  **行为差异**：原来 `LAUNCH_MODE_STANDARD` 的详情页（`GalleryDetail` / `GalleryInfo` /
  `GalleryComments` / `GalleryPreviews` / `Progress`）以前可以叠多份，现在同一 route 只会回弹到已
  有的那一份。这个语义变化要在 P7-c 的回归里确认可接受。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿，无新增编译告警。

### 10.8 P7-c 第一子片：登录 / 守卫组出壳（第五个落地片）

守卫链这五页不再继承 `EhComposeScene`，改成直接的 `@Composable`，注册到宿主的
`entryProvider` 里——`CompatSceneEntry` 那一层（以及它内部那第二个 `NavDisplay`）对它们不再存在。

出壳的页面：`SecurityScene` / `SignInScene` / `CookieSignInScene` / `WebViewSignInScene` /
`SelectSiteScene`。文件名不变，但内容从 `class Xxx : EhComposeScene()` 变成
`@Composable fun XxxScene(...)`。

**新的宿主侧入口**（`ui/host/EhHostState.kt`）：页面失去 Fragment 之后就没法再
`activity as? EhHost`，所以补了两个 CompositionLocal：

- `LocalEhHost`（`EhHostState`：`showTip`×2 / `setNavCheckedItem` / `setDrawerLockMode` /
  `toggleDrawer` / `updateProfile` / `hideSoftInput`）。默认 `null`——脱离宿主运行时
  （预览 / 单测）提示类调用安静失效。实现由 `EhNavHostActivity.hostState` 提供，
  内部转发给子类的 `EhHost`（目前只有 `MainActivity`）。
- `LocalEhNavigator`（`EhNavigator`，由 `EhNavController` 实现）。带一个
  `OnHostResume { }` 辅助——出壳页面没有 `onResume()`，但手势锁要「回到前台自动拉指纹」、
  登录页要「回到前台收子页结果」，所以用 `LifecycleEventObserver` + 「已经是 RESUMED 就补一次」
  的组合还原这个语义。

**launch mode 反射链路彻底拆掉**：`EhNavigator` 过去靠 `Announcer(sceneClassOf(route))`
反射建 Scene，出壳页面没有类可反射。现在 `Fragment.ehNavigator()` / `Activity.ehNavigator()`
都改走 `EhNavigatorProvider`（`EhNavHostActivity` 实现），`push` / `pop` / `replaceTop`
直接落在 `EhNavController` 上。`EhNavigator.push` 不再需要类名——
所以 `sceneClassNameOf` 被拆成 `compatSceneClassNameOf(route): String?`，
出壳页面返回 `null`，还在兼容承载里的返回类名。

**`SolidScene` 判断也换成 route 判断**：`MainActivity.isSolid` 原来用
`SolidScene::class.java.isAssignableFrom(topSceneClass)`，但所有页面都继承
`EhComposeScene : SolidScene`，这个判断**恒为 true**——等于剪贴板提示一直是死的。
改成 `isSolidRoute(route)`（只有 `Security` / `SignIn` / `SelectSite` 为 true），
剪贴板提示恢复成设计意图。同理抽屉锁从 `needShowLeftDrawer()` 改成
`EhRoute.hasLeftDrawer`，由宿主在 `EhSceneShell` 里按 route 应用。

**顺手修的两个真 bug**：

1. 兼容承载的 Fragment tag 原来用 Scene 类名。同一个类可能在栈里有多份
   （不同查询条件的 `GalleryList`），tag 撞车会让 FragmentManager 把旧 fragment 往新容器里
   塞，抛 `Can't change container ID of fragment`。改成 `compatSceneKeyOf(route)` =
   `类名#route.toString()`——数据类的 `toString()` 稳定，旋转重建还能算出同一个 tag
   和同一个容器 id。
2. `navigator.pop()` 弹到栈底时现在调用 `EhNavController.onFinishStage` 收掉整个宿主，
   对应旧 `StageActivity.finishScene` 在只剩一页时 `finish()` 的行为
   （手势锁重试次数耗尽就是这条路径）。

**已知取舍**（P7-c 后续页面的回归清单要覆盖）：

- 登录类页面的协程从 `lifecycleScope` 换成 `rememberCoroutineScope`，旋转会取消请求。
  旧实现虽然请求不取消，但 Fragment 重建后 `loading` 本来就丢了，用户可见结果一致。
- `SignInScene` 的用户名 / 密码 / `SecurityScene` 的重试次数改用 `rememberSaveable`，
  旋转不再丢（旧实现只有重试次数存了 `onSaveInstanceState`）。

验证：四道门禁全绿，无新增编译告警；`nav` 包单测仍 14 个。

### 10.9 P7-c 第二子片：历史 / 下载出壳（第六个落地片）

`HistoryScene` / `DownloadsScene` 也变成 `@Composable`，注册进宿主 `entryProvider`。

生命周期回调的对应关系：

| 旧（`SceneFragment`） | 新（`@Composable`） |
|---|---|
| `onCreate` 里注册监听 | `DisposableEffect { add; onDispose { remove } }` |
| `onDestroy` 反注册 | 同上 `onDispose` |
| `onSaveInstanceState` | `rememberSaveable` |
| `onBackPressed` 里退出多选 | `BackHandler(enabled = 有选中)` |
| `onNewArguments` 换参数 | route 换新（见下） |
| `onResume` 同步 `listMode` | `OnHostResume { }` |

**下载页的 Intent 协议从「类名」解耦**。下载页是唯一还靠外部 Intent 起页面的场景
（通知栏「清掉服务」「跳到某项」），原来 Intent 里塞的是 `DownloadsScene::class.java.name`，
`startSceneFromIntent` 再用 `Class.forName` 反射一次。页面出壳后类没了，于是：

- `EhRoutes.kt` 新增 `SCENE_NAME_DOWNLOADS` / `KEY_SCENE_DOWNLOAD_GID` /
  `KEY_SCENE_DOWNLOAD_ACTION` / `SCENE_DOWNLOAD_ACTION_CLEAR_SERVICE` 四个常量，
  名字是写死的字符串（值仍是原来的类名），由 `legacyRouteOf` 映射成 `EhRoute.Downloads`。
- `EhNavHostActivity.onStartSceneFromIntent` 的入参从 `Class<*>` 换成 `String`，
  宿主里最后一次 `Class.forName` 消失。

**`SINGLE_TASK` 语义补回来**：`EhRoute` 新增 `navSlot`，`EhNavController.push` 按
`navSlot` 而不是 `equals` 找「同一页」。下载页的 `navSlot` 恒为 `"Downloads"`
（忽略 `gid`），其余页面取 `toString()`——这样不同查询的列表页、不同 gid 的详情页
仍然各自算不同页，而通知栏带新 `gid` 再进来是**回到已有那份并换参数**，
不是叠第二层。

`navCheckedItem` 也从「恒为 0」补成真值：`Favorites` → `nav_favourite`、
`History` → `nav_history`、`Downloads` → `nav_downloads`。

**下一片的阻塞点（已查清）**：`FavoritesScene` 用 `EhRequest.enqueue(this)` 加
`inner class` 回调（回调要读 `taskId`、调 `loadLocal`），还依赖
`MaterialDatePicker.show(requireActivity().supportFragmentManager, ...)`。
`EhRequest` 已有 `enqueue(scope: CoroutineScope)` 重载，所以路径是
「回调类提成顶层 + 传 scope + 用 `taskId` 校验」，日期选择器换成 Compose 对话框。
`GalleryListScene` 的 `getNavCheckedItem()` 是动态的（跟着站点 / 模式走），
出壳时要一并把这份派生搬到 route 上。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.10 P7-c 第三子片：收藏页出壳（第七个落地片）

`FavoritesScene` 换成 `@Composable` + `FavoritesViewModel`（`ui/scene/FavoritesViewModel.kt`）。
收藏页比前两页重：分类抽屉、分页、多选修改收藏（删除 / 移动 / 从本地加到云端）、按日期跳转
都在这一页，所以状态没有留在组合里，而是整体搬进 ViewModel——`Nav3` 的
`rememberViewModelStoreNavEntryDecorator` 让 ViewModel 的生命周期等于「在返回栈里」，
旋转不重建列表，与旧 Fragment 一致。

生命周期 / 宿主能力的对应关系：

| 旧（`SceneFragment`） | 新 |
|---|---|
| `onCreate` 里 `loadCurrent()`（实际在 entry 的 `LaunchedEffect`） | ViewModel `init` |
| `onResume` 同步 `listMode` | `OnHostResume { vm.syncListMode() }` |
| `onBackPressed`：多选 → 搜索 → 退出页面 | `BackHandler(enabled = 有选中 \|\| searchMode)`，其余情况交回 `NavDisplay` |
| `onSaveInstanceState` 存 `urlBuilder` / `searchMode` / `favCountArray` | 不需要（ViewModel 活过旋转） |
| `EhRequest.enqueue(this)` | `enqueue(viewModelScope)`（ViewModel clear 时请求一起取消） |
| `getString` / `context` | 构造时传入的 `applicationContext` |
| 回调里直接 `showTip` | 一次性 `tip` 状态，由场景 `LaunchedEffect` 消费后 `consumeTip()` |

`MaterialDatePicker` 这一片**没有**换成 Compose 对话框：它只需要宿主 Activity 的
`supportFragmentManager`，而 `LocalContext` 拿到的就是 `MainActivity`，改造收益小于
同时换交互形态的风险，留到 P7-d 收口。

**顺手修的真 bug**：上一片（10.9）注册 `entry<EhRoute.Downloads> { DownloadsScene(it) }`
之后，忘了删掉兼容承载组里那行 `entry<EhRoute.Downloads> { CompatSceneEntry(it) }`。
`entryProvider` 对同一个 key 是「后注册覆盖先注册」，而 `compatSceneClassNameOf(Downloads)`
已经返回 `null`——下载页会渲染成空白。两行重复一起清掉，兼容承载组只剩仍未出壳的页面。

`EhRoutes` 同步收口：`Favorites` 加进 `compatSceneClassNameOf` 的 `null` 分支（不再需要
`CompatSceneEntry`），`legacyRouteOf` 里那条旧类名映射删除（收藏页没有外部 Intent 入口）。

**已知取舍**：进程被杀后重建时，`urlBuilder` 里的搜索关键词 / `searchMode` 不再恢复
（旧实现存了 `onSaveInstanceState`）。收藏夹分类不受影响——它走 `Settings.recentFavCat`。
若要补齐，得让 ViewModel 带 `SavedStateHandle`，这一片先不做。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.11 P7-c 第四子片：信息 / 进度 2 页出壳（第八个落地片）

`GalleryInfoScene` / `ProgressScene` 换成 `@Composable`，兼容承载组只剩
`GalleryList` / `GalleryDetail` / `GalleryPreviews` / `GalleryComments` / `Reader` 五页
（`Reader` 卡在 `GalleryActivity` 的 Uri 通道，见 10.5）。

**信息页**：route 只带 `gid`，整块 `GalleryDetail` 不再经 `arguments` 传递，改成
`cachedGalleryDetail(route)` 读 `galleryDetailCache`（详情页加载成功时写入）。
缓存未命中就是空列表——与出壳前 `toLegacyArgs` 传 `null` 的表现一致，不额外发请求。
`getString` 换成组合里的 `LocalContext`，行构造提成顶层 `buildInfoRows`；
复制动作仍是 `addTextToClipboard(value, false)`，需要时再补
`Settings.putClipboardTextHashCode`（避免宿主把刚复制的画廊链接又弹一次）。

**进度页**：两种状态（进行中 / 失败可重试）加一跳（换 token 成功 → `replaceTop` 到详情页）
搬进 `ProgressViewModel`。出壳前的旋转语义是「`onSaveInstanceState` 存参数、`onCreate`
重跑一次请求」；现在 ViewModel 活过旋转、请求不重发。为此补了两条防护：

- `start(route)` 只在参数真的变了才重跑；
- 回调里再比对一次 route，旧结果作废。

否则同槽位换参数时，会拿旧 token 跳到错误的分页。`EhRequest.enqueue()`（走 `GlobalScope`）
改成 `enqueue(viewModelScope)`，页面被 `replaceTop` 换掉时在途请求一起取消。
「进度页不解锁左侧抽屉」原本是 `needShowLeftDrawer()` 覆写，现在由 `EhRoute.hasLeftDrawer`
表达，场景里没有额外代码。

`EhRoutes` 收口：两页并入 `compatSceneClassNameOf` 的 `null` 分支，`toLegacyArgs` 与
`legacyRouteOf` 里对应的分支（连同 `KEY_*` / `ACTION_*` 常量引用）一并删除——这两页都没有
外部 Intent 入口，参数只从 route 来。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.12 P7-c 第五子片：预览图页出壳（第九个落地片）

`GalleryPreviewsScene` 换成 `@Composable` + `GalleryPreviewsViewModel`，
兼容承载组只剩 `GalleryList` / `GalleryDetail` / `GalleryComments` / `Reader` 四页。

**分页状态**：出壳前是 `SceneFragment` 里的「页块」列表（每块记录服务端分页在合并列表里的
起点），这套逻辑原样搬进 ViewModel。两处由此变干净：

- 旧 `onDestroyView` 要手动清 `inFlight`——view 重建会取消请求，不清就会「既不加载也不显示
  进度」。请求现在跟着 `viewModelScope` 走，这个补丁直接删掉。
- 旋转不再丢已加载的页块（旧实现只存了 `GalleryInfo`，重建后从 `startPage` 重来）。

**缓存取代 `arguments`**：整块 `GalleryInfo` 不再经旧 `Bundle` 传，改为
`cachedGalleryDetail(route)` 取。拿不到就与出壳前 `arguments` 里没有 `GalleryInfo` 一致——
直接报「找不到画廊」，不额外发请求。

`scrollTo` 的语义原样保留（`-1` = 详情页已展示完首页预览、从第二页接着看；正数 = 首页内定位），
因为它是 `GalleryDetailScene.showPreviews()` 算好放进 route 的，与页面实现无关。

点缩略图仍然起 `GalleryActivity`（旧阅读器的 Intent 协议）——阅读器出壳卡在 Uri 通道，
这一片不碰。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.13 P7-c 第六子片：评论页出壳（第十个落地片）

`GalleryCommentsScene` 换成 `@Composable` + `GalleryCommentsViewModel`，
兼容承载组只剩 `GalleryList` / `GalleryDetail` / `Reader` 三页。

**从「整块 `GalleryDetail`」改成「只要四个字段」**：出壳前靠 `arguments` 传 `GalleryDetail`
（`onSaveInstanceState` 里还存了一份）。现在请求用的 `gid` / `token`、投票用的
`apiUid` / `apiKey` 全都来自 `EhRoute.GalleryComments`，缓存里的详情只在两处出现：

- 首次进页时的「评论列表从哪来」（`GalleryCommentsStore.latest(gid)` 优先于详情里的快照）
- 「打开链接」时作为 `UrlOpener.openUrl(..., galleryDetail)` 的上下文

也就是说缓存未命中不再是功能性问题——评论照常能拉、能发、能投票。

**生命周期对应**：

| 旧 | 新 |
|---|---|
| `onBackPressed`：评论框开着就关框，否则退出 | `BackHandler(enabled = composerVisible)`，顶栏返回箭头走同一分支 |
| `onSaveInstanceState` 存 `GalleryDetail` | 不需要（ViewModel 活过旋转） |
| 发评论/关评论框后 `hideSoftInput()` | ViewModel 递增 `hideKeyboardRequests`，场景 `LaunchedEffect` 里收键盘（初值 0 不触发） |
| `EhRequest.enqueue(this)` | `enqueue(viewModelScope)` |

富文本 → BBCode 的转换留在场景里（`AnnotatedString.toSpannable().toBBCode().trim()`），
ViewModel 只接收最终字符串和 `commentId`，不依赖 Compose 文本类型。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.14 P7-c 第七子片：画廊列表页出壳（第十一个落地片）

`GalleryListScene` 换成 `@Composable` + `GalleryListViewModel`。这一页五个入口
（首页 / 订阅 / 热门 / 榜单 / 任意搜索结果）共用，是 P7-c 里状态最多的一页。

**入口常量搬进 nav 包**：`Settings.launchPageGalleryListSceneAction` 存的是
`"action_homepage"` 这类字符串，值直接搬成 `LIST_ACTION_*`，**字符串不变**，
老用户的「启动页」设置继续有效。`MainActivity` 的四个抽屉入口一并改用新常量。

**就地搜索不换页面**：搜索改的是 `urlBuilder`，route 保持原样——与出壳前一致
（旧实现也是「重置 urlBuilder + 重发请求」，不压新栈）。代价是抽屉勾选不再能从 route 推导，
所以 ViewModel 多一个 `navCheckedRes`，换模式时推给宿主；`EhRoute.navCheckedItem` 只管
「刚进页面时的高亮」。

**返回键**：双击退出只在栈底生效。为此给 `EhNavigator` 加了 `isRoot`——旧实现看的是
`stackIndex`，Nav3 的 entry 不知道自己排第几，只能问控制器。非栈底页面不注册 `BackHandler`，
交给 `NavDisplay` 弹栈。

**其余对应**：

| 旧 | 新 |
|---|---|
| `registerForActivityResult` 选图 | `rememberLauncherForActivityResult(PickVisualMedia)` |
| `onResume` 同步 `listMode` | `OnHostResume { vm.syncListMode() }` |
| `DownloadManager.add/removeDownloadInfoListener`（`onCreate`/`onDestroy`） | `init` / `onCleared` |
| 收藏槽位、下载状态就地改 `GalleryInfo` + `version++` | 原样保留，快照里带 `version` |

**已知取舍**：进程被杀重建后，页面回到 route 里那份原始查询（旧实现把 `ListUrlBuilder`
存进 `onSaveInstanceState`），搜索关键词与榜单页码会丢。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.15 P7-c 第八子片：画廊详情页出壳（第十二个落地片，P7-c 收尾）

`GalleryDetailScene` 换成 `@Composable` + `GalleryDetailViewModel`。这是最后一块大石头：

- **数据装载**：参数只从 `EhRoute.GalleryDetail` 来；`galleryDetailCache` 命中就直接进
  `NORMAL`，未命中才请求。请求回调仍登记进 `application` 的全局表（旧 Intent 协议要按 id
  找请求者，`putGlobalStuff` 本身不是去重），`onCleared` 里按 id 摘掉。
  「本页已经在请求中」的判断还是 `requestId != -1 && containGlobalStuff(requestId)`。
- **收藏 / 下载 / 评分 / 标签投票 / 归档 / 种子**：请求全在 ViewModel，用 `viewModelScope`；
  归档与种子列表的结果缓存在 ViewModel 里，对话框第二次打开不重新请求（与旧行为一致）。
- **四个对话框留在场景**：评分 / 归档 / 种子 / 删除下载仍用原 XML 布局 + `AlertDialog`。
  理由是一次弹出一次关闭，丢掉不影响页面状态——出壳前的 `AlertDialog` 同样活不过重建；
  但请求本身已经不随 Activity 销毁而取消。
- **`syncCommentsFromStore()`** 保留（旧实现已有）：评论页只写 `GalleryCommentsStore`，
  详情页回前台读一次，不依赖 `setResult` 回传。

**已知取舍**：`onProvideAssistContent` 的 `webUri` 没有对应钩子（Fragment 生命周期回调里的
能力），暂时不设置。

**收尾状态**：`compatSceneClassNameOf()` 只剩 `EhRoute.Reader` 一个非 null 分支。
阅读器卡在 `GalleryActivity` 的 `Uri` 通道（`setDataAndType(uri, "image/*")`），
参数表达不进 route；要出壳得先把阅读器入口改成 `EhRoute.Reader(page)` 这类结构化参数，
那是 P7-d 的事。`CompatSceneEntry` / `com.hippo.scene` 的删除（P7-e）已经可以排期。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.16 P7-e：删掉旧 Scene 层（第十三个落地片）

页面全部出壳之后，`SceneFragment` 这一层只剩「为了编译通过」的存在。这一片把它整体删掉：

**删掉的文件**（13 个）：

| 范围 | 文件 |
|---|---|
| 旧场景框架 | `com/hippo/scene/` 四个：`SceneFragment` / `SceneHost` / `Announcer` / `TransitionHelper` |
| 旧场景基类 | `BaseScene` / `ToolbarScene` / `SolidScene` / `ReaderScene` / `TransitionNameFactory` |
| 旧胶水 | `EhComposeScene` / `EhCallback` / `CompatSceneEntry`（含 `compatContainerIdOf`） |
| 旧列表控件 | `GalleryAdapter` / `GalleryHolder`（已被 Compose 列表取代） |

**`BaseScene.LENGTH_SHORT/LONG` → `TIP_LENGTH_SHORT/LONG`**：取值不变（`0`/`1`），
搬到 `ui/host/EhHost.kt` 与 `showTip(length)` 放在一起，调用方不再需要 import 场景层。

**`EhCallback` 的两件事分头处理**：`showTip` 现在由回调闭包里调宿主
（`{ resId, len -> host.showTip(context.getString(resId), len) }`，见 `HistoryScene`），
`cancel()` 由 ViewModel 的 `viewModelScope` 统一接管——原来的「持有 Context 以便随时弹 Toast」
这个理由已经不存在了。

**Intent 里不再传「类名 + arguments」**：`UrlOpener` 在别的窗口里拿到画廊链接时，
把 route 用 CBOR 序列化后放进 `KEY_SCENE_ROUTE`，宿主 `decodeRoute` 再压栈
（`encodeRoute` / `decodeRoute` 在 `EhRoutes.kt`，序列化格式与 Nav3 保存返回栈一致）。
下载页仍然走「名字 + 两个标量」——那是 `DownloadService` 在服务进程里拼的 Intent，
拿不到 route 对象，`legacyRouteOf` 只为它保留了这一个分支。

**`EhRoute.Reader` 一起删了**：全仓没有生产者（阅读器跑在 `GalleryActivity` 里），
留一个永远压不进栈的 route 只会误导人。阅读器要进 Nav3，得先把它改成
`EhRoute.Reader(gid, page)` 并从 `GalleryActivity` 的 `Uri` 通道迁移出来——那是 P7-d。

**已知影响**：升级后如果 `SavedState` 里还留着带 `Reader` 项的旧返回栈，恢复会失败，
重开应用即可（一次性）。

**剩下的收尾（P7-f）**：`ui/scene` 包现在放的是「Compose 场景 + ViewModel」，
名字和内容已经不符；`scene_*.xml` / `dialog_rate.xml` 之外的旧布局、`R.id` 引用
（`right_drawer` 等）也可以一起清。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.17 P7-f：按功能拆包 + 清死资源（第十四个落地片）

**`ui/scene` → `ui/page`**：`SceneFragment` 已经删了，目录名却还在暗示 Fragment。拆成

```
ui/page/security/   SecurityScene / SignInScene / SelectSiteScene / CookieSignInScene / WebViewSignInScene
ui/page/history/    HistoryScene
ui/page/favorites/  FavoritesScene + FavoritesViewModel
ui/page/downloads/  DownloadsScene
ui/page/gallery/    List / Detail / Info / Previews / Comments / Progress（各带 ViewModel）
```

类名保持 `*Scene`：改名要牵动文档与几十处注释，功能收益为零，等合适的机会一起做。
拆包只用了 `git mv`（历史保留）+ 改 `package` 行 + 宿主 14 个 import——
拆得动的前提是**页面之间没有互相引用**：每个页面只被 `EhNavHostActivity` 的
`entryProvider` 引用一次，跨页跳转一律走 `EhRoute`。这也是 P7-c 坚持「页面不直接 new 页面」
换来的红利。

`SCENE_NAME_DOWNLOADS` 保留原字符串值（`com.hippo.ehviewer.ui.scene.DownloadsScene`）：
`DownloadService` 在服务进程里按这个值发 Intent，改值会让运行中的服务打不开下载页。
代码里已经标注「只当协议常量，不要反射」。

**清掉的死资源**（22 个）：

- 布局 4 个：`item_gallery_grid` / `item_gallery_list` / `item_gallery_list_thumb_height` / `scene_toolbar`
- 动画 4 个：`anim/scene_*.xml`（`TransitionHelper` 删掉后无人使用）
- 图标 / 背景 14 个：`v_archive_primary_x48`、`v_heart_*`、`v_share_primary_x48`、`v_similar_primary_x48`、
  `v_utorrent_primary_x48`、`v_last_page_x24`、`v_pencil_dark_x24`、`v_pin_top_24`、
  `v_dots_vertical_secondary_dark_x24`、`category_background`、`tile_background`、`round_side_rect`、`spacer_x6`

**踩坑提醒**：`res/xml/` 下的 `backup_scheme` / `data_extraction_rules` / `filepaths` / `locale_config`
在代码里零引用，看起来同样该死——但它们由 `AndroidManifest.xml` 以 `@xml/...` 引用，
判断死资源时必须把 Manifest 和 XML 里的 `@xxx/name` 一起算进来。

**还可以继续清的**：`activity_main.xml` 里已经没人填充的 `right_drawer`
（`DrawerView` + `mRightDrawer` 的可见性判断）、以及一堆只被布局引用的 `values` 条目。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.18 收尾清理：右侧抽屉 + 死 dimens + 传输层单测（第十五个落地片）

**右侧抽屉整条链路删掉**：`activity_main.xml` 的 `right_drawer` 节点、`MainActivity.mRightDrawer`
字段与初始化、`isDrawerOpen` 里对它的判断、以及 `com.hippo.widget.DrawerView` 这个控件本身。
出壳之后右侧抽屉由各页自己的 Compose 抽屉承担（收藏分类、快速搜索、下载标签都搬过去了），
宿主那层 `DrawerLayout` 只剩左侧导航。顺带好处：`showTip` 的「抽屉开着就关掉抽屉」判断
少了一个永远为 false 的分支。

**dimens 清 31 条**：`gallery_list_*` / `gallery_grid_*` / `search_*` / `fab_*` /
`gallery_detail_thumb_*` 这些只服务于已删布局的尺寸。复核口径要**同时**查 `@dimen/x`
和 `R.dimen.x`——`dad_*`（DrawerArrowDrawable）、`add_*`、`rating_*`、`lock_pattern_*`
看着零引用，其实全是在 Java 控件里按 `R.dimen` 取的。`values/strings.xml` 暂不动：
删一条默认字符串要在所有 `values-*/` 里同步删，容易误伤，留到专门的资源清理里做。

**补上传输层单测**（`EhRouteTransportTest`，4 例）：P7-e 引入的
`encodeRoute` / `decodeRoute` 是启动路径上的新机制，之前零覆盖。现在锁住：

1. 9 种 route 的 CBOR 载荷往返；
2. 坏载荷 / 空载荷安静退化成 `null`（宿主回落到初始页，而不是崩在启动路径）；
3. 抽屉勾选项按列表模式推导（`nav_homepage` / `nav_subscription` / `nav_whats_hot` / `nav_toplist`，
   图片搜索与上传者列表不勾任何项）；
4. 只有抽屉入口才勾选（`Favorites` / `History` / `Downloads` 有，守卫页与详情页为 `0`）。

同时把 `EhRouteCodecTest` 里那个「测试专用的常量替身」删掉，改用真正的 `LIST_ACTION_*`——
常量搬到 nav 包之后，替身存在的理由（避免把 Scene 拖进 JVM 单测）已经消失，
留着反而会掩盖常量值变更。

单测总数 36，全绿。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.19 同步清理死字符串（第十六个落地片）

上一片留下的 `values/strings.xml` 清理，这一片做掉。`617 → 593`，删 **24 条**；
每条在 `values-ja` / `values-zh-rCN` / `values-zh-rHK` / `values-zh-rTW` **同步删**，
共 120 行。不这么做会触发 lint 的 `ExtraTranslation`（翻译在、默认值不在），
比留着更糟。

**判定口径**：全仓（`AndroidManifest.xml`、`res/**/*.xml`、`.kt` / `.java` / `.gradle`）
搜 `R.string.<name>` 与 `@string/<name>`，两个都不命中才算死。判死之前先排除两种
「看不见的引用」：

- 反射式 `resources.getIdentifier(...)`——全仓零命中；
- `import com.hippo.ehviewer.R.string` 后直接用字段名——全仓零命中，全是
  `import com.hippo.ehviewer.R` + `R.string.x` 的写法。

**清单**（按来源分组）：

- 跳页对话框被 Compose 重做：`go_to_gid`、`go_to_hint`（`go_to` / `go_to_last_page` 仍在用）
- 快速搜索改版：`quick_search_tip`、`add_quick_search_dialog_title`、`delete_quick_search_message`
- 下载升级路径：`download_upgrade_existed`、`download_upgrade_service_failed`
- 画廊信息页请求头编辑：`header_key`、`header_value`
- 富文本格式菜单：`format_bold`、`format_italic`、`format_strikethrough`、`format_underline`、`format_plain`
- 其它：`readme`、`search_advance`、`copy_trans`、`torrents`、`rating0`、`no_history`、
  `error_write_failed`、`error_invalid_number`、`kokomade_tip`、`information_webview_outdated`

`rating0` 单独记一笔：`rating1`…`rating10` 由 `GalleryDetailScene` 的 `when` 逐值映射，
`rating0` 没有对应分支，是真正的孤儿——这类「看着像数组之一」的条目最容易被
批量清理时漏判，但也不能因为同族有人用就留着。

**`arrays.xml` 未动**：36 个数组全部挂在设置页 Preference 的 `entries` / `entryValues`
上，是活的。数组内的 `@string/x` 也计入了判定口径，所以数组引用的字符串不会被误删。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.20 清理死样式与死 styleable（第十七个落地片）

字符串清完之后，把剩下的资源类型一次扫干净：**46 项、约 200 行**。
`layout` 28 个全部还在用（Compose 页面仍包着 XML 的壳），死的是颜色、drawable、
id、style、attr 这五类。

**判定口径**：`@<type>/<name>` 与 `R.<type>.<name>`，正则尾巴必须加 `(?![\w.])`。
不加的话搜 `@drawable/icon` 会命中 `@drawable/icon_dark`，死资源被前缀相同的邻居
救活，结果是漏删而不是误删——但漏删会让「零引用」这个结论整个不可信。

**口径必须补的三处，缺一就会误删**：

1. **`tools:override="true"` 的 style 靠名字碰撞生效**。`themes_override.xml` 里那 5 个
   （`Base.ThemeOverlay.AppCompat.ActionBar`、`...Dark.ActionBar`、
   `Widget.MaterialComponents.CompoundButton.CheckBox` / `RadioButton`、
   `PreferenceSummaryTextStyle`）**永远零引用**，但删了就是覆盖失效。判定 style 时必须
   先跳过带 `tools:override` 的。
2. **attr 的引用形式是 `R.styleable.<Styleable>_<attr>`**，不是 `R.attr.x`。只查后者时
   报 38 个死 attr，补上 styleable 索引 + `app:x=` + `<item name="x">` 之后只剩 4 个，
   再减去随死控件消失的 3 个，正好归零。
3. **同名陷阱**：`<declare-styleable name="ProgressView">` 与 `<style name="ProgressView">`
   是两个资源，6 个布局里 `style="@style/ProgressView"` 引的是后者。删 styleable 时
   不能顺手把 style 删掉。

**死控件留下的 styleable**：`ProgressView`、`DividerView`、`ActivityPreference` 三个
`declare-styleable` 对应的类在仓库里已经不存在（`Indicating` 有活类
`IndicatingListView`，别混），整块删除。

**级联要跑三轮**：删 style 会让它引用的 dimen / drawable / color 变成孤儿。
本片实际是 `46 → +5 → +1`：

- 第一轮：`CategoryText` 死 → `@dimen/category_table_item_height`、
  `@drawable/check_text_view_foreground` 死；
- 第二轮：`check_text_view_foreground` 死 → `@color/check_text_view_mask` 死；
- 第三轮：`CardView.Reactive` 死 → `CardView.Normal`（只用它当 parent）死；
  `Widget.FloatingActionButton.Accent.Mini` 死 → `Widget.FloatingActionButton.Accent` 死。

每轮删完都要重跑盘点，直到 `layout` / `drawable` / `color` / `bool` / `dimen` / `attr`
的零引用计数全部归零。

**口径的第四个坑，也是这一片最该记的一条：`R.style.<name>` 里的点号会变成下划线。**

按 `@style/` + `parent=` + `R.style.<name>` 三种形式统计，`ThemeOverlay.Black`
是零引用 —— `themes.xml` 里 27 行没人要，连它带的那十来个
`content_black` / `widget_black` / `progress_black` / `tag_*_background_black` /
`primary_drawable_black` / `secondary_drawable_black` / `divider_black` /
`content_reactive_black` / `gallery_detail_button_background_black` 和
`@drawable/divider_gallery_detail_dark` 都像是死的整条链。

实际它是活的，只是写法是 `R.style.ThemeOverlay_Black`：

```kotlin
// EhActivity.getThemeStyleRes()
if (Settings.blackDarkTheme && night) R.style.ThemeOverlay_Black else R.style.ThemeOverlay
```

纯黑（AMOLED）主题在 View 层就靠它 apply。**带点的 style 名必须先 `.`→`_` 再查
`R.style.`**，否则所有带点的 style 都会被判死。这一片删掉的 7 个带点 style
（`TextAppearance.CardTitle`、`TextAppearance.CardMessage`、`TextAppearance.CategoryTitle`、
`CardView.Reactive`、`CardView.Normal`、`Widget.FloatingActionButton.Accent(.Mini)`）
之所以没出事，是编译期兜住了——被删的 `R.style.X_Y` 会直接编译失败。

但「编译能过」只证明**没删错**，不证明**该删的都删了**。漏判的死资源永远不会
触发编译错误，所以口径必须补全，不能拿编译当兜底。

**第五个坑：点号隐式继承。** `<style name="ThemeOverlay" />` 是空壳，但它被
`ThemeOverlay.Black` 通过 Android 的命名继承自动当作 parent，判定时「存在子样式」
必须算作活，否则父子一起被清掉。

补完这两条后复核：19 个 style（含 `values-night`）、5 个 `tools:override`，
零引用 **0** 个。资源层到此收敛。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.21 清理孤儿类（第十八个落地片）

资源清完，轮到代码。**5 个类 + 1 个布局、269 行**。

**方法**：对每个 Kotlin 顶层类型（`class` / `interface` / `object` / `typealias`）统计
全仓词边界出现次数，只在声明那一行出现一次 → 孤儿。口径收敛了两轮，每轮都在修误报：

- **只扫 `.kt`** 报出 31 个候选，其中 20 个是假的：Preference 子类由 `preference.xml`
  里的全限定名实例化、`ShortcutsActivity` 由 `AndroidManifest` 引用、`BatteryView` /
  `SeekBarPanel` / `TileThumb` 由布局的 `<com.hippo.xxx>` 标签引用。**必须把 XML /
  Manifest / ProGuard 一起算进来。**
- **只判「其它文件零出现」** 仍会误报：`ArchiveChannel` / `PfdArchiveChannel` /
  `Zip4jSession`、`MutexTracker` / `MutexPool`、`TagGroup` / `TagGroups` 这些都在自己
  文件里被用。**要把「本文件声明行之后是否再出现」也算进去。**

两轮之后剩下的 5 个真孤儿：

| 文件 | 行数 | 来历 |
| --- | --- | --- |
| `hippo/drawable/TriangleDrawable.kt` | 66 | 画三角的 `Drawable`，无调用方 |
| `hippo/easyrecyclerview/MarginItemDecoration.kt` | 64 | 列表外边距装饰 |
| `hippo/easyrecyclerview/SimpleHolder.kt` | 21 | 空壳 `RecyclerView.ViewHolder` |
| `hippo/ehviewer/ui/dialog/SelectItemWithIconAdapter.kt` | 54 | 带图标的选择对话框 adapter |
| `hippo/ehviewer/widget/ResizeableFixedThumb.kt` | 31 | 按 `Settings.listThumbSize` 定尺寸的缩略图控件 |

最后一个值得单说：它是「列表缩略图固定 2:3」的实现，列表页 Compose 化之后尺寸由
`Modifier.aspectRatio` 接管，控件留在仓库里没人 `new` 也没人写进布局，是出壳的直接产物。

**级联**：`SelectItemWithIconAdapter` 是 `dialog_item_select_with_icon.xml` 的唯一引用者，
布局一并删除——注意 layout 的零引用判定必须扫 `R.layout.x` 与 `@layout/x` 两种，
只看布局互相引用会漏掉这种「只被代码 inflate」的情况。

复查：513 个 `.kt` 文件，孤儿归零。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。

### 10.22 清理顶层孤儿声明（第十九个落地片）

类型清完，扫顶层 `fun` / `val`。**8 处**：

- `SDKUtils`：`isAtLeastOMR1`、`isAtLeastR`、`isAtLeastU`、`isAtLeastSExtension7`
- `CoroutinesExtensions`：`launchNow`（`GlobalScope` + `UNDISPATCHED` 的旧入口）、
  `withNonCancellableContext`
- `ParcelableCompat`：`getSparseParcelableArrayCompat`（连带 `android.util.SparseArray` import）
- `FavoritesScreen`：`CARD_THUMB_HEIGHT`

**口径**：只认行首（列 0）的声明——类/接口成员天然有缩进，`^fun` / `^val` 就能把顶层
挑出来。再要求 `\bname\b` 全仓计数 **恰好等于 1**（只有声明处）。重载会让计数 >1，
自动跳过：宁可漏，不误删。

**级联又出现一次**：`isAtLeastSExtension7` 的定义是 `isAtLeastR && ...`，删掉它之后
`isAtLeastR` 才失去唯一引用。所以「删完复查」不是形式主义，是必须的第二步。

**两处零引用必须留下**：

1. **`ReaderScreen`**（`ui/reader/ReaderScreen.kt`）—— 全仓零调用，但它不是死代码，
   是**已经写好、还没接线**的 Compose 阅读器。孤儿扫描分不清「死」和「未接线」，
   这种只能靠人看：文件头注释写着「不再需要自绘 GL 与 nativeTexImage 那套 512×512
   纹理上传」，明显是给下一步准备的。
2. **`ehHost()`**（`ui/host/EhHostState.kt`）—— 零调用，但它是 `ehHostOrNoOp()` 的
   非降级版本（后者返回空实现兜底），属于宿主能力 API 面，不是遗留代码。

**顺带撞见一个比清理更重要的事实：阅读器其实还没出壳。**

历史页的「阅读」仍然开 Activity：

```kotlin
// HistoryScene.openReader()
context.startActivity(
    Intent(context, GalleryActivity::class.java).apply {
        action = GalleryActivity.ACTION_EH
        putExtra(GalleryActivity.KEY_GALLERY_INFO, gi)
    },
)
```

`GalleryActivity` 是 1300+ 行的旧实现（`R.layout.activity_gallery` + `GLRootView` +
`GalleryView`，自绘 GL 渲染），而 `ui/reader` 里的 `ReaderScreen` + `PageSource`
已经按新方案写完。也就是说 **P7-c 还有阅读器这一页没出壳**，10.15 里「P7-c 收尾」
的说法不准确。

本片只做清理、不动功能，这条留给下一片：把 `GalleryActivity` 换成
`EhRoute.Reader` + `ReaderScreen`，历史/详情两个入口一起改线。

验证：`:app:compileDebugKotlin`、`:app:spotlessCheck`、`:app:testDebugUnitTest`、
`:app:assembleDebug` 全绿。
