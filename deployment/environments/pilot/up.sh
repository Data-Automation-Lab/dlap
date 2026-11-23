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
# DLAP read-only pilot launcher (DEP-01, ADR-0049 model M3).
#
# Starts the quickstart compose pair with every docker-compose.pilot.*.yml of this folder applied on
# top, in sorted order (base, then whatever the later packages add: keycloak, shadow, tls, ...).
# Before it starts anything it checks the environment file and refuses to run with the secrets
# or the seeded dev realm that the quickstart ships.
#
# Usage:
#   up.sh [options] [docker compose arguments]
#
# Options:
#   --env-file FILE   environment file (default: .env.pilot next to this script, or $PILOT_ENV_FILE)
#   --check           run the checks and `docker compose config -q`, start nothing
#   --print-args      print the docker compose arguments, one per line, and exit (no checks)
#   -h, --help        this text
#
# With no compose arguments the command is `up -d --build`. Anything else is passed to docker compose
# unchanged, with the same files and environment, for example:  up.sh ps    up.sh logs -f da-catalog
# up.sh down  (stops the pilot and keeps its volumes; add -v to delete the data).
#
# Database upgrade (ADR-0056, Q-142): when the command is `up` (also the default), up.sh first starts
# PostgreSQL alone, waits until it accepts TCP connections (on a new volume that is after the init
# scripts finished), and runs deployment/platform/database/upgrades/run-upgrades.sh inside it. The
# runner applies every dlap_common delta the database has not had and records it in
# dlap_common.schema_upgrades. Only then does compose start the services. If the runner fails (exit 1)
# or refuses (exit 2: a delta was edited after it was applied, or one is out of order), up.sh stops
# with that status and no service is started. See docs/guides/upgrade.md, "When the runner refuses".
# Other commands (ps, logs, down, ...) never touch the database.
#
# Checks that depend on an overlay (each runs only when that overlay file is in the folder):
#   keycloak      KEYCLOAK_DB_PASSWORD is set and is its own secret (Q-541). Without the overlay the
#                 quickstart realm would be imported, so PILOT_ALLOW_DEV_REALM=1 is required (lab VM only).
#   tls           the output of tls/gen-certs.sh exists (PILOT_TLS_DIR), PILOT_PUBLIC_ORIGIN is https://,
#                 and, with the keycloak overlay, DLAP_PILOT_ID_URL and DLAP_PILOT_APP_URL equal it, so
#                 every token carries the public issuer (Q-517).
#   identity-map  PILOT_IDENTITY_MAP_FILE, when set, is a readable file that starts with the seed header
#                 (Q-487).
#
# Database roles (SEC-12, Q-176, Q-449, Q-455): every one of the eleven service roles and the two platform
# roles (dlap_monitor, dlap_backup) needs its own real password in the environment file, and POSTGRES_PASSWORD
# a real one too. The check refuses a placeholder, a published default and a value used twice. After the
# dlap_common upgrade, the launcher runs deployment/platform/database/upgrades/sync-database-roles.sh in the
# database container, which sets the passwords of a PostgreSQL volume from before SEC-12 and creates its two
# platform roles. Write the values with deployment/platform/database/gen-db-passwords.sh .env.pilot.
#
# Environment:
#   PILOT_ENV_FILE        same as --env-file
#   COMPOSE_PROFILES      optional profiles: actions (archive and delete), llm (Ollama), monitoring
#   COMPOSE_PROJECT_NAME  compose project (default dlap-pilot)
#   DLAP_PILOT_DIR        folder holding the overlay files (default: this script's folder; for tests)
#   PILOT_DB_WAIT_SECONDS how long to wait for PostgreSQL before the upgrade (default 180)
#
# Exit status: 0 success; 2 a check failed, the arguments are wrong, or the upgrade runner refused;
# 1 PostgreSQL did not come up or the upgrade runner failed; otherwise docker compose's.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PILOT_DIR="${DLAP_PILOT_DIR:-$SCRIPT_DIR}"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DEV_DIR="$REPO_ROOT/deployment/environments/dev"
REALM_FILE="$REPO_ROOT/deployment/platform/keycloak/keycloak-import/dlap-realm.json"
MIN_COMPOSE="2.24.0"
PROJECT_NAME="dlap-pilot"
UPGRADE_RUNNER="$REPO_ROOT/deployment/platform/database/upgrades/run-upgrades.sh"
# SEC-12: the role synchronisation that follows the upgrade, and the tool that names the password variables.
ROLE_SYNC="$REPO_ROOT/deployment/platform/database/upgrades/sync-database-roles.sh"
DB_PASSWORDS_TOOL="$REPO_ROOT/deployment/platform/database/gen-db-passwords.sh"
# The database service, its owner role and the shared database, as the quickstart compose defines them
# (docker-compose.yml: service postgresql, POSTGRES_USER=dlap, dlap_common in POSTGRES_MULTIPLE_DATABASES).
DB_SERVICE="postgresql"
DB_ADMIN_USER="dlap"
DB_COMMON="dlap_common"

ENV_FILE="${PILOT_ENV_FILE:-$PILOT_DIR/.env.pilot}"
MODE="run"

die() { echo "up.sh: $*" >&2; exit 2; }

usage() {
  awk '/^# DLAP read-only pilot launcher/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"
}

# --- Arguments ---------------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || die "--env-file needs a file name"; ENV_FILE="$2"; shift 2 ;;
    --check) MODE="check"; shift ;;
    --print-args) MODE="print"; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    *) break ;;
  esac
done

# --- Overlays, in sorted order -----------------------------------------------------------------

# One path per line, byte-order sorted, so the order never depends on the locale.
overlays() {
  find "$PILOT_DIR" -maxdepth 1 -type f -name 'docker-compose.pilot.*.yml' | LC_ALL=C sort
}

compose_args() {
  printf '%s\n' --project-name "${COMPOSE_PROJECT_NAME:-$PROJECT_NAME}" --env-file "$ENV_FILE" \
    -f "$DEV_DIR/docker-compose.yml" -f "$DEV_DIR/docker-compose.microservices.yml"
  local overlay
  while IFS= read -r overlay; do
    printf '%s\n' -f "$overlay"
  done < <(overlays)
}

if [ "$MODE" = "print" ]; then
  compose_args
  exit 0
fi

# --- Environment file --------------------------------------------------------------------------

[ -f "$ENV_FILE" ] || die "environment file not found: $ENV_FILE
  Create it from the example and fill it in:  cp \"$PILOT_DIR/.env.pilot.example\" \"$PILOT_DIR/.env.pilot\""

# Last assignment of KEY in the env file, without evaluating anything. Surrounding quotes are removed.
env_value() {
  local key="$1" line value
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  case "$value" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac
  printf '%s' "$value"
}

# --- Checks ------------------------------------------------------------------------------------

problems=()
problem() { problems+=("$1"); }

# True when dotted version $1 is at least $2 (numeric fields only; a suffix such as -desktop.1 is ignored).
version_ge() {
  local IFS=. i a b
  local -a have=(${1%%-*}) want=(${2%%-*})
  for i in 0 1 2; do
    a="${have[$i]:-0}"; b="${want[$i]:-0}"
    if [ "$a" -gt "$b" ]; then return 0; fi
    if [ "$a" -lt "$b" ]; then return 1; fi
  done
  return 0
}

check_tooling() {
  command -v docker >/dev/null 2>&1 || { problem "docker is not installed or not on PATH"; return; }
  local version
  version="$(docker compose version --short 2>/dev/null || true)"
  if [ -z "$version" ]; then
    problem "the Docker Compose plugin is missing (docker compose version fails)"
  elif ! version_ge "${version#v}" "$MIN_COMPOSE"; then
    problem "Docker Compose $version is older than $MIN_COMPOSE, which the pilot overlay needs for the !override tag"
  fi
}

# A secret must be set, must not be a placeholder from the example file, and must not be the
# quickstart's published default for that variable.
check_secret() {
  local key="$1" weak="$2" value
  value="$(env_value "$key")"
  if [ -z "$value" ]; then
    problem "$key is not set in $ENV_FILE"
  elif [[ "$value" == CHANGE_ME* ]]; then
    problem "$key still has the placeholder from .env.pilot.example"
  elif [ -n "$weak" ] && [[ " $weak " == *" $value "* ]]; then
    problem "$key is a published quickstart default; choose your own"
  fi
}

# A setting as docker compose sees it: the caller's environment wins over the environment file.
setting() {
  local key="$1"
  if [ -n "${!key:-}" ]; then printf '%s' "${!key}"; else env_value "$key"; fi
}

# A path setting as the compose files resolve it: relative to deployment/environments/dev.
resolve_path() {
  case "$1" in
    /*|[A-Za-z]:*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$DEV_DIR" "$1" ;;
  esac
}

# The TLS overlay (SEC-06) needs the key material of tls/gen-certs.sh before compose runs. Without it
# compose creates an empty bind source for tls/generated and tls-init fails late (Q-517). It also needs
# one issuer: the services check tokens against PILOT_PUBLIC_ORIGIN, which Keycloak writes into them
# only if DLAP_PILOT_ID_URL is that origin.
check_tls() {
  local overlay_list="$1" origin dir f id app missing=""
  origin="$(setting PILOT_PUBLIC_ORIGIN)"
  origin="${origin:-https://localhost}"
  origin="${origin%/}"
  if [[ "$origin" != https://* ]]; then
    problem "PILOT_PUBLIC_ORIGIN must start with https:// because the TLS overlay serves HTTPS only (now '$origin')"
  fi
  if grep -q 'docker-compose\.pilot\.keycloak' <<<"$overlay_list"; then
    id="$(setting DLAP_PILOT_ID_URL)"
    app="$(setting DLAP_PILOT_APP_URL)"
    if [ "${id%/}" != "$origin" ]; then
      problem "DLAP_PILOT_ID_URL must equal PILOT_PUBLIC_ORIGIN ($origin) with the TLS overlay, so every token carries the public issuer (now '${id:-unset}')"
    fi
    if [ "${app%/}" != "$origin" ]; then
      problem "DLAP_PILOT_APP_URL must equal PILOT_PUBLIC_ORIGIN ($origin) with the TLS overlay, so the sign-in redirect URIs match (now '${app:-unset}')"
    fi
  fi
  dir="$(setting PILOT_TLS_DIR)"
  if [ -z "$dir" ]; then dir="$PILOT_DIR/tls/generated"; else dir="$(resolve_path "$dir")"; fi
  for f in certs/ca.crt certs/jvm-truststore.p12 certs/gateway.crt certs/keycloak.crt certs/postgresql.crt; do
    [ -s "$dir/$f" ] || { missing="$f"; break; }
  done
  if [ -n "$missing" ]; then
    problem "no TLS key material: $dir/$missing is missing. Generate it with $PILOT_DIR/tls/gen-certs.sh --public-host <name the browsers use>, or point PILOT_TLS_DIR at the folder it wrote"
  elif [ ! -d "$dir/scram" ]; then
    problem "no SCRAM passwords: $dir/scram is missing. Run $PILOT_DIR/tls/gen-certs.sh again"
  fi
}

# The identity map seed file (Q-487): a readable file whose first line that is not a comment is the header.
check_identity_map() {
  local file first
  file="$(setting PILOT_IDENTITY_MAP_FILE)"
  [ -n "$file" ] || return 0
  file="$(resolve_path "$file")"
  if [ ! -f "$file" ] || [ ! -r "$file" ]; then
    problem "PILOT_IDENTITY_MAP_FILE is not a readable file: $file (a missing path would make Docker create a directory there)"
    return 0
  fi
  first="$(tr -d '\r' <"$file" | sed '1s/^\xEF\xBB\xBF//' | grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' | head -n 1 | tr -d ' ' | tr 'A-Z' 'a-z' || true)"
  if [ "$first" != "provider,principal,subject" ]; then
    problem "PILOT_IDENTITY_MAP_FILE $file must start with the header provider,principal,subject (see deployment/examples/identity-map/)"
  fi
}

# SEC-12: the passwords of the database roles. Each of them must be set, must not be a placeholder, must not
# be a development default of the quickstart (<database>_pass, dlap_monitor_pass, dlap), and must differ from
# every other role's password and from POSTGRES_PASSWORD, so that one leak opens one role.
check_database_passwords() {
  [ -f "$DB_PASSWORDS_TOOL" ] || return 0
  local key value seen
  seen=" $(env_value POSTGRES_PASSWORD) "
  while IFS= read -r key; do
    check_secret "$key" ""
    value="$(env_value "$key")"
    if [ -z "$value" ] || [[ "$value" == CHANGE_ME* ]]; then continue; fi
    if [[ "$value" =~ ^(da_[a-z]+|dlap_common|dlap_monitor|dlap_backup)_pass$ ]] || [ "$value" = "dlap" ]; then
      problem "$key is a published quickstart default; choose your own"
    elif [[ "$seen" == *" $value "* ]]; then
      problem "$key has the same value as another database password; each role needs its own"
    fi
    seen="$seen$value "
  done < <(bash "$DB_PASSWORDS_TOOL" --list)
}

check_environment() {
  check_secret POSTGRES_PASSWORD "dlap"
  check_database_passwords
  check_secret KEYCLOAK_ADMIN_PASSWORD "admin changeme"
  check_secret DLAP_BACKEND_CLIENT_SECRET ""
  check_secret DLAP_MCP_GATEWAY_CLIENT_SECRET ""
  check_secret GCP_PROJECT_ID "dlap-demo-project"

  local key
  key="$(env_value DLAP_ENCRYPTION_KEY)"
  if [ -z "$key" ] || [[ "$key" == CHANGE_ME* ]]; then
    problem "DLAP_ENCRYPTION_KEY is not set (generate one: openssl rand -hex 32)"
  elif ! [[ "$key" =~ ^[0-9a-fA-F]{64}$ ]]; then
    problem "DLAP_ENCRYPTION_KEY must be 64 hex characters, which decode to 32 bytes (openssl rand -hex 32)"
  fi

  # The quickstart realm's default for the backend client secret is written in the realm file itself.
  local realm_default secret
  realm_default="$(grep -o 'DLAP_BACKEND_CLIENT_SECRET:[^}]*' "$REALM_FILE" 2>/dev/null | head -n 1 | cut -d: -f2- || true)"
  secret="$(env_value DLAP_BACKEND_CLIENT_SECRET)"
  if [ -n "$realm_default" ] && [ "$secret" = "$realm_default" ]; then
    problem "DLAP_BACKEND_CLIENT_SECRET is the quickstart realm's published default; choose your own"
  fi
  # The same for the dlap-mcp-gateway client that the realm imports (PEP-02, Q-458).
  realm_default="$(grep -o 'DLAP_MCP_GATEWAY_CLIENT_SECRET:[^}]*' "$REALM_FILE" 2>/dev/null | head -n 1 | cut -d: -f2- || true)"
  secret="$(env_value DLAP_MCP_GATEWAY_CLIENT_SECRET)"
  if [ -n "$realm_default" ] && [ "$secret" = "$realm_default" ]; then
    problem "DLAP_MCP_GATEWAY_CLIENT_SECRET is the quickstart realm's published default; choose your own"
  fi

  # The production Keycloak overlay (IAM-02) ships in this folder, so the pilot does not import the
  # quickstart realm. If that overlay file is removed, the quickstart realm would be imported: five seeded
  # users with a published password. That is fine for a lab VM and not for a customer's data, so it stays
  # opt-in. The variable is not in .env.pilot.example any more (Q-510).
  local overlay_list
  overlay_list="$(overlays)"
  if grep -q 'docker-compose\.pilot\.keycloak' <<<"$overlay_list"; then
    # Its own secret (Q-541). "keycloak" is the PostgreSQL init script's fallback, so it counts as published.
    check_secret KEYCLOAK_DB_PASSWORD "keycloak dlap"
  else
    if [ "$(env_value PILOT_ALLOW_DEV_REALM)" != "1" ] && [ "${PILOT_ALLOW_DEV_REALM:-0}" != "1" ]; then
      problem "no production Keycloak overlay (docker-compose.pilot.keycloak*.yml) is present, so the pilot would import
    the quickstart realm: five seeded users with a published password. Set PILOT_ALLOW_DEV_REALM=1 in $ENV_FILE
    to accept that on a lab or rehearsal VM. Do not do it with customer data."
    fi
  fi
  if grep -q 'docker-compose\.pilot\.tls' <<<"$overlay_list"; then
    check_tls "$overlay_list"
  fi
  if grep -q 'docker-compose\.pilot\.identity-map' <<<"$overlay_list"; then
    check_identity_map
  fi

  if [[ ",${COMPOSE_PROFILES:-$(env_value COMPOSE_PROFILES)}," == *,monitoring,* ]]; then
    check_secret GRAFANA_ADMIN_PASSWORD "admin"
  fi

  local db_wait="${PILOT_DB_WAIT_SECONDS:-180}"
  [[ "$db_wait" =~ ^[0-9]+$ ]] || problem "PILOT_DB_WAIT_SECONDS must be a whole number of seconds, not '$db_wait'"
}

# The Dockerfiles copy build/libs/<service>.jar, and the UI is a submodule.
check_artifacts() {
  local profiles=",${COMPOSE_PROFILES:-$(env_value COMPOSE_PROFILES)}," svc missing=()
  for svc in da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autocompliance da-admin-service da-logingest; do
    [ -f "$REPO_ROOT/$svc/build/libs/$svc.jar" ] || missing+=("$svc")
  done
  if [[ "$profiles" == *,actions,* ]]; then
    for svc in da-autoarchival da-autodelete; do
      [ -f "$REPO_ROOT/$svc/build/libs/$svc.jar" ] || missing+=("$svc")
    done
  fi
  if [ "${#missing[@]}" -gt 0 ]; then
    problem "no jar for: ${missing[*]}. Build them first from the repository root (JDK 21): ./gradlew build -x test"
  fi
  [ -f "$REPO_ROOT/new-ui/src/main/docker/Dockerfile" ] \
    || problem "the new-ui submodule is not checked out. Run: git submodule update --init new-ui"
  [ -f "$UPGRADE_RUNNER" ] \
    || problem "the database upgrade runner is missing: $UPGRADE_RUNNER"
  [ -f "$ROLE_SYNC" ] \
    || problem "the database role synchronisation is missing: $ROLE_SYNC"
  [ -f "$DB_PASSWORDS_TOOL" ] \
    || problem "the database password tool is missing: $DB_PASSWORDS_TOOL"
}

check_tooling
check_environment
check_artifacts

if [ "${#problems[@]}" -gt 0 ]; then
  {
    echo "up.sh: the pilot will not start. ${#problems[@]} problem(s):"
    for p in "${problems[@]}"; do echo "  - $p"; done
  } >&2
  exit 2
fi

ARGS=()
while IFS= read -r arg; do ARGS+=("$arg"); done < <(compose_args)

if [[ ",${COMPOSE_PROFILES:-$(env_value COMPOSE_PROFILES)}," == *,actions,* ]]; then
  echo "up.sh: the actions profile is on. da-autoarchival and da-autodelete will run; deletes stay dry runs and archival stays mocked." >&2
fi

if [ "$MODE" = "check" ]; then
  docker compose "${ARGS[@]}" config -q
  echo "up.sh: checks passed; compose files: $(printf '%s\n' "${ARGS[@]}" | grep -c '^-f$')"
  exit 0
fi

if [ $# -eq 0 ]; then
  set -- up -d --build
fi

# Compose reads COMPOSE_PROFILES from the process environment, not from --env-file.
if [ -z "${COMPOSE_PROFILES:-}" ] && [ -n "$(env_value COMPOSE_PROFILES)" ]; then
  export COMPOSE_PROFILES="$(env_value COMPOSE_PROFILES)"
fi

# --- Database upgrade (ADR-0056, Q-142) ----------------------------------------------------------

# Waits until PostgreSQL in container $1 accepts TCP connections, for PILOT_DB_WAIT_SECONDS at most.
# On a new volume the entrypoint runs the init scripts on a temporary server that listens on the
# socket only, and the container healthcheck (pg_isready on the socket) already passes then. A TCP
# listener exists only after the init scripts have finished, so a TCP probe is the safe signal.
wait_for_database() {
  local cid="$1" limit="${PILOT_DB_WAIT_SECONDS:-180}" waited=0
  until docker exec "$cid" pg_isready -q -h 127.0.0.1 -U "$DB_ADMIN_USER" -d "$DB_COMMON" >/dev/null 2>&1; do
    waited=$((waited + 1))
    if [ "$waited" -gt "$limit" ]; then return 1; fi
    sleep 1
  done
}

# Starts PostgreSQL alone and brings dlap_common forward with the ledgered runner. The runner reaches
# the database through `docker exec` into the container, so the host needs no psql. Its connection is
# fixed here, so the PG* variables of the caller's shell cannot point it elsewhere.
upgrade_database() {
  local cid
  echo "up.sh: starting PostgreSQL to apply the dlap_common upgrades before any service" >&2
  docker compose "${ARGS[@]}" up -d "$DB_SERVICE" || return $?
  cid="$(docker compose "${ARGS[@]}" ps -q "$DB_SERVICE" | head -n 1)" || true
  if [ -z "$cid" ]; then
    echo "up.sh: the $DB_SERVICE container is not running; see: ./up.sh logs $DB_SERVICE" >&2
    return 1
  fi
  if ! wait_for_database "$cid"; then
    echo "up.sh: PostgreSQL did not accept connections within ${PILOT_DB_WAIT_SECONDS:-180} seconds; see: ./up.sh logs $DB_SERVICE" >&2
    return 1
  fi
  DLAP_COMMON_DB_URL="$DB_COMMON" DLAP_PSQL="docker exec -i $cid psql -U $DB_ADMIN_USER" bash "$UPGRADE_RUNNER" || return $?
  sync_database_roles "$cid"
}

# SEC-12: sets the passwords of the database roles and creates dlap_monitor and dlap_backup on a volume that
# predates the init script's variables. The script runs inside the container, where compose has put the
# *_DB_PASSWORD variables, so no password crosses the docker CLI. On a new volume the init script has just
# done the same and this changes nothing.
sync_database_roles() {
  echo "up.sh: synchronising the database roles with the environment" >&2
  docker exec -i "$1" bash -s -- --quiet < "$ROLE_SYNC" ||
    { echo "up.sh: the database role synchronisation failed (see above)" >&2; return 1; }
}

# Only `up` starts services, so only `up` upgrades. A failure stops here: no service starts on a
# schema the runner did not bring forward.
if [ "$1" = "up" ]; then
  upgrade_status=0
  upgrade_database || upgrade_status=$?
  if [ "$upgrade_status" -ne 0 ]; then
    echo "up.sh: the dlap_common upgrade failed (exit $upgrade_status); no service was started. See docs/guides/upgrade.md, \"When the runner refuses\"." >&2
    exit "$upgrade_status"
  fi
fi

status=0
docker compose "${ARGS[@]}" "$@" || status=$?

if [ "$status" -eq 0 ] && [ "$1" = "up" ]; then
  if grep -q 'docker-compose\.pilot\.tls' <<<"$(overlays)"; then
    # The entry point is the gateway's HTTPS port (Q-517). PILOT_PUBLIC_ORIGIN is the address browsers use.
    origin="$(setting PILOT_PUBLIC_ORIGIN)"
    origin="${origin:-https://localhost}"
    origin="${origin%/}"
    bind="$(setting PILOT_TLS_BIND_ADDRESS)"
    bind="${bind:-127.0.0.1}"
    tls_port="$(setting PILOT_TLS_PORT)"
    tls_port="${tls_port:-443}"
    origin_port="${origin##*:}"
    [[ "$origin_port" =~ ^[0-9]+$ ]] || origin_port=443
    if [ "$bind" = "127.0.0.1" ]; then
      reach="The entry point is HTTPS on 127.0.0.1:$tls_port of this VM. From your workstation:

  ssh -L $origin_port:127.0.0.1:$tls_port <vm>        then open $origin

Browsers must use exactly $origin (the sign-in issuer is fixed to it). A name other than localhost needs
that name in the certificate (tls/gen-certs.sh --public-host) and in your hosts file for the tunnel."
    else
      reach="The entry point is HTTPS on $bind:$tls_port of this VM. Open $origin."
    fi
    printf '\nThe pilot is starting. Every service port other than the entry point is bound to 127.0.0.1.\n%s\n' "$reach"
  else
    cat <<'EOF'

The pilot is starting. Every port is bound to 127.0.0.1 on this VM. From your workstation:

  ssh -L 8081:127.0.0.1:8081 <vm>        then open http://localhost:8081
EOF
  fi
  cat <<'EOF'

Watch it come up:      ./up.sh ps
Follow one service:    ./up.sh logs -f da-catalog
Stop it, keep data:    ./up.sh down
EOF
fi
exit "$status"
