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
}

# 영어유치원 = 법적으로 '유아 대상 영어학원' → 유치원알리미가 아닌 나이스(NEIS) 학원교습소정보에 있음
NEIS_KEY = os.environ.get("NEIS_KEY") or "9cfcc173b15f44768c52ad689224a185"
NEIS_URL = "https://open.neis.go.kr/hub/acaInsTiInfo"
GU = re.compile(r"(수지구|분당구|기흥구)")
YOUNG = re.compile(r"유아|유치|영유|킨더|kinder|키즈|kids|프리스쿨|pre-?school|토들러|toddler", re.I)
ENGLISH = re.compile(r"영어|외국어|국제화|english|어학", re.I)

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
