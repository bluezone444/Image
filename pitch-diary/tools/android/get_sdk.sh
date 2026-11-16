#!/bin/bash
# 안드로이드 빌드 도구(SDK) 내려받기: platform 34 · build-tools 35 (약 600MB, dl.google.com 접속 필요)
# 사용: tools/android/get_sdk.sh   → tools/android/sdk/ (저장소에는 올리지 않음)
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd); SDK=${ANDROID_SDK_ROOT:-$D/sdk}
if [ -x "$SDK/build-tools/35.0.0/aapt2" ] && [ -f "$SDK/platforms/android-34/android.jar" ]; then echo "SDK 준비됨: $SDK"; exit 0; fi
mkdir -p "$SDK/cmdline-tools"; TMP=$(mktemp -d)
curl -fsSL -o "$TMP/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip
unzip -q "$TMP/clt.zip" -d "$TMP" && rm -rf "$SDK/cmdline-tools/latest" && mv "$TMP/cmdline-tools" "$SDK/cmdline-tools/latest"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;35.0.0" >/dev/null
rm -rf "$TMP"; echo "SDK 설치 완료: $SDK"
