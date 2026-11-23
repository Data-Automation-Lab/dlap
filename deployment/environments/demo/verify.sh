#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Offline check of the demo overlay (DEMO-01, ADR-0058). Starts nothing and needs no secrets: it asks
# `docker compose config` for the merged model of the quickstart pair plus docker-compose.demo.yml
# and asserts the properties the demo promises, then greps the nginx and Caddy files for the controls
# that only they can carry. A quickstart change that breaks a promise fails here, not on the host.
#
# Usage:  verify.sh [--env-file FILE] [--budget-mib N]
#   default env file: .env.demo.example next to this script
#   DEMO_OVERLAY_FILE  checks another overlay file instead (test-demo.sh mutates a copy)
# Needs:  docker compose 2.24 or later, python3 (or python).
# Exit:   0 all checks pass; 1 a check failed; 2 the model could not be built.
#
# Each check has an id (V01, V02, ...) that README.md and test-demo.sh refer to.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/scripts/lib.sh"

ENV_FILE="$DEMO_DIR/.env.demo.example"
BUDGET_MIB=6656
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || { echo "verify.sh: --env-file needs a file name" >&2; exit 2; }; ENV_FILE="$2"; shift 2 ;;
    --budget-mib) [ $# -ge 2 ] || { echo "verify.sh: --budget-mib needs a number" >&2; exit 2; }; BUDGET_MIB="$2"; shift 2 ;;
    *) echo "verify.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
  done
fi
[ -n "$PYTHON" ] || { echo "verify.sh: python3 is required" >&2; exit 2; }

# The merged model must not depend on the caller's environment: nothing set in the shell that
# compose would read.
unset COMPOSE_PROFILES DEMO_REPLAY_ENABLED DEMO_EXTRA_COMPOSE
demo_init "$ENV_FILE"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

demo_dc config --format json >"$WORK/default.json" 2>"$WORK/default.err" \
  || { cat "$WORK/default.err" >&2; echo "verify.sh: docker compose config failed (default profiles)" >&2; exit 2; }
demo_dc --profile plus --profile not-in-demo --profile monitoring --profile aws --profile unity \
  config --format json >"$WORK/all.json" 2>"$WORK/all.err" \
  || { cat "$WORK/all.err" >&2; echo "verify.sh: docker compose config failed (all profiles)" >&2; exit 2; }

"$PYTHON" - "$(demo_native_path "$WORK/default.json")" "$(demo_native_path "$WORK/all.json")" \
  "$(demo_native_path "$DEMO_DIR")" "$BUDGET_MIB" "$(demo_native_path "$ENV_FILE")" <<'PY'
import json
import os
import re
import sys

default = json.load(open(sys.argv[1], encoding="utf-8"))
everything = json.load(open(sys.argv[2], encoding="utf-8"))
demo_dir = sys.argv[3]
budget_mib = int(sys.argv[4])
env_file = sys.argv[5]
dsvc = default["services"]
asvc = everything["services"]

JVMS = ["da-catalog", "da-policyengine", "da-discovery", "da-logingest", "da-reporting",
        "da-autolabel", "da-autoarchival", "da-autodelete"]
PLUS = ["da-admin-service", "da-autocompliance"]
INFRA = ["postgresql", "keycloak", "kafka"]
EDGE = ["gateway", "caddy"]
WAVE1 = ["da-catalog", "da-policyengine", "da-discovery"]
NOT_IN_DEMO = ["new-ui", "ollama", "ollama-model-pull"]
OPT_INFRA = ["prometheus", "grafana", "localstack", "unitycatalog"]

failures = []
passed = []


def check(cid, ok, message):
    if ok:
        passed.append(cid)
    else:
        failures.append(f"{cid}: {message}")


def env_of(service):
    environment = service.get("environment") or {}
    if isinstance(environment, list):
        return dict(item.split("=", 1) for item in environment if "=" in item)
    return environment


def limit_of(service):
    value = (((service.get("deploy") or {}).get("resources") or {}).get("limits") or {}).get("memory")
    return int(value) / 1048576 if value else None


def read(path):
    with open(f"{demo_dir}/{path}", encoding="utf-8") as handle:
        return handle.read()


# V01 The default profile runs exactly the Lite set: 3 infrastructure services, the gateway, Caddy
# and 8 JVMs.
expected = set(INFRA + EDGE + JVMS)
check("V01", set(dsvc) == expected,
      f"default services differ: extra {sorted(set(dsvc) - expected)}, missing {sorted(expected - set(dsvc))}")

# V02 Everything that is not part of the Lite story is behind a profile.
for name in NOT_IN_DEMO + PLUS + OPT_INFRA:
    check("V02", name not in dsvc and name in asvc, f"{name} is part of the default profiles or missing altogether")
for name in PLUS:
    check("V02", "plus" in (asvc.get(name, {}).get("profiles") or []), f"{name} is not behind the plus profile")
for name in NOT_IN_DEMO:
    check("V02", "not-in-demo" in (asvc.get(name, {}).get("profiles") or []), f"{name} is not behind the not-in-demo profile")

# V03 Only Caddy publishes beyond the loopback, and only TCP 80 and 443; every other published port is
# a loopback port. Checked for the default profiles and again with every profile on.
for label, services in (("default", dsvc), ("all profiles", asvc)):
    for name, service in services.items():
        for port in service.get("ports") or []:
            if name == "caddy":
                check("V03", port.get("protocol", "tcp") == "tcp" and int(port["target"]) in (80, 443),
                      f"[{label}] caddy publishes {port.get('target')}/{port.get('protocol')}")
            else:
                check("V03", port.get("host_ip") == "127.0.0.1",
                      f"[{label}] {name} publishes {port.get('published')}:{port.get('target')} on {port.get('host_ip') or 'all interfaces'}")
caddy_targets = sorted(int(p["target"]) for p in dsvc.get("caddy", {}).get("ports") or [])
check("V03", caddy_targets == [80, 443], f"caddy publishes {caddy_targets}, not [80, 443]")
check("V03", re.fullmatch(r":\d+", env_of(dsvc.get("caddy", {})).get("DEMO_APP_HTTP_ADDRESS", "")) is not None,
      "caddy would serve the app over plain HTTP by default (the default address must be a bare port that nothing publishes)")
loopback = sorted((n, int(p["target"])) for n, s in dsvc.items() if n != "caddy" for p in s.get("ports") or [])
check("V03", loopback == [("gateway", 8081), ("keycloak", 8080)], f"loopback ports are {loopback}")

# V04 Every service has a memory limit, and the limits fit the budget.
for name, service in dsvc.items():
    check("V04", limit_of(service) is not None, f"{name} has no memory limit")
total = sum(limit_of(s) or 0 for s in dsvc.values())
check("V04", total <= budget_mib, f"the memory limits add up to {total:.0f} MiB, over the budget of {budget_mib} MiB")

# V05 Every JVM: explicit heap, bounded metaspace and code cache, C1 only, exit on out of memory, and
# the three fit inside the limit with room for threads and native memory.
for name in JVMS + PLUS:
    service = asvc.get(name)
    if not service:
        continue
    opts = env_of(service).get("JAVA_TOOL_OPTIONS", "")
    heap = re.search(r"-Xmx(\d+)m", opts)
    meta = re.search(r"-XX:MaxMetaspaceSize=(\d+)m", opts)
    code = re.search(r"-XX:ReservedCodeCacheSize=(\d+)m", opts)
    check("V05", bool(heap), f"{name} has no -Xmx")
    check("V05", bool(meta), f"{name} has no -XX:MaxMetaspaceSize")
    check("V05", bool(code), f"{name} has no -XX:ReservedCodeCacheSize")
    check("V05", "-XX:TieredStopAtLevel=1" in opts, f"{name} does not stop tiered compilation at level 1")
    check("V05", "-XX:+ExitOnOutOfMemoryError" in opts, f"{name} does not exit on out of memory")
    if heap and meta and code and limit_of(service):
        used = int(heap.group(1)) + int(meta.group(1)) + int(code.group(1))
        check("V05", used <= 0.9 * limit_of(service),
              f"{name}: heap, metaspace and code cache ({used} MiB) leave under 10% of the {limit_of(service):.0f} MiB limit")

# V06 The Spring services run under prod with ddl-auto=validate. da-logingest adds the demo profile
# (the replay seeder).
for name in JVMS + PLUS:
    service = asvc.get(name)
    if not service:
        continue
    env = env_of(service)
    want = "prod,demo" if name == "da-logingest" else "prod"
    check("V06", env.get("SPRING_PROFILES_ACTIVE") == want, f"{name} runs profile {env.get('SPRING_PROFILES_ACTIVE')!r}, not {want!r}")
    check("V06", env.get("SPRING_JPA_HIBERNATE_DDL_AUTO") == "validate", f"{name} does not force ddl-auto=validate")

# V07 da-autolabel labels with the rules provider and may not fall back to the mock.
application_json = env_of(dsvc.get("da-autolabel", {})).get("SPRING_APPLICATION_JSON", "{}")
try:
    labelling = json.loads(application_json)
except ValueError:
    labelling = {}
check("V07", labelling.get("dlap.autolabel.llm-provider") == "rules", "da-autolabel does not use the rules provider")
check("V07", str(labelling.get("dlap.autolabel.allow-mock")).lower() == "false", "da-autolabel allows the mock provider")
check("V07", "ollama" not in (dsvc.get("da-autolabel", {}).get("depends_on") or {}), "da-autolabel still waits for ollama")

# V08 Fixture data only, every cloud source off. Checked on the default model, where the example
# environment file is in force, and on the all-profiles model.
for label, services in (("default", dsvc), ("all profiles", asvc)):
    logingest = env_of(services.get("da-logingest", {}))
    check("V08", logingest.get("DLAP_LOGINGEST_REPLAY_ENABLED") == "false", f"[{label}] the fixture replay is on by default")
    check("V08", logingest.get("DLAP_LOGINGEST_DEMO_SEED_ENABLED") == "false", f"[{label}] the synthetic demo seeder is on")
    check("V08", logingest.get("DLAP_LOGINGEST_GCP_ENABLED") == "false", f"[{label}] the GCP log source is on")
    check("V08", logingest.get("DLAP_LOGINGEST_AWS_ENABLED") == "false", f"[{label}] the AWS log source is on")
    discovery = env_of(services.get("da-discovery", {}))
    check("V08", discovery.get("DLAP_AWS_ENABLED") == "false", f"[{label}] AWS discovery is on")
    check("V08", discovery.get("CLOUD_PROVIDER_AWS_ENABLED") == "false", f"[{label}] AWS discovery (provider switch) is on")
    check("V08", discovery.get("CLOUD_PROVIDER_UNITY_ENABLED") == "false", f"[{label}] Unity Catalog discovery is on")
    for name, service in services.items():
        env = env_of(service)
        check("V08", "DLAP_DISCOVERY_SCHEDULED_ENABLED" not in env and "dlap.discovery.scheduled.enabled" not in json.dumps(env),
              f"[{label}] {name} enables scheduled discovery")
    check("V08", env_of(services.get("da-autodelete", {})).get("DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE") == "true", f"[{label}] da-autodelete is not a dry run")
    archival = env_of(services.get("da-autoarchival", {}))
    check("V08", archival.get("DLAP_AUTOARCHIVAL_GCS_MOCK_MODE") == "true", f"[{label}] GCS archival is not mocked")
    check("V08", archival.get("DLAP_AUTOARCHIVAL_AZURE_MOCK_MODE") == "true", f"[{label}] Azure archival is not mocked")
check("V08", dsvc.get("da-logingest", {}).get("volumes") and any("e2e-tests" in (v.get("source") or "").replace("\\", "/") and v.get("read_only") for v in dsvc["da-logingest"]["volumes"]),
      "the fixture folder is not mounted read-only into da-logingest")

# V09 The service network has no route out. Only Caddy, the gateway and Keycloak also join the edge
# network.
check("V09", (default["networks"].get("dlap-network") or {}).get("internal") is True, "dlap-network is not internal")
for name, service in dsvc.items():
    nets = set((service.get("networks") or {}).keys())
    if name in ("caddy", "gateway", "keycloak"):
        check("V09", "demo-edge" in nets and "dlap-network" in nets, f"{name} is not on both networks ({sorted(nets)})")
    else:
        check("V09", nets == {"dlap-network"}, f"{name} is on {sorted(nets)}, not only dlap-network")

# V10 Postgres: no statement logging, a small footprint.
command = " ".join(dsvc.get("postgresql", {}).get("command") or [])
check("V10", "log_statement=none" in command, "postgres does not set log_statement=none")
check("V10", "log_statement=all" not in command, "postgres logs every statement")
connections = re.search(r"max_connections=(\d+)", command)
check("V10", bool(connections) and int(connections.group(1)) <= 100, "postgres allows more than 100 connections")
buffers = re.search(r"shared_buffers=(\d+)MB", command)
check("V10", bool(buffers) and int(buffers.group(1)) <= 128, "postgres shared_buffers is over 128MB")

# V11 Kafka: a small heap and one partition.
kafka_env = env_of(dsvc.get("kafka", {}))
heap = re.search(r"-Xmx(\d+)m", str(kafka_env.get("KAFKA_HEAP_OPTS", "")), re.I)
check("V11", bool(heap) and int(heap.group(1)) <= 256, "kafka heap is not capped at 256m")
check("V11", str(kafka_env.get("KAFKA_NUM_PARTITIONS")) == "1", "kafka does not default to one partition")
check("V11", env_of(dsvc.get("da-discovery", {})).get("KAFKA_ADMIN_REPLICATION_FACTOR") == "1", "da-discovery would ask for a replication factor above 1")
check("V11", env_of(dsvc.get("da-discovery", {})).get("KAFKA_CONSUMER_AUTO_OFFSET_RESET") == "earliest", "da-discovery consumers do not start from the beginning")

# V12 Container hardening: the JVMs, the gateway and Caddy drop capabilities, cannot gain privileges
# and have a read-only root file system. Every container has a process limit.
for name in JVMS + PLUS + EDGE:
    service = asvc.get(name)
    if not service:
        continue
    check("V12", "ALL" in (service.get("cap_drop") or []), f"{name} does not drop all capabilities")
    check("V12", "no-new-privileges:true" in (service.get("security_opt") or []), f"{name} can gain privileges")
    check("V12", service.get("read_only") is True, f"{name} has a writable root file system")
for name, service in dsvc.items():
    pids = (((service.get("deploy") or {}).get("resources") or {}).get("limits") or {}).get("pids")
    check("V12", bool(pids), f"{name} has no process limit")

# V13 The images this overlay adds are pinned by digest; the gateway and Caddy mount their files.
check("V13", re.search(r"@sha256:[0-9a-f]{64}$", dsvc.get("caddy", {}).get("image", "")) is not None, "the caddy image is not pinned by digest")
gateway_mounts = {(v.get("target"), (v.get("source") or "").replace("\\", "/").rsplit("/", 1)[-1]) for v in dsvc.get("gateway", {}).get("volumes") or []}
check("V13", ("/etc/nginx/nginx.conf", "nginx.demo.conf") in gateway_mounts, "the gateway does not mount nginx.demo.conf")
check("V13", any(t == "/usr/share/nginx/html" for t, _ in gateway_mounts), "the gateway does not mount the SPA folder")
caddy_mounts = {(v.get("target"), (v.get("source") or "").replace("\\", "/").rsplit("/", 1)[-1]) for v in dsvc.get("caddy", {}).get("volumes") or []}
check("V13", ("/etc/caddy/Caddyfile", "Caddyfile") in caddy_mounts, "caddy does not mount the Caddyfile")
check("V13", any(t == "/srv/flags" for t, _ in caddy_mounts), "caddy does not see the maintenance flag folder")

# V14 Two start waves: the wave-1 services wait only for the infrastructure; every wave-2 service waits
# for all three wave-1 services; the gateway waits for nothing.
for name in WAVE1:
    deps = set((dsvc.get(name, {}).get("depends_on") or {}).keys())
    check("V14", not (deps & set(JVMS)), f"{name} (wave 1) depends on {sorted(deps & set(JVMS))}")
for name in [j for j in JVMS if j not in WAVE1]:
    deps = dsvc.get(name, {}).get("depends_on") or {}
    for dep in WAVE1:
        check("V14", dep in deps and deps[dep].get("condition") == "service_healthy", f"{name} (wave 2) does not wait for {dep} to be healthy")
check("V14", not (dsvc.get("gateway", {}).get("depends_on") or {}), "the gateway waits for services; it resolves them per request")
check("V14", "gateway" in (dsvc.get("caddy", {}).get("depends_on") or {}), "caddy does not wait for the gateway")

# V15 Static controls in the nginx and Caddy files.
def read_file(variable, default):
    path = os.environ.get(variable)
    if path:
        with open(path, encoding="utf-8") as handle:
            return handle.read()
    return read(default)


# DEMO_NGINX_CONF and DEMO_CADDYFILE let test-demo.sh check a broken copy.
nginx = read_file("DEMO_NGINX_CONF", "gateway/nginx.demo.conf")
caddy = read_file("DEMO_CADDYFILE", "caddy/Caddyfile")
check("V15", re.search(r"location\s+~\*\s+\(\^\|/\)test-rule\(/\|\$\)\s*\{[^}]*return 403", nginx) is not None,
      "nginx.demo.conf has no location that answers 403 for test-rule")
for needle, what in [
    ("client_max_body_size 256k", "a 256k body limit"),
    ("limit_req_zone", "request rate limits"),
    ("limit_conn_zone", "a connection limit"),
    ("resolver 127.0.0.11", "per-request DNS resolution"),
    ("set_real_ip_from", "a trusted proxy range for the client address"),
    ("location /access/v1/", "the AuthZEN route"),
    ("try_files $uri $uri/ /index.html", "the SPA fallback"),
    ("server_tokens off", "server_tokens off"),
]:
    check("V15", needle in nginx, f"nginx.demo.conf has no {what}")
check("V15", len(re.findall(r"limit_req_zone", nginx)) >= 3, "nginx.demo.conf has fewer than three rate limit zones")
check("V15", not re.search(r"proxy_pass\s+http://da-", nginx), "nginx.demo.conf proxies to a literal upstream name; use a variable so DNS is resolved per request")
check("V15", "$request_uri" not in re.search(r"log_format[^;]*;", nginx, re.S).group(0), "the access log format contains the query string")
for needle, what in [
    ("{$DEMO_APP_HOST", "the app host"),
    ("{$DEMO_ID_HOST", "the Keycloak host"),
    ("realms/+master", "a block of the master realm"),
    ("admin|metrics|health", "a block of the admin console, metrics and health"),
    ("protocols h1 h2", "HTTP/3 switched off"),
    ("/srv/flags", "the maintenance flag"),
    ("Strict-Transport-Security", "HSTS"),
    ("Content-Security-Policy", "a Content-Security-Policy"),
    ("frame-ancestors", "frame-ancestors"),
    ("Referrer-Policy", "a Referrer-Policy"),
]:
    check("V15", needle in caddy, f"the Caddyfile has no {what}")
check("V15", re.search(r"handle @allowed \{\s*reverse_proxy keycloak:8080", caddy) is not None, "the Caddyfile does not limit the Keycloak proxy to an allow list")

# V16 The example environment file carries no secret that the quickstart publishes.
text = open(env_file, encoding="utf-8").read()
for key, weak in [("POSTGRES_PASSWORD", {"dlap"}), ("KEYCLOAK_ADMIN_PASSWORD", {"admin", "changeme"}),
                  ("DLAP_BACKEND_CLIENT_SECRET", {"change-me-dev-only"})]:
    match = re.search(rf"^{key}=(.*)$", text, re.M)
    check("V16", bool(match) and match.group(1).strip() not in weak, f"{key} in the example file is a published default or missing")
check("V16", all("CHANGE_ME" in host for host in re.findall(r"^DEMO_(?:APP|ID)_HOST=(.*)$", text, re.M)),
      "a demo host name in the example environment file is a real domain, not a CHANGE_ME placeholder")

if failures:
    print("verify.sh: FAILED")
    for message in failures:
        print("  - " + message)
    sys.exit(1)
print(f"verify.sh: OK ({len(passed)} assertions in {len(set(p for p in passed))} checks; services {len(dsvc)} default, {len(asvc)} with every profile; limits {total:.0f} MiB)")
PY
