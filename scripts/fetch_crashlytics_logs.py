"""Fetch existing log reports from Firebase Crashlytics Data API (v1alpha).

凭证只从本地 `~/.config/configstore/firebase-tools.json` 读取,代码里不写死任何
token,可安全提交。首次使用前需在 lcchat 环境跑一次 `firebase login --no-localhost`
完成浏览器授权(见 docs/crashlytics-log-fetch.md)。

Usage:
  # 列最近 issue(默认 7 天 topIssues)
  python scripts/fetch_crashlytics_logs.py issues

  # 拉某 issue 最新一个事件的日志正文(默认),输出到 tmp/
  python scripts/fetch_crashlytics_logs.py events --issue <hexId> [--event-index 0] [--out foo.txt]

  # 列某 issue 全部事件摘要(时间/版本/设备)
  python scripts/fetch_crashlytics_logs.py events --issue <hexId> --list-only

access_token 过期时自动用 refresh_token 换新并回写 configstore(CLI 兼容格式)。
"""
import argparse
import json
import ssl
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

CONFIG_PATH = Path.home() / ".config" / "configstore" / "firebase-tools.json"
API_BASE = "https://firebasecrashlytics.googleapis.com/v1alpha/projects/392012664321/apps/1:392012664321:android:57b895940878fb9f12fa00"
TOKEN_URL = "https://oauth2.googleapis.com/token"
# firebase-tools 源码 lib/api.js 里的内置公开 client(gcloud CLI 同款,泄露无害)
CLIENT_ID = "563584335869-fgrhgmd47bqnekij5i8b5pr03ho849e6.apps.googleusercontent.com"
CLIENT_SECRET = "j9iVZfS8kkCEFUPaAeJV0sAi"
OUT_DIR = Path(__file__).resolve().parent.parent / "tmp"

# conda openssl 加载 Windows 证书库可能撞上坏证书(ASN1: NOT_ENOUGH_DATA),
# 有 certifi 就改用它的 CA bundle 绕开系统证书库
try:
    import certifi
    _SSL_CTX = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    _SSL_CTX = None


def _open(req, timeout=30):
    return urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) if _SSL_CTX \
        else urllib.request.urlopen(req, timeout=timeout)


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        sys.exit(f"凭证文件不存在: {CONFIG_PATH}\n先跑 firebase login(见 docs/crashlytics-log-fetch.md)")
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


def save_config(cfg: dict) -> None:
    CONFIG_PATH.write_text(json.dumps(cfg, indent="\t") + "\n", encoding="utf-8")


def get_access_token(cfg: dict) -> str:
    tokens = cfg["tokens"]
    # expires_at 是毫秒纪元(firebase CLI 写入),比较必须同单位换 ms——
    # 曾经错拿秒比较,ms 恒大于 s 判成永不过期,死守旧 token 直到 API 401
    expires_at = tokens.get("expires_at", 0)
    if expires_at > time.time() * 1000 + 60_000 and tokens.get("access_token"):
        return tokens["access_token"]
    print("[auth] access token 已过期/缺失,用 refresh_token 换新...", file=sys.stderr)
    data = urllib.parse.urlencode({
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "refresh_token": tokens["refresh_token"],
        "grant_type": "refresh_token",
    }).encode()
    with _open(urllib.request.Request(TOKEN_URL, data=data)) as resp:
        result = json.load(resp)
    tokens["access_token"] = result["access_token"]
    tokens["expires_at"] = int(time.time() * 1000) + result.get("expires_in", 3600) * 1000
    save_config(cfg)
    return tokens["access_token"]


def api_get(path: str, params: dict | None = None) -> dict:
    cfg = load_config()
    token = get_access_token(cfg)
    url = f"{API_BASE}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    with _open(req) as resp:
        return json.load(resp)


def cmd_issues(args: argparse.Namespace) -> None:
    rep = api_get("/reports/topIssues", {"page_size": args.limit})
    for g in rep.get("groups", []):
        issue = g["issue"]
        m = g["metrics"][0]
        print(f"{issue['id']}\n  {issue['subtitle']}\n"
              f"  errorType={issue['errorType']} state={issue['state']} "
              f"7dEvents={m['eventsCount']} users={m['impactedUsersCount']} "
              f"version={issue.get('lastSeenVersion', '?')}")
        if args.sample_log:
            print(f"  sampleEvent: {issue['sampleEvent']}")


def cmd_events(args: argparse.Namespace) -> None:
    params = {"filter.issue.id": args.issue, "page_size": args.limit}
    if args.error_type:
        params["filter.issue.error_types"] = args.error_type
    d = api_get("/events", params)
    evs = sorted(d.get("events", []), key=lambda e: e.get("eventTime", ""), reverse=True)
    if not evs:
        sys.exit("该 issue 下没有事件(检查 issue id / 90 天窗口)")
    if args.list_only:
        for i, e in enumerate(evs):
            print(f"[{i}] {e['eventTime']} recv={e.get('receivedTime')} "
                  f"v={e.get('version', {}).get('displayName')} "
                  f"dev={e.get('device', {}).get('displayName')} "
                  f"logs={len(e.get('logs', []))}行 keys={list(e.get('customKeys', {}).items())}")
        return
    e = evs[min(args.event_index, len(evs) - 1)]
    out = args.out or str(OUT_DIR / f"crashlytics_{e['eventId']}.log")
    lines = e.get("logs", [])
    with open(out, "w", encoding="utf-8") as f:
        for l in lines:
            f.write(f"[{l.get('logTime', '')}] {l.get('message', '')}\n")
    print(f"event {e['eventTime']} v={e.get('version', {}).get('displayName')} "
          f"dev={e.get('device', {}).get('displayName')}")
    print(f"日志 {len(lines)} 行已导出 -> {out}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    pi = sub.add_parser("issues", help="列最近 issue")
    pi.add_argument("--limit", type=int, default=15)
    pi.add_argument("--sample-log", action="store_true", help="附 sampleEvent 路径")
    pi.set_defaults(func=cmd_issues)

    pe = sub.add_parser("events", help="拉 issue 事件的日志正文")
    pe.add_argument("--issue", required=True, help="issue id(topIssues 查到的 hex)")
    pe.add_argument("--limit", type=int, default=10)
    pe.add_argument("--error-type", default="NON_FATAL", help="FATAL/NON_FATAL/ANR")
    pe.add_argument("--list-only", action="store_true", help="只列事件摘要,不导日志")
    pe.add_argument("--event-index", type=int, default=0, help="取第几个事件(0=最新)")
    pe.add_argument("--out", help="导出文件路径(默认 tmp/crashlytics_<eventId>.log)")
    pe.set_defaults(func=cmd_events)

    args = p.parse_args()
    try:
        args.func(args)
    except urllib.error.HTTPError as err:
        sys.exit(f"API 失败 HTTP {err.code}: {err.read().decode('utf-8', 'replace')[:500]}")


if __name__ == "__main__":
    main()