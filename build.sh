#!/data/data/com.termux/files/usr/bin/bash
# MYRA build script for Termux
set -e
cd "$(dirname "$0")"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

# SDK location
if [ ! -f local.properties ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
  echo "sdk.dir=$SDK" > local.properties
  echo "[i] local.properties -> sdk.dir=$SDK (galat ho to edit karo)"
fi

# Gradle wrapper (pinned 8.9) - generated from an empty temp dir
if [ ! -f gradlew ]; then
  echo "[i] Gradle wrapper bana raha hoon..."
  T="$(mktemp -d)"; ( cd "$T" && gradle wrapper --gradle-version 8.9 --no-daemon )
  cp -r "$T/gradlew" "$T/gradle" . ; chmod +x gradlew
fi

AAPT2=""
[ -x "$PREFIX/bin/aapt2" ] && AAPT2="-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2"

./gradlew assembleDebug --no-daemon $AAPT2

APK=app/build/outputs/apk/debug/app-debug.apk
echo "[ok] APK: $PWD/$APK"
for d in "$HOME/storage/downloads" "/sdcard/Download"; do
  [ -d "$d" ] && cp "$APK" "$d/MYRA.apk" && echo "[ok] copied -> $d/MYRA.apk" && break
done
