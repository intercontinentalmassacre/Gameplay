#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
sudo apt-get update -qq
sudo apt-get install -y -qq --no-install-recommends binutils curl dpkg python3 unzip xz-utils zstd gcc-aarch64-linux-gnu libc6-dev-arm64-cross
# Resolve the pinned Rust toolchain and Android target before invoking Gradle.
(cd buran && rustup show)
