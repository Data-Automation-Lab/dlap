# DLAP Label (spec)

> v1.0. Normative. Part of the DLAP Open Standard, beside the Asset Descriptor,
> Policy DSL, and Access Model. Labels are the governance vocabulary applied to
> assets; every lifecycle policy matches and acts on them.

## Model

A **Label** is a named, categorized governance tag. An **AssetLabelAssignment**
binds a label to an asset with provenance and (for automated assignments) a
confidence score. Both live in the shared `dlap_common` schema and are owned by
`da-protos` (the single source of truth - never duplicated in a service).

### Label

| Field | Type | Required | Notes |
|---|---|---|---|
| `labelId` | UUID | yes | Primary key. |
| `labelName` | string(255) | yes | **Unique.** The canonical name (e.g. `PII`, `PHI`, `LEGAL_HOLD`). |
| `labelCategory` | string(100) | no | Grouping (e.g. `sensitivity`, `retention`, `compliance`). |
| `description` | text | no | Human explanation of when the label applies. |
| `createdByUserId` | UUID | no | Author; null for system-seeded labels. |
| `createdTs` | instant | yes | Immutable. |
| `lastModifiedTs` | instant | yes | Updated on edit. |

### AssetLabelAssignment

| Field | Type | Required | Notes |
|---|---|---|---|
| `assetId` | UUID | yes | The labeled asset (Asset Descriptor). |
| `labelId` | UUID | yes | The applied label. |
| `source` | enum | yes | Provenance - see below. |
| `assignedByUserId` | UUID | conditional | Required when `source = USER`; must resolve to a `dlap_users` row (never a raw JWT subject). Null for automated sources. |
| `assignedTs` | instant | yes | Immutable. |
| `confidenceScore` | double | conditional | 0.0-1.0. Present for ML sources; null for deterministic ones. |

The `(assetId, labelId)` pair is unique - a label is applied to an asset at most once.

## Assignment source (provenance)

`source` is one of the canonical values in `DlapConstants.AssignmentSource`;
the `dlap_common` check constraint `ck_assignment_source_valid` enforces exactly
this set:

| Source | Meaning | Confidence |
|---|---|---|
| `USER` | A human applied it via the UI. | none |
| `SYSTEM` | Applied by a platform process (seed/migration). | none |
| `SMART_LABEL_ML` | The auto-label LLM/ML pipeline inferred it. | required (0-1) |
| `SMART_LABEL_RULE` | A deterministic rule matched. | optional |
| `POLICY_ENGINE` | A policy `addLabel` action applied it. | none |

Provenance is normative: consumers (policy evaluation, reporting, audit) treat a
`USER` or `POLICY_ENGINE` label as authoritative and an `SMART_LABEL_ML` label as
advisory below a confidence threshold.

## Lifecycle & events

- Applying/removing a label is an auditable event. A `POLICY_ENGINE` assignment
  originates from a policy `addLabel`/`removeLabel` action (see the Policy DSL spec).
- Labels are a first-class match dimension in the Policy DSL `spec.match.labels`
  clause (`anyOf` / `noneOf` / `allOf`).
- The Access Model governs who may assign: Practitioner and above may add/remove
  labels; the Reader tier may not (see the Access Model spec).

## Conformance

An implementation conforms when: (a) `labelName` is globally unique, (b) every
assignment carries one of the five canonical `source` values, (c) a `USER`
assignment resolves its actor to a real user id, (d) ML assignments carry a
confidence score, and (e) the `(assetId, labelId)` pair is unique.
