#!/bin/bash
# Disable packages one pm call each; log successes to backup/disabled_by_us.txt
cd "$(dirname "$0")/.."
for p in "$@"; do
  out=$(tools/tv shell "pm disable-user --user 0 $p" 2>&1 | tail -1)
  echo "$p -> $out"
  case "$out" in *disabled-user*) grep -qx "$p" backup/disabled_by_us.txt 2>/dev/null || echo "$p" >> backup/disabled_by_us.txt;; esac
done
