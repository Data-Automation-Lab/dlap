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
# DLAP hosted-demo launcher (DEMO-01).
#
# Starts the quickstart compose pair with docker-compose.demo.yml on top (and the Keycloak overlay of
# DEMO-02 when it exists). Before it starts anything it checks the environment file and refuses to
# run with the secrets or the seeded dev realm that the quickstart ships.
#
# Usage:
#   up.sh [options] [docker compose arguments]
#
# Options:
#   --env-file FILE   environment file (default: .env.demo next to this script, or $DEMO_ENV_FILE)
#   --check           run the checks and `docker compose config -q`, start nothing
#   --print-args      print the docker compose arguments, one per line, and exit (no checks)
#   --no-build        with the default command, do not build images (they must exist already)
#   -h, --help        this text
#
# With no compose arguments the command is `up -d --build --wait`: the services start in two waves
# (depends_on in the overlay) and the command returns when every one is healthy. Anything else is
# passed to docker compose unchanged, with the same files and environment, for example:
#   up.sh ps     up.sh logs -f da-catalog     up.sh down   (keeps the volumes; add -v to delete the data)
#
# Environment: DEMO_ENV_FILE, COMPOSE_PROFILES (plus), COMPOSE_PROJECT_NAME (default dlap-demo),
# DEMO_EXTRA_COMPOSE (more compose files, space separated), DEMO_EVICT_COLLIDING=1 (development hosts
# only, see scripts/lib.sh).
#
# Exit status: 0 success; 2 a check failed or the arguments are wrong; otherwise docker compose's.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/scripts/lib.sh"
MIN_COMPOSE="2.24.0"

MODE="run"
BUILD="--build"

die() { echo "up.sh: $*" >&2; exit 2; }

usage() {
  awk '/^# DLAP hosted-demo launcher/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || die "--env-file needs a file name"; DEMO_ENV_FILE="$2"; shift 2 ;;
    --check) MODE="check"; shift ;;
    --print-args) MODE="print"; shift ;;
    --no-build) BUILD=""; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    *) break ;;
  esac
done

demo_init "$DEMO_ENV_FILE"

if [ "$MODE" = "print" ]; then
  printf '%s\n' "${DEMO_ARGS[@]}"
  exit 0
fi

[ -f "$DEMO_ENV_FILE" ] || die "environment file not found: $DEMO_ENV_FILE
  Create it from the example and fill it in:  cp \"$DEMO_DIR/.env.demo.example\" \"$DEMO_DIR/.env.demo\""

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
    problem "Docker Compose $version is older than $MIN_COMPOSE, which the demo overlay needs for the !override and !reset tags"
  fi
}

# A secret must be set, must not be a placeholder from the example file, and must not be the
# quickstart's published default for that variable.
check_secret() { # check_secret <KEY> <space separated weak values>
  local key="$1" weak="$2" value
  value="$(demo_env_value "$key")"
  if [ -z "$value" ]; then
    problem "$key is not set in $DEMO_ENV_FILE"
  elif [[ "$value" == CHANGE_ME* ]]; then
    problem "$key still has the placeholder from .env.demo.example"
  elif [ -n "$weak" ] && [[ " $weak " == *" $value "* ]]; then
    problem "$key is a published quickstart default; choose your own"
  fi
}

# An optional secret: unset is fine (the Keycloak overlay then uses KEYCLOAK_ADMIN_PASSWORD, which
# check_secret has already vetted), but a value that is set must not be a placeholder or a weak default.
check_optional_secret() { # check_optional_secret <KEY> <space separated weak values>
  local key="$1" weak="$2" value
  value="$(demo_env_value "$key")"
  if [ -z "$value" ]; then
    return 0
  elif [[ "$value" == CHANGE_ME* ]]; then
    problem "$key still has the placeholder from .env.demo.example; set a secret or remove the line"
  elif [ -n "$weak" ] && [[ " $weak " == *" $value "* ]]; then
    problem "$key is a published quickstart default; choose your own or remove the line"
  fi
}

# SEC-12: the passwords of the database roles. Each must be set, must not be a placeholder, must not be a
# development default of the quickstart (<database>_pass, dlap_monitor_pass, dlap) and must differ from every
# other role's password and from POSTGRES_PASSWORD, so that one leak opens one role.
check_database_passwords() {
  local tool="$DEMO_REPO_ROOT/deployment/platform/database/gen-db-passwords.sh" key value seen
  if [ ! -f "$tool" ]; then problem "the database password tool is missing: $tool"; return 0; fi
  seen=" $(demo_env_value POSTGRES_PASSWORD) "
  while IFS= read -r key; do
    check_secret "$key" ""
    value="$(demo_env_value "$key")"
    if [ -z "$value" ] || [[ "$value" == CHANGE_ME* ]]; then continue; fi
    if [[ "$value" =~ ^(da_[a-z]+|dlap_common|dlap_monitor|dlap_backup)_pass$ ]] || [ "$value" = "dlap" ]; then
      problem "$key is a published quickstart default; choose your own"
    elif [[ "$seen" == *" $value "* ]]; then
      problem "$key has the same value as another database password; each role needs its own"
    fi
    seen="$seen$value "
  done < <(bash "$tool" --list)
}

check_environment() {
  check_secret POSTGRES_PASSWORD "dlap"
  check_database_passwords
  check_secret KEYCLOAK_ADMIN_PASSWORD "admin changeme"
  check_secret DLAP_BACKEND_CLIENT_SECRET "change-me-dev-only"
  check_optional_secret KEYCLOAK_DB_PASSWORD "dlap keycloak admin changeme"
  # The dev realm (dlap-realm.json) imports a dlap-mcp-gateway client whose secret defaults to a published
  # value (PEP-02, Q-458). The demo realm of DEMO-02 has no such client, so the variable is optional here;
  # a value that is set must be a real secret, because the dev realm would use it.
  check_optional_secret DLAP_MCP_GATEWAY_CLIENT_SECRET "change-me-dev-only"

  # The quickstart realm's default for the backend client secret is written in the realm file itself.
  local realm_file="$DEMO_REPO_ROOT/deployment/platform/keycloak/keycloak-import/dlap-realm.json"
  local realm_default secret
  realm_default="$(grep -o 'DLAP_BACKEND_CLIENT_SECRET:[^}]*' "$realm_file" 2>/dev/null | head -n 1 | cut -d: -f2- || true)"
  secret="$(demo_env_value DLAP_BACKEND_CLIENT_SECRET)"
  if [ -n "$realm_default" ] && [ "$secret" = "$realm_default" ]; then
    problem "DLAP_BACKEND_CLIENT_SECRET is the quickstart realm's published default; choose your own"
  fi
  # If the realm file ever publishes another default, a value equal to it is refused as well.
  local gateway_default gateway
  gateway_default="$(grep -o 'DLAP_MCP_GATEWAY_CLIENT_SECRET:[^}]*' "$realm_file" 2>/dev/null | head -n 1 | cut -d: -f2- || true)"
  gateway="$(demo_env_value DLAP_MCP_GATEWAY_CLIENT_SECRET)"
  if [ -n "$gateway_default" ] && [ "$gateway" = "$gateway_default" ] && [ "$gateway_default" != "change-me-dev-only" ]; then
    problem "DLAP_MCP_GATEWAY_CLIENT_SECRET is the quickstart realm's published default; choose your own"
  fi

  local app_host id_host
  app_host="$(demo_cfg DEMO_APP_HOST "")"
  id_host="$(demo_cfg DEMO_ID_HOST "")"
  if [ -z "$app_host" ] || [[ "$app_host" == *CHANGE_ME* ]]; then problem "DEMO_APP_HOST is not set to a real host name"; fi
  if [ -z "$id_host" ] || [[ "$id_host" == *CHANGE_ME* ]]; then problem "DEMO_ID_HOST is not set to a real host name"; fi
  # DEMO_FORBIDDEN_HOSTS: a comma-separated list of domains the demo must never be served from (for
  # example a production site on the same hosting account). Any host name containing one is refused.
  local forbidden
  for forbidden in $(demo_cfg DEMO_FORBIDDEN_HOSTS "" | tr ',' ' '); do
    if [[ "$app_host $id_host" == *"$forbidden"* ]]; then problem "the demo must not use $forbidden (DEMO_FORBIDDEN_HOSTS); use a DLAP-owned domain"; fi
  done

  # The seed replay is for seed.sh only. A value in the file would replay on every start.
  case "$(demo_env_value DEMO_REPLAY_ENABLED)" in
    true|TRUE|1) problem "DEMO_REPLAY_ENABLED is set in $DEMO_ENV_FILE; seed.sh sets it for its own run. Remove it." ;;
  esac

  # Without the Keycloak overlay of DEMO-02 (keycloak/docker-compose.demo.keycloak.yml, present in this
  # repository) the demo would import the quickstart realm, which seeds users with a published password.
  # Fine on a workstation, not on a public host. With the overlay the setting is not asked for.
  if [ ! -f "$DEMO_DIR/keycloak/docker-compose.demo.keycloak.yml" ]; then
    if [ "$(demo_cfg DEMO_ALLOW_DEV_REALM 0)" != "1" ]; then
      problem "no demo Keycloak overlay (keycloak/docker-compose.demo.keycloak.yml, DEMO-02) is present, so the demo would
    import the quickstart realm: seeded users with a published password. Set DEMO_ALLOW_DEV_REALM=1 in $DEMO_ENV_FILE
    to accept that for a rehearsal on your own machine. Never on a host with a public address."
    fi
  fi

  if [[ ",$(demo_cfg COMPOSE_PROFILES ""), " == *,plus,* ]]; then
    local key
    key="$(demo_env_value DLAP_ENCRYPTION_KEY)"
    if [ -z "$key" ] || [[ "$key" == CHANGE_ME* ]]; then
      problem "DLAP_ENCRYPTION_KEY is not set (the plus profile runs da-admin-service; openssl rand -hex 32)"
    elif ! [[ "$key" =~ ^[0-9a-fA-F]{64}$ ]]; then
      problem "DLAP_ENCRYPTION_KEY must be 64 hex characters, which decode to 32 bytes (openssl rand -hex 32)"
    fi
  fi
}

# The Dockerfiles copy build/libs/<service>.jar. The SPA is built by scripts/build-spa.sh.
check_artifacts() {
  local svc missing=()
  for svc in $(demo_jvm_services); do
    if [ ! -f "$DEMO_REPO_ROOT/$svc/build/libs/$svc.jar" ]; then missing+=("$svc"); fi
  done
  if [ "${#missing[@]}" -gt 0 ] && [ -n "$BUILD" ]; then
    problem "no jar for: ${missing[*]}. Build them first from the repository root (JDK 21): ./gradlew bootJar
    (or only these: ./gradlew ${missing[*]/#/:}:bootJar). Do not build on the demo host: copy the jars."
  fi
  local spa
  spa="$(demo_cfg DEMO_SPA_DIR "$DEMO_DIR/spa")"
  case "$spa" in
    /*|[A-Za-z]:*) ;;
    *) spa="$DEMO_DEV_DIR/$spa" ;;
  esac
  [ -f "$spa/index.html" ] || problem "no built SPA in $spa. Run scripts/build-spa.sh"
}

if [ "$MODE" = "check" ] || [ $# -eq 0 ] || [ "$1" = "up" ]; then
  check_tooling
  check_environment
  check_artifacts
fi

if [ "${#problems[@]}" -gt 0 ]; then
  {
    echo "up.sh: the demo will not start. ${#problems[@]} problem(s):"
    for p in "${problems[@]}"; do echo "  - $p"; done
  } >&2
  exit 2
fi

# Folders the containers mount. Created here so Docker does not create them as root.
STATE="$(demo_state_dir)"
mkdir -p "$STATE/flags" "$STATE/golden"

if [ "$MODE" = "check" ]; then
  demo_dc config -q
  echo "up.sh: checks passed; compose files: $(demo_compose_files | wc -l | tr -d ' ')"
  exit 0
fi

demo_harness_prepare

if [ $# -eq 0 ]; then
  set -- up -d $BUILD --wait --wait-timeout 1200
fi

status=0
demo_dc "$@" || status=$?

if [ "$status" -eq 0 ] && [ "$1" = "up" ]; then
  cat <<EOF

The demo is up.
  App:        https://$(demo_cfg DEMO_APP_HOST "")
  Identity:   https://$(demo_cfg DEMO_ID_HOST "")
  Gateway (this host only): http://127.0.0.1:$(demo_cfg DLAP_PORT_GATEWAY 8081)

Seed it once (golden data):  scripts/seed.sh
Reset every night:           scripts/nightly-reset.sh   (cron line in README.md)
Measure:                     scripts/measure.sh
EOF
fi
exit "$status"
