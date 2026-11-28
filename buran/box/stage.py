#!/usr/bin/env python3
"""Build the Box64/Box86 Linux userspace from pinned Debian packages."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request

output = Path(sys.argv[1]).resolve()
directory = Path(__file__).resolve().parent
cache = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "buran-box"
cache.mkdir(parents=True, exist_ok=True)
packages = [line.split() for line in (directory / "packages.txt").read_text().splitlines()]

def download(row):
    name, expected, url = row
    path = cache / (expected + ".deb")
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
        return name, path
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"Package hash mismatch: {name}")
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as staged:
        staged.write(data)
    os.replace(staged.name, path)
    return name, path

def check_elf(path, elf_class, machine):
    header = path.read_bytes()[:20]
    assert header[:6] == b"\x7fELF" + bytes((elf_class, 1)), path
    assert struct.unpack_from("<H", header, 18)[0] == machine, path

with tempfile.TemporaryDirectory(prefix="stage-", dir=cache) as work:
    work = Path(work)
    root = work / "root"
    root.mkdir()
    unpacked = set()
    with ThreadPoolExecutor(max_workers=12) as pool:
        for name, package in pool.map(download, packages):
            if package in unpacked:
                continue
            unpacked.add(package)
            target = work if name in ("asset/proot-loader32", "asset/glibc-locale") else root
            subprocess.run(["dpkg-deb", "-x", package, target], check=True)
    locale = root / "usr/lib/locale"
    locale.mkdir(parents=True, exist_ok=True)
    shutil.copytree(work / "usr/lib/locale/C.utf8", locale / "C.utf8")
    # Only the translator executables are needed from the Pi-Apps packages.
    for binary, elf_class, machine in (("box64", 2, 183), ("box86", 1, 40)):
        check_elf(root / "usr/local/bin" / binary, elf_class, machine)
    shutil.copy2(directory.parent / "src/main/assets/buran-box-session.sh", root / "usr/local/bin/buran-box-session")
    for extra in ("box64-x86_64-linux-gnu", "box86-i386-linux-gnu"):
        shutil.rmtree(root / "usr/lib" / extra)
    shutil.rmtree(root / "etc/binfmt.d")
    for subtree in ("usr/share/man", "usr/share/lintian", "usr/share/bug"):
        if (root / subtree).is_dir():
            shutil.rmtree(root / subtree)
    for folder in ("tmp", "proc", "dev", "home/buran", "opt/buran", "etc/fonts"):
        (root / folder).mkdir(parents=True, exist_ok=True)
    (root / "etc/passwd").write_text("buran:x:1000:1000:Buran:/home/buran:/bin/sh\n")
    (root / "etc/group").write_text("buran:x:1000:\n")
    (root / "etc/fonts/fonts.conf").write_text("""<?xml version="1.0"?>
<fontconfig>
  <dir>/usr/share/fonts</dir>
  <cachedir>/tmp/font-cache</cachedir>
</fontconfig>
""")
    busybox = root / "bin/busybox"
    check_elf(busybox, 2, 183)
    (root / "bin/sh").symlink_to("busybox")
    for path in ("lib/ld-linux-aarch64.so.1", "lib/ld-linux-armhf.so.3"):
        assert (root / path).is_symlink() or (root / path).is_file(), path
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            target = path.readlink()
            if target.is_absolute():
                path.unlink()
                path.symlink_to(os.path.relpath(root / target.relative_to("/"), path.parent))
    output.mkdir(parents=True, exist_ok=True)
    native = output / "jniLibs/arm64-v8a"
    native.mkdir(parents=True, exist_ok=True)
    arm_loader = work / "data/data/com.termux/files/usr/libexec/proot/loader32"
    check_elf(arm_loader, 1, 40)
    shutil.copy2(arm_loader, native / "libburan_box_loader32.so")
    assets = output / "assets/box"
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(directory / "packages.txt", assets / "sources.txt")
    notices = output / "assets/notices"
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copy2(directory / "PROot-COPYING.txt", notices / "box-proot-loader32-COPYING.txt")
    archive = subprocess.Popen([
        "tar", "-C", root, "--sort=name", "--mtime=@0", "--owner=0", "--group=0", "-cf", "-", "."
    ], stdout=subprocess.PIPE)
    with archive.stdout:
        subprocess.run(["zstd", "-q", "-T2", "-f", "-o", str(work / "rootfs.tar.zst")],
                       stdin=archive.stdout, check=True)
    if archive.wait() != 0:
        raise RuntimeError("Could not archive the Box userspace")
    shutil.copy2(work / "rootfs.tar.zst", assets / "rootfs.tar.zst")
