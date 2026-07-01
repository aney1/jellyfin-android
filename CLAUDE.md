# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Jellyfin Android (`org.jellyfin.mobile`) — a native Android wrapper around the Jellyfin
[web client](https://github.com/jellyfin/jellyfin-web). The app loads the server's web UI in a
`WebView` and provides native capabilities (video/audio playback, Chromecast, media session,
downloads, Android Auto) that the web layer calls into via JavaScript bridges.

> **This is a fork pinned to v2.6.4 / ExoPlayer2.** Master upstream has migrated the player to
> media3, which breaks sidecar/external subtitles for this fork's use case. **Never port master's
> media3-based player code here** — the player deliberately uses `com.google.android.exoplayer2.*`
> (ExoPlayer `2.19.1`), not `androidx.media3`. Current work happens on
> `feature/media-segments-on-2.6.4`.

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

Build requires `local.properties` pointing at an Android SDK. compileSdk 34, minSdk 21, Java 11.

### Build variants

- **Flavor dimension `variant`**: `proprietary` (default — Chromecast via ExoPlayer cast extension
  + Google Play Services) vs `libre` (no Google deps). `BuildConfig.IS_PROPRIETARY` gates code.
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
  `WebViewFragment` drains and runs via `webView.loadUrl("javascript:...")`. Used to call into
  `window.NavigationHelper` / `playbackManager` (seek, volume, back navigation, cast callbacks).
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

ExoPlayer2-based, separate from the audio path. Key pieces:

- `PlayerViewModel` + `PlayerFragment` (in `player/ui/`) — playback orchestration and the player UI
  (gestures via `PlayerGestureHelper`, fullscreen/lock/PiP helpers, track-selection menus).
- `source/` — `JellyfinMediaSource`, `MediaSourceResolver`; `queue/QueueManager`.
- `deviceprofile/` — `DeviceProfileBuilder` / `CodecHelpers` build the Jellyfin device profile
  advertising supported codecs so the server transcodes appropriately.
- `mediasegments/` — `MediaSegmentRepository` + `MediaSegmentAction`; powers the skip-intro/credits
  feature (`SkipMediaSegmentButton`), driven from the web side via the `MediaSegments` bridge.
- ExoPlayer `DataSource`/`MediaSource` factories are constructed in `AppModule.kt`, including a
  Cronet-backed data source and a `ResolvingDataSource` that injects the Jellyfin `Authorization`
  header (needed for Android Auto, where the token isn't in the URL).

### Audio playback & background (`player/audio/`, `webapp/RemotePlayerService`)

`MediaService` (a `MediaBrowserServiceCompat`) provides background audio and Android Auto browsing
(`car/LibraryBrowser`). `RemotePlayerService` backs the media session / notification for web-driven
playback and is bound by `MainActivity`.

### Dependency injection (Koin)

DI is wired in `JellyfinApplication.onCreate()` via three modules:
`applicationModule` (`app/AppModule.kt`), `apiModule` (`app/ApiModule.kt`), `databaseModule`
(`data/DatabaseModule.kt`). Fragments are created through Koin's fragment factory
(`setupKoinFragmentFactory()`). When adding a new injectable, register it in the relevant module.

### Data & networking

- **Room** database (`data/`) — `JellyfinDatabase`, entities `ServerEntity` / `UserEntity`, DAOs.
  Schemas are exported to `app/schemas` (KSP `room.schemaLocation`).
- **Jellyfin SDK** (`org.jellyfin.sdk`) is the API client; `ApiClientController` manages it.
- HTTP via OkHttp + Cronet (embedded); images via Coil.

## Conventions

- Kotlin official code style; detekt runs with `buildUponDefaultConfig` and **`maxIssues: 0`** —
  builds fail on any violation, so run `./gradlew detekt` before considering work done. Note
  `detekt.yml`: trailing commas are required (call + declaration sites), `MaxLineLength` 200,
  `@Composable` functions are exempt from `FunctionNaming`.
- Jetpack Compose and view-binding (XML layouts) coexist; both `buildFeatures` are enabled. Setup
  screens (`ui/screens/`) use Compose; the player and web shell use XML/view-binding.
- Logging via Timber (`JellyTree`); LeakCanary in debug builds only.
