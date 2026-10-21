#!/bin/bash
# Undo everything: re-enable every package this project disabled, put the stock
# launcher back as home and reset DNS. Needs ADB debugging switched on on the TV.
cd "$(dirname "$0")/.."
while read -r p; do
  [ -n "$p" ] || continue
  echo "enable $p: $(tools/tv shell "pm enable $p" | tail -1)"
done < backup/disabled_by_us.txt
tools/tv shell 'settings put global private_dns_mode opportunistic'
tools/tv shell 'cmd package set-home-activity com.google.android.tvlauncher/.MainActivity'
echo "To remove Glass Home too:  tools/tv shell 'pm uninstall com.sridhar.glasshome'"
echo "Done. Packages that were already disabled before (see backup/packages_disabled.txt) were left alone."
