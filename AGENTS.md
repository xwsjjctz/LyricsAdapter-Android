# LyricsAdapter Android agent guidance

This repository is the Android client of LyricsAdapter. The desktop app
(Electron, https://github.com/xwsjjctz/LyricsAdapter) is a separate repository
and stays the reference for product behaviour and shared formats.

## Decisions in force

Agreed with the owner on 2026-10-22. Change them only when the owner asks.

- Native Kotlin with Jetpack Compose for UI and Media3 for playback. Playback,
  queues and the four library slots (local, cloud, online, playlist) live on the
  service side (a `MediaLibraryService`), not in the UI, so background controls
  keep working when the activity is gone.
- Music-source plugins are the desktop's `.laplugin` packages, run unchanged in
  QuickJS through `compat-js`. Do not port plugin protocols to Kotlin.
- iOS will be a separate SwiftUI app later. Keep non-UI logic (plugin host,
  lyrics, WebDAV, library model, persistence rules) in pure Kotlin modules with
  no `android.*` imports; put platform services behind interfaces, as
  `plugin-host` does with `HttpTransport`, `HostCrypto`, `PluginDataStore` and
  `PluginLogger`. Do not add Kotlin Multiplatform targets until iOS work starts.
- Releases are APKs on GitHub only; no Google Play.
- Local music comes only from user imports through the Storage Access Framework
  with persisted URI permissions. No MediaStore scanning, no media permissions.
- Do not read or borrow from older Android implementations elsewhere in the
  parent workspace folder.

## Layout and commands

| Path | Contents |
| --- | --- |
| `compat-js/` | Node compatibility prelude for plugins; `npm run build` writes `plugin-host/src/main/resources/com/lyricsadapter/pluginhost/compat.js` |
| `plugin-host/` | Pure Kotlin plugin host: one QuickJS runtime per plugin, JS event loop, host bindings |
| `plugin-host-spike/` | JVM command line against the live services (`smoke`, `qq-login`) |
| `spike-android/` | Android app whose instrumented tests run the plugin host on a device |

```bash
(cd compat-js && npm install && npm run build)   # after editing compat-js/src; compat.js is generated
./gradlew :plugin-host:test
./gradlew :plugin-host-spike:run --args="smoke"
./gradlew :spike-android:connectedDebugAndroidTest
```

Plugins are read from the desktop's install directory `~/.la/plugin/<id>/`.

## Matching desktop behaviour

When Android must behave like desktop, the desktop repository is the source of
truth: plugin manifest and host contract (`src/shared/musicPlugin.ts`,
`src/shared/plugin.ts`, `electron/services/pluginHandle.ts`), online music DTOs
(`src/shared/onlineMusic.ts`), lyrics parsing (`src/shared/lrcParser.ts`) and the
WebDAV Metadata v3 format. Cite the desktop file you follow, and pin expected
values in tests (as `LegacyMusicPluginTest` does with vectors produced by Node).

## Build notes

- `settings.gradle.kts` uses `PREFER_SETTINGS` because a user-level Gradle init
  script may add mirror repositories to every project.
- OkHttp 5.5 requires `compileSdk` 37 or later; `targetSdk` follows Android
  behaviour changes separately.
- The official plugins' audio CDNs return `http://` URLs and Android blocks
  cleartext by default. NetEase's CDN also answers over https.
