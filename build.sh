#!/bin/bash
# Quasar 2.1 — pipeline de compilación (reusa Android SDK de DrexOS Launcher 2.0)
set -e
cd "$(dirname "$0")"
export PATH="$HOME/workspace/jdk17/bin:$PATH"

SDK=~/workspace/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34
AP=$PLAT/android.jar

OUT=out
mkdir -p $OUT

echo "== AAPT2: recursos =="
$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/app.apk \
  -I $AP \
  --manifest AndroidManifest.xml \
  --java $OUT/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  $OUT/res.zip

echo "== JavaC =="
mkdir -p $OUT/classes
find src $OUT/gen -name "*.java" > $OUT/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp $AP \
  -d $OUT/classes \
  @$OUT/sources.txt

echo "== D8 =="
mkdir -p $OUT/dex
$BT/d8 --lib $AP \
  --min-api 26 \
  --output $OUT/dex \
  $(find $OUT/classes -name "*.class")

echo "== APK =="
cp $OUT/app.apk $OUT/unsigned.apk
cd $OUT/dex && zip -q -0 -X ../unsigned.apk classes.dex && cd ../..
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "== Firma =="
$BT/apksigner sign --ks keystore/drexshare.keystore --ks-pass pass:android \
  --key-pass pass:android --out Quasar-2.1-unsigned.apk $OUT/aligned.apk 2>/dev/null || \
$BT/apksigner sign --ks keystore/drexshare.keystore --ks-pass pass:android \
  --ks-key-alias drexshare --key-pass pass:android \
  --out Quasar-2.1-unsigned.apk $OUT/aligned.apk

echo "== Verificación =="
$BT/apksigner verify --print-certs Quasar-2.1-unsigned.apk | head -5
$BT/aapt dump badging Quasar-2.1-unsigned.apk | head -8

mkdir -p ~/workspace/your_files
cp Quasar-2.1-unsigned.apk ~/workspace/your_files/Quasar-2.1.apk
ls -lh ~/workspace/your_files/Quasar-2.1.apk
echo "LISTO"
