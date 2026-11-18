"""候选 client 匿名探测（P11-342 后续，2026-11-18 RAK）。

E: TVHTML5_SIMPLY_PLAYER   —— ReVanced「TV Simply」形态，社区称 TV 系免 n-sig/(或免)poToken
F: TVHTML5_SIMPLY_EMBEDDED_PLAYER(+embedUrl) 复测 —— 探测矩阵曾判 ERROR，确认死活
G: VISIONOS 匿名(GAPIS)    —— 社区 2026-09 主推绕道；我们 NewPipe 主链已在用，看纯匿名给什么
H: IOS × attestation 视频  —— §5 验证清单第 3 条：URL 拿得到但拉流是否 403/RELOAD

用法: python3 yt_candidate_probe.py
"""
import json, re, urllib.request, http.cookiejar

UA_DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
UA_TV = ("Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), "
         "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)")
UA_IOS = "com.google.iphone.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)"
UA_VISIONOS = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)"

NORMAL_VIDEO = "dQw4w9WgXcQ"
ATTEST_VIDEO = "jNl6YkkzKxw"  # docs §5 指定的 attestation 视频

CJ = http.cookiejar.CookieJar()
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))


def http(url, data=None, headers=None, timeout=25):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        r = OPENER.open(req, timeout=timeout)
        return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def warmup():
    """桌面会话: watch + home -> cookie/visitorData。"""
    http("https://www.youtube.com/watch?v=" + NORMAL_VIDEO,
         headers={"User-Agent": UA_DESK, "Accept-Language": "en-US,en;q=0.9"})
    st, home = http("https://www.youtube.com/",
                    headers={"User-Agent": UA_DESK, "Accept-Language": "en-US,en;q=0.9"})
    txt = home.decode("utf-8", "replace")
    vis_m = re.search(r'"VISITOR_DATA":"([^"]+)"', txt)
    cookies = "; ".join(f"{c.name}={c.value}" for c in CJ)
    vis = vis_m.group(1) if vis_m else ""
    print(f"[warmup] home={st} cookies={len(cookies)}B vis={'OK' if vis else 'NO'}")
    return vis, cookies


VIS, COOKIES = warmup()


def probe(tag, video, client_name, client_version, ua,
          base="https://www.youtube.com", client_name_id=None,
          body_extra=None, pull=True):
    body = {
        "context": {"client": {
            "clientName": client_name, "clientVersion": client_version,
            "hl": "en", "gl": "US", "visitorData": VIS,
        }},
        "videoId": video, "contentCheckOk": True, "racyCheckOk": True,
    }
    if body_extra:
        body.update(body_extra)
    hdr = {
        "Content-Type": "application/json", "User-Agent": ua,
        "X-Goog-Visitor-Id": VIS, "Origin": "https://www.youtube.com",
        "Cookie": COOKIES,
    }
    if client_name_id:
        hdr["X-Youtube-Client-Name"] = client_name_id
        hdr["X-Youtube-Client-Version"] = client_version
    st, resp = http(f"{base}/youtubei/v1/player?prettyPrint=false",
                    data=json.dumps(body).encode(), headers=hdr)
    print(f"\n=== {tag} | video={video} ===")
    print(f"  HTTP {st} len={len(resp)}")
    if st != 200:
        print(f"  body: {resp[:300]}")
        return
    d = json.loads(resp)
    ps = d.get("playabilityStatus", {})
    reason = ps.get("reason") or (ps.get("errorReason") or "-")
    print(f"  status={ps.get('status')} reason={reason}")
    sd = d.get("streamingData") or {}
    pf, af = sd.get("formats") or [], sd.get("adaptiveFormats") or []
    heights = sorted({f.get("height") for f in af if f.get("height")})
    n_url = sum(1 for f in pf + af if f.get("url"))
    n_cipher = sum(1 for f in pf + af if f.get("signatureCipher"))
    sabr = bool(sd.get("serverAbrStreamingUrl"))
    hls = bool(sd.get("hlsManifestUrl"))
    print(f"  progressive={len(pf)} adaptive={len(af)} 直链={n_url} cipher={n_cipher} "
          f"SABR基址={sabr} hls={hls}")
    print(f"  高度阶梯={heights}")
    if pull:
        for f in sorted(af, key=lambda x: -(x.get("height") or 0)):
            if f.get("url"):
                label = f"itag{f.get('itag')} {f.get('qualityLabel')} {f.get('mimeType','')[:28]}"
                try:
                    rq = urllib.request.Request(f["url"], headers={
                        "Range": "bytes=0-524287", "User-Agent": ua})
                    r2 = OPENER.open(rq, timeout=25)
                    chunk = r2.read()
                    print(f"  >>> 实拉 {label}: {len(chunk)}B HTTP {r2.status} — OK")
                except urllib.error.HTTPError as e:
                    print(f"  >>> 实拉 {label}: HTTP {e.code} — {'403=门控!' if e.code == 403 else 'FAIL'}")
                except Exception as e:
                    print(f"  >>> 实拉 {label}: {e}")
                break  # 只拉最高档一条


# E: TVHTML5_SIMPLY_PLAYER —— ReVanced TV Simply 形态(无 token)
probe("E: TVHTML5_SIMPLY_PLAYER(无token)", NORMAL_VIDEO,
      "TVHTML5_SIMPLY_PLAYER", "7.20241112.18.00", UA_TV, client_name_id="85")

# F: TVHTML5_SIMPLY_EMBEDDED_PLAYER + embedUrl 复测(矩阵曾 ERROR)
probe("F: TVHTML5_SIMPLY_EMBEDDED(+embedUrl)", NORMAL_VIDEO,
      "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0.02.01", UA_TV, client_name_id="85",
      body_extra={"thirdParty": {"embedUrl": "https://www.youtube.com/"}})

# G: VISIONOS 匿名 —— GAPIS base, client_id=101, 纯匿名(合成 visitor)
probe("G: VISIONOS 匿名(GAPIS)", NORMAL_VIDEO,
      "VISIONOS", "1.02", UA_VISIONOS,
      base="https://www.googleapis.com", client_name_id="101")

# H: IOS × attestation 视频 + 普通视频对照 —— §5 验证清单第 3 条
probe("H1: IOS × attestation", ATTEST_VIDEO,
      "IOS", "20.10.4", UA_IOS, client_name_id="33")
probe("H2: IOS × 普通对照", NORMAL_VIDEO,
      "IOS", "20.10.4", UA_IOS, client_name_id="33")
