#!/usr/bin/env bash
set -euo pipefail
output=${1:?Usage: stage.sh OUTPUT PORTS_WORKSPACE}
root=${2:?Usage: stage.sh OUTPUT PORTS_WORKSPACE}
target_dir=${CARGO_TARGET_DIR:-build/gameplay-ports}
if [[ ! -f $root/Cargo.toml || ! -f $root/Cargo.lock ]]; then
    printf 'Missing private Gameplay ports workspace at %s\n' "$root" >&2
    exit 1
fi
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=${CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER:-aarch64-linux-gnu-gcc}
if ! command -v "$CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER" >/dev/null; then
    printf 'Gameplay ports require the AArch64 glibc cross compiler.\n' >&2
    exit 1
fi
rustup target add aarch64-unknown-linux-gnu

for variant in debug release release-signed release-gold; do
    case "$variant" in
        debug) suffix=.debug ;;
        release) suffix=.dev ;;
        release-signed) suffix= ;;
        release-gold) suffix=.gold ;;
    esac
    export PORT_HOST_ID="app.gameplay$suffix"
    cargo build --locked --release --manifest-path "$root/Cargo.toml" \
        --target-dir "$target_dir" --target aarch64-linux-android -p kgslshim -p steambootstrap -p redirect_bionic
    cargo build --locked --release --manifest-path "$root/Cargo.toml" \
        --target-dir "$target_dir" --target aarch64-unknown-linux-gnu -p kgslshim -p redirect
    android="$target_dir/aarch64-linux-android/release"
    glibc="$target_dir/aarch64-unknown-linux-gnu/release"

    # Box's Debian 12 userspace provides glibc 2.36.
    for lib in libkgslshim.so libredirect.so; do
        version=$(readelf --version-info "$glibc/$lib" |
            awk '/Name: GLIBC_[0-9]/{for (i=1; i<=NF; i++) if ($i ~ /^GLIBC_[0-9]/) print $i}' |
            sort -V | tail -1)
        if [[ -z $version || $(printf '%s\n' GLIBC_2.36 "$version" | sort -V | tail -1) != GLIBC_2.36 ]]; then
            printf 'Buran %s requires %s. Build against glibc 2.36 or older.\n' "$lib" "$version" >&2
            exit 1
        fi
    done

    ports="$output/gameplay-ports/$variant"
    install -Dm644 "$android/libkgslshim.so" "$ports/jniLibs/arm64-v8a/libburan_kgslshim.so"
    install -Dm644 "$android/libredirect_bionic.so" "$ports/jniLibs/arm64-v8a/libburan_redirect_bionic.so"
    install -Dm644 "$android/libsteambootstrap" "$ports/jniLibs/arm64-v8a/libburan_steambootstrap.so"
    install -Dm644 "$glibc/libkgslshim.so" "$ports/assets/gameplay-ports/libkgslshim.so"
    install -Dm644 "$glibc/libredirect.so" "$ports/assets/gameplay-ports/libredirect.so"
done
