# LyricsAdapter Android agent guidance

This repository is the Android client of LyricsAdapter. The desktop app
(Electron, https://github.com/xwsjjctz/LyricsAdapter) is a separate repository
and stays the reference for product behaviour and shared formats.

## Decisions in force

Agreed with the owner on 2026-10-22. Change them only when the owner asks.

- Native Kotlin with Jetpack Compose for UI and Media3 for playback. Playback,
  queues and the four library slots (local, cloud, online, playlist) live on the
  service side (a `MediaLibraryService`), not in the UI, so background controls
  keep working when the activity is gone. The first slice uses a plain
  `MediaSessionService` with the local library only; switch to
  `MediaLibraryService` when browsing by external controllers is added.
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
  The one exception is downloaded online songs: the app inserts them into
  MediaStore (`Music/LyricsAdapter`) itself, which needs no permission, and
  imports the returned URI.
- Do not read or borrow from older Android implementations elsewhere in the
  parent workspace folder.

## Layout and commands

| Path | Contents |
| --- | --- |
| `compat-js/` | Node compatibility prelude for plugins; `npm run build` writes `plugin-host/src/main/resources/com/lyricsadapter/pluginhost/compat.js` |
| `plugin-host/` | Pure Kotlin plugin host: one QuickJS runtime per plugin, JS event loop, host bindings |
| `plugin-host-spike/` | JVM command line against the live services (`smoke`, `qq-login`) |
| `audio-tags/` | Tag reading and writing: a Rust crate on lofty (`rust/`) with UniFFI Kotlin bindings (`src/main/kotlin`, generated and committed); `fixtures/` tags sample files with the desktop's libraries and records what desktop reads |
| `lyrics/` | Pure Kotlin LRC/QRC/YRC parser and line model; `fixtures/` records what desktop's `lrcParser.ts` returns |
| `library/` | Pure Kotlin library model: `Track`, the poster wall layout (`wall/`: desktop's narrow templates and its wide band packer, tested cell by cell against desktop's TypeScript output) and download file names |
| `webdav/` | Pure Kotlin WebDAV client on OkHttp: connection test, PROPFIND listing, text files, byte ranges; paths follow desktop's `webdavPath.ts`; reads desktop's Metadata v3 (`_manifest.json` + `_chunk_*.json`) |
| `app/` | The Android app: SAF import into Room, Media3 playback service, library wall and list, command palette, online downloads, settings and focus mode |
| `tools/i18n/` | `generate.py` writes `values-en/ja/ko/de/fr` from `values/strings.xml`: strings whose Chinese matches desktop's `zh.json` take desktop's translations, the rest come from `manual.py` |
| `spike-android/` | Android app whose instrumented tests run the plugin host and the tag library on a device; also the lyrics rendering prototype |
| `buildSrc/` | `BuildRustLibraries`, the cargo-ndk task both Android apps use to build `libaudio_tags.so` |

```bash
(cd compat-js && npm install && npm run build)   # after editing compat-js/src; compat.js is generated
./gradlew :plugin-host:test
./gradlew :plugin-host-spike:run --args="smoke"
./gradlew :audio-tags:test                       # builds the host library with cargo first
./gradlew :audio-tags:generateBindings           # after changing the Rust API
(cd audio-tags/fixtures && npm install && npm run generate)   # needs ffmpeg and metaflac
./gradlew :lyrics:test :library:test :webdav:test
node lyrics/fixtures/generate.mjs ../LyricsAdapter   # after changing parser cases; argument is the desktop checkout
./gradlew :app:installDebug                      # installs as com.lyricsadapter.app.debug
./gradlew :app:installProfile                    # release code (R8) under the debug id and key, for measuring speed
./gradlew :app:assembleRelease                   # signed when keystore.properties exists at the root (never committed)
python3 tools/i18n/generate.py                   # after changing values/strings.xml
./gradlew :spike-android:connectedDebugAndroidTest
```

Plugins are read from the desktop's install directory `~/.la/plugin/<id>/`.
Building the Android app needs the NDK, the Rust Android targets and cargo-ndk.

## Plugins in the app

`app/.../plugins/` follows desktop's registry: a `.laplugin` is JSON with
`manifest`, `code` and the code's SHA-256, unpacked into `files/plugins/<id>/`;
official packages come only from the catalog (`LyricsAdapter-Music-Plugins`,
catalog v2) and must match its pinned SHA-256. Secrets use desktop's names
(`music-plugin:<id>:secret:<name>`, the plugin reads `cookie`) inside the
Keystore-encrypted store. Online songs play as `online:<source>:<songmid>`
URIs that `PlaybackService` resolves through the plugin when the stream opens,
falling back `flac → 320 → 128 → m4a` like desktop's stream protocol.
Plugins on desktop's platform API (`manifestVersion`, `activate(context)`) run
through `PlatformPlugin` and compat-js `platformRuntime.js`, for the
`lyrics.translation` extension only (desktop's `pluginRuntime.ts` and
`pluginValidation.ts` are the contract); platform music sources are refused.
`./gradlew :plugin-host-spike:run --args="translate"` runs the installed LLM
translation plugin against a local stand-in model.
`./gradlew :plugin-host-spike:run --args="probe --query …"`
lists which search results stream without a login, for manual testing.

## Cloud library

The cloud slot reads the WebDAV library the way desktop's `useWebDAV` does:
list the root directory, trust a `Metadata/_manifest.json` entry while its
size and `getlastmodified` still match the file, fill covers and lyrics from
the chunks in the background, and read tags from the first bytes of a song
the manifest does not describe. Like desktop, it writes what those head reads
found back to the folder (`CloudMetadataWriter`: server manifest as baseline,
chunks first and manifest last) only on 123pan; generic WebDAV stays read-only. Playback adds Basic authentication only for
the WebDAV host (`PlaybackService`), so redirects to a CDN never carry it.

## Tag writing

`audio-tags` must never drop data it does not understand. FLAC and MP3 edits
split the common fields out of the Vorbis comments / ID3v2 tag, change them and
merge them back; do not save these formats through lofty's `TaggedFile`, which
discards every item lofty has no key for (QRC/YRC included).
`AudioTagsTest` guards this with fixtures carrying a PRIV frame and a custom
Vorbis field. Word lyrics follow desktop's `wordLyricsTagService.ts`.

## Lyrics

- Desktop parses LRC/QRC/YRC with AMLL (`@applemusic-like-lyrics/lyric`),
  which is AGPL-3.0-only. Do not translate AMLL code; write the Kotlin parser
  from the formats and match desktop by comparing with what desktop's own
  `src/shared/lrcParser.ts` returns (run it under Node, as
  `spike-android/tools/demo-lyrics.mjs` does).
- Read the playback position only in draw lambdas and in the active-line
  `derivedStateOf`, so a frame redraws without recomposing; the spike's
  `LyricsView` keeps the UI thread under 3 ms per frame this way.
- Blur and the AGSL background must stay switchable for slower devices.
- The same rule covers AMLL's player (`@applemusic-like-lyrics/core`), which
  desktop's focus mode uses: match what desktop's focus mode shows (measure it
  in the running app over CDP, as sizes, alphas, blur and timings), and write
  the Compose code yourself; do not port its source.

## Matching desktop behaviour

When Android must behave like desktop, the desktop repository is the source of
truth: plugin manifest and host contract (`src/shared/musicPlugin.ts`,
`src/shared/plugin.ts`, `electron/services/pluginHandle.ts`), online music DTOs
(`src/shared/onlineMusic.ts`), lyrics parsing (`src/shared/lrcParser.ts`) and the
WebDAV Metadata v3 format. Cite the desktop file you follow, and pin expected
values in tests (as `LegacyMusicPluginTest` does with vectors produced by Node).

## Releases

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the
signed release APK (version name from the tag) and attaches it to the tag's
GitHub release. It needs the `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`
secrets. Release builds use R8: keep rules for reflection-based code (JNA and
the UniFFI bindings) live in `app/proguard-rules.pro`, and resources must be
loaded by absolute path, because R8 moves classes between packages.

## Testing on a device

The test phone's ROM sets `persist.log.tag=S`, silencing app logs. To see one
tag, run `adb shell setprop log.tag.<Tag> VERBOSE`, and set it back to `S`
afterwards.

## Build notes

- `settings.gradle.kts` uses `PREFER_SETTINGS` because a user-level Gradle init
  script may add mirror repositories to every project.
- OkHttp 5.5 requires `compileSdk` 37 or later; `targetSdk` follows Android
  behaviour changes separately.
- The official plugins' audio CDNs return `http://` URLs and Android blocks
  cleartext by default. NetEase's CDN also answers over https.
