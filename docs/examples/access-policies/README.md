# Example: AccessPolicies for the Consumed stage

Runnable `dlap/v1` `kind: AccessPolicy` documents that drive the AuthZEN PDP
(ADR-0040). Unlike a `LifecyclePolicy` - which fires on schedule/event to act on
an asset (archive, delete, label) - an `AccessPolicy` is evaluated **per request,
at the moment an agent reads data**, and returns a verdict plus obligations.
Enforcement points (an MCP gateway, an OPA sidecar, Keycloak) obtain that verdict
over the OpenID AuthZEN 1.0 contract in `docs/specs/enforcement-contract.md`.

## How they load

The PDP loads every `ENABLED` policy whose document is `kind: AccessPolicy`
(`AccessDecisionService.refresh()`), refreshed immediately on any policy change
(`PolicyChangedEvent`, after commit) and on a 30s backstop. Create one through the
policy API/UI (raw editor accepts the YAML directly) or seed it, then it enforces.

## Combining algorithm

Explicit `deny` overrides any `allow`; obligations from all contributing allows
union (most restrictive wins); if nothing matches, the configured default applies
(`dlap.pdp.default-decision`, ships `deny`).

| File | Effect | Shows |
|---|---|---|
| `eu-marketing-aggregate.yaml` | allow + mask | subjects (tier OR role), actions, `match` on labels, `condition` (purpose + consent), a `mask` obligation on PII. |
| `deny-cross-region-export.yaml` | deny | a hard floor no team-level allow can lift. |
| `deny-stale-data.yaml` | deny | `lastAccessedAt` on the catalog's event time; unknown age or incomplete read coverage is denied too (POL-01). |

## Try it (spec §2)

With the dev stack up, a service-account token, and one of these seeded:

```
POST /access/v1/evaluation
{"subject":{"type":"agent","id":"svc-mktg","properties":{"roles":["DATA_ANALYST"]}},
 "action":{"name":"read"},
 "resource":{"type":"asset","properties":{"labels":["EU_RESIDENT"]}},
 "context":{"purpose":"campaign-analytics","consent":true}}
```

→ `decision:true` with a `mask` obligation on `PII`. Drop `consent`, or set
`action:"export"` against an `EU_RESIDENT`+`PII` asset → `decision:false`.
`e2e-tests/authzen-pdp-verify.ps1` runs this end to end.
