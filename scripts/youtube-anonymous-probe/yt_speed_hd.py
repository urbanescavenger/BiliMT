import json, re, sys, time, urllib.request, http.cookiejar
from concurrent.futures import ThreadPoolExecutor

VIDEO = sys.argv[1] if len(sys.argv) > 1 else "dQw4w9WgXcQ"
PARALLEL = 4

UA_DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
UA_CHROMECAST = "Mozilla/5.0 (CrKey armv7l 1.56.500000) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.218 Safari/537.36"
UA_IOS = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
UA_AND = "com.google.android.youtube/20.44.38 (Linux; U; Android 14) gzip"
UA_MWEB = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

CJ = http.cookiejar.CookieJar()
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))

def http(url, data=None, headers=None, timeout=20):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        r = OPENER.open(req, timeout=timeout)
        return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def player(client, ver, ua, extra_ctx=None, third=None, name_num=None):
    cli = {"clientName": client, "clientVersion": ver, "hl": "en", "gl": "US"}
    if VIS:
        cli["visitorData"] = VIS
    if extra_ctx:
        cli.update(extra_ctx)
    ctx = {"client": cli}
    if third:
        ctx["thirdParty"] = third
    body = json.dumps({"context": ctx, "videoId": VIDEO,
                       "contentCheckOk": True, "racyCheckOk": True}).encode()
    hdr = {"Content-Type": "application/json", "User-Agent": ua,
           "X-Goog-Visitor-Id": VIS, "Origin": "https://www.youtube.com"}
    if name_num:
        hdr["X-Youtube-Client-Name"] = str(name_num)
        hdr["X-Youtube-Client-Version"] = ver
    st, resp = http("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    data=body, headers=hdr)
    if st != 200:
        return {"err": f"HTTP {st}"}
    try:
        d = json.loads(resp)
    except Exception:
        return {"err": f"not json len={len(resp)}"}
    ps = d.get("playabilityStatus", {})
    sd = d.get("streamingData", {}) or {}
    pf = sd.get("formats", []) or []
    af = sd.get("adaptiveFormats", []) or []
    urls = [f for f in pf + af if f.get("url")]
    cip = [f for f in pf + af if f.get("signatureCipher") or f.get("cipher")]
    heights = sorted({f.get("height") for f in pf + af if f.get("height")})
    best = max(urls, key=lambda f: f.get("bitrate") or 0, default=None)
    return {"status": ps.get("status"), "reason": ps.get("reason", "-"),
            "pf": len(pf), "af": len(af), "n_url": len(urls), "n_cipher": len(cip),
            "heights": heights, "hls": sd.get("hlsManifestUrl"),
            "sabr": bool(sd.get("serverAbrStreamingUrl")),
            "best": best}

# warmup: 拿 cookie + visitorData
http("https://www.youtube.com/watch?v=" + VIDEO, headers={"User-Agent": UA_DESK})
st, home = http("https://www.youtube.com/", headers={"User-Agent": UA_DESK})
VIS_m = re.search(r'"VISITOR_DATA":"([^"]+)"', home.decode("utf-8", "replace"))
VIS = VIS_m.group(1) if VIS_m else ""

print(f"video={VIDEO}")
PROBES = [
    ("ANDROID", "20.44.38", UA_AND, None, None, 3),
    ("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", UA_CHROMECAST, None,
     {"embedUrl": "https://www.youtube.com/"}, 85),
    ("IOS", "20.10.4", UA_IOS, None, None, 5),
    ("MWEB", "2.20261116.01.00", UA_MWEB, None, None, 2),
    ("TVHTML5", "7.20241112.18.00", UA_CHROMECAST, None, None, 7),
]
results = {}
for name, ver, ua, ex, third, num in PROBES:
    r = player(name, ver, ua, ex, third, num)
    results[name] = r
    if "err" in r:
        print(f"  [{name}] {r['err']}")
        continue
    b = r["best"]
    print(f"  [{name}] status={r['status']} 混流={r['pf']} 分离={r['af']} 直链={r['n_url']} "
          f"cipher={r['n_cipher']} hls={'Y' if r['hls'] else 'n'} sabr={'Y' if r['sabr'] else 'n'} "
          f"heights={r['heights'][:8]}{'...' if len(r['heights'])>8 else ''}"
          + (f" | 最高直链 itag{b.get('itag')} {b.get('qualityLabel')} {b.get('bitrate')//1000}kbps" if b else ""))

# 选最高码率直链
all_best = [r["best"] for r in results.values() if isinstance(r, dict) and r.get("best")]
if not all_best:
    print("NO_DIRECT_URL from any client"); sys.exit(1)
src = max(all_best, key=lambda f: f.get("bitrate") or 0)
url = src["url"]
clen = src.get("contentLength")
print(f"\n>>> 采用: itag{src.get('itag')} {src.get('qualityLabel')} "
      f"{(src.get('bitrate') or 0)//1000}kbps {src.get('mimeType','')[:40]} clen={clen}")

def pull_once(u, secs=8):
    t0 = time.time(); got = 0
    r = OPENER.open(urllib.request.Request(u, headers={"User-Agent": UA_AND, "Connection": "close"}), timeout=15)
    while time.time() - t0 < secs:
        chunk = r.read(65536)
        if not chunk: break
        got += len(chunk)
    r.close()
    return got, time.time() - t0

def show(tag, n, s):
    print(f"  {tag}: {n/1e6:.2f} MB in {s:.2f}s = {n/1e6/s:.2f} MB/s ({n*8/s/1e6:.1f} Mbps)")

print("--- 单连接 x3 (8s 上限) ---")
for i in range(3):
    n, s = pull_once(url)
    show(f"#{i+1}", n, s)
    time.sleep(0.3)

print(f"--- {PARALLEL} 并发 x2 轮 ---")
for rnd in range(2):
    t0 = time.time()
    def work(_):
        try: return pull_once(url, secs=8)[0]
        except Exception as e:
            print("  err:", e); return 0
    with ThreadPoolExecutor(PARALLEL) as ex:
        tot = sum(ex.map(work, range(PARALLEL)))
    wall = time.time() - t0
    show(f"轮{rnd+1}", tot, wall)

# HLS 分支
hls = next((r["hls"] for r in results.values() if isinstance(r, dict) and r.get("hls")), None)
if hls:
    print("--- HLS 变体 ---")
    st, master = http(hls, headers={"User-Agent": UA_DESK})
    lines = master.decode("utf-8", "replace").splitlines()
    best_uri, best_bw, best_res = None, 0, ""
    for i, ln in enumerate(lines):
        if ln.startswith("#EXT-X-STREAM-INF") and "BANDWIDTH=" in ln:
            bw = int(re.search(r"BANDWIDTH=(\d+)", ln).group(1))
            res_m = re.search(r"RESOLUTION=(\d+x\d+)", ln)
            res = res_m.group(1) if res_m else ""
            for j in range(i + 1, len(lines)):
                if lines[j] and not lines[j].startswith("#"):
                    if bw > best_bw: best_bw, best_uri, best_res = bw, lines[j], res
                    break
    print(f"  最高变体 {best_res} bandwidth={best_bw}")
    if best_uri:
        st, pl = http(best_uri, headers={"User-Agent": UA_DESK})
        segs = [l for l in pl.decode("utf-8","replace").splitlines() if l and not l.startswith("#")][:8]
        t0 = time.time()
        def seg(su):
            try:
                _, b = http(su, headers={"User-Agent": UA_DESK}); return len(b)
            except Exception: return 0
        with ThreadPoolExecutor(len(segs) or 1) as ex:
            tot = sum(ex.map(seg, segs))
        wall = time.time() - t0
        show(f"{len(segs)}段并发", tot, wall)
