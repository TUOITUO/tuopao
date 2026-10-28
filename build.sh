#!/usr/bin/env bash
# 手工编译 MockGPS APK（无 gradle，纯 aapt2 + javac + d8 + apksigner）
# 前置：$HOME/android-build 下有 jdk/ 与 build-tools/33.0.2 platforms/android-33
set -euo pipefail
H="$HOME/android-build"
BT="$H/build-tools/33.0.2"
AJ="$H/platforms/android-33/android.jar"
JAVAC="$H/jdk/bin/javac"
D8="$BT/d8"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"/{gen,obj,dex}

cd "$HERE"

echo "== 1/4 编译资源 =="
"$BT/aapt2" compile --dir res -o "$BUILD/res.zip"
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$AJ" \
    --manifest AndroidManifest.xml \
    -R "$BUILD/res.zip" \
    --java "$BUILD/gen" \
    -A assets

echo "== 2/4 javac =="
find src "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -source 8 -target 8 -nowarn \
    -classpath "$AJ" -d "$BUILD/obj" @"$BUILD/sources.txt" 2>&1 | grep -v "^warning:" || true

echo "== 3/4 d8 dex =="
find "$BUILD/obj" -name '*.class' > "$BUILD/classes.txt"
"$D8" --release --lib "$AJ" --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "== 4/4 组装 + 签名 =="
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/unsigned.apk" classes.dex)
# 对齐
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
# keystore（首次生成）
KS="$HERE/debug.jks"
if [ ! -f "$KS" ]; then
    "$H/jdk/bin/keytool" -genkeypair -keystore "$KS" -alias mockgps \
        -storepass android -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=MockGPS, OU=test" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$HERE/mockgps.apk" "$BUILD/aligned.apk"
"$BT/apksigner" verify --print-certs "$HERE/mockgps.apk" | head -3
echo "BUILD_OK: $HERE/mockgps.apk"
