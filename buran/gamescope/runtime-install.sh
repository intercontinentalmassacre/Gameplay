#!/usr/bin/env bash
set -euo pipefail

archive=${1:?Usage: runtime-install.sh ROOTFS_TAR_ZST [ADB_SERIAL]}
adb=${ADB:-adb}
args=()
[[ $# -lt 2 ]] || args=(-s "$2")
root=files/gamescope-runtime
version=bannerlator-linuxfs-r9+gamescope-3.16.29-p4

zstd -q -t "$archive"
# Android creates the files as the application UID, including the runtime marker.
zstd -dc "$archive" | "$adb" "${args[@]}" shell -T "run-as org.buran.runtime sh -c '
    set -eu
    mkdir -p $root
    tmp=\$(mktemp -d $root/incoming.XXXXXXXX)
    trap \"rm -r -- \\\"\$tmp\\\"\" EXIT
    tar -xf - -C \"\$tmp\"
    test \"\$(cat \"\$tmp/.buran-linuxfs\")\" = $version
    test -x \"\$tmp/usr/local/bin/gamescope\" && test -x \"\$tmp/usr/bin/Xwayland\"
    test ! -e $root/rootfs || { echo Gamescope-rootfs-already-installed >&2; exit 1; }
    mv \"\$tmp\" $root/rootfs
    trap - EXIT
'"
