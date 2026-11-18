"""TVHTML5_SIMPLY 系 clientName 正确值重试（E 路 400 迭代，2026-11-18 RAK）。"""
import json, re, urllib.request, http.cookiejar

UA_DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
UA_TV = ("Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), "
         "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)")
VIDEO = "dQw4w9WgXcQ"

CJ = http.cookiejar.CookieJar()
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))


def http(url, data=None, headers=None, timeout=25):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        r = OPENER.open(req, timeout=timeout)
        return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


http("https://www.youtube.com/watch?v=" + VIDEO,
     headers={"User-Agent": UA_DESK, "Accept-Language": "en-US,en;q=0.9"})
st, home = http("https://www.youtube.com/",
                headers={"User-Agent": UA_DESK, "Accept-Language": "en-US,en;q=0.9"})
txt = home.decode("utf-8", "replace")
vis_m = re.search(r'"VISITOR_DATA":"([^"]+)"', txt)
VIS = vis_m.group(1) if vis_m else ""
COOKIES = "; ".join(f"{c.name}={c.value}" for c in CJ)
print(f"[warmup] home={st} vis={'OK' if VIS else 'NO'}")


def probe(tag, client_name, client_version, body_extra=None):
    body = {
        "context": {"client": {
            "clientName": client_name, "clientVersion": client_version,
            "hl": "en", "gl": "US", "visitorData": VIS,
        }},
        "videoId": VIDEO, "contentCheckOk": True, "racyCheckOk": True,
    }
    if body_extra:
        body.update(body_extra)
    hdr = {"Content-Type": "application/json", "User-Agent": UA_TV,
           "X-Goog-Visitor-Id": VIS, "Origin": "https://www.youtube.com",
           "Cookie": COOKIES}
    st, resp = http("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    data=json.dumps(body).encode(), headers=hdr)
    print(f"\n=== {tag} ===")
    print(f"  HTTP {st} len={len(resp)}")
    if st != 200:
        msg = resp.decode("utf-8", "replace")
        m = re.search(r'"message": "([^"]+)"', msg)
        print(f"  error: {m.group(1) if m else msg[:200]}")
        return
    d = json.loads(resp)
    if "error" in d:
        print(f"  api error: {d['error'].get('message', '')[:200]}")
        return
    ps = d.get("playabilityStatus", {})
    print(f"  status={ps.get('status')} reason={ps.get('reason') or '-'}")
    sd = d.get("streamingData") or {}
    pf, af = sd.get("formats") or [], sd.get("adaptiveFormats") or []
    heights = sorted({f.get("height") for f in af if f.get("height")})
    n_url = sum(1 for f in pf + af if f.get("url"))
    print(f"  progressive={len(pf)} adaptive={len(af)} 直链={n_url} "
          f"SABR={bool(sd.get('serverAbrStreamingUrl'))} 高度={heights}")
    for f in sorted(af, key=lambda x: -(x.get("height") or 0)):
        if f.get("url"):
            try:
                rq = urllib.request.Request(f["url"], headers={
                    "Range": "bytes=0-524287", "User-Agent": UA_TV})
                r2 = OPENER.open(rq, timeout=25)
                print(f"  >>> 实拉 itag{f.get('itag')} {f.get('qualityLabel')}: "
                      f"{len(r2.read())}B HTTP {r2.status} — OK")
            except urllib.error.HTTPError as e:
                print(f"  >>> 实拉 itag{f.get('itag')}: HTTP {e.code}")
            except Exception as e:
                print(f"  >>> 实拉 itag{f.get('itag')}: {e}")
            break


# yt-dlp tv_simply 系候选名(clientName 枚举字符串)
probe("E1: TVHTML5_SIMPLY", "TVHTML5_SIMPLY", "7.20241112.18.00")
probe("E2: TVHTML5_SIMPLY + embedUrl", "TVHTML5_SIMPLY", "7.20241112.18.00",
      body_extra={"thirdParty": {"embedUrl": "https://www.youtube.com/"}})
probe("E3: TVHTML5_SIMPLY(新版本串)", "TVHTML5_SIMPLY", "7.20260810.14.00")
