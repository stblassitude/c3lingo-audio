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
  screenshots/        takes the App Store screenshots
  fastlane/           store texts; uploads them with the screenshots
android/   Gradle project for Android
  screenshots/        takes the Google Play screenshots
  fastlane/           store listing and release notes, also for F-Droid; uploads them
shared/    used by both apps
  FrontendBridge.js   injected into the frontend, see below
  icon/               logo and the script that renders all app icons
release-notes/        notes for the next release, see "Releasing"
release               releases both apps
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
- `window.c3lingoNative.language` is the app's UI language (`en`, `de`, `fr`, `es`, `pt`
  or `pl`, the same languages as the frontend), so the page and the native controls
  always match. The WebViews also follow the app's language, which covers pages without
  the bridge.
- If the page also calls the optional `describe(channels)` with each channel's hall,
  language and current talk, the player bar shows them; otherwise it shows the
  channel's `displayName`.
- The app answers `ready()` and reports every state change, whatever caused it, as a
  `c3lingo:state` event with the full state `{state, channel, muted, error}`.

The app's player states map onto the protocol like this:

| app | `state` | notes |
|---|---|---|
| idle | `stopped` | `channel` is `null` |
| paused (e.g. from the lock screen) | `stopped` | `channel` stays set |
| connecting, reconnecting | `connecting` | `error` says whether the network or the channel is the problem |
| joined, nobody sending | `waiting` | no stream offered within 2 s of joining, or the sender stopped |
| playing | `playing` | |

An offered stream that doesn't connect within 15 s (ICE failing, e.g. because UDP is
blocked) counts as a network problem, like in the frontend's own player.

## Translations

The apps come in the frontend's six languages: English, German, French, Spanish,
Portuguese and Polish. They use the system's language, or on Android 13 and later
and iOS the language picked for the app in the system settings. English is the source
and the fallback.

Most of what a listener reads comes from the web page and is translated in
[listen-frontend](https://codeberg.org/c3lingo/listen-frontend), not here. The app
tells the page its own language, so both always match. The apps translate only their
own native parts:

- the player bar: status ("Live", "Nothing playing", …), the message under it, the
  talk line ("Since 14:30: …", "Next: Day 2, 10:00: …") and the labels screen readers
  read for the buttons
- the connection errors in the player bar and in the state sent to the page, worded
  like the frontend's own
- the screen shown when the channel list can't be loaded
- the server settings, which are for testing only
- on Apple, the permission texts the system shows (local network, and camera, which
  is never used but App Store Connect requires)

Hall names, languages and talk titles come from the server and aren't translated.
Where the frontend has the same text, use its wording.

Files to check:

| | file | notes |
|---|---|---|
| Apple | `apple/c3lingo-audio/Localizable.xcstrings` | all texts of the app; edit in Xcode, or as JSON |
| Apple | `apple/c3lingo-audio/InfoPlist.xcstrings` | permission texts |
| Apple | `apple/c3lingo-audio/Settings.bundle/*.lproj/Root.strings` | the app's page in the Settings app |
| Android | `android/app/src/main/res/values-*/strings.xml` | `values/strings.xml` is English |

In the Android files, keep the placeholders (`%1$s`, `%1$d`) and escape apostrophes
as `\'`. `language_tag` is not a text but the language's code, passed to the page.
`%@` and `%lld` in the Apple catalog are placeholders, too.

A new language needs a translation in all of these files, and its code added to
`android/app/src/main/res/xml/locales_config.xml` and to `knownRegions` in the Xcode
project. The frontend needs to support it as well, or the page will be in English.

## Icons

All icons are rendered from `shared/icon/c3lingo-logo.svg`, a copy of the logo in
[c3lingo-design](https://codeberg.org/c3lingo/c3lingo-design). After changing it, run
`shared/icon/render.sh` (needs `brew install librsvg imagemagick`). It writes the PNGs
in the Apple asset catalog and the Android vector drawables.

## Server URL

The apps use `https://audio.c3lingo.org/` by default. For testing:

- iOS, iPadOS, visionOS, Android: the cog in the bottom right corner of the page, or
  Change Server… on the load error screen. The dialog checks that the URL leads to a
  c3lingo frontend before it can be saved.
- iOS, iPadOS, visionOS: also Settings → Apps → c3lingo → Server URL
- macOS: c3lingo → Settings… (⌘,)
- Android: also the gear on the app's App info screen (long-press the icon → App info)
- for one run, from Xcode or the command line: `-serverURL http://localhost:8000/`
  (Apple), `-e serverURL http://10.0.2.2:8000/` (Android, see below). This replaces
  the default for that run; a URL saved in the app still takes precedence.

Leave it empty to go back to the default. Plain `http` is allowed for local network
addresses only (Apple), or in debug builds only (Android).

A changed URL takes effect right away, also while the app is running: playback stops
and the app starts over with a new web view, without any page state, cache or storage
left from the previous server.

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
and press ⌘R. The shared scheme runs the app against the test server
`https://c3listen.lassitu.de/`. Launch options such as `-serverURL` go under Product →
Scheme → Edit Scheme… → Run → Arguments; untick `-serverURL` there for the default server.
Logs appear in Xcode's console; filter for the `org.c3lingo.audio` subsystem.

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
for the Gradle sync, select the emulator in the toolbar and press Run (⌃R), which uses
the shared run configuration **app** (in `android/.run/`). Gradle uses the JDK bundled with Android Studio. The app needs Android 7.0 (API 24) or later, the
oldest version the AndroidX libraries it uses still support. To test on it, create a
second virtual device with an API 24 system image.
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

## Releasing

Both apps are released together, from one tag such as `v1.2.0`, made by `./release`:

1. Write the release notes into `release-notes/<language>.txt`, at most 500 characters
   each (Google Play's and F-Droid's limit), with the languages named like the folders
   in `android/fastlane/metadata/android/`. `en-US.txt` is required; languages without
   notes get the English ones.
2. Commit them to `main`, push, and run `./release 1.2.0` (`-y` to push without asking).

The script sets `versionName` to the new version and increments `versionCode` in
`android/app/build.gradle.kts`, moves the release notes to
`android/fastlane/metadata/android/<language>/changelogs/<versionCode>.txt`, commits
"Release 1.2.0", tags it `v1.2.0` with the English notes as the tag message, and pushes
`main` and the tag. It refuses to run outside an up-to-date `main` without local
changes, or for a version that isn't higher than the last.

The tag starts the two release workflows, which check it against `versionName`:

- **Android:** signed APKs as a GitHub release, and an app bundle on Google Play's
  internal testing track, see [Releasing the Android app](#releasing-the-android-app).
  F-Droid builds the tag itself, see [F-Droid](#f-droid).
- **Apple:** iOS, macOS and visionOS builds in TestFlight, see
  [Releasing the Apple app](#releasing-the-apple-app).

Everything shown in the stores comes from the repository, so it is changed with a
commit and goes out with the next release:

| | Android (Google Play, F-Droid) | Apple (App Store) |
|---|---|---|
| texts | `android/fastlane/metadata/android/<language>/`: `title.txt`, `short_description.txt` (80 characters), `full_description.txt` | `apple/fastlane/metadata/<language>/`: `name.txt`, `subtitle.txt` (30 characters), `description.txt`, `keywords.txt` (100 characters), `support_url.txt`, `privacy_url.txt`; `apple/fastlane/metadata/copyright.txt` |
| release notes | `…/changelogs/<versionCode>.txt` | the same files as Android |
| icon, feature graphic | `…/images/`, from `shared/icon/render.sh` | in the app |
| screenshots | `…/images/*Screenshots/`, committed | `apple/fastlane/screenshots/`, not committed |

The descriptions are plain text, with `•` for lists, which all three stores show as
written. Other fields, such as categories, age ratings and privacy details, are kept
in Play Console and App Store Connect. The privacy policy is [`PRIVACY.md`](PRIVACY.md),
linked from both stores and from the apps' server settings by its URL on GitHub.

The version name is the only version number to choose. The Android version code
counts the releases; the Apple build number is the number of commits.

## Releasing the Android app

The tag runs `.github/workflows/android-release.yml`. It builds signed release APKs for
`arm64-v8a` (almost all current phones), `armeabi-v7a` (older 32-bit phones), `x86_64`
(emulators, a few tablets) and a universal one with all three, and publishes them as a
GitHub release, with the English release notes and a link to all commits since the
previous `v*` tag.

The APKs are signed with the app's release key, which the workflow reads from four
repository secrets. Create the key once and keep the keystore and its passwords safe:
every update must be signed with the same key, or phones refuse to install it.

```sh
keytool -genkeypair -keystore c3lingo-release.jks -alias c3lingo \
    -keyalg RSA -keysize 4096 -validity 10000
base64 -i c3lingo-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body c3lingo
gh secret set ANDROID_KEY_PASSWORD
```

To build a signed release locally, set `ANDROID_KEYSTORE_FILE` to the keystore's path
and the three other variables to the same values, then run
`./gradlew :app:assembleRelease` in `android/`. Without them, the release APKs are
unsigned.

Google Play takes an app bundle instead, built with `./gradlew :app:bundleRelease` and
the same variables. The workflow builds it too and uploads it to the internal testing
track with `bundle exec fastlane upload_internal`, with the release notes as "What's
new". From there, it is promoted to other tracks in Play Console. The same upload
updates the store listing (texts, icon, feature graphic and screenshots, only the
images that changed), which Play shows right away, not just once the build is promoted.
To update the listing between releases:
`GOOGLE_PLAY_JSON_KEY_PATH=~/keys/c3lingo-play.json bundle exec fastlane upload_listing`
in `android/`. Play signs with the same key as the APKs (it was uploaded on Play Console's
App signing page, under Protected with Play), so the two can update each other.

The upload uses the service account described under [Screenshots](#android), whose
JSON key the workflow reads from a repository secret. In Play Console, the service
account also needs permission to release to testing tracks and to edit the store
listing.

```sh
gh secret set GOOGLE_PLAY_JSON_KEY < ~/keys/c3lingo-play.json
```

For each bundle, Play warns that it contains native code without
debug symbols. That warning can be ignored: the native code is WebRTC and AndroidX's
`libandroidx.graphics.path.so`, which come stripped, so there are no symbols to upload.
Crashes in the app's own code are decoded with R8's mapping file, which is part of the
bundle.

### F-Droid

F-Droid builds the app itself from the `v*` tags, using the build recipe in
[fdroiddata](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.c3lingo.audio.yml),
and signs it with its own key, so it can't update the APKs from GitHub or Google Play
or be updated by them. It notices new tags by itself, usually within a few days, and
reads the version from `android/app/build.gradle.kts` in the tagged commit. That's why
the version is committed there by `./release` rather than only known to the workflow.

F-Droid builds one APK per ABI with `./gradlew :app:assembleRelease -Pabi=<ABI>`, with
version code `10 * versionCode + n`, n being 1 for `armeabi-v7a`, 2 for `arm64-v8a`
and 4 for `x86_64`. The store listing (title, descriptions, icon, screenshots and
changelogs) comes from `android/fastlane/metadata/android/`.

## Releasing the Apple app

The tag also runs `.github/workflows/apple-testflight.yml`. It archives the app for
iOS, macOS and visionOS, one job each, and uploads them to App Store Connect with the
settings in `apple/ExportOptions.plist`; after Apple has processed them (usually 10 to
30 minutes), the builds appear in TestFlight. The version comes from the tag and the
build number is the number of commits, so every upload has a higher one. A platform that
fails doesn't stop the others' uploads.

Once all three are uploaded, `bundle exec fastlane upload_metadata platforms:ios,osx,xros`
uploads the texts in `apple/fastlane/metadata/` and the release notes as "What's New" to
the version with that number on each platform, creating it in App Store Connect if
needed. It doesn't submit anything for review. If a build failed, it doesn't run; run it
by hand in `apple/` after fixing the build, with the same variables as
`upload_screenshots` (see [Screenshots](#apple)) and the four `APP_REVIEW_…` ones. App Review's notes come from
`apple/fastlane/metadata/review_information/notes.txt`, and its contact, which App Store
Connect requires with them, from four repository secrets, as the repository is public:

```sh
gh secret set APP_REVIEW_FIRST_NAME --body Jane
gh secret set APP_REVIEW_LAST_NAME --body Doe
gh secret set APP_REVIEW_EMAIL --body jane@example.org
gh secret set APP_REVIEW_PHONE --body "+49 30 1234567"
```

The app record for `org.c3lingo.audio` has to exist in App Store Connect before the first
upload. The workflow signs with Xcode's automatic signing through an App Store Connect
API key, which it reads from three repository secrets. Create the key under Users and
Access → Integrations → App Store Connect API with the **Admin** role, which automatic
signing needs to create certificates and profiles. The `.p8` file can be downloaded only
once:

```sh
gh secret set APP_STORE_CONNECT_KEY < AuthKey_XXXXXXXXXX.p8
gh secret set APP_STORE_CONNECT_KEY_ID --body XXXXXXXXXX
gh secret set APP_STORE_CONNECT_ISSUER_ID --body 00000000-0000-0000-0000-000000000000
```

To upload from a Mac instead, archive in Xcode (destination Any iOS Device, Any Mac or
Any visionOS Device, then Product → Archive) and choose Distribute App → TestFlight &
App Store in the Organizer, or run the two `xcodebuild` commands from the workflow with
the platform as the destination. Without the `-authentication…` options they use the
Apple ID signed in to Xcode. Either way, pass the version and build number as the
workflow does: the project itself says 1.0 (1), which App Store Connect won't take for
the version being prepared.

## Screenshots

The store screenshots are taken on this Mac, in simulators and emulators, and uploaded
with [fastlane](https://fastlane.tools). Each app has one script that takes them and
one fastlane lane that uploads them (on Android, with the rest of the listing). The apps show the test server
`https://c3listen.lassitu.de/`, so it needs audio on its channels and a schedule for
the talk line. For each language (the six the apps come in) there are two
screenshots: the channel list, and the same with a channel playing.

The Apple screenshots aren't kept in the repository; the Android ones are, since F-Droid
takes them from there. Look through them before uploading or committing.

### Setup

fastlane needs a current Ruby; the one macOS comes with is too old. With Homebrew:

```sh
brew install ruby
export PATH="$(brew --prefix ruby)/bin:$PATH"   # also in ~/.zshrc
bundle install                                  # in the repository's root
```

The gems go into `vendor/bundle/`. `bundle update fastlane` updates fastlane.

### Apple

```sh
apple/screenshots/capture.sh                    # all of them, or e.g. `iphone ipad`
cd apple
APP_STORE_CONNECT_KEY_ID=XXXXXXXXXX \
APP_STORE_CONNECT_ISSUER_ID=00000000-0000-0000-0000-000000000000 \
APP_STORE_CONNECT_KEY_PATH=~/keys/AuthKey_XXXXXXXXXX.p8 \
    bundle exec fastlane upload_screenshots     # or `platforms:ios,osx`
```

`capture.sh` builds the app, then takes the screenshots App Store Connect asks for:

| target | device | size | App Store Connect |
|---|---|---|---|
| `iphone` | iPhone 18 Pro, iPhone 18 Pro Max | 1206 × 2622, 1320 × 2868 | iPhone 6.3" (required) and 6.9" |
| `duo` | iPhone Duo, outer and inner display | 1398 × 2034, 2853 × 2007 | iPhone Duo |
| `ipad` | iPad Pro 13-inch (M5) | 2064 × 2752 | iPad 13" |
| `vision` | Apple Vision Pro | 3840 × 2160 | Apple Vision Pro |
| `mac` | the Mac app, in a 1440 × 900 window | 2880 × 1800 | Mac |

They go to `apple/fastlane/screenshots/`, one folder per App Store Connect platform
(`ios`, `visionos`, `macos`) and language. The simulators are the script's own
(`c3lingo screenshots …`) and are erased before each run. Each gets the newest runtime
installed that supports it. The status bar shows 9:41 on 27 December
(`STATUS_BAR_DATE`).

Some steps need you:

- **iPhone Duo:** there is no command to fold or unfold the simulator. The script
  takes the outer display first (an erased simulator starts folded), then opens Device
  Hub and waits while you unfold it: select "c3lingo screenshots iPhone-Duo" under
  Simulators to open its window, and unfold it there. A simulator started from the
  command line has no window until you do. Unfolded, the inner display is in
  landscape.
- **Apple Vision Pro** needs the visionOS simulator, which Xcode doesn't install by
  default: `xcodebuild -downloadPlatform visionOS`. Without it, the script skips it.
- **Mac:** the script takes a picture of the app's window, which macOS only allows apps
  with permission to record the screen. Give it to your terminal app in System
  Settings → Privacy & Security → Screen & System Audio Recording, then restart the
  terminal. The window is 1440 × 900 points; on a display with Retina resolution, that
  makes the 2880 × 1800 pixels App Store Connect wants.

The upload uses an App Store Connect API key, like the TestFlight workflow (see
"Releasing the Apple app"); the App Manager role is enough. The version the screenshots
are for has to exist in App Store Connect, for each platform: create it under the app's
iOS, macOS and visionOS sections. The upload replaces all screenshots of each language
it has screenshots for. fastlane 2.241.1 or later is needed for the iPhone Duo.

### Android

```sh
android/screenshots/capture.sh                  # all of them, or e.g. `phone`
cd android
GOOGLE_PLAY_JSON_KEY_PATH=~/keys/c3lingo-play.json bundle exec fastlane upload_listing
```

`capture.sh` builds the debug app and starts an emulator without a window for each
form factor Google Play shows screenshots for:

| target | screen | Google Play |
|---|---|---|
| `phone` | 1080 × 1920 | phone |
| `tablet7` | 1200 × 1920 | 7-inch tablet |
| `tablet10` | 1600 × 2560 | 10-inch tablet |

The phone has a 16:9 screen because Google Play doesn't take screenshots longer than
2:1, which current phones' screens are. The virtual devices are the script's own
(`c3lingo_screenshots_…`), use the newest Google Play system image installed, and start
from scratch each time. The status bar shows 9:41 (demo mode), and the app's language
is set as in the system's per-app language setting. The screenshots go to
`android/fastlane/metadata/android/<language>/images/`, where fastlane expects them.
Commit them: F-Droid takes its screenshots from the repository.

The upload needs:

1. The app in Google Play Console. The API only works for an app with at least one
   build uploaded, so upload the first one by hand (Play wants an Android App Bundle,
   `./gradlew :app:bundleRelease`, for new apps).
2. A Google Cloud service account with a JSON key: in Google Cloud Console, create a
   project, enable the Google Play Android Developer API, create a service account and
   a key for it (JSON). Then, in Play Console → Users and permissions, invite the
   service account's email address and give it access to the app with permission to
   edit the store listing. Keep the JSON file outside the repository.

`upload_listing` uploads the whole store listing, texts included, and replaces the
screenshots that changed. Each release uploads it too.

### Settings

Both scripts take these environment variables:

| variable | default | |
|---|---|---|
| `SERVER_URL` | `https://c3listen.lassitu.de/` | the server the app shows |
| `CHANNEL` | `Saal_1_1_DE_EN` | the channel playing in the second screenshot |
| `WAIT` | `10` | seconds for the page to load and the channel to connect |
| `LANGUAGES` | all six | e.g. `"en:en-US:en_US de:de-DE:de_DE"` (Apple: app language, App Store locale, region) or `"en:en-US de:de-DE"` (Android: app language, Play locale) |

## License

MIT, see [LICENSE](LICENSE). The apps include LiveKit's build of WebRTC, which comes
under its own BSD-style license.
