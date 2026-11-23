# Chief Data Officer (CDO) Guide

This guide describes what a user with the `ROLE_CDO` realm role can see and do in
DLAP **as implemented at commit `7984d1d`** (`feature/deployment`). Where a figure is
an estimate, or a feature does not exist, the text says so. Source references are
repository paths. UI files live in the `new-ui` submodule under `frontend/src/`.

Read [../architecture/overview.md](../architecture/overview.md) (section "Status and
limits") first: DLAP is pre-release, single-tenant, and has no certification or SLA.

An earlier revision of this guide described dashboards, cost tables, compliance
percentages, BI connectors, SMS alerts and keyboard shortcuts that the code does not
contain. They were removed. Anything not listed below is not shipped.

---

## 1. What the CDO role can reach

The CDO sits in the Executive tier of the access model (ADR-0032). The normative
persona to permission matrix is [../specs/access-model.md](../specs/access-model.md).
The endpoints below were checked against their `@PreAuthorize` annotations.

| Capability | Where | Endpoint and gate |
|---|---|---|
| Governance score | CDO home, Analytics | `GET /api/v1/reporting/dashboards/cdo/governance-score`: ADMIN, CDO, DIRECTOR (`DashboardController.java:70-71`) |
| Cost savings (estimate) | CDO home, Analytics | `GET .../dashboards/cdo/cost-savings`: ADMIN, CDO, DIRECTOR (`DashboardController.java:99-100`) |
| Action items | CDO home, Analytics | `GET .../dashboards/cdo/action-items`: ADMIN, CDO, DIRECTOR, DATA_ENGINEER, DATA_SCIENTIST (`DashboardController.java:131-132`) |
| Refresh dashboard cache | API only | `GET .../dashboards/cache/refresh`: ADMIN, CDO (`DashboardController.java:168-169`) |
| Access-decision drift findings | API only | `GET /api/v1/reporting/drift/findings`: ADMIN, CDO, AUDITOR (`DriftFindingController.java:86-87`) |
| List users and roles (read only) | User Management | `GET /api/v1/admin/users`: ADMIN, CDO, DIRECTOR. Create, update, delete and role assignment are ADMIN only (`UserController.java:42,48`) |
| Read report definitions and generated reports | API | any authenticated user (`ReportController.java:70,76,82,123,130,136`) |
| Export a generated report as CSV | API | any authenticated user (`ReportExportController.java:56-57`) |
| Generate a report | API | ADMIN or DATA_ANALYST only. **CDO cannot trigger report generation** (`ReportController.java:102-103,113-114`) |

On sign-in, a user holding `ROLE_CDO` is routed to `/home-cdo`
(`App.tsx:154-155`). The sidebar offers Home, Dashboard, Data Estate, Data Policy
Center, Analytics, Event Center and the Smart Agents section (Discovery, Label,
Compliance, Archival, Deletion) (`ui/layouts/DefaultPageLayout.tsx`).

---

## 2. The CDO home page

`pages/HomePageCdo.tsx` shows five KPI cards, an action-item list, quick actions and
a recent policy activity table. All of them call live endpoints. If a call fails the
page shows an error state instead of invented numbers (`HomePageCdo.tsx:75-112`).

| Card | Source | What the number is |
|---|---|---|
| Governance Score | governance-score endpoint | Weighted score, section 3 |
| Active Policies | `GET /api/v1/policyengine/policies` | Count of policies whose status is `ENABLED` (`HomePageCdo.tsx:87`) |
| Cost Savings | cost-savings endpoint | **An estimate**, section 4 |
| Compliance Alerts | action-items endpoint | Count of items of type `COMPLIANCE_VIOLATION`, with the HIGH-priority share (`HomePageCdo.tsx:90-91`) |
| Data Lineage Coverage | governance-score endpoint | Share of assets with lineage, as defined in section 3 |

"Recent policy activity" is derived from job-status rows of services whose name
contains "policy" (`HomePageCdo.tsx:168-199`). It is job status, not a policy audit
log.

### Known gap: UI and API field names disagree

By reading the code (not by running a browser), the UI types and the reporting DTOs
use different field names:

- The UI reads `breakdown.policyScore` and `breakdown.lineageScore`
  (`new-ui/frontend/src/utils/apiServices.ts:33-39`). The API returns
  `policyComplianceScore` and `lineageCoverageScore`
  (`GovernanceScoreDTO.java:111,135`).
- The UI reads `items[].priority` and `items[].type`
  (`apiServices.ts:86-103`). The API returns `actionItems[].severity` and
  `category` (`ActionItemsDTO.java:57`, `DashboardService.java:399-413`).

Until the contract is reconciled, expect some cards to show empty or error states
even when the services are healthy. It has been raised with the project owner as
an open question and is not yet fixed.

---

## 3. Governance score

Computed by `DashboardService.getGovernanceScore` (`DashboardService.java:92-238`) from
counts supplied by `da-catalog` and `da-policyengine`:

```
overall = 0.30 * policy compliance
        + 0.25 * classification coverage
        + 0.25 * metadata completeness
        + 0.20 * lineage coverage          (DashboardService.java:164-167)
```

| Component | Definition |
|---|---|
| Policy compliance | PASS / (PASS + FAIL) of stored policy evaluations, from `da-policyengine` (`PolicyEngineMetricsService.java:66-74`) |
| Classification coverage | Share of assets with at least one label assignment, of any label and any provenance (`AssetService.java:1520-1522`). In the quickstart this includes the random labels from the mock provider. |
| Metadata completeness | Share of assets that have any metadata at all (`AssetService.java:1525-1527`). It does not measure completeness against a schema. |
| Lineage coverage | Share of assets that appear in the lineage graph, or carry embedded lineage metadata, whichever count is larger (`AssetService.java:1535-1539`) |

Read these caveats before quoting the score:

- **An empty install scores high on policy compliance.** With zero evaluations the
  policy engine returns 100.0 (`PolicyEngineMetricsService.java:70-72`).
- **A placeholder replaces the compliance rate when the policy engine is
  unreachable.** The constant is 92.0 (`DashboardService.java:58,103`), and the
  response carries a `lastError` saying so (`DashboardService.java:220-223`).
- **The 30-day trend is synthetic.** It is three points derived from the current
  score (minus 5, minus 2, and the score itself); no history is stored
  (`DashboardService.java:169-186`).
- **Benchmark numbers are placeholders.** Industry 72.5 and peer 76.8 are constants,
  and the response sets `isEstimate=true` (`DashboardService.java:65,72,191-202`).
  There is no benchmarking data source.
- Scores are cached (`@Cacheable("governanceScore")`) and the response states a
  freshness of 5 minutes as a fixed value (`DashboardService.java:234`), not a
  measured one.

Do not use the trend or the benchmark in an executive report.

---

## 4. Cost savings

`DashboardService.getCostSavings` (`DashboardService.java:241-376`) does not read any
cloud bill. DLAP has no billing integration; the DTO states that figures are
per-asset estimates (`CostSavingsDTO.java:96-102`) and the response sets
`isEstimate=true` with `dataAccuracy="ESTIMATED_FROM_CATALOG_COUNTS"`
(`DashboardService.java:370-371`). The model is:

| Term | Value |
|---|---|
| Archival savings | 195 USD per asset with status `ARCHIVED` (`DashboardService.java:79`) |
| Deletion savings | 260 USD per asset with status `PENDING_DELETION` (`DashboardService.java:86`) |
| Tier optimization | 12.50 USD per active asset (`DashboardService.java:265-267`) |
| Platform cost deducted | fixed 5,200.00 USD (`DashboardService.java:261`) |

The per-provider split (AWS 45 percent, GCP 35 percent, Azure 20 percent) is a fixed
fraction of three different savings terms, not a measurement per provider
(`DashboardService.java:288-309`). The cost trend is also synthetic
(`DashboardService.java:328-352`). Treat the page as an order-of-magnitude
illustration of what archival and deletion policies could save, not as a financial
statement.

---

## 5. Compliance

DLAP is a lifecycle orchestrator, not a compliance scanner, and it does not certify
anything ([../specs/compliance-labels.md](../specs/compliance-labels.md), ADR-0035).

What exists:

- **Three catalog-backed controls** in `da-autocompliance`: `IAM-DRIFT-001` (public
  exposure markers), `PII-ENCRYPTION-001` (sensitive label without an encryption
  marker) and `GDPR-RESIDENCY-001` (asset outside the allowed regions, default EU)
  (`AssetComplianceEvaluator.java:69-71`). For any other control id the evaluator
  returns nothing instead of inventing a result (`AssetComplianceEvaluator.java:115`);
  `ComplianceControlServiceImpl` then falls back to looking up findings that were
  already stored (`ComplianceControlServiceImpl.java:186-211`). The native
  evaluators for the remaining frameworks are not built (Q-017, ADR-0035).
- **Posture ingestion** from a customer's own compliance tool, projected onto asset
  labels such as `compliance:<framework>:<status>`, through the
  `ComplianceSignalProvider` SPI. The default provider emits no signals
  ([../specs/compliance-labels.md](../specs/compliance-labels.md)).
- **Smart Compliance** page (`pages/ComplianceAgentView.tsx`) listing findings and
  violations from `da-autocompliance`.
- **Weekly score fields** per framework name (HIPAA, GDPR, SOC) in
  `ComplianceTrendDTO` (`ComplianceKpiServiceImpl.java:160-196`). Each one is the
  share of COMPLIANT findings in that week's stored reports whose report type name
  contains the framework name, and 0 when there are none
  (`ComplianceKpiServiceImpl.java:389-412`). It is not an assessment against the
  regulation, and it is not evidence for an audit.

What does not exist: framework certification, a regulatory-change feed, consent
management, automated filing, or controls beyond the three above. The rest of the
framework list is roadmap.

---

## 6. Reporting and export

- **Analytics** (`pages/ReportingInsightsHub.tsx`) loads the same three dashboard
  endpoints and offers **Export CSV** for the cost, governance and action-item views.
  The CSV is built in the browser from the loaded data
  (`ReportingInsightsHub.tsx:53-66,130-175`). Cost figures carry an "Estimated"
  badge.
- **Report definitions and generated reports** are served by `da-reporting`
  (`ReportController.java`). Generated findings download as CSV through
  `GET /api/v1/reporting/exports/csv/{generatedReportId}`
  (`ReportExportController.java:56`). Report generation is limited to ADMIN and
  DATA_ANALYST (section 1).
- **Not available:** Tableau or Power BI connectors (no code references either),
  scheduled executive digests, SQL custom queries, predictive analytics, anomaly
  detection. REST endpoints are the only programmatic access, and they require a
  Keycloak token.

---

## 7. Notifications

`da-reporting` can send **email** (Spring Mail over SMTP) and **Slack** messages
(`NotificationChannel.java`, `EmailNotificationProvider.java`,
`SlackNotificationProvider.java`). Each user stores channel preferences through
`/api/v1/reporting/notifications/preferences` (`NotificationController.java:63-92`).
Both channels need operator configuration: SMTP settings and a Slack bot token
(`da-reporting/src/main/resources/application.properties:120-129`).

**Policy alerts are not wired end to end.** The only trigger for a send is a policy
action event whose `actionType` starts with `NOTIFY` (`PolicyActionConsumer.java:72`),
and it reaches only users who saved a preference for the matching notification type
key (`NotificationService.java:143-147`). The DSL actions `alert.email`,
`alert.slack` and `alert.webhook` are recorded but nothing sends them
([../specs/policy-dsl.md](../specs/policy-dsl.md), "Implementation status").
Do not promise a stakeholder that a policy alert will reach a mailbox or a Slack
channel until you have verified it in your deployment.

There is no SMS sender, and no webhook sender exists in the repository. In the UI,
the CDO home shows a notifications dropdown that lists the same action items; it is
not a separate notification service.

The **Event Center** page is a platform activity feed served by `da-policyengine`.

---

## 8. Users and roles

The User Management page lists users and roles for CDO, DIRECTOR and ADMIN. Only ADMIN
can create, change, delete or assign roles (`UserController.java:42,90-140`, and
[../specs/access-model.md](../specs/access-model.md)). Cloud credentials are never
entered in the UI; connections are provisioned server-side (Q-023 in the access model
notes).

---

## 9. Where to go next

- [../getting-started.md](../getting-started.md): bring the quickstart stack up.
- [../architecture/overview.md](../architecture/overview.md): services, the Kafka
  spine, the coverage table per cloud.
- [../specs/access-model.md](../specs/access-model.md): the role matrix.
- [../api/README.md](../api/README.md): endpoint reference.
