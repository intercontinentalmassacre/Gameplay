#!/usr/bin/env bash
set -euo pipefail

probe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:-"$probe_dir/../../src/main/assets"}
llvm_prefix=
if [[ -n ${LLVM_BIN:-} ]]; then
    llvm_prefix="$LLVM_BIN/"
fi

build_dir=$(mktemp -d)
trap 'rm -rf -- "$build_dir"' EXIT
mkdir -p -- "$output_dir"
for architecture in x64 x86; do
    if [[ $architecture == x64 ]]; then
        triple=x86_64-pc-windows-msvc
        dll_machine=i386:x86-64
        suffix=
        dll_flags=()
        sed 's/@.*//' "$probe_dir/desktop-exit-kernel32.def" > "$build_dir/kernel32.def"
    else
        triple=i686-pc-windows-msvc
        dll_machine=i386
        suffix=-x86
        dll_flags=(--kill-at)
        cp -- "$probe_dir/desktop-exit-kernel32.def" "$build_dir/kernel32.def"
    fi
    "${llvm_prefix}llvm-dlltool" -m "$dll_machine" "${dll_flags[@]}" -d "$build_dir/kernel32.def" -l "$build_dir/kernel32.lib"
    "${llvm_prefix}clang" --target="$triple" -O2 -ffreestanding -fno-stack-protector \
        -Wall -Wextra -Werror -c "$probe_dir/desktop-exit.c" -o "$build_dir/desktop-exit.obj"
    "${llvm_prefix}ld.lld" -flavor link "/machine:$architecture" /subsystem:windows /entry:mainCRTStartup /nodefaultlib \
        /timestamp:0 "/out:$output_dir/buran-desktop-exit$suffix.exe" "$build_dir/desktop-exit.obj" "$build_dir/kernel32.lib"
done
