#!/bin/bash
# Find Android TVs on this network (via mDNS) and save the chosen one to tools/tv.local.
# Saves the TV's stable <uuid>.local Chromecast name, so a new DHCP address after a reboot
# doesn't matter. macOS (uses dns-sd).
cd "$(dirname "$0")/.."
umask 077  # tools/tv.local: owner-only
tmp=$(mktemp -d)
( dns-sd -B _googlecast._tcp local. > "$tmp/cast" & p=$!; sleep 4; kill $p ) 2>/dev/null
names=$(awk '/Add/ {for (i=7;i<=NF;i++) printf "%s%s", $i, (i<NF?" ":"\n")}' "$tmp/cast" | sort -u)
[ -n "$names" ] || { echo "No Android/Google TV found. Is it on and on the same Wi-Fi?"; exit 1; }
i=0; declare -a hosts
while IFS= read -r n; do
  ( dns-sd -L "$n" _googlecast._tcp local. > "$tmp/r" & p=$!; sleep 2; kill $p ) 2>/dev/null
  host=$(grep -o 'reached at [^ :]*' "$tmp/r" | head -1 | awk '{print $3}' | sed 's/\.$//')
  friendly=$(grep -o 'fn=[^=]*' "$tmp/r" | head -1 | sed 's/^fn=//; s/ [a-z]*$//; s/\\ / /g')
  ip=$(dscacheutil -q host -a name "$host" 2>/dev/null | awk '/ip_address/{print $2; exit}')
  [ -n "$host" ] || continue
  i=$((i+1)); hosts[$i]=$host
  echo "$i) ${friendly:-$n}  ($host, $ip)"
done <<< "$names"
rm -rf "$tmp"
[ $i -gt 0 ] || { echo "Found TVs but could not resolve them."; exit 1; }
if [ $i -eq 1 ]; then pick=1; else read -rp "Which TV? [1-$i] " pick; fi
echo "${hosts[$pick]}" > tools/tv.local
echo "Saved ${hosts[$pick]} to tools/tv.local. Check port 5555 with: nc -vz ${hosts[$pick]} 5555"
