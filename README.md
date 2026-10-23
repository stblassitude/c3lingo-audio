# c3lingo audio

Native apps for listening to c3lingo audio channels (hall floor audio and live
interpretation) distributed through [Galène](https://galene.org/): one for iOS, iPadOS,
macOS and visionOS, and one for Android. Both do the same things;
[`FEATURES.md`](FEATURES.md) describes what, and tracks where they differ.

The app shows the event's web frontend
([listen-frontend](https://codeberg.org/c3lingo/listen-frontend)), loaded from the
server, so branding and channel layout change with the server and need no app
update. Playback is done natively:

- audio keeps playing in the background and with the screen locked
- lock screen / Control Center controls and Now Playing info
- AirPlay and Bluetooth output selection (Android: the system output switcher)
- automatic reconnect with backoff, after network changes and after interruptions
  such as phone calls
- the microphone is never used, so there is no permission prompt

## Repository layout

```
apple/     Xcode project for iOS, iPadOS, macOS and visionOS
android/   Gradle project for Android
shared/    used by both apps
  FrontendBridge.js   injected into the frontend, see below
  icon/               logo and the script that renders all app icons
FEATURES.md           what the apps do, and where they differ
```

## How it works

```
┌──────────── web view ──────────────┐        ┌──────────── native ─────────────┐
│ listen-frontend (from the server)  │        │ Player       state, reconnects, │
│   c3lingoNative.play(), stop() …   │ ─────▶ │              Now Playing        │
│                                    │ bridge │ GaleneSession  WebSocket + one  │
│ FrontendBridge.js (injected)       │ ◀───── │              peer connection    │
│   c3lingo:state events             │ state  │              per stream         │
└────────────────────────────────────┘        │ PlayerBar    native controls    │
                                              └─────────────────────────────────┘
```

In the Apple app (`apple/c3lingo-audio/`):

- `Frontend.swift` hosts the web view, injects `shared/FrontendBridge.js` at document start
  and keeps the page in sync with the player.
- `GaleneSession.swift` is a receive-only client for the Galène protocol (version 2):
  handshake, join, request audio, answer offers, trickle ICE both ways, ping/pong
  keepalive. TURN servers from the server's `rtcConfiguration` are used.
- `AudioEngine.swift` sets up WebRTC (LiveKit's build, `LiveKitWebRTC`, which also
  covers macOS, tvOS and visionOS) with the AVAudioEngine audio device module and
  voice processing disabled, so the audio session can use the `.playback` category.
- `Player.swift` keeps the selected channel playing and drives the lock screen controls.

The Android app (`android/app/src/main/java/org/c3lingo/audio/`) has the same parts:

- `Frontend.kt` hosts the `WebView`. The bridge script is added with
  `WebViewCompat.addDocumentStartJavaScript`, and the page talks to the app through a
  `WebMessageListener` that the script sees as `window.c3lingoAndroid`. Both are limited
  to the server's origin. Gradle copies `shared/FrontendBridge.js` into the assets.
- `GaleneSession.kt` is the same Galène client, on OkHttp's WebSocket.
- `WebRtc.kt` sets up WebRTC (LiveKit's build of the same version as the Apple app)
  with `JavaAudioDeviceModule` playing as media rather than as a call.
- `Player.kt` keeps the channel playing, with audio focus for interruptions.
- `PlaybackService.kt` is a media3 `MediaSessionService`: foreground service for
  background playback, plus notification, lock screen and headset controls.

## Bridge between frontend and app

The app implements version 2 of the frontend's native bridge protocol, documented in
listen-frontend's
[`docs/playback-api.md`](https://codeberg.org/c3lingo/listen-frontend/src/branch/main/docs/playback-api.md).
In short:

- The page declares `<meta name="c3lingo-native-bridge" content="2">`. Only then does
  it see `window.c3lingoNative`; any other page is left alone and plays audio itself.
- The page calls `ready()`, `play(channel)`, `stop()` and `setMuted(muted)`.
- The app answers `ready()` and reports every state change, whatever caused it, as a
  `c3lingo:state` event with the full state `{state, channel, muted, error}`.

The app's player states map onto the protocol like this:

| app | `state` | notes |
|---|---|---|
| idle | `stopped` | `channel` is `null` |
| paused (e.g. from the lock screen) | `stopped` | `channel` stays set |
| connecting, reconnecting | `connecting` | `error` explains a reconnect |
| joined, nobody sending | `connecting` | `error` is "Waiting for audio" |
| playing | `playing` | |

## Icons

All icons are rendered from `shared/icon/c3lingo-logo.svg`, a copy of the logo in
[c3lingo-design](https://codeberg.org/c3lingo/c3lingo-design). After changing it, run
`shared/icon/render.sh` (needs `brew install librsvg imagemagick`). It writes the PNGs
in the Apple asset catalog and the Android vector drawables.

## Server URL

The apps use `https://audio.c3lingo.org/` by default. For testing:

- iOS, iPadOS, visionOS: Settings → Apps → c3lingo → Server URL
- macOS: c3lingo → Settings… (⌘,)
- Android: Change Server… on the load error screen, or the gear on the app's App info
  screen (long-press the icon → App info)
- for one run, from Xcode or the command line: `-serverURL http://localhost:8000/`
  (Apple), `-e serverURL http://10.0.2.2:8000/` (Android, see below)

Leave it empty to go back to the default. Plain `http` is allowed for local network
addresses only (Apple), or in debug builds only (Android).

For testing playback without tapping through the UI, `-autoplay test` (Android:
`-e autoplay test`) selects a channel (here `test`) once the page has loaded, exactly as
if it had been clicked.

## Development setup: Apple

You need a Mac with **Xcode 27** from the App Store. Start it once so it installs its
components, and install the iOS and visionOS simulators when it asks (or later under
Xcode → Settings → Components). Nothing else is needed: the WebRTC framework comes in
through Swift Package Manager from `https://github.com/livekit/webrtc-xcframework` on the
first build.

**In Xcode:** open `apple/c3lingo-audio.xcodeproj`, pick the `c3lingo-audio` scheme and a
destination in the toolbar (an iPhone or iPad simulator, Apple Vision Pro, or My Mac),
and press ⌘R. Launch options such as `-serverURL` go under Product → Scheme → Edit
Scheme… → Run → Arguments. Logs appear in Xcode's console; filter for the
`org.c3lingo.audio` subsystem.

**From the command line**, for an iPhone or iPad simulator:

```sh
cd apple
xcodebuild -scheme c3lingo-audio -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath build build
xcrun simctl list devices available    # pick a device, then boot it by name or ID
xcrun simctl boot 'iPhone 16 Pro'
open -a Simulator
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/c3lingo-audio.app
xcrun simctl launch --console booted org.c3lingo.audio -serverURL http://localhost:8000/
```

The simulator shares the Mac's network, so `localhost` reaches a test server on the Mac.
For the visionOS simulator, build with `ARCHS=arm64`: LiveKit's WebRTC has no Intel
slice for it.

Minimum OS versions are iOS/iPadOS 15, macOS 12 and visionOS 26. iOS 15 and macOS 12
are the oldest deployment targets Xcode 27 supports; LiveKit's WebRTC build only ships
visionOS 26 and later. tvOS and watchOS aren't supported, since there is no WKWebView on
those platforms.

## Development setup: Android

Install **[Android Studio](https://developer.android.com/studio)** (on macOS also
`brew install --cask android-studio`). Its setup wizard installs the Android SDK, the
emulator and a JDK. Then, in Android Studio:

1. Settings → Languages & Frameworks → Android SDK: under SDK Platforms install
   **API 37**; under SDK Tools make sure Android SDK Build-Tools, Android
   Emulator and Android SDK Platform-Tools are installed.
2. Device Manager → **+** → Create Virtual Device: pick a phone (e.g. Pixel 9) and a
   system image for API 37 (on Apple silicon, an arm64 image).

**In Android Studio:** File → Open… the `android/` folder (not the repository root), wait
for the Gradle sync, select the emulator in the toolbar and press Run (⌃R). There are
two shared run configurations (in `android/.run/`): **app**, and
**app (c3listen.lassitu.de)**, which uses the test server `https://c3listen.lassitu.de/`. Gradle uses
the JDK bundled with Android Studio. The app needs Android 8 (API 26) or later.
Logs are in the Logcat window; filter for `tag:galene tag:player tag:frontend`.

**From the command line**, with the SDK in Android Studio's default location:

```sh
export ANDROID_HOME=~/Library/Android/sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

emulator -list-avds                  # the virtual devices you created
emulator -avd Pixel_9 &              # start one
cd android
./gradlew :app:installDebug          # build and install on the running emulator
adb shell am start -n org.c3lingo.audio/.MainActivity \
    -e serverURL http://10.0.2.2:8000/ -e autoplay test
adb logcat -s galene player frontend
```

In the emulator, `10.0.2.2` is the Mac, so that reaches a test server running there.
`./gradlew :app:assembleRelease` builds a release APK (it needs signing set up to
install).
