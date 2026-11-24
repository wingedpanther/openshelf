"""Open Shelf crawler: find every Android TV app on GitHub that ships an APK release.

Stages, each cached as JSON in the work directory so a run can resume:
  discover  code search for LEANBACK_LAUNCHER in AndroidManifest.xml, sliced by file size, then again for forks
  enrich    GraphQL: repo facts and the latest release's assets
  inspect   read each candidate APK's manifest and signing block with HTTP range requests
  forks     for apps that look abandoned, find forks that ship a newer TV build, and mark
            forks that add a TV build to an app that never had one or ship their own TV app
            based on one that did
  art       pick icon, banner and TV screenshots from each repo's tree
  build     filter, dedupe by package name and write catalog.json

Usage: GH_TOKEN=... python3 -I tools/crawl.py <workdir> [stage ...]
Needs only the standard library plus the openssl command for old v1-only signatures.
"""
import concurrent.futures as cf
import hashlib
import io
import json
import os
import re
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

UA = "OpenShelf-crawler/0.1 (+https://github.com/wingedpanther)"
TOKEN = os.environ.get("GH_TOKEN", "")
TODAY = datetime.now(timezone.utc)
MAX_AGE = timedelta(days=3 * 365)
QUERY = "LEANBACK_LAUNCHER filename:AndroidManifest.xml"
OVERRIDES = json.loads((Path(__file__).resolve().parent / "overrides.json").read_text())
CATALOG = Path(__file__).resolve().parent.parent / "app/src/main/assets/catalog.json"

def connect(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None, **kw):
    err = OSError(f"no address for {address[0]}")
    for af, kind, proto, _, addr in socket.getaddrinfo(address[0], address[1], 0, socket.SOCK_STREAM):
        sock = socket.socket(af, kind, proto)
        try:
            sock.settimeout(8)
            sock.connect(addr)
            sock.settimeout(None if timeout is socket._GLOBAL_DEFAULT_TIMEOUT else timeout)
            return sock
        except OSError as e:
            err = e
            sock.close()
    raise err

socket.create_connection = connect

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)

def api(url, body=None, tries=6):
    headers = {"User-Agent": UA, "Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data, headers), timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (403, 429, 502, 503, 504):
                wait = int(e.headers.get("Retry-After") or 0)
                reset = int(e.headers.get("X-RateLimit-Reset") or 0)
                if not wait and reset and e.headers.get("X-RateLimit-Remaining") == "0":
                    wait = max(1, reset - int(time.time()) + 2)
                wait = wait or 20 * (attempt + 1)
                log(f"  HTTP {e.code}, waiting {wait}s")
                time.sleep(wait)
                continue
            raise
        except (urllib.error.URLError, TimeoutError) as e:
            log(f"  network error {e}, retrying")
            time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"giving up on {url}")

_last_search = [0.0]

def code_search(q, page=1):
    wait = 6.3 - (time.time() - _last_search[0])
    if wait > 0:
        time.sleep(wait)
    _last_search[0] = time.time()
    url = "https://api.github.com/search/code?" + urllib.parse.urlencode({"q": q, "per_page": 100, "page": page})
    return api(url)

def listed(path: Path):
    return json.loads(path.read_text())["apps"] if path.exists() else []

def discover(work: Path):
    out = work / "discover.json"
    state = json.loads(out.read_text()) if out.exists() else {"done": [], "repos": {}}
    done = {tuple(x) for x in state["done"]}

    def save():
        state["done"] = sorted(done)
        out.write_text(json.dumps(state))

    state.setdefault("forks", {})

    def harvest(lo, hi, kind):
        if (lo, hi, kind) in done or kind == "repos" and (lo, hi) in done:
            return
        q = f"{QUERY} size:{lo}..{hi}" + (" fork:true" if kind == "forks" else "")
        first = code_search(q)
        total = first["total_count"]
        if total > 1000 and hi > lo:
            mid = (lo + hi) // 2
            log(f"{kind} slice {lo}..{hi}: {total}, splitting")
            harvest(lo, mid, kind)
            harvest(mid + 1, hi, kind)
            return
        log(f"{kind} slice {lo}..{hi}: {total}")
        pages = [first] + [code_search(q, p) for p in range(2, min(10, (total + 99) // 100) + 1)]
        for page in pages:
            for item in page.get("items", []):
                repo = item["repository"]
                state["forks" if repo.get("fork") else "repos"].setdefault(repo["full_name"], []).append(item["path"])
        done.add((lo, hi, kind))
        save()
        log(f"  {len(state['repos'])} repos, {len(state['forks'])} forks so far")

    for kind in ("repos", "forks"):
        for lo, hi in [(0, 1499), (1500, 1999), (2000, 2999), (3000, 4999), (5000, 9999), (10000, 2_000_000)]:
            harvest(lo, hi, kind)
    for full in OVERRIDES.get("extra", []):
        state["repos"].setdefault(full, ["(overrides.json)"])
    for a in listed(CATALOG) + listed(work / "seed.json"):
        if not a.get("forkOf"):
            state["forks" if a.get("basedOn") else "repos"].setdefault(a["repo"], ["(catalog)"])
    save()
    log(f"discover: {len(state['repos'])} repos, {len(state['forks'])} forks")

FRAGMENT = """
fragment R on Repository {
  nameWithOwner isFork isArchived stargazerCount description pushedAt
  licenseInfo { spdxId }
  owner { login }
  parent { nameWithOwner }
  defaultBranchRef { name }
  repositoryTopics(first: 12) { nodes { topic { name } } }
  latestRelease { tagName publishedAt releaseAssets(first: 50) { nodes { name size downloadUrl downloadCount digest } } }
  releases(first: 5, orderBy: {field: CREATED_AT, direction: DESC}) { nodes { tagName publishedAt isPrerelease isDraft releaseAssets(first: 30) { nodes { name downloadCount } } } }
}"""

def enrich(work: Path):
    found = json.loads((work / "discover.json").read_text())
    repos = sorted({*found["repos"], *found.get("forks", {})})
    out = work / "enrich.json"
    meta = json.loads(out.read_text()) if out.exists() else {}
    todo = [r for r in repos if r not in meta]
    log(f"enrich: {len(todo)} of {len(repos)} to fetch")
    for i in range(0, len(todo), 40):
        batch = todo[i:i + 40]
        parts = []
        for j, full in enumerate(batch):
            owner, name = full.split("/", 1)
            parts.append(f"r{j}: repository(owner: {json.dumps(owner)}, name: {json.dumps(name)}) {{ ...R }}")
        res = api("https://api.github.com/graphql", {"query": "query {" + "\n".join(parts) + "}" + FRAGMENT})
        data = res.get("data") or {}
        for j, full in enumerate(batch):
            meta[full] = data.get(f"r{j}")
        if i % 400 == 0:
            out.write_text(json.dumps(meta))
            log(f"  {i + len(batch)} / {len(todo)}")
    out.write_text(json.dumps(meta))
    log("enrich: done")

STALE = timedelta(days=365)
CRAWLED_BEFORE = "2026-11-04"

def released(m):
    rel = m.get("latestRelease")
    return datetime.fromisoformat(rel["publishedAt"].replace("Z", "+00:00")) if rel else None

def forks(work: Path):
    meta = json.loads((work / "enrich.json").read_text())
    parents = sorted(full for full, m in meta.items() if m and not m["isFork"] and not m.get("forkOf") and m["stargazerCount"] >= 10
                     and released(m) and (m["isArchived"] or TODAY - released(m) > STALE))
    out = work / "forks.json"
    found = json.loads(out.read_text()) if out.exists() else {}
    todo = [p for p in parents if p not in found]
    log(f"forks: {len(todo)} of {len(parents)} stale apps to check")
    for i in range(0, len(todo), 3):
        batch = todo[i:i + 3]
        parts = []
        for j, full in enumerate(batch):
            owner, name = full.split("/", 1)
            parts.append(f"r{j}: repository(owner: {json.dumps(owner)}, name: {json.dumps(name)}) "
                         "{ forks(first: 20, orderBy: {field: STARGAZERS, direction: DESC}) { nodes { ...R } } }")
        res = api("https://api.github.com/graphql", {"query": "query {" + "\n".join(parts) + "}" + FRAGMENT})
        data = res.get("data") or {}
        for j, full in enumerate(batch):
            since = released(meta[full])
            keep = []
            for f in ((data.get(f"r{j}") or {}).get("forks") or {}).get("nodes") or []:
                when = released(f) if f else None
                if when and when > since and TODAY - when <= MAX_AGE:
                    keep.append(f)
            found[full] = keep
        out.write_text(json.dumps(found))
        if i % 30 == 0:
            log(f"  {i + len(batch)} / {len(todo)}, {sum(map(len, found.values()))} newer forks so far")
    out.write_text(json.dumps(found))
    for parent, fs in found.items():
        for f in fs:
            f["forkOf"] = parent
            meta.setdefault(f["nameWithOwner"], f)
    found_tv = json.loads((work / "discover.json").read_text())
    ports = variants = 0
    for full in found_tv.get("forks", {}):
        m = meta.get(full)
        if not m or not m["isFork"] or m.get("forkOf") or m["stargazerCount"] < 10:
            continue
        parent = (m.get("parent") or {}).get("nameWithOwner")
        tv_parent = parent in found_tv["repos"] or parent in found_tv["forks"]
        m["port"] = bool(parent) and not tv_parent
        m["variant"] = bool(parent) and tv_parent
        ports += m["port"]
        variants += m["variant"]
    (work / "enrich.json").write_text(json.dumps(meta))
    log(f"forks: {sum(map(len, found.values()))} newer forks of {sum(1 for v in found.values() if v)} apps, {ports} TV ports, {variants} TV variants")

SKIP_ASSET = re.compile(r"debug|unsigned|(^|[-_.])root[-_.]|geckoIncluded|test", re.I)
TV_HINT = re.compile(r"(^|[-_.])(tv|androidtv|atv|leanback|firetv)([-_.]|$)", re.I)
ABI_TOKEN = re.compile(r"(^|[-_.])(arm64(?:[-_]?v8a?)?|armeabi(?:[-_]?v7a)?|arm32(?:[-_]?v7a?)?|x86[-_]64|aarch64|armv7a?|armv8a?|arm7|arm|amd64|x64|x86|i686|mips64|mips|universal|all|any)(?=[-_.]|$)", re.I)
PHONE_HINT = re.compile(r"(^|[-_.])(mobile|phone|wear|auto)([-_.]|$)", re.I)

def abi_of(name):
    n = name.lower()
    if "mips" in n:
        return "mips"
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
        if abi in n:
            return abi
    if "arm64" in n or "aarch64" in n:
        return "arm64-v8a"
    if "armv7" in n or "arm32" in n or "armeabi" in n:
        return "armeabi-v7a"
    if re.search(r"(^|[-_.])(x64|x86-64|amd64)([-_.]|$)", n):
        return "x86_64"
    if "x86" in n or "i686" in n:
        return "x86"
    return "universal" if "universal" in n else "any"

def family(name):
    return re.sub(r"[-_.]+", "-", ABI_TOKEN.sub(r"\1", name.lower()))

def candidates(meta):
    out = {}
    for full, m in meta.items():
        if not m or (m["isFork"] and not m.get("forkOf") and not m.get("port") and not m.get("variant")):
            continue
        rel = m.get("latestRelease")
        if not rel:
            continue
        published = datetime.fromisoformat(rel["publishedAt"].replace("Z", "+00:00"))
        if TODAY - published > MAX_AGE:
            continue
        apks = [a for a in rel["releaseAssets"]["nodes"] if a["name"].lower().endswith(".apk") and not SKIP_ASSET.search(a["name"])]
        if not apks:
            continue
        apks.sort(key=lambda a: (not TV_HINT.search(a["name"]), bool(PHONE_HINT.search(a["name"])),
                                 abi_of(a["name"]) not in ("armeabi-v7a", "any", "universal"), a["size"]))
        out[full] = apks
    return out

class RangeFile(io.RawIOBase):
    BLOCK = 64 * 1024

    def __init__(self, url):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-0"})
        with urllib.request.urlopen(req, timeout=60) as r:
            self.url = r.url
            cr = r.headers.get("Content-Range", "")
            if not cr.startswith("bytes 0-0/"):
                raise IOError("server ignored Range")
            self.size = int(cr.split("/")[1])
        self.pos = 0
        self.blocks = {}
        self.fetched = 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos

    def _block(self, i):
        if i not in self.blocks:
            start = i * self.BLOCK
            end = min(self.size, start + self.BLOCK) - 1
            req = urllib.request.Request(self.url, headers={"User-Agent": UA, "Range": f"bytes={start}-{end}"})
            for attempt in range(4):
                try:
                    with urllib.request.urlopen(req, timeout=60) as r:
                        self.blocks[i] = r.read()
                    break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(3 * (attempt + 1))
            else:
                raise IOError("range read failed")
            self.fetched += len(self.blocks[i])
            if self.fetched > 40 * 1024 * 1024:
                raise IOError("read too much of this file")
        return self.blocks[i]

    def read(self, n=-1):
        if n < 0:
            n = self.size - self.pos
        n = max(0, min(n, self.size - self.pos))
        out = bytearray()
        while n > 0:
            b = self._block(self.pos // self.BLOCK)
            off = self.pos % self.BLOCK
            chunk = b[off:off + n]
            out += chunk
            self.pos += len(chunk)
            n -= len(chunk)
        return bytes(out)

    def readinto(self, buf):
        data = self.read(len(buf))
        buf[:len(data)] = data
        return len(data)

ATTR_BY_RES = {
    0x01010001: "label", 0x01010003: "name", 0x0101021B: "versionCode", 0x0101021C: "versionName",
    0x0101020C: "minSdkVersion", 0x01010270: "targetSdkVersion", 0x0101028E: "required",
}

def parse_axml(buf):
    strings, resmap = [], []
    pos = 8
    while pos + 8 <= len(buf):
        ctype, hsize, csize = struct.unpack_from("<HHI", buf, pos)
        if csize <= 0:
            break
        if ctype == 0x0001:
            count, _styles, flags, sstart, _ = struct.unpack_from("<IIIII", buf, pos + 8)
            utf8 = bool(flags & 0x100)
            offsets = struct.unpack_from(f"<{count}I", buf, pos + hsize)
            base = pos + sstart
            for o in offsets:
                p = base + o
                if utf8:
                    n = buf[p]; p += 2 if n & 0x80 else 1
                    n = buf[p]
                    if n & 0x80:
                        n = ((n & 0x7F) << 8) | buf[p + 1]; p += 2
                    else:
                        p += 1
                    strings.append(buf[p:p + n].decode("utf-8", "replace"))
                else:
                    n = struct.unpack_from("<H", buf, p)[0]; p += 2
                    if n & 0x8000:
                        n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]; p += 2
                    strings.append(buf[p:p + n * 2].decode("utf-16-le", "replace"))
        elif ctype == 0x0180:
            resmap = list(struct.unpack_from(f"<{(csize - hsize) // 4}I", buf, pos + hsize))
        elif ctype == 0x0102:
            _ns, name_i, astart, asize, acount = struct.unpack_from("<IIHHH", buf, pos + 16)
            tag = strings[name_i] if name_i < len(strings) else ""
            attrs = {}
            a = pos + 16 + astart
            for k in range(acount):
                _ans, an, raw, _sz, _r, dtype, data = struct.unpack_from("<IIIHBBI", buf, a + k * asize)
                key = strings[an] if an < len(strings) and strings[an] else ATTR_BY_RES.get(resmap[an] if an < len(resmap) else 0, f"#{an}")
                if dtype == 0x03:
                    val = strings[data] if data < len(strings) else ""
                elif raw != 0xFFFFFFFF and raw < len(strings):
                    val = strings[raw]
                elif dtype == 0x12:
                    val = data != 0
                elif dtype in (0x10, 0x11):
                    val = data
                else:
                    val = f"@{data:08x}"
                attrs[key] = val
            yield tag, attrs
        pos += csize

def manifest_facts(axml):
    facts = {"leanback": False, "touchRequired": None, "perms": []}
    for tag, at in parse_axml(axml):
        if tag == "manifest":
            facts["package"] = at.get("package")
            facts["versionCode"] = at.get("versionCode")
            facts["versionName"] = at.get("versionName")
        elif tag == "application":
            label = at.get("label")
            if isinstance(label, str) and label and not label.startswith("@"):
                facts["label"] = label
        elif tag == "uses-sdk":
            facts["minSdk"] = at.get("minSdkVersion")
        elif tag == "category" and at.get("name") == "android.intent.category.LEANBACK_LAUNCHER":
            facts["leanback"] = True
        elif tag == "uses-feature" and at.get("name") == "android.hardware.touchscreen":
            facts["touchRequired"] = at.get("required", True)
        elif tag in ("uses-permission", "uses-permission-sdk-23") and isinstance(at.get("name"), str):
            if at["name"] not in facts["perms"]:
                facts["perms"].append(at["name"])
    return facts

def signing_cert(f: RangeFile, zf: zipfile.ZipFile):
    f.seek(max(0, f.size - 65557))
    tail = f.read()
    eocd = tail.rfind(b"PK\x05\x06")
    if eocd >= 0:
        cd_off = struct.unpack_from("<I", tail, eocd + 16)[0]
        f.seek(cd_off - 24)
        foot = f.read(24)
        if foot[8:] == b"APK Sig Block 42":
            size = struct.unpack_from("<Q", foot, 0)[0]
            f.seek(cd_off - size - 8)
            block = f.read(size + 8)
            pairs = {}
            p = 8
            while p < len(block) - 24:
                ln, pid = struct.unpack_from("<QI", block, p)
                pairs[pid] = block[p + 12:p + 8 + ln]
                p += 8 + ln
            for pid in (0x1B93AD61, 0xF05368C0, 0x7109871A):
                if pid in pairs:
                    v = pairs[pid]

                    def lp(b, o):
                        n = struct.unpack_from("<I", b, o)[0]
                        return b[o + 4:o + 4 + n], o + 4 + n

                    signers, _ = lp(v, 0)
                    signer, _ = lp(signers, 0)
                    signed, _ = lp(signer, 0)
                    _digests, o = lp(signed, 0)
                    certs, _ = lp(signed, o)
                    cert, _ = lp(certs, 0)
                    return hashlib.sha256(cert).hexdigest()
    for name in zf.namelist():
        if re.match(r"META-INF/[^/]+\.(RSA|DSA|EC)$", name, re.I):
            pkcs7 = zf.read(name)
            pem = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-print_certs"], input=pkcs7, capture_output=True).stdout
            der = subprocess.run(["openssl", "x509", "-outform", "DER"], input=pem, capture_output=True).stdout
            if der:
                return hashlib.sha256(der).hexdigest()
    return None

def inspect_one(asset):
    f = RangeFile(asset["downloadUrl"])
    zf = zipfile.ZipFile(f)
    facts = manifest_facts(zf.read("AndroidManifest.xml"))
    facts["cert"] = signing_cert(f, zf)
    facts["bytesRead"] = f.fetched
    return facts

def inspect(work: Path):
    meta = json.loads((work / "enrich.json").read_text())
    cands = candidates(meta)
    out = work / "inspect.json"
    found = json.loads(out.read_text()) if out.exists() else {}

    def stale(full):
        tried = found.get(full)
        if tried is None or not any(t.get("leanback") for t in tried) and any("error" in t for t in tried):
            return True
        rel = meta[full]["latestRelease"]
        tags = {t.get("tag") for t in tried} - {None}
        if any(t.get("leanback") and "perms" not in t for t in tried):
            return True
        return rel["tagName"] not in tags if tags else rel["publishedAt"][:10] >= CRAWLED_BEFORE

    todo = [r for r in cands if stale(r)]
    log(f"inspect: {len(todo)} of {len(cands)} candidates to read")
    lock = threading.Lock()

    def work_on(full):
        tried = []
        tag = meta[full]["latestRelease"]["tagName"]
        for asset in cands[full][:3]:
            for wait in (30, 60, 120, 0):
                try:
                    facts = inspect_one(asset)
                    break
                except urllib.error.URLError as e:
                    if not wait or not isinstance(e.reason, OSError):
                        facts = e
                        break
                    time.sleep(wait)
                except Exception as e:
                    facts = e
                    break
            if isinstance(facts, Exception):
                e = facts
                tried.append({"asset": asset["name"], "tag": tag, "error": str(e)[:200]})
                continue
            facts["asset"] = asset["name"]
            facts["tag"] = tag
            tried.append(facts)
            if facts["leanback"]:
                break
        return full, tried

    done = 0
    with cf.ThreadPoolExecutor(6) as pool:
        for full, tried in pool.map(work_on, todo):
            with lock:
                found[full] = tried
                done += 1
                if done % 50 == 0:
                    out.write_text(json.dumps(found))
                    tv = sum(1 for v in found.values() if any(t.get("leanback") for t in v))
                    log(f"  {done} / {len(todo)} read, {tv} TV builds so far")
    out.write_text(json.dumps(found))
    log("inspect: done")

DENSITY = {"xxxhdpi": 5, "xxhdpi": 4, "xhdpi": 3, "hdpi": 2, "mdpi": 1, "nodpi": 3}

def best(paths, pattern):
    hits = [p for p in paths if re.search(pattern, p, re.I) and re.search(r"\.(png|webp|jpe?g)$", p, re.I)
            and not re.search(r"round|foreground|background|monochrome|debug|test", p, re.I)]

    def score(p):
        d = next((v for k, v in DENSITY.items() if f"-{k}" in p), 0)
        return (("fastlane" in p) or ("metadata" in p), "/main/" in p, d, -len(p))

    return max(hits, key=score) if hits else None

def art(work: Path):
    inspected = json.loads((work / "inspect.json").read_text())
    meta = json.loads((work / "enrich.json").read_text())
    out = work / "art.json"
    found = json.loads(out.read_text()) if out.exists() else {}
    todo = [r for r, v in inspected.items() if any(t.get("leanback") for t in v) and r not in found]
    log(f"art: {len(todo)} repos")

    def one(full):
        branch = (meta[full].get("defaultBranchRef") or {}).get("name") or "main"
        try:
            tree = api(f"https://api.github.com/repos/{full}/git/trees/{urllib.parse.quote(branch)}?recursive=1")
        except Exception:
            return full, {}
        paths = [t["path"] for t in tree.get("tree", []) if t["type"] == "blob"]
        raw = lambda p: f"https://raw.githubusercontent.com/{full}/{urllib.parse.quote(branch)}/{urllib.parse.quote(p)}" if p else None
        shots = sorted(p for p in paths if re.search(r"images/tvScreenshots/[^/]+\.(png|jpe?g|webp)$", p, re.I))[:4]
        title = None
        title_path = next((p for p in paths if re.search(r"metadata/android/en-(US|GB)/title\.txt$", p)), None)
        if title_path:
            try:
                req = urllib.request.Request(raw(title_path), headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=30) as r:
                    title = r.read(200).decode("utf-8", "replace").strip().splitlines()[0][:60] or None
            except Exception:
                title = None
        return full, {
            "title": title,
            "banner": raw(best(paths, r"(fastlane|metadata).*/tvBanner\.|/(mipmap|drawable)[^/]*/(app_|ic_|tv_)?banner[^/]*\.")),
            "feature": raw(best(paths, r"(fastlane|metadata).*/featureGraphic\.")),
            "icon": raw(best(paths, r"(fastlane|metadata).*/images/icon\.|/mipmap-x+hdpi/ic_launcher\.|/mipmap-x+hdpi/(app_)?icon\.")),
            "shots": [raw(p) for p in shots],
        }

    with cf.ThreadPoolExecutor(4) as pool:
        for i, (full, a) in enumerate(pool.map(one, todo)):
            found[full] = a
            if i % 100 == 0:
                out.write_text(json.dumps(found))
                log(f"  {i} / {len(todo)}")
    out.write_text(json.dumps(found))
    log("art: done")

IPTV = re.compile(r"\biptv\b|xtream|\bm3u8?\b|live ?tv|tvbox|直播", re.I)

ROOMS = [
    ("Games", r"moonlight|game ?stream|cloud gaming|\bsunshine\b"),
    ("Launchers", r"flauncher|glassos|\blauncher\b|home ?screen|wallpaper|projectivy|桌面"),
    ("Screensavers", r"screensaver|daydream|aerial|屏保"),
    ("VPN & network", r"reticulum|mihomo|urnetwork|route engine|usb/?ip|telnet|ble transport|sudoku protocol|\bvpn\b|proxy|v2ray|xray|clash|shadowsocks|wireguard|amneziawg|openvpn|\bwarp\b|sing-?box|hysteria|trojan|\btor\b|hiddify|tunnel|\bdns\b|dpi\b|censorship|блокиров|zerotier|tailscale|翻墙|代理"),
    ("Video players", r"\bmpv|\bvlc\b|just ?player|next ?player|nova video|exoplayer|\bmx player|media ?player|video ?player|视频播放器"),
    ("Streaming", r"douyin|抖音|danmu|弹幕|红果|hongguo|floatplane|kino\.?pub|rezka|voctotv|sport events|\bdrama|streaming|\bepg|youtube|tizentube|twitch|\biptv\b|xtream|live ?tv|\bm3u\b|tv channels?|sponsorblock|bilibili|哔哩哔哩|stremio|nuvio|torrserv|real-?debrid|torbox|debrid|tvbox|短剧|直播|电视"),
    ("App stores", r"f-?droid (client|store)|droid-?ify|aurora store|obtainium|app ?store|storefront|apk ?updater|\binstaller\b|revanced|vanced"),
    ("Games", r"recomp|decomp|unity engine|daggerfall|andor'?s trail|sm64|zelda|\bhalo\b|ygopro|frosthaven|gloomhaven|\bnes\b|\beden\b|cheats?\b|\brpg\b|emulat|dsemu|retro ?(game|console)|\barcade\b|moonlight|chiaki|gamestream|game ?stream|libretro|gamepad|controller|\bgames?\b|galgame|\bdoom\b|\bchess\b|kart|shooter|\bport\b of|source port|游戏"),
    ("Music", r"subsonic|navidrome|equali[sz]er|songloft|music|\baudio player\b|podcast|\bradio\b|spotify|soundcloud|\bmpd\b|lyrics|karaoke|\bktv\b|音乐"),
    ("Video", r"\bani(zen|view|tube)?\b|kino|idlix|luna ?tv|fred ?tv|airplay|scrcpy|screen ?mirror|fermata|jellyfin|emby|plex|kodi|video|\bmedia\b|movie|film|anime|动漫|\bdlna\b|subtitle|player|视频|影视|播放"),
    ("Smart home", r"home ?assistant|smart ?home|\bcctv\b|camera|\bcams?\b|surveillance|\bnvr\b|frigate|motioneye|\bmqtt\b|hyperion|ambient ?light|ambilight|\bwled\b|klipper"),
    ("Photos", r"immich|photo ?frame|fnphoto|\bphotos?\b|gallery|\bimage\b|\braw\b|picture"),
    ("Reading", r"epub|ehviewer|\bwiki|剧情|\breader\b|e-?book|\bbooks?\b|manga|comic|novel|\brss\b|news|read ?later|archive viewer|小说|漫画"),
    ("Learning", r"anki|flashcard|robotics|educat|learn|study|dictionary|quran|bible|kids|children|school|词典"),
    ("Chat & social", r"mumble|messenger|fediverse|hotline|4pda|boosty|\bchat\b|matrix|\birc\b|telegram|nostr|mastodon|activitypub|messag|social|forum"),
    ("AI", r"opencode|dictation|speech-to-text|\bai\b|\bllm\b|chatbot|chatgpt|gemini|agent|sillytavern|tavern|ollama"),
    ("Browsers", r"browser|\bweb\b|浏览器"),
    ("Files & downloads", r"torrent|\bftp\b|cloud (drive|storage)|nextcloud|webdav|\bsmb\b|\bnas\b|file (manager|transfer|sharing|explorer)|\bfiles?\b|download"),
    ("Tools", r"boot image|webview|tizen|vulkan|\bgpu\b|\bgps\b|dhizuku|proxmark|programmer|serial|coreelec|cursor|app info|timer|fitness|password|wallet|expense|bookkeeping|\badb\b|shizuku|app manager|remote|keyboard|utility|\btools?\b|settings|backup|monitor|cast|share|sync|clipboard|terminal|termux|\bcpu\b|hex editor|activit|volume|display|clock|notes?\b|markdown|office|工具"),
]

JUNK = re.compile(r"template|boilerplate|starter|build (tiny )?desktop apps", re.I)

GENERIC = {"android", "tv", "app", "client", "launcher", "player", "test", "demo", "main", "application", "androidtv"}

def good_name(n):
    return bool(n) and len(n.strip()) >= 3 and n.strip().lower() not in GENERIC

def clean_name(repo_name):
    n = re.sub(r"(?i)[-_ ]?(for[-_ ]?)?(android[-_ ]?tv|androidtv|android|tv[-_]app|app)$", "", repo_name)
    n = re.sub(r"(?i)^(aos|android)[-_]", "", n) or repo_name
    n = re.sub(r"[-_]+", " ", n).strip()
    return n[:1].upper() + n[1:]

def room_for(text):
    for room, pat in ROOMS:
        if room == "Video players" and IPTV.search(text):
            continue
        if re.search(pat, text, re.I):
            return room
    return "Other"

def short_code(full, taken):
    n = int(hashlib.sha1(full.encode()).hexdigest(), 16) % 90000 + 10000
    while n in taken:
        n = 10000 + (n - 10000 + 1) % 90000
    taken.add(n)
    return n

def downloads(m):
    return sum(a.get("downloadCount") or 0 for r in ((m.get("releases") or {}).get("nodes") or []) if r and not r["isPrerelease"] and not r["isDraft"]
               for a in ((r.get("releaseAssets") or {}).get("nodes") or []) if a and a["name"].lower().endswith(".apk"))

def build(work: Path, out_path: Path):
    meta = json.loads((work / "enrich.json").read_text())
    inspected = json.loads((work / "inspect.json").read_text())
    arts = json.loads((work / "art.json").read_text()) if (work / "art.json").exists() else {}
    previous = json.loads(out_path.read_text()) if out_path.exists() else {"apps": []}
    before = previous["apps"]
    seed = listed(work / "seed.json")
    seen_before = {x["repo"]: x.get("added") for x in seed + before}
    stars_before = {x["repo"]: x["stars"] for x in before}
    codes_before = {x["repo"]: x["code"] for x in before if x.get("code")}
    for x in seed:
        if x.get("code") and x["repo"] not in codes_before and x["code"] not in codes_before.values():
            codes_before[x["repo"]] = x["code"]
    same_day = previous.get("generated") == TODAY.strftime("%Y-%m-%d")
    since = previous.get("trendSince") if same_day else previous.get("generated")
    trend_before = {x["repo"]: x.get("trend", 0) for x in before} if same_day else {}
    by_pkg, by_parent = {}, {}
    for full, tried in inspected.items():
        tv = next((t for t in tried if t.get("leanback")), None)
        if not tv or not tv.get("package") or not isinstance(tv.get("versionCode"), int):
            continue
        m = meta.get(full)
        if not m or full in OVERRIDES.get("hide", []) or JUNK.search(full + " " + (m.get("description") or "")):
            continue
        if m.get("variant"):
            source = next((t for t in inspected.get(m["parent"]["nameWithOwner"], []) if t.get("leanback")), {})
            if tv["package"] == source.get("package"):
                continue
        rel = m.get("latestRelease")
        if not rel or not rel.get("releaseAssets"):
            continue
        parent = meta.get(m.get("forkOf") or "") or {}
        picked = tv["asset"]
        assets = [a for a in (rel["releaseAssets"]["nodes"] or []) if a and a["name"].lower().endswith(".apk") and not SKIP_ASSET.search(a["name"])]
        if TV_HINT.search(picked):
            assets = [a for a in assets if TV_HINT.search(a["name"])]
        else:
            assets = [a for a in assets if not PHONE_HINT.search(a["name"])]
        assets = [a for a in assets if family(a["name"]) == family(picked)] or [a for a in assets if a["name"] == picked]
        per_abi = {}
        for x in sorted(assets, key=lambda x: (x["name"] != picked, len(x["name"]))):
            per_abi.setdefault(abi_of(x["name"]).replace("universal", "any"), x)
        assets = list(per_abi.values())
        if not assets:
            continue
        topics = [n["topic"]["name"] for n in m["repositoryTopics"]["nodes"]]
        desc = (m.get("description") or "").strip() or (parent.get("description") or "").strip()
        a = arts.get(full, {})
        repo_name = clean_name(m["nameWithOwner"].split("/")[1])
        if not good_name(repo_name):
            repo_name = m["owner"]["login"][:1].upper() + m["owner"]["login"][1:]
        name = OVERRIDES["names"].get(full) or next((n for n in (a.get("title"), tv.get("label")) if good_name(n)), None) or repo_name
        app = {
            "id": re.sub(r"[^a-z0-9]+", "-", full.lower()).strip("-"),
            "repo": full,
            "name": name,
            "package": tv["package"],
            "versionCode": tv["versionCode"],
            "versionName": tv.get("versionName"),
            "minSdk": tv.get("minSdk") if isinstance(tv.get("minSdk"), int) else None,
            "room": OVERRIDES["rooms"].get(full) or room_for(" ".join([desc, name, m["nameWithOwner"], tv["package"]] + topics)),
            "note": OVERRIDES["notes"].get(full) or desc or "Open-source Android TV app.",
            "desc": desc,
            "topics": topics,
            "maker": m["owner"]["login"],
            "stars": m["stargazerCount"],
            "licence": ((m.get("licenseInfo") or {}).get("spdxId") or None),
            "archived": m["isArchived"],
            "pushed": (m.get("pushedAt") or "")[:10] or None,
            "downloads": downloads(m),
            "perms": tv.get("perms", []),
            "version": rel["tagName"],
            "date": rel["publishedAt"][:10],
            "certSha256": tv.get("cert"),
            "art": {k: v for k, v in a.items() if v and k != "title"},
            "apks": [{"name": x["name"], "abi": abi_of(x["name"]), "url": x["downloadUrl"], "size": x["size"],
                      **({"sha256": x["digest"][7:]} if (x.get("digest") or "").startswith("sha256:") else {})} for x in assets],
            "releases": [{"tag": r["tagName"], "date": r["publishedAt"][:10]}
                         for r in m["releases"]["nodes"] if not r["isPrerelease"] and not r["isDraft"]][:4],
        }
        if app["licence"] == "NOASSERTION":
            app["licence"] = None
        if m.get("port") or m.get("variant"):
            app["basedOn"] = m["parent"]["nameWithOwner"]
            source = meta.get(app["basedOn"]) or {}
            if app["room"] == "Other" and source and full not in OVERRIDES["rooms"]:
                source_topics = [n["topic"]["name"] for n in (source.get("repositoryTopics") or {}).get("nodes", [])]
                app["room"] = room_for(" ".join([source.get("description") or "", app["basedOn"]] + source_topics))
        if parent:
            if app["stars"] < 10:
                continue
            app["forkOf"] = m["forkOf"]
            app["parentStars"] = parent["stargazerCount"]
            by_parent.setdefault(m["forkOf"], []).append(app)
            continue
        prev = by_pkg.get(app["package"])
        if prev is None or app["stars"] > prev["stars"]:
            by_pkg[app["package"]] = app
    apps, seen = [], {}
    for x in sorted(by_pkg.values(), key=lambda x: -x["stars"]):
        key = re.sub(r"\W+", "", x["name"].lower())
        top = seen.get(key)
        if top is not None and x["stars"] < max(50, top // 20):
            continue
        seen.setdefault(key, x["stars"])
        apps.append(x)
    for fs in by_parent.values():
        apps.extend(sorted(fs, key=lambda x: (x["stars"], x["date"]), reverse=True)[:2])
    taken = set(codes_before.values())
    for x in apps:
        added = seen_before.get(x["repo"]) if x["repo"] in seen_before else TODAY.strftime("%Y-%m-%d")
        if added:
            x["added"] = added
        if since and x["repo"] in stars_before:
            x["trend"] = trend_before.get(x["repo"], 0) + x["stars"] - stars_before[x["repo"]]
        x["code"] = codes_before.get(x["repo"]) or short_code(x["repo"], taken)
    present = {x["repo"] for x in apps}
    packs = [{**p, "repos": [r for r in p["repos"] if r in present]} for p in OVERRIDES.get("packs", [])]
    out = {"generated": TODAY.strftime("%Y-%m-%d"), "apps": apps, "packs": [p for p in packs if p["repos"]]}
    if since:
        out["trendSince"] = since
    out_path.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")))
    rooms = {}
    for x in apps:
        rooms[x["room"]] = rooms.get(x["room"], 0) + 1
    log(f"build: {len(apps)} apps -> {out_path} ({out_path.stat().st_size // 1024} KB); rooms {rooms}")

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    work = Path(sys.argv[1])
    work.mkdir(parents=True, exist_ok=True)
    stages = sys.argv[2:] or ["discover", "enrich", "inspect", "art", "build"]
    for s in stages:
        log(f"== {s}")
        if s == "build":
            build(work, CATALOG)
        else:
            globals()[s](work)
    return 0

if __name__ == "__main__":
    sys.exit(main())
