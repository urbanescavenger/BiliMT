import requests, json
MOBILE_UA=("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
API_KEY="AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
REF="https://www.youtube.com/youtubei/v1"
s=requests.Session(); s.headers.update({"User-Agent":MOBILE_UA,"Accept":"*/*","Accept-Language":"en-US","Referer":"https://www.youtube.com"})
r=s.get("https://www.youtube.com/sw.js_data",headers={"Cookie":"PREF=tz=Asia.Shanghai"})
visitor=json.loads(r.text[4:].lstrip())[0][2][0][0][13]
ctx={"client":{"clientName":"WEB","clientVersion":"2.20260623.01.00","hl":"en","gl":"US","platform":"DESKTOP","clientFormFactor":"UNKNOWN_FORM_FACTOR","userInterfaceTheme":"USER_INTERFACE_THEME_LIGHT","originalUrl":"https://www.youtube.com","userAgent":MOBILE_UA,"screenWidthPoints":1920,"screenHeightPoints":1080,"screenPixelDensity":1,"screenDensityFloat":1,"utcOffsetMinutes":0,"timeZone":"Asia/Shanghai","clientScreen":"WATCH","memoryTotalKbytes":"8000000","mainAppWebInfo":{"graftUrl":"https://www.youtube.com","pwaInstallabilityStatus":"PWA_INSTALLABILITY_STATUS_UNKNOWN","webDisplayMode":"WEB_DISPLAY_MODE_BROWSER","isWebNativeShareAvailable":"true"},"visitorData":visitor},"user":{"enableSafetyMode":False,"lockedSafetyMode":False},"request":{"useSsl":True,"internalExperimentFlags":[]}}
body={"browseId":"UCBJycsmduvYEL83R_U4JriQ","params":"EgZ2aWRlb3PyBgQKAjoA","context":ctx}
r=s.post(f"{REF}/browse?key={API_KEY}&prettyPrint=false&alt=json",json=body,headers={"X-Youtube-Client-Version":"2.20260623.01.00","X-Youtube-Client-Name":"1","X-Youtube-Bootstrap-Logged-In":"false","Cookie":f"VISITOR_INFO1_LIVE={visitor}"})
j=r.json()
def find_first(obj):
    if isinstance(obj,dict):
        if "lockupViewModel" in obj: return obj["lockupViewModel"]
        for v in obj.values():
            f=find_first(v)
            if f: return f
    elif isinstance(obj,list):
        for v in obj:
            f=find_first(v)
            if f: return f
    return None
lock=find_first(j)
def desc(o):
    if isinstance(o,dict): return "dict keys="+str(list(o.keys())[:12])
    if isinstance(o,list): return "list len="+str(len(o))
    return type(o).__name__
print("contentId:", lock.get("contentId"))
print("lockup top keys:", list(lock.keys()))
md=lock.get("metadata")
print("metadata:", desc(md))
lmd=md.get("lockupMetadataViewModel") if isinstance(md,dict) else None
print("lockupMetadataViewModel:", desc(lmd))
if isinstance(lmd,dict):
    title=lmd.get("title")
    print("title:", json.dumps(title)[:220])
    meta=lmd.get("metadata")
    print("lmd.metadata:", desc(meta))
    cmv=meta.get("contentMetadataViewModel") if isinstance(meta,dict) else None
    print("contentMetadataViewModel:", desc(cmv))
    if isinstance(cmv,dict):
        print("metadataRows:", json.dumps(cmv.get("metadataRows"))[:300])
ci=lock.get("contentImage")
print("contentImage:", desc(ci))
