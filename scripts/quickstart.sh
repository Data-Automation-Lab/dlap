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
#
# ---------------------------------------------------------------------------
# The README quickstart as one command (QS-01): from a fresh clone to a signed-in catalog.
#
#   bash scripts/quickstart.sh             # check, build, start, wait, smoke test
#   bash scripts/quickstart.sh --check     # prerequisites only; changes nothing
#   bash scripts/quickstart.sh --dry-run   # print the commands it would run
#   bash scripts/quickstart.sh --no-ui     # backend only (the new-ui submodule is not needed)
#   bash scripts/quickstart.sh --no-build  # reuse the jars already in */build/libs
#   bash scripts/quickstart.sh --down      # stop the stack, keep its data
#   bash scripts/quickstart.sh --reset     # stop the stack and delete its data
#
# Steps, each timed; the summary at the end prints the seconds of every step:
#   1 prerequisites   git, curl, Docker 24+ with Compose 2.20+, a JDK 21, the ports
#   2 submodule       fetches new-ui when it is missing; without it the run is backend only
#   3 env file        copies .env.example to .env when .env is missing, never overwrites
#   4 build           Gradle bootJar for the ten services and the UI shell; the UI frontend is
#                     built once, inside its Docker image, not twice
#   5 start           docker compose up -d --build, ollama and its model pull skipped, then waits for healthy
#   6 smoke test      Keycloak answers, the seeded admin gets a token, the catalog lists assets
#
# The commands are the ones the README shows, in the same order. Nothing here is hidden:
# --dry-run prints them.
#
# JDK 21: Gradle itself runs on newer JDKs, but the build asks for a Java 21 toolchain and fails
# without one. The script uses, in this order: DLAP_JDK21, JAVA_HOME, java on the PATH, then the
# usual install folders of Linux, macOS and Windows (Git Bash). Only a real 21 is accepted.
#
# Environment (all optional):
#   DLAP_JDK21                    JDK 21 home
#   DLAP_QUICKSTART_NO_JDK_SEARCH set to 1 to skip the install folders (used by the tests)
#   COMPOSE_PROJECT_NAME          compose project (default: the folder name of the compose file, dev)
#   DLAP_QUICKSTART_WAIT_TIMEOUT  seconds to wait for healthy containers (default 900)
#   DLAP_QUICKSTART_USER          user of the smoke test (default admin, the seeded dev-realm user)
#   DLAP_QUICKSTART_PASSWORD      password of that user (default: the seed password in the README)
#   DLAP_PORT_<SERVICE>           host ports, read from the environment or from .env
#
# Exit: 0 when every step passed, 1 on a failed step (the message names the fix), 2 on bad usage.
# ---------------------------------------------------------------------------
set -euo pipefail

QS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QS_DEV_DIR="deployment/environments/dev"
# The ten services whose jars the Dockerfiles COPY. test-quickstart.sh fails when this list and
# the build contexts of the compose file drift apart.
QS_SERVICES=(da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival
  da-autodelete da-logingest da-autocompliance da-admin-service)
QS_COMPOSE_MIN="2.20"
QS_MIN_DOCKER_MEM=$((10 * 1024 * 1024 * 1024))
QS_SEED_USER="admin"
QS_SEED_PASSWORD="Password123!"

MODE="run"; WITH_UI=1; DO_BUILD=1
QS_STEP_NAMES=(); QS_STEP_SECS=()
QS_STEP_START=0; QS_STEP_LABEL=""
QS_T0="$(date +%s)"

qs_say()  { echo "[quickstart] $*"; }
qs_warn() { echo "[quickstart] WARN: $*" >&2; }
qs_die()  { echo "[quickstart] FAIL: $*" >&2; exit 1; }

qs_step_begin() { QS_STEP_LABEL="$1"; QS_STEP_START="$(date +%s)"; qs_say "== $1"; }
qs_step_end() {
  local secs=$(( $(date +%s) - QS_STEP_START ))
  QS_STEP_NAMES+=("$QS_STEP_LABEL"); QS_STEP_SECS+=("$secs")
  qs_say "   done in ${secs}s"
}

qs_summary() {
  local i total=$(( $(date +%s) - QS_T0 ))
  qs_say "== timing"
  for i in "${!QS_STEP_NAMES[@]}"; do
    printf '[quickstart]   %-16s %5ss\n' "${QS_STEP_NAMES[$i]}" "${QS_STEP_SECS[$i]}"
  done
  printf '[quickstart]   %-16s %5ss (%sm %ss)\n' "total" "$total" $((total / 60)) $((total % 60))
}

usage() { sed -n '/^# The README quickstart/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

for arg in "$@"; do
  case "$arg" in
    --check) MODE="check" ;;
    --dry-run) MODE="dry-run" ;;
    --down) MODE="down" ;;
    --reset) MODE="reset" ;;
    --no-ui) WITH_UI=0 ;;
    --no-build) DO_BUILD=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

cd "$QS_ROOT"

# --- Helpers ---------------------------------------------------------------------------------

# Last assignment of KEY in .env, else the default. Read, never sourced.
qs_env() { # qs_env <KEY> <default>
  local key="$1" def="$2" line value
  if [ -n "${!key:-}" ]; then printf '%s' "${!key}"; return 0; fi
  if [ -f .env ]; then
    line="$(grep -E "^[[:space:]]*${key}=" .env | tail -n 1 || true)"
    if [ -n "$line" ]; then
      value="${line#*=}"; value="${value%$'\r'}"
      value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
      if [ -n "$value" ]; then printf '%s' "$value"; return 0; fi
    fi
  fi
  printf '%s' "$def"
}

# Runs a command with a time limit when a GNU timeout exists; a half-started Docker engine makes
# the CLI hang, and a hang is no answer.
qs_timeout() { # qs_timeout <seconds> <command...>
  local secs="$1"; shift
  if command -v timeout >/dev/null 2>&1 && timeout --version 2>/dev/null | grep -q "GNU coreutils"; then
    timeout "$secs" "$@"
  else
    "$@"
  fi
}

# Major version of the JDK at <home>, or nothing when <home> holds no java.
qs_jdk_major() { # qs_jdk_major <home>
  local home="$1" bin="" out
  if [ -x "$home/bin/java" ]; then bin="$home/bin/java"
  elif [ -x "$home/bin/java.exe" ]; then bin="$home/bin/java.exe"
  else return 0; fi
  out="$("$bin" -version 2>&1 | head -n 1 || true)"
  # openjdk version "21.0.5" 2024-10-15  ->  21.   java version "1.8.0_402"  ->  1.
  out="${out#*\"}"; out="${out%%\"*}"
  printf '%s' "${out%%.*}"
}

# Prints the home of the first JDK 21 found, else nothing and status 1.
qs_find_jdk21() {
  local cand dir major seen=""
  local -a cands=()
  if [ -n "${DLAP_JDK21:-}" ]; then cands+=("$DLAP_JDK21"); fi
  if [ -n "${JAVA_HOME:-}" ]; then cands+=("$JAVA_HOME"); fi
  if command -v java >/dev/null 2>&1; then
    dir="$(command -v java)"; dir="$(cd "$(dirname "$dir")/.." 2>/dev/null && pwd || true)"
    if [ -n "$dir" ]; then cands+=("$dir"); fi
  fi
  # DLAP_QUICKSTART_NO_JDK_SEARCH=1 skips the install folders (the tests need a fixed answer).
  if [ "${DLAP_QUICKSTART_NO_JDK_SEARCH:-0}" != 1 ]; then
    if [ -x /usr/libexec/java_home ]; then
      dir="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
      if [ -n "$dir" ]; then cands+=("$dir"); fi
    fi
    # Unquoted globs on purpose; a pattern that matches nothing stays literal and fails the test below.
    for cand in /usr/lib/jvm/*21* /usr/lib/jvm/*/21* /usr/local/lib/jvm/*21* /opt/homebrew/opt/openjdk@21 \
        /Library/Java/JavaVirtualMachines/*21*/Contents/Home \
        "/c/Program Files/Java/"*21* "/c/Program Files/Eclipse Adoptium/"*21* \
        "/c/Program Files/Microsoft/"*21* "/c/Program Files/Amazon Corretto/"*21* \
        "/c/Program Files/Zulu/"*21* "$HOME/.sdkman/candidates/java/"*21* "$HOME/.jdks/"*21* \
        "$HOME/.gradle/jdks/"*21*; do
      cands+=("$cand")
    done
  fi
  for cand in "${cands[@]}"; do
    case "|$seen|" in *"|$cand|"*) continue ;; esac
    seen="$seen|$cand"
    major="$(qs_jdk_major "$cand")"
    if [ "$major" = "21" ]; then printf '%s' "$cand"; return 0; fi
  done
  return 1
}

# Compose with the two files of the dev stack and the root .env, as the README shows.
qs_compose() {
  docker compose --env-file .env \
    -f "$QS_DEV_DIR/docker-compose.yml" \
    -f "$QS_DEV_DIR/docker-compose.microservices.yml" "$@"
}

qs_compose_cmd_text() { # the compose prefix as text, for --dry-run
  printf 'docker compose --env-file .env -f %s/docker-compose.yml -f %s/docker-compose.microservices.yml' \
    "$QS_DEV_DIR" "$QS_DEV_DIR"
}

# True when something listens on 127.0.0.1:<port>.
qs_port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# Waits until <url> answers 2xx, at most <secs> seconds.
qs_wait_http() { # qs_wait_http <url> <secs>
  local url="$1" secs="$2" end=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    if curl -fsS -o /dev/null --max-time 5 "$url" 2>/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

# --- Plan ------------------------------------------------------------------------------------

qs_gradle_tasks() {
  local s
  for s in "${QS_SERVICES[@]}"; do printf ':%s:bootJar\n' "$s"; done
  if [ "$WITH_UI" = 1 ]; then
    # The UI jar is only the Spring shell. The frontend is built in the node stage of its Dockerfile
    # and served from /app/static, which the compose file puts ahead of the jar's own resources.
    printf '%s\n' ":new-ui:bootJar" "-x" ":new-ui:buildFrontend" "-x" ":new-ui:installFrontendDeps"
  fi
}

qs_scale_args() {
  printf '%s\n' "--scale" "ollama=0"
  # With named services the model pull is not selected (nothing depends on it), and compose
  # refuses to scale a service that is not selected ("no such service: ollama-model-pull: disabled").
  if [ "$WITH_UI" = 1 ]; then printf '%s\n' "--scale" "ollama-model-pull=0"; fi
}

# Services named on the `up` line. None (the whole compose project) with the UI. Without it the
# names are explicit: `--scale new-ui=0` still builds the new-ui image (its context is the missing
# submodule, or a jar that was not built), so new-ui and gateway must not be selected at all.
# Compose adds the dependencies of the named services by itself.
qs_up_services() {
  if [ "$WITH_UI" = 1 ]; then return 0; fi
  printf '%s\n' postgresql keycloak kafka "${QS_SERVICES[@]}"
}

qs_print_plan() {
  qs_say "plan (nothing is run)"
  echo "  [ -f .env ] || cp .env.example .env"
  if [ "$WITH_UI" = 1 ] && [ ! -f new-ui/build.gradle ]; then echo "  git submodule update --init new-ui"; fi
  if [ "$DO_BUILD" = 1 ]; then
    echo "  JAVA_HOME=<a JDK 21> ./gradlew $(qs_gradle_tasks | tr '\n' ' ')--console=plain --no-watch-fs"
  fi
  echo "  $(qs_compose_cmd_text) up -d --build $(qs_scale_args | tr '\n' ' ')$(qs_up_services | tr '\n' ' ')"
  echo "  wait until every container is running and healthy (at most $(qs_env DLAP_QUICKSTART_WAIT_TIMEOUT 900) s)"
  echo "  curl Keycloak, token for $QS_SEED_USER, GET the catalog assets"
}

# --- Steps -----------------------------------------------------------------------------------

qs_preflight() {
  local ok=1 cv major mem
  command -v git >/dev/null 2>&1 || { qs_warn "git is not installed"; ok=0; }
  command -v curl >/dev/null 2>&1 || { qs_warn "curl is not installed"; ok=0; }
  if ! command -v docker >/dev/null 2>&1; then
    qs_warn "docker is not installed. Install Docker 24+ with Compose v2 (Docker Desktop or Docker Engine)."; ok=0
  elif ! qs_timeout 30 docker info >/dev/null 2>&1; then
    qs_warn "the Docker daemon does not answer. Start Docker Desktop (or the docker service) and wait until it is ready."; ok=0
  else
    cv="$(docker compose version --short 2>/dev/null || true)"; cv="${cv#v}"
    if [ -z "$cv" ]; then
      qs_warn "docker compose v2 is missing (the old docker-compose does not work). Install the Compose plugin."; ok=0
    elif [ "$(printf '%s\n%s\n' "$QS_COMPOSE_MIN" "$cv" | sort -V | head -n 1)" != "$QS_COMPOSE_MIN" ]; then
      qs_warn "Docker Compose $cv is older than $QS_COMPOSE_MIN, which is not supported."; ok=0
    fi
    mem="$(docker info --format '{{.MemTotal}}' 2>/dev/null || true)"
    if [[ "$mem" =~ ^[0-9]+$ ]] && [ "$mem" -lt "$QS_MIN_DOCKER_MEM" ]; then
      qs_warn "Docker has $((mem / 1048576)) MiB of memory, under 10 GiB. The 15 containers held 8.5 to 9.1 GiB when measured on a 15.8 GiB Docker VM (the JVMs size their heaps from the VM). With less the stack may be killed for memory; raise the limit in Docker Desktop (Settings, Resources)."
    fi
  fi
  if [ "$DO_BUILD" = 1 ]; then
    JDK21="$(qs_find_jdk21 || true)"
    if [ -z "$JDK21" ]; then
      qs_warn "no JDK 21 found. Install one (for example Temurin 21) and set DLAP_JDK21=<its folder>, or JAVA_HOME. Gradle runs on newer JDKs but the build needs a 21 toolchain."
      ok=0
    else
      qs_say "JDK 21: $JDK21"
    fi
  fi
  [ "$ok" = 1 ] || qs_die "prerequisites missing (see the warnings above)"
  if ! docker compose ls -q 2>/dev/null | grep -qx "${COMPOSE_PROJECT_NAME:-dev}"; then
    local name var def port busy=""
    for entry in GATEWAY:8081 KEYCLOAK:8180 POSTGRES:5432 KAFKA:9092 NEW_UI:3000 CATALOG:8082; do
      name="${entry%%:*}"; def="${entry##*:}"; var="DLAP_PORT_$name"
      port="$(qs_env "$var" "$def")"
      if qs_port_busy "$port"; then busy="$busy $port($var)"; fi
    done
    if [ -n "$busy" ]; then
      qs_warn "ports already in use on 127.0.0.1:$busy. If a container fails to start with 'port is already allocated', put a free port in .env, for example DLAP_PORT_POSTGRES=15432. Ports 8081 and 8180 must stay: the UI login uses them."
    fi
  fi
}

qs_submodule() {
  if [ "$WITH_UI" = 0 ]; then qs_say "backend only (--no-ui)"; return 0; fi
  if [ -f new-ui/build.gradle ]; then qs_say "new-ui is present"; return 0; fi
  if [ -f .gitmodules ] && git rev-parse --git-dir >/dev/null 2>&1; then
    qs_say "fetching the new-ui submodule"
    git submodule update --init new-ui || true
  fi
  if [ ! -f new-ui/build.gradle ]; then
    qs_warn "the new-ui submodule could not be fetched; continuing with the backend only (no UI, no gateway). See the README for the submodule."
    WITH_UI=0
  fi
}

qs_env_file() {
  if [ -f .env ]; then qs_say ".env exists, left as it is"; return 0; fi
  [ -f .env.example ] || qs_die ".env.example is missing; is this the repository root?"
  cp .env.example .env
  qs_say "created .env from .env.example (dev defaults; not for production)"
}

qs_build() {
  if [ "$DO_BUILD" = 0 ]; then qs_say "skipped (--no-build)"; return 0; fi
  local -a tasks=()
  while IFS= read -r line; do tasks+=("$line"); done < <(qs_gradle_tasks)
  # 9>&-: a Gradle daemon must not inherit the harness lock's file descriptor and hold it.
  JAVA_HOME="$JDK21" ./gradlew "${tasks[@]}" --console=plain --no-watch-fs 9>&-
}

qs_start() {
  local -a scale=() svcs=()
  while IFS= read -r line; do scale+=("$line"); done < <(qs_scale_args)
  while IFS= read -r line; do svcs+=("$line"); done < <(qs_up_services)
  if ! qs_compose up -d --build "${scale[@]}" ${svcs[@]+"${svcs[@]}"}; then
    qs_warn "compose up failed. See the messages above."
    qs_warn "  'port is already allocated': set a free DLAP_PORT_<SERVICE> in .env (not 8081 or 8180)."
    qs_warn "  'COPY build/libs/...': the jar build was skipped or failed; run without --no-build."
    qs_die "the stack did not come up"
  fi
  qs_say "images built and containers started after $(( $(date +%s) - QS_STEP_START ))s of this step; waiting for them to become healthy"
  qs_wait_ready "$(qs_env DLAP_QUICKSTART_WAIT_TIMEOUT 900)"
}

# Waits until every container of the stack is running and, where it has a health check, healthy.
# Not `compose up --wait`: that stops at the first "unhealthy", and the larger JVMs (da-discovery
# takes about 95 s here) use up the three failed checks of the dev compose before they open their
# port, then turn healthy a check later. Here "unhealthy" counts as not ready yet; only an exited
# container, or the time limit, fails the run.
qs_wait_ready() { # qs_wait_ready <seconds>
  local secs="$1" end=$(( $(date +%s) + $1 )) next=0 ids lines pending total ready gone
  while :; do
    ids="$(qs_compose ps -a -q 2>/dev/null | tr -d '\r' | tr '\n' ' ' || true)"
    if [ -n "$ids" ]; then
      # shellcheck disable=SC2086  # one argument per container id
      lines="$(docker inspect --format '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' $ids 2>/dev/null | tr -d '\r' || true)"
      gone="$(printf '%s\n' "$lines" | awk '$2=="exited" || $2=="dead" {print $1}' | tr '\n' ' ')"
      if [ -n "$gone" ]; then
        qs_warn "stopped: $gone. Read why with: docker compose logs <service>"
        qs_die "a container of the stack exited"
      fi
      pending="$(printf '%s\n' "$lines" | awk '$2!="running" || ($3!="-" && $3!="healthy") {print $1}' | sed 's|^/||' | tr '\n' ' ')"
      total="$(printf '%s\n' "$lines" | grep -c . || true)"
      if [ -z "$pending" ] && [ "$total" -gt 0 ]; then
        qs_say "all $total containers are running and healthy"
        return 0
      fi
      ready=$(( total - $(printf '%s\n' "$pending" | wc -w) ))
      if [ "$(date +%s)" -ge "$next" ]; then
        qs_say "waiting: $ready of $total ready; not yet: $pending"
        next=$(( $(date +%s) + 20 ))
      fi
    fi
    if [ "$(date +%s)" -ge "$end" ]; then
      qs_warn "not ready after ${secs}s: ${pending:-no containers}. Read why with: docker compose logs <service>. Docker needs about 10 GiB for the stack."
      qs_die "the stack is not healthy"
    fi
    sleep 3
  done
}

qs_smoke() {
  local kc gw cat secret tok body total
  kc="http://localhost:$(qs_env DLAP_PORT_KEYCLOAK 8180)"
  if [ "$WITH_UI" = 1 ]; then
    gw="http://localhost:$(qs_env DLAP_PORT_GATEWAY 8081)"
    cat="$gw/api/v1/catalog/assets?size=1"
    qs_wait_http "$gw/" 120 || qs_die "the gateway does not answer on $gw/"
  else
    cat="http://localhost:$(qs_env DLAP_PORT_CATALOG 8082)/api/v1/catalog/assets?size=1"
  fi
  qs_wait_http "$kc/realms/dlap/.well-known/openid-configuration" 120 \
    || qs_die "Keycloak does not serve the dlap realm on $kc"
  secret="$(qs_env DLAP_BACKEND_CLIENT_SECRET change-me-dev-only)"
  # The dlap-ui client has no direct grant (the browser uses the code flow); dlap-backend has.
  body="$(curl -sS --max-time 20 -X POST "$kc/realms/dlap/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=dlap-backend --data-urlencode "client_secret=$secret" \
    --data-urlencode "username=${DLAP_QUICKSTART_USER:-$QS_SEED_USER}" \
    --data-urlencode "password=${DLAP_QUICKSTART_PASSWORD:-$QS_SEED_PASSWORD}" || true)"
  tok="$(printf '%s' "$body" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
  [ -n "$tok" ] || qs_die "Keycloak gave no token for ${DLAP_QUICKSTART_USER:-$QS_SEED_USER}: ${body:0:200}"
  qs_say "login ok: Keycloak issued a token for ${DLAP_QUICKSTART_USER:-$QS_SEED_USER}"
  body="$(curl -sS --max-time 30 -H "Authorization: Bearer $tok" "$cat" || true)"
  total="$(printf '%s' "$body" | sed -n 's/.*"totalElements":\([0-9][0-9]*\).*/\1/p')"
  [ -n "$total" ] || qs_die "the catalog did not answer with a page of assets: ${body:0:200}"
  qs_say "catalog ok: $cat lists $total assets"
}

qs_down() {
  [ -f .env ] || cp .env.example .env
  if [ "$MODE" = "reset" ]; then qs_compose down -v; else qs_compose down; fi
}

# --- Main ------------------------------------------------------------------------------------

case "$MODE" in
  dry-run) qs_print_plan; exit 0 ;;
  check) qs_step_begin "prerequisites"; qs_preflight; qs_step_end; qs_say "prerequisites ok"; exit 0 ;;
  down|reset) qs_down; exit 0 ;;
esac

qs_step_begin "prerequisites"; qs_preflight; qs_step_end
qs_step_begin "submodule";     qs_submodule; qs_step_end
qs_step_begin "env file";      qs_env_file; qs_step_end
qs_step_begin "build";         qs_build; qs_step_end
qs_step_begin "start";         qs_start; qs_step_end
qs_step_begin "smoke test";    qs_smoke; qs_step_end
qs_summary

if [ "$WITH_UI" = 1 ]; then
  qs_say "open http://localhost:$(qs_env DLAP_PORT_GATEWAY 8081) , press Sign In, and on the Keycloak page sign in as $QS_SEED_USER with the seed password from the README."
else
  qs_say "backend only: the services answer on their own ports (README, service catalog)."
fi
qs_say "stop with: bash scripts/quickstart.sh --down   (--reset also deletes the data)"
