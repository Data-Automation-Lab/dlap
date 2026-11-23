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

# DLAP production Keycloak image (IAM-02).
#
# The stock image re-runs the Quarkus build step at every `kc.sh start`. This image runs it once, here,
# with the build-time options a DLAP deployment needs, so the container starts with
# `kc.sh start --optimized`: no build at start, and a start that refuses a build option it was not built
# with. The pilot overlay (deployment/environments/pilot/docker-compose.pilot.keycloak.yml) builds and
# runs it; a Helm chart is expected to run the same image (deployment/platform/keycloak/production/README.md).
#
# Build-time options (a change needs a rebuild):
#   db=postgres                  the only supported store; no dev-file H2 database
#   health-enabled=true          /health/ready and /health/live on the management port 9000
#   metrics-enabled=false        no /metrics endpoint
#   features-disabled=...        scripts (JavaScript authenticators and mappers, the preview feature the
#                                quickstart used to switch on), impersonation, the device and CIBA grants,
#                                Kerberos. Disabled explicitly so a change of Keycloak's defaults cannot turn
#                                one of them on.
#
# Same release and digest as the demo identity overlay (deployment/environments/demo/keycloak), the
# current 26.x patch release (26.8.1, 2026-10-13). Bump both together.

FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244 AS builder

ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=false \
    KC_FEATURES_DISABLED=scripts,impersonation,device-flow,ciba,kerberos

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# The stock image's unprivileged user.
USER 1000

# The image has no curl or wget: bash speaks HTTP to the management port over /dev/tcp.
HEALTHCHECK --interval=10s --timeout=8s --start-period=60s --retries=30 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && timeout 5 cat <&3 | grep -m1 '\"status\"' | grep -q '\"UP\"'"]

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
