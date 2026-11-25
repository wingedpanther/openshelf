"""Build the GitHub Pages site: the website, catalog.json, its signature, version.json,
and the apps that are new this week as new.json and feed.xml.

Usage: CATALOG_KEY_FILE=key.pem python3 -I tools/publish.py <catalog.json> <out-dir> [--before old.json ...] [--history new.json]
The key is an EC P-256 private key in PEM. Without CATALOG_KEY_FILE the site is built unsigned,
which is only useful for previewing it. An app is new when no --before catalog lists its repo.
--history is the last published new.json, so earlier weeks stay in the feed.
"""
import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
from datetime import date, datetime, timezone
from email.utils import format_datetime
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
SITE = "https://wingedpanther.github.io/openshelf/"
WEEKS = 8

def read(path):
    try:
        return json.loads(Path(path).read_text()) if path else None
    except (OSError, ValueError):
        return None

def weeks(meta, before, history):
    olds = [b for b in (read(p) for p in before) if b]
    past = [w for w in ((read(history) or {}).get("weeks") or []) if w.get("date") and isinstance(w.get("repos"), list)]
    if any(b.get("generated") == meta["generated"] for b in olds) or not olds:
        return past[:WEEKS]
    seen = {a["repo"].lower() for b in olds for a in b.get("apps", [])}
    fresh = [a["repo"] for a in sorted(meta["apps"], key=lambda a: -a.get("stars", 0))
             if a["repo"].lower() not in seen and not a.get("forkOf") and a.get("apks")]
    return ([{"date": meta["generated"], "repos": fresh}] + [w for w in past if w["date"] != meta["generated"]])[:WEEKS]

def feed(meta, history):
    apps = {a["repo"]: a for a in meta["apps"]}
    stamp = lambda d: format_datetime(datetime.combine(date.fromisoformat(d), datetime.min.time(), timezone.utc))
    items = []
    for w in history:
        for repo in w["repos"]:
            a = apps.get(repo)
            if not a or not a.get("code"):
                continue
            link = f"{SITE}#{a['code']}"
            note = (a.get("note") or "").strip()
            text = f"{note}{'' if not note or note[-1] in '.!?' else '.'} By {a.get('maker', '')}, {a.get('stars', 0)} stars. Code {a['code']} in Open Shelf.".strip()
            items.append(f"<item><title>{escape(a['name'])}</title><link>{link}</link><guid>{link}</guid>"
                         f"<description>{escape(text)}</description><pubDate>{stamp(w['date'])}</pubDate></item>")
    return ('<?xml version="1.0" encoding="utf-8"?>\n<rss version="2.0"><channel>'
            f"<title>Open Shelf: new Android TV apps</title><link>{SITE}</link>"
            "<description>Android TV apps on GitHub that joined the Open Shelf list, checked every Monday.</description>"
            f"<lastBuildDate>{stamp(meta['generated'])}</lastBuildDate>" + "".join(items) + "</channel></rss>\n")

def main():
    args = argparse.ArgumentParser(usage=__doc__)
    args.add_argument("catalog")
    args.add_argument("out")
    args.add_argument("--before", nargs="*", default=[])
    args.add_argument("--history")
    opts = args.parse_args()
    catalog, out = Path(opts.catalog), Path(opts.out)
    data = catalog.read_bytes()
    meta = json.loads(data)
    out.mkdir(parents=True, exist_ok=True)
    for f in (ROOT / "tools/site").iterdir():
        shutil.copy(f, out / f.name)
    for name in ("mark.png", "mark_dark.png"):
        shutil.copy(ROOT / "app/src/main/res/drawable-nodpi" / name, out / name)
    (out / "catalog.json").write_bytes(data)
    key = os.environ.get("CATALOG_KEY_FILE")
    if key:
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=data, capture_output=True, check=True).stdout
        pub = subprocess.run(["openssl", "ec", "-in", key, "-pubout"], capture_output=True, check=True).stdout
        (out / "pub.pem").write_bytes(pub)
        (out / "raw.sig").write_bytes(sig)
        subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(out / "pub.pem"), "-signature", str(out / "raw.sig"), str(out / "catalog.json")],
                       capture_output=True, check=True)
        (out / "pub.pem").unlink()
        (out / "raw.sig").unlink()
        (out / "catalog.sig").write_text(base64.b64encode(sig).decode() + "\n")
    (out / "version.json").write_text(json.dumps({"generated": meta["generated"], "apps": len(meta["apps"])}) + "\n")
    history = weeks(meta, opts.before, opts.history)
    (out / "new.json").write_text(json.dumps({"weeks": history}) + "\n")
    (out / "feed.xml").write_text(feed(meta, history))
    (out / ".nojekyll").write_text("")
    added = len(history[0]["repos"]) if history and history[0]["date"] == meta["generated"] else 0
    print(f"site: {len(meta['apps'])} apps, {added} new, generated {meta['generated']}, {'signed' if key else 'unsigned'} -> {out}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
