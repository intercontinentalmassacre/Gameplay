#!/usr/bin/env sh
# Rebuilds the JNI fork/exec bridge used by FexCacheToolRunner.
# Requires ANDROID_NDK (r29 tested) and an arm64-v8a APK target.
set -eu
: "${ANDROID_NDK:?set ANDROID_NDK to the NDK root}"
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT="$HERE/../../jniLibs/arm64-v8a/libfexlauncher.so"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" \
  --target=aarch64-linux-android29 -shared -fPIC -O2 \
  -o "$OUT" "$HERE/fexlauncher.c" -llog -Wl,-z,max-page-size=16384
echo "wrote $OUT"
