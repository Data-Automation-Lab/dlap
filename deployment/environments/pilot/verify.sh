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
# Offline check of the pilot overlay (DEP-01). Starts nothing and needs no secrets: it asks
# `docker compose config` for the merged model of the quickstart pair plus every
# docker-compose.pilot.*.yml and asserts the properties the pilot promises.
#
# Usage:  verify.sh [--env-file FILE]        (default: .env.pilot.example next to this script)
# Needs:  docker compose 2.24 or later, python3 (or python).
# Exit:   0 all checks pass; 1 a check failed; 2 the model could not be built.
#
# Checked, for the default profiles and again with every profile on:
#   - every published port is bound to 127.0.0.1, except the TLS entry point (the gateway's target port
#     8443), which PILOT_TLS_BIND_ADDRESS may open on purpose (Q-517);
#   - every service with a limit in scope has a memory limit, and every JVM has an explicit -Xmx;
#   - the ten Spring services run under prod with ddl-auto=validate;
#   - da-autodelete deletes are a dry run, and archival is mocked;
#   - da-autoarchival and da-autodelete (profile actions) and Ollama (profile llm) are absent by default;
#   - the demo seed script is replaced;
#   - every database role has its own password from the environment file with no development fallback, each
#     service reads the password of its own role, the Postgres exporter connects as dlap_monitor and the backup
#     agent as dlap_backup (SEC-12);
#   - every Spring service checks the JWT issuer and the audience, also without the TLS overlay (Q-919), so
#     the strict boot guard of the prod profile lets a pilot without TLS start.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env.pilot.example"
if [ "${1:-}" = "--env-file" ]; then
  [ $# -ge 2 ] || { echo "verify.sh: --env-file needs a file name" >&2; exit 2; }
  ENV_FILE="$2"
fi

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
  done
fi
[ -n "$PYTHON" ] || { echo "verify.sh: python3 is required" >&2; exit 2; }

ARGS=()
while IFS= read -r arg; do ARGS+=("$arg"); done < <("$SCRIPT_DIR/up.sh" --env-file "$ENV_FILE" --print-args)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The same arguments without the TLS overlay: the base and the other overlays alone (Q-919).
NOTLS_ARGS=()
i=0
while [ "$i" -lt "${#ARGS[@]}" ]; do
  if [ "${ARGS[$i]}" = "-f" ] && [[ "${ARGS[$((i + 1))]:-}" == *docker-compose.pilot.tls.yml ]]; then
    i=$((i + 2))
    continue
  fi
  NOTLS_ARGS+=("${ARGS[$i]}")
  i=$((i + 1))
done

docker compose "${ARGS[@]}" config --format json >"$WORK/default.json" 2>"$WORK/default.err" \
  || { cat "$WORK/default.err" >&2; echo "verify.sh: docker compose config failed (default profiles)" >&2; exit 2; }
docker compose "${ARGS[@]}" --profile actions --profile llm --profile monitoring --profile aws --profile unity \
  config --format json >"$WORK/all.json" 2>"$WORK/all.err" \
  || { cat "$WORK/all.err" >&2; echo "verify.sh: docker compose config failed (all profiles)" >&2; exit 2; }

docker compose "${NOTLS_ARGS[@]}" --profile actions --profile llm --profile monitoring --profile aws --profile unity   config --format json >"$WORK/notls.json" 2>"$WORK/notls.err"   || { cat "$WORK/notls.err" >&2; echo "verify.sh: docker compose config failed (without the TLS overlay)" >&2; exit 2; }

"$PYTHON" - "$WORK/default.json" "$WORK/all.json" "$WORK/notls.json" <<'PY'
import json
import sys

default = json.load(open(sys.argv[1], encoding="utf-8"))["services"]
everything = json.load(open(sys.argv[2], encoding="utf-8"))["services"]
notls = json.load(open(sys.argv[3], encoding="utf-8"))["services"]

SPRING = ["da-discovery", "da-catalog", "da-policyengine", "da-reporting", "da-autolabel",
          "da-autocompliance", "da-admin-service", "da-logingest", "da-autoarchival", "da-autodelete"]
OPTIONAL = {"da-autoarchival", "da-autodelete", "ollama", "ollama-model-pull"}
# Third-party images behind their own profile that the overlay only re-binds, with no limit of its own.
NO_LIMIT = {"localstack", "unitycatalog", "ollama", "ollama-model-pull"}

failures = []


def check(ok, message):
    if not ok:
        failures.append(message)


def env_of(service):
    environment = service.get("environment") or {}
    if isinstance(environment, list):
        return dict(item.split("=", 1) for item in environment if "=" in item)
    return environment


def limit_of(service):
    return (((service.get("deploy") or {}).get("resources") or {}).get("limits") or {}).get("memory")


for label, services in (("default profiles", default), ("all profiles", everything)):
    for name, service in services.items():
        for port in service.get("ports") or []:
            # The one door the operator may open: the gateway's TLS entry point (PILOT_TLS_BIND_ADDRESS).
            if name == "gateway" and str(port.get("target")) == "8443":
                continue
            check(port.get("host_ip") == "127.0.0.1",
                  f"[{label}] {name} publishes {port.get('published')}:{port.get('target')} on {port.get('host_ip') or 'all interfaces'}")
        if name not in NO_LIMIT:
            check(limit_of(service), f"[{label}] {name} has no memory limit")

    for name in SPRING:
        if name not in services:
            continue
        env = env_of(services[name])
        check(env.get("SPRING_PROFILES_ACTIVE") == "prod", f"[{label}] {name} is not on the prod profile")
        check(env.get("SPRING_JPA_HIBERNATE_DDL_AUTO") == "validate", f"[{label}] {name} does not force ddl-auto=validate")

    for name in SPRING + ["new-ui"]:
        if name not in services:
            continue
        check("-Xmx" in env_of(services[name]).get("JAVA_TOOL_OPTIONS", ""), f"[{label}] {name} has no explicit -Xmx")

# Archive and delete (and Ollama) are opt-in.
for name in sorted(OPTIONAL):
    check(name not in default, f"{name} is part of the default profiles")
    check(name in everything, f"{name} is missing even with every profile on")
for name in ("da-autoarchival", "da-autodelete"):
    check("actions" in (everything.get(name, {}).get("profiles") or []), f"{name} is not behind the actions profile")
check("llm" in (everything.get("ollama", {}).get("profiles") or []), "ollama is not behind the llm profile")
for name, service in default.items():
    for dependency in service.get("depends_on") or {}:
        check(dependency in default, f"{name} depends on {dependency}, which is not in the default profiles")

# Even when actions are switched on, nothing reaches a cloud.
delete = env_of(everything.get("da-autodelete", {}))
check(delete.get("DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE") == "true", "da-autodelete is not a dry run")
archival = env_of(everything.get("da-autoarchival", {}))
check(archival.get("DLAP_AUTOARCHIVAL_GCS_MOCK_MODE") == "true", "da-autoarchival GCS is not mocked")
check(archival.get("DLAP_AUTOARCHIVAL_AZURE_MOCK_MODE") == "true", "da-autoarchival Azure is not mocked")

# The demo seed is replaced by the empty script.
seed = [v for v in (default.get("postgresql", {}).get("volumes") or [])
        if v.get("target") == "/docker-entrypoint-initdb.d/03-insert-test-data.sql"]
check(len(seed) == 1 and "pilot" in seed[0].get("source", "").replace("\\", "/"),
      "the demo seed 03-insert-test-data.sql is not replaced by the pilot's empty script")

# The log is statement-free.
command = default.get("postgresql", {}).get("command") or []
check("log_statement=none" in command, "postgres still logs every statement")

# SEC-12 (Q-176, Q-247, Q-297, Q-449, Q-455): every database role has its own password from the environment file,
# with no development fallback, and each client uses the password of its own role.
ROLES = {"da-discovery": "DA_DISCOVERY", "da-catalog": "DA_CATALOG", "da-policyengine": "DA_POLICYENGINE",
         "da-reporting": "DA_REPORTING", "da-autolabel": "DA_AUTOLABEL", "da-autoarchival": "DA_AUTOARCHIVAL",
         "da-autodelete": "DA_AUTODELETE", "da-autocompliance": "DA_AUTOCOMPLIANCE", "da-admin-service": "DA_ADMIN",
         "da-logingest": "DA_LOGINGEST"}
for label, services in (("default profiles", default), ("all profiles", everything)):
    pg = env_of(services.get("postgresql", {}))
    variables = [v + "_DB_PASSWORD" for v in sorted(ROLES.values()) + ["DLAP_COMMON"]] + [
        "DLAP_MONITOR_DB_PASSWORD", "DLAP_BACKUP_DB_PASSWORD"]
    for variable in variables:
        value = pg.get(variable, "")
        check(value != "" and not value.startswith(("da_", "dlap_")),
              f"[{label}] postgresql has no value, or a development default, for {variable}")
    check(pg.get("POSTGRES_PASSWORD", "") not in ("", "dlap"), f"[{label}] POSTGRES_PASSWORD falls back to the development value")
    for name, role in ROLES.items():
        if name not in services:
            continue
        env = env_of(services[name])
        want = pg.get(role + "_DB_PASSWORD")
        check(env.get("SPRING_DATASOURCE_PASSWORD") == want and env.get("DB_PASS") == want,
              f"[{label}] {name} does not read the password of its own role ({role}_DB_PASSWORD)")
    exporter = env_of(services.get("postgres-exporter", {}))
    if exporter:
        check(exporter.get("DATA_SOURCE_USER") == "dlap_monitor", f"[{label}] postgres-exporter does not connect as dlap_monitor")
        check(exporter.get("DATA_SOURCE_PASS") == pg.get("DLAP_MONITOR_DB_PASSWORD"),
              f"[{label}] postgres-exporter does not use the monitor role's password")
    backup = env_of(services.get("dlap-backup", {}))
    if backup:
        check(backup.get("PGUSER") == "dlap_backup", f"[{label}] dlap-backup does not connect as dlap_backup")
        check(backup.get("PGPASSWORD") == pg.get("DLAP_BACKUP_DB_PASSWORD"),
              f"[{label}] dlap-backup does not use the backup role's password")
        check(backup.get("PGPASSWORD") != pg.get("POSTGRES_PASSWORD"), f"[{label}] dlap-backup uses the superuser's password")

# Q-919 (SEC-12, ADR-0137): the boot guard of the prod profile refuses JWT validation off, so every Spring service
# checks the issuer and the audience with and without the TLS overlay. The issuer is the one the Keycloak overlay
# fixes (DLAP_PILOT_ID_URL) or, with TLS, the public origin; the audience is dlap-api.
for label, services in (("default profiles", default), ("all profiles", everything), ("without the TLS overlay", notls)):
    for name in SPRING:
        if name not in services:
            continue
        env = env_of(services[name])
        check(env.get("DLAP_SECURITY_JWT_VALIDATE_ISSUER") == "true", f"[{label}] {name} does not validate the JWT issuer")
        check(env.get("DLAP_SECURITY_JWT_VALIDATE_AUDIENCE") == "true", f"[{label}] {name} does not validate the JWT audience")
        check(env.get("DLAP_SECURITY_JWT_EXPECTED_AUDIENCE") == "dlap-api", f"[{label}] {name} does not expect the audience dlap-api")
        check(bool(env.get("DLAP_SECURITY_JWT_EXPECTED_ISSUER", "").strip(", ")), f"[{label}] {name} has no expected JWT issuer")
        check("DLAP_SECURITY_DEV_SECRET_GUARD_ACCEPTED" not in env, f"[{label}] {name} waives a boot-guard finding")
for name in SPRING:
    if name in notls:
        issuer = env_of(notls[name]).get("DLAP_SECURITY_JWT_EXPECTED_ISSUER", "")
        check(issuer.endswith("/realms/dlap") and "," not in issuer, f"[without the TLS overlay] {name} expects the issuer {issuer!r}, not one realm URL")

if failures:
    print(f"verify.sh: {len(failures)} check(s) failed")
    for message in failures:
        print("  - " + message)
    sys.exit(1)
print(f"verify.sh: all checks passed ({len(default)} services by default, {len(everything)} with every profile)")
PY
