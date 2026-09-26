#!/usr/bin/env bash
# Render the canonical SVG; optionally update the companion checkout's assets.
set -euo pipefail

branding_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command -v magick >/dev/null || { echo 'ImageMagick 7 (magick) is required.' >&2; exit 1; }

render_icon() {
    # Give tiny raster favicons one physical pixel of line weight.
    local stroke_width=2.75
    if [[ $1 -le 16 ]]; then stroke_width=4.5; fi
    sed "s/stroke-width=\"2.75\"/stroke-width=\"$stroke_width\"/" "$branding_dir/lightious-icon.svg" | \
        magick -background none -density 384 svg:- \
        -resize "${1}x${1}" -strip "PNG32:$2"
}

render_icon 512 "$branding_dir/lightious-icon.png"

if [[ $# -gt 0 ]]; then
    assets="$1/assets"
    [[ -d "$assets" ]] || { echo "Missing companion assets directory: $assets" >&2; exit 1; }
    cp "$branding_dir/lightious-icon.svg" "$assets/lightious-icon.svg"
    cp "$branding_dir/lightious-mark.svg" "$assets/lightious-mark.svg"
    sed 's/stroke="currentColor"/stroke="#000000"/' "$branding_dir/lightious-mark.svg" > "$assets/safari-pinned-tab.svg"
    render_icon 512 "$assets/android-chrome-512x512.png"
    render_icon 192 "$assets/android-chrome-192x192.png"
    render_icon 180 "$assets/apple-touch-icon.png"
    render_icon 150 "$assets/mstile-150x150.png"
    render_icon 32 "$assets/favicon-32x32.png"
    render_icon 16 "$assets/favicon-16x16.png"
    magick \( "$branding_dir/lightious-icon.png" -resize 48x48 \) \
        "$assets/favicon-32x32.png" "$assets/favicon-16x16.png" "$assets/favicon.ico"
fi
