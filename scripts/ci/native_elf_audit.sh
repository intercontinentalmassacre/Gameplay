#!/usr/bin/env bash
# Audits every bundled ELF for basic sanity and 16KB page-size alignment.
# Fails on unreadable ELF or LOAD alignment below 4096; warns below 16384.
set -u

fail=0
warn=0
count=0

while IFS= read -r -d '' so; do
  count=$((count + 1))
  header=$(readelf -lW "$so" 2>/dev/null) || {
    echo "ERROR: readelf failed: $so"
    fail=1
    continue
  }
  aligns=$(echo "$header" | awk '$1 == "LOAD" {print $NF}' | sort -u)
  for a in $aligns; do
    align_dec=$((a))
    if [ "$align_dec" -lt 4096 ]; then
      echo "ERROR: $so LOAD alignment $a (< 0x1000)"
      fail=1
    elif [ "$align_dec" -lt 16384 ]; then
      echo "WARN: $so LOAD alignment $a (< 0x4000, not 16KB-ready)"
      warn=1
    fi
  done
done < <(find app -name '*.so' -print0)

echo "audited $count .so files (errors: $fail, warnings: $warn)"
exit "$fail"
