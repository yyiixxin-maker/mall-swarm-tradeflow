"""Isolated TradeFlow development environment; credentials stay in .runtime."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
STATE = HERE / ".runtime"
ENV_FILE = STATE / "secrets.env"
APPS = {
    "mall-monitor": (18101, "com.macro.mall.MallMonitorApplication"),
    "mall-admin": (18080, "com.macro.mall.MallAdminApplication"),
    "mall-portal": (18085, "com.macro.mall.portal.MallPortalApplication"),
    "mall-search": (18081, "com.macro.mall.search.MallSearchApplication"),
    "mall-auth": (18401, "com.macro.mall.auth.MallAuthApplication"),
    "mall-demo": (18082, "com.macro.mall.MallDemoApplication"),
    "mall-gateway": (18201, "com.macro.mall.MallGatewayApplication"),
}
NAMESPACE = "tradeflow-local"
GROUP = "TRADEFLOW_LOCAL"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PROCESS_MARKER = "-Dtradeflow.workspace=" + hashlib.sha256(str(ROOT).lower().encode()).hexdigest()


def credentials():
    return dict(line.split("=", 1) for line in ENV_FILE.read_text("utf-8").splitlines() if line and not line.startswith("#"))


def redact(text):
    if ENV_FILE.exists():
        for key, value in credentials().items():
            if value and any(word in key for word in ("PASSWORD", "TOKEN", "IDENTITY", "SECRET")):
                text = text.replace(value, "<redacted>")
    text = re.sub(r"(?i)\bBearer\s+[^\s\"',}]+", "Bearer <redacted>", text)
    text = re.sub(r"\beyJ[\w-]+\.[\w-]+\.[\w-]+\b", "<jwt-redacted>", text)
    return re.sub(r'''(?i)((?:password|access[_-]?token|token(?:value)?|secret(?:key)?)["']?\s*[:=]\s*["']?)[^\s"',}]+''', r"\1<redacted>", text)


def run(args, *, input=None, env=None):
    result = subprocess.run(args, cwd=ROOT, input=input, env=env, capture_output=True)
    output = (result.stdout + result.stderr).decode("utf-8", "replace")
    if result.returncode:
        raise RuntimeError(redact(output[-5000:]))
    return output


def compose(*args):
    ids = run(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=tradeflow-local"]).split()
    if ids:
        owner = run(["docker", "inspect", "--format", '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}', ids[0]]).strip()
        if Path(owner).resolve() != HERE:
            raise RuntimeError("TradeFlow containers belong to a different checkout; refusing to change them")
    return run(["docker", "compose", "--env-file", str(ENV_FILE), "-f", str(HERE / "compose.yml"), *args])


def http(url, method="GET", data=None, *, headers=None, auth=None):
    headers = dict(headers or {})
    if auth:
        encoded = base64.b64encode(":".join(auth).encode()).decode()
        headers["Authorization"] = "Basic " + encoded
    if isinstance(data, dict):
        if headers.get("Content-Type") == "application/json":
            data = json.dumps(data).encode()
        else:
            data = urllib.parse.urlencode(data).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=15) as response:
            body = response.read().decode()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP {error.code} {urllib.parse.urlsplit(url).path}") from None


def seed_sql(source):
    # shortcut: only this line-oriented Navicat dump is supported; use a SQL parser for other dump formats.
    clean = re.sub(r"(?im)^\s*DROP TABLE\b[^\r\n]*;\s*$", "", source)
    statements = re.sub(r"/\*.*?\*/|--[^\n]*", "", clean, flags=re.S)
    if re.search(r"(?im)^\s*(?:DROP|TRUNCATE|DELETE\s+FROM|GRANT|USE|UPDATE|ALTER)\b", statements) or re.search(r"(?i)\bDROP\s+TABLE\b", clean):
        raise ValueError("Seed contains a forbidden SQL keyword")
    if len(re.findall(r"(?m)^CREATE TABLE ", clean)) != 76:
        raise ValueError("Unexpected seed schema; review it before initialization")
    return clean


def prepare():
    release = Path(os.environ.get("JAVA_HOME", "")) / "release"
    if not release.is_file() or not re.search(r'^JAVA_VERSION="17\.', release.read_text("utf-8"), re.M):
        raise RuntimeError("JAVA_HOME must point to the installed JDK 17")
    STATE.mkdir(exist_ok=True)
    if "environment/.runtime/secrets.env" not in run(["git", "check-ignore", "environment/.runtime/secrets.env"]):
        raise RuntimeError("Private runtime directory must be ignored by Git")
    if not ENV_FILE.exists():
        existing = run(["docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=tradeflow-local"])
        if existing.strip():
            raise RuntimeError("Existing TradeFlow volumes found without credentials; restore the original secrets.env")
        values = {"TRADEFLOW_" + name: secrets.token_urlsafe(32) for name in (
            "MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD", "REDIS_PASSWORD", "RABBIT_PASSWORD", "RABBIT_ADMIN_PASSWORD",
            "NACOS_PASSWORD", "NACOS_APP_PASSWORD", "NACOS_IDENTITY", "MONGO_PASSWORD", "MONGO_ROOT_PASSWORD",
            "MINIO_PASSWORD", "JWT_SECRET")}
        values["TRADEFLOW_NACOS_TOKEN"] = base64.b64encode(secrets.token_bytes(48)).decode()
        values["TRADEFLOW_MYSQL_IMAGE"] = "mysql:8.4"
        monitor = (ROOT / "mall-monitor/src/main/java/com/macro/mall/config/SecuritySecureConfig.java").read_text("utf-8")
        values["TRADEFLOW_MONITOR_USER"] = re.search(r'User\.withUsername\("([^\"]+)"\)', monitor).group(1)
        values["TRADEFLOW_MONITOR_PASSWORD"] = re.search(r'passwordEncoder\.encode\("([^\"]+)"\)', monitor).group(1)
        with ENV_FILE.open("x", encoding="utf-8") as output:
            output.write("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
    values = credentials()
    additions = {}
    if "TRADEFLOW_ADMIN_USER" not in values:
        additions["TRADEFLOW_ADMIN_USER"] = "tradeflow_local"
    if "TRADEFLOW_ADMIN_PASSWORD" not in values:
        additions["TRADEFLOW_ADMIN_PASSWORD"] = secrets.token_urlsafe(32)
    if "TRADEFLOW_INTERNAL_TOKEN" not in values:
        additions["TRADEFLOW_INTERNAL_TOKEN"] = secrets.token_urlsafe(48)
    if additions:
        with ENV_FILE.open("a", encoding="utf-8") as output:
            output.write("\n".join(f"{key}={value}" for key, value in additions.items()) + "\n")
        values.update(additions)
    sql_dir = STATE / "mysql-init"
    sql_dir.mkdir(exist_ok=True)
    (sql_dir / "001-mall.sql").write_text(seed_sql((ROOT / "document/sql/mall.sql").read_text("utf-8")), "utf-8")
    mongo_dir = STATE / "mongo-init"
    mongo_dir.mkdir(exist_ok=True)
    (mongo_dir / "001-mall.js").write_text(
        'db = db.getSiblingDB("mall-port");\ndb.createUser(' + json.dumps({
            "user": "mall", "pwd": values["TRADEFLOW_MONGO_PASSWORD"],
            "roles": [{"role": "readWrite", "db": "mall-port"}]}) + ");\n", "utf-8")
    local = {
        "tradeflow.internal-token": "${TRADEFLOW_INTERNAL_TOKEN}",
        "server.address": "127.0.0.1",
        "spring.profiles.active": "dev",
        "spring.cloud.nacos.discovery.ip": "127.0.0.1",
        "spring.boot.admin.client.url": "http://127.0.0.1:18101",
        "spring.boot.admin.client.username": "${TRADEFLOW_MONITOR_USER}",
        "spring.boot.admin.client.password": "${TRADEFLOW_MONITOR_PASSWORD}",
        "management.endpoints.web.exposure.include": "health,info",
        "management.endpoint.health.show-details": "never",
        "management.endpoint.env.show-values": "never",
        "management.endpoint.configprops.show-values": "never",
        "spring.datasource.druid.stat-view-servlet.enabled": False,
        "sa-token.jwt-secret-key": "${TRADEFLOW_JWT_SECRET}",
        "logging.config": (HERE / "logback-local.xml").as_uri(),
    }
    (STATE / "application-local.yml").write_text(json.dumps(local, ensure_ascii=False, indent=2), "utf-8")
    print("Prepared ignored credentials, additive seed (76 tables), and local application settings")


def nacos_login():
    values = credentials()
    url = "http://127.0.0.1:18848/nacos"
    try:
        http(url + "/v3/auth/user/admin", "POST", {"password": values["TRADEFLOW_NACOS_PASSWORD"]})
    except RuntimeError:
        pass  # An initialized server rejects this one-time endpoint; login must still succeed.
    result = http(url + "/v3/auth/user/login", "POST", {"username": "nacos", "password": values["TRADEFLOW_NACOS_PASSWORD"]})
    token = result.get("data", result).get("accessToken")
    if not token:
        raise RuntimeError("Nacos login did not return an access token")
    return token


def configuration(module):
    common = {"logstash.host": "127.0.0.1", "logstash.enableInnerLog": False,
              "management.endpoints.web.exposure.include": "health,info",
              "management.endpoint.env.show-values": "never",
              "management.endpoint.configprops.show-values": "never",
              "management.endpoint.health.show-details": "never"}
    if module in {"admin", "portal"}:
        common["tradeflow.internal-token"] = "${TRADEFLOW_INTERNAL_TOKEN}"
    if module in {"admin", "portal", "search", "demo"}:
        common.update({
            "spring.datasource.url": "jdbc:mysql://127.0.0.1:13306/mall?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
            "spring.datasource.username": "mall", "spring.datasource.password": "${TRADEFLOW_MYSQL_PASSWORD}",
            "spring.datasource.druid.initial-size": 1, "spring.datasource.druid.min-idle": 1,
            "spring.datasource.druid.max-active": 5,
        })
    if module in {"admin", "portal", "gateway", "demo"}:
        common.update({"spring.data.redis.host": "127.0.0.1", "spring.data.redis.port": 16379,
                       "spring.data.redis.password": "${TRADEFLOW_REDIS_PASSWORD}", "spring.data.redis.database": 0})
    if module == "portal":
        common.update({
            "spring.data.mongodb.uri": "mongodb://mall:${TRADEFLOW_MONGO_PASSWORD}@127.0.0.1:27018/mall-port?authSource=mall-port",
            "spring.rabbitmq.host": "127.0.0.1", "spring.rabbitmq.port": 5673, "spring.rabbitmq.virtual-host": "/mall",
            "spring.rabbitmq.username": "mall", "spring.rabbitmq.password": "${TRADEFLOW_RABBIT_PASSWORD}",
            "spring.rabbitmq.publisher-confirm-type": "correlated", "spring.rabbitmq.publisher-returns": True,
            "spring.rabbitmq.template.mandatory": True,
            "alipay.appId": "", "alipay.sellerId": "", "alipay.appPrivateKey": "", "alipay.alipayPublicKey": "",
        })
    if module == "search":
        common["spring.elasticsearch.uris"] = "http://127.0.0.1:19200"
    if module == "admin":
        common.update({"minio.endpoint": "http://127.0.0.1:19000", "minio.bucketName": "mall",
                       "minio.accessKey": "tradeflow", "minio.secretKey": "${TRADEFLOW_MINIO_PASSWORD}",
                       # OSS eagerly creates a client; placeholders keep local startup independent of cloud credentials.
                       "aliyun.oss.endpoint": "http://127.0.0.1:19000",
                       "aliyun.oss.accessKeyId": "LOCAL_OSS_NOT_CONFIGURED",
                       "aliyun.oss.accessKeySecret": "LOCAL_OSS_NOT_CONFIGURED"})
    return json.dumps(common, ensure_ascii=False, indent=2)


def configure():
    values = credentials()
    auth = ("bootstrap", values["TRADEFLOW_RABBIT_ADMIN_PASSWORD"])
    rabbit = "http://127.0.0.1:15673/api"
    json_headers = {"Content-Type": "application/json"}
    http(rabbit + "/users/mall", "PUT", {"password": values["TRADEFLOW_RABBIT_PASSWORD"], "tags": ""}, headers=json_headers, auth=auth)
    http(rabbit + "/permissions/%2Fmall/mall", "PUT", {key: r"^mall\.order\..*" for key in ("configure", "write", "read")}, headers=json_headers, auth=auth)
    for suffix in ("", ".ttl"):
        exchange, queue = "mall.order.direct" + suffix, "mall.order.cancel" + suffix
        http(rabbit + "/exchanges/%2Fmall/" + exchange, "PUT", {"type": "direct", "durable": True, "auto_delete": False, "internal": False, "arguments": {}}, headers=json_headers, auth=auth)
        arguments = {"x-dead-letter-exchange": "mall.order.direct", "x-dead-letter-routing-key": "mall.order.cancel"} if suffix else {}
        http(rabbit + "/queues/%2Fmall/" + queue, "PUT", {"durable": True, "auto_delete": False, "arguments": arguments}, headers=json_headers, auth=auth)
        http(rabbit + f"/bindings/%2Fmall/e/{exchange}/q/{queue}", "POST", {"routing_key": queue, "arguments": {}}, headers=json_headers, auth=auth)
    print("RabbitMQ: /mall, restricted mall user, 2 durable queues and TTL/DLX bindings configured")
    token = nacos_login()
    headers = {"accessToken": token}
    console = "http://127.0.0.1:18880"
    namespaces = http(console + "/v3/console/core/namespace/list", headers=headers)["data"]
    if NAMESPACE not in {row["namespace"] for row in namespaces}:
        http(console + "/v3/console/core/namespace", "POST", {"customNamespaceId": NAMESPACE, "namespaceName": "TradeFlow local", "namespaceDesc": "Isolated local development only"}, headers=headers)
    for module in ("admin", "portal", "search", "auth", "gateway", "demo"):
        content = configuration(module)
        for profile in ("dev", "prod"):
            data_id = f"mall-{module}-{profile}.yaml"
            result = http(console + "/v3/console/cs/config", "POST", {"dataId": data_id, "groupName": GROUP, "namespaceId": NAMESPACE, "content": content, "type": "yaml", "desc": "LOCAL ONLY; not a production configuration"}, headers=headers)
            if result.get("code") != 0:
                raise RuntimeError("Nacos configuration publication failed: " + data_id)
            query = urllib.parse.urlencode({"dataId": data_id, "groupName": GROUP, "namespaceId": NAMESPACE})
            stored = http(console + "/v3/console/cs/config?" + query, headers=headers)["data"]
            if stored["md5"] != hashlib.md5(content.encode()).hexdigest():
                raise RuntimeError("Nacos configuration checksum mismatch: " + data_id)
    print("Nacos: isolated namespace/group and 12 local DataIds published and checksummed")
    auth_url = "http://127.0.0.1:18848/nacos/v3/auth"
    for endpoint, data in (
        ("user", {"username": "tradeflow", "password": values["TRADEFLOW_NACOS_APP_PASSWORD"]}),
        ("role", {"role": "TRADEFLOW_LOCAL", "username": "tradeflow"}),
        ("permission", {"role": "TRADEFLOW_LOCAL", "resource": NAMESPACE + ":*:*", "action": "rw"}),
    ):
        response = http(auth_url + "/" + endpoint, "POST", data, headers=headers)
        if response is not None and response.get("code") not in (0, 409):
            raise RuntimeError("Nacos application permission setup failed: " + endpoint)
    app_login = http(auth_url + "/user/login", "POST", {"username": "tradeflow", "password": values["TRADEFLOW_NACOS_APP_PASSWORD"]})
    app_token = app_login.get("data", app_login)["accessToken"]
    query = urllib.parse.urlencode({"dataId": "mall-portal-dev.yaml", "groupName": GROUP, "namespaceId": NAMESPACE})
    if http(console + "/v3/console/cs/config?" + query, headers={"accessToken": app_token}).get("code") != 0:
        raise RuntimeError("Nacos application user cannot read its namespace")
    print("Nacos: application account can read its isolated namespace; global admin credentials are not used by apps")


def app_environment():
    values = {**os.environ, **credentials()}
    for component in ("CONFIG", "DISCOVERY"):
        prefix = "SPRING_CLOUD_NACOS_" + component + "_"
        values.update({prefix + "SERVER_ADDR": "127.0.0.1:18848", prefix + "NAMESPACE": NAMESPACE,
                       prefix + "GROUP": GROUP, prefix + "USERNAME": "tradeflow",
                       prefix + "PASSWORD": values["TRADEFLOW_NACOS_APP_PASSWORD"]})
    return values


def owns_process(pid):
    script = f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}'; if ($p.CommandLine -and $p.CommandLine.Contains('{PROCESS_MARKER} ')) {{'owned'}}"
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True)
    return result.stdout.strip() == b"owned"


def start_apps():
    pids_file = STATE / "pids.json"
    pids = json.loads(pids_file.read_text("utf-8")) if pids_file.exists() else {}
    for module, (port, main) in APPS.items():
        if module in pids and owns_process(pids[module]):
            if http(f"http://127.0.0.1:{port}/actuator/health").get("status") != "UP":
                raise RuntimeError(module + " is running but unhealthy; review its local log before restarting")
            continue
        classpath_file = ROOT / module / "target/runtime-classpath.txt"
        if not classpath_file.exists():
            raise RuntimeError("Build runtime classpaths before starting: " + module)
        classpath = str(ROOT / module / "target/classes") + os.pathsep + classpath_file.read_text("utf-8-sig").strip()
        log = (STATE / (module + ".log")).open("ab")
        args = [str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"), "-Xms64m", "-Xmx256m", "-Dfile.encoding=UTF-8", PROCESS_MARKER, "-cp", classpath, main,
                "--spring.config.additional-location=" + (STATE / "application-local.yml").as_uri(), f"--server.port={port}"]
        process = subprocess.Popen(args, cwd=ROOT / module, env=app_environment(), stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        log.close()
        pids[module] = process.pid
        pids_file.write_text(json.dumps(pids, indent=2), "utf-8")
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            if process.poll() is not None:
                tail = (STATE / (module + ".log")).read_text("utf-8", errors="replace")[-5000:]
                raise RuntimeError(module + " exited:\n" + redact(tail))
            try:
                if http(f"http://127.0.0.1:{port}/actuator/health").get("status") == "UP":
                    print(f"{module}: UP http://127.0.0.1:{port}")
                    break
            except (RuntimeError, OSError):
                pass
            time.sleep(2)
        else:
            raise RuntimeError(module + " did not become healthy; inspect its private runtime log")


def stop():
    pids_file = STATE / "pids.json"
    if pids_file.exists():
        pids = json.loads(pids_file.read_text("utf-8"))
        for pid in pids.values():
            if owns_process(pid):
                run(["taskkill", "/PID", str(pid), "/T", "/F"])
        pids_file.write_text("{}", "utf-8")
    compose("stop")
    print("Stopped only TradeFlow processes and containers; data volumes retained")


def status():
    print(compose("ps"))
    ids = compose("ps", "-aq").split()
    health = run(["docker", "inspect", "--format", "{{.State.Running}} {{.State.Health.Status}}", *ids]).splitlines() if ids else []
    if len(health) != 7 or any(line.strip() != "true healthy" for line in health):
        raise RuntimeError("Not all seven TradeFlow middleware containers are healthy")
    unready = []
    for module, (port, _) in APPS.items():
        try:
            state = http(f"http://127.0.0.1:{port}/actuator/health")["status"]
            print(module, state)
            if state != "UP":
                unready.append(module)
        except (RuntimeError, OSError):
            print(module, "NOT READY")
            unready.append(module)
    if unready:
        raise RuntimeError("Unhealthy applications: " + ", ".join(unready))


def bootstrap():
    values = credentials()
    user = values["TRADEFLOW_ADMIN_USER"]
    if user != "tradeflow_local":
        raise RuntimeError("Unexpected local bootstrap account; refusing to change it")
    sql = "SELECT id FROM ums_admin WHERE username='tradeflow_local';"
    query = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -umall --batch --skip-column-names mall -e '
    account_id = compose("exec", "-T", "mysql", "sh", "-c", query + '"' + sql + '"').strip()
    headers = {"Content-Type": "application/json"}
    if not account_id:
        result = http("http://127.0.0.1:18080/admin/register", "POST", {
            "username": user, "password": values["TRADEFLOW_ADMIN_PASSWORD"],
            "nickName": "TradeFlow local", "note": "Isolated development account",
        }, headers=headers)
        if result.get("code") != 200:
            raise RuntimeError("Local development account registration failed")
        account_id = str(result["data"]["id"])
        role_sql = "SELECT role_id FROM ums_role_resource_relation GROUP BY role_id ORDER BY COUNT(*) DESC LIMIT 1;"
        role_id = compose("exec", "-T", "mysql", "sh", "-c", query + '"' + role_sql + '"').strip()
        if not account_id.isdigit() or not role_id.isdigit():
            raise RuntimeError("Unexpected local account/role identifier")
        assigned = http("http://127.0.0.1:18080/admin/role/update", "POST", {"adminId": account_id, "roleIds": role_id})
        if assigned.get("code") != 200:
            raise RuntimeError("Local development role assignment failed")
    login = http("http://127.0.0.1:18201/mall-auth/auth/login", "POST", {
        "clientId": "admin-app", "username": user, "password": values["TRADEFLOW_ADMIN_PASSWORD"]})
    if login.get("code") != 200 or not login.get("data", {}).get("token"):
        raise RuntimeError("Gateway -> auth -> admin login failed")
    token_data = login["data"]
    auth_headers = {"Authorization": token_data.get("tokenHead", "Bearer").strip() + " " + token_data["token"]}
    info = http("http://127.0.0.1:18201/mall-admin/admin/info", headers=auth_headers)
    if info.get("code") != 200 or info.get("data", {}).get("username") != user:
        raise RuntimeError("Authenticated gateway request failed")
    print("Local development account and gateway -> auth -> admin -> Redis authentication verified")
    indices = http("http://127.0.0.1:19200/_cat/indices?format=json")
    if not any(int(index.get("docs.count") or 0) for index in indices if index["index"] == "pms"):
        imported = http("http://127.0.0.1:18081/esProduct/importAll", "POST", headers=auth_headers)
        if imported.get("code") != 200:
            raise RuntimeError("Sample product indexing failed")
    http("http://127.0.0.1:19200/pms/_refresh", "POST")
    if http("http://127.0.0.1:19200/pms/_count")["count"] == 0:
        raise RuntimeError("The local product search index is empty")
    boundary = "tradeflow-environment-probe"
    payload = b"TradeFlow local environment upload verification\n"
    filename = "tradeflow-environment-" + PROCESS_MARKER[-16:] + ".txt"
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/plain\r\n\r\n'.encode()
            + payload + f"\r\n--{boundary}--\r\n".encode())
    upload = http("http://127.0.0.1:18080/minio/upload", "POST", body,
                  headers={**auth_headers, "Content-Type": "multipart/form-data; boundary=" + boundary})
    if upload.get("code") != 200:
        raise RuntimeError("Local MinIO upload failed")
    url = upload["data"]["url"]
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme, parsed.hostname, parsed.port) != ("http", "127.0.0.1", 19000):
        raise RuntimeError("Upload returned a URL outside the local MinIO endpoint")
    with OPENER.open(url, timeout=10) as response:
        if response.read() != payload:
            raise RuntimeError("MinIO uploaded object content mismatch")
    print("Sample search index and MinIO upload/download verified")
    (STATE / "bootstrap.json").write_text(json.dumps({"username": user, "adminId": int(account_id),
                                                      "authenticationVerified": True, "uploadVerified": True}, indent=2), "utf-8")


def build():
    command = "mvn -B -ntp '-DskipTests=true' test dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-DincludeScope=runtime'; exit $LASTEXITCODE"
    env = {**os.environ, "MAVEN_OPTS": "-Xmx512m -Dfile.encoding=UTF-8"}
    with (STATE / "build.log").open("wb") as output:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                                cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError("Maven compilation failed; inspect the private build.log")
    print("All Maven modules compiled; runtime classpaths generated; tests skipped")


def up():
    prepare()
    compose("up", "-d", "--build", "--wait", "--wait-timeout", "240")
    configure()
    from reliability import migrate
    migrate()
    build()
    start_apps()
    bootstrap()
    status()


def test():
    env = app_environment()
    env.update({"SPRING_CONFIG_ADDITIONAL_LOCATION": (STATE / "application-local.yml").as_uri(),
                "SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED": "false",
                "SPRING_BOOT_ADMIN_CLIENT_ENABLED": "false",
                "TRADEFLOW_JOBS_ENABLED": "false",
                "MAVEN_OPTS": "-Xmx512m -Dfile.encoding=UTF-8"})
    command = "mvn -B -ntp '-DskipTests=false' '-DargLine=-Xmx256m -Dfile.encoding=UTF-8' test; exit $LASTEXITCODE"
    with (STATE / "tests.log").open("wb") as output:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                                cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    (STATE / "tests.exit").write_text(str(result.returncode), "utf-8")
    lines = (STATE / "tests.log").read_text("utf-8", errors="replace").splitlines()
    print(redact("\n".join(line for line in lines if "Tests run:" in line or "BUILD " in line or "[ERROR]" in line)[-6000:]))
    if result.returncode:
        raise RuntimeError("Existing tests failed; inspect the private tests.log and Surefire reports")


def self_test():
    source = (ROOT / "document/sql/mall.sql").read_text("utf-8")
    clean = seed_sql(source)
    assert "DROP TABLE" in source and not re.search(r"(?i)\bDROP\s+TABLE\b", clean)
    assert len(re.findall(r"(?m)^CREATE TABLE ", clean)) == 76
    for forbidden in ("TRUNCATE TABLE existing;", "USE mysql;", "DELETE FROM existing;"):
        try:
            seed_sql(source + "\n" + forbidden)
        except ValueError:
            pass
        else:
            raise AssertionError("Unsafe seed accepted")
    for module in ("admin", "portal", "search", "auth", "gateway", "demo"):
        assert isinstance(json.loads(configuration(module)), dict)
    assert len({port for port, _ in APPS.values()}) == len(APPS)
    assert "example-only" not in redact('{"password":"example-only","token":"example-only"}')
    assert "example-only" not in redact("Authorization: Bearer example-only")
    print("Self-check PASS: additive SQL, configuration documents, unique application ports")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("up", "prepare", "configure", "build", "start", "bootstrap", "stop", "status", "test", "self-test"))
    args = parser.parse_args()
    try:
        {"up": up, "prepare": prepare, "configure": configure, "build": build, "start": start_apps, "bootstrap": bootstrap, "stop": stop,
         "status": status, "test": test, "self-test": self_test}[args.command]()
    except Exception as error:
        try:
            message = redact(str(error))
        except OSError:
            message = type(error).__name__ + ": private runtime files are unreadable; check workspace permissions"
        print(message, file=sys.stderr)
        sys.exit(1)
