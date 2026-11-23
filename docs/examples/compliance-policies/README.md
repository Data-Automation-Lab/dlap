# Example: compliance as control-policies (DLAP-native)

These are runnable `dlap/v1` policies that show the **primary** compliance path
(ADR-0035): DLAP evaluates compliance controls with **its own authoritative labels +
asset metadata** and the policy engine - no external scanner required. The pattern is
two-stage and fully label-driven:

1. **Detect**: a policy matches a control condition over classification labels
   (`data-class:pii`, applied by auto-label/manual) + asset metadata (region, last access)
   and **stamps a `compliance:<framework>:<status>` label** via an `addLabel` action.
2. **Enforce**: a second policy matches that compliance label and takes a lifecycle
   action (archive / delete / alert).

Because the verdict is a label in DLAP's catalog, it composes with everything else and
DLAP stays the single source of truth. An external CSPM/GRC tool (via
`ComplianceSignalProvider`) is an optional supplement that writes into the *same*
label - it does not replace this path.

| File | Control |
|---|---|
| `gdpr-residency.yaml` | GDPR data residency - PII outside the EU. |
| `hipaa-phi-encryption.yaml` | HIPAA - PHI not marked encrypted-at-rest. |
| `pii-retention.yaml` | PII past its retention window (stale). |
| `enforce-violations.yaml` | Enforcement - act on any stamped violation. |

Classification labels these rely on (`data-class:pii`, `data-class:phi`,
`encryption:enabled`, `retention:*`) come from auto-label (rules + LLM on business/technical
metadata) or manual tagging - the authoritative label sources. Load a policy by publishing
its DSL through the Policy service (or the visual builder, which emits the same DSL).
