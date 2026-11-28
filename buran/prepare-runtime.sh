#!/usr/bin/env bash
set -euo pipefail
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
    echo 'To prepare Buran, use Linux x86-64 or supply a complete buran/.runtime.' >&2
    exit 1
fi
output=$(realpath -m -- "${1:?Usage: prepare-runtime.sh OUTPUT NDK}")
ndk=$(realpath -m -- "${2:?Usage: prepare-runtime.sh OUTPUT NDK}")
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
for tool in ar cargo curl dpkg-deb python3 readelf rustup sha256sum tar unzip xz zstd; do
    if ! command -v "$tool" >/dev/null; then
        printf 'Buran runtime preparation requires %s. See buran/README.md.\n' "$tool" >&2
        exit 1
    fi
done
abi="$output/jniLibs/arm64-v8a"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
clang="$bin/aarch64-linux-android29-clang"
if [[ ! -x $clang ]]; then
    printf 'Android NDK clang is missing: %s\n' "$clang" >&2
    exit 1
fi
mkdir -p "$abi" "$output/assets/gamescope-runtime" "$output/assets/notices"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$clang"
export CC_aarch64_linux_android="$clang"
export AR_aarch64_linux_android="$bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
cargo build --locked --release --manifest-path native/Cargo.toml \
    --target-dir build/cargo --target aarch64-linux-android
ports_source=${GAMEPLAY_NATIVE_PORTS_SOURCE-../dist/nonfree/rea/rust}
if [[ -v GAMEPLAY_NATIVE_PORTS_SOURCE || -f $ports_source/Cargo.toml ]]; then
    bash native/gameplay-ports/stage.sh "$output" "$ports_source"
fi
cp build/cargo/aarch64-linux-android/release/buran "$abi/libburan.so"
for component in gamepad guest_pause; do
    "$clang" -shared -fPIC -O3 -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 \
        "native/src/android_$component.c" -ldl -o "$abi/libburan_$component.so"
done
"$clang" -shared -fPIC -O3 -Wall -Wextra -Werror -Wl,-z,max-page-size=16384,--gc-sections,--exclude-libs,ALL \
    native/src/android_present.c build/cargo/aarch64-linux-android/release/libburan.a -ldl -lm \
    -o "$abi/libburan_present.so"
"$clang" -shared -fPIC -O2 -Wl,-z,max-page-size=16384,-soname,libvulkan.so.1 \
    native/src/turnip-bridge.c -ldl -llog -o "$abi/libburan_vulkan_bridge.so"
python3 box/stage.py "$output"
bash box/wine-stage.sh "$output"
bash dxvk/stage.sh "$output"
bash gamescope/host-stage.sh "$output"
bash gamescope/runtime-stage.sh native "$output/jniLibs"
bash gamescope/runtime-stage.sh rootfs "$output/assets/gamescope-runtime/rootfs.tar.zst" "$ndk"
"$bin/clang" --target=aarch64-linux-gnu -fuse-ld=lld -nostdlib -shared -fPIC -O2 +    -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 gamescope/x11-filesystem.c +    -o "$output/assets/gamescope-runtime/x11-filesystem.so"
cp gamescope/runtime-notices.txt "$output/assets/notices/gamescope-guest.txt"
while IFS= read -r artifact; do
    if [[ ! -s "$output/$artifact" ]]; then
        printf 'Missing Buran runtime artifact: %s\n' "$artifact" >&2
        exit 1
    fi
done < runtime-files.txt
