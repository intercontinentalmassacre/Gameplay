#!/usr/bin/env python3
"""Exercise graphics pipeline library validation with a native Fossilize replayer."""

import json
from pathlib import Path
import struct
import subprocess
import sys
from tempfile import TemporaryDirectory


def pipeline(is_library, subsets=0, links=()):
    extensions = []
    if subsets:
        extensions.append({"sType": 1000320002, "flags": subsets})
    if links:
        extensions.append({"sType": 1000290000, "libraries": [f"{index:016x}" for index in links]})
    return {
        "flags": 0x800 if is_library else 0,
        "basePipelineHandle": "0" * 16,
        "basePipelineIndex": -1,
        "layout": "0" * 16,
        "renderPass": "0" * 16,
        "subpass": 0,
        "stages": [],
        "pNext": extensions,
    }


def main(executable):
    # The null device only needs the library graph. The four leaves provide one subset each.
    pipelines = [pipeline(True, subsets) for subsets in (1, 2, 4, 8)]
    pipelines += [
        pipeline(True, links=(1, 4)),
        pipeline(False, links=(5, 2, 3)),
        pipeline(False, links=(5, 2, 3, 4)),
        pipeline(False, subsets=8, links=(5, 2, 3)),
    ]
    with TemporaryDirectory() as directory:
        archive = Path(directory) / "graphics.foz"
        cache_prefix = Path(directory) / "replayed.foz"
        with archive.open("wb") as output:
            output.write(b"\x81FOSSILIZEDB\0\0\0\x06")
            for index, state in enumerate(pipelines, 1):
                name = f"{index:016x}"
                payload = json.dumps({"version": 6, "graphicsPipelines": {name: state}}).encode()
                output.write(f"{6:024x}{name}".encode())
                output.write(struct.pack("<IIII", len(payload), 1, 0, len(payload)))
                output.write(payload)
        replay = subprocess.run(
            [executable, str(archive), "--num-threads", "1", "--null-device", "--replayer-cache", str(cache_prefix)],
            check=True, capture_output=True, text=True,
        )
        for invalid in (7, 8):
            assert f"{invalid:016x} links overlapping library subsets" in replay.stderr, replay.stderr
        # The replayer cache stores one 56-byte, zero-payload entry per compiled pipeline.
        entries = set()
        for cache in cache_prefix.parent.glob(cache_prefix.name + ".*"):
            contents = cache.read_bytes()
            entries.update(int(contents[offset:offset + 40], 16) for offset in range(16, len(contents), 56))
        assert entries == {(6 << 64) | index for index in range(1, 7)}, entries


if __name__ == "__main__":
    main(sys.argv[1])
