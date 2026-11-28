#!/usr/bin/env bash
set -euo pipefail

output=${1:?Usage: wine-stage.sh OUTPUT_DIR}
cache=${BURAN_BOX_CACHE:-"${XDG_CACHE_HOME:-$HOME/.cache}/buran-box"}
release=https://github.com/Kron4ek/Wine-Builds/releases/download/10.0
source=https://raw.githubusercontent.com/wine-mirror/wine/wine-10.0
mkdir -p "$output" "$cache"
work=$(mktemp -d "$output/.wine-stage.XXXXXXXX")
trap 'rm -rf "$work"' EXIT

verify() {
    printf '%s  %s\n' "$1" "$2" | sha256sum -c --status
}

fetch() {
    local name=$1 url=$2 hash=$3
    if [[ ! -f "$cache/$name" ]] || ! verify "$hash" "$cache/$name"; then
        curl --retry 2 -fLSs "$url" -o "$work/$name"
        verify "$hash" "$work/$name"
        mv -f "$work/$name" "$cache/$name"
    fi
}

wine64=wine-10.0-amd64-wow64.tar.xz
wine86=wine-10.0-x86.tar.xz
fetch "$wine64" "$release/$wine64" aeebbbf239e548f0136f1cd72a2e109dd9a572a8f703da3c09fa40d74d5c255f
fetch "$wine86" "$release/$wine86" c5be273749f5c513b3edf7ad79d8214be558b80c67c6ca343d2f17fdcc30c2d2
fetch box-wine-10.0-LICENSE.txt "$source/LICENSE" 62ee29c6334de7719922090246c7377d20ed941b4a5903486a7652b45c0affc0
fetch box-wine-10.0-COPYING.LIB.txt "$source/COPYING.LIB" e237fa56668030e928551ddd60f05df5fe957f75eab874bbd017e085ed722e7c

mkdir -p "$work/assets/box" "$work/assets/notices"
cp "$cache/$wine64" "$work/assets/box/wine64.tar.xz"
cp "$cache/$wine86" "$work/assets/box/wine86.tar.xz"
cp "$cache/box-wine-10.0-LICENSE.txt" "$cache/box-wine-10.0-COPYING.LIB.txt" "$work/assets/notices/"
cat > "$work/assets/notices/box-wine.txt" <<'EOF_NOTICE'
Buran includes vanilla Kron4ek Wine 10.0 builds for Linux x86_64 WoW64 and i386.
Release https://github.com/Kron4ek/Wine-Builds/releases/tag/10.0
Release asset IDs 222247955 (amd64-wow64) and 222247759 (x86)
Build scripts https://github.com/Kron4ek/Wine-Builds/tree/25cebd75cd24a303aee33aeef701e40d653cb18a
Wine source https://gitlab.winehq.org/wine/wine/-/tree/wine-10.0
Wine is copyright the Wine project authors and distributed under LGPL-2.1-or-later.
EOF_NOTICE

for dir in assets/box assets/notices; do
    mkdir -p "$output/$dir"
    mv -f "$work/$dir/"* "$output/$dir/"
done
