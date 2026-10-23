#!/bin/sh
# Renders the app icons and the launch/splash logo from c3lingo-logo.svg, which is a copy
# of c3lingo-logo.svg from https://codeberg.org/c3lingo/c3lingo-design.
#
# Needs rsvg-convert and ImageMagick 7 (brew install librsvg imagemagick).
# Only the PNGs are written; the asset catalogs' Contents.json files are kept in git.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
svg=$here/c3lingo-logo.svg
assets=$here/../../apple/c3lingo-audio/Assets.xcassets
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Leave out timestamps, so unchanged icons render to identical files.
png() { magick "$@" -define png:exclude-chunks=date,time,tIME "PNG32:$out"; }

# round <in> <size> <radius>: clip a square image to a rounded rectangle
round() {
    magick "$1" \( -size "$2x$2" xc:black -fill white \
        -draw "roundrectangle 0,0,$(($2 - 1)),$(($2 - 1)),$3,$3" \) \
        -alpha off -compose CopyOpacity -composite "$tmp/rounded.png"
}

# iOS: full-bleed square, the system applies the mask
rsvg-convert -w 1024 -h 1024 "$svg" -o "$tmp/ios.png"
out=$assets/AppIcon.appiconset/AppIcon-1024.png
magick "$tmp/ios.png" -alpha off -define png:exclude-chunks=date,time,tIME "PNG24:$out"

# macOS: 824pt rounded rectangle with a soft shadow on a 1024 canvas (Apple's icon grid)
rsvg-convert -w 824 -h 824 "$svg" -o "$tmp/mac-tile.png"
round "$tmp/mac-tile.png" 824 185
magick "$tmp/rounded.png" \( +clone -background black -shadow 30x10+0+12 \) +swap \
    -background none -layers merge +repage "$tmp/shadowed.png"
magick -size 1024x1024 xc:none "$tmp/shadowed.png" -gravity center -geometry +0-4 \
    -composite "$tmp/mac-1024.png"
for size in 16 32 64 128 256 512 1024; do
    out=$assets/AppIcon.appiconset/AppIcon-mac-$size.png
    png "$tmp/mac-1024.png" -resize "${size}x$size"
done

# visionOS: yellow back layer, glyphs in front, scaled to 75% to fit the circular mask
stack=$assets/AppIcon-visionOS.solidimagestack
sed -e 's|<rect class="cls-1" width="513" height="513"/>|<g transform="translate(256 256) scale(0.75) translate(-256 -256)">|' \
    -e 's|</svg>|</g></svg>|' "$svg" > "$tmp/vision-front.svg"
rsvg-convert -w 1024 -h 1024 "$tmp/vision-front.svg" -o "$tmp/vision-front.png"
out=$stack/Front.solidimagestacklayer/Content.imageset/Front.png
png "$tmp/vision-front.png"
out=$stack/Back.solidimagestacklayer/Content.imageset/Back.png
magick -size 1024x1024 xc:'#fff200' -alpha off -define png:exclude-chunks=date,time,tIME "PNG24:$out"

# Launch and splash screen logo: 128pt rounded tile, corner radius as on the iOS icon
for scale in 1 2 3; do
    size=$((128 * scale))
    rsvg-convert -w "$size" -h "$size" "$svg" -o "$tmp/logo.png"
    round "$tmp/logo.png" "$size" $((115 * size / 512))
    out=$assets/Logo.imageset/Logo@${scale}x.png
    png "$tmp/rounded.png"
done
