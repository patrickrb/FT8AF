#!/usr/bin/env python3
"""Poll Google Play Developer API for track contents; post to Discord on changes.

State file is a JSON snapshot of {track_name: [releases]} from the last run.
On first run (empty state) we snapshot and stay silent so we don't spam every
existing release. On subsequent runs we diff per-versionCode and emit a Discord
embed when a versionCode appears in a track it wasn't in before — distinguishing
"promotion" (was already in a lower-priority track) from "new" (first sighting).
"""
import json
import os
import sys
from pathlib import Path

import requests
from google.auth.transport.requests import Request as GAuthRequest
from google.oauth2 import service_account

PACKAGE = os.environ["PACKAGE_NAME"]
WEBHOOK = os.environ["DISCORD_WEBHOOK_URL"]
SVC_JSON = os.environ["PLAY_SERVICE_ACCOUNT_JSON"]

# Tracks we care about, ordered low → high. "beta" is the API name for closed testing.
TRACK_ORDER = ["internal", "beta", "production"]
TRACK_LABEL = {"internal": "Internal testing", "beta": "Closed testing", "production": "Production"}
TRACK_COLOR = {"internal": 3447003, "beta": 15844367, "production": 3066993}
TRACK_RANK = {t: i for i, t in enumerate(TRACK_ORDER)}

API = "https://androidpublisher.googleapis.com/androidpublisher/v3"


def play_session():
    creds = service_account.Credentials.from_service_account_info(
        json.loads(SVC_JSON),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    creds.refresh(GAuthRequest())
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {creds.token}"
    return s


def fetch_tracks(s):
    """Return {track_name: [{name, status, versionCodes:[...]}, ...]} for the watched tracks."""
    edit = s.post(f"{API}/applications/{PACKAGE}/edits", timeout=30)
    edit.raise_for_status()
    edit_id = edit.json()["id"]
    try:
        out = {}
        for t in TRACK_ORDER:
            r = s.get(f"{API}/applications/{PACKAGE}/edits/{edit_id}/tracks/{t}", timeout=30)
            if r.status_code == 404:
                out[t] = []
                continue
            r.raise_for_status()
            releases = []
            for rel in r.json().get("releases", []):
                releases.append({
                    "name": rel.get("name") or "",
                    "status": rel.get("status") or "",
                    "versionCodes": sorted(int(v) for v in (rel.get("versionCodes") or [])),
                })
            out[t] = releases
        return out
    finally:
        # Delete the edit so we don't leave it hanging — these are read-only ops anyway.
        s.delete(f"{API}/applications/{PACKAGE}/edits/{edit_id}", timeout=30)


def index_by_version(state):
    """{versionCode: {track: release_name}} for diffing."""
    idx = {}
    for track, releases in state.items():
        for rel in releases:
            for vc in rel["versionCodes"]:
                idx.setdefault(vc, {})[track] = rel["name"]
    return idx


def post_discord(embeds):
    for i in range(0, len(embeds), 10):  # Discord caps at 10 embeds per message
        r = requests.post(WEBHOOK, json={"embeds": embeds[i:i + 10]}, timeout=15)
        r.raise_for_status()


def build_embeds(prev_idx, curr_idx, current):
    """Diff prev vs current; emit one embed per (versionCode, newly-entered track)."""
    # Build a lookup of current release status by (track, versionCode).
    status_by = {}
    for t, releases in current.items():
        for rel in releases:
            for vc in rel["versionCodes"]:
                status_by[(t, vc)] = rel.get("status") or "—"

    embeds = []
    for vc in sorted(curr_idx.keys()):
        curr_tracks = curr_idx[vc]
        prev_tracks = prev_idx.get(vc, {})
        for t, name in curr_tracks.items():
            if t in prev_tracks:
                continue  # already known in this track
            lower = [pt for pt in prev_tracks if TRACK_RANK.get(pt, -1) < TRACK_RANK[t]]
            if lower:
                from_t = max(lower, key=lambda x: TRACK_RANK[x])
                title = f"⬆️ Promoted to {TRACK_LABEL[t]}"
                desc = (
                    f"**{name or f'versionCode {vc}'}** "
                    f"promoted **{TRACK_LABEL[from_t]} → {TRACK_LABEL[t]}**"
                )
            else:
                title = f"🆕 New release in {TRACK_LABEL[t]}"
                desc = f"**{name or f'versionCode {vc}'}** appeared in **{TRACK_LABEL[t]}**"
            embeds.append({
                "title": title,
                "description": desc,
                "color": TRACK_COLOR.get(t, 5814783),
                "fields": [
                    {"name": "Release", "value": name or "(unnamed)", "inline": True},
                    {"name": "versionCode", "value": str(vc), "inline": True},
                    {"name": "Status", "value": status_by.get((t, vc), "—"), "inline": True},
                ],
            })
    return embeds


def main(state_path):
    state_path = Path(state_path)
    previous = {}
    if state_path.exists():
        try:
            previous = json.loads(state_path.read_text() or "{}")
        except json.JSONDecodeError:
            previous = {}

    s = play_session()
    current = fetch_tracks(s)

    # Always write the new snapshot first so the state branch picks it up regardless of notify outcome.
    state_path.write_text(json.dumps(current, indent=2, sort_keys=True))

    if not previous:
        print("First run — snapshotted state, not notifying.")
        return

    prev_idx = index_by_version(previous)
    curr_idx = index_by_version(current)
    embeds = build_embeds(prev_idx, curr_idx, current)

    if not embeds:
        print("No track changes detected.")
        return

    print(f"Posting {len(embeds)} Discord embed(s).")
    post_discord(embeds)


if __name__ == "__main__":
    main(sys.argv[1])
