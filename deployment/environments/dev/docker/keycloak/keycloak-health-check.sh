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


# Keycloak Health Check Script
# This script checks if Keycloak is ready to accept requests

# Get the HTTP port from environment variable or default to 8080
HTTP_PORT=${KC_HTTP_PORT:-8080}

# Check if Keycloak health endpoint is responding
if curl -f -s "http://localhost:${HTTP_PORT}/health/ready" > /dev/null 2>&1; then
    echo "Keycloak is ready"
    exit 0
else
    echo "Keycloak is not ready"
    exit 1
fi 