#!/usr/bin/env bash
set -euo pipefail

mode=${1:?Usage: runtime-stage.sh native|rootfs OUTPUT}
output=${2:?Usage: runtime-stage.sh native|rootfs OUTPUT}
cache=${BURAN_GAMESCOPE_CACHE:-"${XDG_CACHE_HOME:-$HOME/.cache}/buran-gamescope-runtime"}
script=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$cache"
work=$(mktemp -d "$cache/.stage.XXXXXXXX")
trap 'rm -r -- "$work"' EXIT

check() {
    printf '%s  %s\n' "$1" "$2" | sha256sum -c --status
}

fetch() {
    local file=$1 url=$2 digest=$3
    if [[ ! -f "$cache/$file" ]] || ! check "$digest" "$cache/$file"; then
        curl -fLSs --retry 2 "$url" -o "$work/$file"
        check "$digest" "$work/$file"
        mv "$work/$file" "$cache/$file"
    fi
}

case "$mode" in
    native)
        source=https://raw.githubusercontent.com/The412Banner/Bannerlator/8e18588f5c9c950be7a09415091deab2fac6f8cd/tools/linuxfs/prebuilt/proot
        fetch proot "$source/proot" 125dff2415ae1dcb8b1ae97c51357de73ef11f28268b86cd50a0f13aa1c3ea91
        fetch loader "$source/loader" 44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04
        fetch libtalloc.so.2 "$source/libtalloc.so.2" 3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb
        mkdir -p "$output/arm64-v8a"
        cp "$cache/proot" "$output/arm64-v8a/libburan_gamescope_proot.so"
        cp "$cache/loader" "$output/arm64-v8a/libburan_gamescope_loader.so"
        cp "$cache/libtalloc.so.2" "$output/arm64-v8a/libburan_gamescope_talloc.so"
        ;;
    rootfs)
        release=https://github.com/The412Banner/winlator-contents/releases/download
        fetch linuxfs-r9.tar.zst "$release/linuxfs-r9/linuxfs.tar.zst" d3240c1531531615b0dd06b6ad56e92db9015a8641eb18c3a613835ae45992e7
        fetch gamescope-3.16.29-p4.tzst "$release/gamescope-3.16.29-p4/gamescope.tzst" e750fb34864bc2eb1160aeed29593e402bf9c8153fafd9fd18c77450bdd50574
        mkdir "$work/input" "$work/rootfs"
        tar --zstd -xf "$cache/linuxfs-r9.tar.zst" -C "$work/input" --wildcards \
            './usr/bin/gamescopereaper' './usr/bin/Xwayland' './usr/bin/xkbcomp' \
            './usr/bin/env' './usr/bin/sleep' './usr/bin/bash' \
            './usr/lib/*.so*' './usr/local/lib/libblsession.so' \
            './etc/passwd' './etc/group' './etc/nsswitch.conf' './etc/fonts/*' \
            './opt/android-host/README.md' './usr/share/gamescope/*' './usr/share/X11/*' \
            './usr/share/xkeyboard-config-2/*' './usr/share/hwdata/pnp.ids' \
            './usr/share/vulkan/icd.d/freedreno_icd.json' './usr/share/glvnd/*' \
            './usr/share/licenses/*' './usr/share/fonts/*' './usr/share/fontconfig/*' \
            './usr/share/doc/systemd/LICENSES/*' './usr/share/doc/xz/COPYING*'
        tar --zstd -xf "$cache/gamescope-3.16.29-p4.tzst" -C "$work/input" usr/local/bin/gamescope
        # The LinuxFS supplies glibc but has no PulseAudio server. Keep the daemon
        # and its private libpulsecommon ABI on the same package revision.
        mirror=https://ca.us.mirror.archlinuxarm.org/aarch64
        pulse=17.0+r134+g77d25a1e6-2
        fetch "pulseaudio-$pulse.tar.xz" "$mirror/extra/pulseaudio-$pulse-aarch64.pkg.tar.xz" 1c72d7141e94147726af564d45ede3948c11af8647304b49d0f22342bb325e73
        fetch "libpulse-$pulse.tar.xz" "$mirror/extra/libpulse-$pulse-aarch64.pkg.tar.xz" 37012521a6d7d1707b5559e27fd1434b0cba5a45157e8d19145739440c210fcf
        fetch libtool-2.6.0+r23+gb08cb0a0-1.tar.xz "$mirror/core/libtool-2.6.0+r23+gb08cb0a0-1-aarch64.pkg.tar.xz" 064e9d5e1dcd6840943e87964ea233694e9911d281e1747b6c7f885199dcaa86
        fetch tdb-1.4.15-2.tar.xz "$mirror/extra/tdb-1.4.15-2-aarch64.pkg.tar.xz" 950900d8b4462bf0faf0e701f0c35a95cd23f49f8221244381aee457a05f0dd3
        fetch libsoxr-0.1.3-5.tar.xz "$mirror/extra/libsoxr-0.1.3-5-aarch64.pkg.tar.xz" c61e55285c17399b1fa70a35524b80049bc34bbc21c6f3e96537e01e7635e8e7
        fetch speexdsp-1.2.1-3.tar.xz "$mirror/extra/speexdsp-1.2.1-3-aarch64.pkg.tar.xz" 474d8d6785c920004585c9d37d06264efcdce3d87b48b952e9c20f9f4d180c03
        tar -xf "$cache/pulseaudio-$pulse.tar.xz" -C "$work/input" \
            usr/bin/pulseaudio usr/lib/pulseaudio/libpulsecore-17.0.so \
            usr/lib/pulseaudio/modules/{module-native-protocol-unix,module-null-sink,module-simple-protocol-unix,libprotocol-native,libprotocol-simple}.so
        tar -xf "$cache/libpulse-$pulse.tar.xz" -C "$work/input" \
            usr/lib/libpulse.so.0 usr/lib/libpulse.so.0.24.3 usr/lib/pulseaudio/libpulsecommon-17.0.so
        tar -xf "$cache/libtool-2.6.0+r23+gb08cb0a0-1.tar.xz" -C "$work/input" --wildcards 'usr/lib/libltdl.so.7*'
        tar -xf "$cache/tdb-1.4.15-2.tar.xz" -C "$work/input" --wildcards 'usr/lib/libtdb.so.1*'
        tar -xf "$cache/libsoxr-0.1.3-5.tar.xz" -C "$work/input" --wildcards 'usr/lib/libsoxr.so.0*' 'usr/share/licenses/libsoxr/*'
        tar -xf "$cache/speexdsp-1.2.1-3.tar.xz" -C "$work/input" --wildcards 'usr/lib/libspeexdsp.so.1*' 'usr/share/licenses/speexdsp/*'
        ndk=${3:?Usage: runtime-stage.sh rootfs OUTPUT NDK}
        tar --zstd -xf "$cache/linuxfs-r9.tar.zst" -C "$work/input" ./usr/include
        python3 "$script/runtime-rootfs.py" "$work/input" "$work/rootfs"
        debian=https://deb.debian.org/debian/pool/main
        mango=mangohud_0.8.4+dfsg-1+b2_arm64.deb
        spdlog=libspdlog1.17_1.17.0+ds-1_arm64.deb
        fmt=libfmt12_12.2.1+ds1-1_arm64.deb
        fetch "$mango" "$debian/m/mangohud/$mango" 38e217928ed49e241c50f119e1229179f5a83a7197eb941cf1d9102131bf6bef
        fetch "$spdlog" "$debian/s/spdlog/$spdlog" 844f8bb5edc229d88dda96ec53ce1ab02f3616a9f8e7fa2550dc6a29d9bf02d3
        fetch "$fmt" "$debian/f/fmtlib/$fmt" c8d29a0e0667816fbcef02b9e0966a64884f955cf905e45f1d9651001685a4e5
        bash "$script/runtime-mangohud.sh" "$work/rootfs" "$cache/$mango" "$cache/$spdlog" "$cache/$fmt"
        compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
        "$compiler" --target=aarch64-linux-gnu --sysroot="$work/input" -fuse-ld=lld \
            -std=c11 -O2 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared -nostdlib \
            -Wl,-z,defs,-z,max-page-size=16384 \
            -o "$work/rootfs/usr/local/lib/libburan-gamepad.so" "$script/runtime-gamepad.c" \
            "$work/input/usr/lib/libc.so.6"
        mkdir -p "$(dirname "$output")"
        archive=$(mktemp "$(dirname "$output")/.gamescope.XXXXXXXX")
        trap 'rm -r -- "$work"; test ! -e "$archive" || unlink "$archive"; test ! -e "$archive.sha256" || unlink "$archive.sha256"' EXIT
        tar -C "$work/rootfs" --sort=name --mtime='@0' --owner=0 --group=0 -cf - . | zstd -q -T0 -f -o "$archive"
        sha256sum "$archive" | cut -d ' ' -f 1 > "$archive.sha256"
        mv "$archive" "$output"
        mv "$archive.sha256" "$output.sha256"
        ;;
    *) echo 'Expected native or rootfs' >&2; exit 2 ;;
esac
