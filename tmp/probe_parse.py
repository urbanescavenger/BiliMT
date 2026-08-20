import requests, json, re
MOBILE_UA=("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
API_KEY="AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
REF="https://www.youtube.com/youtubei/v1"
s=requests.Session(); s.headers.update({"User-Agent":MOBILE_UA,"Accept":"*/*","Accept-Language":"en-US","Referer":"https://www.youtube.com"})
r=s.get("https://www.youtube.com/sw.js_data",headers={"Cookie":"PREF=tz=Asia.Shanghai"})
visitor=json.loads(r.text[4:].lstrip())[0][2][0][0][13]
ctx={"client":{"clientName":"WEB","clientVersion":"2.20260623.01.00","hl":"en","gl":"US","platform":"DESKTOP","clientFormFactor":"UNKNOWN_FORM_FACTOR","userInterfaceTheme":"USER_INTERFACE_THEME_LIGHT","originalUrl":"https://www.youtube.com","userAgent":MOBILE_UA,"screenWidthPoints":1920,"screenHeightPoints":1080,"screenPixelDensity":1,"screenDensityFloat":1,"utcOffsetMinutes":0,"timeZone":"Asia/Shanghai","clientScreen":"WATCH","memoryTotalKbytes":"8000000","mainAppWebInfo":{"graftUrl":"https://www.youtube.com","pwaInstallabilityStatus":"PWA_INSTALLABILITY_STATUS_UNKNOWN","webDisplayMode":"WEB_DISPLAY_MODE_BROWSER","isWebNativeShareAvailable":"true"},"visitorData":visitor},"user":{"enableSafetyMode":False,"lockedSafetyMode":False},"request":{"useSsl":True,"internalExperimentFlags":[]}}

def browse(channel, params=None):
    p={"browseId":channel}
    if params: p["params"]=params
    p["context"]=ctx
    rr=s.post(f"{REF}/browse?key={API_KEY}&prettyPrint=false&alt=json",json=p,headers={"X-Youtube-Client-Version":"2.20260623.01.00","X-Youtube-Client-Name":"1","X-Youtube-Bootstrap-Logged-In":"false","Cookie":f"VISITOR_INFO1_LIVE={visitor}"})
    return rr.status_code, rr.json()

def collect(obj, key, out):
    if isinstance(obj,dict):
        if key in obj: out.append(obj[key])
        for v in obj.values(): collect(v,key,out)
    elif isinstance(obj,list):
        for v in obj: collect(v,key,out)

def parse_lockup(node):
    vid=node.get("contentId")
    if not vid: return None
    title=(node.get("metadata",{}).get("lockupMetadataViewModel",{}).get("title",{}) or {}).get("content")
    if not title: return None
    return vid, title

for ch,label in [("UCBJycsmduvYEL83R_U4JriQ","MKBHD"),
                 ("UCX6OQ3DkcsbYNE6H8uQQuVA","MrBeast"),
                 ("UC-a1gZOHsWsA1vHI6FX3rWA","BBC")]:
    st,j=browse(ch,"EgZ2aWRlb3PyBgQKAjoA")
    locks=[]
    collect(j,"lockupViewModel",locks)
    parsed=[parse_lockup(x) for x in locks]
    parsed=[x for x in parsed if x]
    print(f"{label}: status={st} lockups={len(locks)} parsed_videos={len(parsed)}")
    for vid,title in parsed[:3]:
        print("   ",vid,title[:50])
