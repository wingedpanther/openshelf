"""Print the Discord message for a freshly built site, or nothing when no apps are new.

Usage: python3 -I tools/announce.py <site-dir>
Reads catalog.json and new.json from the site that tools/publish.py wrote.
"""
import json
import sys
from datetime import date
from pathlib import Path

SITE = "https://wingedpanther.github.io/openshelf/"
NAMED = 3

def message(meta, weeks):
    week = (weeks or [{}])[0]
    if week.get("date") != meta["generated"] or not week.get("repos"):
        return ""
    names = {a["repo"]: a["name"] for a in meta["apps"]}
    top = [names[r] for r in week["repos"] if r in names][:NAMED]
    day = date.fromisoformat(meta["generated"][:10])
    text = f"List updated {day.day} {day.strftime('%b')}: {len(meta['apps']):,} apps, {len(week['repos']):,} new this week."
    if top:
        text += f" Most starred new {'one' if len(top) == 1 else 'ones'}: {', '.join(top)}."
    return text + f" Browse them at <{SITE}>"

def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    site = Path(sys.argv[1])
    meta = json.loads((site / "catalog.json").read_text())
    weeks = json.loads((site / "new.json").read_text()).get("weeks") if (site / "new.json").exists() else []
    print(message(meta, weeks))
    return 0

if __name__ == "__main__":
    sys.exit(main())
