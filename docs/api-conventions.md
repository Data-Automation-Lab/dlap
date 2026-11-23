<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->

# DLAP API Conventions

Platform-wide conventions for the DLAP HTTP APIs. Each backend service is an
independent Spring Boot 3.x resource server; these conventions keep their
surfaces consistent so a client that speaks to one service speaks to all of
them.

Where a service deviates, the deviation is documented on the controller (the
AuthZEN PDP and the MCP PEP are the notable exceptions - see
[Base paths](#base-paths--versioning)).

## Base paths & versioning

Business APIs are versioned in the path under `/api/v1/<domain>`:

| Service | Port | Base path |
|---|---|---|
Ports are the **host-published** ports from the canonical dev quickstart
(`deployment/environments/dev/docker-compose.microservices.yml`); inside the
container network most services listen on `8080`.

| Service | Port | Base path |
|---|---|---|
| da-catalog | 8082 | `/api/v1/catalog` |
| da-discovery | 8091* | `/api/v1/discovery` |
| da-policyengine | 8083 | `/api/v1/policyengine` |
| da-reporting | 8084 | `/api/v1/reporting` |
| da-autolabel | 8085 | `/api/v1/autolabel` |
| da-autoarchival | 8086 | `/api/v1/archival` |
| da-autodelete | 8087 | `/api/v1/deletion` |
| da-autocompliance | 8088 | `/api/v1/compliance` |
| da-admin-service | 8089 | `/api/v1/admin` |
| da-logingest | 8090 | `/api/v1/logs` |

\* Host `8081` is the nginx API gateway; `da-discovery` is remapped to host
`8091` in the microservices compose.

Several services expose more than one controller root under their prefix (for
example `da-autoarchival` serves `/api/v1/archival/tasks`, `/config` and
`/overview`). Two controllers sit outside their service's nominal prefix:
`da-autodelete` also serves `/api/v1/delete-agent/dashboard`, and `da-autolabel`
also serves `/api/v1/labeling` and `/api/v1/label-agent`. Treat the generated
OpenAPI document as authoritative for the full endpoint list.

Two Consumed-stage surfaces follow their own spec-mandated prefixes rather than
`/api/v1`, because they implement external contracts:

- **AuthZEN PDP** (da-policyengine): `POST /access/v1/evaluation` and
  `POST /access/v1/evaluations` - the AuthZEN 1.0 evaluation contract
  (ADR-0040, `docs/specs/enforcement-contract.md`).
- **MCP PEP** (da-mcp-pep): `POST /mcp/v1/authorize` - the reference
  enforcement surface (`McpPepController.java:57,67`). Not a Keycloak-gated
  business API; it authenticates its own callers at the MCP transport boundary.
  It is **not** in the dev compose: it is a standalone reference PEP whose own
  default is `server.port=8091`
  (`da-mcp-pep/src/main/resources/application.properties`), which collides with
  the compose's host mapping for `da-discovery` - override it if you run both
  on one host.

Versioning is major-only and in the path. A breaking change bumps the path
segment (`/api/v2/...`); additive changes stay on `v1`.

## Authentication & authorization

- **Transport:** Keycloak-issued OAuth2 / OIDC JWT bearer tokens. Every
  business service is a `spring-boot-starter-oauth2-resource-server` and
  validates the JWT against the Keycloak realm.
- **Roles:** JWT realm/client roles are mapped to Spring authorities in each
  service's `SecurityConfiguration.java` (ten role constants in
  `DlapConstants.UserRole` - nine human personas plus `SERVICE_ACCOUNT`).
  Controller methods enforce with `@PreAuthorize` (e.g.
  `hasAnyRole('ADMIN','CDO','AUDITOR')`).
- **Service-to-service:** machine callers present `ROLE_SERVICE_ACCOUNT`
  (ADR-0034). The AuthZEN PDP endpoints admit `ROLE_SERVICE_ACCOUNT` and
  `ROLE_ADMIN`.
- **Exempt paths:** `/actuator/health` and the Swagger/OpenAPI paths are open;
  everything else requires authentication.

## Standard error shape

Every service registers a `@RestControllerAdvice GlobalExceptionHandler` that
renders a consistent JSON envelope (RFC-7807-style). Stack traces are never
leaked to clients.

```json
{
  "timestamp": "2026-07-07T12:34:56.789Z",
  "status": 400,
  "error": "Bad Request",
  "message": "size must be less than or equal to 200",
  "path": "/api/v1/reporting/drift/findings"
}
```

Bean-validation failures additionally include a `fieldErrors` map
(`field -> message`).

Status mapping:

| Condition | Status |
|---|---|
| `MethodArgumentNotValidException`, `ConstraintViolationException`, `IllegalArgumentException` | 400 |
| Missing / invalid JWT | 401 |
| `AccessDeniedException` (`@PreAuthorize` denial) | 403 |
| `ResourceNotFoundException` / `NoSuchElementException` | 404 |
| Any unmapped exception | 500 (generic message, details logged server-side) |

## Pagination

List endpoints that can return large result sets accept `page` and `size`
query parameters and return a Spring `Page<T>`:

- `page` - zero-based page index (default `0`).
- `size` - page size (per-endpoint default, e.g. `50`), capped at a
  **maximum page size of 200**. A larger `size` is clamped to 200; a
  non-positive `size` falls back to the endpoint default.
- Response body: `content` (the array) plus `totalElements`, `totalPages`,
  `number`, and `size`.

Reference implementation: `DriftFindingController#listFindings`
(`GET /api/v1/reporting/drift/findings`). Broader pagination rollout across
list endpoints is tracked in `docs/OPEN_QUESTIONS.md` (Q-029).

## Advisory rate-limit headers

The MCP PEP (da-mcp-pep) is the reference throttle point and can emit
**advisory** rate-limit headers on every response:

| Header | Meaning |
|---|---|
| `X-RateLimit-Limit` | Per-window request ceiling |
| `X-RateLimit-Remaining` | Requests left in the current window (floored at 0) |
| `X-RateLimit-Reset` | Epoch-second at which the window resets |

Config: `dlap.mcppep.ratelimit.enabled` (default `false`),
`dlap.mcppep.ratelimit.limit` (default `600` per 60-second window),
`dlap.mcppep.ratelimit.client-header` (default `X-Client-Id`, falls back to the
remote address).

These headers are **advisory**: the reference filter informs clients but does
not reject over-limit requests (no `429`). The counter is an in-memory
fixed-window map - process-local, not shared across replicas, and reset on
restart. A production deployment replaces the in-memory window with a shared
store (Redis / bucket4j) and, where hard throttling is required, enforces `429`
at the transport edge.

## OpenAPI / Swagger UI

Every service depends on `springdoc-openapi-starter-webmvc-ui` (added to all
modules by the root build). Controllers carry `@Tag`, `@Operation`, and
`@ApiResponses` annotations. With springdoc defaults (no path override), each
service serves:

- **Swagger UI:** `/swagger-ui.html` (redirects to `/swagger-ui/index.html`)
- **OpenAPI JSON:** `/v3/api-docs`

e.g. the catalog service UI is at `http://localhost:8082/swagger-ui.html`.
