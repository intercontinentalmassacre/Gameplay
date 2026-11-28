#!/usr/bin/env bash
set -euo pipefail

output=${1:?Usage: stage.sh OUTPUT_DIR}
directory=$(dirname "$0")
cache=${BURAN_DXVK_CACHE:-"${XDG_CACHE_HOME:-$HOME/.cache}/buran-dxvk"}
version=2.6.2
name=dxvk-$version
archive="$cache/$name.tar.gz"
sha256=17761876556afd55736cb895d184f5a1c55d43350f1b1e3b129f8d28706d7992
release=https://github.com/doitsujin/dxvk/releases

verify() {
    printf '%s  %s\n' "$sha256" "$1" | sha256sum -c --status
}

mkdir -p "$cache" "$output"
download=
work=$(mktemp -d "$output/.stage.XXXXXXXX")
trap 'rm -rf "$work"; if [[ -n "$download" ]]; then rm -f "$download"; fi' EXIT
if [[ ! -f "$archive" ]] || ! verify "$archive"; then
    download=$(mktemp "$cache/.download.XXXXXXXX")
    curl --retry 2 -fLSs "$release/download/v$version/$name.tar.gz" -o "$download"
    verify "$download"
    mv -f "$download" "$archive"
fi

tar -xzf "$archive" -C "$work" "$name/x64/d3d11.dll" "$name/x64/dxgi.dll"
mkdir -p "$output/assets/dxvk" "$output/assets/notices"
mv -f "$work/$name/x64/d3d11.dll" "$work/$name/x64/dxgi.dll" "$output/assets/dxvk/"
printf '%s\n' "$sha256" > "$output/assets/dxvk/payload.id"
cp "$directory/$name-LICENSE.txt" "$directory/$name-openvr-LICENSE.txt" "$output/assets/notices/"
cat > "$output/assets/notices/dxvk.txt" <<EOF_NOTICE
Buran includes the unmodified x64 d3d11.dll and dxgi.dll from DXVK $version.
Release $release/tag/v$version
Archive SHA-256 $sha256
Source https://github.com/doitsujin/dxvk/tree/v$version
EOF_NOTICE
