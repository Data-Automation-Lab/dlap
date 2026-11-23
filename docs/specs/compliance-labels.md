# DLAP Spec: Compliance via Labels

Status: Draft · Applies to `dlap/v1` · See [ADR-0035](../DECISIONS.md)

## Principle

DLAP is a **data-lifecycle orchestrator, not a compliance scanner.** It does not
re-implement GDPR / HIPAA / SOC 2 / PCI-DSS control evaluators - that is a mature,
crowded category (AWS Config, GCP Security Command Center, Wiz, Prisma Cloud,
OneTrust, ServiceNow GRC, …). Instead DLAP **ingests compliance posture from the
customer's existing tool and projects it onto asset labels**, and its Policy DSL
enforces lifecycle actions on those labels. Labels are the carrier; the policy
engine is the enforcer.

```
external compliance tool ──(ComplianceSignalProvider)──▶ ComplianceSignal
   └─ ComplianceLabelMapper ─▶ compliance:<framework>:<status> label
        └─ ComplianceLabelSink ─▶ asset label (catalog)
             └─ Policy DSL  spec.match.labels / tagMatches ─▶ archive / delete / alert
```

## Two pluggable points (SPI)

Both mirror the platform's other cloud-neutral SPIs (`ILLMClient`,
`ExternalCatalogIntegration`); a deployment supplies a bean and the defaults yield.

| SPI | Role | Default |
|---|---|---|
| `ComplianceSignalProvider` | **source**: pull `ComplianceSignal`s from the customer's tool | `NoopComplianceSignalProvider` (no signals); reference impls: `File`, `Http` (see below) |
| `ComplianceLabelSink` | **sink**: write a compliance label onto a catalog asset | `LoggingComplianceLabelSink` (logs, no write) |

`ComplianceSignal(assetUri, framework, controlId, status, detail)` where `status ∈
{COMPLIANT, VIOLATION, UNKNOWN}`. `assetUri` is the canonical join key (see the
Asset Descriptor spec).

## Label taxonomy

`ComplianceLabelMapper` produces a canonical, policy-matchable label name:

```
compliance:<framework>:<status>
```

- `<framework>` and `<status>` are lower-cased and sanitised to `[a-z0-9-]`.
- `UNKNOWN` signals map to **no** label - DLAP stamps only a definite posture.
- Examples: `compliance:gdpr:violation`, `compliance:hipaa:compliant`,
  `compliance:pci-dss:violation`, `compliance:soc-2:violation`.

Companion data-classification labels (applied by discovery/auto-label or the same
sink) compose naturally: `data-class:pii`, `data-class:phi`, `data-class:pci`.

## Enforcement (already supported by the Policy DSL)

The **primary** compliance path is DLAP-native (ADR-0035 reconciliation): *control-policies*
express compliance controls over DLAP's own labels + asset metadata, stamp a
`compliance:<framework>:<status>` label via an `addLabel` action, and drive lifecycle
actions. Runnable examples: [`docs/examples/compliance-policies/`](../examples/compliance-policies/).
The external `ComplianceSignalProvider` is an optional supplement that writes into the same
labels. Both write through the authoritative `addLabel` pipeline (da-autolabel).

- **`labels`** clause - set membership (`anyOf` / `noneOf` / `allOf`) over DLAP labels.
- **`tagMatches`** clause - map-equality over cloud-native tags (`env: production`).

Example: archive any asset flagged as a GDPR violation, and delete PII flagged as a GDPR
violation (each document is a separate `kind: Policy`, see [policy-dsl.md](policy-dsl.md)):

```yaml
apiVersion: dlap/v1
kind: Policy
metadata:
  id: archive-gdpr-violations
spec:
  triggers:
    - type: event
      topic: dlap.catalog.asset.upserted
  match:
    labels: { anyOf: [ "compliance:gdpr:violation" ] }
  action:
    type: archive
    requireApproval: true
---
apiVersion: dlap/v1
kind: Policy
metadata:
  id: delete-pii-gdpr-violations
spec:
  triggers:
    - type: schedule
      cron: "0 0 3 * * ?"
  match:
    labels: { allOf: [ "data-class:pii", "compliance:gdpr:violation" ] }
  action:
    type: delete
    dryRun: false
```

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `dlap.compliance.signal-sync.enabled` | `false` | Enable periodic ingestion of external compliance signals. |
| `dlap.compliance.signal-sync.interval-ms` | `900000` | Sync interval when enabled. |
| `dlap.compliance.signal-provider` | *(unset → no-op)* | Select a reference provider: `file` or `http`. |
| `dlap.compliance.signal-provider.file.path` | - | JSON signals file (when `=file`). |
| `dlap.compliance.signal-provider.http.url` | - | REST endpoint returning the JSON signals array (when `=http`). |

Two cloud-neutral reference providers ship - `FileComplianceSignalProvider` ("bring your
export") and `HttpComplianceSignalProvider` (pull from your GRC tool). Runnable example +
"write your own" guide: [`docs/examples/compliance-signals/`](../examples/compliance-signals/).
Without any provider, DLAP runs on the built-in findings roll-up (informational) - the
zero-config default. Writing labels also requires a catalog-backed `ComplianceLabelSink`
(the default `CatalogEventComplianceLabelSink`).

## Why this over native evaluators

- Native GDPR/HIPAA/SOC2/PCI evaluators are large and would perpetually trail the
  dedicated tools, and require security config DLAP does not harvest.
- Label projection is **incremental, cloud-neutral, and plays to DLAP's strengths**
  (catalog + labels + lifecycle policy). New frameworks/tools plug in without
  touching the aggregation or the policy engine.
