"""Compose Gameplay's optional Android ARM64 runtime from upstream build outputs.

Build both Mesa stages from GameNative/gamescope@3738bbe, Xwayland from
GameNative/xserver@d67e6b9, and the ICD from GameNative/gamenative-drm@8946b30.
Run on Linux with GNU tar, zstd, readelf and the Termux cross-build prefix.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def elf_output(flag, path):
    return subprocess.check_output(["readelf", flag, path], text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("stable", "glamor", "guest-gl", "mesa-source", "xwayland", "xserver-source",
                 "icd", "prefix", "host", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    if sha(args.stable) != "5e2bf27ce8dda593297872fe5e4975112cc8d8d2b7d06fbb1149450d72ce08d9":
        parser.error("expected the 20261116.1 base archive")
    if sha(args.host) != "6441f18a5e28ffccf5938e31b74d233409450d6c5f8d0a5d8c443fb025a9d7eb":
        parser.error("expected the matching 20261116.1 Android DRM host")
    if re.search(r"\((?:RPATH|RUNPATH)\)", elf_output("-d", args.xwayland)):
        parser.error("use the installed Xwayland binary with its build-time search path removed")
    args.output.mkdir(parents=True, exist_ok=True)
    version = "20261121.1"
    archive_name = f"gamescope-gamenative-{version}.tzst"
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp) / "stage"
        root.mkdir()
        subprocess.run(["tar", "--zstd", "-xf", args.stable, "-C", root], check=True)
        notices = root / "share/licenses"
        notices.mkdir(parents=True, exist_ok=True)
        shutil.copytree(args.mesa_source / "licenses", notices / "mesa", dirs_exist_ok=True)
        shutil.copyfile(args.xserver_source / "COPYING", notices / "Xwayland-COPYING")
        lib = root / "lib"
        gl = lib / "gl"
        gl.mkdir()
        shutil.copyfile(args.xwayland, root / "bin/Xwayland")
        shutil.copyfile(args.icd, lib / "libadrenotools_gamescope.so")
        for name in ("libEGL_mesa.so.0", "libgbm.so", "libgbm.so.1", "libgallium-26.2.6.so",
                     "dri/libdril_dri.so", "gbm/dri_gbm.so"):
            shutil.copyfile(args.glamor / "lib" / name, lib / name)
        for name in ("libGL.so.1.2.0", "libEGL.so.1.0.0", "libGLESv2.so.2.0.0", "libgbm.so.1.0.0",
                     "libgallium-26.2.6.so", "dri/libdril_dri.so", "gbm/dri_gbm.so"):
            target = gl / name
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(args.guest_gl / "lib" / name, target)
        for stem, soname, actual in (("libGL", "1", "1.2.0"), ("libEGL", "1", "1.0.0"),
                                     ("libGLESv2", "2", "2.0.0"), ("libgbm", "1", "1.0.0")):
            (gl / f"{stem}.so").symlink_to(f"{stem}.so.{soname}")
            (gl / f"{stem}.so.{soname}").symlink_to(f"{stem}.so.{actual}")
        (gl / "dri/zink_dri.so").symlink_to("libdril_dri.so")

        # The upstream Termux toolchain uses unversioned X11 SONAMEs. Copy only
        # dependencies missing from the shipped release and audit those too.
        system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libsync.so",
                  "libnativewindow.so", "libvulkan.so", "libstdc++.so", "ld-android.so"}
        pending = [p for p in root.rglob("*") if p.is_file() and not p.is_symlink()]
        while pending:
            path = pending.pop()
            with path.open("rb") as stream:
                if stream.read(4) != b"\x7fELF":
                    continue
            if "AArch64" not in elf_output("-h", path):
                raise ValueError(f"unexpected architecture in {path.name}")
            alignments = [int(s.split()[-1], 16) for s in elf_output("-lW", path).splitlines()
                          if s.lstrip().startswith("LOAD ")]
            if not alignments or min(alignments) < 16384:
                raise ValueError(f"invalid LOAD alignment in {path.name}")
            for name in re.findall(r"\(NEEDED\).*?\[(.*?)\]", elf_output("-d", path)):
                if name == "libandroid.so":
                    raise ValueError(f"unexpected dependency on libandroid.so in {path.name}")
                if name in system or (lib / name).is_file() or (gl / name).is_file():
                    continue
                shutil.copyfile(args.prefix / "lib" / name, lib / name)
                pending.append(lib / name)
        with tempfile.TemporaryDirectory(dir=args.output) as output:
            archive = Path(output) / archive_name
            subprocess.run(["tar", "--sort=name", "--mtime=@0", "--owner=0", "--group=0",
                            "--numeric-owner", "-I", "zstd -10", "-cf", archive, "-C", root,
                            "bin", "lib", "share", "tmp"], check=True)
            props = Path(output) / "bundle.properties"
            props.write_text(f"version={version}\narchiveSha256={sha(archive)}\nhostSha256={sha(args.host)}\n")
            os.replace(archive, args.output / archive.name)
            os.replace(props, args.output / props.name)


if __name__ == "__main__":
    main()
