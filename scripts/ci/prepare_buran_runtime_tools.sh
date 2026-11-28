#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
sudo apt-get update -qq
sudo apt-get install -y -qq --no-install-recommends binutils curl dpkg python3 unzip xz-utils zstd
if [[ -v GAMEPLAY_NATIVE_PORTS_SOURCE || -f dist/nonfree/rea/rust/Cargo.toml ]]; then
    sudo apt-get install -y -qq --no-install-recommends gcc-aarch64-linux-gnu libc6-dev-arm64-cross
fi
# Resolve the pinned Rust toolchain and Android target before invoking Gradle.
(cd buran && rustup show)
