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
HOME = (37.3369, 127.0866)  # 용인시 수지구 동천동 행정복지센터 부근

REGIONS = [
    {"name": "수지구", "sido": "41", "sgg": "41465"},
    {"name": "분당구", "sido": "41", "sgg": "41135"},
    {"name": "기흥구", "sido": "41", "sgg": "41463"},
]
EXTRAS = {"_bus": "schoolBus", "_after": "afterSchoolPresent", "_meal": "schoolMeal"}

HERE = os.path.dirname(os.path.abspath(__file__))
GEO_CACHE = os.path.join(HERE, "geo_cache.json")


def get_json(url, headers=None, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=headers or {"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=30) as res:
                return json.loads(res.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                raise
            print(f"  retry {url.split('?')[0]}: {e}", file=sys.stderr)
            time.sleep(2 * (i + 1))


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


def main():
    cache = {}
    if os.path.exists(GEO_CACHE):
        with open(GEO_CACHE, encoding="utf-8") as f:
            cache = json.load(f)

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

    found = 0
    for k in items:
        p = geocode(str(k.get("addr") or ""), cache) if k.get("addr") else None
        if p:
            k["_lat"], k["_lng"] = p
            k["_dist"] = round(km(HOME, p), 2)
            found += 1
    print(f"좌표 확인 {found}/{len(items)}")

    with open(GEO_CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=0, sort_keys=True)

    payload = {"updated": time.strftime("%Y-%m-%d %H:%M", time.gmtime(time.time() + 9 * 3600)), "items": items}
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
