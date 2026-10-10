"""Regenerates the patch-bundle JSON files in bundles/ from this repo's GitHub releases.

ReVanced Manager (and compatible managers) add a remote patch bundle from a JSON file that
points at the .rvp, not from the .rvp itself. Run this after publishing a release, then
commit the updated files:

    python scripts/update_bundle_json.py

Channels (same rules as Jman-Github/ReVanced-Patch-Bundles):
    stable.json  newest regular release (pre-releases skipped)
    latest.json  newest release, including pre-releases
    dev.json     newest pre-release

Uses only the standard library and GitHub's public API (no token needed).
"""

import json
import pathlib
import urllib.request

REPO = "MojiRS/revanced-rif-patches"
ASSET = "patches.rvp"
OUT = pathlib.Path(__file__).resolve().parent.parent / "bundles"


def releases():
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/releases?per_page=100",
        headers={"Accept": "application/vnd.github+json", "User-Agent": REPO},
    )
    with urllib.request.urlopen(request) as response:
        data = json.load(response)
    usable = [
        r for r in data
        if not r.get("draft") and any(a["name"] == ASSET for a in r.get("assets", []))
    ]
    return sorted(usable, key=lambda r: r["published_at"], reverse=True)


def bundle(release):
    asset = next(a for a in release["assets"] if a["name"] == ASSET)
    return {
        "created_at": release["published_at"].rstrip("Z"),
        "description": release.get("body") or "",
        "download_url": asset["browser_download_url"],
        "signature_download_url": "N/A",
        "version": release["tag_name"],
    }


def main():
    found = releases()
    channels = {
        "stable": next((r for r in found if not r["prerelease"]), None),
        "latest": found[0] if found else None,
        "dev": next((r for r in found if r["prerelease"]), None),
    }
    OUT.mkdir(exist_ok=True)
    for name, release in channels.items():
        path = OUT / f"{name}.json"
        if release is None:
            print(f"{name}: no matching release; left {path.name} unchanged")
            continue
        path.write_text(json.dumps(bundle(release), indent=2) + "\n", encoding="utf-8")
        print(f"{name}: {release['tag_name']}")


if __name__ == "__main__":
    main()
