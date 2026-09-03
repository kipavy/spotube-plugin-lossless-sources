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
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# A track that exists in every region, so a miss means the source is broken
# rather than the catalogue being patchy.
PROBE_QUERY = "daft punk one more time"
PROBE_ISRC = "GBDUW0000053"
HIFI_QUALITIES = ("LOSSLESS", "HIGH")

# The Archive has no studio releases, so it is probed with something actually
# in it: a live recording collection that has been there for years.
ARCHIVE_QUERY = "collection:(etree) AND mediatype:(audio)"

HEADERS = {
    "User-Agent": "spotube-plugin-hifi-tidal source probe (+https://github.com/kipavy/spotube-plugin-hifi-tidal)",
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


def probe_archive(base: str, timeout: int) -> dict:
    """Search, open an item, and confirm a FLAC file is really downloadable."""
    result = {"search": None, "stream": None, "playable": False}

    query = urllib.parse.urlencode(
        {"q": ARCHIVE_QUERY, "fl[]": "identifier", "rows": 1, "output": "json"}
    )
    status, body = fetch(f"{base}/advancedsearch.php?{query}", timeout)
    payload = as_json(body)

    docs = ((payload or {}).get("response") or {}).get("docs") or []
    if status != 200 or not docs:
        result["search"] = f"{status} {detail_of(payload, body)}".strip()
        return result
    result["search"] = "ok"

    identifier = docs[0].get("identifier")
    status, body = fetch(f"{base}/metadata/{identifier}", timeout)
    payload = as_json(body)
    if status != 200 or not isinstance(payload, dict):
        result["stream"] = f"{status} metadata unavailable"
        return result

    flacs = [
        f["name"]
        for f in payload.get("files", [])
        if str(f.get("name", "")).lower().endswith(".flac")
    ]
    if not flacs:
        result["stream"] = f"{status} item has no flac"
        return result

    url = f"{base}/download/{identifier}/{urllib.parse.quote(flacs[0])}"
    status, _ = fetch(url, timeout, method="HEAD")
    if status not in (200, 302):
        result["stream"] = f"{status} file not downloadable"
        return result

    result["stream"] = f"ok flac ({identifier})"
    result["playable"] = True
    return result


PROBES = {
    "hifi-api": probe_hifi_api,
    "archive": probe_archive,
}


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
    args = parser.parse_args()

    path = pathlib.Path(args.file)
    document = json.loads(path.read_text())

    candidates = document.get("candidates") or []
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
