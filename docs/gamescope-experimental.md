# Experimental Gamescope

The per-container setting defaults to off. A build without the experimental GL bundle can use the experimental frame-generation pacing with the stable Gamescope 20261116.1 runtime. The host daemon and the stable runtime directory are shared with regular Gamescope. The opt-in selects a separate ICD manifest and library in the app's private files directory.

## Cap-only ICD

The bundled `libadrenotools_gamescope-pr71.so` is built from GameNative DRM [revision 8946b3080b8dcbf7b4eb72d3538596b0ae0187fb](https://github.com/GameNative/gamenative-drm/tree/8946b3080b8dcbf7b4eb72d3538596b0ae0187fb), [DRM pull request 71](https://github.com/GameNative/gamenative-drm/pull/71). The source is GPL-3.0-or-later. Compared with the stable ICD's source revision `c77095f22ecba46202739127f9dd7df2dde5ac26`, it changes the ICD's pacing logic and documentation. The daemon, wire types, and Cargo lockfile are identical.

- Target `aarch64-linux-android`, API 28, 16 KiB ELF page alignment.
- Rust 1.94.1 and Android NDK 28.2.13676358, with dependencies resolved from the source lockfile.
- Link libraries come from the verified stable Gamescope `20261116.1` archive, SHA-256 `5e2bf27ce8dda593297872fe5e4975112cc8d8d2b7d06fbb1149450d72ce08d9`.
- Output SHA-256 `2b067c0c93272ce9901f3e2eff34fc127ffbc41e64f24e7e3eefc23efe4ae0d1` (933,952 bytes).
- Required APK host daemon SHA-256 `6441f18a5e28ffccf5938e31b74d233409450d6c5f8d0a5d8c443fb025a9d7eb`.

To build the ICD, check out the pinned DRM revision. Point `GAMESCOPE_TERMUX_PREFIX` at a staging directory with the stable payload's `lib/` and a `libwayland-client.so` alias for its versioned library. Set `ANDROID_NDK_HOME` to the NDK path and `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` to its `aarch64-linux-android28-clang` launcher. On Windows, the launcher ends in `.cmd`. Set `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS` to `-C link-args=-Wl,-z,max-page-size=16384` and remap source paths as in upstream `tools/build-android.sh`. Install Rust's Android target and build the single library with the locked dependency set.

```sh
rustup target add aarch64-linux-android --toolchain 1.94.1
cargo +1.94.1 build --locked --release --target aarch64-linux-android -p adrenotools_gamescope --lib
```

The stable and experimental ICDs export the same three Vulkan loader functions and have the same dynamic dependency list. The experimental build adds a `nanosleep` import supplied by Android's libc. Verify the package checksum before replacing an APK asset.

## OpenGL runtime on Adreno

Gameplay assembles its own experimental archive from the verified stable runtime and three upstream builds. Mesa 26.2.6 (source tarball SHA-256 `b9436b3e1a8be76fbd4823dc884f90596ab7c6ac5e1867a305e66ffccfb31a92`) supplies zink, GLX, EGL, GBM and GLES. The guest libraries live in `lib/gl` so that enabling the option selects the matching DRI modules. Xwayland supplies GLX and DRI3. This runtime uses its own private directory and the stable host daemon.

1. Build the glamor stage and the guest GL stage with `android/mesa-glamor/build-mesa-glamor.sh` and `android/mesa-gl/build-mesa-gl.sh` from [GameNative/gamescope at 3738bbe](https://github.com/GameNative/gamescope/tree/3738bbe92a77d3f29fa60f1a1b8ab506c4e0c9ee). Use the CI Termux prefix, Android NDK r27d and API 28. The GLX headers from the pinned Mesa source are needed in the target prefix.
2. Build the Android Xwayland binary with GLX enabled from [GameNative/xserver at d67e6b9](https://github.com/GameNative/xserver/tree/d67e6b93d108e6ac47ee34a50aa8ad95becab9ef). Use the upstream runtime prefix for the app's install-time path relocation.
3. Pass both Mesa installation directories, the Mesa and Xserver source directories, the installed Xwayland binary, the ICD built above, the stable archive, the Android DRM host and the Termux prefix to `scripts/package-gamescope-experiment.py`. Use `--output app/src/main/assets/gamescope-experimental`. The packager checks the base and host digests, ELF architectures, load-segment alignment and shared-library dependencies, then writes the archive and manifest. The Mesa and Xserver license notices are included in the archive.
