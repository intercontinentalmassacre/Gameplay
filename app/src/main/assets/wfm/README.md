# Rebuilding `wfm.exe`

The payload is built from Winlator File Manager commit
`cb1d078cef36d3d61eec822882febcde828f09e7`.

Use the 64-bit w64devkit 2.9.0 archive
`w64devkit-x64-2.9.0.7z.exe`:

- Archive SHA-256:
  `bff1d13fc2718eebd93548cf37f8d0332d925458d5e99506cff8f46eb5a9de5a`
- GCC: 16.1.0
- GNU binutils/windres: 2.47.20260726

Clone the upstream source, check out the pinned commit, copy the three patch
files from this directory into the checkout, and apply them in order:

```sh
git clone https://github.com/brunodev85/wfm.git
cd wfm
git checkout cb1d078cef36d3d61eec822882febcde828f09e7
git apply native-copy.patch
git apply replace-all-conflicts.patch
git apply copy-hardening.patch
```

From a w64devkit shell in that clean checkout, run this exact build command.
The object order is intentional and is required for a byte-identical result:

```sh
make CC=gcc RC=windres "INCLUDE_DIR=-I./include" "OBJS=obj/file_actions.o obj/main.o obj/content_view.o obj/toolbar.o obj/navbar.o obj/treeview.o obj/sizebar.o obj/statusbar.o obj/file_node.o obj/file_utils.o obj/input_dialog.o obj/resource.o" "CFLAGS=-O3 -std=c99 -DUNICODE -D_UNICODE -DCOBJMACROS -D_CRT_NON_CONFORMING_WCSTOK -D_WIN32_IE=0x0500 -DWINVER=0x500 -Wall -Wno-error=incompatible-pointer-types -Wno-error=int-conversion" "LDFLAGS=-s -lcomctl32 -lgdi32 -lole32 -luuid -Wl,--subsystem,windows"
```

The expected output is 304,640 bytes with SHA-256:

```text
a7bb48aa14c59ece23c011c43c8439869267b13387d4a972f462a06575deebbb
```
