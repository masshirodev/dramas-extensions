#!/usr/bin/env bash
# Draws each extension's launcher icon: its initials on a colour, in the five
# densities Android wants, plus the 512 px copy the repository serves (doc/RELEASING.md).
# Original artwork on purpose: the sites' own logos are their trademarks.
set -euo pipefail
cd "$(dirname "$0")/.."

FONT=${ICON_FONT:-/usr/share/fonts/inter/InterVariable.ttf}

draw() { # <module dir> <initials> <background>
  local dir=$1 text=$2 bg=$3
  local master; master=$(mktemp --suffix=.png)
  magick -size 512x512 xc:none -fill "$bg" -draw "roundrectangle 0,0 511,511 112,112" \
    -font "$FONT" -fill white -pointsize 220 -gravity center -annotate +0+8 "$text" \
    -strip "$master"
  for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    mkdir -p "$dir/src/main/res/mipmap-${d%%:*}"
    magick "$master" -resize "${d##*:}x${d##*:}" -strip "$dir/src/main/res/mipmap-${d%%:*}/ic_launcher.png"
  done
  mkdir -p "$dir/icon"
  cp "$master" "$dir/icon/ic_launcher-512.png"
  rm -f "$master"
}

draw src/en/reelshort RS '#c2185b'
draw src/en/dramabox DB '#3949ab'
