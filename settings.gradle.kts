/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

rootProject.name = "microservices-dlap"

// Include all microservices and the new protos project
include("da-protos")
// Shared LLM provider abstraction (ADR-0044) - library, not a service
include("da-llm-client")
include("da-discovery")
include("da-catalog")
include("da-policyengine")
include("da-reporting")
include("da-autolabel")
include("da-autoarchival")
include("da-autodelete")
include("da-autocompliance")
include("da-admin-service")
include("da-logingest")
include("da-mcp-pep")
// Test-support fixture corpus and its provenance gate (FIX-01); never a runtime dependency
include("da-log-fixtures")
include("new-ui")

// Potentially include ot