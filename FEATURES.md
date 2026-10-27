# Features

What the apps do, described independently of any platform, so each app can implement
the same behaviour. The Apple app (iOS, iPadOS, macOS, visionOS) is the reference; the
Android app follows it.

When a feature changes, update this file in the same change, for every app, and mark
what isn't done yet in the table at the end.

## Frontend

- **The web frontend is the UI.** The app loads
  [listen-frontend](https://codeberg.org/c3lingo/listen-frontend) from the server and
  shows it full screen. Branding and channel layout come from the server and need no app
  update.
- **Native bridge, protocol version 2.** `shared/FrontendBridge.js` is injected into the
  page before its own scripts run, and only into pages from the configured server. Pages
  that declare `<meta name="c3lingo-native-bridge" content="2">` get
  `window.c3lingoNative` and leave playback to the app; any other page plays audio itself.
  Only messages from the main frame are accepted. See the README for the state mapping.
- **The player is the source of truth.** Commands from the page go to the player, and
  every state change, whatever caused it (page, player bar, lock screen, reconnect), is
  reported back to the page in full. A reloaded page gets the current state on `ready()`,
  so audio keeps playing across reloads.
- **Links leaving the frontend open in the browser**, e.g. the schedule. Links within
  the server stay in the app.
- **Links open the app**: `https://audio.c3lingo.org/` (once the server has the app link
  files) and `c3lingo:`, with a channel, a hall or a talk's GUID in the fragment. Once
  the page has called `ready()` and `describe()`, a channel the page described plays,
  unless it already is, and the page gets a `c3lingo:link` event with `{channel}`,
  `{hall}` or `{talk}`, so it can show it. A link without a fragment just opens the app.
  See the README.
- **Native help.** The page's help text (`help(content)`: headings, paragraphs and
  bulleted lists, with bold labels and `https`/`mailto` links) shows in a sheet (Apple)
  or dialog (Android) with a Done button. It shows by itself once, the first time a page
  that has it has loaded, and whenever the page's help button calls `showHelp()`.
  Unknown block types and run fields are left out.
- **Pull to refresh** reloads the page (iOS, Android).
- **No zooming:** the page scrolls, but pinching and double tapping don't zoom it.
- **The web content process may crash**; the page is then reloaded without user
  interaction.
- **Inspectable in debug builds** (Safari Web Inspector, Chrome `chrome://inspect`).

## Launch and loading

- **App icon** from `shared/icon/c3lingo-logo.svg`, in each platform's format:
  full-bleed square (iOS), rounded tile with shadow (macOS), layered with the lettering
  scaled into the circle (visionOS), adaptive and themed icon (Android).
- **Splash screen while the page loads.** The logo is centred on the screen with a
  spinner and "Loading…" below it, on the system background, until the page has
  finished loading the first time. It fades out. It shows again when the server changes.
- **Launch screen matches the splash screen** (iOS: logo at the same position, so the
  handover is seamless; Android: the system splash screen shows the app icon, then the
  splash screen takes over).
- **Only c3lingo frontends are shown.** A page the app loads itself (at launch, on Try
  Again, pull to refresh or a server change) must declare
  `<meta name="c3lingo-native-bridge">`, in any version; pages the user navigates to
  from there aren't checked. The splash screen stays until the check has passed.
- **Load errors** replace the page with "Unable to load the channel list", an
  explanation (the server URL doesn't appear to be a c3lingo audio site; check the
  network connection and the URL), then in small print the technical reason (the
  network error, the HTTP status such as "HTTP 404", or "The site is not compatible.")
  and the server URL, a **Try Again** button and a way to change the server.

## Playback

- **Receive-only Galène client**, protocol version 2: handshake, join the channel's
  group, request audio only, answer each offer on its own peer connection, trickle ICE
  both ways, answer pings and send a ping every 5 seconds. TURN/STUN servers from the
  server's `rtcConfiguration` are used. Streams labelled anything but `camera` (or
  unlabelled, as from WHIP) are refused.
- **Endpoint lookup.** If the frontend doesn't pass the channel's WebSocket endpoint, it
  is read from `<group URL>/.status`.
- **One channel at a time.** Playing another channel replaces the current one.
- **Never uses the microphone**, so there is no permission prompt, and audio plays as
  media (loudspeaker or headphones, media volume), not as a call.
- **Mute** keeps the connection but silences the audio, so unmuting is instant.
- **Pause** disconnects but keeps the channel selected; **play** reconnects to it.
  **Stop** disconnects and clears the channel.
- **Plays in the background and with the screen locked.**

## Staying connected

- **Status:** connecting, waiting for audio (joined, nobody sending), live, paused,
  reconnecting (with the reason).
- **Reconnect with backoff** after any failure: 1, 2, 4, 8 seconds, then every
  15 seconds, until it works or the user pauses or stops. The backoff resets once audio
  flows.
- **Timeout:** if nothing arrives from the server for 15 seconds, the connection counts
  as failed.
- **Network comes back:** reconnects right away instead of waiting for the backoff.
- **Interruptions** such as phone calls: playback stops for the duration. Afterwards it
  reconnects when the system says playback may resume, otherwise it stays paused.
  Live audio can't continue where it left off, so resuming always reconnects.
- **Audio system reset** (iOS media services reset): reconnects.
- **Headphones unplugged or Bluetooth disconnected:** pauses (Android convention).

## System integration

- **Player bar** at the bottom while a channel is selected: channel name, status with a
  live indicator or progress, mute, play/pause, audio output, stop. Slides in and out.
  The controls are the platform's standard borderless icon buttons at twice the usual
  size. Narrow (portrait phones): hall, language, talk, status and message centred, with
  the controls centred below. Wide: the information on the left, the controls on the right.
- **Lock screen, Control Center / notification and headset controls:** title (channel),
  artist (server host), shown as a live stream. Play, pause and toggle work. Seeking
  and skipping are disabled; on iOS and macOS stop is too, so the lock screen shows pause.
- **Audio output selection:** AirPlay and Bluetooth route picker (iOS, macOS); the system
  output switcher (Android).

## Settings and testing

- **Server URL**, empty for the default `https://audio.c3lingo.org/`. Input without a
  scheme gets `https://`, a trailing slash is added, only `http` and `https` are
  accepted. Changing it stops playback and loads the new frontend.
  - **Server dialog** (iOS, iPadOS, visionOS: a sheet; Android: a dialog), opened from a
    small cog in the bottom right corner of the page, over the page and scrolling with
    it, and from Change Server… on the load error screen. One field, showing the URL
    without `https://` (`http://` stays), the default as placeholder, and a button that
    empties it to restore the default. Once the host looks complete and typing pauses,
    the page is fetched: a host that can't be found, a connection problem, an HTTP error
    or a page that isn't a c3lingo frontend is shown below the field, and the confirm
    button stays disabled until the check passes. The buttons are worded as each
    platform does: Cancel and Done (iOS), Cancel and Save (Android). Confirming always
    reloads the page.
  - Also: iOS, iPadOS, visionOS: the Settings app. macOS: the Settings window, with
    validation, Use Default and Apply. Android: the app's settings screen (from the gear
    on the system's App info screen), with the same validation and buttons as on macOS.
- **Plain `http`:** allowed for local network addresses (Apple); allowed in debug builds
  only (Android, which can't limit it to local addresses).
- **`serverURL` launch option** replaces the default for that run only, including the
  dialog's placeholder; a URL saved in the app still takes precedence.
- **`autoplay` launch option** selects a channel once the page has loaded, as if it had
  been clicked.

## Status

| Feature | Apple | Android |
|---|---|---|
| Frontend with bridge, main frame and own origin only | ✓ | ✓ |
| State reported on every change and on `ready()` | ✓ | ✓ |
| External links open in the browser | ✓ | ✓ |
| Links open the app (`https`, `c3lingo:`) | ✓ | ✓ |
| Native help, first launch and help button | ✓ | ✓ |
| Pull to refresh | ✓ (iOS) | ✓ |
| Reload after web content crash | ✓ | ✓ |
| App icon | ✓ | ✓ |
| Splash screen, matching launch screen | ✓ | ✓ (system splash shows the icon) |
| Load error screen | ✓ | ✓ |
| Galène client, endpoint lookup, TURN | ✓ | ✓ |
| Media playback without microphone | ✓ | ✓ |
| Mute, pause, stop | ✓ | ✓ |
| Background playback | ✓ | ✓ (foreground service) |
| Reconnect with backoff, timeout, network change | ✓ | ✓ |
| Interruptions | ✓ | ✓ (audio focus) |
| Pause when headphones are unplugged | – | ✓ |
| Player bar | ✓ | ✓ |
| Lock screen and headset controls | ✓ | ✓ (media3 session) |
| Audio output selection | ✓ (iOS, macOS) | ✓ |
| Server URL setting | ✓ | ✓ |
| `serverURL` and `autoplay` launch options | ✓ | ✓ (intent extras) |

The Android app has so far only been tried in the emulator: playback works; the lock screen, notification, interruptions and output switching are untested.
