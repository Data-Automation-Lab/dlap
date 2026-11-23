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
# Build the SPA for the hosted demo (DEMO-01).
#
# The UI reads its API origin and its Keycloak origin at build time, so the demo needs its own build:
# both are the public hostnames, mock data is off, and the demo flag (VITE_DEMO_MODE, DEMO-03) is on.
# The output goes outside the UI checkout, into the folder that nginx serves, so a build never
# dirties the UI repository.
#
# Usage:  build-spa.sh [options]
#   --env-file FILE   environment file (default .env.demo; the hosts and ports are read from it)
#   --ui-dir DIR      the UI frontend folder (default: new-ui/frontend, or $DEMO_UI_DIR)
#   --out DIR         output folder (default: $DEMO_SPA_DIR, else spa/ next to this folder)
#   --install         run `npm ci` even when node_modules exists
#   -h, --help        this text
#
# Needs Node 22 or later and npm. Run it on a workstation or in CI, not on the demo host.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

UI_DIR="${DEMO_UI_DIR:-$DEMO_REPO_ROOT/new-ui/frontend}"
OUT=""
INSTALL=0

usage() { awk '/^# Build the SPA/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || demo_die "--env-file needs a file name"; DEMO_ENV_FILE="$2"; shift 2 ;;
    --ui-dir) [ $# -ge 2 ] || demo_die "--ui-dir needs a folder"; UI_DIR="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || demo_die "--out needs a folder"; OUT="$2"; shift 2 ;;
    --install) INSTALL=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) demo_die "unknown argument: $1" ;;
  esac
done

[ -f "$UI_DIR/package.json" ] || demo_die "no UI at $UI_DIR (git submodule update --init new-ui, or --ui-dir)"
command -v node >/dev/null 2>&1 || demo_die "node is not installed (Node 22 or later)"
major="$(node -p 'process.versions.node.split(".")[0]')"
[ "$major" -ge 22 ] || demo_die "Node $major found; the UI build needs 22 or later"

if [ -z "$OUT" ]; then
  OUT="$(demo_cfg DEMO_SPA_DIR "$DEMO_DIR/spa")"
  case "$OUT" in
    /*|[A-Za-z]:*) ;;
    *) OUT="$DEMO_DEV_DIR/$OUT" ;;
  esac
fi

app_host="$(demo_cfg DEMO_APP_HOST "")"
id_host="$(demo_cfg DEMO_ID_HOST "")"
[ -n "$app_host" ] && [ -n "$id_host" ] || demo_die "DEMO_APP_HOST and DEMO_ID_HOST must be set in $DEMO_ENV_FILE"

# The browser-facing origins. A public host is https on 443. A rehearsal (DEMO_TLS=internal with a
# non-default port) is https on that port, and plain http on the HTTP port when DEMO_APP_HTTP_ADDRESS is set.
https_port="$(demo_cfg DEMO_HTTPS_PORT 443)"
http_port="$(demo_cfg DEMO_HTTP_PORT 80)"
suffix=""
[ "$https_port" = "443" ] || suffix=":$https_port"
http_address="$(demo_cfg DEMO_APP_HTTP_ADDRESS "")"
if [ -n "$http_address" ]; then
  # Local check mode: the app is also served over plain HTTP at this address.
  api_origin="$http_address"
  [ "$http_port" = "80" ] || api_origin="$api_origin:$http_port"
else
  api_origin="https://$app_host$suffix"
fi
kc_origin="https://$id_host$suffix"

demo_say "UI:          $UI_DIR"
demo_say "API origin:  $api_origin"
demo_say "Keycloak:    $kc_origin"
demo_say "Output:      $OUT"

cd "$UI_DIR"
if [ "$INSTALL" = "1" ] || [ ! -d node_modules ]; then
  demo_say "npm ci"
  npm ci --no-audit --no-fund
fi

export REACT_APP_API_BASE_URL="$api_origin"
export REACT_APP_KEYCLOAK_URL="$kc_origin"
export REACT_APP_KEYCLOAK_REALM="${REACT_APP_KEYCLOAK_REALM:-dlap}"
export REACT_APP_KEYCLOAK_CLIENT_ID="${REACT_APP_KEYCLOAK_CLIENT_ID:-dlap-ui}"
export REACT_APP_ENABLE_MOCK_DATA=false
export REACT_APP_ENABLE_DEBUG_LOGS=false
# The banner and the page allowlist of DEMO-03. Ignored by a UI build that does not know it yet.
export VITE_DEMO_MODE=true

mkdir -p "$OUT"
demo_say "vite build"
# A native path: on Windows, node reads /d/git/... as a path on the current drive.
npx vite build --outDir "$(demo_native_path "$OUT")" --emptyOutDir

[ -f "$OUT/index.html" ] || demo_die "the build produced no index.html in $OUT"
demo_say "done: $(find "$OUT" -type f | wc -l | tr -d ' ') files, $(du -sk "$OUT" | cut -f1) KiB"
