#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把多个 TVBox 上游配置汇总成单仓订阅产物。

上游清单人工维护在 tools/tvbox/sources.toml（唯一权威数据）。
本脚本只做：拉取 → 结构校验（字节门槛/可解析/条目数）→ 解析 → 去重
→ 按校验状态分层 → 写 dist/。
站点分层测活（L1/L2/L3）不在此处——由本地或 OpenWrt LuCI 侧 probe 执行，
结果写 state/checks.json 提交回来后，本脚本读取它分层 recommended.json。

产物：
    dist/tvbox.json        全量合并（剔除黑名单）
    dist/recommended.json  仅实测 healthy 的源（无校验数据时=全量并标 absent）
    dist/status.json       本次运行统计（机器读）
    dist/status.md         人读状态表

仅使用标准库（tomllib 需 Python >= 3.11），Actions 里不需要 pip install。
设计文档：docs/tvbox-source-aggregator-design.md
"""

import argparse
import hashlib
import json
import os
import re
import ssl
import sys
import time
import tomllib
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

USER_AGENT = "bilitv-tvbox-aggregate/1.0 (+https://github.com/urbanescavenger/BiliMT)"
DEFAULT_TIMEOUT = 25
DEFAULT_RETRIES = 2

# 本文件位于 <repo>/tools/tvbox/scripts/aggregate.py
TOOL_DIR = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = TOOL_DIR / "sources.toml"
DEFAULT_STATE_DIR = TOOL_DIR / "state"
DEFAULT_DIST_DIR = TOOL_DIR / "dist"

# 全局展示字段：取第一个携带它的上游的值，不跨源合并（客户端一次性配置）
GLOBAL_KEYS = ("spider", "wallpaper", "logo")

_SSL_CONTEXT = None
_SSL_CHECKED = False


def _ssl_context():
    """系统没有 CA 包时（Windows/conda 常见）退回 certifi。

    Actions 的 Ubuntu runner 有系统 CA，返回 None 走默认校验。
    （与隔壁 CFIP 仓库 merge_ip_libs.py 同款。）
    """
    global _SSL_CONTEXT, _SSL_CHECKED
    if _SSL_CHECKED:
        return _SSL_CONTEXT
    _SSL_CHECKED = True
    paths = ssl.get_default_verify_paths()
    if paths.cafile and os.path.exists(paths.cafile):
        return None
    try:
        import certifi
    except ImportError:
        return None
    _SSL_CONTEXT = ssl.create_default_context(cafile=certifi.where())
    return _SSL_CONTEXT


def fetch(url, timeout, retries):
    context = _ssl_context()
    last_error = None
    for attempt in range(1, retries + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
                return response.read()
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = str(exc)
            if attempt < retries:
                time.sleep(2)
    raise RuntimeError(last_error or "unknown error")


def site_id(site):
    """站点唯一标识：优先 key，缺 key 时用 name|api 组合。"""
    key = str(site.get("key") or "").strip()
    if key:
        return key
    return "%s|%s" % (site.get("name", ""), site.get("api", ""))


def load_manifest(path):
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise SystemExit("读取源清单失败 %s: %s" % (path, exc))
    upstreams = data.get("upstreams")
    if not isinstance(upstreams, list) or not upstreams:
        raise SystemExit("源清单没有 upstreams: %s" % path)
    return data


def compile_adult_filters(filters):
    """编译 [filters] 成人词表(词表事实源=hebijunge categories.json adult 节)。

    空词表(关键词与域名黑名单都空)=整个过滤功能关闭。
    刻意不含 ^\\d{1,3}$ 纯数字台号规则——那是直播台名口径,会误杀 360 这类站名。
    """
    return {
        "keywords": [str(k).lower() for k in (filters.get("adult_keywords") or [])],
        "name_res": [re.compile(p) for p in (filters.get("adult_name_regexes") or [])],
        "exact": [str(h).lower() for h in (filters.get("host_blacklist_exact") or [])],
        "tokens": [str(t).lower() for t in (filters.get("host_blacklist_tokens") or [])],
        "pattern": (re.compile(filters["host_blacklist_pattern"])
                    if filters.get("host_blacklist_pattern") else None),
        "exempt": [str(d).lower() for d in (filters.get("shared_cdn_no_blacklist") or [])],
    }


def adult_reason(site, filters):
    """返回成人命中规则标签(空=放行)。判定序与 hebijunge is_adult/is_adult_url 对齐:
    ① name 关键词子串 ② name 正则 ③ host 精确后缀黑名单 ④ 共用 CDN 豁免放行
    ⑤ host token / 边界正则。豁免必须在 token 之前——防共用 CDN 误伤正常源。"""
    if not filters["keywords"] and not filters["exact"]:
        return ""
    name = str(site.get("name") or "")
    low = name.lower()
    for kw in filters["keywords"]:
        if kw and kw in low:
            return "kw:%s" % kw
    for rx in filters["name_res"]:
        if rx.search(name):
            return "name_re"
    api = str(site.get("api") or "")
    host = ""
    if "//" in api:
        host = api.split("//", 1)[1].split("/", 1)[0].split("?", 1)[0].lower()
    if not host:
        return ""
    if any(host == d or host.endswith("." + d) for d in filters["exact"]):
        return "host:%s" % host
    if any(host == d or host.endswith("." + d) for d in filters["exempt"]):
        return ""
    for tok in filters["tokens"]:
        if tok and tok in host:
            return "token:%s" % tok
    if filters["pattern"] and filters["pattern"].search(host):
        return "host_re"
    return ""


def load_state(state_dir):
    """读校验状态。数据缺失/损坏时按「无校验数据」处理，不阻塞汇总。"""
    checks, blacklist = {}, set()
    checks_path = state_dir / "checks.json"
    if checks_path.exists():
        try:
            loaded = json.loads(checks_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                checks = loaded
            else:
                print("[!] checks.json 不是对象，忽略", file=sys.stderr)
        except ValueError as exc:
            print("[!] checks.json 解析失败，忽略: %s" % exc, file=sys.stderr)
    blacklist_path = state_dir / "blacklist.txt"
    if blacklist_path.exists():
        for line in blacklist_path.read_text(encoding="utf-8").splitlines():
            item = line.strip()
            if item and not item.startswith("#"):
                blacklist.add(item)
    return checks, blacklist


def normalize_config(obj):
    """校验一个 config 类上游的顶层结构，返回 (sites, parses)。

    lives（直播）刻意不读：直播功能不做（2026-11-19 拍板），
    上游配置里内嵌的 lives 一并丢弃，避免客户端出现直播入口。
    """
    if not isinstance(obj, dict):
        raise ValueError("顶层不是 JSON 对象")
    sites = obj.get("sites") or []
    parses = obj.get("parses") or []
    if not isinstance(sites, list) or not isinstance(parses, list):
        raise ValueError("sites/parses 不是数组")
    return sites, parses


def render_status_md(stats):
    probe_note = {
        "absent": "无校验数据（probe 未接入），recommended = 全量",
        "present": "已接入 state/checks.json 按 healthy 分层",
    }.get(stats["probe_state"], stats["probe_state"])
    lines = [
        "# TVBox 汇总状态",
        "",
        "- 更新时间：%s（UTC）" % stats["updated"],
        "- 校验状态：%s" % probe_note,
        "",
        "| 上游 | 状态 | 字节 | sha256 | 条目 | 新增 | 说明 |",
        "|---|---|---:|---|---:|---:|---|",
    ]
    for up in stats["upstreams"]:
        if up.get("skipped"):
            state = "停用"
        elif up.get("ok"):
            state = "ok"
        else:
            state = "失败"
        lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            up.get("name", ""), state,
            up.get("bytes", "-"), up.get("sha256", "-"),
            up.get("items", "-"), up.get("added", "-"),
            (up.get("error") or "").replace("|", "/"),
        ))
    totals = stats["totals"]
    lines += [
        "",
        "合计：sites %d（黑名单剔除 %d，成人词表剔除 %d）、parses %d；"
        "recommended healthy=%s fallback=%s"
        % (totals.get("sites", 0), totals.get("blacklisted_removed", 0),
           totals.get("adult_removed", 0), totals.get("parses", 0),
           totals.get("healthy", 0), totals.get("recommended_fallback", False)),
        "",
        "本文件由 tools/tvbox/scripts/aggregate.py 生成，勿手改。",
        "",
    ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST,
                        help="源清单 TOML（默认 tools/tvbox/sources.toml）")
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE_DIR,
                        help="校验状态目录（checks.json / blacklist.txt）")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_DIST_DIR,
                        help="产物输出目录（默认 tools/tvbox/dist）")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    parser.add_argument("--retries", type=int, default=DEFAULT_RETRIES)
    args = parser.parse_args()

    # Windows 控制台默认 GBK，中文统计会乱码；本地跑时切到 UTF-8
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            pass

    manifest = load_manifest(args.manifest)
    adult = compile_adult_filters(manifest.get("filters") or {})
    overrides = manifest.get("overrides") or {}
    checks, state_black = load_state(args.state_dir)
    force = {str(x) for x in (overrides.get("force_include") or [])}
    black = ({str(x) for x in (overrides.get("blacklist") or [])} | state_black) - force

    stats = {
        "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "manifest": str(args.manifest),
        "probe_state": "present" if checks else "absent",
        "upstreams": [],
        "totals": {},
    }

    merged_sites, seen_site = [], set()
    merged_parses, seen_parse = [], set()
    globals_found = {}
    ok_count = 0
    black_removed = 0
    adult_removed = 0
    adult_names = []

    for up in manifest["upstreams"]:
        name = str(up.get("name") or "").strip() or "(未命名)"
        url = str(up.get("url") or "").strip()
        entry = {"name": name, "url": url, "kind": up.get("kind", "config")}

        if not up.get("enabled", True):
            entry["skipped"] = True
            stats["upstreams"].append(entry)
            print("[-] %-12s 已停用，跳过" % name)
            continue
        if not url:
            entry.update(ok=False, error="缺少 url")
            stats["upstreams"].append(entry)
            continue
        if entry["kind"] != "config":
            entry.update(ok=False, error="不支持的 kind（当前仅 config）")
            stats["upstreams"].append(entry)
            continue

        min_bytes = int(up.get("min_bytes") or 1)
        min_items = int(up.get("min_items") or 1)
        raw = None
        try:
            raw = fetch(url, args.timeout, args.retries)
            if len(raw) < min_bytes:
                raise ValueError("字节数 %d < 门槛 %d（疑似假 200）" % (len(raw), min_bytes))
            # lstrip 参数是显式转义的 U+FEFF（BOM），不要写成字面量——肉眼看不见
            text = raw.decode("utf-8", "replace").lstrip("\\ufeff")
            obj = json.loads(text)
            sites, parses = normalize_config(obj)
            items = len(sites)
            if items < min_items:
                raise ValueError("条目数 %d < 门槛 %d" % (items, min_items))
        except (RuntimeError, ValueError, TypeError) as exc:
            entry.update(ok=False, error=str(exc), bytes=len(raw) if raw else 0)
            stats["upstreams"].append(entry)
            print("[!] %-12s 失败: %s" % (name, exc))
            continue

        added = 0
        adult_n = 0
        spider = str(obj.get("spider") or "")
        for key in GLOBAL_KEYS:
            value = obj.get(key)
            if value and not globals_found.get(key):
                globals_found[key] = value

        for site in sites:
            if not isinstance(site, dict):
                continue
            # 成人过滤(拍板 2026-11-20):词表命中即整站剔除,不进任何产物也不探测
            reason = adult_reason(site, adult)
            if reason:
                adult_removed += 1
                adult_n += 1
                if len(adult_names) < 50:
                    adult_names.append(str(site.get("name") or "?"))
                continue
            # CSP 源依赖线路原装 spider 的 jar：单仓只能留一个全局 spider，
            # 所以给 csp_ 站点带上自己线路的 jar（与 TVboxorg merge.py 同思路）。
            if not site.get("jar") and spider and str(site.get("api") or "").startswith("csp_"):
                site["jar"] = spider
            sid = site_id(site)
            if sid in black:
                black_removed += 1
                continue
            if sid in seen_site:
                continue
            seen_site.add(sid)
            merged_sites.append(site)
            added += 1

        # parses（二级解析）只是播放辅助，不计入条目/新增列，只汇总进 totals
        for parse in parses:
            if not isinstance(parse, dict):
                continue
            pname = str(parse.get("name") or "").strip()
            if not pname or pname in seen_parse:
                continue
            seen_parse.add(pname)
            merged_parses.append(parse)

        entry.update(ok=True, bytes=len(raw),
                     sha256=hashlib.sha256(raw).hexdigest()[:12],
                     items=items, added=added)
        if adult_n:
            entry["adult_removed"] = adult_n
        stats["upstreams"].append(entry)
        ok_count += 1
        print("[+] %-12s %7d 字节  条目 %4d  新增 %4d" % (name, len(raw), items, added))

    def assemble(sites, parses):
        config = {}
        for key in GLOBAL_KEYS:
            if globals_found.get(key):
                config[key] = globals_found[key]
        config["sites"] = sites
        if parses:
            config["parses"] = parses
        return config

    def is_healthy(site):
        return (checks.get(site_id(site)) or {}).get("status") == "healthy"

    # recommended 分层：有校验数据按 healthy 过滤；全空时兜底回全量
    fallback = False
    if checks:
        rec_sites = [s for s in merged_sites if site_id(s) in force or is_healthy(s)]
        if merged_sites and not rec_sites:
            rec_sites = merged_sites
            fallback = True
    else:
        rec_sites = merged_sites

    stats["adult_filtered_sample"] = adult_names
    stats["totals"] = {
        "sites": len(merged_sites),
        "parses": len(merged_parses),
        "blacklisted_removed": black_removed,
        "adult_removed": adult_removed,
        "healthy": sum(1 for s in rec_sites if is_healthy(s)),
        "recommended_fallback": fallback,
    }

    args.output_dir.mkdir(parents=True, exist_ok=True)

    def write_json(path, data):
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")

    write_json(args.output_dir / "tvbox.json", assemble(merged_sites, merged_parses))
    write_json(args.output_dir / "recommended.json", assemble(rec_sites, merged_parses))
    write_json(args.output_dir / "status.json", stats)
    (args.output_dir / "status.md").write_text(render_status_md(stats), encoding="utf-8")

    totals = stats["totals"]
    print("汇总完成: %d/%d 上游成功, sites=%d parses=%d, 剔除黑名单 %d, 成人词表剔除 %d"
          % (ok_count, len(manifest["upstreams"]), totals["sites"],
             totals["parses"], totals["blacklisted_removed"],
             totals["adult_removed"]))
    if not ok_count:
        print("没有任何上游拉取成功", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
