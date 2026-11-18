import json, re, urllib.request, http.cookiejar

UA_DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
UA_AND = "com.google.android.youtube/20.44.38 (Linux; U; Android 14) gzip"
VIDEO = "jNQXAC9IVRw"

CJ = http.cookiejar.CookieJar()
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))

def http(url, data=None, headers=None, timeout=20):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        r = OPENER.open(req, timeout=timeout)
        return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

# ---------- 路径 A: ANDROID_REEL + /reel/reel_item_watch (ReVanced 法) ----------
print("=== A: ANDROID_REEL /reel/reel_item_watch ===")
# 先用桌面会话拿 cookie + visitorData
st0, home0 = http("https://www.youtube.com/watch?v=" + VIDEO,
                  headers={"User-Agent": UA_DESK, "Accept-Language": "en-US,en;q=0.9"})
home0_txt = home0.decode("utf-8", "replace")
vis0_m = re.search(r'"VISITOR_DATA":"([^"]+)"', home0_txt)
vis0 = vis0_m.group(1) if vis0_m else ""
cookies0 = "; ".join(f"{c.name}={c.value}" for c in CJ)
print(f"  warmup watch={st0} cookies={len(cookies0)}B vis={'OK' if vis0 else 'NO'}")
print(f"  watch页 bot文本={'Sign in to confirm' in home0_txt}")

body = json.dumps({
    "context": {"client": {
        "clientName": "ANDROID", "clientVersion": "20.44.38",
        "androidSdkVersion": 34, "hl": "en", "gl": "US", "userAgent": UA_AND,
        "visitorData": vis0,
    }},
    "videoId": VIDEO, "contentCheckOk": True, "racyCheckOk": True,
}).encode()
for extra in ({}, {"X-Goog-Api-Format-Version": "1"}):
    hdr = {"Content-Type": "application/json", "User-Agent": UA_AND,
           "X-Goog-Visitor-Id": vis0, "Origin": "https://www.youtube.com",
           "Cookie": cookies0, **extra}
    st, resp = http("https://www.youtube.com/youtubei/v1/reel/reel_item_watch?prettyPrint=false",
                    data=body, headers=hdr)
    print(f"  HTTP {st} extra={extra or '{}'} len={len(resp)}")
    try:
        d = json.loads(resp)
    except Exception:
        print("   not json:", resp[:300]); continue
    if "playabilityStatus" not in d:
        print("   keys:", list(d.keys())[:20])
        print("   top status:", d.get("status"))
        print("   body:", json.dumps(d)[:400])
        continue
    ps = d.get("playabilityStatus", {})
    print(f"   status={ps.get('status')} reason={ps.get('reason','-')}")
    sd = d.get("streamingData", {})
    pf = sd.get("formats", []) or []
    af = sd.get("adaptiveFormats", []) or []
    sabr = bool(sd.get("serverAbrStreamingUrl"))
    pcs = d.get("playerConfig", {}) or {}
    mcr = (pcs.get("mediaCommonConfig") or {})
    urc = (mcr.get("mediaUstreamerRequestConfig") or {})
    ustream = bool(urc.get("videoPlaybackUstreamerConfig"))
    heights = sorted({f.get("height") for f in af if f.get("height")})
    n_url = sum(1 for f in pf + af if f.get("url"))
    print(f"   progressive={len(pf)} adaptive={len(af)} 直链={n_url} SABR基址={sabr} ustreamerCfg={ustream}")
    print(f"   高度阶梯={heights}")
    for f in pf:
        print(f"   混流 itag{f.get('itag')} {f.get('qualityLabel')} url={'YES' if f.get('url') else 'no'}")
    for f in pf:
        if f.get("url"):
            try:
                rq = urllib.request.Request(f["url"], headers={"Range": "bytes=0-524287", "User-Agent": UA_AND})
                r2 = OPENER.open(rq, timeout=25)
                chunk = r2.read()
                print(f"   >>> 实拉 itag{f['itag']} 512KB: got {len(chunk)} bytes HTTP {r2.status} — 可播!")
            except Exception as e:
                print(f"   >>> 实拉 itag{f['itag']} 失败: {e}")
    break

# ---------- 路径 B: WEB /player 带新鲜 visitorData ----------
print("=== B: WEB /player (fresh visitorData) ===")
st, home = http("https://www.youtube.com/", headers={"User-Agent": UA_DESK, "Accept-Language": "en-US,en;q=0.9"})
home_txt = home.decode("utf-8", "replace")
key_m = re.search(r'"INNERTUBE_API_KEY":"([^"]+)"', home_txt)
ver_m = re.search(r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"', home_txt)
vis_m = re.search(r'"VISITOR_DATA":"([^"]+)"', home_txt)
key = key_m.group(1) if key_m else None
ver = ver_m.group(1) if ver_m else None
vis = vis_m.group(1) if vis_m else None
print(f"  home={st} key={'OK' if key else 'NO'} ver={ver} vis={'OK' if vis else 'NO'}")
if key and ver:
    body = json.dumps({
        "context": {"client": {
            "clientName": "WEB", "clientVersion": ver, "hl": "en", "gl": "US",
            "visitorData": vis or "",
        }},
        "videoId": VIDEO, "contentCheckOk": True, "racyCheckOk": True,
    }).encode()
    hdr = {"Content-Type": "application/json", "User-Agent": UA_DESK,
           "Accept-Language": "en-US,en;q=0.9", "Origin": "https://www.youtube.com",
           "Referer": "https://www.youtube.com/", "X-Youtube-Client-Name": "1",
           "X-Youtube-Client-Version": ver,
           "X-Goog-Visitor-Id": vis or ""}
    st, resp = http(f"https://www.youtube.com/youtubei/v1/player?key={key}&prettyPrint=false",
                    data=body, headers=hdr)  # Cookie 经 OPENER 自动回传
    print(f"  HTTP {st} len={len(resp)}")
    if st == 200:
        d = json.loads(resp)
        ps = d.get("playabilityStatus", {})
        print(f"  status={ps.get('status')} reason={ps.get('reason','-')}")
        sd = d.get("streamingData", {})
        pf = sd.get("formats", []) or []
        af = sd.get("adaptiveFormats", []) or []
        sabr = bool(sd.get("serverAbrStreamingUrl"))
        pcs = d.get("playerConfig", {}) or {}
        mcr = (pcs.get("mediaCommonConfig") or {})
        urc = (mcr.get("mediaUstreamerRequestConfig") or {})
        ustream = bool(urc.get("videoPlaybackUstreamerConfig"))
        heights = sorted({f.get("height") for f in af if f.get("height")})
        itags = [f.get("itag") for f in af]
        n_url = sum(1 for f in pf + af if f.get("url") or f.get("signatureCipher"))
        bot = "Sign in to confirm" in json.dumps(ps)
        print(f"  bot={bot} progressive={len(pf)} adaptive={len(af)} 有url/cipher={n_url}")
        print(f"  SABR基址={sabr} ustreamerCfg={ustream}")
        print(f"  高度阶梯={heights}")
        print(f"  itags={itags}")
    else:
        print("  body:", resp[:300])

# ---------- 路径 C: ANDROID 直打 /player (无 web key) ----------
print("=== C: ANDROID /player (no key) ===")
body = json.dumps({
    "context": {"client": {
        "clientName": "ANDROID", "clientVersion": "20.44.38",
        "androidSdkVersion": 34, "hl": "en", "gl": "US",
        "visitorData": vis0,
    }},
    "videoId": VIDEO, "contentCheckOk": True, "racyCheckOk": True,
}).encode()
hdr = {"Content-Type": "application/json", "User-Agent": UA_AND,
       "X-Goog-Visitor-Id": vis0, "Origin": "https://www.youtube.com",
       "X-Youtube-Client-Name": "3", "X-Youtube-Client-Version": "20.44.38"}
st, resp = http("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                data=body, headers=hdr)
print(f"  HTTP {st} len={len(resp)}")
if st == 200:
    d = json.loads(resp)
    ps = d.get("playabilityStatus", {})
    print(f"  status={ps.get('status')} reason={ps.get('reason','-')}")
    sd = d.get("streamingData", {})
    pf = sd.get("formats", []) or []
    af = sd.get("adaptiveFormats", []) or []
    heights = sorted({f.get("height") for f in af if f.get("height")})
    n_url = sum(1 for f in pf + af if f.get("url"))
    print(f"  progressive={len(pf)} adaptive={len(af)} 直链={n_url} SABR={bool(sd.get('serverAbrStreamingUrl'))}")
    print(f"  高度阶梯={heights}")
    for f in pf[:3]:
        print(f"  混流 itag{f.get('itag')} {f.get('qualityLabel')} url={'YES' if f.get('url') else 'no'}")
    for f in pf:
        if f.get("url"):
            try:
                rq = urllib.request.Request(f["url"], headers={"Range": "bytes=0-524287", "User-Agent": UA_AND})
                r2 = OPENER.open(rq, timeout=25)
                chunk = r2.read()
                print(f"  >>> 实拉 itag{f['itag']} 512KB: {len(chunk)} bytes HTTP {r2.status} — 可播!")
            except Exception as e:
                print(f"  >>> 实拉 itag{f['itag']} 失败: {e}")
            break
else:
    print("  body:", resp[:300])

# ---------- 路径 D: ANDROID_VR /player (对照文档 LOGIN_REQUIRED 预期) ----------
print("=== D: ANDROID_VR /player ===")
body = json.dumps({
    "context": {"client": {
        "clientName": "ANDROID_VR", "clientVersion": "1.61.48",
        "androidSdkVersion": 34, "hl": "en", "gl": "US", "visitorData": vis0,
    }},
    "videoId": VIDEO, "contentCheckOk": True, "racyCheckOk": True,
}).encode()
hdr = {"Content-Type": "application/json",
       "User-Agent": "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 14) gzip",
       "X-Goog-Visitor-Id": vis0, "Origin": "https://www.youtube.com"}
st, resp = http("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                data=body, headers=hdr)
print(f"  HTTP {st} len={len(resp)}")
if st == 200:
    d = json.loads(resp)
    ps = d.get("playabilityStatus", {})
    print(f"  status={ps.get('status')} reason={ps.get('reason','-')}")
    sd = d.get("streamingData", {})
    print(f"  adaptive={len(sd.get('adaptiveFormats') or [])} 直链={sum(1 for f in (sd.get('formats') or []) + (sd.get('adaptiveFormats') or []) if f.get('url'))}")
else:
    print("  body:", resp[:300])
