# LyricsAdapter Android

[LyricsAdapter](https://github.com/xwsjjctz/LyricsAdapter) 的安卓客户端，目前处于技术验证阶段。第一项验证：桌面端现有的 `.laplugin`（QQ 音乐、网易云，旧 API v1）能否不改代码，直接在安卓上的 QuickJS 里运行。

## 结构

| 目录 | 内容 |
| --- | --- |
| `compat-js/` | Node 兼容层：`fetch`/`Headers`（Node 语义）、`crypto`（转给平台加解密）、`zlib`/`stream`/`Buffer`/`util`/`assert`（browserify 系 polyfill）、计时器、`URL`、`TextEncoder` 等。`npm run build` 打包成 `plugin-host` 的资源 `compat.js` |
| `plugin-host/` | 纯 Kotlin 模块（不依赖 `android.*`）：每个插件一个 QuickJS 运行时，常驻 JS 事件循环收发请求；调用白名单、参数上限、`coverUrlFullSize` 映射与桌面仓库的 `electron/services/pluginHandle.ts` 一致 |
| `plugin-host-spike/` | 桌面 JVM 上的命令行：`smoke` 对真实接口做冒烟检查，`qq-login` 走 QQ 扫码登录后再测需要登录的接口 |
| `spike-android/` | 安卓工程，插桩测试在设备或模拟器上跑同样的检查 |

插件代码读取自桌面端安装目录 `~/.la/plugin/<id>/index.cjs`。

## 运行

```sh
(cd compat-js && npm install && npm run build)          # 修改 compat-js 后需要重新打包
./gradlew :plugin-host:test                             # 兼容层单元测试（加解密/zlib 与 Node 逐字节对照）
./gradlew :plugin-host-spike:run --args="smoke"         # 真实接口，无需登录
./gradlew :plugin-host-spike:run --args="qq-login"      # 打印二维码路径，用 QQ 音乐扫码
./gradlew :spike-android:connectedDebugAndroidTest      # 需要已连接的设备或模拟器
```

安卓工程需要 `local.properties` 里的 `sdk.dir`，以及 SDK Platform 37（OkHttp 5.5 要求 `compileSdk >= 37`）。

## 验证结果（2026-10-22）

- JVM：单元测试 15/15；`smoke` 13/13。
- Pixel 7 模拟器（Android 14，arm64）：插桩测试 2/2。网易云运行时冷启动 54 ms，QQ 99 ms。
- 跑通：网易云 weapi 加密、搜索、取流（Range 返回 206）、歌词、本地生成二维码（qrcode+pngjs+zlib 流）；QQ 用 tough-cookie 加手动重定向发起扫码登录。
- 3 次搜索串行 4.2 s，并行 1.4 s：同一插件的多个调用可以并发。
- quickjs-kt 的 `.so` 已按 16 KB 页对齐。

## 已知问题与待办

- 网易云、QQ 的音频 CDN 返回 `http://` 地址，安卓默认禁止明文流量。网易云 CDN 换成 https 同样返回 206；正式版应优先自动升级到 https，用户自建 WebDAV（局域网 http）另行处理。
- 需要登录的 QQ 接口（搜索、取流、QRC 逐字歌词解密）尚未验证，要扫码后运行 `qq-login`。
- 尚未实现新格式插件（`activate(context)`，如歌词翻译）的 SDK：`network.fetchText`、`storage`、`secrets`、`cache`、`configuration`。
- 插件和应用在同一进程（每个插件一个独立运行时）。要做到桌面那样的崩溃隔离，需要移到 `:plugin` 进程；事件循环异常退出后应由上层重建运行时。
- 兼容层 693 KB（未压缩）。启动已经够快，之后可以再压缩，或缓存 QuickJS 字节码。
