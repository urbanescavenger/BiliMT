import json, re, sys, time, urllib.request, http.cookiejar
from concurrent.futures import ThreadPoolExecutor

VIDEO = sys.argv[1] if len(sys.argv) > 1 else "aqz-KE-bpKQ"
PULL_SECS = 8          # 每轮拉流持续秒数
PARALLEL = 4           # 并发连接数

UA_DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
UA_AND = "com.google.android.youtube/20.44.38 (Linux; U; Android 14) gzip"

CJ = http.cookiejar.CookieJar()
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))

def http(url, data=None, headers=None, timeout=20):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        r = OPENER.open(req, timeout=timeout)
        return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)

# 1) 拿流地址
print(f"video={VIDEO}")
http("https://www.youtube.com/watch?v=" + VIDEO,
     headers={"User-Agent": UA_DESK, "Accept-Language": "en-US,en;q=0.9"})
vis_m = None
st, home, _ = http("https://www.youtube.com/", headers={"User-Agent": UA_DESK})
vis_m = re.search(r'"VISITOR_DATA":"([^"]+)"', home.decode("utf-8", "replace"))
vis = vis_m.group(1) if vis_m else ""

body = json.dumps({
    "context": {"client": {
        "clientName": "ANDROID", "clientVersion": "20.44.38",
        "androidSdkVersion": 34, "hl": "en", "gl": "US", "visitorData": vis,
    }},
    "videoId": VIDEO, "contentCheckOk": True, "racyCheckOk": True,
}).encode()
st, resp, _ = http("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                   data=body, headers={"Content-Type": "application/json", "User-Agent": UA_AND,
                                       "X-Goog-Visitor-Id": vis, "Origin": "https://www.youtube.com"})
d = json.loads(resp)
ps = d.get("playabilityStatus", {})
print(f"status={ps.get('status')}")
sd = d.get("streamingData", {})
pf = sd.get("formats", []) or []
af = sd.get("adaptiveFormats", []) or []
hls = sd.get("hlsManifestUrl")
print(f"progressive={len(pf)} adaptive={len(af)} hls={'YES' if hls else 'no'} sabr={bool(sd.get('serverAbrStreamingUrl'))}")

# 直链候选: adaptive 里有 url 的(通常无) > 混流 itag18
cands = [f for f in af if f.get("url")]
best = max(cands, key=lambda f: f.get("bitrate") or 0, default=None)
src = best or next((f for f in pf if f.get("url")), None)
if not src:
    print("NO_DIRECT_URL"); sys.exit(1)
url = src["url"]
clen = None
sth, _, hh = http(url + ("&" if "?" in url else "?") + "range=0-1",
                  headers={"User-Agent": UA_AND, "Range": "bytes=0-1"})
# contentLength 从 format 字段拿
clen = src.get("contentLength")
print(f"测试源: itag{src.get('itag')} {src.get('qualityLabel') or '-'} bitrate={src.get('bitrate')} contentLength={clen}")

def pull_once(u, start=None, nbytes=None, secs=PULL_SECS):
    headers = {"User-Agent": UA_AND, "Connection": "close"}
    if start is not None:
        end = start + (nbytes or 16 * 1024 * 1024) - 1
        if clen:
            end = min(end, int(clen) - 1)
        headers["Range"] = f"bytes={start}-{end}"
    t0 = time.time()
    got = 0
    r = OPENER.open(urllib.request.Request(u, headers=headers), timeout=15)
    while True:
        if time.time() - t0 >= secs:
            break
        if nbytes and got >= nbytes:
            break
        chunk = r.read(65536)
        if not chunk:
            break
        got += len(chunk)
    r.close()
    return got, time.time() - t0

def mbps(nbytes, secs):
    return nbytes * 8 / secs / 1e6

# 2) 单连接 x3 轮
print("--- 单连接 x3 ---")
for i in range(3):
    n, s = pull_once(url)
    print(f"  #{i+1} {n/1e6:.2f} MB in {s:.2f}s = {n/1e6/s:.2f} MB/s ({mbps(n,s):.1f} Mbps)")
    time.sleep(0.5)

# 3) 并发: 每 worker 重复整文件拉 (文件小, 用次数换负载), 2 轮
print(f"--- {PARALLEL} 并发整文件 x2 轮 ---")
for rnd in range(2):
    t0 = time.time()
    def work(_):
        try:
            return pull_once(url, secs=PULL_SECS * 2)[0]
        except Exception as e:
            print("  worker err:", e); return 0
    with ThreadPoolExecutor(PARALLEL) as ex:
        totals = list(ex.map(work, range(PARALLEL)))
    wall = time.time() - t0
    tot = sum(totals)
    print(f"  轮{rnd+1}: {tot/1e6:.2f} MB in {wall:.2f}s = {tot/1e6/wall:.2f} MB/s ({mbps(tot,wall):.1f} Mbps)")

# 4) HLS(若有) — 找最高档变体拉几段
if hls:
    print("--- HLS 变体 ---")
    st, master, _ = http(hls, headers={"User-Agent": UA_DESK})
    lines = master.decode("utf-8", "replace").splitlines()
    best_uri, best_bw = None, 0
    for i, ln in enumerate(lines):
        if ln.startswith("#EXT-X-STREAM-INF") and "BANDWIDTH=" in ln:
            bw = int(re.search(r"BANDWIDTH=(\d+)", ln).group(1))
            # 下一行是非 # 的 uri
            for j in range(i + 1, len(lines)):
                if lines[j] and not lines[j].startswith("#"):
                    if bw > best_bw:
                        best_bw, best_uri = bw, lines[j]
                    break
    print(f"  master={st} 最高变体 bandwidth={best_bw}")
    if best_uri:
        st, pl, _ = http(best_uri, headers={"User-Agent": UA_DESK})
        segs = [l for l in pl.decode("utf-8", "replace").splitlines()
                if l and not l.startswith("#")][:6]
        print(f"  playlist={st} 取 {len(segs)} 段并发拉")
        t0 = time.time()
        def seg(su):
            try:
                st2, b, _ = http(su, headers={"User-Agent": UA_DESK})
                return len(b)
            except Exception as e:
                print("  seg err:", e); return 0
        with ThreadPoolExecutor(len(segs) or 1) as ex:
            tot = sum(ex.map(seg, segs))
        wall = time.time() - t0
        print(f"  {tot/1e6:.2f} MB in {wall:.1f}s = {tot/1e6/wall:.2f} MB/s ({mbps(tot,wall):.1f} Mbps)")
