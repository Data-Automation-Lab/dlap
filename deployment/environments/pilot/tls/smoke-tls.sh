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
# Transport-security smoke test of the pilot (SEC-06, docs/guides/transport-security.md).
#
# Proves each hop of docker-compose.pilot.tls.yml on a running pilot: that it is encrypted, that the
# peer's certificate is verified, that the plain-text door is shut, and that the JWT issuer and
# audience checks reject a foreign token in every service. Every check prints PASS, FAIL or SKIP with
# its hop; the table at the end is the evidence. WARN marks a known gap of the configuration under test
# that does not fail the run (Keycloak's development mode, which always opens plain HTTP).
#
# Usage:  smoke-tls.sh [options]
#   --env-file FILE   the pilot environment file (default: ../.env.pilot, or $PILOT_ENV_FILE)
#   --tls-dir DIR     the folder gen-certs.sh wrote (default: PILOT_TLS_DIR of the environment file,
#                     else generated/ next to this script)
#   --public-ca FILE  CA of the entry point certificate, when it is not the pilot CA (--public-cert)
#   --static          check the merged compose model only; start nothing, needs no running pilot
#   --executors       also run H7: make da-autoarchival and da-autodelete call GET /approvals on the policy
#                     engine over HTTPS (WIRE-01). It WRITES to the pilot: one REFUSED archive rule, one
#                     refused deletion task and one chained refusal record in each executor, so use it on a
#                     rehearsal (tls-e2e.sh --monitoring does), not on a pilot that holds real work.
#   -h, --help        this text
#
# Needs: docker (with compose 2.24+), python3 (or python), on the host. The live checks run in
# throwaway containers on the pilot's network (curlimages/curl, postgres:15.1, cp-kafka 7.5.3) and
# read only the pilot CA, the admin and SCRAM material from the pilot-tls volume, and the client
# secrets from the environment file. They write nothing to the pilot except one consumer group,
# da-reporting-smoke-tls, which reads at most one record of dlap.access.decision and prints none.
#
# Exit: 0 every check passed (SKIP and WARN allowed); 1 a check failed; 2 the pilot or a prerequisite is
# missing.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PILOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${PILOT_ENV_FILE:-$PILOT_DIR/.env.pilot}"
TLS_DIR=""
PUBLIC_CA=""
MODE="live"
EXECUTORS=0
CURL_IMAGE="curlimages/curl:8.11.1"
PG_IMAGE="postgres:15.1"
KAFKA_IMAGE="confluentinc/cp-kafka:7.5.3"
SPRING=(da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete
        da-autocompliance da-admin-service da-logingest)

die() { echo "smoke-tls.sh: $*" >&2; exit 2; }
usage() { awk '/^# Transport-security smoke test/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || die "--env-file needs a file"; ENV_FILE="$2"; shift 2 ;;
    --tls-dir) [ $# -ge 2 ] || die "--tls-dir needs a folder"; TLS_DIR="$2"; shift 2 ;;
    --public-ca) [ $# -ge 2 ] || die "--public-ca needs a file"; PUBLIC_CA="$2"; shift 2 ;;
    --static) MODE="static"; shift ;;
    --executors) EXECUTORS=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
  done
fi
[ -n "$PYTHON" ] || die "python3 is required"
command -v docker >/dev/null 2>&1 || die "docker is not installed"

# Docker CLI calls with in-container paths: Git Bash must not rewrite /tls/... into a Windows path.
dk() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"; }
# A host path as the Docker CLI on this host wants it.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# Last assignment of KEY in the environment file, as up.sh reads it (no evaluation).
env_value() {
  local key="$1" line value
  [ -f "$ENV_FILE" ] || { printf ''; return; }
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  case "$value" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac
  printf '%s' "$value"
}

# --- results -----------------------------------------------------------------------------------

RESULTS=()
FAILS=0
WARNS=0
record() { # record PASS|FAIL|WARN|SKIP <hop> <check> [detail]
  local status="$1" hop="$2" check="$3" detail="${4:-}"
  RESULTS+=("$status|$hop|$check|$detail")
  [ "$status" = FAIL ] && FAILS=$((FAILS + 1))
  [ "$status" = WARN ] && WARNS=$((WARNS + 1))
  printf '%-4s  %-22s %s%s\n' "$status" "$hop" "$check" "${detail:+  ($detail)}"
}
# b <test command...>: 0 when the command succeeds, else 1 (the third argument of expect).
b() { if "$@"; then echo 0; else echo 1; fi; }
# nz VALUE: true when VALUE is a number other than 0.
nz() { [[ "${1:-}" =~ ^[0-9]+$ ]] && [ "$1" -gt 0 ]; }
expect() { # expect <hop> <check> <condition is true: 0|1> [detail]
  if [ "$3" = 0 ]; then record PASS "$1" "$2" "${4:-}"; else record FAIL "$1" "$2" "${4:-}"; fi
}

summary() {
  echo
  echo "Transport-security smoke test: ${#RESULTS[@]} checks, $FAILS failed, $WARNS warnings"
  printf '%-4s | %-22s | %s\n' "" "hop" "check"
  local r
  for r in "${RESULTS[@]}"; do
    IFS='|' read -r s h c d <<<"$r"
    printf '%-4s | %-22s | %s\n' "$s" "$h" "$c"
  done
  [ "$FAILS" = 0 ]
}

# --- compose model -----------------------------------------------------------------------------

ARGS=()
while IFS= read -r arg; do ARGS+=("$arg"); done < <(bash "$PILOT_DIR/up.sh" --env-file "$ENV_FILE" --print-args)
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
docker compose "${ARGS[@]}" --profile actions --profile monitoring config --format json >"$WORK/model.json" 2>"$WORK/model.err" \
  || { cat "$WORK/model.err" >&2; die "docker compose config failed"; }

# The static checks: what the merged model promises, without running anything.
static_checks() {
  "$PYTHON" - "$WORK/model.json" "$PILOT_DIR/docker-compose.pilot.tls.yml" <<'PY'
import json
import re
import sys

model = json.load(open(sys.argv[1], encoding="utf-8"))
OVERLAY = open(sys.argv[2], encoding="utf-8").read()
services = model["services"]
SPRING = ["da-discovery", "da-catalog", "da-policyengine", "da-reporting", "da-autolabel",
          "da-autoarchival", "da-autodelete", "da-autocompliance", "da-admin-service", "da-logingest"]
# The quickstart keys of each SPRING_APPLICATION_JSON (docker-compose.microservices.yml and the base
# overlay). The TLS overlay replaces the whole value, so none of these may go missing.
JSON_KEYS = {
    "da-discovery": {"dlap.catalog-service.url", "dlap.service-auth.enabled", "dlap.service-auth.token-uri",
                     "dlap.service-auth.client-id"},
    "da-policyengine": {"dlap.services.catalog.url", "dlap.service-auth.enabled", "dlap.service-auth.token-uri",
                        "dlap.service-auth.client-id"},
    "da-reporting": {"dlap.services.da-catalog.url", "dlap.services.da-policyengine.url", "dlap.service-auth.enabled",
                     "dlap.service-auth.token-uri", "dlap.service-auth.client-id"},
    "da-autolabel": {"da-catalog.service.url", "dlap.service-auth.enabled", "dlap.service-auth.token-uri",
                     "dlap.service-auth.client-id", "dlap.autolabel.llm-provider", "dlap.autolabel.allow-mock"},
    "da-autoarchival": {"dlap.catalog-service.url", "dlap.services.da-policyengine.url", "dlap.service-auth.enabled",
                        "dlap.service-auth.token-uri", "dlap.service-auth.client-id"},
    "da-autodelete": {"dlap.catalog-service.url", "dlap.policyengine-service.url", "dlap.service-auth.enabled",
                      "dlap.service-auth.token-uri", "dlap.service-auth.client-id"},
    "da-autocompliance": {"dlap.services.catalog.url", "dlap.service-auth.enabled", "dlap.service-auth.token-uri",
                          "dlap.service-auth.client-id"},
    "da-admin-service": {"services.da-discovery.url", "services.da-autolabel.url", "services.da-autodelete.url",
                         "services.da-autoarchival.url", "services.da-autocompliance.url", "dlap.service-auth.enabled",
                         "dlap.service-auth.token-uri", "dlap.service-auth.client-id"},
}
# The two action executors re-read every approval at the policy engine before they act (approvals.md section 9
# rule 3). Their defaults are plain HTTP (dev and e2e), so the overlay must name the https URL (Q-602, Q-613).
EXECUTOR_APPROVAL_URL = {"da-autoarchival": "dlap.services.da-policyengine.url",
                         "da-autodelete": "dlap.policyengine-service.url"}
# Plain-HTTP URLs that may stay: emulators and Ollama of opt-in profiles that the pilot does not run
# with real data (docs/guides/transport-security.md, "Outside the overlay").
HTTP_ALLOWED = {"DLAP_AUTOLABEL_OLLAMA_BASE_URL", "CLOUD_PROVIDER_UNITY_URI", "DLAP_UNITY_URI"}

results = []


def check(hop, ok, text):
    results.append(("PASS" if ok else "FAIL", hop, text))


def env_of(service):
    environment = service.get("environment") or {}
    if isinstance(environment, list):
        return dict(item.split("=", 1) for item in environment if "=" in item)
    return {k: ("" if v is None else str(v)) for k, v in environment.items()}


def needs_tls_init(name):
    deps = services[name].get("depends_on") or {}
    return deps.get("tls-init", {}).get("condition") == "service_completed_successfully"


for name in SPRING:
    if name not in services:
        check("service", False, f"{name} is missing from the model")
        continue
    env = env_of(services[name])
    check("service listener", env.get("SERVER_SSL_ENABLED") == "true", f"{name} serves TLS (server.ssl.enabled)")
    check("service listener", env.get("SERVER_SSL_CERTIFICATE") == f"file:/tls/{name}.crt"
          and env.get("SERVER_SSL_CERTIFICATE_PRIVATE_KEY") == f"file:/tls/{name}.key",
          f"{name} uses its own certificate and key")
    url = env.get("SPRING_DATASOURCE_URL", "")
    check("service -> postgres", "sslmode=verify-full" in url and "sslrootcert=/tls/ca.crt" in url,
          f"{name} JDBC URL has sslmode=verify-full and the pilot CA")
    check("service -> kafka", env.get("SPRING_KAFKA_SECURITY_PROTOCOL") == "SASL_SSL"
          and env.get("SPRING_KAFKA_PROPERTIES_SASL_MECHANISM") == "SCRAM-SHA-512"
          and env.get("SPRING_KAFKA_SSL_TRUST_STORE_LOCATION") == "file:/tls/ca.crt",
          f"{name} Kafka client is SASL_SSL with SCRAM-SHA-512 and the pilot CA")
    # compose config inlines env_file, so the overlay text is checked for the file, and the merged
    # environment for the login once gen-certs.sh has written it (the password is never printed).
    check("service -> kafka", f"/env/{name}.env" in OVERLAY, f"{name} reads its Kafka login from env/{name}.env")
    jaas = env.get("SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG")
    if jaas is not None:
        check("service -> kafka", "ScramLoginModule" in jaas and f'username="{name}"' in jaas,
              f"{name} logs in to Kafka as SCRAM user {name}")
    check("service -> keycloak", env.get("KEYCLOAK_AUTH_SERVER_URL") == "https://keycloak:8443"
          and env.get("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI", "").startswith("https://keycloak:8443/"),
          f"{name} reads the realm keys over TLS")
    check("jwt", env.get("DLAP_SECURITY_JWT_VALIDATE_ISSUER") == "true"
          and bool(env.get("DLAP_SECURITY_JWT_EXPECTED_ISSUER", "").strip()),
          f"{name} validates the issuer")
    check("jwt", env.get("DLAP_SECURITY_JWT_VALIDATE_AUDIENCE") == "true"
          and bool(env.get("DLAP_SECURITY_JWT_EXPECTED_AUDIENCE", "").strip()),
          f"{name} validates the audience")
    issuers = [i.strip() for i in env.get("DLAP_SECURITY_JWT_EXPECTED_ISSUER", "").split(",") if i.strip()]
    check("jwt", bool(issuers) and all(i.startswith("https://") for i in issuers),
          f"{name} accepts https issuers only ({', '.join(issuers)})")
    check("service -> service", "trustStore=/tls/jvm-truststore.p12" in env.get("JDK_JAVA_OPTIONS", ""),
          f"{name} JVM trusts the pilot CA (JDK_JAVA_OPTIONS)")
    plain = [k for k, v in env.items() if "http://" in v and k not in HTTP_ALLOWED]
    check("service -> service", not plain, f"{name} has no plain-HTTP URL" + (f": {', '.join(sorted(plain))}" if plain else ""))
    if name in JSON_KEYS:
        try:
            blob = json.loads(env.get("SPRING_APPLICATION_JSON", "{}"))
        except ValueError:
            blob = {}
        missing = sorted(JSON_KEYS[name] - set(blob))
        check("service -> service", not missing,
              f"{name} SPRING_APPLICATION_JSON keeps the quickstart keys" + (f", missing {missing}" if missing else ""))
        http = sorted(k for k, v in blob.items() if isinstance(v, str) and v.startswith("http://"))
        check("service -> service", not http, f"{name} SPRING_APPLICATION_JSON has https URLs only" + (f": {http}" if http else ""))
        check("service -> service", "dlap.service-auth.client-secret" not in blob,
              f"{name} keeps the client secret out of SPRING_APPLICATION_JSON (SEC-05)")
        if name in EXECUTOR_APPROVAL_URL:
            key = EXECUTOR_APPROVAL_URL[name]
            check("service -> service", blob.get(key) == "https://da-policyengine:8080",
                  f"{name} reads approvals at https://da-policyengine:8080 ({key}), the pilot-CA-verified door of the policy engine")
    check("monitoring", env.get("MANAGEMENT_SERVER_PORT") == "9091" and env.get("MANAGEMENT_SERVER_SSL_ENABLED") == "true"
          and env.get("MANAGEMENT_SERVER_SSL_CERTIFICATE") == f"file:/tls/{name}.crt"
          and env.get("MANAGEMENT_SERVER_SSL_CERTIFICATE_PRIVATE_KEY") == f"file:/tls/{name}.key",
          f"{name} serves its management port 9091 over TLS with its own certificate (Q-518)")
    check("key material", needs_tls_init(name), f"{name} waits for tls-init")
    deps = services[name].get("depends_on") or {}
    check("key material", deps.get("kafka-init", {}).get("condition") == "service_completed_successfully",
          f"{name} waits for kafka-init (users and ACLs)")

# new-ui
ui = env_of(services.get("new-ui", {}))
check("gateway -> new-ui", ui.get("SERVER_SSL_ENABLED") == "true" and ui.get("SERVER_SSL_CERTIFICATE") == "file:/tls/new-ui.crt",
      "new-ui serves TLS with its own certificate")
check("gateway -> new-ui", not [v for v in ui.values() if "http://" in v], "new-ui has no plain-HTTP URL")

# gateway
gw = services.get("gateway", {})
ports = gw.get("ports") or []
check("browser -> gateway", len(ports) == 1 and str(ports[0].get("target")) == "8443",
      "the gateway publishes one port, the TLS listener 8443")
mounts = [v.get("source", "").replace("\\", "/") for v in gw.get("volumes") or []]
check("browser -> gateway", any(m.endswith("/pilot/tls/nginx.pilot.tls.conf") for m in mounts),
      "the gateway runs tls/nginx.pilot.tls.conf")

# Kafka
kafka = env_of(services.get("kafka", {}))
protocols = dict(p.split(":", 1) for p in kafka.get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "").split(",") if ":" in p)
check("kafka listeners", bool(protocols) and all(v in ("SSL", "SASL_SSL") for v in protocols.values()),
      f"every broker listener is SSL or SASL_SSL ({kafka.get('KAFKA_LISTENER_SECURITY_PROTOCOL_MAP', '')})")
check("kafka listeners", not (services.get("kafka", {}).get("ports") or []), "the broker publishes no host port")
check("kafka acl", kafka.get("KAFKA_AUTHORIZER_CLASS_NAME", "").endswith("StandardAuthorizer")
      and kafka.get("KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND") == "false",
      "the broker authorizes every request and denies without an ACL")
check("kafka -> kafka", kafka.get("KAFKA_LISTENER_NAME_SSL_SSL_CLIENT_AUTH") == "required"
      and kafka.get("KAFKA_LISTENER_NAME_CONTROLLER_SSL_CLIENT_AUTH") == "required",
      "controller and inter-broker listeners require a client certificate")

# PostgreSQL
pg = services.get("postgresql", {})
command = pg.get("command") or []
check("service -> postgres", "ssl=on" in command and "hba_file=/etc/dlap-tls/pg_hba.conf" in command,
      "PostgreSQL serves TLS and refuses TCP without it (hba_file)")
check("service -> postgres", "log_statement=none" in command, "PostgreSQL keeps the base overlay's log_statement=none")
pe = env_of(services.get("postgres-exporter", {}))
check("monitoring", "sslmode=verify-full" in pe.get("DATA_SOURCE_URI", ""), "postgres-exporter connects with verify-full")
prom = services.get("prometheus", {})
prom_cmd = prom.get("command") or []
check("monitoring", "--config.file=/etc/prometheus/prometheus.tls.yml" in prom_cmd,
      "Prometheus runs prometheus.tls.yml, which scrapes the services over HTTPS (Q-518)")
check("monitoring", any(v.get("target") == "/tls" and v.get("read_only") for v in prom.get("volumes") or []),
      "Prometheus mounts the pilot CA read-only")
check("monitoring", needs_tls_init("prometheus"), "Prometheus waits for tls-init")

# Keycloak
kc = env_of(services.get("keycloak", {}))
check("service -> keycloak", kc.get("KC_HTTP_ENABLED") == "false" and kc.get("KC_HTTPS_CERTIFICATE_FILE") == "/tls/keycloak.crt",
      "Keycloak serves HTTPS only")
check("service -> keycloak", kc.get("DLAP_REALM_SSL_REQUIRED") == "all",
      "the realm import sets sslRequired all for realm dlap (Q-514, D31)")
kc_ports = services.get("keycloak", {}).get("ports") or []
check("service -> keycloak", all(str(p.get("target")) == "8443" and p.get("host_ip") == "127.0.0.1" for p in kc_ports),
      "Keycloak's admin port is the loopback TLS port")

for name in ("postgresql", "keycloak", "kafka", "gateway", "new-ui"):
    check("key material", needs_tls_init(name), f"{name} waits for tls-init")

for status, hop, text in results:
    print(f"{status}|{hop}|{text}")
PY
}

run_static() {
  local line
  while IFS= read -r line; do
    IFS='|' read -r s h c <<<"$line"
    record "$s" "$h" "$c"
  done < <(static_checks)
}

run_static
if [ "$MODE" = static ]; then
  summary
  exit $?
fi

# --- live: locate the running pilot ------------------------------------------------------------

[ -f "$ENV_FILE" ] || die "environment file not found: $ENV_FILE"
if [ -z "$TLS_DIR" ]; then
  TLS_DIR="$(env_value PILOT_TLS_DIR)"
  if [ -z "$TLS_DIR" ]; then
    TLS_DIR="$SCRIPT_DIR/generated"
  elif [[ "$TLS_DIR" != /* ]] && [[ "$TLS_DIR" != [A-Za-z]:* ]]; then
    TLS_DIR="$PILOT_DIR/../dev/$TLS_DIR"
  fi
fi
[ -s "$TLS_DIR/certs/ca.crt" ] || die "no pilot CA at $TLS_DIR/certs/ca.crt (--tls-dir)"
[ -n "$PUBLIC_CA" ] || PUBLIC_CA="$TLS_DIR/certs/ca.crt"

ORIGIN="$(env_value PILOT_PUBLIC_ORIGIN)"; ORIGIN="${ORIGIN:-https://localhost}"
AUDIENCE="$(env_value PILOT_JWT_AUDIENCE)"; AUDIENCE="${AUDIENCE:-dlap-api}"
ORIGIN_HOSTPORT="${ORIGIN#https://}"; ORIGIN_HOSTPORT="${ORIGIN_HOSTPORT%%/*}"
ORIGIN_HOST="${ORIGIN_HOSTPORT%%:*}"
ORIGIN_PORT="443"; [[ "$ORIGIN_HOSTPORT" == *:* ]] && ORIGIN_PORT="${ORIGIN_HOSTPORT##*:}"
BACKEND_SECRET="$(env_value DLAP_BACKEND_CLIENT_SECRET)"
GATEWAY_SECRET="$(env_value DLAP_MCP_GATEWAY_CLIENT_SECRET)"
PG_PASSWORD="$(env_value POSTGRES_PASSWORD)"
[ -n "$BACKEND_SECRET" ] && [ -n "$GATEWAY_SECRET" ] && [ -n "$PG_PASSWORD" ] \
  || die "DLAP_BACKEND_CLIENT_SECRET, DLAP_MCP_GATEWAY_CLIENT_SECRET and POSTGRES_PASSWORD must be set in $ENV_FILE"

project="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$WORK/model.json")"
volume="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["volumes"]["pilot-tls"]["name"])' "$WORK/model.json")"
network="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["networks"]["dlap-network"]["name"])' "$WORK/model.json")"
dk network inspect "$network" >/dev/null 2>&1 || die "the pilot network $network does not exist: is the pilot running? (up.sh ps)"

cid_of() { docker compose "${ARGS[@]}" ps -q "$1" 2>/dev/null | head -n 1; }
running=()
for svc in "${SPRING[@]}" new-ui gateway keycloak kafka postgresql; do
  cid="$(cid_of "$svc")"
  [ -n "$cid" ] && [ "$(dk inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)" = true ] && running+=("$svc")
done
is_running() { [[ " ${running[*]} " == *" $1 "* ]]; }
for svc in gateway keycloak kafka postgresql; do is_running "$svc" || die "$svc is not running (up.sh ps)"; done
echo "smoke-tls.sh: project $project, network $network, origin $ORIGIN, running: ${running[*]}"
START_EPOCH="$(date +%s)"

# A throwaway container on the pilot network with the pilot-tls volume read-only.
on_net() { dk run --rm -i --network "$network" --mount "type=volume,source=$volume,target=/tls,readonly" "$@"; }

port_of() { case "$1" in da-logingest) echo 8090 ;; new-ui) echo 3000 ;; keycloak) echo 8443 ;; *) echo 8080 ;; esac; }

# --- H1 browser -> gateway ---------------------------------------------------------------------

{
  echo "set -u"
  echo "o=\"$ORIGIN_HOSTPORT\"; h=\"$ORIGIN_HOST\"; p=\"$ORIGIN_PORT\""
  cat <<'SH'
c="--connect-to $h:$p:gateway:8443 --cacert /pub/ca.crt -sS --max-time 20"
curl $c -o /tmp/index.html -D /tmp/h1 -w '%{http_code} %{ssl_verify_result}\n' "https://$o/" > /tmp/r1 2>/tmp/e1
echo "root=$(cat /tmp/r1)"
for hd in strict-transport-security content-security-policy x-content-type-options x-frame-options referrer-policy; do
  grep -qi "^$hd:" /tmp/h1 && echo "header_$hd=yes" || echo "header_$hd=no"
done
js="$(grep -o 'src="/[^"]*\.js"' /tmp/index.html | head -n 1 | sed 's/src="//;s/"$//')"
echo "bundle=$js"
if [ -n "$js" ]; then
  curl $c -o /tmp/bundle.js "https://$o$js" 2>/dev/null
  grep -c 'http://localhost:8081' /tmp/bundle.js | sed 's/^/bundle_plain_api=/'
  grep -c 'http://localhost:8180' /tmp/bundle.js | sed 's/^/bundle_plain_kc=/'
  grep -c "https://$o" /tmp/bundle.js | sed 's/^/bundle_origin=/'
fi
curl $c "https://$o/realms/dlap/.well-known/openid-configuration" 2>/dev/null | sed -n 's/.*"issuer":"\([^"]*\)".*/issuer=\1/p'; echo
echo "master=$(curl $c -o /dev/null -w '%{http_code}' "https://$o/realms/master/" 2>/dev/null)"
echo "api_anon=$(curl $c -o /dev/null -w '%{http_code}' "https://$o/api/v1/catalog/assets" 2>/dev/null)"
echo "plain=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' http://gateway:8443/ 2>/dev/null)"
echo "tls11=$(curl $c --tlsv1.1 --tls-max 1.1 -o /dev/null -w '%{http_code}' "https://$o/" 2>/dev/null)"
echo "noca=$(curl --connect-to $h:$p:gateway:8443 -sS --max-time 10 -o /dev/null -w '%{http_code}' "https://$o/" 2>/dev/null)"
SH
} >"$WORK/h1.sh"
pubdir="$WORK/pub"; mkdir -p "$pubdir"; cp "$PUBLIC_CA" "$pubdir/ca.crt"
h1="$(on_net --mount "type=bind,source=$(native "$pubdir"),target=/pub,readonly" --entrypoint sh "$CURL_IMAGE" -s <"$WORK/h1.sh" 2>&1)"
val() { sed -n "s/^$1=//p" <<<"$2" | head -n 1; }
root="$(val root "$h1")"
expect "browser -> gateway" "TLS handshake with the pilot CA, certificate verified for $ORIGIN_HOST" "$([ "${root% *}" = 200 ] && [ "${root#* }" = 0 ] && echo 0 || echo 1)" "HTTP ${root:-none}"
for hd in strict-transport-security content-security-policy x-content-type-options x-frame-options referrer-policy; do
  expect "browser -> gateway" "header $hd on the SPA" "$([ "$(val "header_$hd" "$h1")" = yes ] && echo 0 || echo 1)"
done
expect "browser -> gateway" "plain HTTP on the TLS port gets no content" "$([ "$(val plain "$h1")" = 400 ] && echo 0 || echo 1)" "HTTP $(val plain "$h1")"
expect "browser -> gateway" "a client without the pilot CA refuses the certificate" "$(b [ "$(val noca "$h1")" = 000 ])" "HTTP $(val noca "$h1")"
expect "browser -> gateway" "TLS 1.1 is refused" "$(b [ "$(val tls11 "$h1")" = 000 ])" "HTTP $(val tls11 "$h1")"
if [ -n "$(val bundle "$h1")" ]; then
  expect "browser -> gateway" "the SPA bundle names no plain-HTTP origin" "$([ "$(val bundle_plain_api "$h1")" = 0 ] && [ "$(val bundle_plain_kc "$h1")" = 0 ] && echo 0 || echo 1)"
  expect "browser -> gateway" "the SPA bundle calls https://$ORIGIN_HOSTPORT" "$(b nz "$(val bundle_origin "$h1")")" "$(val bundle_origin "$h1") places"
else
  record FAIL "browser -> gateway" "the SPA index names a script bundle"
fi
expect "browser -> keycloak" "browser-side issuer is $ORIGIN/realms/dlap" "$([ "$(val issuer "$h1")" = "$ORIGIN/realms/dlap" ] && echo 0 || echo 1)" "$(val issuer "$h1")"
expect "browser -> keycloak" "the master realm is not served on the entry point" "$([ "$(val master "$h1")" = 404 ] && echo 0 || echo 1)" "HTTP $(val master "$h1")"
expect "gateway -> services" "an API call reaches da-catalog over TLS (401 without a token)" "$([ "$(val api_anon "$h1")" = 401 ] && echo 0 || echo 1)" "HTTP $(val api_anon "$h1")"

# The published port itself, from the host.
bind="$(docker compose "${ARGS[@]}" port gateway 8443 2>/dev/null | head -n 1)"
if [ -n "$bind" ] && command -v curl >/dev/null 2>&1; then
  hostport="${bind##*:}"
  revoke=(); curl -V 2>/dev/null | grep -q Schannel && revoke=(--ssl-no-revoke)
  code="$(curl -sS --max-time 15 "${revoke[@]}" --cacert "$(native "$PUBLIC_CA")" --connect-to "$ORIGIN_HOST:$ORIGIN_PORT:127.0.0.1:$hostport" -o /dev/null -w '%{http_code}' "https://$ORIGIN_HOSTPORT/" 2>/dev/null || true)"
  expect "browser -> gateway" "the published port $bind answers over verified TLS" "$([ "$code" = 200 ] && echo 0 || echo 1)" "HTTP ${code:-none}"
else
  record SKIP "browser -> gateway" "the published port from the host" "no host curl or no published port"
fi

# --- H2 every upstream: TLS with a certificate for its name, no plain-text door ----------------

{
  echo "set -u"
  for svc in "${running[@]}"; do
    case "$svc" in kafka|postgresql|gateway) continue ;; esac
    p="$(port_of "$svc")"
    path="/actuator/health"; [ "$svc" = new-ui ] && path="/"; [ "$svc" = keycloak ] && path="/realms/dlap"
    echo "echo \"tls_$svc=\$(curl -sS --max-time 15 --cacert /tls/ca.crt -o /dev/null -w '%{http_code}' https://$svc:$p$path 2>/dev/null || echo 000)\""
    echo "b=\$(curl -sS --max-time 10 http://$svc:$p$path 2>/dev/null | tr -d '\r\n' | head -c 300); echo \"plain_$svc=\$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' http://$svc:$p$path 2>/dev/null || echo 000) \$b\""
  done
  echo "echo \"kc_http=\$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' http://keycloak:8080/realms/dlap 2>/dev/null || echo 000)\""
} >"$WORK/h2.sh"
h2="$(on_net --entrypoint sh "$CURL_IMAGE" -s <"$WORK/h2.sh" 2>&1)"
for svc in "${running[@]}"; do
  case "$svc" in kafka|postgresql|gateway) continue ;; esac
  hop="gateway -> $svc"; [[ "$svc" == da-* ]] && hop="peer -> $svc"; [ "$svc" = keycloak ] && hop="service -> keycloak"
  code="$(val "tls_$svc" "$h2")"
  expect "$hop" "$svc answers TLS with a certificate for its name" "$([ -n "$code" ] && [ "$code" != 000 ] && echo 0 || echo 1)" "HTTP ${code:-none}"
  plain="$(val "plain_$svc" "$h2")"
  # Tomcat answers plain text on a TLS port with 400 and "requires TLS"; anything else is a door left open.
  expect "$hop" "$svc refuses plain HTTP" "$( { [[ "$plain" == 000* ]] || { [[ "$plain" == 400* ]] && { [[ "$plain" == *TLS* ]] || [[ "$svc" != da-* ]]; }; }; } && echo 0 || echo 1)" "${plain%% *}"
done
# curl prints 000 for a refused connection and the fallback adds another 000, as in the plain checks above.
if [[ "$(val kc_http "$h2")" == 000* ]]; then
  record PASS "service -> keycloak" "Keycloak's plain-HTTP port is closed"
elif dk inspect -f '{{json .Config.Cmd}} {{json .Args}}' "$(cid_of keycloak)" 2>/dev/null | grep -q 'start-dev'; then
  # Keycloak's development mode always opens its HTTP listener (KC_HTTP_ENABLED and --http-enabled are
  # ignored there). No service uses it; the production Keycloak overlay (kc.sh start) closes it.
  record WARN "service -> keycloak" "Keycloak's plain-HTTP port is closed" "open: Keycloak runs start-dev (quickstart realm), which cannot close it"
else
  record FAIL "service -> keycloak" "Keycloak's plain-HTTP port is closed" "HTTP $(val kc_http "$h2")"
fi

# --- H2b the management port: TLS, and the only door to /actuator/prometheus (Q-518) ------------

{
  echo "set -u"
  for svc in "${running[@]}"; do
    [[ "$svc" == da-* ]] || continue
    p="$(port_of "$svc")"
    echo "echo \"mg_health_$svc=\$(curl -sS --max-time 15 --cacert /tls/ca.crt -o /dev/null -w '%{http_code}' https://$svc:9091/actuator/health 2>/dev/null || echo 000)\""
    echo "echo \"mg_metrics_$svc=\$(curl -sS --max-time 15 --cacert /tls/ca.crt https://$svc:9091/actuator/prometheus 2>/dev/null | grep -c '^jvm_memory_used_bytes')\""
    echo "echo \"mg_plain_$svc=\$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' http://$svc:9091/actuator/prometheus 2>/dev/null || echo 000)\""
    echo "echo \"mg_app_$svc=\$(curl -sS --max-time 15 --cacert /tls/ca.crt -o /dev/null -w '%{http_code}' https://$svc:$p/actuator/prometheus 2>/dev/null || echo 000)\""
  done
} >"$WORK/h2b.sh"
h2b="$(on_net --entrypoint sh "$CURL_IMAGE" -s <"$WORK/h2b.sh" 2>&1)"
for svc in "${running[@]}"; do
  [[ "$svc" == da-* ]] || continue
  expect "monitoring" "$svc: /actuator/health on the management port answers 200 over verified TLS" "$([ "$(val "mg_health_$svc" "$h2b")" = 200 ] && echo 0 || echo 1)" "HTTP $(val "mg_health_$svc" "$h2b")"
  expect "monitoring" "$svc: /actuator/prometheus on the management port serves metrics without a token" "$(b nz "$(val "mg_metrics_$svc" "$h2b")")" "$(val "mg_metrics_$svc" "$h2b") jvm_memory_used_bytes lines"
  plain_mg="$(val "mg_plain_$svc" "$h2b")"
  expect "monitoring" "$svc: the management port refuses plain HTTP" "$( { [[ "$plain_mg" == 000* ]] || [[ "$plain_mg" == 400* ]]; } && echo 0 || echo 1)" "HTTP ${plain_mg%% *}"
  app_mg="$(val "mg_app_$svc" "$h2b")"
  expect "monitoring" "$svc: the application port does not serve /actuator/prometheus without a token" "$( { [ "$app_mg" = 401 ] || [ "$app_mg" = 403 ] || [ "$app_mg" = 404 ]; } && echo 0 || echo 1)" "HTTP $app_mg"
done

# --- H2c Prometheus scrapes the services over HTTPS (monitoring profile only) --------------------

prom_cid="$(dk ps -q --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=prometheus" | head -n 1)"
if [ -n "$prom_cid" ]; then
  cat >"$WORK/h2c.sh" <<'SH'
set -u
# The first scrape of every target takes up to one interval (15 s); wait until none is still unknown.
i=0
while [ "$i" -lt 12 ]; do
  out="$(curl -sS --max-time 15 'http://prometheus:9090/api/v1/targets?state=active' 2>/dev/null)"
  case "$out" in *'"health":"unknown"'*) i=$((i + 1)); sleep 10 ;; *) break ;; esac
done
printf 'targets=%s\n' "$(printf '%s' "$out" | tr -d '\n')"
SH
  h2c="$(on_net --entrypoint sh "$CURL_IMAGE" -s <"$WORK/h2c.sh" 2>&1)"
  "$PYTHON" - "$(val targets "$h2c")" "${running[*]}" >"$WORK/h2c.out" <<'PY'
import json
import sys

try:
    targets = json.loads(sys.argv[1])["data"]["activeTargets"]
except (ValueError, KeyError):
    print("FAIL|monitoring|Prometheus answered its targets API")
    sys.exit(0)
running = [s for s in sys.argv[2].split() if s.startswith("da-")]
jobs = {t["scrapeUrl"].split("/")[2]: t for t in targets if t["labels"].get("job") == "dlap-services"}
for svc in running:
    target = jobs.get(f"{svc}:9091")
    ok = bool(target) and target["scrapeUrl"].startswith("https://") and target["health"] == "up"
    detail = "not a target" if not target else f"{target['scrapeUrl'].split(':')[0]} {target['health']} {target.get('lastError', '')[:80]}"
    print(f"{'PASS' if ok else 'FAIL'}|monitoring|Prometheus scrapes {svc} over HTTPS and the target is up ({detail.strip()})")
plain = [t["scrapeUrl"] for t in targets if t["labels"].get("job") == "dlap-services" and not t["scrapeUrl"].startswith("https://")]
print(f"{'PASS' if not plain else 'FAIL'}|monitoring|no dlap-services target is scraped over plain HTTP" + (f" ({plain})" if plain else ""))
PY
  while IFS='|' read -r s h c; do record "$s" "$h" "$c"; done <"$WORK/h2c.out"
else
  record SKIP "monitoring" "Prometheus scrapes the services over HTTPS" "the monitoring profile is off"
fi

# --- H3 PostgreSQL -----------------------------------------------------------------------------

cat >"$WORK/h3.sh" <<'SH'
set -u
q() { psql -X -A -t -v ON_ERROR_STOP=1 "$1" -c "$2" 2>&1; }
base="host=postgresql port=5432 dbname=dlap user=dlap"
echo "verify_full=$(q "$base sslmode=verify-full sslrootcert=/tls/ca.crt" 'select 1' | tr -d '\n')"
echo "no_tls=$(q "$base sslmode=disable" 'select 1' | tr '\n' ' ' | head -c 200)"
ip="$(getent hosts postgresql | awk '{print $1}' | head -n 1)"
echo "wrong_name=$(q "host=$ip port=5432 dbname=dlap user=dlap sslmode=verify-full sslrootcert=/tls/ca.crt" 'select 1' | tr '\n' ' ' | head -c 200)"
q "$base sslmode=verify-full sslrootcert=/tls/ca.crt" "select 'conn=' || a.datname || ',' || a.usename || ',' || s.ssl || ',' || coalesce(s.version, '-') from pg_stat_activity a join pg_stat_ssl s using (pid) where a.backend_type = 'client backend' and a.client_addr is not null"
SH
h3="$(on_net -e PGPASSWORD="$PG_PASSWORD" --entrypoint bash "$PG_IMAGE" -s <"$WORK/h3.sh" 2>&1)"
expect "service -> postgres" "a verify-full client connects (certificate valid for postgresql)" "$([ "$(val verify_full "$h3")" = 1 ] && echo 0 || echo 1)"
expect "service -> postgres" "a client without TLS is refused" "$(grep -q 'no encryption' <<<"$(val no_tls "$h3")" && echo 0 || echo 1)" "$(val no_tls "$h3" | head -c 90)"
expect "service -> postgres" "verify-full refuses the server under another name (its IP)" "$(grep -qiE 'does not match|server certificate' <<<"$(val wrong_name "$h3")" && echo 0 || echo 1)"
conns="$(sed -n 's/^conn=//p' <<<"$h3")"
# s.ssl || text prints true or false.
plain_conns="$(awk -F, '$3 != "true" && $3 != "t"' <<<"$conns" | grep -c . || true)"
expect "service -> postgres" "every TCP session in pg_stat_ssl uses TLS" "$([ -n "$conns" ] && [ "$plain_conns" = 0 ] && echo 0 || echo 1)" "$(grep -c . <<<"$conns") sessions, $plain_conns without TLS, versions $(awk -F, '{print $4}' <<<"$conns" | sort -u | tr '\n' ' ')"
for svc in "${running[@]}"; do
  case "$svc" in da-catalog) db=dlap_common ;; da-admin-service) db=da_admin ;; da-*) db="${svc//-/_}" ;; *) continue ;; esac
  n="$(awk -F, -v d="$db" '$1 == d && ($3 == "true" || $3 == "t")' <<<"$conns" | grep -c . || true)"
  expect "service -> postgres" "$svc holds TLS sessions to $db" "$([ "$n" -gt 0 ] && echo 0 || echo 1)" "$n sessions"
done

# --- H4 Kafka ----------------------------------------------------------------------------------

{
  echo "set -u"
  echo "SERVICES='${running[*]}'"
  cat <<'SH'
umask 077
cd /tmp
apw="$(cat /tls/dlap-kafka-admin.password)"
cat > admin.properties <<EOF
security.protocol=SSL
ssl.keystore.type=PKCS12
ssl.keystore.location=/tls/dlap-kafka-admin.keystore.p12
ssl.keystore.password=$apw
ssl.key.password=$apw
ssl.truststore.type=PEM
ssl.truststore.location=/tls/ca.crt
EOF
scram() { # scram <principal> <password> <file>
  cat > "$3" <<EOF
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="$1" password="$2";
ssl.truststore.type=PEM
ssl.truststore.location=/tls/ca.crt
request.timeout.ms=10000
default.api.timeout.ms=15000
EOF
}
scram da-catalog "$(cat /tls/scram/da-catalog.password)" catalog.properties
scram da-reporting "$(cat /tls/scram/da-reporting.password)" reporting.properties
scram da-catalog "wrong-$(cat /tls/scram/da-catalog.password)" wrong.properties
printf '%s\n' security.protocol=PLAINTEXT request.timeout.ms=5000 > plain.properties
printf '%s\n' security.protocol=SSL ssl.truststore.type=PEM ssl.truststore.location=/tls/ca.crt request.timeout.ms=5000 > nocert.properties
t() { timeout "$1" "${@:2}" 2>&1; }
echo "plain_client=$(t 25 kafka-broker-api-versions --bootstrap-server kafka:29092 --command-config plain.properties | grep -c 'id: 1 rack' )"
echo "nocert_internal=$(t 25 kafka-broker-api-versions --bootstrap-server kafka:29094 --command-config nocert.properties | grep -c 'id: 1 rack')"
echo "wrong_password=$(t 30 kafka-broker-api-versions --bootstrap-server kafka:29092 --command-config wrong.properties | grep -ciE 'authentication failed|SaslAuthenticationException')"
echo "scram_ok=$(t 30 kafka-broker-api-versions --bootstrap-server kafka:29092 --command-config catalog.properties | grep -c 'id: 1 rack')"
echo "admin_ok=$(t 30 kafka-broker-api-versions --bootstrap-server kafka:29094 --command-config admin.properties | grep -c 'id: 1 rack')"
echo smoke-tls | t 40 kafka-console-producer --bootstrap-server kafka:29092 --producer.config catalog.properties --topic dlap.policies.actions > p1.log
echo "catalog_writes_actions=$(grep -ciE 'TOPIC_AUTHORIZATION_FAILED|TopicAuthorizationException|Not authorized' p1.log)"
t 40 kafka-console-consumer --bootstrap-server kafka:29092 --consumer.config reporting.properties --group da-reporting-smoke-tls --topic dlap.log.event.normalized --max-messages 1 --timeout-ms 15000 > c1.log 2>&1
echo "reporting_reads_spine=$(grep -ciE 'TOPIC_AUTHORIZATION_FAILED|TopicAuthorizationException|Not authorized' c1.log)"
t 40 kafka-console-consumer --bootstrap-server kafka:29092 --consumer.config catalog.properties --group da-reporting-smoke-tls --topic dlap.policies.actions --max-messages 1 --timeout-ms 15000 > c2.log 2>&1
echo "catalog_joins_reporting_group=$(grep -ciE 'GROUP_AUTHORIZATION_FAILED|GroupAuthorizationException|Not authorized' c2.log)"
t 40 kafka-console-consumer --bootstrap-server kafka:29092 --consumer.config reporting.properties --group da-reporting-smoke-tls --topic dlap.access.decision --max-messages 1 --timeout-ms 10000 > /dev/null 2> c3.log
echo "reporting_reads_decisions_denied=$(grep -ciE 'AUTHORIZATION_FAILED|AuthorizationException|Not authorized' c3.log)"
t 60 kafka-consumer-groups --bootstrap-server kafka:29094 --command-config admin.properties --describe --all-groups --members 2>/dev/null \
  | awk 'NF >= 5 && $1 != "GROUP" && $2 != "-" { print "member=" $1 }' | sort -u
t 30 kafka-acls --bootstrap-server kafka:29094 --command-config admin.properties --list 2>/dev/null | grep -c 'principal=User:' | sed 's/^/acl_count=/'
SH
} >"$WORK/h4.sh"
h4="$(on_net --user 0 --entrypoint bash "$KAFKA_IMAGE" -s <"$WORK/h4.sh" 2>&1)"
expect "service -> kafka" "a PLAINTEXT client gets no broker answer" "$([ "$(val plain_client "$h4")" = 0 ] && echo 0 || echo 1)"
expect "kafka -> kafka" "the mutual-TLS listener refuses a client without a certificate" "$([ "$(val nocert_internal "$h4")" = 0 ] && echo 0 || echo 1)"
expect "service -> kafka" "a wrong SCRAM password is refused" "$(b nz "$(val wrong_password "$h4")")"
expect "service -> kafka" "a service's SCRAM login over SASL_SSL works (da-catalog)" "$([ "$(val scram_ok "$h4")" = 1 ] && echo 0 || echo 1)"
expect "kafka -> kafka" "the admin certificate works on the mutual-TLS listener" "$([ "$(val admin_ok "$h4")" = 1 ] && echo 0 || echo 1)"
expect "kafka acl" "da-catalog may not write dlap.policies.actions" "$(b nz "$(val catalog_writes_actions "$h4")")"
expect "kafka acl" "da-reporting may not read dlap.log.event.normalized" "$(b nz "$(val reporting_reads_spine "$h4")")"
expect "kafka acl" "da-catalog may not join a da-reporting consumer group" "$(b nz "$(val catalog_joins_reporting_group "$h4")")"
expect "kafka acl" "da-reporting may read dlap.access.decision (its own ACL)" "$([ "$(val reporting_reads_decisions_denied "$h4")" = 0 ] && echo 0 || echo 1)"
members="$(sed -n 's/^member=//p' <<<"$h4")"
while read -r principal verb names; do
  [ "$verb" = group ] || continue
  is_running "$principal" || continue
  n=0
  for prefix in $names; do n=$((n + $(grep -c "^$prefix" <<<"$members" || true))); done
  expect "service -> kafka" "$principal has consumer groups with members (SCRAM over SASL_SSL, Read ACLs)" "$([ "$n" -gt 0 ] && echo 0 || echo 1)" "$n groups"
done < <(sed 's/#.*//' "$SCRIPT_DIR/kafka-acls.txt" | awk 'NF >= 3')
# The action executors' own groups, named: the prefix match above passes with one group of five (WIRE-01, Q-602).
# da-autoarchival: policy actions, approval decisions, archive proof. da-autodelete: policy actions, delete proof.
for group in da-autoarchival-consumer-group da-autoarchival-consumer-group-approvals da-autoarchival-consumer-group-proof \
             da-autodelete-policy-actions da-autodelete-deletion-proof; do
  owner="${group%%-consumer-group*}"; owner="${owner%%-policy-actions}"; owner="${owner%%-deletion-proof}"
  is_running "$owner" || continue
  expect "service -> kafka" "$owner consumer group $group has a member (topic and group ACLs, no authorization error)" \
    "$(grep -qx "$group" <<<"$members" && echo 0 || echo 1)"
done
expect "kafka acl" "the broker holds the ACLs of kafka-acls.txt" "$(b nz "$(val acl_count "$h4")")" "$(val acl_count "$h4") ACL lines"

# --- H5 JWT issuer and audience in every service ----------------------------------------------

{
  echo "set -u"
  echo "o=\"$ORIGIN_HOSTPORT\"; h=\"$ORIGIN_HOST\"; p=\"$ORIGIN_PORT\""
  echo "SERVICES='${running[*]}'"
  cat <<'SH'
tok() { # tok <url> <client> <secret> [curl args]: an access token, or nothing
  curl -sS --max-time 20 --cacert /tls/ca.crt "${@:4}" -d grant_type=client_credentials -d "client_id=$2" --data-urlencode "client_secret=$3" "$1" 2>/dev/null \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
kc=https://keycloak:8443/realms/dlap/protocol/openid-connect/token
good="$(tok "$kc" dlap-backend "$BACKEND_SECRET")"
aud="$(tok "$kc" dlap-mcp-gateway "$GATEWAY_SECRET")"
iss="$(tok "$kc" dlap-backend "$BACKEND_SECRET" -H 'Host: issuer-check.invalid:8443')"
pub="$(curl -sS --max-time 20 --cacert /pub/ca.crt --connect-to "$h:$p:gateway:8443" -d grant_type=client_credentials -d client_id=dlap-backend --data-urlencode "client_secret=$BACKEND_SECRET" "https://$o/realms/dlap/protocol/openid-connect/token" 2>/dev/null | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
echo "token_good=$good"
echo "token_aud=$aud"
echo "token_iss=$iss"
echo "token_pub=$pub"
call() { # call <svc> <port> <token or -> : "<code> <www-authenticate>"
  if [ "$3" = - ]; then a=""; else a="Authorization: Bearer $3"; fi
  curl -sS --max-time 15 --cacert /tls/ca.crt -o /dev/null -D /tmp/hd ${a:+-H "$a"} -w '%{http_code}' "https://$1:$2/api/v1/smoke-tls-no-such-route" 2>/dev/null
  printf ' %s\n' "$(grep -i '^www-authenticate:' /tmp/hd | tr -d '\r' | head -n 1)"
}
for s in $SERVICES; do
  case "$s" in da-*) ;; *) continue ;; esac
  port=8080; [ "$s" = da-logingest ] && port=8090
  echo "call_${s}_none=$(call "$s" "$port" -)"
  echo "call_${s}_good=$(call "$s" "$port" "${good:--}")"
  echo "call_${s}_aud=$(call "$s" "$port" "${aud:--}")"
  echo "call_${s}_iss=$(call "$s" "$port" "${iss:--}")"
done
echo "pub_assets=$(curl -sS --max-time 20 --cacert /pub/ca.crt --connect-to "$h:$p:gateway:8443" -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${pub:-none}" "https://$o/api/v1/catalog/assets?size=1" 2>/dev/null)"
SH
} >"$WORK/h5.sh"
h5="$(on_net -e BACKEND_SECRET="$BACKEND_SECRET" -e GATEWAY_SECRET="$GATEWAY_SECRET" \
  --mount "type=bind,source=$(native "$pubdir"),target=/pub,readonly" --entrypoint sh "$CURL_IMAGE" -s <"$WORK/h5.sh" 2>&1)"

claims() { # claims <jwt>: "iss aud" of the token, decoded on the host
  "$PYTHON" -c '
import base64, json, sys
t = sys.argv[1].split(".")
if len(t) < 2:
    print("- -"); sys.exit()
p = json.loads(base64.urlsafe_b64decode(t[1] + "=" * (-len(t[1]) % 4)))
aud = p.get("aud")
aud = ",".join(aud) if isinstance(aud, list) else (aud or "-")
print(p.get("iss", "-"), aud)' "${1:-x}"
}
read -r good_iss good_aud <<<"$(claims "$(val token_good "$h5")")"
read -r aud_iss aud_aud <<<"$(claims "$(val token_aud "$h5")")"
read -r iss_iss iss_aud <<<"$(claims "$(val token_iss "$h5")")"
read -r pub_iss pub_aud <<<"$(claims "$(val token_pub "$h5")")"
# A keycloak overlay that fixes the host name (KC_HOSTNAME) gives service tokens the public issuer too.
expect "jwt" "dlap-backend token (internal) carries an accepted issuer" "$(case "$good_iss" in "https://keycloak:8443/realms/dlap"|"$ORIGIN/realms/dlap") echo 0 ;; *) echo 1 ;; esac)" "$good_iss"
if [[ ",$good_aud," == *",$AUDIENCE,"* ]]; then
  record PASS "jwt" "dlap-backend token carries the audience $AUDIENCE"
else
  record FAIL "jwt" "dlap-backend token carries the audience $AUDIENCE" "aud=$good_aud: the realm needs an oidc-audience-mapper '$AUDIENCE' on dlap-backend and dlap-ui"
fi
expect "jwt" "dlap-mcp-gateway token lacks $AUDIENCE (the wrong-audience probe)" "$([ -n "$(val token_aud "$h5")" ] && [[ ",$aud_aud," != *",$AUDIENCE,"* ]] && echo 0 || echo 1)" "aud=$aud_aud"
expect "jwt" "public token via the gateway carries iss $ORIGIN/realms/dlap" "$([ "$pub_iss" = "$ORIGIN/realms/dlap" ] && echo 0 || echo 1)" "$pub_iss"
foreign_iss=1
case "$iss_iss" in "https://keycloak:8443/realms/dlap"|"$ORIGIN/realms/dlap"|-) foreign_iss=0 ;; esac
for svc in "${running[@]}"; do
  [[ "$svc" == da-* ]] || continue
  none="$(val "call_${svc}_none" "$h5")"; good="$(val "call_${svc}_good" "$h5")"
  aud="$(val "call_${svc}_aud" "$h5")"; iss="$(val "call_${svc}_iss" "$h5")"
  expect "jwt" "$svc: no token is 401" "$([ "${none%% *}" = 401 ] && echo 0 || echo 1)" "${none%% *}"
  expect "jwt" "$svc: the dlap-backend token is accepted" "$([ -n "${good%% *}" ] && [ "${good%% *}" != 401 ] && [ "${good%% *}" != 000 ] && echo 0 || echo 1)" "HTTP ${good%% *}"
  expect "jwt" "$svc: a token without audience $AUDIENCE is 401 (aud check)" "$([ "${aud%% *}" = 401 ] && grep -qi 'aud' <<<"$aud" && echo 0 || echo 1)" "${aud:0:120}"
  if [ "$foreign_iss" = 1 ]; then
    expect "jwt" "$svc: a token of a foreign issuer is 401 (iss check)" "$([ "${iss%% *}" = 401 ] && grep -qi 'iss' <<<"$iss" && echo 0 || echo 1)" "${iss:0:120}"
  else
    record SKIP "jwt" "$svc: a token of a foreign issuer is 401 (iss check)" "Keycloak fixes the issuer; no foreign-issuer token can be minted here"
  fi
done
expect "jwt" "the public token reads the catalog through the gateway (end to end over TLS)" "$([ "$(val pub_assets "$h5")" = 200 ] && echo 0 || echo 1)" "HTTP $(val pub_assets "$h5")"

# --- H5b the realm refuses plain HTTP (sslRequired all, Q-514, D31) -----------------------------

{
  echo "set -u"
  cat <<'SH'
adm="$(curl -sS --max-time 20 --cacert /tls/ca.crt -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$KC_ADMIN" --data-urlencode "password=$KC_ADMIN_PASSWORD" https://keycloak:8443/realms/master/protocol/openid-connect/token 2>/dev/null | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
echo "admin_token=$([ -n "$adm" ] && echo yes || echo no)"
echo "realm_ssl=$(curl -sS --max-time 20 --cacert /tls/ca.crt -H "Authorization: Bearer $adm" https://keycloak:8443/admin/realms/dlap 2>/dev/null | sed -n 's/.*"sslRequired":"\([a-z]*\)".*/\1/p')"
SH
} >"$WORK/h5b.sh"
h5b="$(on_net -e KC_ADMIN="$(env_value KEYCLOAK_ADMIN)" -e KC_ADMIN_PASSWORD="$(env_value KEYCLOAK_ADMIN_PASSWORD)" \
  --entrypoint sh "$CURL_IMAGE" -s <"$WORK/h5b.sh" 2>&1)"
realm_ssl="$(val realm_ssl "$h5b")"
if [ "$realm_ssl" = all ]; then
  record PASS "service -> keycloak" "realm dlap has sslRequired all (the admin API reports it)" "$realm_ssl"
elif [ "$(val admin_token "$h5b")" != yes ]; then
  record SKIP "service -> keycloak" "realm dlap has sslRequired all (the admin API reports it)" "no admin sign-in: the temporary admin was replaced; check Realm settings, General, Require SSL"
elif dk inspect -f '{{json .Config.Cmd}} {{json .Args}}' "$(cid_of keycloak)" 2>/dev/null | grep -q 'start-dev'; then
  record WARN "service -> keycloak" "realm dlap has sslRequired all (the admin API reports it)" "$realm_ssl: Keycloak runs start-dev (quickstart realm)"
else
  record FAIL "service -> keycloak" "realm dlap has sslRequired all (the admin API reports it)" "${realm_ssl:-unreadable}: a realm imported before the TLS overlay keeps its setting; change it in the admin console"
fi

# --- H7 the action executors call the policy engine over HTTPS (WIRE-01, Q-602, Q-604, Q-613) -------------
#
# da-autoarchival and da-autodelete re-read an approval at the policy engine before they act. Both default to
# plain HTTP, so a pilot that leaves the URL alone fails closed, and a client that does not trust the pilot CA
# fails with a handshake error. Each executor is made to ask about an approval that does not exist; a 404 from the
# application is an answer that crossed the verified TLS connection, so the executor ends the work as refused with
# "unknown approval", not as "could not be checked". Policy engine side: its GET /approvals request counter grows
# by at least two.
#   da-autoarchival: a PLANNED archive rule is seeded, then a decided-and-executable event is produced on
#                    dlap.approvals.decided as the policy engine's own SCRAM user (the topic ACL, the group ACL).
#   da-autodelete:   a deletion task awaiting approval is seeded; the retire sweeper (one minute) reads it.

if [ "$EXECUTORS" = 1 ]; then
  if is_running da-autoarchival && is_running da-autodelete && is_running da-policyengine; then
    probe="$(date +%s)-$$"
    pe_counter() { # GET /approvals requests the policy engine has served, summed over statuses
      on_net --entrypoint sh "$CURL_IMAGE" -c "curl -sS --max-time 15 --cacert /tls/ca.crt https://da-policyengine:9091/actuator/prometheus 2>/dev/null \
        | grep '^http_server_requests_seconds_count' | grep 'method=\"GET\"' | grep '/approvals/' | awk '{ s += \$NF } END { print s + 0 }'" 2>&1 | tail -n 1
    }
    before="$(pe_counter)"
    {
      echo "set -u"
      echo "probe='$probe'"
      cat <<'SH'
q() { psql -X -A -t -v ON_ERROR_STOP=1 "host=postgresql port=5432 dbname=$1 user=dlap sslmode=verify-full sslrootcert=/tls/ca.crt" -c "$2" 2>&1; }
ar_approval="$(cat /proc/sys/kernel/random/uuid)"; ar_asset="$(cat /proc/sys/kernel/random/uuid)"
ad_approval="$(cat /proc/sys/kernel/random/uuid)"; ad_asset="$(cat /proc/sys/kernel/random/uuid)"
echo "ar_approval=$ar_approval"
q da_autoarchival "INSERT INTO dlap_autoarchival.dlap_archive_rules (rule_id, approval_id, task_id, asset_id, asset_uri, provider, mode, scope_bucket, native_class, after_days, status, mock, created_at, updated_at) VALUES ('smoke-tls-$probe', '$ar_approval', 'smoke-tls-task-$probe', '$ar_asset', 'gs://smoke-tls-not-a-bucket/data', 'GCP', 'NATIVE_RULE', 'smoke-tls-not-a-bucket', 'COLD', 30, 'PLANNED', true, now(), now())" > /dev/null
q da_autodelete "INSERT INTO dlap_autodelete.dlap_deletion_tasks (task_id, task_name, status, submitted_at, triggered_by, requested_by, scope, total_assets_in_scope, assets_processed_successfully, assets_failed_to_process, assets_pending_processing, asset_statuses) VALUES ('smoke-tls-$probe', 'smoke-tls trust probe', 'PENDING_APPROVAL', now(), 'smoke-tls', 'smoke-tls', '{}'::jsonb, 1, 0, 0, 1, '[{\"assetId\":\"$ad_asset\",\"status\":\"AWAITING_APPROVAL\",\"deletionType\":\"SOFT\",\"approvalId\":\"$ad_approval\"}]'::jsonb)" > /dev/null
echo "seeded=yes"
SH
    } >"$WORK/h7a.sh"
    # The seed script prints the archive approval id; the event is produced in a second container (Kafka tools).
    h7a="$(on_net -e PGPASSWORD="$PG_PASSWORD" --entrypoint bash "$PG_IMAGE" -s <"$WORK/h7a.sh" 2>&1)"
    ar_approval="$(val ar_approval "$h7a")"
    if [ "$(val seeded "$h7a")" = yes ] && [ -n "$ar_approval" ]; then
      cat >"$WORK/h7b.sh" <<'SH'
set -u
umask 077
cd /tmp
cat > pe.properties <<EOF
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="da-policyengine" password="$(cat /tls/scram/da-policyengine.password)";
ssl.truststore.type=PEM
ssl.truststore.location=/tls/ca.crt
request.timeout.ms=10000
default.api.timeout.ms=15000
EOF
printf '{"schemaVersion":"v1","eventType":"DECIDED","approvalId":"%s","status":"APPROVED","executable":true,"actionClass":"ARCHIVE"}\n' "$AR_APPROVAL" \
  | timeout 60 kafka-console-producer --bootstrap-server kafka:29092 --producer.config pe.properties --topic dlap.approvals.decided > p.log 2>&1
echo "produce_denied=$(grep -ciE 'AUTHORIZATION_FAILED|AuthorizationException|Not authorized' p.log)"
SH
      h7b="$(on_net --user 0 -e AR_APPROVAL="$ar_approval" --entrypoint bash "$KAFKA_IMAGE" -s <"$WORK/h7b.sh" 2>&1)"
      expect "executors" "the decided event reaches dlap.approvals.decided (da-policyengine's produce ACL)" "$([ "$(val produce_denied "$h7b")" = 0 ] && echo 0 || echo 1)" "$(tail -n 2 <<<"$h7b" | tr '\n' ' ')"
      # Wait for both outcomes: the archive rule at once, the deletion task at the sweeper's next run (about a minute).
      cat >"$WORK/h7c.sh" <<'SH'
set -u
q() { psql -X -A -t -v ON_ERROR_STOP=1 "host=postgresql port=5432 dbname=$1 user=dlap sslmode=verify-full sslrootcert=/tls/ca.crt" -c "$2" 2>&1 | tr -d '\n'; }
i=0
while [ "$i" -lt 40 ]; do
  ar="$(q da_autoarchival "select status || '|' || coalesce(last_error, '') from dlap_autoarchival.dlap_archive_rules where rule_id = 'smoke-tls-$PROBE'")"
  ad="$(q da_autodelete "select coalesce(asset_statuses->0->>'status', '') || '|' || coalesce(asset_statuses->0->>'errorMessage', '') || '|' || status from dlap_autodelete.dlap_deletion_tasks where task_id = 'smoke-tls-$PROBE'")"
  case "$ar" in PLANNED*) a=wait ;; *) a=done ;; esac
  case "$ad" in AWAITING_APPROVAL*) d=wait ;; *) d=done ;; esac
  [ "$a$d" = donedone ] && break
  i=$((i + 1)); sleep 5
done
echo "archive=$ar"
echo "delete=$ad"
# Remove the probe rows; the audit records of the refusals stay (they are chained and immutable).
q da_autoarchival "delete from dlap_autoarchival.dlap_archive_rules where rule_id = 'smoke-tls-$PROBE'" > /dev/null
q da_autodelete "delete from dlap_autodelete.dlap_deletion_tasks where task_id = 'smoke-tls-$PROBE'" > /dev/null
SH
      h7c="$(on_net -e PGPASSWORD="$PG_PASSWORD" -e PROBE="$probe" --entrypoint bash "$PG_IMAGE" -s <"$WORK/h7c.sh" 2>&1)"
      archive="$(val archive "$h7c")"; del="$(val delete "$h7c")"
      expect "executors" "da-autoarchival re-reads the approval at https://da-policyengine:8080 and is told it is unknown (REFUSED, not a failed check)" \
        "$(case "$archive" in REFUSED*"does not know approval"*) echo 0 ;; *) echo 1 ;; esac)" "${archive:0:160}"
      # approval-unknown is the executor's code for a 404 of the policy engine. A wrong URL ends as remote-refused (the
      # TLS port answers plain HTTP with a 400) or stays AWAITING_APPROVAL (no answer), so only the real answer passes.
      expect "executors" "da-autodelete reads the approval at https://da-policyengine:8080 and is told it is unknown (approval-unknown)" \
        "$(case "$del" in REFUSED\|approval-unknown\|*) echo 0 ;; *) echo 1 ;; esac)" "${del:0:160}"
      after="$(pe_counter)"
      expect "executors" "the policy engine served at least two more GET /approvals requests (one per executor)" \
        "$(if [[ "$before" =~ ^[0-9]+$ ]] && [[ "$after" =~ ^[0-9]+$ ]] && [ $((after - before)) -ge 2 ]; then echo 0; else echo 1; fi)" "counter $before -> $after"
    else
      record FAIL "executors" "the probe rows were seeded in the executors' databases" "$(tail -n 3 <<<"$h7a" | tr '\n' ' ' | head -c 200)"
    fi
  else
    record FAIL "executors" "da-autoarchival, da-autodelete and da-policyengine are all running (the actions profile is on)"
  fi
else
  record SKIP "executors" "the executors' approval calls over HTTPS" "run with --executors on a rehearsal (tls-e2e.sh --monitoring does)"
fi

# --- H6 no TLS or authorization errors in the service logs since the pilot started -------------

for svc in "${running[@]}"; do
  [[ "$svc" == da-* ]] || [ "$svc" = gateway ] || continue
  cid="$(cid_of "$svc")"
  since="$(dk inspect -f '{{.State.StartedAt}}' "$cid")"
  hits="$(dk logs --since "$since" "$cid" 2>&1 | grep -cE 'SSLHandshakeException|PKIX path|unable to find valid certification path|SaslAuthenticationException|TopicAuthorizationException|GroupAuthorizationException|ClusterAuthorizationException|upstream SSL certificate verify error|SSL_do_handshake\(\) failed' || true)"
  expect "logs" "$svc logged no TLS or Kafka authorization error since it started" "$([ "$hits" = 0 ] && echo 0 || echo 1)" "$hits lines"
done

summary
