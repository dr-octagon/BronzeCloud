#!/opt/hermes/.venv/bin/python
"""Apply or roll back approved BronzeCloud domain-only updates.

This command is intentionally restricted to the DiziPal pilot providers.  New
domains are read from the monitor's durable state; callers cannot supply an
arbitrary replacement URL.
"""

from __future__ import annotations

import argparse
import base64
import fcntl
import html
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

import requests


REPO_ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = Path("/opt/data/domain-monitor")
if not DATA_ROOT.parent.exists():
    DATA_ROOT = Path("/opt/hermes/data/domain-monitor")

PENDING_PATH = DATA_ROOT / "domain-watch-state.json"
HISTORY_PATH = DATA_ROOT / "domain-update-history.json"
LOCK_PATH = DATA_ROOT / "domain-update.lock"

GITHUB_REPOSITORY = "Dr.Octagon/cloudstream-turkish"
GITHUB_API = f"https://api.github.com/repos/{GITHUB_REPOSITORY}"
CI_WORKFLOW_NAME = "CloudStream Derleyici"
SSH_KEY = Path("/opt/data/ssh/cloudstream_turkish_ed25519")

TARGETS = {
    "dizipal": {
        "name": "DiziPal",
        "source": Path("DiziPal/src/main/kotlin/com/keyiflerolsun/DiziPal.kt"),
        "gradle": Path("DiziPal/build.gradle.kts"),
        "manifest_name": "DiziPal",
    },
    "dizipal_original": {
        "name": "DiziPalOriginal",
        "source": Path(
            "DiziPalOriginal/src/main/kotlin/com/keyiflerolsun/DiziPalOriginal.kt"
        ),
        "gradle": Path("DiziPalOriginal/build.gradle.kts"),
        "manifest_name": "DiziPalOriginal",
    },
}

TARGET_ALIASES = {
    "dizipal": "dizipal",
    "dizipaloriginal": "dizipal_original",
    "dizipal_original": "dizipal_original",
}

MAIN_URL_RE = re.compile(
    r'(override\s+var\s+mainUrl\s*=\s*")([^"]+)(")'
)
VERSION_RE = re.compile(r"(?m)^(version\s*=\s*)(\d+)(\s*)$")
DIZIPAL_HOST_RE = re.compile(
    r"^(?:www\.)?dizipal\d+\.[a-z]{2,24}$", re.IGNORECASE
)
TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; Mobile) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Mobile Safari/537.36"
)


class DomainActionError(RuntimeError):
    pass


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def normalize_url(value: str) -> str:
    return value.strip().rstrip("/")


def host_of(value: str) -> str:
    return (urlparse(value).hostname or "").lower().rstrip(".")


def validate_url_shape(value: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme != "https" or parsed.username or parsed.password:
        raise DomainActionError("Aday adres guvenli bir HTTPS URL degil.")
    if parsed.port not in (None, 443):
        raise DomainActionError("Aday adres standart disi port kullaniyor.")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise DomainActionError("Aday adres yalnizca site kokeni olmali.")
    if not DIZIPAL_HOST_RE.fullmatch(host_of(value)):
        raise DomainActionError("Aday adres DiziPal alan adi kalibina uymuyor.")


def read_json(path: Path, default):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return default


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temp, path)


def run_git(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    if SSH_KEY.exists():
        env["GIT_SSH_COMMAND"] = (
            f"ssh -i {SSH_KEY} -o IdentitiesOnly=yes "
            "-o StrictHostKeyChecking=accept-new"
        )
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        text=True,
        capture_output=True,
        env=env,
    )
    if check and result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise DomainActionError(f"Git komutu basarisiz: git {' '.join(args)}\n{detail}")
    return result


def normalize_target(value: str | None, available: list[str]) -> str:
    if value:
        key = TARGET_ALIASES.get(value.lower().replace("-", "_"))
        if not key or key not in TARGETS:
            raise DomainActionError("Yalnizca DiziPal ve DiziPalOriginal destekleniyor.")
        return key
    if len(available) == 1:
        return available[0]
    if not available:
        raise DomainActionError("Bekleyen domain guncellemesi yok.")
    names = ", ".join(TARGETS[key]["name"] for key in available)
    raise DomainActionError(f"Birden fazla bekleyen guncelleme var: {names}")


def extract_main_url(text: str) -> str:
    match = MAIN_URL_RE.search(text)
    if not match:
        raise DomainActionError("mainUrl satiri bulunamadi.")
    return normalize_url(match.group(2))


def extract_version(text: str) -> int:
    match = VERSION_RE.search(text)
    if not match:
        raise DomainActionError("Eklenti surumu bulunamadi.")
    return int(match.group(2))


def replace_main_url(text: str, old_url: str, new_url: str) -> str:
    matches = list(MAIN_URL_RE.finditer(text))
    if len(matches) != 1 or normalize_url(matches[0].group(2)) != old_url:
        raise DomainActionError("mainUrl beklenen eski adresle eslesmiyor.")
    updated, count = MAIN_URL_RE.subn(
        lambda match: f"{match.group(1)}{new_url}{match.group(3)}", text, count=1
    )
    if count != 1:
        raise DomainActionError("mainUrl guvenli sekilde degistirilemedi.")
    return updated


def bump_version(text: str) -> tuple[str, int, int]:
    old_version = extract_version(text)
    new_version = old_version + 1
    updated, count = VERSION_RE.subn(
        lambda match: f"{match.group(1)}{new_version}{match.group(3)}",
        text,
        count=1,
    )
    if count != 1:
        raise DomainActionError("Surum guvenli sekilde artirilamadi.")
    return updated, old_version, new_version


def verify_candidate(candidate_url: str) -> str:
    validate_url_shape(candidate_url)
    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
        }
    )
    last_error = ""
    for attempt in range(2):
        try:
            response = session.get(
                candidate_url, allow_redirects=True, timeout=(6, 20)
            )
            final_url = normalize_url(response.url)
            title_match = TITLE_RE.search(response.text)
            title = (
                re.sub(r"\s+", " ", html.unescape(title_match.group(1))).strip()
                if title_match
                else ""
            )
            if response.status_code != 200:
                last_error = f"HTTP {response.status_code}"
            elif host_of(final_url) != host_of(candidate_url):
                last_error = f"aday baska domaine yonlendi: {final_url}"
            elif "text/html" not in response.headers.get("content-type", "").lower():
                last_error = "yanit HTML degil"
            elif len(response.text) < 500 or "dizipal" not in title.lower():
                last_error = "DiziPal site kimligi dogrulanamadi"
            else:
                return title
        except requests.RequestException as exc:
            last_error = type(exc).__name__
        if attempt == 0:
            time.sleep(2)
    raise DomainActionError(f"Aday domain yeniden dogrulanamadi: {last_error}")


def ensure_clean_and_sync() -> None:
    status = run_git("status", "--porcelain").stdout.strip()
    if status:
        raise DomainActionError("BronzeCloud calisma kopyasi temiz degil; islem durduruldu.")
    run_git("checkout", "master")
    run_git("fetch", "origin", "master")
    run_git("merge", "--ff-only", "origin/master")
    status = run_git("status", "--porcelain").stdout.strip()
    if status:
        raise DomainActionError("Git senkronizasyonundan sonra beklenmeyen degisiklik var.")


def validate_diff(expected_paths: set[str]) -> None:
    run_git("diff", "--check")
    changed = {
        line.strip().replace("\\", "/")
        for line in run_git("diff", "--name-only").stdout.splitlines()
        if line.strip()
    }
    if changed != expected_paths:
        raise DomainActionError(
            "Beklenmeyen dosya degisikligi: " + ", ".join(sorted(changed))
        )


def commit_changes(target: dict, new_url: str, new_version: int, action: str) -> str:
    source_path = str(target["source"]).replace("\\", "/")
    gradle_path = str(target["gradle"]).replace("\\", "/")
    run_git("add", "--", source_path, gradle_path)
    verb = "update" if action == "apply" else "roll back"
    message = f"fix({target['name']}): {verb} domain to {host_of(new_url)} (v{new_version})"
    run_git("commit", "-m", message)
    return run_git("rev-parse", "HEAD").stdout.strip()


def github_get(path: str, params: dict | None = None) -> requests.Response:
    response = requests.get(
        f"{GITHUB_API}/{path}",
        params=params,
        headers={"Accept": "application/vnd.github+json", "User-Agent": "BronzeCloud-Hermes"},
        timeout=(6, 25),
    )
    response.raise_for_status()
    return response


def wait_for_ci(commit: str, timeout: int) -> tuple[str, str]:
    deadline = time.monotonic() + timeout
    run_url = ""
    while time.monotonic() < deadline:
        try:
            runs = github_get(
                "actions/runs",
                {"head_sha": commit, "event": "push", "per_page": 20},
            ).json().get("workflow_runs", [])
            matching = [run for run in runs if run.get("name") == CI_WORKFLOW_NAME]
            if matching:
                run = matching[0]
                run_url = str(run.get("html_url") or "")
                if run.get("status") == "completed":
                    conclusion = str(run.get("conclusion") or "unknown")
                    return conclusion, run_url
        except (requests.RequestException, ValueError):
            pass
        time.sleep(15)
    return "timeout", run_url


def wait_for_live_manifest(target: dict, version: int, timeout: int = 300) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            payload = github_get(
                "contents/plugins_stable.json", {"ref": "builds"}
            ).json()
            content = base64.b64decode(payload["content"]).decode("utf-8")
            plugins = json.loads(content)
            entry = next(
                (
                    item
                    for item in plugins
                    if item.get("internalName") == target["manifest_name"]
                ),
                None,
            )
            if entry and int(entry.get("version", -1)) >= version:
                package = requests.get(
                    str(entry.get("url")), timeout=(6, 40), allow_redirects=True
                )
                if package.status_code == 200 and len(package.content) > 1000:
                    return True
        except (requests.RequestException, ValueError, KeyError, TypeError):
            pass
        time.sleep(15)
    return False


def mutate_and_publish(
    target_key: str,
    old_url: str,
    new_url: str,
    action: str,
    wait_seconds: int,
) -> dict:
    target = TARGETS[target_key]
    source_path = REPO_ROOT / target["source"]
    gradle_path = REPO_ROOT / target["gradle"]
    source_before = source_path.read_text(encoding="utf-8")
    gradle_before = gradle_path.read_text(encoding="utf-8")

    current_url = extract_main_url(source_before)
    if current_url != old_url:
        raise DomainActionError(
            f"Kod beklenen adreste degil: beklenen={old_url}, mevcut={current_url}"
        )

    if action == "apply":
        title = verify_candidate(new_url)
    else:
        title = "Kullanici onayli geri alma"

    source_after = replace_main_url(source_before, old_url, new_url)
    gradle_after, old_version, new_version = bump_version(gradle_before)

    committed = False
    expected_paths = {
        str(target["source"]).replace("\\", "/"),
        str(target["gradle"]).replace("\\", "/"),
    }
    try:
        source_path.write_text(source_after, encoding="utf-8")
        gradle_path.write_text(gradle_after, encoding="utf-8")
        validate_diff(expected_paths)
        commit = commit_changes(target, new_url, new_version, action)
        committed = True
        run_git("push", "origin", "master")
    finally:
        if not committed:
            source_path.write_text(source_before, encoding="utf-8")
            gradle_path.write_text(gradle_before, encoding="utf-8")
            run_git("add", "--", *sorted(expected_paths), check=False)

    conclusion, workflow_url = wait_for_ci(commit, wait_seconds)
    live = conclusion == "success" and wait_for_live_manifest(target, new_version)
    return {
        "action": action,
        "target": target_key,
        "name": target["name"],
        "old_url": old_url,
        "new_url": new_url,
        "old_version": old_version,
        "new_version": new_version,
        "site_title": title,
        "commit": commit,
        "commit_url": f"https://github.com/{GITHUB_REPOSITORY}/commit/{commit}",
        "ci_conclusion": conclusion,
        "workflow_url": workflow_url,
        "live_manifest_verified": live,
        "timestamp": now_iso(),
    }


def pending_entries() -> dict[str, dict]:
    state = read_json(PENDING_PATH, {"notified": {}})
    notified = state.get("notified", {}) if isinstance(state, dict) else {}
    return {
        key: value
        for key, value in notified.items()
        if key in TARGETS
        and isinstance(value, dict)
        and value.get("delivered") is True
        and value.get("source_url")
        and value.get("candidate_url")
    }


def status_command() -> int:
    pending = pending_entries()
    history = read_json(HISTORY_PATH, {"updates": []}).get("updates", [])
    print(
        json.dumps(
            {
                "pending": [
                    {
                        "target": key,
                        "name": TARGETS[key]["name"],
                        "source_url": value["source_url"],
                        "candidate_url": value["candidate_url"],
                    }
                    for key, value in pending.items()
                ],
                "last_update": history[-1] if history else None,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


def verify_command(target_arg: str | None) -> int:
    pending = pending_entries()
    target_key = normalize_target(target_arg, list(pending))
    item = pending[target_key]
    candidate_url = normalize_url(str(item["candidate_url"]))
    title = verify_candidate(candidate_url)
    print(
        json.dumps(
            {
                "target": target_key,
                "name": TARGETS[target_key]["name"],
                "source_url": normalize_url(str(item["source_url"])),
                "candidate_url": candidate_url,
                "site_title": title,
                "verified": True,
                "timestamp": now_iso(),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


def apply_command(target_arg: str | None, wait_seconds: int) -> int:
    pending = pending_entries()
    target_key = normalize_target(target_arg, list(pending))
    item = pending[target_key]
    old_url = normalize_url(str(item["source_url"]))
    new_url = normalize_url(str(item["candidate_url"]))
    validate_url_shape(new_url)

    ensure_clean_and_sync()
    result = mutate_and_publish(target_key, old_url, new_url, "apply", wait_seconds)

    history = read_json(HISTORY_PATH, {"updates": []})
    history.setdefault("updates", []).append(result)
    write_json(HISTORY_PATH, history)

    state = read_json(PENDING_PATH, {"notified": {}})
    state.setdefault("notified", {}).pop(target_key, None)
    write_json(PENDING_PATH, state)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ci_conclusion"] == "success" and result["live_manifest_verified"] else 2


def rollback_command(target_arg: str | None, wait_seconds: int) -> int:
    history = read_json(HISTORY_PATH, {"updates": []})
    updates = history.get("updates", [])
    candidates = [
        item
        for item in updates
        if isinstance(item, dict)
        and item.get("action") == "apply"
        and not item.get("rolled_back_by")
        and item.get("target") in TARGETS
    ]
    available = list(dict.fromkeys(item["target"] for item in reversed(candidates)))
    target_key = normalize_target(target_arg, available)
    original = next(item for item in reversed(candidates) if item["target"] == target_key)

    ensure_clean_and_sync()
    result = mutate_and_publish(
        target_key,
        normalize_url(original["new_url"]),
        normalize_url(original["old_url"]),
        "rollback",
        wait_seconds,
    )
    result["rollback_of"] = original["commit"]
    original["rolled_back_by"] = result["commit"]
    updates.append(result)
    write_json(HISTORY_PATH, history)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ci_conclusion"] == "success" and result["live_manifest_verified"] else 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("status")
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("target", nargs="?")
    apply_parser = subparsers.add_parser("apply")
    apply_parser.add_argument("target", nargs="?")
    apply_parser.add_argument("--wait-seconds", type=int, default=900)
    rollback_parser = subparsers.add_parser("rollback")
    rollback_parser.add_argument("target", nargs="?")
    rollback_parser.add_argument("--wait-seconds", type=int, default=900)
    args = parser.parse_args()

    DATA_ROOT.mkdir(parents=True, exist_ok=True)
    with LOCK_PATH.open("a+", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise DomainActionError("Baska bir domain islemi halen calisiyor.") from exc

        if args.command == "status":
            return status_command()
        if args.command == "verify":
            return verify_command(args.target)
        if args.command == "apply":
            return apply_command(args.target, max(60, args.wait_seconds))
        return rollback_command(args.target, max(60, args.wait_seconds))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DomainActionError as exc:
        print(f"HATA: {exc}", file=sys.stderr)
        raise SystemExit(1)
