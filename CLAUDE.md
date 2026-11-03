# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Jellyfin Android (`org.jellyfin.mobile`) — a native Android wrapper around the Jellyfin
[web client](https://github.com/jellyfin/jellyfin-web). The app loads the server's web UI in a
`WebView` and provides native capabilities (video/audio playback, Chromecast, media session,
downloads, Android Auto) that the web layer calls into via JavaScript bridges.

> **This is a fork.** This branch (`feature/custom-on-2.7.3`) is based on upstream
> v2.7.3, which uses the **media3** player (`androidx.media3.*`), and carries the fork's
> custom features ported from the older branch. It is the only published branch. An earlier
> v2.6.4 / ExoPlayer2 line is kept as the local-only tag `archive/media-segments-2.6.4`, because
> the original media3 migration broke sidecar/external subtitles for this fork's use case — if
> that regression reappears here, that tag is the reference.

> **Private-by-convention naming.** Nothing committed here may name software the maintainer
> self-hosts, so the in-app archive screen is called "Media Archive" and the home-tab playlist
> shortcut reads its target from `homePlaylist.name`. Private values (that name,
> `mediaArchive.url`, `externalLinks`) live only in gitignored `local.properties` and reach the
> app through `BuildConfig`; use neutral placeholders in examples.

## Build & Test Commands

```sh
./gradlew assembleDebug              # Build debug APK (default flavor: proprietary)
./gradlew installDebug               # Build + install on connected device/emulator
./gradlew assembleProprietaryDebug   # Proprietary flavor (Chromecast + Play Services)
./gradlew assembleLibreDebug         # Libre flavor (no proprietary deps, F-Droid)
./gradlew assembleRelease            # Optimized, minified, signed release

./gradlew detekt                     # Lint (Kotlin static analysis); autoCorrect is ON
./gradlew lint                       # Android lint (config: android-lint.xml)

./gradlew test                       # Run JVM unit tests (JUnit5 + Kotest + MockK)
./gradlew testProprietaryDebugUnitTest                       # Single variant
./gradlew testProprietaryDebugUnitTest --tests "*ClassName*" # Single test class
./gradlew connectedAndroidTest       # Instrumented tests (needs device/emulator)
```

Build requires `local.properties` pointing at an Android SDK and Java 11+ (use the Android Studio
JBR via `JAVA_HOME` on this machine). SDK versions come from the version catalog
(`gradle/libs.versions.toml`).

### Build variants

- **Flavor dimension `variant`**: `proprietary` (default — Chromecast via media3 cast +
  Google Play Services) vs `libre` (no Google deps). `BuildConfig.IS_PROPRIETARY` gates code.
  Proprietary-only deps use the `proprietaryImplementation` configuration.
- **Jellyfin SDK source** is selectable via the `sdk.version` property in `gradle.properties`
  (`default` / `local` / `snapshot` / `unstable-snapshot`).
- **Versioning** is derived in `buildSrc` (`VersionUtils.kt`) from the `JELLYFIN_VERSION` env var or
  `jellyfin.version` property; `versionCode` is computed from the semver string.

## Architecture

### WebView ↔ native bridge (the core pattern)

The app's central design is a bidirectional bridge between the web client and native Kotlin:

- **JS → native**: `@JavascriptInterface` classes registered on the WebView in
  `WebViewFragment.initialize()` — `NativeInterface`, `NativePlayer`, `ExternalPlayer`,
  `MediaSegments`. The web client calls these to trigger fullscreen, downloads, media session
  updates, native playback, etc.
- **native → JS**: `WebappFunctionChannel` (a coroutine `Channel<String>`) queues JS snippets that
  `WebViewFragment` drains and runs via `webView.evaluateJavascript(...)`. Used to call into
  `window.NavigationHelper` / `playbackManager` (seek, volume, back navigation, cast callbacks).
- **Injected UI** (fork-only): `injectionScript.js` is served in place of the web client's main
  bundle and side-loads the fork's assets from `app/src/main/assets/native/`. The three extra
  navigation entries go through `NavTabInjector.js`, which is the one piece coupled to
  jellyfin-web's *markup* rather than its bridge API — so it is what breaks on a server upgrade.
  It supports Jellyfin 12.0's MUI app bar (wide) and burger drawer (narrow) plus the pre-12.0
  `emby-tabs` strip, and clones a neighbouring entry instead of hardcoding emotion `css-*` classes.
  Verify changes against a real server; the live DOM is reachable over
  `adb forward` + `webview_devtools_remote_<pid>` because debug builds enable WebView debugging.
- **Decoupling layer**: native bridge methods mostly don't act directly — they `emit` an
  `ActivityEvent` onto `ActivityEventHandler` (a `MutableSharedFlow`), which `MainActivity`
  collects and handles (`subscribe()` / `handleEvent()`). This keeps `@JavascriptInterface` code
  (called off the main thread) decoupled from Activity/UI work.

### App shell & navigation

`MainActivity` hosts a single `R.id.fragment_container` and swaps fragments based on
`MainViewModel.serverState`: `ConnectFragment` (server setup) → `WebViewFragment` (the web app) →
`PlayerFragment` (native video). Back-press handling is delegated to the visible fragment if it
implements `BackPressInterceptor`.

### Native video player (`player/`)

media3-based (`androidx.media3.*`), separate from the audio path. Key pieces:

- `PlayerViewModel` + `PlayerFragment` (in `player/ui/`) — playback orchestration and the player UI
  (gestures via `PlayerGestureHelper`, fullscreen/lock/PiP helpers, track-selection menus).
- `source/` — `JellyfinMediaSource`, `MediaSourceResolver`; `queue/QueueManager`.
- `deviceprofile/` — `DeviceProfileBuilder` / `CodecHelpers` build the Jellyfin device profile
  advertising supported codecs so the server transcodes appropriately.
- `mediasegments/` — media segment (skip intro/credits) support, native in upstream since 2.7.0.
- media3 `DataSource`/`MediaSource` factories are constructed in `AppModule.kt`.

### Audio playback & background (`player/audio/`, `webapp/RemotePlayerService`)

`MediaService` (a `MediaBrowserServiceCompat`) provides background audio and Android Auto browsing
(`car/LibraryBrowser`). `RemotePlayerService` backs the media session / notification for web-driven
playback and is bound by `MainActivity`.

### Dependency injection (Koin)

DI is wired in `JellyfinApplication.onCreate()` via modules in `app/AppModule.kt`,
`app/ApiModule.kt` and `data/DatabaseModule.kt`. Fragments are created through Koin's fragment
factory (`setupKoinFragmentFactory()`). When adding a new injectable, register it in the relevant
module.

### Data & networking

- **Room** database (`data/`) — `JellyfinDatabase`, entities `ServerEntity` / `UserEntity`, DAOs.
  Schemas are exported to `app/schemas` (KSP `room.schemaLocation`).
- **Jellyfin SDK** (`org.jellyfin.sdk`) is the API client; `ApiClientController` manages it.
- HTTP via OkHttp + Cronet (embedded); images via Coil.

### Offline playlists (fork-only, `offline/`)

Plays downloaded copies instead of streaming, syncs their watch state, downloads playlists
(off / semi / automatic, limits, per-video exclusions), deletes watched downloads after a set
period, and shows an offline screen when the server can't be reached.

- **Kept apart from upstream so upstream merges stay easy.** Own Room database `OfflineDatabase`
  (`offline.db`, schema in `app/schemas/org.jellyfin.mobile.offline.data.OfflineDatabase`) — never
  add tables to or bump upstream's `JellyfinDatabase`. Upstream entities/DAOs are not changed;
  downloads are started/removed only through upstream's `DownloadManager` (or the activity's
  `ActivityEvent.DownloadItems` for user actions). Strings live in `values/strings_offline.xml`.
- **Hooks in upstream files** are small and marked `// Fork: offline`: `JellyfinApplication`
  (module + `OfflineCoordinator.start()`), `QueueManager` (`startItem()` decides local vs. stream per
  item, local subtitles, fallback to streaming), `PlayerViewModel` (report local playback, cached
  autoplay/skip settings), `MediaSegmentRepository` (stored segments), `WebViewFragment` (JS bridge
  `NativeOffline`, server error → offline screen), `SettingsFragment` (`offlineSettings()`),
  `injectionScript.js` (loads `PlaylistDownloadInjector.js`), `app/build.gradle.kts` (JUnit platform).
- **Watch state**: `OfflinePlayback` only records locally (dirty rows); `PlayStateSync` is the single
  serialized sender (`updateItemUserData`) with a three-way conflict check against the last state
  both sides agreed on. Local playback reports no playback sessions.
- **Deleting**: everything goes through the guarded `OfflineDownloads.delete()`; rules are pure
  functions in `OfflineRules` (unit tested). Auto delete only counts watching after the download.
- **If upstream ships one of these features** (e.g. PR #2169, offline watch-state sync): take
  upstream's version, drop the matching hook, and let pending fork rows flush before the update.
- All SDK calls from fork code run on `Dispatchers.IO` (the SDK reads responses on the calling
  thread). `subtitleApi.getSubtitle()` can't be used (expects JSON), subtitles are fetched raw.

## Conventions

- Kotlin official code style; detekt config is `detekt.yaml` (`buildUponDefaultConfig`). The build
  sets `ignoreFailures = true` and upstream has ~120 pre-existing findings, so compare the report
  against the base branch: new code must add none. The formatting rules wrap at **120** columns
  (`.editorconfig`/`MaxLineLength` say 140); trailing commas are used at call and declaration sites;
  `@Composable` functions are exempt from `FunctionNaming`.
- Jetpack Compose and view-binding (XML layouts) coexist; both `buildFeatures` are enabled. Setup
  screens (`ui/screens/`) use Compose; the player and web shell use XML/view-binding.
- Logging via Timber (`JellyTree`); LeakCanary in debug builds only.
