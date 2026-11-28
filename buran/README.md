# Embedded Buran runtime

Gameplay includes Buran in the modern ARM64 variants. The module contains its own sources and build inputs. The legacy variants build without it.

## Clean Linux build

Use Linux x86-64, JDK 17, Android SDK 36, NDK 27.3.13750724 and Rustup. The toolchain file selects Rust 1.89.0 and the Android ARM64 target. Install the archive tools on Debian or Ubuntu before the first build.

```sh
sudo apt-get install binutils curl dpkg python3 unzip xz-utils zstd
sdkmanager --install 'platforms;android-36' 'ndk;27.3.13750724'
./gradlew :app:assembleModernDebug
```

The signed modern release uses the same runtime preparation. Its signing setup is described in the [main build documentation](../README.md#updates-and-releases).

The Gradle task `:buran:prepareRuntime` builds the native runner and JNI libraries and prepares the pinned guest assets in `buran/build/runtime`. Source download caches live under `${XDG_CACHE_HOME:-$HOME/.cache}` by default. The first build needs network access for the Android toolchain, Rust dependencies and pinned runtime packages.

## Private release inputs

To include Gameplay's private Rust ports in a release, supply the workspace locally in `dist/nonfree/rea/rust` or set `GAMEPLAY_NATIVE_PORTS_SOURCE` to its absolute path. Install `gcc-aarch64-linux-gnu` and `libc6-dev-arm64-cross` for this build. The build adds the Rust ARM64 glibc target, compiles the Steam bootstrap and KGSL shim for Android, and packages the glibc KGSL and redirect shims as guest assets. Each variant uses the corresponding Gameplay application ID. The guest build checks compatibility with Buran's glibc 2.36. Public checkouts without the private workspace can build the normal Buran runtime.

## Using an existing runtime

For Windows or macOS, or for a local build without the source download caches, prepare the runtime on a Linux x86-64 host from the same checkout and copy the result to `buran/.runtime`. Use the entire directory, including `assets`, `jniLibs` and `gameplay-ports` if the private ports were built.

```sh
./gradlew :buran:prepareRuntime
```

If `buran/.runtime` exists, Gradle uses it and validates the required nonempty files in [runtime-files.txt](runtime-files.txt), the Gamescope rootfs SHA-256 and the checked-in desktop-exit helper executables. Use a current cache from the same revision after changes to native code or runtime packages.

The Windows helpers come from Buran's desktop-exit probe and are distributed with their source. To regenerate them, set `LLVM_BIN` to the NDK LLVM bin directory and run `bash buran/native/windows/build-desktop-exit.sh` from the project root. See [NOTICE](NOTICE) for runtime provenance.

## Private Android Steam test payload

Set `GAMEPLAY_ANDROID_STEAM_RUNTIME` to the absolute path of a locally supplied `steam-androidarm64-20260709.tzst` archive and place `lsteamclient-arm64ec-proton10.tzst` next to it. The build verifies both SHA-256 checksums and includes five Android client libraries and three Proton 10 ARM64EC bridge binaries in the debug assets. Keep both archives outside Git. A `tar` with Zstandard decompression support is required. This payload is specific to Android Bionic and its matching Proton 10 guest.
