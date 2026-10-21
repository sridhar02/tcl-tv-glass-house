#!/bin/bash
# Disable the packages listed in a debloat list (default debloat/tcl-beyondtv.txt), in small
# batches with a screenshot after each batch so you can spot anything that broke.
cd "$(dirname "$0")/.."
LIST="${1:-debloat/tcl-beyondtv.txt}"
[ -f backup/packages_all.txt ] || { echo "Run tools/backup.sh first."; exit 1; }
mkdir -p shots
pkgs=$(sed 's/#.*//' "$LIST" | awk 'NF{print $1}')
batch=(); n=0
flush() {
  [ ${#batch[@]} -gt 0 ] || return
  tools/disable.sh "${batch[@]}"
  n=$((n+1)); tools/tv key KEYCODE_HOME >/dev/null; sleep 2
  tools/tv screencap "shots/debloat_batch_$n.png" >/dev/null && echo "  -> check shots/debloat_batch_$n.png"
  batch=()
}
for p in $pkgs; do
  if grep -q "package:.*=$p\$" backup/packages_all.txt; then batch+=("$p"); else echo "$p: not installed, skipped"; fi
  [ ${#batch[@]} -ge 4 ] && flush
done
flush
