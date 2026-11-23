"""Additive migration and isolated MySQL/RabbitMQ reliability checks. Never runs package."""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import local


def database_name(name):
    if name != "mall" and not re.fullmatch(r"tradeflow_test_[a-f0-9]{12}", name):
        raise ValueError("Refusing an unrelated database")
    return name


def sql(statement, database="mall"):
    database_name(database)
    container = local.compose("ps", "-q", "mysql").strip()
    if not container:
        raise RuntimeError("TradeFlow MySQL is not running")
    env = dict(os.environ, MYSQL_PWD=local.credentials()["TRADEFLOW_MYSQL_ROOT_PASSWORD"])
    return local.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", container, "mysql", "-uroot",
                      "--batch", "--skip-column-names", "--default-character-set=utf8mb4", database],
                     input=statement.encode("utf-8"), env=env).strip()


def migrate(database="mall"):
    database_name(database)
    invalid = int(sql("SELECT COUNT(*) FROM oms_order WHERE order_sn IS NULL OR TRIM(order_sn)='';", database))
    duplicates = int(sql("SELECT COUNT(*) FROM (SELECT order_sn FROM oms_order GROUP BY order_sn HAVING COUNT(*)>1) d;", database))
    if invalid or duplicates:
        raise RuntimeError(f"Migration stopped: {invalid} missing order numbers, {duplicates} duplicate groups; no rows changed")
    source = (local.ROOT / "document/sql/tradeflow/V1__transaction_reliability.sql").read_text("utf-8")
    source = re.sub(r"--[^\n]*", "", source)
    for statement in source.split(";"):
        statement = statement.strip()
        if not statement:
            continue
        index = re.fullmatch(r"CREATE (UNIQUE )?INDEX (\w+) ON (\w+) \(([^)]+)\)", statement)
        if index:
            unique, name, table, columns = index.groups()
            existing = sql(f"SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index),MIN(non_unique) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}';", database)
            expected = columns.replace(" ", "") + "\t" + ("0" if unique else "1")
            if existing != "NULL\tNULL":
                if existing != expected:
                    raise RuntimeError("Existing index has an unexpected definition: " + name)
                continue
        elif not statement.startswith("CREATE TABLE IF NOT EXISTS "):
            raise ValueError("Migration must contain only additive CREATE statements")
        sql(statement + ";", database)
    print("Migration PASS: 3 reliability tables and 3 indexes present; existing rows retained")


def test():
    suffix = uuid.uuid4().hex[:12]
    database = database_name("tradeflow_test_" + suffix)
    vhost = "/" + database
    values = local.credentials()
    tables = sql("SELECT table_name FROM information_schema.tables WHERE table_schema='mall' AND table_type='BASE TABLE';").splitlines()
    if not tables or any(not re.fullmatch(r"[a-z0-9_]+", table) for table in tables):
        raise RuntimeError("Unexpected source schema")
    sql(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4;" +
        "".join(f"CREATE TABLE `{database}`.`{table}` LIKE mall.`{table}`;" for table in tables) +
        f"GRANT ALL ON `{database}`.* TO 'mall'@'%';")
    migrate(database)
    auth = ("bootstrap", values["TRADEFLOW_RABBIT_ADMIN_PASSWORD"])
    headers = {"Content-Type": "application/json"}
    encoded = local.urllib.parse.quote(vhost, safe="")
    rabbit = "http://127.0.0.1:15673/api"
    local.http(rabbit + "/vhosts/" + encoded, "PUT", {}, headers=headers, auth=auth)
    local.http(rabbit + "/permissions/" + encoded + "/mall", "PUT", {k: r"^mall\.order\..*" for k in ("configure", "read", "write")}, headers=headers, auth=auth)
    env = local.app_environment()
    env.update({"TRADEFLOW_TEST_JDBC_URL": f"jdbc:mysql://127.0.0.1:13306/{database}?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "TRADEFLOW_TEST_AMQP_VHOST": vhost, "MAVEN_OPTS": "-Xmx512m -Dfile.encoding=UTF-8"})
    command = "mvn -B -ntp -pl mall-portal,mall-admin,mall-gateway -am '-DskipTests=false' '-Dtest=*ReliabilityTests,AlipayControllerProtocolTests,WebLogSecurityTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m -Dfile.encoding=UTF-8' test; exit $LASTEXITCODE"
    log = local.STATE / "reliability-tests.log"
    with log.open("wb") as output:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command], cwd=local.ROOT,
                                env=env, stdout=output, stderr=subprocess.STDOUT)
    classes = []
    for module in ("mall-common", "mall-admin", "mall-portal", "mall-gateway"):
        for path in (local.ROOT / module / "target/surefire-reports").glob("TEST-*.xml"):
            suite = ET.parse(path).getroot()
            name = suite.get("name", "")
            if name.endswith(("ReliabilityTests", "AlipayControllerProtocolTests", "WebLogSecurityTests")):
                classes.append(dict(name=name, **{key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}))
    required = {"TradeDatabaseReliabilityTests", "SkuDatabaseReliabilityTests", "RabbitReliabilityTests"}
    executed = {row["name"].rsplit(".", 1)[-1] for row in classes if row["tests"] > 0 and row["skipped"] == 0}
    totals = {key: sum(row[key] for row in classes) for key in ("tests", "failures", "errors", "skipped")}
    (local.STATE / "reliability-results.json").write_text(json.dumps({"classes": classes, "totals": totals}, indent=2), "utf-8")
    verified = result.returncode == 0 and required <= executed and totals["skipped"] == totals["errors"] == totals["failures"] == 0
    summary = {"database": database, "vhost": vhost, "exit": result.returncode, "verified": verified, "time": time.strftime("%Y-%m-%d %H:%M:%S")}
    (local.STATE / "reliability-tests.json").write_text(json.dumps(summary, indent=2), "utf-8")
    lines = log.read_text("utf-8", errors="replace").splitlines()
    print(local.redact("\n".join(line for line in lines if "Tests run:" in line or "BUILD " in line or "[ERROR]" in line)[-6000:]))
    print("Isolated test schema/vhost retained for inspection: " + database)
    if not verified:
        raise RuntimeError("Reliability tests failed or a required integration suite was skipped; inspect private reliability-tests.log")


def self_test():
    for name in ("mall", "tradeflow_test_012345abcdef"):
        assert database_name(name) == name
    for name in ("mysql", "mall;DELETE", "tradeflow_test_bad", "other"):
        try:
            database_name(name)
        except ValueError:
            continue
        raise AssertionError("Unrelated database accepted")
    source = (local.ROOT / "document/sql/tradeflow/V1__transaction_reliability.sql").read_text("utf-8")
    assert len(re.findall(r"CREATE TABLE IF NOT EXISTS", source)) == 3
    assert not re.search(r"(?i)\b(DROP|TRUNCATE|DELETE|UPDATE)\b", re.sub(r"--[^\n]*", "", source))
    print("Reliability tooling self-check PASS")


def replay():
    result = local.http("http://127.0.0.1:18085/internal/orders/replay-timeouts?limit=10", "POST", b"",
                        headers={"X-TradeFlow-Internal": local.credentials()["TRADEFLOW_INTERNAL_TOKEN"]})
    if result.get("code") != 200:
        raise RuntimeError("Failure queue replay did not complete")
    print("Confirmed timeout messages replayed: " + str(result["data"]))


def status():
    print("Payment states (no customer or payment credentials):")
    print(sql("SELECT state,COALESCE(review_reason,'-'),COUNT(*) FROM payment_transaction GROUP BY state,review_reason;") or "No payment facts")
    print("Outbox pending / confirmed:")
    print(sql("SELECT COALESCE(SUM(published_at IS NULL),0),COALESCE(SUM(published_at IS NOT NULL),0) FROM outbox_event;"))
    auth = ("bootstrap", local.credentials()["TRADEFLOW_RABBIT_ADMIN_PASSWORD"])
    queue = local.http("http://127.0.0.1:15673/api/queues/%2Fmall/mall.order.cancel.failed", auth=auth)
    print("Isolated failure messages: " + str(queue.get("messages", 0)))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("migrate", "test", "self-test", "replay", "status"))
    args = parser.parse_args()
    try:
        {"migrate": migrate, "test": test, "self-test": self_test, "replay": replay, "status": status}[args.command]()
    except Exception as error:
        print(local.redact(str(error)), file=sys.stderr)
        sys.exit(1)
