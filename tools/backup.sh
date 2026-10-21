#!/bin/bash
# Snapshot the TV before changing anything: package lists, props, all settings, home app.
# Output goes to backup/ (git-ignored: it contains device identifiers).
cd "$(dirname "$0")/.."
umask 077  # backup/ holds device identifiers: owner-only
mkdir -p backup
tools/tv shell 'pm list packages -f' > backup/packages_all.txt
tools/tv shell 'pm list packages -d' > backup/packages_disabled.txt
tools/tv shell 'pm list packages -s' > backup/packages_system.txt
tools/tv shell 'pm list packages -3' > backup/packages_thirdparty.txt
tools/tv shell 'getprop' > backup/getprop.txt
for t in global secure system; do tools/tv shell "settings list $t" > "backup/settings_$t.txt"; done
tools/tv shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' > backup/home_default.txt
wc -l backup/* | tail -1
echo "Backup saved in backup/. Already-disabled packages: $(wc -l < backup/packages_disabled.txt)"
