import requests, json
MOBILE_UA=("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
API_KEY="AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
REF="https://www.youtube.com/youtubei/v1"
s=requests.Session(); s.headers.update({"User-Agent":MOBILE_UA,"Accept":"*/*","Accept-Language":"en-US","Referer":"https://www.youtube.com"})
r=s.get("https://www.youtube.com/sw.js_data",headers={"Cookie":"PREF=tz=Asia.Shanghai"})
visitor=json.loads(r.text[4:].lstrip())[0][2][0][0][13]
ctx={"client":{"clientName":"WEB","clientVersion":"2.20260623.01.00","hl":"en","gl":"US","platform":"DESKTOP","clientFormFactor":"UNKNOWN_FORM_FACTOR","userInterfaceTheme":"USER_INTERFACE_THEME_LIGHT","originalUrl":"https://www.youtube.com","userAgent":MOBILE_UA,"screenWidthPoints":1920,"screenHeightPoints":1080,"screenPixelDensity":1,"screenDensityFloat":1,"utcOffsetMinutes":0,"timeZone":"Asia/Shanghai","clientScreen":"WATCH","memoryTotalKbytes":"8000000","mainAppWebInfo":{"graftUrl":"https://www.youtube.com","pwaInstallabilityStatus":"PWA_INSTALLABILITY_STATUS_UNKNOWN","webDisplayMode":"WEB_DISPLAY_MODE_BROWSER","isWebNativeShareAvailable":"true"},"visitorData":visitor},"user":{"enableSafetyMode":False,"lockedSafetyMode":False},"request":{"useSsl":True,"internalExperimentFlags":[]}}
ch="UC-a1gZOHsWsA1vHI6FX3rWA"
p={"browseId":ch,"params":"EgZ2aWRlb3PyBgQKAjoA","context":ctx}
rr=s.post(f"{REF}/browse?key={API_KEY}&prettyPrint=false&alt=json",json=p,headers={"X-Youtube-Client-Version":"2.20260623.01.00","X-Youtube-Client-Name":"1","X-Youtube-Bootstrap-Logged-In":"false","Cookie":f"VISITOR_INFO1_LIVE={visitor}"})
j=rr.json()
def count(obj,key):
    n=0
    if isinstance(obj,dict):
        if key in obj: n+=1
        for v in obj.values(): n+=count(v,key)
    elif isinstance(obj,list):
        for v in obj: n+=count(v,key)
    return n
for k in ["videoRenderer","gridVideoRenderer","compactVideoRenderer","lockupViewModel","richItemRenderer","shelfRenderer","continuationItemRenderer","gridRenderer","richGridRenderer","channelFeaturedContentRenderer","reelItemRenderer"]:
    print(k, count(j,k))
print("top keys:", list(j.keys()))
# find the videos tab content structure
def find_tab(obj, depth=0):
    if isinstance(obj,dict):
        if obj.get("selected") is True and "tabRenderer" in obj:
            return obj
        for v in obj.values():
            f=find_tab(v,depth+1)
            if f: return f
    elif isinstance(obj,list):
        for v in obj:
            f=find_tab(v,depth+1)
            if f: return f
    return None
print("status", rr.status_code)
# print contents top-level
print("header keys:", list(j.get("header",{}).keys()))
print(json.dumps({k: (list(v.keys())[:8] if isinstance(v,dict) else type(v).__name__) for k,v in j.get("contents",{}).items()})[:500])
