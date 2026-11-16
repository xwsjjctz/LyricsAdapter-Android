# LyricsAdapter Android

[LyricsAdapter](https://github.com/xwsjjctz/LyricsAdapter) 的安卓客户端。技术验证（QuickJS 跑插件、lofty 读写标签、Compose 逐字歌词）已完成，应用已覆盖桌面版的主要页面：

- 界面结构仿 iOS 26 Apple Music：底部是常驻的玻璃胶囊控制栏（上一首、播放/暂停、下一首；点开专注模式，左右滑动切歌；没有歌曲时显示应用图标与「轻点歌曲开始播放」），其下是液态玻璃标签栏（点按或拖动切换「播放列表」「设置」），横屏时控制栏居中、标签栏靠右；玻璃边缘是柔和的高光、内侧辉光与投影；顶栏没有底色，列表滚到标题与搜索框下方时逐渐模糊淡出；长按与下拉菜单是玻璃面板，长按的歌曲行浮起、页面模糊，按住滑到选项上松手即执行；重新打开会停在上次播放的歌曲与位置（歌单从音乐源重新获取）；玻璃效果用 [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）
- 启动画面：与桌面版相同，logo 居中、背景是 logo 模糊后的色彩；从系统启动窗无缝接上，曲库读完、上次播放恢复好之后 logo 放大淡出，看不到加载过程
- 曲库：本地、云端、在线播放记录三个槽位，标题下拉里还列出各音乐源账号的歌单（可管理显示哪些），点标题立即展开，双击标题栏的空白处回到顶部；标题下方固定一条玻璃搜索框，列表从它下面滚过，输入时原地显示搜索结果；海报墙（窄屏用桌面的 6 列模板，宽于 720dp 用桌面的分带排列）与 Apple Music 风格列表可切换；「更多」菜单里有播放全部、随机播放、定位当前、导入文件、导入文件夹（含子文件夹；之后每次打开应用或手动重新扫描时，文件夹里新增的歌曲自动导入、已删除的移出，在「设置 › 通用」里移除文件夹）、刷新云端、多选与切换布局；长按菜单、多选移除
- 搜索：本地、云端、各音乐源与歌单的统一搜索（支持拼音），在曲库标题下方的搜索框里
- 在线歌曲下载：128kbps / 320kbps / FLAC，按实际音质命名，写入标签、歌词、逐字歌词和封面，存到 Music/LyricsAdapter 并加入本地曲库；封面上显示进度
- 元数据编辑：封面、标题、艺术家、专辑、歌词（含逐字歌词原文），写回文件
- 专注模式：桌面同款背景、竖屏与横屏布局、控制区；打开时先排当前唱到的十来行歌词，其余在页面到位后逐帧补齐，点击后不再停顿；逐字歌词、错落滚动、间奏、长音光晕，透明度与模糊取 AMLL 的值
- 云端曲库：WebDAV，读取桌面写的 Metadata v3，清单外的歌曲从文件头读标签
- 插件：官方目录下载或本地安装，扫码 / Cookie 登录，在线搜索、播放、歌词与歌单；插件平台（`activate(context)`）的音乐源与歌词翻译插件：设置页按清单显示配置项，专注模式里一键显示对照译文
- 设置：通用（语言、外观）、专注模式、云端曲库、插件、关于
- 语言：中文、English、日本語、한국어、Deutsch、Français（与桌面相同；Android 13 起可在应用内单独设置）

## 结构

| 目录 | 内容 |
| --- | --- |
| `compat-js/` | Node 兼容层：`fetch`/`Headers`（Node 语义）、`crypto`（转给平台加解密）、`zlib`/`stream`/`Buffer`/`util`/`assert`（browserify 系 polyfill）、计时器、`URL`、`TextEncoder` 等。`npm run build` 打包成 `plugin-host` 的资源 `compat.js` |
| `plugin-host/` | 纯 Kotlin 模块（不依赖 `android.*`）：每个插件一个 QuickJS 运行时，常驻 JS 事件循环收发请求；调用白名单、参数上限、`coverUrlFullSize` 映射与桌面仓库的 `electron/services/pluginHandle.ts` 一致 |
| `plugin-host-spike/` | 桌面 JVM 上的命令行：`smoke` 对真实接口做冒烟检查，`qq-login` 走 QQ 扫码登录后再测需要登录的接口 |
| `audio-tags/` | 标签读写：Rust crate（`rust/`，lofty 0.25，与桌面的 music-tag-native 同一版本线）经 UniFFI 生成 Kotlin 绑定（`src/main/kotlin`，已生成，改 Rust 后运行 `generateBindings`）。`fixtures/` 用桌面同款库生成带标签的样本和桌面读到的期望值 |
| `lyrics/` | 纯 Kotlin 的 LRC/QRC/YRC 解析与歌词行模型；`fixtures/` 记录桌面 `lrcParser.ts` 对同一批输入的解析结果，测试逐项对照 |
| `library/` | 纯 Kotlin 的曲库模型：`Track`、海报墙布局（`wall/`，窄屏模板与宽屏分带，测试与桌面 TypeScript 的输出逐格比对）、下载文件命名、文件夹同步的增删判断（`FolderSync`） |
| `app/` | 安卓应用：系统文件选择器导入（持久化授权）、标签存入 Room、Media3 后台播放（通知栏、锁屏、耳机拔出暂停）、Apple Music 风格的曲库列表、液态玻璃标签栏、控制栏与菜单、专注模式歌词（点歌词跳转） |
| `buildSrc/` | 两个安卓工程共用的 cargo-ndk 构建任务 |
| `spike-android/` | 安卓工程，插桩测试在设备或模拟器上跑同样的检查；构建时用 cargo-ndk 编出 `libaudio_tags.so`。桌面图标打开的是歌词渲染原型（`src/main/kotlin/.../lyrics/`），演示歌词由 `tools/demo-lyrics.mjs` 交给桌面仓库的 `lrcParser.ts` 解析生成，歌词文字是原创的 |

插件代码读取自桌面端安装目录 `~/.la/plugin/<id>/index.cjs`。

## 运行

```sh
(cd compat-js && npm install && npm run build)          # 修改 compat-js 后需要重新打包
./gradlew :plugin-host:test                             # 兼容层单元测试（加解密/zlib 与 Node 逐字节对照）
./gradlew :plugin-host-spike:run --args="smoke"         # 真实接口，无需登录
./gradlew :plugin-host-spike:run --args="qq-login"      # 打印二维码路径，用 QQ 音乐扫码
./gradlew :audio-tags:test                              # 标签读写与桌面对照（先用 cargo 编本机库）
./gradlew :audio-tags:generateBindings                  # 改了 Rust 接口后重新生成 Kotlin 绑定
(cd audio-tags/fixtures && npm install && npm run generate)  # 重新生成样本与期望值，需要 ffmpeg 和 metaflac
./gradlew :lyrics:test :library:test                   # 歌词解析与桌面对照、曲库模型
./gradlew :app:installDebug                             # 应用的 debug 构建，包名 com.lyricsadapter.app.debug
./gradlew :app:installProfile                           # 正式版代码（R8）、debug 包名和签名，覆盖安装后测性能
./gradlew :app:assembleRelease                          # 正式版；没有 keystore.properties 时不签名
python3 tools/i18n/generate.py                          # 改了 values/strings.xml 后重新生成其他语言
./gradlew :spike-android:connectedDebugAndroidTest      # 需要已连接的设备或模拟器
./gradlew :spike-android:installBenchmark               # 测歌词帧率用不可调试的 benchmark 构建
node spike-android/tools/demo-lyrics.mjs ../LyricsAdapter    # 重新生成演示歌词，参数是桌面仓库路径
```

在手机上看歌词渲染：装上 benchmark 构建，打开「LyricsAdapter spike」，左上角每 5 秒刷新一次帧率、掉帧率和 UI 线程耗时；同样的数据输出到 logcat（`adb logcat -s LASpike`）。关掉某项效果对比开销：

```sh
adb shell am start -n com.lyricsadapter.spike/.lyrics.LyricsSpikeActivity --ez blur false --ez background false
```

安卓工程需要 `local.properties` 里的 `sdk.dir`、SDK Platform 37（OkHttp 5.5 要求 `compileSdk >= 37`）、NDK，以及 Rust 工具链（`aarch64-linux-android`、`x86_64-linux-android` 目标和 `cargo-ndk`）。

## 发布

APK 只发在 GitHub Release，流程与桌面端一致：

1. 在 `dev` 上把根目录 `gradle.properties` 的 `appVersionName` 改成新版本号，提 PR 到 `master`；`.github/workflows/pr-check.yml` 跑单元测试并构建一次正式版代码。
2. 合并后手动运行 Release（Actions → Release → Run workflow，或 `gh workflow run release.yml --ref master`）。它构建签名的 APK，先建草稿 Release、上传完成后再公开，`v<版本号>` 标签在公开的那一刻才创建；版本号已经发布过时直接拒绝。`v0.*` 和带后缀的版本（如 `1.0.0-beta.1`）标为 Pre-release。

仓库需要四个 secret：`ANDROID_KEYSTORE_BASE64`（`.jks` 的 base64）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，缺任何一个 Release 都会失败。

生成签名密钥（只做一次，丢失后已安装的用户无法升级，务必备份）：

```sh
keytool -genkeypair -v -keystore lyricsadapter-release.jks -alias lyricsadapter -keyalg RSA -keysize 4096 -validity 36500
base64 -i lyricsadapter-release.jks | pbcopy    # 粘贴到 ANDROID_KEYSTORE_BASE64
```

本地构建签名版时，在仓库根目录放 `keystore.properties`（已被 .gitignore 忽略）：

```properties
storeFile=/绝对路径/lyricsadapter-release.jks
storePassword=…
keyAlias=lyricsadapter
keyPassword=…
```

## 真机验证（2026-10-24，努比亚 NX769J，骁龙 8 Gen 3，Android 16，60 Hz）

- QQ 音乐扫码登录（真实账号）：登录后搜索、320 取流、QRC 逐字歌词、歌单都正常。
- 专注模式逐字歌词播放 20 秒（profile 构建）：1219 帧，卡顿 0.25%，没有掉帧；debug 构建卡顿 1.08%。
- profile 构建（R8）：插件搜索、标签读写（JNA）、Room、播放与下载都正常。
- 下载：320kbps 8.7 MB、FLAC 46.8 MB，标签、歌词、QRC、封面读回正确。

## 验证结果（2026-10-22）

- JVM：单元测试 15/15；`smoke` 13/13。
- Pixel 7 模拟器（Android 14，arm64）：插桩测试 2/2。网易云运行时冷启动 54 ms，QQ 99 ms。
- 跑通：网易云 weapi 加密、搜索、取流（Range 返回 206）、歌词、本地生成二维码（qrcode+pngjs+zlib 流）；QQ 用 tough-cookie 加手动重定向发起扫码登录。
- 3 次搜索串行 4.2 s，并行 1.4 s：同一插件的多个调用可以并发。
- quickjs-kt 的 `.so` 已按 16 KB 页对齐。

标签读写：

- JVM：9/9。6 个样本（MP3/FLAC/M4A，含 QRC、YRC、小写键名、无标签文件、只有 ID3v1 的老文件）的标题、艺术家、专辑、歌词、逐字歌词、时长、码率、采样率、封面与桌面读取逐项一致；安卓写出的文件再用桌面的库读取，结果正确。
- 模拟器：经文件描述符读取多数文件约 1 ms（刚开机时个别到 18 ms），写入约 30 ms；`libaudio_tags.so` 在 arm64 上 1.3 MB，16 KB 页对齐。
- FLAC、MP3 的编辑不经过 lofty 的通用标签整体保存，而是拆分、修改再合并，所以 lofty 不认识的条目（QRC/YRC、PRIV 帧、自定义 Vorbis 字段）都会保留。只有 ID3v1 的 MP3 第一次编辑时，新建的 ID3v2 会先带上 ID3v1 的内容。桌面用 music-tag-native 整体保存，曾经在只改 FLAC 标题或封面时丢掉 QRC/YRC，已在桌面仓库 dev 分支 c2c21f6 修复（保存前后保留 QRC/YRC）。
- 与桌面的差异：安卓写出的 MP3 是 ID3v2.4；桌面写入逐字歌词后会被 node-id3 改成 v2.3。两种版本两边都能读。

歌词渲染（benchmark 构建，Pixel 7 模拟器用宿主 GPU，60 Hz）：

- 全部效果打开时稳定 60 fps，掉帧 0%，换行滚动时也没有掉帧。
- 歌词视图每帧的 UI 线程耗时 p50 1.7 ms、p95 2.8 ms，关掉模糊和背景后是 1.5–1.6 ms。播放时间只在绘制阶段读取，每帧只重绘、不重组。
- 模拟器的 CPU 总耗时固定在约 18 ms，开关效果都一样，是模拟器渲染管线的开销。所以模糊和 AGSL 背景的 GPU 成本还要在真机上看，尤其是 120 Hz 的中低端机。

应用第一个切片（2026-10-23，Pixel 7 模拟器）：

- 歌词解析：桌面 `lrcParser.ts` 的 28 个用例全部一致。
- 从系统文件选择器多选导入 FLAC（带 QRC 和封面）与 MP3（LRC）：标题、艺术家、专辑、时长、封面正确，冷启动 0.4–0.6 s。
- 播放：切到后台继续播放，媒体通知正常；点歌词跳到该行开头，位置精确到毫秒；逐字扫光和整行高亮都正常；一首播完自动下一首。
- 杀掉进程重开：曲库从 Room 恢复，凭持久化授权仍能播放。

## 已知问题与待办

- 网易云、QQ 的音频 CDN 返回 `http://` 地址，安卓默认禁止明文流量。网易云 CDN 换成 https 同样返回 206；正式版应优先自动升级到 https，用户自建 WebDAV（局域网 http）另行处理。
- 插件和应用在同一进程（每个插件一个独立运行时）。要做到桌面那样的崩溃隔离，需要移到 `:plugin` 进程；事件循环异常退出后应由上层重建运行时。
- 兼容层 693 KB（未压缩）。启动已经够快，之后可以再压缩，或缓存 QuickJS 字节码。
- 标签写入是原地修改，中途被杀可能损坏文件；正式版应先写到临时副本再替换，或至少在写之前备份。
- armeabi-v7a 设备需要再加一个 Rust 目标。
- M4A 等其他格式仍走通用标签整体保存，lofty 不认识的条目会丢失。
- 专注模式（`app/.../ui/focus/`）：背景与桌面经典背景一致（封面铺满后 80 px 模糊、饱和度 1.5、亮度 0.55、上下黑色渐变）；竖屏照桌面竖屏（88 px 封面头部、当前行顶部对齐在 10%、顶部 72 px 渐隐），横屏照桌面横屏（左侧大封面，右侧歌词行中心对齐在 35%）；歌词取值来自桌面专注模式的实测：非当前行 0.2，当前行整体 0.85（已唱约 0.82、未唱约 0.33），逐词动画移植自 AMLL（`lyrics/.../LineMotion.kt`）：逐词上浮 0.05em（ease-out，至少 1 秒，离开该行时原路落回）、扫光在词与词之间连续推进、长音（≥1 秒，中日文不限字数，其他文字 2–7 个字母）逐字母放大、散开、上浮并发光，数值与桌面实测一致，模糊每行 0.8 px（下方多一行、上方多两行，最多 5 px，按 CSS sigma 换算），行高 1.2em、字距 -0.025em；间奏圆点只在较长的前奏出现，行间间奏保持上一行高亮（与桌面一致）；控制区进入时隐藏，触碰控制区或手动滚动歌词时出现，停手 1 秒后淡出；间奏三点的尺寸和节奏与桌面一致。另有可选的动态流光背景（封面颜色的流动渐变，随鼓点律动，需要 Android 13；着色器按桌面效果自行编写），以及翻译插件提供的对照译文。音译行、对唱与背景人声桌面专注模式同样不显示（桌面传给 AMLL 的这些字段固定为空），因此不算缺失。拉丁文字用 Inter（SIL OFL，与桌面的 SF Pro 同类风格，按字号取显示/正文光学尺寸），中文仍用系统字体（苹方与 SF 的授权不允许分发）；歌词只在词与词之间换行，并像桌面那样把一行均匀拆成几行。
- 云端元数据写回与桌面一致，只对 123 云盘开启（通用 WebDAV 只读）；写入逻辑用模拟服务器测试并在手机上对本机 WebDAV 跑通，尚未在真实的 123 云盘上验证。
- 源文件被移动或删除后，曲目会留在列表里但无法播放，需要检测并提示。

## 许可

本仓库以 GNU AGPL-3.0-only 发布（见 `LICENSE`）。专注模式的逐词动画移植自 [AMLL](https://github.com/amll-dev/applemusic-like-lyrics)（同为 AGPL-3.0-only）。液态玻璃效果用 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 Backdrop 库（Apache-2.0）。
