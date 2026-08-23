import requests, json, re

MOBILE_UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
REF = "https://www.youtube.com/youtubei/v1"

s = requests.Session()
s.headers.update({
    "User-Agent": MOBILE_UA,
    "Accept": "*/*",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://www.youtube.com",
})

def get_visitor():
    r = s.get("https://www.youtube.com/sw.js_data",
              headers={"Cookie": "PREF=tz=Asia.Shanghai"})
    print("sw.js_data status", r.status_code)
    if not r.text.startswith(")]}'"):
        print("sw.js_data unexpected prefix:", r.text[:80])
        return None
    root = json.loads(r.text[4:].lstrip())
    # data[0][2]=ytcfg, ytcfg[0][0]=device_info, [13]=visitorData
    dev = root[0][2][0][0]
    return dev[13] if len(dev) > 13 else None

def build_context(visitor):
    return {
        "client": {
            "clientName": "WEB",
            "clientVersion": "2.20260623.01.00",
            "hl": "en", "gl": "US",
            "platform": "DESKTOP",
            "clientFormFactor": "UNKNOWN_FORM_FACTOR",
            "userInterfaceTheme": "USER_INTERFACE_THEME_LIGHT",
            "originalUrl": "https://www.youtube.com",
            "userAgent": MOBILE_UA,
            "screenWidthPoints": 1920, "screenHeightPoints": 1080,
            "screenPixelDensity": 1, "screenDensityFloat": 1,
            "utcOffsetMinutes": 0, "timeZone": "Asia/Shanghai",
            "clientScreen": "WATCH",
            "memoryTotalKbytes": "8000000",
            "mainAppWebInfo": {
                "graftUrl": "https://www.youtube.com",
                "pwaInstallabilityStatus": "PWA_INSTALLABILITY_STATUS_UNKNOWN",
                "webDisplayMode": "WEB_DISPLAY_MODE_BROWSER",
                "isWebNativeShareAvailable": "true",
            },
            "visitorData": visitor,
        },
        "user": {"enableSafetyMode": False, "lockedSafetyMode": False},
        "request": {"useSsl": True, "internalExperimentFlags": []},
    }

def browse(payload, visitor):
    body = dict(payload)
    body["context"] = build_context(visitor)
    url = f"{REF}/browse?key={API_KEY}&prettyPrint=false&alt=json"
    r = s.post(url, json=body,
               headers={"X-Youtube-Client-Version": "2.20260623.01.00",
                        "X-Youtube-Client-Name": "1",
                        "X-Youtube-Bootstrap-Logged-In": "false",
                        "Cookie": f"VISITOR_INFO1_LIVE={visitor}; PREF=tz=Asia.Shanghai"})
    return r

def count_keys(obj, key):
    n = 0
    if isinstance(obj, dict):
        if key in obj: n += 1
        for v in obj.values(): n += count_keys(v, key)
    elif isinstance(obj, list):
        for v in obj: n += count_keys(v, key)
    return n

visitor = get_visitor()
print("visitor:", (visitor[:24] + "...") if visitor else "NONE")

channel = "UCBJycsmduvYEL83R_U4JriQ"  # MKBHD
params = "EgZ2aWRlb3PyBgQKAjoA"

# 1) resolveChannel-style: /browse with only browseId (no params)
r1 = browse({"browseId": channel}, channel)
print("\n=== resolveChannel /browse (no params) ===")
print("status", r1.status_code)
try:
    j1 = r1.json()
    top = list(j1.keys())
    print("topKeys:", top[:15])
    print("has error:", "error" in j1)
    print("videoRenderer count:", count_keys(j1, "videoRenderer"))
    print("lockupViewModel count:", count_keys(j1, "lockupViewModel"))
    hdr = j1.get("header", {})
    print("header keys:", list(hdr.keys())[:5])
except Exception as e:
    print("parse err", e)
    print(r1.text[:400])

# 2) getChannelVideos-style /browse with browseId + params
r2 = browse({"browseId": channel, "params": params}, channel)
print("\n=== getChannelVideos /browse (browseId+params) ===")
print("status", r2.status_code)
try:
    j2 = r2.json()
    print("topKeys:", list(j2.keys())[:15])
    print("has error:", "error" in j2)
    if "error" in j2:
        print("err detail:", json.dumps(j2["error"])[:300])
    print("videoRenderer count:", count_keys(j2, "videoRenderer"))
    print("lockupViewModel count:", count_keys(j2, "lockupViewModel"))
    # check under contents
    cont = j2.get("contents", {})
    print("contents top keys:", list(cont.keys())[:6])
except Exception as e:
    print("parse err", e)
    print(r2.text[:400])
