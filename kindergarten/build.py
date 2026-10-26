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

    with open(GEO_CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=0, sort_keys=True)

    payload = {"updated": time.strftime("%Y-%m-%d %H:%M", time.gmtime(time.time() + 9 * 3600)), "items": items, "english": english,
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
    main()
