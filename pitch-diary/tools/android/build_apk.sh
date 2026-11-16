#!/bin/bash
# 체험판 HTML을 안드로이드 앱(APK)으로 묶는다.
# 사용: tools/android/build_apk.sh [입력 HTML]   (기본: dist/trial/pitch-career-trial.html)
# 필요: tools/android/get_sdk.sh 로 SDK 설치, JDK(javac·keytool), python3 + Pillow
# 서명 키: $PITCH_KEYSTORE (기본 tools/android/pitch-career.jks), 비밀번호 $PITCH_KS_PASS, 별칭 pitch
#   키는 저장소에 올리지 않는다. 같은 키로 서명해야 이미 설치된 앱 위에 업데이트가 된다.
# versionCode는 android/AndroidManifest.xml 값을 1 올려서 쓰고, 그 값을 파일에 남긴다(커밋할 것).
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$D/../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-$D/sdk}; BT=$SDK/build-tools/35.0.0; JAR=$SDK/platforms/android-34/android.jar
IN=${1:-$ROOT/dist/trial/pitch-career-trial.html}; APP=$ROOT/android; OUT=$ROOT/dist/android-build
KS=${PITCH_KEYSTORE:-$D/pitch-career.jks}; PASS=${PITCH_KS_PASS:?PITCH_KS_PASS(서명 키 비밀번호)를 설정해 주세요}
[ -x "$BT/aapt2" ] || { echo "SDK가 없어요. 먼저 tools/android/get_sdk.sh"; exit 1; }
[ -f "$IN" ] || { echo "입력 HTML이 없어요: $IN (먼저 python3 tools/build.py trial)"; exit 1; }
VC=$(( $(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | grep -o '[0-9]*') + 1 ))
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" "$APP/AndroidManifest.xml"
rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/assets"
cp -r "$APP/res/." "$OUT/res/"; cp "$IN" "$OUT/assets/index.html"
python3 - "$ROOT/tools/pwa/icon-512.png" "$OUT/res" <<'PY'
import sys, os
from PIL import Image
src, res = sys.argv[1], sys.argv[2]
im = Image.open(src).convert('RGBA')
for d, px in {'mdpi':48, 'hdpi':72, 'xhdpi':96, 'xxhdpi':144, 'xxxhdpi':192}.items():
    os.makedirs(f'{res}/mipmap-{d}', exist_ok=True); im.resize((px, px), Image.LANCZOS).save(f'{res}/mipmap-{d}/ic_launcher.png')
PY
"$BT/aapt2" compile --dir "$OUT/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$APP/AndroidManifest.xml" -A "$OUT/assets" --java "$OUT/gen" "$OUT/res.zip" --min-sdk-version 24 --target-sdk-version 34
javac -nowarn -g:none --release 8 -classpath "$JAR" -d "$OUT/classes" $(find "$APP/src" "$OUT/gen" -name '*.java') 2>&1 | grep -v "warning\|JAVA_TOOL_OPTIONS" || true
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BT/d8" --min-api 24 --lib "$JAR" --output "$OUT" "$OUT/classes.jar"
(cd "$OUT" && zip -q -j base.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias pitch -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Pitch Career, O=Pitch Career, C=KR" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
mkdir -p "$ROOT/dist/trial"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --ks-key-alias pitch --out "$ROOT/dist/trial/pitch-career-trial.apk" "$OUT/aligned.apk" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
rm -f "$ROOT/dist/trial/pitch-career-trial.apk.idsig"
echo "APK 완성 · versionCode $VC → dist/trial/pitch-career-trial.apk (android/AndroidManifest.xml 커밋 필요)"
