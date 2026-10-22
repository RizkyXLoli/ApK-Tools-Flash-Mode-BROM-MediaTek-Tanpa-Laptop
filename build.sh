#!/bin/bash

set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"

if [ -d "$SDK/build-tools" ]; then
  BT="$SDK/build-tools/$(ls -1 "$SDK/build-tools" | sort -V | tail -1)"
else
  echo "ERROR: build-tools tidak ditemukan di $SDK/build-tools" >&2
  exit 1
fi

if [ -f "$SDK/platforms/android-34/android.jar" ]; then
  PLATFORM="$SDK/platforms/android-34/android.jar"
else
  PLATFORM="$(ls -1d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi

if [ -z "${PLATFORM:-}" ] || [ ! -f "$PLATFORM" ]; then
  echo "ERROR: android.jar tidak ditemukan. Pasang platform android-34." >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  for j in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64; do
    [ -x "$j/bin/javac" ] && { JAVA_HOME="$j"; break; }
  done
fi
[ -x "${JAVA_HOME:-}/bin/javac" ] || { echo "ERROR: javac tidak ditemukan. Set JAVA_HOME." >&2; exit 1; }
export JAVA_HOME

OUT="$SRC/build"
APK_NAME=mtkflash.apk

echo "SDK      : $SDK"
echo "build-tools: $(basename "$BT")"
echo "platform : $PLATFORM"
echo "JAVA_HOME: $JAVA_HOME"
echo

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

echo "=== 1. kompilasi resource ==="
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res/res.zip"

echo "=== 2. link resource + manifest ==="
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/classes" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  "$OUT/res/res.zip"

echo "=== 3. kompilasi Java ==="
find "$SRC/src" -name '*.java' > "$OUT/sources.txt"
echo "$OUT/classes/com/rizky/mtkflash/R.java" >> "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" \
  -source 8 -target 8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  -nowarn \
  @"$OUT/sources.txt" 2>&1 | grep -vE 'bootstrap class path|source value 8|target value 8|deprecat' || true

echo "=== 4. dex ==="
"$BT/d8" --release --min-api 24 \
  --lib "$PLATFORM" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "=== 5. rakit APK ==="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )

echo "=== 6. zipalign ==="
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "=== 7. tanda tangan ==="

KS="$SRC/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "keystore debug baru dibuat: $KS"
fi

"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$SRC/$APK_NAME" \
  "$OUT/aligned.apk"

echo "=== 8. verifikasi ==="
"$BT/apksigner" verify --print-certs "$SRC/$APK_NAME" | head -5
ls -l "$SRC/$APK_NAME"
echo
echo "APK: $SRC/$APK_NAME"
