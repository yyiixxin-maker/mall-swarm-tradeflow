"""Manage only this checkout's two frontend development servers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
STATE = HERE / ".runtime"
APPS = {"admin": ("mall-admin-web", "dev", 15173), "mobile": ("mall-app-web", "dev:h5", 15174)}
MARKER = hashlib.sha256(str(HERE).lower().encode()).hexdigest()
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def metadata(pid):
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                             "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); "
                             f"Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}' | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"],
                            capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
    text = result.stdout.decode("utf-8", "replace").strip()
    return json.loads(text) if text else {}


def owned(data):
    command = data.get("CommandLine") or ""
    return str(Path(__file__).resolve()).lower() in command.lower() and "_serve" in command and MARKER in command


def listening(port):
    with socket.socket() as connection:
        connection.settimeout(1)
        return connection.connect_ex(("127.0.0.1", port)) == 0


def ready(port):
    try:
        with OPENER.open(f"http://127.0.0.1:{port}/", timeout=5) as response:
            return response.status == 200 and "html" in response.headers.get("Content-Type", "")
    except (OSError, urllib.error.URLError):
        return False


def registry():
    path = STATE / "processes.json"
    return json.loads(path.read_text("utf-8")) if path.exists() else {}


def save(data):
    STATE.mkdir(exist_ok=True)
    (STATE / "processes.json").write_text(json.dumps(data, indent=2), "utf-8")


def serve(name):
    directory, script, port = APPS[name]
    node = shutil.which("node")
    npm = shutil.which("npm.cmd")
    cli = Path(npm).parent / "node_modules/npm/bin/npm-cli.js" if npm else None
    if not node or not cli or not cli.is_file():
        raise RuntimeError("Node.js/npm must be installed and on PATH")
    command = [node, str(cli), "run", script, "--", "--host", "127.0.0.1", "--port", str(port), "--strictPort"]
    child = subprocess.Popen(command, cwd=HERE / directory, creationflags=subprocess.CREATE_NO_WINDOW)
    sys.exit(child.wait())


def start():
    state = registry()
    template = HERE / "mall-admin-web/.env.tradeflow.example"
    for filename in (".env.development.local", ".env.production.local"):
        target = HERE / "mall-admin-web" / filename
        if not target.exists() and template.exists():
            shutil.copyfile(template, target)
    for name, (directory, _, port) in APPS.items():
        if name in state and owned(metadata(state[name])):
            if not ready(port):
                raise RuntimeError(f"{name} worker is running but not ready; inspect frontend/.runtime/{name}.log")
            print(f"{name}: already running http://127.0.0.1:{port}")
            continue
        if listening(port):
            raise RuntimeError(f"Port {port} is occupied by an unowned process; nothing was stopped")
        if not (HERE / directory / "node_modules").is_dir():
            raise RuntimeError(f"Install dependencies in frontend/{directory} first")
        STATE.mkdir(exist_ok=True)
        with (STATE / f"{name}.log").open("ab") as output:
            process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "_serve", name, "--marker", MARKER],
                                       cwd=HERE, stdout=output, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        state[name] = process.pid
        save(state)
        end = time.monotonic() + 120
        while time.monotonic() < end:
            if process.poll() is not None:
                raise RuntimeError(f"{name} exited; inspect frontend/.runtime/{name}.log")
            if ready(port):
                print(f"{name}: UP http://127.0.0.1:{port}", flush=True)
                break
            time.sleep(1)
        else:
            raise RuntimeError(f"{name} did not become ready; inspect frontend/.runtime/{name}.log")


def stop():
    state = registry()
    for name, pid in list(state.items()):
        data = metadata(pid)
        if not data:
            del state[name]
            continue
        if not owned(data):
            raise RuntimeError(f"Refusing to stop unowned PID for {name}; no backend processes were touched")
        result = subprocess.run(["taskkill", "/PID", str(int(pid)), "/T", "/F"], capture_output=True)
        if result.returncode:
            raise RuntimeError(f"Could not stop the {name} frontend worker")
        del state[name]
        save(state)
        print(f"{name}: stopped frontend process tree")
    save(state)


def check():
    state = registry()
    failed = []
    for name, (_, _, port) in APPS.items():
        healthy = name in state and owned(metadata(state[name])) and ready(port)
        print(f"{name}: {'UP' if healthy else 'DOWN'} http://127.0.0.1:{port}")
        if not healthy:
            failed.append(name)
    if failed:
        raise RuntimeError("Frontend services are not ready: " + ", ".join(failed))


def self_test():
    command = f'python "{Path(__file__).resolve()}" _serve admin --marker {MARKER}'
    assert owned({"CommandLine": command})
    assert not owned({"CommandLine": "java MallPortalApplication"})
    assert not owned({"CommandLine": command.replace(MARKER, "different-workspace")})
    assert not owned({})
    assert set(port for _, _, port in APPS.values()) == {15173, 15174}
    assert all(port not in {18201, 18080, 18085} for _, _, port in APPS.values())
    print("Frontend process ownership and port self-check PASS")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "stop", "check", "self-test", "_serve"))
    parser.add_argument("name", nargs="?", choices=tuple(APPS))
    parser.add_argument("--marker")
    args = parser.parse_args()
    try:
        if args.command == "_serve":
            if args.marker != MARKER or not args.name:
                raise RuntimeError("Invalid frontend worker identity")
            serve(args.name)
        else:
            {"start": start, "stop": stop, "check": check, "self-test": self_test}[args.command]()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
