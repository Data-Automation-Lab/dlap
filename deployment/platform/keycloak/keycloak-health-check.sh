#!/bin/bash

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


# Keycloak health check (IAM-02, Keycloak 26).
#
# Keycloak 25 and later serve /health/ready on the management interface (port 9000, KC_HEALTH_ENABLED=true),
# not on the HTTP port: a probe of 8080 gets 404 and would hold the container unhealthy for ever. The image
# ships no curl or wget, so the probe speaks HTTP over a raw TCP socket (bash /dev/tcp).
#
# Exit status: 0 when Keycloak reports UP, 1 otherwise.

MANAGEMENT_PORT=${KC_HTTP_MANAGEMENT_PORT:-9000}

if ! exec 3<>"/dev/tcp/127.0.0.1/${MANAGEMENT_PORT}" 2>/dev/null; then
    echo "Keycloak is not ready (cannot connect to the management port :${MANAGEMENT_PORT})"
    exit 1
fi
printf 'GET /health/ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
# The first "status" of the body is the overall one; the checks below it can be UP while it is DOWN
# (for example "Keycloak Initialized" during the start-up import).
if timeout 5 cat <&3 2>/dev/null | grep -m1 '"status"' | grep -q '"UP"'; then
    echo "Keycloak is ready"
    exit 0
else
    echo "Keycloak is not ready"
    exit 1
fi
