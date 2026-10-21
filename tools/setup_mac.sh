#!/bin/bash
# One-time toolchain for building Glass Home on macOS (Apple Silicon or Intel):
# JDK 17 (Azul Zulu), Android SDK platform 34 + build-tools, and the Python ADB client.
# Installs into ~/Library/Java and ~/Library/Android; no sudo, no Homebrew needed.
set -e
cd "$(dirname "$0")/.."
ARCH=$(uname -m); [ "$ARCH" = arm64 ] && ZARCH=aarch64 || ZARCH=x64
JDK_DIR=~/Library/Java/JavaVirtualMachines/zulu-17.jdk
SDK=~/Library/Android/sdk
TMP=$(mktemp -d)

if [ ! -x "$JDK_DIR/Contents/Home/bin/java" ]; then
  echo "Downloading JDK 17..."
  curl -sSfL -o "$TMP/jdk.tgz" "https://cdn.azul.com/zulu/bin/zulu17.54.21-ca-jdk17.0.13-macosx_${ZARCH}.tar.gz"
  tar xzf "$TMP/jdk.tgz" -C "$TMP"
  mkdir -p ~/Library/Java/JavaVirtualMachines
  mv "$TMP"/zulu17*/zulu-17.jdk "$JDK_DIR"
fi
export JAVA_HOME="$JDK_DIR/Contents/Home"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Downloading Android command-line tools..."
  curl -sSfL -o "$TMP/clt.zip" https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$TMP/clt.zip" -d "$TMP/clt" && mv "$TMP/clt/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" | tail -1

if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt
fi
# local.properties holds machine-specific paths and your greeting name; it is not committed.
touch launcher/local.properties
grep -q '^sdk.dir=' launcher/local.properties || echo "sdk.dir=$SDK" >> launcher/local.properties
rm -rf "$TMP"
echo "Toolchain ready. JAVA_HOME=$JAVA_HOME"
