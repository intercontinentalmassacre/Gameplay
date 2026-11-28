"""Give the pinned DroidDeck host private SONAMEs and matching dynamic loader paths."""
from pathlib import Path
import sys

directory = Path(sys.argv[1])
names = {
    "libc++_shared.so": "libbrn_cxxabi.so",
    "libhook_impl.so": "libbrn_hooks.so",
    "libmain_hook.so": "libbrn_entry.so",
}
# Includes DT_NEEDED, DT_SONAME and the AdrenoTools dlopen names in .rodata.
expected = {
    "libc++_shared.so": (1, 0, 0),
    "libdroiddeckwayland.so": (1, 1, 1),
    "libhook_impl.so": (1, 2, 0),
    "libmain_hook.so": (1, 1, 1),
}
for path in directory.glob("*.so"):
    data = path.read_bytes()
    counts = tuple(data.count(name.encode() + b"\0") for name in names)
    if counts != expected.get(path.name, (0, 0, 0)):
        raise ValueError(f"Unexpected host library references in {path.name}: {counts}")
    if any(counts):
        for old, new in names.items():
            assert len(old) == len(new), "ELF offsets must remain unchanged"
            data = data.replace(old.encode() + b"\0", new.encode() + b"\0")
        path.write_bytes(data)
        if path.name in names:
            path.rename(directory / names[path.name])
