import requests, json
MOBILE_UA=("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
API_KEY="AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
REF="https://www.youtube.com/youtubei/v1"
s=requests.Session(); s.headers.update({"User-Agent":MOBILE_UA,"Accept":"*/*","Accept-Language":"en-US","Referer":"https://www.youtube.com"})
r=s.get("https://www.youtube.com/sw.js_data",headers={"Cookie":"PREF=tz=Asia.Shanghai"})
visitor=json.loads(r.text[4:].lstrip())[0][2][0][0][13]
ctx={"client":{"clientName":"WEB","clientVersion":"2.20260623.01.00","hl":"en","gl":"US","platform":"DESKTOP","clientFormFactor":"UNKNOWN_FORM_FACTOR","userInterfaceTheme":"USER_INTERFACE_THEME_LIGHT","originalUrl":"https://www.youtube.com","userAgent":MOBILE_UA,"screenWidthPoints":1920,"screenHeightPoints":1080,"screenPixelDensity":1,"screenDensityFloat":1,"utcOffsetMinutes":0,"timeZone":"Asia/Shanghai","clientScreen":"WATCH","memoryTotalKbytes":"8000000","mainAppWebInfo":{"graftUrl":"https://www.youtube.com","pwaInstallabilityStatus":"PWA_INSTALLABILITY_STATUS_UNKNOWN","webDisplayMode":"WEB_DISPLAY_MODE_BROWSER","isWebNativeShareAvailable":"true"},"visitorData":visitor},"user":{"enableSafetyMode":False,"lockedSafetyMode":False},"request":{"useSsl":True,"internalExperimentFlags":[]},"thirdParty":{"embedUrl":"https://www.youtube.com/"}}
body={"videoId":"o4SSoURPODY","contentCheckOk":True,"racyCheckOk":True,"context":ctx}
rr=s.post(f"{REF}/player?key={API_KEY}&prettyPrint=false&alt=json",json=body,headers={"X-Youtube-Client-Version":"2.20260623.01.00","X-Youtube-Client-Name":"1","X-Youtube-Bootstrap-Logged-In":"false","Cookie":f"VISITOR_INFO1_LIVE={visitor}"})
print("status", rr.status_code)
j=rr.json()
vd=j.get("videoDetails")
print("videoDetails keys:", list(vd.keys()) if vd else None)
if vd:
    print("videoId:", vd.get("videoId"))
    print("author:", vd.get("author"))
    print("channelId:", vd.get("channelId"))
    ath=vd.get("authorThumbnails")
    print("authorThumbnails:", len(ath) if ath else None, (ath[0].get('url')[:60] if ath else ''))
