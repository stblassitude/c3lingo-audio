#!/bin/bash
# Takes the Google Play screenshots: the app showing SERVER_URL, in every language, on a
# phone and on 7" and 10" tablets, each in an emulator of its own. They end up where
# `bundle exec fastlane upload_screenshots` (see android/fastlane/Fastfile) picks them up:
#
#   android/fastlane/metadata/android/<locale>/images/phoneScreenshots/
#   android/fastlane/metadata/android/<locale>/images/sevenInchScreenshots/
#   android/fastlane/metadata/android/<locale>/images/tenInchScreenshots/
#
# Usage: android/screenshots/capture.sh [phone] [tablet7] [tablet10]
# Without arguments, all of them. See "Screenshots" in README.md.

set -euo pipefail

SERVER_URL=${SERVER_URL:-https://c3listen.lassitu.de/}
# The channel playing in the second screenshot.
CHANNEL=${CHANNEL:-Saal_1_1_DE_EN}
# Seconds to give the page to load, and a channel to connect, before each screenshot.
WAIT=${WAIT:-10}
# app language : Google Play locale
LANGUAGES=${LANGUAGES:-"en:en-US de:de-DE fr:fr-FR es:es-ES pt:pt-PT pl:pl-PL"}

export ANDROID_HOME=${ANDROID_HOME:-~/Library/Android/sdk}
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
[ -n "${JAVA_HOME:-}" ] || export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

PACKAGE=org.c3lingo.audio
here=$(cd "$(dirname "$0")" && pwd)
android=$(dirname "$here")
out=$android/fastlane/metadata/android
avds=${ANDROID_AVD_HOME:-~/.android/avd}
# A port of its own, so a running emulator doesn't get in the way.
port=5580
export ANDROID_SERIAL=emulator-$port
targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(phone tablet7 tablet10)

log() { printf '\033[1m%s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# The screenshots: file name and whether a channel plays.
screens() {
    echo "01-channels -"
    echo "02-playing $CHANNEL"
}

# The newest installed Google Play system image for this Mac's architecture.
system_image() {
    local abi=arm64-v8a
    [ "$(uname -m)" = arm64 ] || abi=x86_64
    find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -path "*/google_apis_playstore/$abi" 2>/dev/null |
        sed "s|^$ANDROID_HOME/||" | sort -V | tail -1
}

# A virtual device of its own for each form factor, written directly, so this doesn't
# need the SDK's command line tools.
# Usage: avd <name> <width> <height> <density>
avd() {
    local name=c3lingo_screenshots_$1 image=$2 width=$3 height=$4 density=$5 target
    target=$(echo "$image" | cut -d/ -f2)
    mkdir -p "$avds/$name.avd"
    cat > "$avds/$name.ini" <<EOF
avd.ini.encoding=UTF-8
path=$avds/$name.avd
path.rel=avd/$name.avd
target=$target
EOF
    cat > "$avds/$name.avd/config.ini" <<EOF
AvdId=$name
avd.ini.displayname=c3lingo screenshots $1
avd.ini.encoding=UTF-8
PlayStore.enabled=true
abi.type=$(basename "$image")
hw.cpu.arch=$( [ "$(basename "$image")" = arm64-v8a ] && echo arm64 || echo x86_64 )
hw.cpu.ncore=4
hw.ramSize=4096
disk.dataPartition.size=6G
hw.lcd.width=$width
hw.lcd.height=$height
hw.lcd.density=$density
hw.keyboard=yes
hw.mainKeys=no
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.initialOrientation=portrait
image.sysdir.1=$image/
skin.dynamic=yes
showDeviceFrame=no
tag.id=google_apis_playstore
tag.display=Google Play
target=$target
EOF
    echo "$name"
}

stop_emulator() {
    adb emu kill >/dev/null 2>&1 || true
    while adb get-state >/dev/null 2>&1; do sleep 1; done
}

# Starts a virtual device from scratch, without a window, and sets it up for screenshots.
start_emulator() {
    local name=$1
    stop_emulator
    emulator -avd "$name" -port $port -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        >"$TMPDIR/$name.log" 2>&1 &
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
    # Booted isn't ready: wait for the home screen, by when the status bar takes demo
    # mode, and for a network that reaches the internet.
    until adb shell dumpsys window 2>/dev/null | grep -q 'mCurrentFocus=.*[Ll]auncher'; do sleep 2; done
    until adb shell dumpsys connectivity 2>/dev/null | grep -q IS_VALIDATED; do sleep 2; done
    adb shell cmd uimode night no >/dev/null
    adb install -r "$apk" >/dev/null
}

# Demo mode: a clean status bar with a fixed time. Set before each screenshot: soon
# after the first boot, the system resets the setting that allows it.
demo_mode() {
    adb shell settings put global sysui_demo_allowed 1
    # The status bar takes a moment to notice.
    sleep 2
    demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
    demo enter
    demo clock -e hhmm 0941
    demo battery -e level 100 -e plugged false
    demo network -e wifi show -e level 4 -e mobile hide
    demo notifications -e visible false
}

check_size() {
    local file=$1 expected=$2 size
    size=$(sips -g pixelWidth -g pixelHeight "$file" | awk '/pixelWidth/ {w=$2} /pixelHeight/ {h=$2} END {print w "x" h}')
    [ "$size" = "$expected" ] || die "$file is $size, expected $expected"
}

# Usage: capture <form factor> <Play image type> <width> <height> <density>
capture() {
    local form=$1 type=$2 width=$3 height=$4 density=$5 image name entry lang locale screen channel file
    image=$(system_image)
    [ -n "$image" ] || die "No Google Play system image; install one in Android Studio → Settings → Android SDK → SDK Platforms (Show Package Details)"
    log "$form: ${width}×${height}, $image"
    name=$(avd "$form" "$image" "$width" "$height" "$density")
    start_emulator "$name"
    for entry in $LANGUAGES; do
        IFS=: read -r lang locale <<< "$entry"
        mkdir -p "$out/$locale/images/$type"
        rm -f "$out/$locale/images/$type"/*.png
        # The app's own language, as set in the system settings (Android 13 and later).
        adb shell cmd locale set-app-locales "$PACKAGE" --locales "$lang"
        while read -r -u 3 screen channel; do
            file=$out/$locale/images/$type/$screen.png
            adb shell am force-stop "$PACKAGE"
            args=(-e serverURL "$SERVER_URL")
            [ "$channel" = - ] || args+=(-e autoplay "$channel")
            adb shell am start -W -n "$PACKAGE/.MainActivity" "${args[@]}" >/dev/null
            demo_mode
            sleep "$WAIT"
            adb exec-out screencap -p > "$file"
            check_size "$file" "${width}x${height}"
            echo "  $locale/images/$type/$screen.png" >&2
        done 3< <(screens)
    done
    adb shell am force-stop "$PACKAGE"
    stop_emulator
}

# Google Play takes nothing longer than 2:1, so the phone gets a 16:9 screen, not a current 20:9 one.
capture_phone() { capture phone phoneScreenshots 1080 1920 420; }
capture_tablet7() { capture tablet7 sevenInchScreenshots 1200 1920 320; }
capture_tablet10() { capture tablet10 tenInchScreenshots 1600 2560 320; }

for target in "${targets[@]}"; do
    case $target in
        phone|tablet7|tablet10) ;;
        *) die "Unknown target $target; use phone, tablet7 or tablet10" ;;
    esac
done
log "Building"
(cd "$android" && ./gradlew -q :app:assembleDebug)
apk=$android/app/build/outputs/apk/debug/app-debug.apk
[ -f "$apk" ] || apk=$(ls "$android"/app/build/outputs/apk/debug/*universal*.apk 2>/dev/null | head -1)
[ -f "$apk" ] || die "Can't find the debug APK in $android/app/build/outputs/apk/debug"
trap stop_emulator EXIT
for target in "${targets[@]}"; do
    "capture_$target"
done
log "Done: $out"
