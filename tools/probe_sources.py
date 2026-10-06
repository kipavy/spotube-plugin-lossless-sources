#!/usr/bin/env python3
"""Probe candidate sources and publish the ones that actually play.

One probe per source type, each ending at a URL a player could stream. That
matters most for hifi-api, where searching is unauthenticated: a blocked
instance answers /search/ perfectly and serves no audio at all, so only a
decodable /track/ manifest counts as working.

Writes sources.json in place. Exit code is 0 whether or not anything was found:
no working source is a normal state, not a failure of this script.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# A track that exists in every region, so a miss means the source is broken
# rather than the catalogue being patchy.
PROBE_QUERY = "daft punk one more time"
PROBE_ISRC = "GBDUW0000053"
HIFI_QUALITIES = ("LOSSLESS", "HIGH")

# Uptime trackers that list hifi-api instances, checked down to the stream.
# Every host they name is probed here too; see discover_from_uptime_feeds.
UPTIME_FEEDS = (
    "https://tidal-uptime.props-76styles.workers.dev",
    "https://tidal-uptime.jiffy-puffs-1j.workers.dev",
)

# Where Monochrome's client declares the host of its own catalogue API.
MONOCHROME_SOURCES = (
    "https://raw.githubusercontent.com/monochrome-music/monochrome/main/js/tracks-api.js",
)
MONOCHROME_BASE_PATTERN = re.compile(r"TRACKS_API_BASE_URL\s*=\s*['\"](https://[^'\"]+)['\"]")
MONOCHROME_ATTEMPTS = 3

# Preference order of the published list: own-file lossless first, then the
# account proxies, which get blocked.
TYPE_ORDER = ("monochrome", "hifi-api")

HEADERS = {
    "User-Agent": "spotube-plugin-lossless-sources source probe (+https://github.com/kipavy/spotube-plugin-lossless-sources)",
    "Accept": "application/json",
}


def fetch(url: str, timeout: int, method: str = "GET") -> tuple[int, bytes]:
    request = urllib.request.Request(url, headers=HEADERS, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read() if method == "GET" else b""
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except Exception as error:  # DNS, TLS, timeout, refused
        return -1, f"{type(error).__name__}: {error}".encode()[:200]


def fetch_range(url: str, timeout: int, length: int) -> tuple[int, bytes]:
    """The first `length` bytes of a file, without downloading the rest."""
    headers = dict(HEADERS, Range=f"bytes=0-{length - 1}", Accept="*/*")
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read(length)
    except urllib.error.HTTPError as error:
        return error.code, error.read()[:length]
    except Exception as error:  # DNS, TLS, timeout, refused
        return -1, f"{type(error).__name__}: {error}".encode()[:200]


def as_json(body: bytes):
    try:
        return json.loads(body)
    except Exception:
        return None


def detail_of(payload, body: bytes) -> str:
    if isinstance(payload, dict) and payload.get("detail"):
        return str(payload["detail"])
    return body[:60].decode(errors="replace")


def probe_hifi_api(base: str, timeout: int) -> dict:
    """Search, then decode a real BTS manifest -- searching alone proves nothing."""
    result = {"search": None, "stream": None, "playable": False}

    query = urllib.parse.urlencode({"s": PROBE_QUERY, "limit": 5})
    status, body = fetch(f"{base}/search/?{query}", timeout)
    payload = as_json(body)

    if status != 200 or not isinstance(payload, dict):
        result["search"] = f"{status} {detail_of(payload, body)}".strip()
        return result

    items = (payload.get("data") or {}).get("items") or []
    if not items:
        result["search"] = f"{status} no items"
        return result
    result["search"] = "ok"

    # Prefer the exact recording, the same way the plugin ranks by ISRC.
    track = next((i for i in items if (i.get("isrc") or "").upper() == PROBE_ISRC), items[0])

    for quality in HIFI_QUALITIES:
        params = urllib.parse.urlencode({"id": track.get("id"), "quality": quality})
        status, body = fetch(f"{base}/track/?{params}", timeout)
        payload = as_json(body)

        if status != 200 or not isinstance(payload, dict) or payload.get("detail"):
            result["stream"] = f"{status} {detail_of(payload, body)}".strip()
            continue

        data = payload.get("data") or {}
        if data.get("manifestMimeType") != "application/vnd.tidal.bts":
            result["stream"] = f"{status} mime={data.get('manifestMimeType')}"
            continue

        try:
            manifest = json.loads(base64.b64decode(data["manifest"]).decode())
        except Exception as error:
            result["stream"] = f"{status} manifest undecodable: {type(error).__name__}"
            continue

        if not (manifest.get("urls") or []):
            result["stream"] = f"{status} manifest without urls"
            continue

        result["stream"] = f"ok {manifest.get('codecs')} {quality}"
        result["playable"] = True
        return result

    return result


def probe_monochrome(base: str, timeout: int) -> dict:
    """Search, then read the first bytes of the file -- it must be a real FLAC.

    The host sits behind Cloudflare and answers a transient 521 now and then,
    so the file is tried a few times before the source is written off.
    """
    result = {"search": None, "stream": None, "playable": False}

    query = urllib.parse.urlencode({"q": PROBE_QUERY, "limit": 5})
    status, body = fetch(f"{base}/search/tracks?{query}", timeout)
    payload = as_json(body)

    items = (payload or {}).get("tracks") if isinstance(payload, dict) else None
    if status != 200 or not items:
        result["search"] = f"{status} {detail_of(payload, body)}".strip()
        return result
    result["search"] = "ok"

    playable = [i for i in items if i.get("playable") is not False]
    if not playable:
        result["stream"] = "no playable result"
        return result

    track = next((i for i in playable if (i.get("isrc") or "").upper() == PROBE_ISRC), playable[0])

    for attempt in range(MONOCHROME_ATTEMPTS):
        status, head = fetch_range(f"{base}/track/{track.get('id')}", timeout, 4)
        if status in (200, 206) and head == b"fLaC":
            result["stream"] = "ok flac"
            result["playable"] = True
            return result
        result["stream"] = f"{status} not flac ({head[:16]!r})"
        if status < 500:
            break
        time.sleep(2 ** attempt)

    return result


PROBES = {
    "hifi-api": probe_hifi_api,
    "monochrome": probe_monochrome,
}


def discover_from_uptime_feeds(timeout: int) -> list[dict]:
    """hifi-api instances that public uptime trackers know about.

    Monochrome-style frontends keep these lists current; every host in them,
    up or down, becomes a candidate, so an instance that appears or comes back
    is probed here within the hour without anyone adding it by hand.
    """
    found = []
    for feed in UPTIME_FEEDS:
        status, body = fetch(feed, timeout)
        payload = as_json(body)
        if status != 200 or not isinstance(payload, dict):
            print(f"uptime feed {feed}: {status}, skipped")
            continue
        for group in ("api", "streaming", "down"):
            for item in payload.get(group) or []:
                url = item if isinstance(item, str) else (item or {}).get("url")
                if isinstance(url, str) and url.startswith("https://"):
                    found.append({"type": "hifi-api", "base": url.rstrip("/")})
    return found


def discover_monochrome(timeout: int) -> list[dict]:
    """The address Monochrome's own client currently uses for its catalogue.

    Read from its source on GitHub, so if Monochrome moves the API, the new
    host is probed and published without a release of this plugin.
    """
    found = []
    for url in MONOCHROME_SOURCES:
        status, body = fetch(url, timeout)
        if status != 200:
            print(f"monochrome source {url}: {status}, skipped")
            continue
        for base in MONOCHROME_BASE_PATTERN.findall(body.decode(errors="replace")):
            found.append({"type": "monochrome", "base": base.rstrip("/")})
    return found


def merge_candidates(candidates: list[dict], discovered: list[dict]) -> list[dict]:
    """Appends newly discovered hosts, keeping the existing order and entries.

    Discovered hosts are written back into `candidates`, so they stay probed
    even if the feed that named them later disappears.
    """
    seen = {(c.get("type"), (c.get("base") or "").rstrip("/")) for c in candidates}
    merged = list(candidates)
    for item in discovered:
        key = (item["type"], item["base"])
        if key not in seen:
            seen.add(key)
            merged.append(item)
            print(f"discovered {item['type']} {item['base']}")
    return merged


def probe(candidate: dict, timeout: int) -> dict:
    kind = candidate.get("type")
    base = (candidate.get("base") or "").rstrip("/")
    runner = PROBES.get(kind)

    if runner is None:
        return {"search": f"unknown type {kind}", "stream": None, "playable": False}
    return runner(base, timeout)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default="sources.json")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--no-discovery", action="store_true",
                        help="probe only the listed candidates")
    args = parser.parse_args()

    path = pathlib.Path(args.file)
    document = json.loads(path.read_text())

    candidates = document.get("candidates") or []
    if not args.no_discovery:
        discovered = discover_monochrome(args.timeout) + discover_from_uptime_feeds(args.timeout)
        candidates = merge_candidates(candidates, discovered)
        document["candidates"] = candidates
    if not candidates:
        print("no candidates to probe")
        return 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda c: (c, probe(c, args.timeout)), candidates))

    working = []
    status = {}
    for candidate, result in results:
        base = (candidate.get("base") or "").rstrip("/")
        key = f"{candidate.get('type')} {base}"
        status[key] = {
            "search": result["search"],
            "stream": result["stream"],
            "playable": result["playable"],
        }
        mark = "PLAYS" if result["playable"] else "     "
        print(f"{mark} {key:52} search={result['search']} stream={result['stream']}")
        if result["playable"]:
            working.append({"type": candidate["type"], "base": base})

    # The plugin takes the first source with a match, so the published order is
    # the preference order. Discovered hosts are appended to the candidates,
    # so sorting by type keeps a newly found Monochrome host ahead of every
    # hifi-api instance. sort() is stable: hosts of one type keep candidate
    # order.
    working.sort(key=lambda s: TYPE_ORDER.index(s["type"]) if s["type"] in TYPE_ORDER else len(TYPE_ORDER))

    was_playable = document.get("playable_count", 0)

    document["sources"] = working
    document["status"] = status
    document["playable_count"] = len(working)
    document["updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # Latches so a recovery is announced once, and re-arms when everything dies
    # again -- otherwise every hourly run would comment on the same issue.
    if working and not was_playable:
        document["announced_recovery"] = False
    if not working:
        document["announced_recovery"] = False

    path.write_text(json.dumps(document, indent=2) + "\n")

    print(f"\n{len(working)} of {len(candidates)} sources serve audio")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
