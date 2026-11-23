"""Check private-file denial against the two real frontend servers; reads no secrets."""
import json
from pathlib import Path
import urllib.error
import urllib.request

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
paths = ("/.runtime/does-not-exist.json", "/.git/config", "/.env", "/.env.development.local",
         "/@fs/C:/probe/.runtime/private.json", "/%2eruntime/private.json", "/.RUNTIME/private.json", "/private.pem")
results = []
for port in (15173, 15174):
    for path in paths:
        request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method="HEAD")
        try:
            with opener.open(request, timeout=10) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        assert status == 403, f"Private path was not denied on {port}: {path} HTTP{status}"
        results.append({"port": port, "path": path, "status": status})
state = Path(__file__).resolve().parent / ".runtime"
state.mkdir(exist_ok=True)
(state / "private-file-test-results.json").write_text(json.dumps(results, indent=2), "utf-8")
print(f"PASS: {len(results)} real private-file access checks returned 403")
