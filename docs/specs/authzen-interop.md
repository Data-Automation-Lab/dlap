# DLAP AuthZEN 1.0 interop & conformance profile

> Companion to [enforcement-contract.md](enforcement-contract.md). That
> document is the normative DLAP profile; this one records **which OpenID
> AuthZEN Authorization API 1.0 features DLAP implements**, how a third-party
> AuthZEN PEP calls DLAP, and the conformance profile the automated test
> asserts. Descriptive, not normative - where the two disagree, the contract
> wins.

DLAP's Consumed-stage Policy Decision Point (PDP) speaks the
[OpenID AuthZEN Authorization API 1.0](https://openid.net/specs/authorization-api-1_0.html)
wire contract (final, OpenID Foundation, March 2026). Any AuthZEN 1.0 Policy
Enforcement Point (PEP) - an OPA/AuthZEN sidecar, an AuthZEN-capable API
gateway, an MCP gateway, or a hand-rolled interceptor - can obtain verdicts
from DLAP without DLAP-specific client code. The PDP is served by
`da-policyengine` (port 8083).

## 1. Endpoints implemented

| AuthZEN 1.0 endpoint | DLAP path | Status |
|---|---|---|
| Access Evaluation | `POST /access/v1/evaluation` | Implemented |
| Access Evaluations (boxcarring / batch) | `POST /access/v1/evaluations` | Implemented - results returned in request order under `evaluations` |

DLAP does **not** implement the AuthZEN discovery metadata document or the
Search APIs (Subject/Resource/Action search); see §4.

## 2. Conformance profile: features implemented vs omitted

### Implemented (AuthZEN 1.0 core)

- **Access Evaluation request**: the `subject` / `action` / `resource` /
  `context` object model (enforcement-contract.md §2). Unknown members are
  ignored per AuthZEN's open information model (`@JsonIgnoreProperties`), so a
  PEP MAY send additional fields DLAP does not read.
- **Access Evaluation response**: a JSON object with a boolean `decision`
  member (AuthZEN §5.2) plus a `context` object (§3). The DLAP `context`
  extension carries `id` (decision id / audit-join key), `reason_admin`
  (a language-tagged object, e.g. `{"en": "..."}`) naming the deciding policy
  or default, and `obligations` (DLAP extension - AuthZEN permits additional
  context members).
- **Boxcarring**: batch evaluation returns results **positionally aligned**
  with the request `evaluations` array (AuthZEN §6 ordering requirement). Each
  entry is evaluated and audited independently.
- **Default decision**: when no policy matches, the configured
  `dlap.pdp.default-decision` applies (ships `deny`; `allow` = observe mode).
  This is DLAP policy semantics layered on the AuthZEN boolean decision.

### Omitted / DLAP-specific (documented divergence)

- **Reason semantics**: AuthZEN carries `reason_admin` / `reason_user` maps.
  DLAP populates `reason_admin.en` only; `reason_user` is not emitted (the
  admin reason names the deciding policy for audit, not for end-user display).
- **Obligations shape**: AuthZEN 1.0 leaves obligation payloads
  implementation-defined. DLAP obligations are typed `{type, labels}` objects
  (`mask` / `redact_rows` / `log_only`; enforcement-contract.md §3). A PEP that
  cannot honour a returned obligation MUST fail closed (treat the decision as
  `false`).
- **Discovery metadata** (`/.well-known/authzen-configuration`) - not served;
  endpoint paths are fixed by this profile.
- **Search APIs** (Subject / Resource / Action search) - not implemented; DLAP
  is an evaluation PDP only.
- **Transport auth**: AuthZEN leaves PEP↔PDP authentication to the deployment.
  DLAP fixes it to Keycloak (§3): every endpoint is bearer-authenticated.

## 3. How a third-party PEP calls DLAP

### Auth handshake (ADR-0034)

DLAP endpoints are Keycloak-authenticated like every other DLAP endpoint. A
PEP authenticates as an **M2M service account** and must present a bearer token
carrying `ROLE_SERVICE_ACCOUNT` (or `ROLE_ADMIN`). The authorization outcomes
are:

| Caller | Result |
|---|---|
| No / invalid token | `401 Unauthorized` |
| Authenticated, lacks `ROLE_SERVICE_ACCOUNT` / `ROLE_ADMIN` | `403 Forbidden` |
| `ROLE_SERVICE_ACCOUNT` or `ROLE_ADMIN` | `200 OK` + AuthZEN verdict |
| Malformed / unparseable request body | `400 Bad Request` |

A PEP obtains its token via the standard OAuth2 client-credentials grant
against the DLAP realm, then sends `Authorization: Bearer <token>` on each
evaluation call.

### Example: OPA/AuthZEN sidecar or AuthZEN-capable gateway

A sidecar or gateway configured with the DLAP PDP as its AuthZEN backend maps
its intercepted request to the DLAP profile and POSTs it:

```http
POST /access/v1/evaluation HTTP/1.1
Host: policyengine.dlap.internal:8083
Authorization: Bearer <service-account-token>
Content-Type: application/json

{
  "subject":  { "type": "agent", "id": "svc-marketing-agent",
                "properties": { "roles": ["DATA_ANALYST"], "tier": "PRACTITIONER" } },
  "action":   { "name": "read" },
  "resource": { "type": "asset", "id": "9f6d…-asset-uuid",
                "properties": { "labels": ["PII", "EU_RESIDENT"] } },
  "context":  { "purpose": "campaign-analytics", "consent": true, "region": "eu-west1" }
}
```

```json
{
  "decision": true,
  "context": {
    "id": "dec-0191c2…",
    "reason_admin": { "en": "allowed by access-eu-marketing-aggregate" },
    "obligations": [ { "type": "mask", "labels": ["PII"] } ]
  }
}
```

The PEP enforces the boolean `decision` and applies any returned
`context.obligations` (masking/redaction) before releasing data. High-volume
PEPs SHOULD use the batch endpoint (`POST /access/v1/evaluations`) to amortise
round-trips; responses stay positionally aligned with the request.

Every verdict (allow or deny) also emits a decision-log event on
`dlap.access.decision` (enforcement-contract.md §6); the PEP does not see it,
but it is the audit spine the platform joins against enforcement-point access
logs.

## 4. Conformance test

The profile above is asserted by an automated conformance suite:

- `da-policyengine/src/test/java/com/dlap/policyengine/web/rest/AuthZenConformanceTest.java`

It is a deterministic `@WebMvcTest` slice (mocked `AccessDecisionService`; no
live Kafka or database) covering: boolean-`decision` shape, the `context.id` /
`reason_admin` / `obligations` extension shapes, default-deny serialisation,
batch request-order alignment, malformed-request → 400, and the
401/403/200 auth handshake.

Run it from the repo root:

```bash
./gradlew :da-policyengine:test --tests "*AuthZenConformance*"
```

Or together with the controller WebMvc slice:

```bash
./gradlew :da-policyengine:test --tests "*AuthZen*"
```
