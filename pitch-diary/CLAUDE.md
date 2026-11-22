# Pitch Career (피치 커리어) — 지속 관리 프로젝트

한국어 축구 선수 커리어 육성 게임. 고3 유망주로 시작해 K리그 → 유럽 → 은퇴 → 자녀(가문)로 이어진다.
원본은 **`career.html` 한 파일**(바닐라 JS · 인라인 CSS · 인라인 SVG, 약 7,500줄)이고, 배포본은 이 파일에서 빌드한다.

## 사용자와 일하는 방식
- 답변은 **한국어로 짧게**. 게임 안 문구도 짧게(사용자가 "말이 너무 많다"고 한 적 있음).
- **코드가 노출되면 안 된다.** 다른 사람에게 주는 파일(HTML·APK·웹앱)은 항상 `tools/build.py`로 난독화한 것만 준다. 원본 `career.html`을 배포하지 않는다.
- 작업 브랜치에 커밋·푸시. PR은 요청할 때만.
- 미리보기(비공개 아티팩트): https://claude.ai/artifact/E5JzDmtNHLGem4M4qtDdhN — `dist/pub/career.html`을 이 URL로 다시 게시한다.

## 폴더
| 경로 | 내용 |
|---|---|
| `career.html` | 게임 원본 (유일한 소스) |
| `index.html` | 직관 일기(별개 페이지). 빌드에서 링크를 뺀다 |
| `fonts/` | Pretendard (SIL OFL) |
| `android/` | WebView 래퍼 앱 소스 (`MainActivity`, `Bridge` = `saveFile` 백업 · `saveImage` 카드 저장). `AndroidManifest.xml`의 versionCode는 빌드 때마다 올라가고 커밋한다 |
| `tools/build.py` | 빌드: `pub` · `pwa` · `trial [일수]` · `all` → `dist/` |
| `tools/obfuscate.js` | terser + javascript-obfuscator + csso (먼저 `cd tools && npm ci`) |
| `tools/pwa/` | 웹앱 매니페스트 · 서비스 워커 템플릿 · 아이콘 |
| `tools/android/` | `get_sdk.sh`(SDK 설치, dl.google.com 필요) · `build_apk.sh`(APK) |
| `tools/test/` | `smoke.js`(30초) · `regression.js`(10시즌 은퇴까지, 3~6분) |
| `dist/` | 빌드 결과물 (git 제외) |

## 자주 쓰는 명령 (pitch-diary 폴더에서)
```bash
(cd tools && npm ci)                       # 처음 한 번
node tools/test/smoke.js                   # 고칠 때마다
node tools/test/regression.js              # 경기 엔진·저장·시즌 흐름을 건드렸을 때
python3 tools/build.py all 7               # 미리보기 사본 + 웹앱 + 7일 체험판 + 기한 없는 정식판
node tools/test/smoke.js dist/trial/pitch-career-trial.html   # 난독화본도 확인
tools/android/get_sdk.sh                   # APK가 필요할 때 한 번
PITCH_KS_PASS=… PITCH_KEYSTORE=… tools/android/build_apk.sh   # 체험판 → dist/trial/pitch-career-trial.apk
PITCH_KS_PASS=… PITCH_KEYSTORE=… tools/android/build_apk.sh dist/full/pitch-career.html dist/full/PitchCareer.apk 1.0   # 기한 없는 정식판
```
- **서명 키(`pitch-career.jks`, 별칭 `pitch`)는 저장소에 없다.** 사용자가 보관한다. 같은 키로 서명해야 기존 설치 위에 업데이트된다. 키가 없으면 사용자에게 받아서 `tools/android/`에 두고(git 제외됨) 비밀번호도 받는다. 새 키를 만들면 기존 설치본과 호환이 깨진다는 점을 먼저 알린다.
- 테스트 화면 확인: Playwright(`require('playwright')`). 같은 URL에서 해시만 바꿀 땐 먼저 `about:blank`로 이동. `#skiphs` 해시는 고교 프롤로그를 건너뛴다.
- 내부 상태가 필요한 테스트는 사본을 만들어 마지막 `})();` 바로 앞에 `window.__T={get S(){return S},go,save,renderHub};`를 넣어 쓴다(원본은 건드리지 않음).

## 구조 핵심
- 상태 `S` 하나, 저장은 localStorage 슬롯 3개(`pitch-career.v1`, `.s1`, `.s2`). **`load()`가 모든 필드를 검증·기본값 처리**한다 — 새 필드를 추가하면 반드시 `load()`에 정규화를 넣고, 새 커리어 생성 객체(`best:{rating:0, salary:3000}` 근처)에도 기본값을 넣는다. 옛 저장 파일이 깨지면 안 된다.
- **되돌리기 방지:** 확률 결과는 먼저 굴려 저장한 뒤 연출한다(경기 `simulate()` → `S.pending`, 장비 강화 `doForge()` 등). 새로고침으로 결과를 바꿀 수 없게 유지.
- 화면 흐름은 `go()`가 `S.phase`로 고른다: week · match · interview · nego · retired · window · tnego · awards · best11 · school · scout · signing. 허브 탭은 `UI.tab`(match/player/shop/forge/skills/home/bench/club/world/news).
- 경기 엔진: `matchModel()`(찬스 모델, 장비 보너스는 `effStats()`로 반영) → `simulate()` → 중계 → `finalize()` · `applyMatch()`(보상·재료). 기술 트리·장비 특수 능력의 보정치는 모두 `skm(key)`로 합산.
- 주요 시스템: 포지션별 기술 트리(`SKILLS`), 골키퍼 전용 능력치 이름(`GK_STAT`), 인터뷰·심리전, 연봉/이적 협상, 시상식·베스트 11, 국가대표, 우리 집(집·차·인테리어), 가문(10시즌 후 자녀, 금융 지원 세금 90%), 장비 강화(`GEAR`, +10 특수 능력), 공유 카드(`makeCard`, 1080×1350 PNG), 체험판 잠금(`TRIAL_END`, 원본은 항상 `0`).
- 실명: `REAL_STARS`(2025-26 기준 129명). 외국 선수는 한 글자 바꾼 이름, 한국 선수는 그대로. `REAL_NAMES=false`면 전부 가상 이름. 옛 저장은 `parodyFix()`로 바꾼다.
- 캐릭터: `chibiSVG()`(viewBox 0 0 200 262, 얼굴 크롭 `42 14 116 116`), 포즈 `data-cpose` = idle/run/clap/jump, 표정 `mood` = happy/sad/angry/wow.

## 지켜야 할 규칙
- 외부 라이브러리·CDN·네트워크 요청 없음. CSP가 엄격하다(`img-src data:` 등). 그림은 인라인 SVG·캔버스로.
- 애니메이션은 transform/opacity 위주(저사양 안드로이드 WebView), `prefers-reduced-motion`이면 끈다(`RM` 상수).
- 화면 폭 320·360·412px에서 가로 넘침·겹침이 없어야 한다(`smoke.js`가 320·360을 확인).
- UI 문구는 한국어, 짧게. 이모지는 자동으로 Lucide 아이콘으로 바뀐다.
- 원본의 `const TRIAL_END = 0;`은 그대로 둔다(체험판 빌드가 바꿔 넣는다).

## 출시 전 남은 일 (사용자와 논의된 것)
- 구단·리그 이름이 실명이다 → 스토어 출시 전에 가상 이름으로 바꾸기(상표 위험).
- 구글 플레이: AAB 빌드, 최신 target API, 개인정보 처리방침, 출시용 서명 키 새로 만들기.
- 장비 +10 밸런스(공격수 경기당 골 기대치 약 2배) — 사용자 피드백에 따라 조정.
- 체험판 날짜 되돌리기 우회 방지(마지막 실행일 기억) — 제안만 함.
