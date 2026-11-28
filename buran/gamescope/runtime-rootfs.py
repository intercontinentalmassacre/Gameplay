#!/usr/bin/env python3
"""Copy the graphics runtime and its transitive glibc ELF dependencies."""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

source, destination = (Path(x).resolve() for x in sys.argv[1:])


def copy(path):
    src = source / path
    dst = destination / path
    if dst.exists() or dst.is_symlink():
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.is_symlink():
        target = src.readlink()
        guest_target = target.relative_to("/") if target.is_absolute() else path.parent / target
        guest_target = Path(os.path.normpath(guest_target))
        if guest_target.parts and guest_target.parts[0] == "..":
            raise ValueError(f"Guest link escapes the root filesystem: {path}")
        if not (source / guest_target).exists() and not (source / guest_target).is_symlink():
            if path.is_relative_to("usr/share/licenses"):
                return
            raise FileNotFoundError(guest_target)
        dst.symlink_to(os.path.relpath(guest_target, path.parent))
        copy(guest_target)
    elif src.is_dir():
        dst.mkdir(exist_ok=True)
    else:
        shutil.copy2(src, dst)


executables = (
    "usr/local/bin/gamescope",
    "usr/bin/gamescopereaper",
    "usr/bin/Xwayland",
    "usr/bin/xkbcomp",
    "usr/bin/bash",
    "usr/bin/env",
    "usr/bin/sleep",
    "usr/bin/pulseaudio",
)
# These loaders and drivers are opened at runtime rather than listed as DT_NEEDED.
plugins = (
    "usr/lib/ld-linux-aarch64.so.1",
    # Box64 imports the glibc compatibility DSOs even where Gamescope does not.
    "usr/lib/libdl.so.2",
    "usr/lib/libpthread.so.0",
    "usr/lib/librt.so.1",
    "usr/lib/libutil.so.1",
    # Wine opens font discovery and optional X11 extensions via dlopen.
    "usr/lib/libfontconfig.so.1",
    "usr/lib/libfreetype.so.6",
    "usr/lib/libXcomposite.so.1",
    "usr/lib/libXcursor.so.1",
    "usr/lib/libXext.so.6",
    "usr/lib/libXfixes.so.3",
    "usr/lib/libXi.so.6",
    "usr/lib/libXinerama.so.1",
    "usr/lib/libXrandr.so.2",
    "usr/lib/libXrender.so.1",
    "usr/lib/libXxf86vm.so.1",
    "usr/lib/libGL.so.1",
    "usr/lib/libSDL3.so.0",
    "usr/lib/libSDL2-2.0.so.0",
    "usr/lib/libvulkan.so.1",
    "usr/lib/libvulkan_freedreno.so",
    "usr/lib/libEGL.so.1",
    "usr/lib/libEGL_mesa.so.0",
    "usr/lib/libGLX_mesa.so.0",
    "usr/lib/libGLESv2.so.2",
    "usr/lib/libxcb-dri3.so.0",
    "usr/lib/libwayland-cursor.so.0",
    "usr/lib/dri/zink_dri.so",
    "usr/lib/dri/swrast_dri.so",
    "usr/lib/dri/kgsl_dri.so",
    "usr/lib/gbm/dri_gbm.so",
    "usr/local/lib/libblsession.so",
    "usr/lib/pulseaudio/modules/module-native-protocol-unix.so",
    "usr/lib/pulseaudio/modules/module-null-sink.so",
    "usr/lib/pulseaudio/modules/module-simple-protocol-unix.so",
)
pending = [Path(p) for p in (*executables, *plugins)]
visited = set()
while pending:
    item = pending.pop()
    copy(item)
    resolved = (source / item).resolve(strict=True)
    if resolved in visited:
        continue
    visited.add(resolved)
    dynamic = subprocess.check_output(["readelf", "-d", resolved], text=True)
    names = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic)
    for name in names:
        for directory in ("usr/lib", "usr/lib/pulseaudio", "usr/lib/pulseaudio/modules"):
            dependency = Path(directory) / name
            if (source / dependency).exists():
                pending.append(dependency)
                break
        else:
            raise FileNotFoundError(f"{item} requires {name}")

for folder in (
    "etc/fonts", "usr/share/fontconfig", "usr/share/fonts", "usr/share/X11", "usr/share/xkeyboard-config-2",
    "usr/share/gamescope", "usr/share/glvnd", "usr/share/licenses",
):
    for path in (source / folder).rglob("*"):
        if path.is_file() or path.is_symlink():
            copy(path.relative_to(source))
for path in (
    "etc/passwd", "etc/group", "etc/nsswitch.conf", "usr/share/hwdata/pnp.ids",
    "usr/share/vulkan/icd.d/freedreno_icd.json", "opt/android-host/README.md",
):
    copy(Path(path))

for directory in (
    "dev", "proc", "sys", "tmp", "root", "usr/local/bin", "home/buran", "opt/buran/wine64",
    "usr/lib/x86_64-linux-gnu", "opt/buran/lib/x86_64-linux-gnu",
):
    (destination / directory).mkdir(parents=True, exist_ok=True)
(destination / "bin").symlink_to("usr/bin")
(destination / "lib").symlink_to("usr/lib")
(destination / "usr/bin/sh").symlink_to("bash")
(destination / "usr/local/bin/sleep").symlink_to("../../bin/sleep")
(destination / ".buran-linuxfs").write_text("bannerlator-linuxfs-r9+gamescope-3.16.29-p4\n")
