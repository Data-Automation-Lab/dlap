# DLAP Open Standard: spec set

DLAP (Data Lifecycle Automation Platform) is an open standard for end-to-end data
lifecycle management, both **cloud-neutral** and **log-enriched**: truth about assets,
owners, and lineage is reconstructed from audit + application logs, not read from
the tools that emit them.

These specs are the normative contracts an implementation must satisfy. They are
versioned and evolve by ADR (see `docs/DECISIONS.md`).

## Data model

| Spec | What it defines |
|---|---|
| [Asset Descriptor](asset-descriptor.md) | The canonical, cloud-neutral representation of a data asset - the join key (`assetUri`) everything else references. Includes log-harvested owner/team and derived lineage. |
| [Cloud Resource](cloud-resource.md) | The provider-side resource model discovery emits, before normalization into an Asset. |
| [Label](label.md) | The governance vocabulary applied to assets, with provenance (USER / SYSTEM / SMART_LABEL_ML / SMART_LABEL_RULE / POLICY_ENGINE) and confidence. |
| [Lifecycle State](lifecycle-state.md) | v0.1. A small, vendor-neutral JSON record of one asset's lifecycle state (created, last read, last written, read coverage, size, retention, legal hold, disposition), each value tied to the log events that produced it. Derived from the Asset Descriptor; JSON Schema, examples, and mappings to OpenLineage, Open Knowledge Format, AuthZEN, Unity Catalog tags and Knowledge Catalog aspects. |
| [Asset ID](asset-id.md) | The canonical asset identifier (ADR-0047): one URI per resource (`s3://`, `gs://`, `bq://`, `az://`, `uc://`, the AWS ARN, the Azure ARM ID), every alias that normalizes to it, and the OpenLineage mapping. `assetUri` holds this form. |

## Events

| Spec | What it defines |
|---|---|
| [Log Event](log-event.md) | The normalized `dlap/v1` log event - the log-enriched spine. Carries `principal` (owner), `targetResource`, `changeType`, and the raw event from which lineage is derived. |

## Governance

| Spec | What it defines |
|---|---|
| [Policy DSL](policy-dsl.md) | The `dlap/v1` YAML policy language - triggers, match clauses, and lifecycle actions. The single canonical, evaluatable policy representation (ADR-0031). |
| [Access Model](access-model.md) | The normative 4-tier persona RBAC model (Executive / Leadership / Practitioner / Reader) over 10 canonical roles (ADR-0032). |
| [Enforcement Contract](enforcement-contract.md) | The Consumed-stage access-decision contract: DLAP as an OpenID AuthZEN 1.0 Policy Decision Point (PDP), the `kind: AccessPolicy` model, obligation semantics, and the decision-log event (ADR-0040). See also the [AuthZEN interop & conformance profile](authzen-interop.md). |

## How they fit together

Discovery + log ingestion produce **Log Events** and **Cloud Resources**, which
normalize into **Asset Descriptors**. Log Events also harvest each asset's owner/
team and derive **lineage** edges between assets. **Labels** classify assets;
**Policy DSL** matches assets by type/label/age/size and fires lifecycle actions;
the **Access Model** governs who may author policies, apply labels, and act.
Lineage + harvested metadata are the substrate the policy engine and agentic
capabilities reason over.
