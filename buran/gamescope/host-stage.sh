#!/usr/bin/env bash
set -euo pipefail

output=${1:?Usage: host-stage.sh OUTPUT_DIR}
cache=${BURAN_GAMESCOPE_CACHE:-"${XDG_CACHE_HOME:-$HOME/.cache}/buran-gamescope"}
version=0.7.11
source_commit=c6169cba808d82f4d247b00bf01827506c1db54d
license_hash=a4874def444c8ecba99b613a60431b78f6e8931f7713efcc78312eda9ced811f
apk_name="DroidDeck-$version.apk"
apk_hash=bbcdc476bc51e2e3701b161db4a3eca50aa58a1c57697117d13aa74f73e3573b
github=https://github.com/Droid-Deck/DroidDeck
mkdir -p "$cache" "$output"
work=$(mktemp -d "$output/.host-stage.XXXXXXXX")
trap 'rm -rf "$work"' EXIT

verify() {
    printf '%s  %s\n' "$1" "$2" | sha256sum -c --status
}

if [[ -n ${BURAN_DROIDDECK_APK:-} ]]; then
    apk=$BURAN_DROIDDECK_APK
    verify "$apk_hash" "$apk"
else
    apk="$cache/$apk_name"
    if [[ ! -f "$apk" ]] || ! verify "$apk_hash" "$apk"; then
        curl --retry 2 -fLSs "$github/releases/download/$version/$apk_name" -o "$work/$apk_name"
        verify "$apk_hash" "$work/$apk_name"
        mv -f "$work/$apk_name" "$apk"
    fi
fi

jni=jniLibs/arm64-v8a
assets=assets/gamescope-host
notices=assets/notices
mkdir -p "$work/$jni" "$work/$assets" "$work/$notices"
for library in droiddeckwayland wayland-server ffi c++_shared hook_impl main_hook; do
    unzip -p "$apk" "lib/arm64-v8a/lib$library.so" > "$work/$jni/lib$library.so"
done
python3 "$(dirname "$0")/name-host-libs.py" "$work/$jni"
unzip -p "$apk" assets/wayland/keymap.xkb > "$work/$assets/keymap.xkb"
unzip -p "$apk" assets/graphics_driver/adrenotools-turnip25.1.0.tzst |
    zstd -dc |
    tar -xOf - vulkan.ad07xx.so > "$work/$assets/vulkan.ad07xx.so"

license="$cache/DroidDeck-$source_commit-LICENSE.txt"
if [[ ! -f "$license" ]] || ! verify "$license_hash" "$license"; then
    curl --retry 2 -fLSs "https://raw.githubusercontent.com/Droid-Deck/DroidDeck/$source_commit/LICENSE" \
        -o "$work/DroidDeck-LICENSE.txt"
    verify "$license_hash" "$work/DroidDeck-LICENSE.txt"
    mv -f "$work/DroidDeck-LICENSE.txt" "$license"
fi
cp "$license" "$work/$notices/DroidDeck-LICENSE.txt"
cat > "$work/$notices/gamescope-host.txt" <<EOF_NOTICE
DroidDeck 0.7.11 Android Wayland compositor (GPL-3.0).
Release $github/releases/tag/$version
Corresponding source $github/tree/$source_commit
Includes the release's Wayland server, libffi, LLVM C++ runtime, AdrenoTools hooks,
keyboard map, and Vulkan Turnip driver for Adreno 7xx. Buran gives the C++ runtime
and the two hooks private library names, including references in the host loader.
Upstream notices $github/blob/$source_commit/README.md
EOF_NOTICE

mkdir -p "$output/$jni" "$output/$assets" "$output/$notices"
# Remove the three previous names from an incremental staging directory.
rm -f "$output/$jni/libc++_shared.so" "$output/$jni/libhook_impl.so" "$output/$jni/libmain_hook.so"
for dir in "$jni" "$assets" "$notices"; do
    mv -f "$work/$dir/"* "$output/$dir/"
done
