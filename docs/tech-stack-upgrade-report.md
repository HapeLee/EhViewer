# EhViewer 技术栈升级与 NDK 替代方案报告

- 仓库：`d:/Project/Eh`（基线 `EhViewer-NekoInverter/EhViewer` master `9e102164`，工作分支 `dev`）
- 生成日期：2026-10-30
- 结论摘要：**NDK 与 CMake 可以完全去掉**。四个 JNI 面都有纯 JVM / 框架 / 预编译 AAR 的替代路径；推荐路线是「libarchive 用预编译 AAR + 其余三项改纯 JVM」，可在不装 NDK 的情况下恢复 `assembleDebug`。

---

## 1. 本轮已完成（已编译验证）

| 项 | 内容 | 验证 |
|---|---|---|
| 最低版本 | `minSdk 23 → 31`（`settings.gradle.kts`） | `:app:compileDebugKotlin` 通过 |
| 依赖源 | `dl.google.com` 的 JVM TLS 握手被重置，`settings.gradle.kts` 前置阿里云镜像（对齐 `D:\Project\Matter` 的既有配置） | 依赖解析通过 |
| SDK 路径 | 新增 `local.properties` 指向 `D:\Android\SDK` | 通过 |
| 设计系统 | Compose BOM `2026.09.00` + `material3 1.5.0-beta01`（M3 Expressive）+ `kotlin.plugin.compose` + `buildFeatures.compose` | 通过 |
| 导航系统 | `navigation3 1.2.0`（原为自研 `com.hippo.scene` 的 `Announcer`/`StageActivity` 栈） | 通过 |
| 主题 | `ui/compose/theme/EhTheme.kt`：`MaterialExpressiveTheme` + Monet 动态取色（minSdk 31，已删除低版本回退）+ AMOLED 纯黑 | 通过 |
| 容器 | `ui/compose/EhComposeScene.kt`：仍是 `SolidScene` 子类，内部 `ComposeView` + `NavDisplay`（两个 entryDecorator） | 通过 |
| 路由 | `ui/compose/navigation/EhRoute.kt`：`@Serializable sealed interface EhRoute : NavKey` | 通过 |
| 首个迁移屏 | `SelectSiteScene` 改写为 Compose + M3E（`SingleChoiceSegmentedButtonRow`），类名不变，所有 `Announcer` 调用点零改动 | 通过 |

**尚未验证**：`assembleDebug` / 真机运行（见第 2 节）。

---

## 2. 环境现状与阻塞点

| 项 | 状态 |
|---|---|
| JDK | 21.0.11 ✔ |
| Gradle wrapper | 8.14 ✔ |
| Android SDK | `D:\Android\SDK` ✔（platforms 35 / 36 / 36.1 / 37.0 / 37.1；build-tools 36.0.0 / 36.1.0 / 37.0.0） |
| **NDK** | ✘ 空（工程钉死 `ndkVersion = "29.0.14206865"`） |
| **CMake** | ✘ 空 |
| 结论 | `compileDebugKotlin` 可用；`assembleDebug` 必然失败 |

### 额外隐患：原生构建本身还有第二层联网依赖

`app/src/main/cpp/CMakeLists.txt` 用 `FetchContent` 在配置期拉三个上游源码：

- `liblzma` ← `github.com/tukaani-project/xz` v5.8.1
- `nettle` ← `ftp.gnu.org/gnu/nettle/nettle-3.10.2.tar.gz`
- `libarchive` ← `github.com/libarchive/libarchive` v3.8.1（还要 `git apply` 三个补丁）

也就是说：**即使装了 NDK，构建仍依赖 GitHub / ftp.gnu.org 的实时可达性**，在受限网络下不可复现。这是「去掉原生层」比「装 NDK」更有价值的第二个理由。

---

## 3. 原生层到底在做什么（代码事实）

四个 JNI 暴露面，全部由 `System.loadLibrary("ehviewer")` 加载，源码在 `app/src/main/cpp/`（约 1000 行 C，不含拉下来的上游）。

| # | Kotlin 侧 | 原生实现 | 业务调用点 | 实际职责 |
|---|---|---|---|---|
| 1 | `jni/Hash.kt` `sha1(fd)` | `hash.c` + libnettle，8 KB 分块读 fd，输出小写 hex | `UniFileExtensions.kt:35` `UniFile.sha1()` → `SpiderDen`、`EhTagDatabase`、`MainActivity`（导入档案）、`ImageSearchLayout` | 文件 SHA-1（下载完整性校验、标签库校验） |
| 2 | `jni/Archive.kt`（9 个函数） | `archive.c` + libarchive 3.8.1 | `gallery/ArchiveGalleryProvider.kt`（180 行） | 读取本地压缩包画廊：开包、条目过滤（只留可播放图片）、自然排序、解到 ByteBuffer / fd、密码 |
| 3 | `jni/GifUtils.kt`（4 个函数） | `gifutils.c` | `com/hippo/image/Image.kt:156-173`，**仅 `!isAtLeastU`（Android 12/13）** | GIF 魔数判定、`mmap` 映射（`PROT_READ|WRITE` + `MAP_PRIVATE`，即写时复制、不落盘）、修补过小的帧延迟（<2ms 改成 10ms） |
| 4 | `jni/Image.kt` `nativeTexImage(...)` | `image.c`，`jnigraphics` + GLES3 | `com/hippo/image/Image.kt:102-117` → 阅读器 GL 渲染 | 把 Bitmap 的 512×512 分块拷进静态缓冲并 `glTexImage2D`/`glTexSubImage2D`（支持 RGBA8 / RGBA_F16） |

---

## 4. 替代方案逐项评估

### 4.1 SHA-1（最容易）

| | |
|---|---|
| 替代 | `MessageDigest.getInstance("SHA-1")` + 8 KB 缓冲读 `FileInputStream(fd)`，`HexFormat`/手写 hex |
| 语义差异 | 无。注意原实现是**从当前 fd 偏移读到底**，替换时要么显式 `seek(0)`，要么沿用调用方用法（`UniFileExtensions` 每次新开 fd） |
| 工作量 | 0.5 小时 |
| 风险 | 极低 |

### 4.2 GIF 工具（低）

| 子功能 | 替代 |
|---|---|
| `isGif(fd)` | 读 6 字节比对 `GIF87a` / `GIF89a` |
| `mmap` / `munmap` | `FileChannel.map(READ_ONLY, 0, size)` 得到 `MappedByteBuffer`，但**必须注意语义**：原实现是 `MAP_PRIVATE`（可写、不落盘）；纯 Java 的 `map(READ_WRITE)` 会写回文件，不能照抄。正确做法是把需要修补的区域读进 `ByteBuffer.allocateDirect()` 再修 |
| `rewriteGifSource` | 扫描字节流找 `21 F9 04`（Graphic Control Extension），把首帧 delay < 2 的改成 10；约 30 行 Kotlin |

补充：这段逻辑只在 Android 12/13 走（14+ 交给 Coil / `ImageDecoder`），minSdk 已提到 31，仍需要保留但影响面小。

工作量：3~4 小时。风险：低（可用同一批 GIF 做前后解码对比）。

### 4.3 压缩包读取（中等，功能对等性是关键）

两条路：

| 方案 | 说明 | 覆盖 | 风险 |
|---|---|---|---|
| **A. 预编译 AAR（推荐）** | `me.zhanghai.android.libarchive:library:1.1.6`（Maven Central，`aar`，原生 `.so` 已编好，API 为 `Archive` / `ArchiveEntry`）；**`D:\Project\legado-with-MD3` 已长期生产使用该依赖，且该工程完全没有 NDK/CMake** | 与现状**同一套 libarchive**：zip / 7z / rar / tar / LZMA / AES 密码 | 引入第三方 AAR（Apache-2.0 封装层），需要把 `archive.c` 的条目过滤、自然排序、fd 解压搬到 Kotlin |
| B. 纯 Java 重写 | Apache Commons Compress 1.28 + `org.tukaani:xz`；RAR 另加 `junrar` | zip / 7z / tar / xz 完整；**RAR 支持不完整** | 行为与 libarchive 不完全一致，密码场景要逐个验证 |

`archive.c` 里需要搬过来的逻辑：`archive_entry_is_playable_file`（扩展名白名单）、`archive_map_entries_index`（`strnatcmp` 自然排序）、`extractToByteBuffer`（注意返回的是 direct buffer 供零拷贝解码，纯 Kotlin 要显式 `allocateDirect`）、`extractToFd`、密码池（`needPassword` / `providePassword`）。

工作量：方案 A ≈ 1~2 人日；方案 B ≈ 3~4 人日 + 更高回归成本。**建议 A。**

### 4.4 GL 纹理上传（阅读器，最重）

`nativeTexImage` 只是在做「取矩形 → 转 RGBA → `glTexSubImage2D`」，没有不可替代的算法：

| 方案 | 说明 | 代价 |
|---|---|---|
| A. Kotlin 直译 | `Bitmap.copyPixelsToBuffer(directByteBuffer)`（整图）或 `getPixels` 到 `IntArray` 再转；然后 `GLES30.glTexImage2D` / `glTexSubImage2D` | 丢掉 `AndroidBitmap_lockPixels` 的零拷贝，单块 512×512 多一次内存拷贝；预计掉帧幅度需实测，1 人日 |
| B. 随阅读器 Compose 化重做（**推荐方向**） | 阅读器本来就在迁移计划里；用 Coil 3 + Compose 缩放/平移，`BitmapRegionDecoder` 处理超大图，彻底不需要 GL 分块 | 3~5 人日，但与「M3E + Nav3 全量迁移」是同一笔投入，不重复付费 |

---

## 5. 推荐路线

**目标：不再依赖 NDK/CMake，同时保持现有功能。**

```
M1  sha1 → MessageDigest                      0.5h   删 jni/Hash.kt 调用
M2  GIF 三函数 → Kotlin                       3~4h   删 jni/GifUtils.kt 调用
M3  archive → me.zhanghai.android.libarchive   1~2d   重写 ArchiveGalleryProvider
M4  nativeTexImage → Kotlin GLES 直译          1d     先保功能，等阅读器 Compose 化再替换
M5  清理构建脚本                               0.5h   删 externalNativeBuild / ndkVersion /
                                                     abiFilters / debugSymbolLevel / cpp 目录
```

合计约 **3~4 人日**，M1→M4 全部完成后才能删 `externalNativeBuild`（少一个都不行，因为 `System.loadLibrary("ehviewer")` 仍在）。

### M5 之后的连带收益

- `assembleDebug` 不再需要 NDK / CMake / GitHub / ftp.gnu.org
- 可以整块删掉 `splits { abi }` 与 ABI 过滤逻辑，APK 里不再有 `lib/*/libehviewer.so`
- 构建时间显著下降（当前 CMake 侧要拉 3 个上游并编译 libarchive + nettle + xz）

---

## 6. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| RAR / 加密压缩包行为差异 | 打开压缩包画廊失败 | M3 用手上现有 zip / 7z / rar / 加密包做回归清单；方案 A 与现实现同源 libarchive，风险最低 |
| 阅读器掉帧 | 大图翻页体验下降 | M4 直译后实测；不达标就走 4.4-B（阅读器 Compose 化） |
| GIF 帧延迟修补遗漏 | Android 12/13 上个别动图只播一帧 | 保留原修补逻辑的逐字节对照测试 |
| 引入第三方 AAR | 供应链 | 固定版本 `1.1.4` + 校验和；只依赖一个 `library` 模块 |
| 阅读器 GL 路径与 Compose 迁移冲突 | 返工 | M4 先做最小直译，把「重做」并入阅读器迁移里程碑，不单独立项 |

---

## 7. 后续里程碑（与 M3E/Nav3 迁移合并排期）

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M0（已完成） | minSdk 31 + Compose/M3E/Nav3 骨架 + `SelectSiteScene` | `compileDebugKotlin` 通过 |
| M1~M5 | 去原生层（第 5 节） | `assembleDebug` 通过且 APK 内无 `.so`；压缩包/阅读器/校验手测清单通过 |
| M6 | 13 个设置 Fragment → Compose（M3E 表单控件试验田） | 设置项读写与现有 `Settings` 行为一致 |
| M7 | 7 个简单 Scene 迁移（Progress / History / GalleryInfo / GalleryPreviews / SignIn / CookieSignIn / WebViewSignIn） | 每屏功能等价 |
| M8 | 4 个大 Scene（Favorites 1156 / Downloads 1333 / GalleryList 1713 / GalleryDetail 2177 行） | 列表、分页、长按多选、下载队列等价 |
| M9 | 阅读器（`GalleryActivity` 1305 行）Compose 化 + 取代 M4 的 GL 直译 | 缩放/翻页/预加载/进度条手势 |
| M10 | 收敛为单条根 `NavDisplay`，删除 `com.hippo.scene`，`MainActivity` → `ComponentActivity` + `setContent` | 全链路返回栈、深链、`onBackPressed` 行为一致 |

---

## 8. 需要你决策的点

1. **M3 走方案 A（预编译 AAR）还是 B（纯 Java）？** 我建议 A：与现实现同源，回归面最小。
2. **M4 现在直译 GL，还是直接跳过、把阅读器一起 Compose 化？** 前者能最快恢复可用构建，后者少一次返工。
3. **是否现在就执行 M1~M5？** 一旦完成，`assembleDebug` 就能在本机跑通，之后所有 UI 迁移都有真机验证手段（目前只有编译验证）。
