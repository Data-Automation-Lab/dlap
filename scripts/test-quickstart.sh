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
# Named tests for scripts/quickstart.sh and the README quickstart section (QS-01).
#
#   bash scripts/test-quickstart.sh            # every test
#   bash scripts/test-quickstart.sh QS-T10     # one test
#
# No Docker, no Gradle, no network: the script runs against stub docker, gradlew, curl, git and
# java programs in a private temp folder, and the stubs log every call. The real compose file
# and the real README are read for the drift tests (QS-T06, QS-T07, QS-T20 to QS-T23). The
# timed run from a clean clone is not a test: it needs the images and a quarter of an hour; the
# README quickstart section records its numbers.
#
# Exit 0 when every test passed, 1 otherwise.
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
SCRIPT="$HERE/quickstart.sh"
COMPOSE="$REPO/deployment/environments/dev/docker-compose.microservices.yml"
README="$REPO/README.md"
ONLY="${1:-}"

PASS=0; FAIL=0
t_ok()   { PASS=$((PASS + 1)); echo "  ok    $1"; }
t_fail() { FAIL=$((FAIL + 1)); echo "  FAIL  $1${2:+ : $2}"; }
has()    { # has <name> <haystack> <needle>
  if [[ "$2" == *"$3"* ]]; then t_ok "$1"; else t_fail "$1" "missing '$3' in: ${2:0:400}"; fi
}
lacks()  { # lacks <name> <haystack> <needle>
  if [[ "$2" != *"$3"* ]]; then t_ok "$1"; else t_fail "$1" "unexpected '$3' in: ${2:0:400}"; fi
}
eq()     { if [[ "$2" == "$3" ]]; then t_ok "$1"; else t_fail "$1" "expected '$2', got '$3'"; fi; }
want()   { [[ -z "$ONLY" || "$ONLY" == "$1" ]]; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- Stubs and a throw-away repository ----------------------------------------------------------

mk_stubs() { # mk_stubs <dir>: stub programs that log to <dir>/calls.log
  local d="$1"; mkdir -p "$d/bin"
  cat > "$d/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_LOG"
case "$1" in
  info) [ "${STUB_DOCKER_INFO_RC:-0}" = 0 ] || exit 1
        if [[ "$*" == *MemTotal* ]]; then echo "${STUB_MEM:-17000000000}"; fi; exit 0 ;;
  compose)
    shift
    if [ "$1" = version ]; then [ -n "${STUB_COMPOSE_VERSION-2.38.1}" ] || exit 1; echo "${STUB_COMPOSE_VERSION-2.38.1}"; exit 0; fi
    if [ "$1" = ls ]; then exit 0; fi
    for a in "$@"; do
      if [ "$a" = ps ]; then echo c1; echo c2; exit 0; fi
      if [ "$a" = up ]; then exit "${STUB_COMPOSE_UP_RC:-0}"; fi
    done
    exit 0 ;;
  inspect)
    n=0; [ -f "$STUB_LOG.inspect" ] && n="$(cat "$STUB_LOG.inspect")"; n=$((n + 1)); echo "$n" > "$STUB_LOG.inspect"
    if [ -n "${STUB_INSPECT:-}" ]; then echo "$STUB_INSPECT"; exit 0; fi
    if [ "$n" -le "${STUB_UNHEALTHY_POLLS:-0}" ]; then
      echo "/dlap-a running unhealthy"; echo "/dlap-b running healthy"
    else
      echo "/dlap-a running healthy"; echo "/dlap-b running -"
    fi
    exit 0 ;;
esac
exit 0
STUB
  cat > "$d/bin/curl" <<'STUB'
#!/usr/bin/env bash
echo "curl $*" >> "$STUB_LOG"
url=""; for a in "$@"; do case "$a" in http*) url="$a" ;; esac; done
token_body="${STUB_TOKEN_BODY:-}"; [ -n "$token_body" ] || token_body='{"access_token":"stub-token"}'
catalog_body="${STUB_CATALOG_BODY:-}"; [ -n "$catalog_body" ] || catalog_body='{"content":[],"totalElements":5}'
case "$url" in
  */protocol/openid-connect/token) echo "$token_body" ;;
  */api/v1/catalog/assets*) echo "$catalog_body" ;;
esac
exit 0
STUB
  cat > "$d/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >> "$STUB_LOG"
exit 0
STUB
  chmod +x "$d/bin/"*
}

mk_jdk() { # mk_jdk <home> <version text>
  mkdir -p "$1/bin"
  printf '#!/usr/bin/env bash\necho "openjdk version \\"%s\\" 2024-10-15" >&2\n' "$2" > "$1/bin/java"
  chmod +x "$1/bin/java"
}

# mk_repo <name>: a copy of the script, an .env.example, a stub gradlew, no new-ui. Prints the dir.
mk_repo() {
  local r="$TMP/$1"
  mkdir -p "$r/scripts"
  cp "$SCRIPT" "$r/scripts/quickstart.sh"
  echo "POSTGRES_PASSWORD=dlap" > "$r/.env.example"
  cat > "$r/gradlew" <<'STUB'
#!/usr/bin/env bash
echo "gradlew JAVA_HOME=$JAVA_HOME $*" >> "$STUB_LOG"
exit "${STUB_GRADLE_RC:-0}"
STUB
  chmod +x "$r/gradlew"
  mk_stubs "$r"
  mk_jdk "$r/jdk21" "21.0.5"
  mk_jdk "$r/jdk24" "24.0.2"
  mk_jdk "$r/jdk8" "1.8.0_402"
  : > "$r/calls.log"
  printf '%s' "$r"
}

# qs <repo> [env assignments...] -- [script args...]: runs the script in <repo> with a clean
# environment; output in $OUT, status in $RC, stub calls in $CALLS.
OUT=""; RC=0; CALLS=""
qs() {
  local r="$1"; shift
  local -a envs=()
  while [[ $# -gt 0 && "$1" != "--" ]]; do envs+=("$1"); shift; done
  [[ "${1:-}" == "--" ]] && shift
  : > "$r/calls.log"; rm -f "$r/calls.log.inspect"
  OUT="$(cd "$r" && env -u JAVA_HOME -u DLAP_JDK21 -u COMPOSE_PROJECT_NAME -u DLAP_QUICKSTART_USER \
      -u DLAP_QUICKSTART_PASSWORD -u DLAP_PORT_GATEWAY -u DLAP_PORT_KEYCLOAK \
      PATH="$r/bin:/usr/bin:/bin" HOME="$r/home" STUB_LOG="$r/calls.log" \
      DLAP_QUICKSTART_NO_JDK_SEARCH=1 DLAP_QUICKSTART_WAIT_TIMEOUT=30 ${envs[@]+"${envs[@]}"} \
      "$BASH" scripts/quickstart.sh "$@" 2>&1)"
  RC=$?
  CALLS="$(cat "$r/calls.log")"
}

section() { echo; echo "== $1"; }

# --- Tests ---------------------------------------------------------------------------------------

if want QS-T01; then section "QS-T01 the script parses and every function the steps call exists"
  if bash -n "$SCRIPT"; then t_ok "bash -n"; else t_fail "bash -n"; fi
  for f in qs_preflight qs_submodule qs_env_file qs_build qs_start qs_smoke qs_find_jdk21 qs_down; do
    grep -q "^$f()" "$SCRIPT" && t_ok "defines $f" || t_fail "defines $f"
  done
fi

if want QS-T02; then section "QS-T02 --help and bad usage"
  R="$(mk_repo t02)"
  qs "$R" -- --help
  eq "--help exits 0" 0 "$RC"
  has "--help lists --dry-run" "$OUT" "--dry-run"
  qs "$R" -- --bogus
  eq "an unknown option exits 2" 2 "$RC"
  has "an unknown option is named" "$OUT" "unknown option: --bogus"
fi

if want QS-T03; then section "QS-T03 --dry-run prints the plan and runs nothing"
  R="$(mk_repo t03)"
  qs "$R" -- --dry-run
  eq "exit 0" 0 "$RC"
  has "gradle builds the bootJar of da-catalog" "$OUT" ":da-catalog:bootJar"
  has "the UI shell is built without its frontend" "$OUT" ":new-ui:bootJar -x :new-ui:buildFrontend -x :new-ui:installFrontendDeps"
  has "compose starts detached with a build" "$OUT" "up -d --build --scale ollama=0 --scale ollama-model-pull=0"
  has "the script waits for healthy containers itself" "$OUT" "wait until every container is running and healthy (at most 30 s)"
  lacks "it does not use compose --wait (it stops at the first transient unhealthy)" "$OUT" "--wait"
  has "the ollama model pull is skipped" "$OUT" "--scale ollama-model-pull=0"
  has "ollama itself is skipped (its 6.5 GB image is not needed with the rules provider)" "$OUT" "--scale ollama=0"
  has "the env file is passed to compose" "$OUT" "docker compose --env-file .env -f deployment/environments/dev/docker-compose.yml -f deployment/environments/dev/docker-compose.microservices.yml"
  eq "no stub program was called" "" "$CALLS"
  [[ ! -f "$R/.env" ]] && t_ok "no .env written" || t_fail "dry run wrote .env"
fi

if want QS-T04; then section "QS-T04 --no-ui plans a backend-only stack"
  R="$(mk_repo t04)"
  qs "$R" -- --dry-run --no-ui --no-build
  lacks "no UI jar" "$OUT" "new-ui:bootJar"
  lacks "no gradle at all with --no-build" "$OUT" "./gradlew"
  has "the up call names the infrastructure and a service explicitly" "$OUT" "up -d --build --scale ollama=0 postgresql keycloak kafka da-discovery da-catalog"
  lacks "the model pull is not scaled when it is not selected (compose rejects a scale of a disabled service)" "$OUT" "ollama-model-pull"
  has "the up call ends with the last service" "$OUT" "da-autocompliance da-admin-service "
  lacks "new-ui is not scaled (a scale of 0 still builds it)" "$OUT" "new-ui"
  lacks "gateway is not selected" "$OUT" "gateway"
fi

if want QS-T05; then section "QS-T05 JDK 21 selection"
  R="$(mk_repo t05)"
  qs "$R" "JAVA_HOME=$R/jdk24" -- --check
  eq "JAVA_HOME on a JDK 24 alone fails" 1 "$RC"
  has "it says why" "$OUT" "no JDK 21 found"
  has "it names the fix" "$OUT" "DLAP_JDK21"
  qs "$R" "JAVA_HOME=$R/jdk8" -- --check
  eq "a JDK 8 is not accepted" 1 "$RC"
  qs "$R" "JAVA_HOME=$R/jdk21" -- --check
  eq "JAVA_HOME on a JDK 21 passes" 0 "$RC"
  has "it reports the JDK" "$OUT" "JDK 21: $R/jdk21"
  qs "$R" "JAVA_HOME=$R/jdk24" "DLAP_JDK21=$R/jdk21" -- --check
  eq "DLAP_JDK21 wins over a JDK 24 JAVA_HOME" 0 "$RC"
  has "it uses DLAP_JDK21" "$OUT" "JDK 21: $R/jdk21"
  qs "$R" -- --check --no-build
  eq "--no-build needs no JDK" 0 "$RC"
fi

if want QS-T06; then section "QS-T06 the service list matches the compose build contexts"
  from_script="$(sed -n '/^QS_SERVICES=(/,/)$/p' "$SCRIPT" | tr -d '()' | sed 's/QS_SERVICES=//' | tr -s ' \n' '\n\n' | grep -E '^da-' | sort | tr '\n' ' ')"
  from_compose="$(grep -oE 'context: \.\./\.\./\.\./da-[a-z-]+' "$COMPOSE" | sed 's|.*/||' | sort | tr '\n' ' ')"
  eq "10 services, the same names" "$from_compose" "$from_script"
  eq "ten of them" 10 "$(echo "$from_script" | wc -w | tr -d ' ')"
fi

if want QS-T07; then section "QS-T07 the compose services the script names exist"
  for s in ollama ollama-model-pull new-ui gateway; do
    grep -qE "^  $s:" "$COMPOSE" && t_ok "compose defines $s" || t_fail "compose defines $s"
  done
  # Nothing may depend on the model pull, or scaling it to 0 would break the start.
  if grep -nE '^ {6}ollama-model-pull:' "$COMPOSE" >/dev/null; then
    t_fail "no service depends on ollama-model-pull"
  else
    t_ok "no service depends on ollama-model-pull"
  fi
  if grep -nE 'depends_on: *\[.*ollama-model-pull' "$COMPOSE" >/dev/null; then
    t_fail "no short-form depends_on names ollama-model-pull"
  else
    t_ok "no short-form depends_on names ollama-model-pull"
  fi
  # Backend-only mode names these on the up line; each must be a service of the compose pair.
  BASE="$REPO/deployment/environments/dev/docker-compose.yml"
  backend="$(sed -n '/^qs_up_services()/,/^}/p' "$SCRIPT")"
  svc_names="$(sed -n '/^QS_SERVICES=(/,/)$/p' "$SCRIPT" | tr -d '()' | sed 's/QS_SERVICES=//' | tr -s ' ' '\n' | grep -E '^da-')"
  for s in postgresql keycloak kafka $svc_names; do
    if grep -qE "^  $s:" "$COMPOSE" "$BASE"; then t_ok "backend-only service $s is defined by the compose pair"; else t_fail "backend-only service $s is defined by the compose pair"; fi
  done
  has "the backend-only list names postgresql keycloak kafka" "$backend" "postgresql keycloak kafka"
  lacks "the backend-only list does not name new-ui" "$backend" "new-ui"
  lacks "the backend-only list does not name the gateway" "$backend" "gateway"
fi

if want QS-T08; then section "QS-T08 Docker prerequisites"
  R="$(mk_repo t08)"
  qs "$R" "JAVA_HOME=$R/jdk21" "PATH=/usr/bin:/bin" -- --check
  has "no docker on the PATH is reported" "$OUT" "docker is not installed"
  eq "and fails" 1 "$RC"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_DOCKER_INFO_RC=1 -- --check
  has "a silent daemon is reported" "$OUT" "daemon does not answer"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_COMPOSE_VERSION=2.10.2 -- --check
  has "an old Compose is reported" "$OUT" "older than 2.20"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_COMPOSE_VERSION= -- --check
  has "a missing Compose v2 is reported" "$OUT" "docker compose v2 is missing"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_COMPOSE_VERSION=v2.38.1-desktop.1 -- --check
  eq "a v-prefixed desktop version passes" 0 "$RC"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_MEM=9000000000 -- --check
  has "under 10 GiB warns" "$OUT" "under 10 GiB"
  eq "but does not fail" 0 "$RC"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_MEM=17000000000 -- --check
  lacks "16 GiB does not warn" "$OUT" "under 10 GiB"
fi

if want QS-T09; then section "QS-T09 the whole run, with stubs"
  R="$(mk_repo t09)"
  qs "$R" "JAVA_HOME=$R/jdk21" --
  eq "exit 0" 0 "$RC"
  has "gradle got the JDK 21" "$CALLS" "gradlew JAVA_HOME=$R/jdk21 :da-discovery:bootJar"
  has "gradle builds all ten services" "$CALLS" ":da-admin-service:bootJar"
  has "compose up starts the stack" "$CALLS" "up -d --build --scale ollama=0 postgresql"
  has "the containers were inspected for health" "$CALLS" "docker inspect"
  has "it reports the stack ready" "$OUT" "2 containers are running and healthy"
  has "it reports when the images were built" "$OUT" "images built and containers started after"
  has "the login smoke test passes" "$OUT" "login ok"
  has "the catalog smoke test reports the count" "$OUT" "lists 5 assets"
  has "the timing summary has a total" "$OUT" "total"
  has "every step is listed" "$OUT" "smoke test"
  [[ -f "$R/.env" ]] && t_ok ".env was created" || t_fail ".env was not created"
  # No new-ui in the temp repo: the script must fall back to the backend only.
  has "a missing UI falls back to the backend only" "$OUT" "continuing with the backend only"
  has "backend only uses the catalog port" "$CALLS" "localhost:8082/api/v1/catalog/assets"
  has "the up call names the services explicitly" "$CALLS" "up -d --build --scale ollama=0 postgresql keycloak kafka da-discovery"
  lacks "the up call does not scale the unselected model pull" "$CALLS" "ollama-model-pull=0"
  up_line="$(grep -E 'docker compose .* up ' <<<"$CALLS" | head -n 1)"
  lacks "the up call does not mention new-ui" "$up_line" "new-ui"
  lacks "the up call does not mention the gateway" "$up_line" "gateway"
fi

if want QS-T10; then section "QS-T10 an existing .env is never overwritten"
  R="$(mk_repo t10)"
  echo "MARKER=keep-me" > "$R/.env"
  qs "$R" "JAVA_HOME=$R/jdk21" --
  eq "exit 0" 0 "$RC"
  eq ".env is unchanged" "MARKER=keep-me" "$(cat "$R/.env")"
  has "it says so" "$OUT" ".env exists, left as it is"
fi

if want QS-T11; then section "QS-T11 with the UI present"
  R="$(mk_repo t11)"
  mkdir -p "$R/new-ui"; : > "$R/new-ui/build.gradle"
  qs "$R" "JAVA_HOME=$R/jdk21" --
  eq "exit 0" 0 "$RC"
  has "the UI jar is built without the frontend tasks" "$CALLS" ":new-ui:bootJar -x :new-ui:buildFrontend -x :new-ui:installFrontendDeps"
  has "the smoke test goes through the gateway" "$CALLS" "localhost:8081/api/v1/catalog/assets"
  lacks "the UI is not scaled away" "$CALLS" "--scale new-ui=0"
  up_line="$(grep -E 'docker compose .* up ' <<<"$CALLS" | head -n 1)"
  has "with the UI the whole project is started (no service names)" "$up_line" "up -d --build --scale ollama=0 --scale ollama-model-pull=0"
  lacks "with the UI no service is named on the up line" "$up_line" "postgresql"
fi

if want QS-T12; then section "QS-T12 ports come from .env"
  R="$(mk_repo t12)"
  mkdir -p "$R/new-ui"; : > "$R/new-ui/build.gradle"
  printf 'DLAP_PORT_GATEWAY=18081\nDLAP_PORT_KEYCLOAK="18180"\n' > "$R/.env"
  qs "$R" "JAVA_HOME=$R/jdk21" --
  eq "exit 0" 0 "$RC"
  has "the gateway port is read from .env" "$CALLS" "localhost:18081/api/v1/catalog/assets"
  has "the Keycloak port is read from .env, quotes removed" "$CALLS" "localhost:18180/realms/dlap/protocol/openid-connect/token"
fi

if want QS-T13; then section "QS-T13 failures say what to do"
  R="$(mk_repo t13)"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_COMPOSE_UP_RC=1 --
  eq "a failed compose up exits 1" 1 "$RC"
  has "it names the port fix" "$OUT" "port is already allocated"
  has "it says the stack did not come up" "$OUT" "the stack did not come up"
  lacks "it does not go on to the smoke test" "$OUT" "login ok"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_GRADLE_RC=1 --
  eq "a failed build exits non-zero" 1 "$RC"
  lacks "and does not start the stack" "$CALLS" "up -d"
  qs "$R" "JAVA_HOME=$R/jdk21" 'STUB_TOKEN_BODY={"error":"unauthorized_client"}' --
  eq "no token exits 1" 1 "$RC"
  has "it names the user" "$OUT" "gave no token for admin"
  qs "$R" "JAVA_HOME=$R/jdk21" 'STUB_CATALOG_BODY={"error":"forbidden"}' --
  eq "a catalog without a page exits 1" 1 "$RC"
  has "it says so" "$OUT" "did not answer with a page of assets"
fi

if want QS-T14; then section "QS-T14 --down and --reset"
  R="$(mk_repo t14)"
  qs "$R" -- --down
  eq "--down exits 0" 0 "$RC"
  has "--down calls compose down" "$CALLS" " down"
  lacks "--down keeps the volumes" "$CALLS" "down -v"
  qs "$R" -- --reset
  has "--reset removes the volumes" "$CALLS" "down -v"
fi

if want QS-T15; then section "QS-T15 --no-build skips Gradle and the JDK"
  R="$(mk_repo t15)"
  qs "$R" -- --no-build
  eq "exit 0" 0 "$RC"
  lacks "gradlew is not called" "$CALLS" "gradlew"
  has "compose is" "$CALLS" "up -d --build"
fi

if want QS-T16; then section "QS-T16 a transient unhealthy is waited out"
  R="$(mk_repo t16)"
  qs "$R" "JAVA_HOME=$R/jdk21" STUB_UNHEALTHY_POLLS=2 -- 
  eq "exit 0 although the first two polls saw an unhealthy container" 0 "$RC"
  has "it reported the wait" "$OUT" "waiting: 1 of 2 ready; not yet: dlap-a"
  has "and then that all are healthy" "$OUT" "all 2 containers are running and healthy"
  eq "three polls" 3 "$(cat "$R/calls.log.inspect")"
fi

if want QS-T17; then section "QS-T17 a stack that never turns healthy fails at the time limit"
  R="$(mk_repo t17)"
  qs "$R" "JAVA_HOME=$R/jdk21" DLAP_QUICKSTART_WAIT_TIMEOUT=4 'STUB_INSPECT=/dlap-a running unhealthy' -- 
  eq "exit 1" 1 "$RC"
  has "it names the container" "$OUT" "not ready after 4s: dlap-a"
  has "it says the stack is not healthy" "$OUT" "the stack is not healthy"
  lacks "no smoke test" "$OUT" "login ok"
fi

if want QS-T18; then section "QS-T18 an exited container fails at once"
  R="$(mk_repo t18)"
  qs "$R" "JAVA_HOME=$R/jdk21" 'STUB_INSPECT=/dlap-a exited -' -- 
  eq "exit 1" 1 "$RC"
  has "it names the container" "$OUT" "stopped: /dlap-a"
  has "it says a container exited" "$OUT" "a container of the stack exited"
fi

# --- README drift tests (the real README) --------------------------------------------------------

quickstart_section() { sed -n '/^## Quickstart (fresh clone)/,/^## Test the pipeline/p' "$README" | tr -d '\r'; }
# Only the lines inside fenced code blocks of the section: the commands a reader would paste.
quickstart_code() { quickstart_section | awk '/^```/ {f = !f; next} f'; }

if want QS-T20; then section "QS-T20 the README quickstart runs the script and says what it costs"
  Q="$(quickstart_section)"
  has "the README calls the script" "$Q" "bash scripts/quickstart.sh"
  [[ -f "$SCRIPT" ]] && t_ok "the script exists" || t_fail "the script exists"
  has "a measured wall time is stated" "$Q" "Measured"
  lacks "no unmeasured 'under 5 minutes' claim" "$Q" "under 5 minutes"
  lacks "no claim that Gradle 8.7 cannot run on JDK 24 (the wrapper is 8.14.6)" "$Q" "Gradle 8.7"
  if grep -qE 'distributionUrl=.*gradle-8\.14' "$REPO/gradle/wrapper/gradle-wrapper.properties"; then
    t_ok "the wrapper is still Gradle 8.14 (the README does not name a Gradle version)"
  else
    t_fail "the wrapper changed; re-check the JDK sentence of the README"
  fi
fi

if want QS-T21; then section "QS-T21 the README by-hand commands match the script"
  Q="$(quickstart_section)"
  has "env file is passed" "$Q" "--env-file .env"
  has "both compose files" "$Q" "-f deployment/environments/dev/docker-compose.microservices.yml"
  has "up -d --build with the pull skipped" "$Q" "up -d --build --scale ollama=0 --scale ollama-model-pull=0"
  C="$(quickstart_code)"
  lacks "no pasted command uses compose --wait (a slow JVM turns unhealthy first and --wait gives up)" "$C" "--wait"
  has "only the bootJars are built" "$Q" ":new-ui:bootJar -x :new-ui:buildFrontend -x :new-ui:installFrontendDeps"
  lacks "no pasted command is the 15 minute 'build -x test'" "$C" "gradlew build -x test"
  for s in da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete da-logingest da-autocompliance da-admin-service; do
    [[ "$Q" == *":$s:bootJar"* ]] || { t_fail "README builds $s"; continue; }
  done
  t_ok "README builds all ten services"
fi

if want QS-T22; then section "QS-T22 the README health list is the published ports"
  Q="$(quickstart_section)"
  ports="$(printf '%s\n' "$Q" | grep -oE 'curl -sf http://localhost:[0-9]+/actuator/health' | grep -oE '[0-9]{4,5}' | sort -u | tr '\n' ' ')"
  published="$(grep -oE '\$\{DLAP_PORT_(CATALOG|POLICYENGINE|REPORTING|AUTOLABEL|AUTOARCHIVAL|AUTODELETE|AUTOCOMPLIANCE|ADMIN|LOGINGEST|DISCOVERY):-[0-9]+\}' "$COMPOSE" | grep -oE ':-[0-9]+' | tr -d ':-' | sort -u | tr '\n' ' ')"
  eq "one health URL per service port" "$published" "$ports"
fi

if want QS-T23; then section "QS-T23 the compose pair of the README is the one the script runs"
  Q="$(quickstart_section)"
  README_FILES="$(printf '%s\n' "$Q" | grep -oE 'deployment/environments/dev/docker-compose[a-z.-]*\.yml' | sort -u | tr '\n' ' ')"
  eq "only the two files of the dev stack" "deployment/environments/dev/docker-compose.microservices.yml deployment/environments/dev/docker-compose.yml " "$README_FILES"
  for f in $README_FILES; do [[ -f "$REPO/$f" ]] && t_ok "$f exists" || t_fail "$f exists"; done
fi

echo
echo "passed $PASS, failed $FAIL"
[[ "$FAIL" -eq 0 ]]
