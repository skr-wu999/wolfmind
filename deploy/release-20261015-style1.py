"""One-time release of the synchronized frontend/backend images to the existing host."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tarfile
import time
import urllib.request

os.umask(0o077)
release = "20261015-style1"
image_file = Path("/tmp/wolfmind-images-20261015-style1.tar")
expected_sha = "5bf93e89b61bf9fe548a1e5604b26958818d22ffb6fcf7542701381164de7eca"
deploy = Path("/opt/wolfmind/deploy")
override = deploy / "docker-compose.aliyun.yml"
compose = ["docker", "compose", "-f", "docker-compose.yml", "-f", override.name]
backup = Path("/opt/wolfmind/backups") / (release + "-" + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()))


def run(args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def dc(*args):
    return run(compose + list(args), cwd=deploy)


def fetch(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return response.read()


def inspect(name):
    return json.loads(run(["docker", "inspect", name]))[0]


def states():
    keys = run(["docker", "exec", "wolfmind-redis-1", "redis-cli", "--scan", "--pattern", "game:*:state"]).splitlines()
    result = {}
    for key in keys:
        value = run(["docker", "exec", "wolfmind-redis-1", "redis-cli", "--raw", "HGET", key, "state"])
        if value:
            state = json.loads(value)
            result[state["gameId"]] = {"status": state["status"], "version": state["version"]}
    return result


def volume_source(container, destination, expected_name):
    mount = next(m for m in inspect(container)["Mounts"] if m["Destination"] == destination)
    assert mount["Type"] == "volume" and mount["Name"] == expected_name
    source = Path(mount["Source"]).resolve()
    assert source == Path("/var/lib/docker/volumes") / expected_name / "_data"
    return source


def sqlite_check(directory):
    connection = sqlite3.connect("file:" + str(directory / "wolfmind.db") + "?mode=ro", uri=True)
    try:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        assert integrity == "ok", integrity
        tables = connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchall()
        return {name: connection.execute('SELECT count(*) FROM "' + name.replace('"', '""') + '"').fetchone()[0] for (name,) in tables}
    finally:
        connection.close()


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


assert hashlib.file_digest(image_file.open("rb"), "sha256").hexdigest() == expected_sha
before_states = states()
assert not any(s["status"] in ("RUNNING", "INIT") for s in before_states.values()), "Active game: defer maintenance"
metrics = json.loads(fetch("http://127.0.0.1:8080/api/v1/metrics"))
assert metrics["gauges"].get("wolfmind.scheduler.running", 0) == 0
before_config = json.loads(dc("config", "--format", "json"))
before_static = hashlib.sha256(fetch("http://127.0.0.1/")).hexdigest()
before_redis_id = inspect("wolfmind-redis-1")["Id"]
sqlite_source = volume_source("wolfmind-backend-1", "/app/data", "wolfmind_wolfmind-data")
redis_source = volume_source("wolfmind-redis-1", "/data", "wolfmind_wolfmind-redis-data")
old_images = {s: inspect("wolfmind-" + s + "-1")["Config"]["Image"] for s in ("backend", "frontend")}
print(json.dumps({"preflight": "PASS", "games": before_states, "oldImages": old_images}), flush=True)
print(run(["docker", "load", "-i", str(image_file)]), flush=True)

backup.mkdir(mode=0o700, parents=True, exist_ok=False)
for name in ("docker-compose.yml", "docker-compose.aliyun.yml", ".env"):
    shutil.copy2(deploy / name, backup / name)
save_json(backup / "effective-before.json", before_config)
env_sha = hashlib.sha256((deploy / ".env").read_bytes()).hexdigest()
save_json(backup / "release-before.json", {"images": old_images, "states": before_states, "redisContainer": before_redis_id, "staticSiteSha256": before_static})
backend_stopped = False
redis_paused = False
config_changed = False

try:
    dc("stop", "backend")
    backend_stopped = True
    assert not any(s["status"] in ("RUNNING", "INIT") for s in states().values()), "Game started during preflight"
    run(["tar", "-C", str(sqlite_source), "-czf", str(backup / "sqlite-data.tar.gz"), "."])
    assert run(["docker", "exec", "wolfmind-redis-1", "redis-cli", "SAVE"]) == "OK"
    run(["docker", "pause", "wolfmind-redis-1"])
    redis_paused = True
    try:
        run(["tar", "-C", str(redis_source), "-czf", str(backup / "redis-data.tar.gz"), "."])
    finally:
        run(["docker", "unpause", "wolfmind-redis-1"])
        redis_paused = False
    for name in ("sqlite", "redis"):
        target = backup / (name + "-verify")
        target.mkdir(mode=0o700)
        with tarfile.open(backup / (name + "-data.tar.gz")) as archive:
            archive.extractall(target, filter="data")
    db_counts = sqlite_check(backup / "sqlite-verify")
    redis_check = run(["docker", "run", "--rm", "--pull", "never", "--network", "none", "--memory", "128m", "--entrypoint", "redis-check-rdb", "-v", str(backup / "redis-verify") + ":/verify:ro", "redis:8-alpine", "/verify/dump.rdb"])
    print(json.dumps({"backup": str(backup), "sqliteIntegrity": "ok", "tableCounts": db_counts, "redisRdb": "verified"}), flush=True)
    (backup / "redis-check.log").write_text(redis_check + "\n", encoding="utf-8")

    text = override.read_text(encoding="utf-8")
    expected_config = copy.deepcopy(before_config)
    for service in ("backend", "frontend"):
        previous = before_config["services"][service]["image"]
        assert text.count(previous) == 1, "Ambiguous image entry"
        replacement = "wolfmind-" + service + ":" + release
        text = text.replace(previous, replacement, 1)
        expected_config["services"][service]["image"] = replacement
    temp = override.with_suffix(".yml." + release + ".tmp")
    temp.write_text(text, encoding="utf-8")
    temp.replace(override)
    config_changed = True
    after_config = json.loads(dc("config", "--format", "json"))
    assert after_config == expected_config, "Configuration changed beyond the two image tags"
    assert hashlib.sha256((deploy / ".env").read_bytes()).hexdigest() == env_sha
    dc("up", "-d", "--no-build", "--pull", "never", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "120", "backend", "frontend")
    health = json.loads(fetch("http://127.0.0.1:8080/api/v1/health"))
    assert health["status"] == "UP"
    assert inspect("wolfmind-redis-1")["Id"] == before_redis_id
    assert hashlib.sha256(fetch("http://127.0.0.1/")).hexdigest() == before_static
    assert states() == before_states, "Stored game status or version changed"
    live_db_counts = sqlite_check(sqlite_source)
    assert live_db_counts == db_counts, "Database row counts changed"
    result = {"release": release, "sha256": expected_sha, "backup": str(backup), "health": health, "tableCounts": live_db_counts, "gameStates": before_states, "configOnlyImagesChanged": True, "redisContainerPreserved": True, "staticSitePreserved": True, "images": {s: inspect("wolfmind-" + s + "-1")["Config"]["Image"] for s in ("backend", "frontend")}}
    save_json(backup / "release-after.json", result)
    print(json.dumps(result, ensure_ascii=False), flush=True)
except Exception:
    if redis_paused:
        run(["docker", "unpause", "wolfmind-redis-1"])
    if config_changed:
        shutil.copy2(backup / override.name, override)
        print("Restoring the previous application image tags", flush=True)
        dc("up", "-d", "--no-build", "--pull", "never", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "120", "backend", "frontend")
    elif backend_stopped:
        run(["docker", "start", "wolfmind-backend-1"])
    raise
