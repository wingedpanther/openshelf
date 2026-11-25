"""Look up each APK's SHA-256 on VirusTotal and add the result to the catalog.

Usage: VT_KEY=... python3 -I tools/scan.py <catalog.json> <work-dir> [previous-catalog.json ...]
Results are kept in <work-dir>/vt.json and carried over from earlier catalogs, so each run
only asks about files it hasn't seen. The free API allows 4 lookups a minute and 500 a day.
VT_MINUTES (default 80) and VT_LIMIT (default 450) cap a run. Without VT_KEY nothing is
looked up and earlier results are still added.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

WAIT = 15.5
RETRY_MISSING = 28
REFRESH = 90

def lookup(sha, key):
    req = urllib.request.Request(f"https://www.virustotal.com/api/v3/files/{sha}", headers={"x-apikey": key, "accept": "application/json"})
    today = date.today().isoformat()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            attrs = json.load(r)["data"]["attributes"]
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return {"missing": today}
        if e.code in (401, 403, 429):
            return "stop"
        return None
    except (urllib.error.URLError, TimeoutError, KeyError, ValueError):
        return None
    stats = attrs.get("last_analysis_stats") or {}
    bad = stats.get("malicious", 0)
    of = bad + stats.get("suspicious", 0) + stats.get("undetected", 0) + stats.get("harmless", 0)
    when = attrs.get("last_analysis_date")
    on = datetime.fromtimestamp(when, timezone.utc).date().isoformat() if when else today
    return {"bad": bad, "of": of, "on": on, "seen": today}

def due(entry, today):
    if not entry:
        return 0
    if "missing" in entry:
        return 1 if date.fromisoformat(entry["missing"]) < today - timedelta(days=RETRY_MISSING) else None
    if date.fromisoformat(entry.get("seen", entry["on"])) < today - timedelta(days=REFRESH):
        return 2
    return None

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    catalog, work = Path(sys.argv[1]), Path(sys.argv[2])
    cache_path = work / "vt.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}
    for old in sys.argv[3:]:
        if not Path(old).exists():
            continue
        for a in json.loads(Path(old).read_text()).get("apps", []):
            for x in a.get("apks", []):
                if x.get("sha256") and x.get("vt") and x["sha256"] not in cache:
                    cache[x["sha256"]] = dict(x["vt"], seen=x["vt"]["on"])
    meta = json.loads(catalog.read_text())
    today = date.today()
    order, queued = [], set()
    for a in sorted(meta["apps"], key=lambda a: -a.get("stars", 0)):
        for x in a.get("apks", []):
            sha = x.get("sha256")
            rank = due(cache.get(sha), today) if sha else None
            if rank is not None and sha not in queued:
                queued.add(sha)
                order.append((rank, sha))
    order.sort(key=lambda t: t[0])
    key = os.environ.get("VT_KEY")
    budget = time.time() + float(os.environ.get("VT_MINUTES", "80")) * 60
    limit = int(os.environ.get("VT_LIMIT", "450"))
    asked = found = 0
    if key:
        for _, sha in order:
            if asked >= limit or time.time() + WAIT > budget:
                break
            r = lookup(sha, key)
            asked += 1
            if r == "stop":
                break
            if r:
                cache[sha] = r
                found += "of" in r
            cache_path.write_text(json.dumps(cache))
            time.sleep(WAIT)
    flagged = scanned = 0
    for a in meta["apps"]:
        hit = False
        for x in a.get("apks", []):
            r = cache.get(x.get("sha256"))
            x.pop("vt", None)
            if r and r.get("of"):
                x["vt"] = {"bad": r["bad"], "of": r["of"], "on": r["on"]}
                scanned += 1
                hit = hit or r["bad"] > 0
        flagged += hit
    work.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache))
    catalog.write_text(json.dumps(meta, ensure_ascii=False, separators=(",", ":")))
    print(f"scan: asked {asked} of {len(order)} due, {found} known to VirusTotal; {scanned} APKs have results, {flagged} apps flagged by at least one scanner"
          + ("" if key else " (no VT_KEY, nothing looked up)"))
    return 0

if __name__ == "__main__":
    sys.exit(main())
