#!/usr/bin/env python3
"""Pitch Career 빌드 도구. 원본은 ../career.html 하나이고, 결과물은 ../dist/ 에 만든다(저장소에는 올리지 않음).

사용법 (pitch-diary 폴더에서):
  python3 tools/build.py pub            # 미리보기(아티팩트)용 사본 → dist/pub/career.html
  python3 tools/build.py pwa            # 난독화 정식판 + 오프라인 웹앱 → dist/pwa/
  python3 tools/build.py trial [일수]    # 체험판(기본 7일) → dist/trial/ (단일 HTML · 웹앱 ZIP)
  python3 tools/build.py full           # 기한 없는 정식판 단일 HTML → dist/full/pitch-career.html (APK 재료)
  python3 tools/build.py all [일수]      # 위 세 가지 모두
처음 한 번: (cd tools && npm ci)  — 난독화 도구 설치
"""
import base64, datetime, hashlib, os, re, shutil, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, 'tools')
DIST = os.path.join(ROOT, 'dist')
SRC = os.path.join(ROOT, 'career.html')
FONTS = os.path.join(ROOT, 'fonts')
KST = datetime.timezone(datetime.timedelta(hours=9))
DIARY_LINK = r'<a class="t-diary" href="index.html">\$\{ic\(\'book-open\'\)\} 직관 일기</a>'
PWA_HEAD = ('<meta name="theme-color" content="#0a0f1f">\n'
            '<meta name="description" content="K리그에서 유럽 무대까지, 나만의 축구 선수 커리어 게임">\n'
            '<link rel="manifest" href="manifest.webmanifest">\n'
            '<link rel="icon" type="image/png" sizes="192x192" href="icon-192.png">\n'
            '<link rel="apple-touch-icon" href="apple-touch-icon.png">\n'
            '<meta name="apple-mobile-web-app-capable" content="yes">\n'
            '<meta name="mobile-web-app-capable" content="yes">\n'
            '<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">\n'
            '<meta name="apple-mobile-web-app-title" content="Pitch Career">\n'
            '<link rel="preload" href="fonts/PretendardVariable.woff2" as="font" type="font/woff2" crossorigin>\n')
SW_REG = '<script>if("serviceWorker" in navigator&&location.protocol!=="file:")addEventListener("load",()=>navigator.serviceWorker.register("sw.js").catch(()=>{}));</script>\n</body>'


def read(p): return open(p, encoding='utf-8').read()
def write(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, 'w', encoding='utf-8').write(s)


def obfuscate(src_path, out_path):
    if not os.path.isdir(os.path.join(TOOLS, 'node_modules')):
        sys.exit('난독화 도구가 없어요. 먼저 실행: (cd tools && npm ci)')
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    subprocess.run(['node', 'obfuscate.js', src_path, out_path], cwd=TOOLS, check=True, stdout=subprocess.DEVNULL)
    return read(out_path)


def embed_fonts(s):
    """글꼴을 data URI로 넣어 파일 하나로 실행되게 한다."""
    for f in ['PretendardVariable.woff2', 'Pretendard-ExtraBold.woff2']:
        b64 = base64.b64encode(open(os.path.join(FONTS, f), 'rb').read()).decode()
        s, n = re.subn(r'url\(fonts/' + re.escape(f) + r'\)', f'url(data:font/woff2;base64,{b64})', s)
        assert n >= 1, f
    return s.replace("font-src 'self'", "font-src 'self' data:")


def build_full():
    """기한 없는 정식판(TRIAL_END = 0 그대로) 단일 HTML. 앱(APK)에 넣거나 직접 나눠 줄 때 쓴다."""
    src = read(SRC)
    assert src.count('const TRIAL_END = 0;') == 1, '원본의 TRIAL_END는 0이어야 해요'
    s = obfuscate(SRC, os.path.join(DIST, 'tmp', 'full-obf.html'))
    out = os.path.join(DIST, 'full', 'pitch-career.html')
    write(out, embed_fonts(s))
    print('full  → dist/full/pitch-career.html (기한 없음)')


def build_pub():
    """claude.ai 아티팩트용: 문서 껍데기(doctype·head·CSP)와 직관 일기 링크를 뺀 원본 사본."""
    s = read(SRC)
    for pat in [r'<!doctype html>\n', r'<html lang="ko">\n', r'<head>\n', r'</head>\n', r'<body>\n', r'</body>\n', r'</html>\n?',
                r'<meta charset="utf-8">\n', r'<meta name="viewport"[^>]*>\n', r'<meta http-equiv="Content-Security-Policy"[^>]*>\n',
                r'<meta name="referrer"[^>]*>\n', DIARY_LINK]:
        s, n = re.subn(pat, '', s, count=1)
        assert n == 1, pat
    out = os.path.join(DIST, 'pub', 'career.html')
    write(out, s)
    print('pub   →', os.path.relpath(out, ROOT))


def pwa_dir(html, out_dir, cache):
    """난독화된 HTML을 오프라인 웹앱 폴더로: 매니페스트 · 서비스 워커 · 아이콘 · 글꼴."""
    if os.path.exists(out_dir): shutil.rmtree(out_dir)
    os.makedirs(out_dir)
    for f in os.listdir(os.path.join(TOOLS, 'pwa')):
        if f != 'sw.js': shutil.copy(os.path.join(TOOLS, 'pwa', f), out_dir)
    shutil.copytree(FONTS, os.path.join(out_dir, 'fonts'))
    m = re.search(r'<meta http-equiv="Content-Security-Policy" content="([^"]*)">', html)
    csp = m.group(1).replace("img-src data:", "img-src data: 'self' blob:") + "; manifest-src 'self'; worker-src 'self'; connect-src 'self'"
    html = html[:m.start(1)] + csp + html[m.end(1):]
    html = html.replace('<meta name="viewport"', PWA_HEAD + '<meta name="viewport"', 1).replace('</body>', SW_REG, 1)
    write(os.path.join(out_dir, 'index.html'), html)
    write(os.path.join(out_dir, 'sw.js'), read(os.path.join(TOOLS, 'pwa', 'sw.js')).replace('__VERSION__', cache))


def build_pwa():
    html = obfuscate(SRC, os.path.join(DIST, 'tmp', 'obf.html'))
    cache = hashlib.sha1(html.encode()).hexdigest()[:10]   # 내용이 바뀌면 캐시 이름도 바뀌어 다음 실행 때 갱신
    pwa_dir(html, os.path.join(DIST, 'pwa'), cache)
    print('pwa   → dist/pwa/ (cache', cache + ')')


def build_trial(days=7):
    end = (datetime.datetime.now(KST) + datetime.timedelta(days=days)).replace(hour=23, minute=59, second=59, microsecond=0)
    end_ms = int(end.timestamp() * 1000)
    src = read(SRC)
    assert src.count('const TRIAL_END = 0;') == 1, '원본의 TRIAL_END는 0이어야 해요'
    tmp = os.path.join(DIST, 'tmp', 'trial-src.html')
    write(tmp, src.replace('const TRIAL_END = 0;', f'const TRIAL_END = {end_ms};'))
    s = obfuscate(tmp, os.path.join(DIST, 'tmp', 'trial-obf.html'))
    out = os.path.join(DIST, 'trial')
    os.makedirs(out, exist_ok=True)
    # 단일 HTML: 글꼴을 data URI로 넣어 파일 하나로 실행
    write(os.path.join(out, 'pitch-career-trial.html'), embed_fonts(s))
    # 웹앱 ZIP
    pw = os.path.join(DIST, 'tmp', 'trial-pwa')
    pwa_dir(s, pw, f'trial-{end_ms}')
    zp = os.path.join(out, 'pitch-career-trial-app.zip')
    with zipfile.ZipFile(zp, 'w', zipfile.ZIP_DEFLATED) as z:
        for r, _, fs in os.walk(pw):
            for f in fs:
                fp = os.path.join(r, f)
                z.write(fp, os.path.relpath(fp, pw))
    print('trial →', 'dist/trial/ · 기한', end.strftime('%Y-%m-%d %H:%M KST'))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'all'
    days = int(sys.argv[2]) if len(sys.argv) > 2 else 7
    if cmd in ('pub', 'all'): build_pub()
    if cmd in ('pwa', 'all'): build_pwa()
    if cmd in ('trial', 'all'): build_trial(days)
    if cmd in ('full', 'all'): build_full()
    if cmd not in ('pub', 'pwa', 'trial', 'full', 'all'): sys.exit(__doc__)
