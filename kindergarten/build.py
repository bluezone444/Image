"""유치원알리미 Open API에서 데이터를 내려받아 하윤이 전용 앱(hayoon.html)을 만든다.

GitHub Actions(.github/workflows/kindergarten-data.yml)에서 실행되며,
로컬에서도 `python kindergarten/build.py`로 실행할 수 있다.
"""
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime

API_KEY = os.environ.get("CHILDSCHOOL_KEY") or "5164059cb25a4827b42a677c4af10a04"
API_BASE = "https://e-childschoolinfo.moe.go.kr/api/notice/"
# 우리 집: 동천센트럴자이 (좌표는 실행 때 주소 검색으로 확인, 실패하면 아래 대략값)
HOME_NAME = "동천센트럴자이"
HOME_QUERIES = ["동천센트럴자이", "동천 센트럴자이", "Dongcheon Central Xi"]
HOME = (37.3343, 127.0862)

REGIONS = [
    {"name": "수지구", "sido": "41", "sgg": "41465"},
    {"name": "분당구", "sido": "41", "sgg": "41135"},
    {"name": "기흥구", "sido": "41", "sgg": "41463"},
]
EXTRAS = {
    "_bus": "schoolBus", "_after": "afterSchoolPresent", "_meal": "schoolMeal",
    "_building": "building", "_classArea": "classArea", "_teachers": "teachersInfo",
    "_lesson": "lessonDay", "_safety": "safetyEdu", "_hygiene": "environmentHygiene",
    "_years": "yearOfWork",  # 교사 근속연수
}

# 영어유치원 = 법적으로 '유아 대상 영어학원' → 유치원알리미가 아닌 나이스(NEIS) 학원교습소정보에 있음
NEIS_KEY = os.environ.get("NEIS_KEY") or "9cfcc173b15f44768c52ad689224a185"
NEIS_URL = "https://open.neis.go.kr/hub/acaInsTiInfo"
GU = re.compile(r"(수지구|분당구|기흥구)")
YOUNG = re.compile(r"유아|유치|영유|킨더|kinder|키즈|kids|프리스쿨|pre-?school|토들러|toddler", re.I)
ENGLISH = re.compile(r"영어|외국어|국제화|english|어학", re.I)

# 커뮤니티 정보: 네이버 검색 API(블로그·카페글). developers.naver.com 에서 발급
NAVER_ID = os.environ.get("NAVER_CLIENT_ID") or ""
NAVER_SECRET = os.environ.get("NAVER_CLIENT_SECRET") or ""
COMMUNITY_KM = 3.5  # 집에서 이 거리 안의 유치원만 수집 (호출량 절약)

FACILITY_WORDS = {
    "수영장": r"수영장|수영 ?수업|생존수영",
    "실내 놀이터": r"실내 ?놀이터|실내 ?놀이실",
    "체육관·강당": r"체육관|체육실|강당|유희실",
    "텃밭": r"텃밭|농장|농작물",
    "숲 체험": r"숲 ?체험|숲 ?놀이|숲 ?유치원|숲교실",
    "생태 학습": r"생태",
    "도서실": r"도서실|도서관|책 ?놀이터",
    "요리실": r"요리실|쿠킹",
    "과학실": r"과학실|과학 ?놀이",
    "미술실": r"미술실|아틀리에",
    "음악실": r"음악실",
    "옥상·실외 놀이터": r"옥상 ?놀이터|실외 ?놀이터|바깥 ?놀이터|놀이터",
    "모래·물놀이": r"모래 ?놀이|물놀이",
    "원어민 영어": r"원어민",
    "CCTV 실시간": r"실시간 ?(CCTV|cctv|영상)|키즈노트|키즈 ?캠",
}
PRO_WORDS = re.compile(r"좋아|좋았|좋은|만족|추천|친절|꼼꼼|깨끗|넓|최고|따뜻|세심|잘 ?챙|즐거워|행복")
CON_WORDS = re.compile(r"아쉬|단점|불만|비싸|좁|멀어|힘들|별로|걱정|실망|불친절|대기")
FEE_RE = re.compile(r"(원비|교육비|학비|수업료|부모 ?부담금|한 ?달|월)\D{0,12}?(\d{2,3})\s*만\s*원?")

HERE = os.path.dirname(os.path.abspath(__file__))
GEO_CACHE = os.path.join(HERE, "geo_cache.json")


def get_json(url, headers=None, tries=5):
    # 유치원알리미 서버가 느리거나 가끔 연결이 끊겨서 넉넉히 기다리며 재시도
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=headers or {"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=60) as res:
                return json.loads(res.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                raise
            wait = 5 * 2 ** i
            print(f"  retry in {wait}s {url.split('?')[0]}: {e}", file=sys.stderr)
            time.sleep(wait)


def fetch(endpoint, region):
    qs = urllib.parse.urlencode({"key": API_KEY, "sidoCode": region["sido"], "sggCode": region["sgg"]})
    data = get_json(f"{API_BASE}{endpoint}.do?{qs}")
    items = data.get("kinderInfo") if isinstance(data, dict) else data
    if not isinstance(items, list):
        items = next((v for v in data.values() if isinstance(v, list)), []) if isinstance(data, dict) else []
    status = data.get("status") if isinstance(data, dict) else None
    print(f"  {region['name']} {endpoint}: {len(items)}건 (status={status})")
    if not items and endpoint == "basicInfo2" and status and str(status).upper() != "SUCCESS":
        raise SystemExit(f"API 오류: {region['name']} {status}")
    return items


def fetch_neis(extra):
    rows, page = [], 1
    while True:
        qs = urllib.parse.urlencode({"KEY": NEIS_KEY, "Type": "json", "pIndex": page, "pSize": 1000,
                                     "ATPT_OFCDC_SC_CODE": "J10", **extra})  # J10 = 경기도교육청
        data = get_json(f"{NEIS_URL}?{qs}")
        if "acaInsTiInfo" not in data:
            result = data.get("RESULT", {})
            if result.get("CODE") == "INFO-200":  # 데이터 없음
                break
            raise RuntimeError(f"NEIS 오류: {result}")
        head, body = data["acaInsTiInfo"][0]["head"], data["acaInsTiInfo"][1]["row"]
        rows.extend(body)
        total = head[0]["list_total_count"]
        print(f"  NEIS {extra or '전체'} {len(rows)}/{total}")
        if len(rows) >= total or not body:
            break
        page += 1
    return rows


def english_kindergartens():
    """수지·분당·기흥의 유아 대상 영어학원(영어유치원) 목록. 학원 1곳에 과정이 여러 줄이라 묶어서 정리."""
    if not NEIS_KEY:
        print("NEIS_KEY 없음 → 영어유치원 건너뜀")
        return None
    rows = fetch_neis({"REALM_SC_NM": "국제화"}) or fetch_neis({})
    academies = {}
    for r in rows:
        addr = " ".join(x for x in (r.get("FA_RDNMA"), r.get("FA_RDNDA")) if x)
        gu = GU.search(addr or r.get("ADMST_ZONE_NM") or "")
        if not gu:
            continue
        if r.get("REG_STTUS_NM") and r["REG_STTUS_NM"] not in ("개원", "등록"):
            continue
        course = " ".join(str(r.get(k) or "") for k in ("LE_ORD_NM", "LE_CRSE_LIST_NM", "LE_CRSE_NM"))
        name = str(r.get("ACA_NM") or "")
        if not ENGLISH.search(course + name + str(r.get("REALM_SC_NM") or "")):
            continue
        by_course, by_name = bool(YOUNG.search(course)), bool(YOUNG.search(name))
        if not (by_course or by_name):
            continue
        key = r.get("ACA_ASNUM") or name + addr
        a = academies.setdefault(key, {
            "id": key, "name": name, "addr": addr, "region": gu.group(1),
            "tel": r.get("FA_TELNO") or r.get("TELNO") or "", "cap": r.get("TOFOR_SMTOT"),
            "courses": [], "fees": [], "basis": "이름으로 추정", "branch": r.get("BRHS_ACA_YN"),
            "estbl": r.get("ESTBL_YMD") or r.get("REG_YMD") or "",
        })
        if r.get("LE_CRSE_NM") and r["LE_CRSE_NM"] not in a["courses"]:
            a["courses"].append(r["LE_CRSE_NM"])
        fee = r.get("PSNBY_THCC_CNTNT")
        if fee and fee not in a["fees"]:
            a["fees"].append(fee)
        if by_course:
            a["basis"] = "유아 과정 등록"
    out = list(academies.values())
    print(f"영어유치원 후보 {len(out)}곳")
    return out


def strip_tags(t):
    return re.sub(r"<[^>]+>", "", t or "").replace("&quot;", '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")


def naver(kind, query, display=10):
    qs = urllib.parse.urlencode({"query": query, "display": display, "sort": "sim"})
    data = get_json(f"https://openapi.naver.com/v1/search/{kind}.json?{qs}",
                    headers={"X-Naver-Client-Id": NAVER_ID, "X-Naver-Client-Secret": NAVER_SECRET}, tries=3)
    time.sleep(0.15)
    return data.get("items", [])


def community(k):
    """블로그·카페 검색 결과 요약: 원비 언급, 좋은 점/아쉬운 점 문장, 시설 키워드. 모든 항목에 출처 링크."""
    name = str(k.get("kindername") or k.get("name") or "")
    area = "수지" if "수지" in str(k.get("addr")) else "분당" if "분당" in str(k.get("addr")) else ""
    posts, seen = [], set()
    for kind, q in (("blog", f"{name} 원비"), ("blog", f"{name} {area} 후기"), ("cafearticle", f"{name} {area}")):
        try:
            for it in naver(kind, q):
                link = it.get("link")
                text = strip_tags(it.get("title")) + " · " + strip_tags(it.get("description"))
                if link in seen or name.replace("유치원", "")[:3] not in text:
                    continue  # 다른 유치원 글은 제외
                seen.add(link)
                posts.append({"text": text, "link": link, "date": it.get("postdate", ""), "kind": kind})
        except Exception as e:  # noqa: BLE001
            print(f"  네이버 검색 실패 {q}: {e}", file=sys.stderr)
    fees, pros, cons, facs = [], [], [], {}
    for p in posts:
        for m in FEE_RE.finditer(p["text"]):
            v = int(m.group(2))
            if 10 <= v <= 300:  # 월 10만~300만원 범위만 원비로 봄
                fees.append({"won": v * 10000, "text": p["text"][max(0, m.start() - 25):m.end() + 15], "link": p["link"], "date": p["date"]})
        for sent in re.split(r"[.!?…\n]", p["text"]):
            sent = sent.strip()
            if len(sent) < 8:
                continue
            if CON_WORDS.search(sent) and len(cons) < 4:
                cons.append({"text": sent[:90], "link": p["link"], "date": p["date"]})
            elif PRO_WORDS.search(sent) and len(pros) < 4:
                pros.append({"text": sent[:90], "link": p["link"], "date": p["date"]})
        for label, pat in FACILITY_WORDS.items():
            if re.search(pat, p["text"]) and label not in facs:
                facs[label] = {"source": "커뮤니티", "link": p["link"]}
    return {"posts": len(posts), "fees": fees[:6], "pros": pros, "cons": cons, "facilities": facs}


# 사건·사고 보도: 구글 뉴스 RSS (키 필요 없음)
NEWS_KM = 7
INCIDENT = re.compile(r"학대|폭행|폭언|방임|사고|부상|다쳐|숨져|사망|식중독|집단 ?감염|화재|추락|질식|"
                      r"행정처분|과태료|과징금|비리|횡령|유용|불법|위반|폐원|수사|입건|송치|고소|고발|기소|징역|벌금")
LOCAL = re.compile(r"용인|성남|수지|분당|기흥|동천|판교|죽전")


def incident_news(k):
    name = str(k.get("kindername") or k.get("name") or "")
    city = "용인" if "용인" in str(k.get("addr")) else "성남" if "성남" in str(k.get("addr")) else ""
    qs = urllib.parse.urlencode({"q": f'"{name}" {city}', "hl": "ko", "gl": "KR", "ceid": "KR:ko"})
    try:
        req = urllib.request.Request(f"https://news.google.com/rss/search?{qs}", headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=20) as res:
            root = ET.fromstring(res.read())
    except Exception as e:  # noqa: BLE001
        print(f"  뉴스 검색 실패 {name}: {e}", file=sys.stderr)
        return []
    finally:
        time.sleep(1.0)
    out, key = [], name.replace(" ", "")
    for it in root.iter("item"):
        title = (it.findtext("title") or "").strip()
        desc = strip_tags(it.findtext("description") or "")
        if key not in title.replace(" ", "") or not INCIDENT.search(title):
            continue  # 제목에 유치원 이름과 사건 단어가 같이 있어야 함
        if not LOCAL.search(title + " " + desc):
            continue  # 다른 지역 동명 유치원 기사 제외
        try:
            date = parsedate_to_datetime(it.findtext("pubDate")).strftime("%Y.%m.%d")
        except Exception:  # noqa: BLE001
            date = ""
        src = it.find("source")
        out.append({"title": title, "link": it.findtext("link"), "date": date, "source": src.text if src is not None else ""})
    return out[:6]


# 교육청 공식 공개: 경기도교육청 '감사결과공개' 게시판 (사립유치원 감사결과·위반 공표 실명 게시)
GOE_BOARD = "https://www.goe.go.kr/goe/na/ntt/selectNttList.do?mi=10275&bbsId=1965"


def goe_audit_posts(max_pages=40):
    posts, seen = [], set()
    for page in range(1, max_pages + 1):
        try:
            req = urllib.request.Request(f"{GOE_BOARD}&currPage={page}", headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=30) as res:
                html = res.read().decode("utf-8", "ignore")
        except Exception as e:  # noqa: BLE001
            print(f"  교육청 게시판 {page}쪽 실패: {e}", file=sys.stderr)
            break
        found = 0
        for m in re.finditer(r'<a[^>]+(?:href="([^"]*selectNttInfo[^"]*)"|data-id="(\d+)")[^>]*>(.*?)</a>', html, re.S):
            title = re.sub(r"\s+", " ", strip_tags(m.group(3))).strip()
            key = m.group(1) or m.group(2)
            if not title or key in seen:
                continue
            seen.add(key)
            href = m.group(1)
            link = urllib.parse.urljoin(GOE_BOARD, href.replace("&amp;", "&")) if href else GOE_BOARD
            posts.append({"title": title, "link": link})
            found += 1
        if page == 1:
            print(f"  교육청 게시판 1쪽 예시: {[p['title'] for p in posts[:5]]}")
        if not found:
            break
        time.sleep(0.5)
    print(f"교육청 감사결과 게시글 {len(posts)}건 수집")
    return posts


def homepage_facilities(url):
    """유치원 홈페이지 첫 화면에서 시설 키워드 찾기."""
    if not url:
        return {}
    url = url.strip()
    if not url.startswith("http"):
        url = "http://" + url
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as res:
            raw = res.read(600_000)
        text = raw.decode("utf-8", "ignore")
        if "�" in text[:2000]:
            text = raw.decode("euc-kr", "ignore")
    except Exception:  # noqa: BLE001
        return {}
    text = re.sub(r"<script.*?</script>|<style.*?</style>", " ", text, flags=re.S)
    text = strip_tags(text)
    return {label: {"source": "홈페이지", "link": url} for label, pat in FACILITY_WORDS.items() if re.search(pat, text)}


def km(a, b):
    r = math.pi / 180
    h = math.sin((b[0] - a[0]) * r / 2) ** 2 + math.cos(a[0] * r) * math.cos(b[0] * r) * math.sin((b[1] - a[1]) * r / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def geocode(addr, cache):
    """OpenStreetMap Nominatim으로 주소 → 좌표 (1초 1건, 결과는 geo_cache.json에 저장)."""
    query = re.sub(r"\(.*?\)", "", addr)
    query = re.sub(r",.*$", "", query).strip()
    if query in cache:
        return cache[query]
    qs = urllib.parse.urlencode({"q": query, "format": "json", "limit": 1, "countrycodes": "kr"})
    point = None
    try:
        res = get_json(f"https://nominatim.openstreetmap.org/search?{qs}",
                       headers={"User-Agent": "hayoon-kindergarten-finder/1.0 (github.com/bluezone444/Image)"}, tries=2)
        if res:
            p = (float(res[0]["lat"]), float(res[0]["lon"]))
            if km(HOME, p) < 30:  # 엉뚱한 지역으로 잡힌 결과는 버림
                point = p
    except Exception as e:  # noqa: BLE001
        print(f"  geocode 실패 {query}: {e}", file=sys.stderr)
        return None  # 실패는 캐시하지 않아 다음 실행 때 재시도
    cache[query] = point
    time.sleep(1.1)
    return point


# 통학 예상시간: OSRM(오픈스트리트맵 도로망) — 실시간 교통은 반영 안 됨
ROUTERS = {
    "car": "https://router.project-osrm.org/table/v1/driving/",
    "walk": "https://routing.openstreetmap.de/routed-foot/table/v1/foot/",
}


def commute_times(points):
    for mode, base in ROUTERS.items():
        done = 0
        for i in range(0, len(points), 80):  # URL 길이 제한 때문에 나눠서 요청
            chunk = points[i:i + 80]
            coords = ";".join([f"{HOME[1]},{HOME[0]}"] + [f"{k['_lng']},{k['_lat']}" for k in chunk])
            url = f"{base}{coords}?sources=0&annotations=duration,distance"
            try:
                res = get_json(url, headers={"User-Agent": "hayoon-kindergarten-finder/1.0"}, tries=3)
            except Exception as e:  # noqa: BLE001
                print(f"  {mode} 경로 계산 실패: {e}", file=sys.stderr)
                continue
            durs, dists = res.get("durations", [[]])[0], res.get("distances", [[]])[0]
            for j, k in enumerate(chunk, start=1):
                if j < len(durs) and durs[j] is not None:
                    k[f"_{mode}_min"] = round(durs[j] / 60, 1)
                    if j < len(dists) and dists[j] is not None:
                        k[f"_{mode}_km"] = round(dists[j] / 1000, 2)
                    done += 1
            time.sleep(1.5)
        print(f"통학시간({mode}) {done}/{len(points)}")


def locate_home(cache):
    global HOME
    for q in HOME_QUERIES:
        qs = urllib.parse.urlencode({"q": q, "format": "json", "limit": 1, "countrycodes": "kr"})
        try:
            res = get_json(f"https://nominatim.openstreetmap.org/search?{qs}",
                           headers={"User-Agent": "hayoon-kindergarten-finder/1.0 (github.com/bluezone444/Image)"}, tries=2)
        except Exception as e:  # noqa: BLE001
            print(f"  집 좌표 검색 실패 {q}: {e}", file=sys.stderr)
            res = None
        time.sleep(1.1)
        if res:
            p = (float(res[0]["lat"]), float(res[0]["lon"]))
            if km(HOME, p) < 3:  # 동천동 근처로 잡혔을 때만 사용
                print(f"집 좌표 확인: {q} → {p}")
                HOME = p
                return True
    print(f"집 좌표 검색 실패 → 대략값 {HOME} 사용")
    return False


# ---------- 엄마(안다빈) 픽 4곳 집중 수집 ----------
DEEP_TARGETS = [
    {"key": "daycare", "names": ["동천센트럴자이", "동천자이", "센트럴자이"], "home": None,
     "childcare": ["41460000477", "41460000475"],  # 어린이집 정보공개포털 코드 (시립동천자이2차 등)
     "queries": ["시립동천자이2차어린이집", "동천센트럴자이 어린이집"]},
    {"key": "poly", "names": ["폴리", "POLY", "Poly"], "home": None,
     "neis": "폴리", "smileus": ["폴리어학원", "수지폴리어학원"],
     "queries": ["수지 폴리어학원 유치부", "폴리 킨더 수지 원비", "폴리어학원 수지 후기"]},
    {"key": "sungmin", "names": ["성민프리미어", "성민 프리미어"], "home": None,
     "neis": "성민프리미어", "smileus": ["성민프리미어학원"],
     "queries": ["성민프리미어학원 수지", "성민프리미어스쿨 원비", "성민프리미어스쿨 후기"]},
    {"key": "besti", "names": ["베스아이", "best-i", "BEST-I"], "home": "http://best-i.kg.kr",
     "queries": ["베스아이유치원", "베스아이유치원 수지 원비", "베스아이유치원 후기"]},
]
TOPICS = {
    "비용": r"원비|교육비|수업료|수강료|부모 ?부담|납부|입학금|등록금|재료비|교재비|차량비|만 ?원",
    "시간": r"등원|하원|운영 ?시간|일과|연장|종일|\d{1,2}[:시]\s?\d{0,2}",
    "차량": r"차량|셔틀|노선|운행|탑승",
    "프로그램": r"특성화|프로그램|커리큘럼|교육과정|영어|체육|미술|음악|숲|코딩|발레|수영|원어민|몬테소리|프로젝트|놀이 ?중심|리딩|파닉스",
    "모집·설명회": r"설명회|모집|입학|입소|대기|처음학교로|상담|견학",
    "시설": r"수영장|놀이터|강당|체육관|텃밭|도서|교실|실내|옥상|정원|시설",
    "교사": r"교사|선생님|원장|담임|원어민|자격",
    "후기": r"좋아|만족|추천|아쉬|단점|불만|친절|꼼꼼|비싸",
}
DEBUG = "--deep-only" in sys.argv
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
      "Accept-Language": "ko-KR,ko;q=0.9"}


def fetch_text(url, limit=800_000):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as res:
        ctype = res.headers.get("Content-Type", "")
        if "pdf" in ctype or "image" in ctype:
            return "", ""
        raw = res.read(limit)
    html = raw.decode("utf-8", "ignore")
    if html.count("�") > 20:
        html = raw.decode("euc-kr", "ignore")
    return html, res.geturl()


def page_text(html):
    html = re.sub(r"<script.*?</script>|<style.*?</style>|<noscript.*?</noscript>", " ", html, flags=re.S | re.I)
    html = re.sub(r"<(br|p|div|li|tr|h\d)[^>]*>", "\n", html, flags=re.I)
    return re.sub(r"[ \t\r\f\v]+", " ", strip_tags(html))


def web_search(query):
    """Bing·DuckDuckGo 검색 결과 링크 (키 없이)."""
    links = []
    try:
        html, _ = fetch_text("https://www.bing.com/search?" + urllib.parse.urlencode({"q": query, "setlang": "ko", "cc": "KR"}))
        links += re.findall(r'<li class="b_algo".*?<a[^>]+href="(https?://[^"]+)"', html, re.S)
    except Exception as e:  # noqa: BLE001
        print(f"  bing 실패 {query}: {e}", file=sys.stderr)
    time.sleep(6.0)  # 검색엔진이 빠른 연속 요청을 막아서 천천히
    try:
        html, _ = fetch_text("https://html.duckduckgo.com/html/?" + urllib.parse.urlencode({"q": query, "kl": "kr-kr"}))
        for u in re.findall(r'class="result__a"[^>]+href="([^"]+)"', html):
            m = re.search(r"uddg=([^&]+)", u)
            links.append(urllib.parse.unquote(m.group(1)) if m else u)
    except Exception as e:  # noqa: BLE001
        print(f"  ddg 실패 {query}: {e}", file=sys.stderr)
    time.sleep(6.0)
    out = []
    for u in links:
        u = u.replace("&amp;", "&")
        if "blog.naver.com" in u and "m.blog" not in u:
            u = u.replace("://blog.naver.com", "://m.blog.naver.com")
        if u not in out and not re.search(r"bing\.com|duckduckgo|microsoft|youtube\.com|\.pdf$", u):
            out.append(u)
    return out


def homepage_pages(home):
    pages = [home]
    try:
        html, base = fetch_text(home)
        # 빈 껍데기 페이지는 meta refresh·자바스크립트 이동 주소를 따라감
        for _ in range(3):
            if len(page_text(html)) > 300:
                break
            m = (re.search(r'http-equiv=["\']?refresh["\']?[^>]*url=([^"\'>\s]+)', html, re.I)
                 or re.search(r'location(?:\.href)?\s*=\s*["\']([^"\']+)["\']', html)
                 or re.search(r'location\.replace\(["\']([^"\']+)["\']', html))
            if not m:
                break
            nxt = urllib.parse.urljoin(base, m.group(1))
            if DEBUG:
                print(f"  [이동] {base} → {nxt}")
            html, base = fetch_text(nxt)
            pages = [base]
    except Exception as e:  # noqa: BLE001
        print(f"  홈페이지 실패 {home}: {e}")
        return []
    if DEBUG and len(page_text(html)) < 300:
        print(f"  [홈 원문 앞부분] {html[:400]!r}")
    # 프레임·아이프레임으로 만든 홈페이지는 안쪽 페이지를 따라감
    for src in re.findall(r'<i?frame[^>]+src="([^"]+)"', html, re.I):
        u = urllib.parse.urljoin(base, src.replace("&amp;", "&"))
        if u not in pages:
            pages.append(u)
            try:
                inner, _ = fetch_text(u)
                html += inner
            except Exception:  # noqa: BLE001
                pass
    if DEBUG:
        print(f"  [홈] {home} → {base} html {len(html)}자, 텍스트 {len(page_text(html))}자, 프레임 {len(pages) - 1}")
    for href, text in re.findall(r'<a[^>]+href="([^"#]+)"[^>]*>(.*?)</a>', html, re.S):
        t = strip_tags(text)
        if re.search(r"소개|교육|프로그램|일과|모집|입학|시설|안내|오시는|특성화|방과후|차량|공지|원비|교육비|하루", t):
            u = urllib.parse.urljoin(base, href.replace("&amp;", "&"))
            if urllib.parse.urlparse(u).netloc == urllib.parse.urlparse(base).netloc and u not in pages:
                pages.append(u)
    return pages[:14]


def childcare_official(code):
    """어린이집 정보공개포털 공개 페이지(요약·기본현황·교직원 등)를 읽어 줄 단위로 정리."""
    base = "https://info.childcare.go.kr/info_html5/pnis/search/preview/"
    first = f"{base}SummaryInfoSlPu.jsp?flag=YJ&STCODE_POP={code}"
    out, seen = [], set()
    try:
        html, _ = fetch_text(first)
    except Exception as e:  # noqa: BLE001
        print(f"  어린이집 포털 실패 {code}: {e}")
        return []
    urls = [first] + [urllib.parse.urljoin(first, h.replace("&amp;", "&"))
                      for h in re.findall(r'href="([^"]*SlPu\.jsp[^"]*)"', html)]
    urls += [f"{base}{p}?flag={f}&STCODE_POP={code}" for p, f in
             (("BasisPresentConditionSlPu.jsp", "JH"), ("ChildStaffSlPu.jsp", "BG"), ("ClassInfoSlPu.jsp", "BG"))]
    for u in urls[:10]:
        if u in seen:
            continue
        seen.add(u)
        try:
            h, final = fetch_text(u) if u != first else (html, first)
        except Exception:  # noqa: BLE001
            continue
        title = strip_tags((re.search(r"<title[^>]*>(.*?)</title>", h, re.S | re.I) or [None, ""])[1]).strip()
        lines = [ln.strip() for ln in page_text(h).split("\n") if 2 <= len(ln.strip()) <= 120]
        lines = [ln for i, ln in enumerate(lines) if ln not in lines[:i]][:80]
        if DEBUG:
            print(f"  [포털] {u[-60:]} 제목 {title[:30]} 줄 {len(lines)}: {' / '.join(lines[:25])[:600]}")
        if len(lines) > 5:
            out.append({"url": final or u, "title": title or "어린이집 정보공개포털", "lines": lines})
        time.sleep(0.5)
    return out


NEIS_FIELDS = {"ACA_NM": "학원명", "FA_RDNMA": "주소", "FA_RDNDA": "상세주소", "REG_STTUS_NM": "상태", "ESTBL_YMD": "설립일",
               "REG_YMD": "등록일", "TOFOR_SMTOT": "정원", "DTM_RCPTN_ABLTY_NMPR_SMTOT": "일시수용", "REALM_SC_NM": "분야",
               "LE_ORD_NM": "교습계열", "LE_CRSE_LIST_NM": "교습과정목록", "LE_CRSE_NM": "교습과정",
               "PSNBY_THCC_CNTNT": "1인당 수강료", "THCC_OTHBC_YN": "수강료 공개", "BRHS_ACA_YN": "기숙사"}


def neis_lookup(name):
    """교육청 학원 등록 정보(나이스)에서 이름으로 찾기 — 수지·분당만."""
    if not NEIS_KEY:
        return []
    try:
        rows = fetch_neis({"ACA_NM": name})
    except Exception as e:  # noqa: BLE001
        print(f"  NEIS 조회 실패 {name}: {e}")
        return []
    out = []
    for r in rows:
        if not re.search(r"수지구|분당구", str(r.get("FA_RDNMA") or "") + str(r.get("ADMST_ZONE_NM") or "")):
            continue
        out.append({NEIS_FIELDS[k]: str(r.get(k)).strip() for k in NEIS_FIELDS if r.get(k) not in (None, "", " ")})
    if DEBUG:
        for o in out:
            print(f"  [NEIS] {o}")
    return out


def smileus_page(name):
    """학원 정보 사이트(smileus) 용인시 학원 상세: 교습비 등."""
    url = "https://education.smileus.net/" + "/".join(urllib.parse.quote(x) for x in ("학원", "경기", "용인시", "view", name))
    try:
        html, final = fetch_text(url)
    except Exception as e:  # noqa: BLE001
        print(f"  smileus 실패 {name}: {e}")
        return None
    lines = [ln.strip() for ln in page_text(html).split("\n") if 2 <= len(ln.strip()) <= 160]
    lines = [ln for i, ln in enumerate(lines) if ln not in lines[:i]]
    if DEBUG:
        print(f"  [smileus] {name} 줄 {len(lines)}: {' / '.join(lines[:120])[:3000]}")
    if not any(name[:4] in ln for ln in lines):
        return None
    return {"url": final or url, "title": f"{name} · 학원 정보(smileus)", "lines": lines[:150]}


def deep_collect(target):
    names = target["names"]
    found = {t: [] for t in TOPICS}
    sources, seen_sent = [], set()
    urls = (homepage_pages(target["home"]) if target["home"] else [])
    official = set(urls)
    for q in target["queries"]:
        res = web_search(q)
        if DEBUG:
            print(f"  [검색] {q} → {len(res)}건 {res[:4]}")
        for u in res[:6]:
            if u not in urls:
                urls.append(u)
    for u in urls[:40]:
        try:
            html, final = fetch_text(u)
        except Exception as e:  # noqa: BLE001
            if DEBUG:
                print(f"  [실패] {u[:90]} {e}")
            continue
        text = page_text(html)
        if DEBUG:
            print(f"  [페이지] {u[:90]} 텍스트 {len(text)}자 이름포함 {any(n in text for n in names)}")
        if u not in official and not any(n in text for n in names):
            continue  # 대상과 상관없는 페이지 제외
        title = strip_tags((re.search(r"<title[^>]*>(.*?)</title>", html, re.S | re.I) or [None, ""])[1]).strip()[:80]
        kind = "공식 홈페이지" if u in official else ("블로그" if "blog" in u else "카페" if "cafe" in u else "웹")
        hits = 0
        for sent in re.split(r"[\n。!?]|(?<=[다요음])\.\s", text):
            sent = sent.strip(" -·•|")
            if not (12 <= len(sent) <= 170) or sent in seen_sent:
                continue
            if u not in official and not any(n in sent for n in names) and not re.search(r"원비|하원|등원|차량|설명회|특성화|수영|원어민", sent):
                continue
            for topic, pat in TOPICS.items():
                if re.search(pat, sent) and len(found[topic]) < 10:
                    found[topic].append({"text": sent, "url": final or u, "kind": kind})
                    seen_sent.add(sent)
                    hits += 1
                    break
        if hits:
            sources.append({"url": final or u, "title": title or u, "kind": kind, "hits": hits})
        time.sleep(0.4)
    news = []
    for q in target["queries"][:2]:
        qs = urllib.parse.urlencode({"q": q, "hl": "ko", "gl": "KR", "ceid": "KR:ko"})
        try:
            req = urllib.request.Request(f"https://news.google.com/rss/search?{qs}", headers=UA)
            with urllib.request.urlopen(req, timeout=20) as res:
                root = ET.fromstring(res.read())
            for it in root.iter("item"):
                title = (it.findtext("title") or "").strip()
                if any(n in title for n in names) and title not in [n["title"] for n in news]:
                    try:
                        date = parsedate_to_datetime(it.findtext("pubDate")).strftime("%Y.%m.%d")
                    except Exception:  # noqa: BLE001
                        date = ""
                    news.append({"title": title, "link": it.findtext("link"), "date": date})
        except Exception as e:  # noqa: BLE001
            print(f"  뉴스 실패 {q}: {e}", file=sys.stderr)
        time.sleep(1.0)
    official_pages = []
    for code in target.get("childcare", []):
        official_pages += childcare_official(code)
    for nm in target.get("smileus", []):
        pg = smileus_page(nm)
        if pg:
            official_pages.append(pg)
    neis_rows = neis_lookup(target["neis"]) if target.get("neis") else []
    total = sum(len(v) for v in found.values())
    print(f"  집중 수집 {target['key']}: 페이지 {len(sources)}곳, 문장 {total}개, 뉴스 {len(news)}건")
    return {"facts": found, "sources": sources[:15], "news": news[:8], "official": official_pages, "neis": neis_rows,
            "collected": time.strftime("%Y-%m-%d", time.gmtime(time.time() + 9 * 3600))}


def main():
    cache = {}
    if os.path.exists(GEO_CACHE):
        with open(GEO_CACHE, encoding="utf-8") as f:
            cache = json.load(f)
    home_exact = locate_home(cache)

    items = []
    for region in REGIONS:
        base = fetch("basicInfo2", region)
        for field, endpoint in EXTRAS.items():
            try:
                by_code = {r.get("kindercode"): r for r in fetch(endpoint, region) if r.get("kindercode")}
            except Exception as e:  # noqa: BLE001  부가 정보는 실패해도 계속
                print(f"  {region['name']} {endpoint} 실패: {e}", file=sys.stderr)
                by_code = {}
            for k in base:
                if k.get("kindercode") in by_code:
                    k[field] = by_code[k["kindercode"]]
        for k in base:
            k["region"] = region["name"]
        items.extend(base)

    if not items:
        raise SystemExit("유치원 데이터가 비어 있어요")

    try:
        english = english_kindergartens()
    except Exception as e:  # noqa: BLE001  영어유치원이 실패해도 일반 유치원은 만든다
        print(f"영어유치원 수집 실패: {e}", file=sys.stderr)
        english = None

    found = 0
    for k in items + (english or []):
        p = None
        try:  # 유치원알리미 기본현황에 위도·경도가 들어 있음
            p = (float(k["lttdcdnt"]), float(k["lngtcdnt"])) if k.get("lttdcdnt") else None
        except (TypeError, ValueError):
            p = None
        if not p and k.get("addr"):
            p = geocode(str(k["addr"]), cache)
        if not p:  # 주소로 못 찾으면 이름으로 (OSM에 유치원 이름이 등록된 경우가 많음)
            name = str(k.get("kindername") or k.get("name") or "")
            p = geocode(f"{name} {k.get('region', '')}", cache) if name else None
        if p:
            k["_lat"], k["_lng"] = p
            k["_dist"] = round(km(HOME, p), 2)
            found += 1
    print(f"좌표 확인 {found}/{len(items) + len(english or [])}")
    commute_times([k for k in items + (english or []) if "_lat" in k])

    near = [k for k in items + (english or []) if k.get("_dist") is not None and k["_dist"] <= COMMUNITY_KM]
    print(f"홈페이지·커뮤니티 수집 대상 {len(near)}곳")
    for k in near:
        facs = homepage_facilities(k.get("hpaddr"))
        if NAVER_ID and NAVER_SECRET:
            c = community(k)
            for label, v in c["facilities"].items():
                facs.setdefault(label, v)
            k["_community"] = {kk: c[kk] for kk in ("posts", "fees", "pros", "cons")}
        if facs:
            k["_facilities"] = facs
    if not (NAVER_ID and NAVER_SECRET):
        print("NAVER_CLIENT_ID/SECRET 없음 → 커뮤니티 수집 건너뜀")

    audits = goe_audit_posts()
    if audits:
        hit = 0
        for k in items:  # 유치원 이름이 게시글 제목에 그대로 들어간 경우만
            key = str(k.get("kindername") or "").replace(" ", "")
            if len(key) < 4:
                continue
            rel = [a for a in audits if key in a["title"].replace(" ", "")]
            if rel:
                k["_audit"] = rel[:5]
                hit += 1
        print(f"교육청 감사·처분 공개 일치 {hit}곳")

    targets = [k for k in items + (english or []) if k.get("_dist") is not None and k["_dist"] <= NEWS_KM]
    flagged = 0
    for k in targets:
        news = incident_news(k)
        if news:
            k["_news"] = news
            flagged += 1
    print(f"사건·사고 보도 확인 {len(targets)}곳 중 {flagged}곳")

    with open(GEO_CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=0, sort_keys=True)

    deep = {}
    for t in DEEP_TARGETS:
        try:
            deep[t["key"]] = deep_collect(t)
        except Exception as e:  # noqa: BLE001
            print(f"  집중 수집 실패 {t['key']}: {e}", file=sys.stderr)

    payload = {"updated": time.strftime("%Y-%m-%d %H:%M", time.gmtime(time.time() + 9 * 3600)), "items": items, "english": english, "deep": deep,
               "home": {"lat": HOME[0], "lng": HOME[1], "label": HOME_NAME, "exact": home_exact}}
    with open(os.path.join(HERE, "data.json"), "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)

    with open(os.path.join(HERE, "index.html"), encoding="utf-8") as f:
        html = f.read()
    data_js = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")
    marker = "<script>\n(function(){"
    assert marker in html, "index.html 구조가 바뀌었어요"
    html = html.replace(marker, f"<script>window.KG_DATA = {data_js};</script>\n{marker}", 1)
    with open(os.path.join(HERE, "hayoon.html"), "w", encoding="utf-8") as f:
        f.write(html)
    print(f"hayoon.html 생성: 유치원 {len(items)}곳")


if __name__ == "__main__":
    if DEBUG:  # 4곳 집중 수집만 빠르게 점검
        only = [a.split("=", 1)[1] for a in sys.argv if a.startswith("--only=")]
        for t in DEEP_TARGETS:
            if only and t["key"] not in only[0].split(","):
                continue
            r = deep_collect(t)
            for topic, fs in r["facts"].items():
                for f in fs[:3]:
                    print(f"    {topic} | {f['text'][:100]} | {f['url'][:60]}")
    else:
        main()
