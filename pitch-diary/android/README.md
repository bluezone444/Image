# Pitch Career 안드로이드 앱 (WebView 래퍼)

`assets/index.html`(난독화된 게임 빌드)을 화면 가득 띄우는 작은 앱입니다. 인터넷 없이 동작하고, 저장 데이터는 앱 안에 남습니다.

- `MainActivity`: 게임 화면, 뒤로 가기, 백업 파일 불러오기(파일 선택)
- `Bridge`: 게임의 `window.AndroidBridge.saveFile(이름, 내용)` → 휴대폰 다운로드 폴더에 백업 저장

## 빌드 (Android SDK platform 34 · build-tools 35 필요)

1. 체험판·배포용 HTML을 `assets/index.html`로 복사
2. 아이콘 PNG를 `res/mipmap-*/ic_launcher.png`로 넣기 (48·72·96·144·192px)
3. `aapt2 compile` → `aapt2 link -A assets` → `javac --release 8` → `d8`(build-tools 35 이상) → `zipalign` → `apksigner`

서명 키는 저장소에 올리지 않습니다. 같은 키로 서명해야 기존 설치 위에 업데이트할 수 있어요.
