#!/usr/bin/env bash
set -euo pipefail

root=${1:?Usage: runtime-mangohud.sh ROOT MANGOHUD_DEB SPDLOG_DEB FMT_DEB}
mango=${2:?}
spdlog=${3:?}
fmt=${4:?}
work=$(mktemp -d)
trap 'rm -r -- "$work"' EXIT

ar p "$mango" data.tar.xz | tar -xJ -C "$root" \
    ./usr/lib/aarch64-linux-gnu/mangohud/libMangoHud.so \
    ./usr/share/vulkan/implicit_layer.d/MangoHud.aarch64.json \
    ./usr/share/doc/mangohud/copyright
ar p "$spdlog" data.tar.xz | tar -xJ -C "$work" \
    ./usr/lib/aarch64-linux-gnu/libspdlog.so.1.17.0 ./usr/share/doc/libspdlog1.17/copyright
ar p "$fmt" data.tar.xz | tar -xJ -C "$work" \
    ./usr/lib/aarch64-linux-gnu/libfmt.so.12.2.1 ./usr/share/doc/libfmt12/copyright
install -m 644 "$work/usr/lib/aarch64-linux-gnu/libspdlog.so.1.17.0" "$root/usr/lib/libspdlog.so.1.17"
install -m 644 "$work/usr/lib/aarch64-linux-gnu/libfmt.so.12.2.1" "$root/usr/lib/libfmt.so.12"
cp -r "$work/usr/share/doc/." "$root/usr/share/doc/"
