# DLAP Access Model (spec)

> v1.0 (ADR-0032). Normative. The persona → role → permission matrix for the
> DLAP Open Standard. Keycloak is the single source of truth for role names;
> services map the JWT `roles` claim to Spring authorities with a `ROLE_` prefix.

## Tiers

DLAP defines **four persona tiers**. Authorization widens from Reader (narrowest)
to Executive (broadest); a tier is expected to hold at least the read authority of
the tiers below it.

| Tier | Roles | Intent |
|---|---|---|
| **Executive** | `ADMIN`, `CDO` | Own the platform / the governance mandate. Full control (ADMIN) or full read + governance sign-off (CDO). |
| **Leadership** | `DIRECTOR`, `POLICY_MANAGER` | Set and approve policy; oversee a domain. Author/approve policies; read across the estate. |
| **Practitioner** | `DATA_ENGINEER`, `DATA_SCIENTIST` | Do the lifecycle work - connect sources, run discovery/labeling, act on assets. |
| **Reader** | `DATA_ANALYST`, `AUDITOR`, `VIEWER` | Observe. Read catalog, reports, events, audit trail; no mutation. |

The 10 role names are the canonical set in `DlapConstants.UserRole` and the
Keycloak realm. There are no other roles; `USER`, `DATA_STEWARD`,
`COMPLIANCE_OFFICER`, `ANALYST`, `SYSTEM`, and `VP` are **not** DLAP roles.

## Permission matrix

Capabilities by area. ✅ = allowed, 👁 = read-only, - = denied. "Any" means any of
the 10 roles (authenticated).

| Capability | Executive (ADMIN / CDO) | Leadership (DIRECTOR / POLICY_MANAGER) | Practitioner (DATA_ENGINEER / DATA_SCIENTIST) | Reader (DATA_ANALYST / AUDITOR / VIEWER) |
|---|---|---|---|---|
| **Data Estate**: browse assets, labels, lineage | 👁/✅ | 👁 | 👁 | 👁 |
| **Labels**: add/remove asset labels | ✅ | ✅ | ✅ | - |
| **Discovery**: run scans, edit discovery config | ✅ | 👁 | ✅ | 👁 |
| **Cloud connections**: provisioned server-side | ADMIN ✅ / CDO 👁 | 👁 | - | - |
| **Policy**: author / edit / publish | ✅ | ✅ | - | 👁 |
| **Policy**: enable / disable | ✅ | ✅ | - | - |
| **Lifecycle actions**: archive / delete / restore | ✅ | ✅ | ✅ | - |
| **Compliance**: view findings | 👁/✅ | 👁 | 👁 | 👁 (AUDITOR) |
| **Compliance**: add/edit controls | ✅ | ✅ | - | - |
| **Reports & dashboards** | 👁/✅ | 👁 | 👁 | 👁 |
| **User management**: list users/roles | ✅ | 👁 (DIRECTOR); CDO 👁 | - | - |
| **User management**: create/update/delete/assign | ADMIN ✅ | - | - | - |
| **System / service-to-service** | ADMIN | - | - | - |

Notes:
- **CDO / Director read-only user management** (ADR-0032): they may *list* users and
  roles for oversight; all mutation stays `ADMIN`-only.
- **Cloud credentials are never entered in the UI** (Q-023): connections are
  provisioned server-side; Executive sees status, nobody edits secrets in-app.

## Enforcement

- Each service's `SecurityConfiguration` maps the JWT `realm_access.roles` (or the
  top-level `roles` claim) to `ROLE_*` Spring authorities.
- Controllers guard every method with `@PreAuthorize("hasAnyAuthority('ROLE_…')")`
  naming only canonical roles. Unprefixed `hasAnyRole('X')` is equivalent to
  `hasAnyAuthority('ROLE_X')` but the `hasAnyAuthority('ROLE_…')` form is preferred
  for consistency.
- `/actuator/health` and Swagger are unauthenticated by policy (CLAUDE.md §9).

## Conformance

An implementation conforms to the DLAP Access Model when: (a) it recognizes exactly
these 10 role names, (b) every mutating endpoint denies the Reader tier, (c) policy
authoring/enable is limited to Executive + Leadership, (d) user-management mutation
is `ADMIN`-only, and (e) no endpoint authorizes a role outside the canonical set.
