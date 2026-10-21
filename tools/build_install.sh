#!/bin/bash
# Build the Glass Home launcher, install it on the TV and make it the default home screen.
# Needs: ADB debugging on the TV, JDK 17 and the Android SDK (see README).
set -e
cd "$(dirname "$0")/.."
# JDK 17: $JAVA_HOME, else macOS java_home, else the JDK tools/setup_mac.sh installs.
if [ -z "$JAVA_HOME" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null) ||
  JAVA_HOME=$(ls -d ~/Library/Java/JavaVirtualMachines/*/Contents/Home \
                    ~/Library/Java/JavaVirtualMachines/*/zulu-17.jdk/Contents/Home 2>/dev/null | head -1)
fi
[ -x "$JAVA_HOME/bin/java" ] || { echo "No JDK 17 found: run tools/setup_mac.sh"; exit 1; }
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# local.properties (git-ignored) keeps sdk.dir and your greeting.name; only add sdk.dir if missing.
touch launcher/local.properties
grep -q '^sdk.dir=' launcher/local.properties || echo "sdk.dir=$ANDROID_HOME" >> launcher/local.properties

(cd launcher && ./gradlew -q assembleDebug)
APK=launcher/app/build/outputs/apk/debug/app-debug.apk
echo "Built $APK ($(du -h "$APK" | cut -f1))"

# adb install can hang waiting for on-screen confirmation, so push and use pm.
tools/tv push "$APK" /data/local/tmp/glasshome.apk
tools/tv shell 'pm install -r /data/local/tmp/glasshome.apk'
tools/tv shell 'rm -f /data/local/tmp/glasshome.apk'
# Android 9 only reveals the Wi-Fi name to apps with a location permission.
tools/tv shell 'pm grant com.sridhar.glasshome android.permission.ACCESS_COARSE_LOCATION' || true
# Now Playing card: let Glass Home read media sessions (TV Settings has no screen for this).
tools/tv shell 'cmd notification allow_listener com.sridhar.glasshome/com.sridhar.glasshome.MediaListener' || true
tools/tv shell 'cmd package set-home-activity com.sridhar.glasshome/.MainActivity'
# On Android 9 the stock launcher outranks every other home app, so it has to be disabled.
# (Logged in backup/disabled_by_us.txt; tools/restore.sh brings it back.)
if ! tools/tv shell 'pm list packages -d' | grep -q 'package:com.google.android.tvlauncher$'; then
  mkdir -p backup
  tools/disable.sh com.google.android.tvlauncher
  tools/tv shell 'cmd package set-home-activity com.sridhar.glasshome/.MainActivity'
fi
tools/tv shell 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME' >/dev/null
echo "Installed and set as home."
