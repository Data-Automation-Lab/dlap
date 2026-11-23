#!/usr/bin/env bash
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

# OBS-01: offline checks of the monitoring configuration. Needs Docker, starts no stack.
#
#   promtool check config   prometheus.yml (and the rule files it names)
#   promtool check config   prometheus.tls.yml, syntax only (Q-518: its CA file exists only in the pilot's
#                           Prometheus container), and test-prometheus-tls.py, which keeps it equal to
#                           prometheus.yml apart from the HTTPS scrape of the services
#   promtool test rules     alerts.test.yml (every alert, firing and quiet)
#   amtool   check-config   ../alertmanager/alertmanager.yml
#   image tags              no image of the dev compose files is untagged or :latest
#
# The image tags are read from the dev compose file, so the check and the stack cannot drift apart.
# Run from anywhere:  bash deployment/platform/prometheus/check.sh

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
platform="$(cd "$here/.." && pwd)"
compose="$platform/../environments/dev/docker-compose.yml"

image_of() {
  # image: <name>:<tag> of the compose service whose key is $1
  awk -v svc="$1" '
    $0 ~ "^  " svc ":" { in_svc = 1; next }
    in_svc && /^  [a-z]/ { in_svc = 0 }
    in_svc && /^    image:/ { print $2; exit }
  ' "$compose"
}

prometheus_image="$(image_of prometheus)"
alertmanager_image="$(image_of alertmanager)"
if [[ -z "$prometheus_image" || -z "$alertmanager_image" ]]; then
  echo "check.sh: cannot read the prometheus and alertmanager images from $compose" >&2
  exit 2
fi
for image in "$prometheus_image" "$alertmanager_image"; do
  case "$image" in
    *:latest | *: | "${image%:*}") echo "check.sh: image '$image' is not pinned to a version tag" >&2; exit 2 ;;
  esac
done

# Git Bash on Windows rewrites paths that start with a slash; the container paths below must stay as they are.
export MSYS_NO_PATHCONV=1
to_docker_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

echo "== promtool check config ($prometheus_image)"
docker run --rm -v "$(to_docker_path "$here"):/p:ro" --entrypoint promtool "$prometheus_image" check config /p/prometheus.yml

echo "== promtool check config, syntax only ($prometheus_image): prometheus.tls.yml"
docker run --rm -v "$(to_docker_path "$here"):/p:ro" --entrypoint promtool "$prometheus_image" check config --syntax-only /p/prometheus.tls.yml

echo "== prometheus.tls.yml follows prometheus.yml and the pilot TLS overlay"
python_bin="$(command -v python3 || command -v python || true)"
if [[ -z "$python_bin" ]]; then
  echo "check.sh: python3 is required for test-prometheus-tls.py" >&2
  exit 2
fi
"$python_bin" "$(to_docker_path "$here")/test-prometheus-tls.py"

echo "== promtool test rules ($prometheus_image)"
docker run --rm -v "$(to_docker_path "$here"):/p:ro" -w /p --entrypoint promtool "$prometheus_image" test rules alerts.test.yml

echo "== amtool check-config ($alertmanager_image)"
docker run --rm -v "$(to_docker_path "$platform/alertmanager"):/a:ro" --entrypoint amtool "$alertmanager_image" check-config /a/alertmanager.yml

echo "== images of the dev compose files carry a version tag"
dev_dir="$(cd "$platform/../environments/dev" && pwd)"
unpinned="$(grep -hE '^[[:space:]]+image:' "$dev_dir"/docker-compose.yml "$dev_dir"/docker-compose.microservices.yml   "$dev_dir"/docker-compose.monitoring.yml | awk '{print $2}' | grep -E ':latest$|^[^:]+$' || true)"
if [[ -n "$unpinned" ]]; then
  echo "check.sh: these images are not pinned to a version tag:" >&2
  echo "$unpinned" >&2
  exit 1
fi
echo "ok"

echo "OK: prometheus.yml, alerts.yml, recording_rules.yml, alerts.test.yml and alertmanager.yml pass, images pinned"
