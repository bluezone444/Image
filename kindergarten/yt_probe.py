"""probe.txt의 yt:영상ID 줄 → 제목·설명·자막 출력 (점검용)"""
import json, os, re, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ids = [ln.strip()[3:] for ln in open(os.path.join(HERE, "probe.txt"), encoding="utf-8") if ln.startswith("yt:")]
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36", "Accept-Language": "ko-KR,ko;q=0.9"}


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode("utf-8", "replace")


for vid in ids:
    print(f"\n######## {vid}")
    try:
        print("oembed:", get(f"https://www.youtube.com/oembed?url=https://youtu.be/{vid}&format=json"))
    except Exception as e:  # noqa: BLE001
        print("oembed 실패", e)
    try:
        html = get(f"https://www.youtube.com/watch?v={vid}&hl=ko")
        m = re.search(r'"shortDescription":"(.*?)","isCrawlable', html)
        print("설명:", json.loads(f'"{m.group(1)}"') if m else "(없음)")
        d = re.search(r'"publishDate":"([^"]+)"', html); print("게시일:", d.group(1) if d else "")
        v = re.search(r'"viewCount":"(\d+)"', html); print("조회수:", v.group(1) if v else "")
    except Exception as e:  # noqa: BLE001
        print("watch 실패", e)
    try:
        from youtube_transcript_api import YouTubeTranscriptApi
        api = YouTubeTranscriptApi()
        tr = api.fetch(vid, languages=["ko", "en"])
        text = " ".join(s.text for s in tr)
        print(f"자막({len(text)}자):", text[:30000])
    except Exception as e:  # noqa: BLE001
        print("자막 실패", type(e).__name__, str(e)[:300])
