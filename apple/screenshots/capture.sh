#!/bin/bash
# Takes the App Store screenshots: the app showing SERVER_URL, in every language, on
# every device class App Store Connect asks for. They end up where
# `bundle exec fastlane upload_screenshots` (see apple/fastlane/Fastfile) picks them up:
#
#   apple/fastlane/screenshots/ios/<locale>/        iPhone 6.3" and 6.9", iPhone Duo, iPad 13"
#   apple/fastlane/screenshots/visionos/<locale>/   Apple Vision Pro
#   apple/fastlane/screenshots/macos/<locale>/      Mac
#
# Usage: apple/screenshots/capture.sh [iphone] [duo] [ipad] [vision] [mac]
# Without arguments, all of them. See "Screenshots" in README.md.

set -euo pipefail

SERVER_URL=${SERVER_URL:-https://c3listen.lassitu.de/}
# The channel playing in the second screenshot.
CHANNEL=${CHANNEL:-Saal_1_1_DE_EN}
# Seconds to give the page to load, and a channel to connect, before each screenshot.
WAIT=${WAIT:-10}
# app language : App Store Connect locale : region format
LANGUAGES=${LANGUAGES:-"en:en-US:en_US de:de-DE:de_DE fr:fr-FR:fr_FR es:es-ES:es_ES pt:pt-PT:pt_PT pl:pl:pl_PL"}

# Date and time in the simulators' status bar, local time. iPads show the date, too.
STATUS_BAR_DATE=${STATUS_BAR_DATE:-"2026-12-27 09:41"}

BUNDLE_ID=org.c3lingo.audio
# simctl only takes ISO dates with milliseconds and a colon in the time zone offset.
STATUS_BAR_TIME=$(date -j -f '%Y-%m-%d %H:%M:%S' "$STATUS_BAR_DATE:00" +%Y-%m-%dT%H:%M:%S.000%z | sed 's/\(..\)$/:\1/')
here=$(cd "$(dirname "$0")" && pwd)
apple=$(dirname "$here")
out=$apple/fastlane/screenshots
derived=$apple/build/screenshots
helper=$here/helper.swift
targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(iphone duo ipad vision mac)
skipped=()

log() { printf '\033[1m%s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# The screenshots: file name and whether a channel plays.
screens() {
    echo "01-channels -"
    echo "02-playing $CHANNEL"
}

# Removes the earlier screenshots of a device class, e.g. `clear_screenshots ios iphone-6.9`.
clear_screenshots() {
    rm -f "$out/$1"/*/"$2"-*.png
}

# Checks that a screenshot has a size App Store Connect expects, one of a list such as
# "2007x2853 2853x2007". With `scale`, one with the aspect ratio of the first is scaled
# to it instead.
check_size() {
    local file=$1 sizes=$2 scale=${3:-} size expected
    size=$(sips -g pixelWidth -g pixelHeight "$file" | awk '/pixelWidth/ {w=$2} /pixelHeight/ {h=$2} END {print w "x" h}')
    for expected in $sizes; do
        [ "$size" = "$expected" ] && return
    done
    expected=${sizes%% *}
    if [ -n "$scale" ] && [ $(( ${size%x*} * ${expected#*x} )) -eq $(( ${size#*x} * ${expected%x*} )) ]; then
        sips -z "${expected#*x}" "${expected%x*}" "$file" >/dev/null
        return
    fi
    die "$file is $size, App Store Connect expects ${sizes// / or }"
}

build() {
    local destination=$1; shift
    log "Building for $destination"
    xcodebuild -project "$apple/c3lingo-audio.xcodeproj" -scheme c3lingo-audio -configuration Debug \
        -destination "$destination" -derivedDataPath "$derived" -quiet "$@" build
}

# MARK: Simulators

# The newest installed runtime that runs a device type. Some are only in a runtime of
# their own, e.g. the iPhone Duo's.
runtime() {
    xcrun simctl list runtimes -j | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"]
            if r["isAvailable"] and any(t["identifier"] == sys.argv[1] for t in r.get("supportedDeviceTypes", []))]
runtimes.sort(key=lambda r: [int(p) for p in r["version"].split(".")])
print(runtimes[-1]["identifier"] if runtimes else "")' "$1"
}

# A simulator of its own for each device type, created on first use and erased before
# each run, so nothing from earlier runs or other work shows up.
simulator() {
    local type=$1 runtime=$2 name="c3lingo screenshots ${1##*.}" udid
    udid=$(xcrun simctl list devices -j | python3 -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["name"] == sys.argv[1]:
            print(d["udid"], runtime)' "$name" | while read -r id rt; do
        if [ "$rt" = "$runtime" ]; then echo "$id"; else xcrun simctl delete "$id"; fi
    done)
    [ -n "$udid" ] || udid=$(xcrun simctl create "$name" "$type" "$runtime")
    xcrun simctl shutdown "$udid" 2>/dev/null || true
    xcrun simctl erase "$udid"
    xcrun simctl boot "$udid"
    xcrun simctl bootstatus "$udid" -b >/dev/null
    xcrun simctl ui "$udid" appearance light 2>/dev/null || true
    xcrun simctl status_bar "$udid" override --time "$STATUS_BAR_TIME" --dataNetwork wifi --wifiMode active --wifiBars 3 \
        --cellularMode active --cellularBars 4 --batteryState discharging --batteryLevel 100 2>/dev/null || true
    echo "$udid"
}

# Takes the screenshots on a simulator's display, in every language.
# Usage: shoot <udid> <display or ""> <directory> <file prefix> <expected size> [scale]
shoot() {
    local udid=$1 display=$2 dir=$3 prefix=$4 expected=$5 scale=${6:-} entry lang locale region name channel file args
    for entry in $LANGUAGES; do
        IFS=: read -r lang locale region <<< "$entry"
        mkdir -p "$out/$dir/$locale"
        while read -r -u 3 name channel; do
            file=$out/$dir/$locale/$prefix-$name.png
            xcrun simctl terminate "$udid" "$BUNDLE_ID" 2>/dev/null || true
            args=(-serverURL "$SERVER_URL" -helpShown YES -AppleLanguages "($lang)" -AppleLocale "$region")
            [ "$channel" = - ] || args+=(-autoplay "$channel")
            xcrun simctl launch "$udid" "$BUNDLE_ID" "${args[@]}" >/dev/null
            sleep "$WAIT"
            xcrun simctl io "$udid" screenshot ${display:+--display="$display"} "$file" >/dev/null 2>&1
            check_size "$file" "$expected" "$scale"
            echo "  $dir/$locale/$prefix-$name.png" >&2
        done 3< <(screens)
    done
    xcrun simctl terminate "$udid" "$BUNDLE_ID" 2>/dev/null || true
}

blank() { xcrun swift "$helper" blank "$1"; }

# Waits until a display of the iPhone Duo is on (or off), asking to fold or unfold it:
# there is no command for that, only Device Hub.
# Usage: wait_for_display <udid> <display> on|off <request>
wait_for_display() {
    local udid=$1 display=$2 state=$3 request=$4 probe asked=
    probe=$(mktemp -t c3lingo-display)
    while true; do
        xcrun simctl io "$udid" screenshot --type=png --display="$display" "$probe" >/dev/null 2>&1
        if blank "$probe"; then [ "$state" = off ] && break; else [ "$state" = on ] && break; fi
        if [ -z "$asked" ]; then
            open -a "$(xcode-select -p)/../Applications/DeviceHub.app" 2>/dev/null || true
            log "$request: in Device Hub, select \"c3lingo screenshots iPhone-Duo\" under Simulators to open its window. Waiting…"
            asked=1
        fi
        sleep 2
    done
    rm -f "$probe"
}

ios_app() { echo "$derived/Build/Products/Debug-iphonesimulator/c3lingo-audio.app"; }

capture_simulator() {
    local type=$1 dir=$2 prefix=$3 expected=$4 runtime udid
    runtime=$(runtime "$type")
    [ -n "$runtime" ] || die "No simulator runtime for ${type##*.}; install the newest iOS in Xcode → Settings → Components"
    log "$prefix: $type"
    udid=$(simulator "$type" "$runtime")
    xcrun simctl install "$udid" "$(ios_app)"
    clear_screenshots "$dir" "$prefix"
    shoot "$udid" "" "$dir" "$prefix" "$expected"
    xcrun simctl shutdown "$udid"
}

capture_iphone() {
    # Dynamic Island, medium (6.3", required) and large (6.9").
    capture_simulator com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro ios iphone-6.3 1206x2622
    capture_simulator com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro-Max ios iphone-6.9 1320x2868
}

capture_ipad() {
    capture_simulator com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-12GB ios ipad-13 2064x2752
}

capture_duo() {
    # Both displays go into the same iPhone Duo set: outer first, then inner. Unfolded,
    # the inner display is in landscape.
    local type=com.apple.CoreSimulator.SimDeviceType.iPhone-Duo runtime udid outer=primary inner=primary-1
    runtime=$(runtime "$type")
    [ -n "$runtime" ] || die "No simulator runtime for the iPhone Duo; install iOS 27.1 or later in Xcode → Settings → Components"
    log "iPhone Duo"
    udid=$(simulator "$type" "$runtime")
    xcrun simctl install "$udid" "$(ios_app)"
    rm -f "$out"/ios/*/duo-*.png
    wait_for_display "$udid" "$outer" on "Fold the iPhone Duo"
    shoot "$udid" "$outer" ios duo-1-outer "1398x2034 2034x1398"
    wait_for_display "$udid" "$inner" on "Unfold the iPhone Duo"
    shoot "$udid" "$inner" ios duo-2-inner "2007x2853 2853x2007"
    xcrun simctl shutdown "$udid"
}

capture_vision() {
    local type=com.apple.CoreSimulator.SimDeviceType.Apple-Vision-Pro-4K runtime udid
    runtime=$(runtime "$type")
    if [ -z "$runtime" ]; then
        log "Skipping Apple Vision Pro: no visionOS simulator runtime. Install it with: xcodebuild -downloadPlatform visionOS"
        skipped+=(vision)
        return
    fi
    # LiveKit's WebRTC has no Intel slice for the visionOS simulator.
    build 'generic/platform=visionOS Simulator' ARCHS=arm64
    log "Apple Vision Pro"
    udid=$(simulator "$type" "$runtime")
    xcrun simctl install "$udid" "$derived/Build/Products/Debug-xrsimulator/c3lingo-audio.app"
    clear_screenshots visionos vision
    shoot "$udid" "" visionos vision 3840x2160 scale
    xcrun simctl shutdown "$udid"
}

# MARK: Mac

capture_mac() {
    local app plist key screen entry lang locale region name channel file pid window args
    build "platform=macOS,arch=$(uname -m)"
    app=$derived/Build/Products/Debug/c3lingo-audio.app/Contents/MacOS/c3lingo-audio
    plist=~/Library/Containers/$BUNDLE_ID/Data/Library/Preferences/$BUNDLE_ID.plist
    frame_key() {
        [ -f "$plist" ] || return 0
        python3 -c '
import plistlib, sys
for key in plistlib.load(open(sys.argv[1], "rb")):
    if key.startswith("NSWindow Frame SwiftUI.WindowGroup"):
        print(key)
        break' "$plist"
    }
    # The window's size comes from the frame SwiftUI saves under this key, which an
    # argument overrides for one run. Launch once, if needed, so that it exists.
    key=$(frame_key)
    if [ -z "$key" ]; then
        "$app" -serverURL "$SERVER_URL" -helpShown YES >/dev/null 2>&1 & pid=$!
        sleep 5; kill "$pid"; wait "$pid" 2>/dev/null || true
        key=$(frame_key)
        [ -n "$key" ] || die "Can't find the window frame the app saved in $plist"
    fi
    screen=$(xcrun swift "$helper" screen)
    log "Mac"
    clear_screenshots macos mac
    for entry in $LANGUAGES; do
        IFS=: read -r lang locale region <<< "$entry"
        mkdir -p "$out/macos/$locale"
        while read -r -u 3 name channel; do
            file=$out/macos/$locale/mac-$name.png
            # 1440 × 900 points, 16:10, gives 2880 × 1800 pixels on a Retina display.
            args=(-serverURL "$SERVER_URL" -helpShown YES -AppleLanguages "($lang)" -AppleLocale "$region"
                  -ApplePersistenceIgnoreState YES "-$key" "100 100 1440 900 $screen ")
            [ "$channel" = - ] || args+=(-autoplay "$channel")
            "$app" "${args[@]}" >/dev/null 2>&1 & pid=$!
            sleep "$WAIT"
            window=$(xcrun swift "$helper" window "$pid") || die "The app's window didn't show up"
            if ! screencapture -o -x -l"$window" "$file" 2>/dev/null || [ ! -s "$file" ]; then
                kill "$pid"
                die "Can't capture the window. Allow your terminal app to record the screen in System Settings → Privacy & Security → Screen & System Audio Recording, restart it and run again."
            fi
            kill "$pid"; wait "$pid" 2>/dev/null || true
            check_size "$file" 2880x1800
            echo "  macos/$locale/mac-$name.png" >&2
        done 3< <(screens)
    done
}

# MARK: Main

for target in "${targets[@]}"; do
    case $target in
        iphone|duo|ipad|vision|mac) ;;
        *) die "Unknown target $target; use iphone, duo, ipad, vision or mac" ;;
    esac
done
case " ${targets[*]} " in *" iphone "*|*" duo "*|*" ipad "*) build 'generic/platform=iOS Simulator' ;; esac
for target in "${targets[@]}"; do
    "capture_$target"
done
log "Done: $out"
[ ${#skipped[@]} -eq 0 ] || log "Skipped: ${skipped[*]}"
