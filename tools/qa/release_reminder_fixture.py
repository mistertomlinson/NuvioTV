#!/usr/bin/env python3
"""Disposable Stremio add-on for Nuvio release-reminder on-device QA.

Runs ONLY on the Mac loopback interface. Connect the onn 4K Pro using:
    adb reverse tcp:18765 tcp:18765
The TV can then install http://127.0.0.1:18765/manifest.json

Commands:
    python3 tools/qa/release_reminder_fixture.py serve
    python3 tools/qa/release_reminder_fixture.py status
    python3 tools/qa/release_reminder_fixture.py release
    python3 tools/qa/release_reminder_fixture.py reset

Use a disposable Nuvio profile with LOCAL My List selected. This fixture
does not write to the user's Streaming Originals service or any API.
"""

import argparse
import html
import json
import threading
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import URLError
from urllib.parse import unquote, urlsplit
from urllib.request import urlopen

PORT = 18765
HOST = "127.0.0.1"
BASE = f"http://{HOST}:{PORT}"
LOCK = threading.Lock()
STATE = {"released": False, "requests": 0}

ITEMS = (
    {"id": "qa:movie-alpha", "type": "movie", "name": "QA Movie Alpha",
     "season": None, "color": "#3456b7", "description": "Release test: first movie."},
    {"id": "qa:series-beta", "type": "series", "name": "QA Series Beta",
     "season": 3, "color": "#8752a6", "description": "Release test: returning season three."},
    {"id": "qa:movie-gamma", "type": "movie", "name": "QA Movie Gamma",
     "season": None, "color": "#307f69", "description": "Negative control: stays in Coming Soon."},
)
BY_ID = {item["id"]: item for item in ITEMS}


def release_date(item):
    offset = 30 if item["id"] == "qa:movie-gamma" else 0
    return (date.today() + timedelta(days=offset)).isoformat()


def is_released(item):
    with LOCK:
        return STATE["released"] and item["id"] != "qa:movie-gamma"


def behavior_hints(item):
    released = is_released(item)
    hints = {
        "comingSoon": not released,
        "releaseDate": release_date(item),
        "platformId": "qa",
        "releaseReminderState": "released" if released else "coming_soon",
    }
    if item["season"] is not None:
        if released:
            hints["newSeason"] = True
            hints["newSeasonNumber"] = item["season"]
        else:
            hints["upcomingSeason"] = item["season"]
    return hints


def meta(item):
    title = item["name"]
    return {
        "id": item["id"],
        "type": item["type"],
        "name": title,
        "poster": f"{BASE}/poster/{item['id']}.svg",
        "background": f"{BASE}/backdrop/{item['id']}.svg",
        "description": item["description"],
        "releaseInfo": str(date.today().year),
        "genres": ["QA", "Test"],
        "runtime": "90 min" if item["type"] == "movie" else None,
        "status": "released" if is_released(item) else "coming soon",
        "behaviorHints": behavior_hints(item),
    }


def manifest():
    catalogs = []
    for kind, noun in (("movie", "Movies"), ("series", "Series")):
        for family, title in (("coming_soon", "Coming Soon"), ("latest", "Latest")):
            plural = "movies" if kind == "movie" else "series"
            catalogs.append({
                "type": kind, "id": f"{family}_{plural}",
                "name": f"QA {title} - {noun}", "showInHome": True,
            })
    return {
        "id": "com.nuvio.qa.release-reminders",
        "name": "Nuvio Reminder QA (local only)",
        "version": "0.0.1",
        "description": "Three disposable test titles; no streaming endpoints.",
        "resources": ["catalog", "meta"],
        "types": ["movie", "series"],
        "idPrefixes": ["qa:"],
        "catalogs": catalogs,
    }


def catalog(kind, catalog_id):
    if kind not in ("movie", "series"):
        return {"metas": []}
    plural = "movies" if kind == "movie" else "series"
    if catalog_id not in (f"coming_soon_{plural}", f"latest_{plural}"):
        return {"metas": []}
    wants_released = catalog_id.startswith("latest_")
    return {
        "metas": [
            meta(item) for item in ITEMS
            if item["type"] == kind and is_released(item) == wants_released
        ]
    }


def artwork(item, landscape=False):
    width, height = (960, 540) if landscape else (300, 450)
    title = html.escape(item["name"])
    detail = "SEASON 3" if item["season"] else "MOVIE"
    fontsize = 54 if landscape else 30
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
<rect width="{width}" height="{height}" fill="{item['color']}"/>
<rect x="18" y="18" width="{width - 36}" height="{height - 36}" rx="22" fill="none" stroke="white" stroke-opacity="0.5" stroke-width="3"/>
<text x="50%" y="35%" text-anchor="middle" fill="white" font-family="sans-serif" font-size="{fontsize}" font-weight="bold">REMINDER QA</text>
<text x="50%" y="52%" text-anchor="middle" fill="white" font-family="sans-serif" font-size="{fontsize // 2}">{title}</text>
<text x="50%" y="68%" text-anchor="middle" fill="white" font-family="sans-serif" font-size="{fontsize // 2}">{detail}</text>
</svg>""".encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, content, mime="application/json; charset=utf-8"):
        if not isinstance(content, bytes):
            content = json.dumps(content, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        route = unquote(urlsplit(self.path).path).strip("/")
        parts = route.split("/")
        with LOCK:
            STATE["requests"] += 1

        if route == "manifest.json":
            return self.reply(200, manifest())
        if route == "__qa/status":
            with LOCK:
                status = dict(STATE)
            return self.reply(200, {"mode": "released" if status["released"] else "coming_soon",
                                    "requestCount": status["requests"],
                                    "localOnly": True, "port": PORT})
        if route in ("__qa/release", "__qa/reset"):
            with LOCK:
                STATE["released"] = route == "__qa/release"
            return self.reply(200, {"mode": "released" if STATE["released"] else "coming_soon"})
        if len(parts) >= 3 and parts[0] == "catalog" and parts[2].endswith(".json"):
            return self.reply(200, catalog(parts[1], parts[2][:-5]))
        if len(parts) >= 3 and parts[0] == "meta" and parts[2].endswith(".json"):
            item = BY_ID.get(parts[2][:-5])
            return self.reply(200, {"meta": meta(item)}) if item else self.reply(404, {"error": "unknown id"})
        if len(parts) == 2 and parts[0] in ("poster", "backdrop") and parts[1].endswith(".svg"):
            item = BY_ID.get(parts[1][:-4])
            if item:
                return self.reply(200, artwork(item, landscape=parts[0] == "backdrop"),
                                  "image/svg+xml; charset=utf-8")
        return self.reply(404, {"error": "unknown endpoint"})

    def log_message(self, format, *args):
        print(f"[fixture] {self.address_string()} {format % args}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("serve", "status", "release", "reset"))
    args = parser.parse_args()
    if args.command == "serve":
        httpd = ThreadingHTTPServer((HOST, PORT), Handler)
        print(f"Fixture running at {BASE}/manifest.json (Mac loopback ONLY)", flush=True)
        print("Use: adb reverse tcp:18765 tcp:18765", flush=True)
        print("Press Ctrl+C to stop. Reset before the next run.", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nStopped.", flush=True)
        finally:
            httpd.server_close()
    else:
        url = f"{BASE}/__qa/{args.command}"
        try:
            with urlopen(url, timeout=5) as response:
                print(response.read().decode("utf-8"))
        except URLError as error:
            raise SystemExit(f"Fixture server not reachable on {BASE}: {error}") from error


if __name__ == "__main__":
    main()
