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
