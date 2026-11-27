#!/usr/bin/env python3
"""Export the three requested GameNative Android launch shims."""

from argparse import ArgumentParser
from hashlib import sha256
from pathlib import Path
from subprocess import check_output
from zipfile import ZipFile


NATIVE_LIBRARIES = ("libkgslshim.so", "libsteambootstrap.so")


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apk", type=Path, action="append", default=[], help="compare against a built APK",
    )
    args = parser.parse_args()

    repo = Path(__file__).resolve().parents[1]
    source = repo / "app" / "src" / "main"
    archive = source / "assets" / "redirect.tzst"
    blobs = {
        "libredirect.so": check_output(["tar", "-xOf", str(archive), "./usr/lib/libredirect.so"]),
        **{name: (source / "jniLibs" / "arm64-v8a" / name).read_bytes() for name in NATIVE_LIBRARIES},
    }

    for apk in args.apk:
        with ZipFile(apk) as package:
            if package.read("assets/redirect.tzst") != archive.read_bytes():
                raise SystemExit(f"The redirect archive differs in {apk}")
            for name in NATIVE_LIBRARIES:
                if package.read(f"lib/arm64-v8a/{name}") != blobs[name]:
                    raise SystemExit(f"{name} differs in {apk}")

    output = repo / "dist" / "nonfree"
    output.mkdir(parents=True, exist_ok=True)
    checksums = []
    for name, data in sorted(blobs.items()):
        destination = output / name
        destination.write_bytes(data)
        digest = sha256(data).hexdigest()
        if sha256(destination.read_bytes()).hexdigest() != digest:
            raise SystemExit(f"SHA-256 mismatch for {destination}")
        checksums.append(f"{digest}  {name}\n")
    (output / "SHA256SUMS").write_bytes("".join(checksums).encode("ascii"))
    print(f"Exported and verified {len(blobs)} launch shims in {output}")


if __name__ == "__main__":
    main()
