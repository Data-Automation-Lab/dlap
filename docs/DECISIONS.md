# DECISIONS — DLAP

ADR-style log. Append-only, one decision per entry. Newest first.

Each entry captures: context, decision, consequences, status. If a
decision is later reversed, add a new entry that supersedes the old
one — do not edit history.

---

## ADR-0143 - The public repository starts from one commit, and the working documents stay private

**Date**: 2026-11-22
**Status**: Accepted (founder decision of 2026-11-22; public release cut)

**Context**: DLAP was developed in a private repository. Its history holds a self-signed keystore with a published password (SECURITY.md, "Key material previously committed") and three service keys that were removed from the tree but not from history. The repository also carried working documents that were never written for outside readers: a session log, an operating plan, a question log of about 900 entries, an audit snapshot and a draft incubation proposal. The founder decided to release the code as open source under Apache 2.0 without waiting for a foundation.

**Decision**:
1. The public repository (Data-Automation-Lab/dlap) and the UI repository it uses as the new-ui submodule (Data-Automation-Lab/dlap-ui) each start from a single release commit. No earlier history is published, so no burned key material is reachable from them.
2. The session log, the operating plan, the question log, the audit snapshot, the incubation draft and the donation runbook are not published. docs/OPEN_QUESTIONS.md is a short page that explains the Q-nnn ids and points to GitHub issues. This log (docs/DECISIONS.md) is published whole: it is the record of why the code is the way it is. Its entries cite package ids, wave numbers, Q-nnn ids and short commit ids from the private history; those references stay as written and do not resolve here.
3. CLAUDE.md is published as the engineering rules, with its section numbers unchanged, because about a hundred source comments cite it by section. The parts that described the private working process are replaced.
4. The public repositories are the home of the code from this commit on. New work lands there.
5. DLAP is not an Apache Software Foundation project and no incubation proposal is filed. Source files keep the ASF-style licence header they were written with, so that a later donation needs no mass edit; README.md and GOVERNANCE.md state the project's real status. If no proposal follows, a later ADR replaces the header with the plain Apache 2.0 form.
6. There is no project mailbox. Security and conduct reports go through GitHub private vulnerability reporting.

**Consequences**: Readers meet references they cannot follow (Q-nnn ids, package ids, commit ids). docs/OPEN_QUESTIONS.md says what they are. Known limits are stated in the README status note and in docs/guides/deployment.md, section 10, in place of the question log. Sample addresses on a domain the project does not use (support@dalab.com, noreply@dalab.com, the seeded users) remain in the reporting service and the seed data and are to be replaced with example.com values. Several guides lag the code in places that the deployment guide names; they are to be corrected through issues.

---

## ADR-0142 - Audience validation is on in the demo and in the pilot base overlay

**Date**: 2026-11-03
**Status**: Accepted (ID-04, hardening wave 16; orchestrator decision D46, recorded in ADR-0141; closes Q-913 and Q-919; builds on ADR-0140 (SEC-12) and decision D45 in ADR-0137)

> Numbered 0142 by assignment at the wave-16 integration; ID-04 supplied an optional draft with a placeholder number. No source cites the draft number.

**Context**: Since SEC-12 the DevSecretGuard of the prod profile refuses a start while dlap.security.jwt.validate-issuer or validate-audience is not true. The demo realm wrote no audience claim, so the demo overlay waived the audience finding with DLAP_SECURITY_DEV_SECRET_GUARD_ACCEPTED (Q-913). The pilot base overlay left both checks at the dev pair's false and relied on the TLS overlay to switch them on, so a pilot without TLS (and the login smoke) failed to start (Q-919).

**Decision**:
1. The demo realm (dlap-demo-realm.json) gets the dlap-api-audience oidc-audience-mapper (${DLAP_API_AUDIENCE:dlap-api}) on dlap-ui and dlap-backend, exactly as the pilot realm. Every demo JVM validates issuer and audience (expected audience dlap-api) and the accept line is deleted; the demo runs under prod with no waiver.
2. The pilot base overlay sets DLAP_SECURITY_JWT_VALIDATE_ISSUER and DLAP_SECURITY_JWT_VALIDATE_AUDIENCE to true for the ten Spring services, with DLAP_SECURITY_JWT_EXPECTED_ISSUER = ${DLAP_PILOT_ID_URL:-http://localhost:8180}/realms/dlap and DLAP_SECURITY_JWT_EXPECTED_AUDIENCE = ${PILOT_JWT_AUDIENCE:-dlap-api}; the TLS overlay still replaces the issuer. The login smoke carries no override of its own.

**Consequences**: A demo Keycloak database created before this change keeps the old clients (Keycloak imports the realm once) and every service answers 401 until the mapper is added to dlap-ui and dlap-backend or the realm is re-imported; the demo README has the steps (Q-926). verify-issuer.sh S-13, I-08 and I-09, test-demo.sh AUD-00 to AUD-04, pilot/verify.sh (no-TLS model) and pilot-login-smoke.sh S03 hold the decision in place. The ACCEPTED_PROPERTY mechanism of DevSecretGuard stays available, but no shipped overlay uses it (Q-924). An operator who runs the pilot without TLS while keeping the example's DLAP_PILOT_ID_URL=https://localhost:8443 gets a consistent https issuer that matches the Keycloak overlay's KC_HOSTNAME; the base comment covers only the quickstart-realm case (Q-928).

**Integrator note (wave 16)**: merged clean as 5bd3c239, second, after POL-06. The orchestrator accepts, through this integration, the files outside ID-04's literal Owns list that its reviewer listed: deployment/environments/pilot/verify.sh and the one-sentence change to deployment/environments/pilot/README.md (the test and doc of the owned base-overlay lines), and the demo README and test-demo.sh. No other wave-16 package touched them. The base-overlay comment cites ADR-0137 for the strict guard; that is where D45 is recorded, the guard itself is ADR-0140.

---

## ADR-0141 - A keyset sweep that meets an answer without nextAfter ends INCOMPLETE and the slot retries

**Date**: 2026-11-03
**Status**: Accepted (POL-06, hardening wave 16; orchestrator decision D46; closes Q-917; amends ADR-0139 item 5)

> Numbered 0141 by assignment at the wave-16 integration. POL-06 supplied no ADR and proposed an addendum to ADR-0138 or ADR-0137; this log is append-only, so the rule is recorded here as its own entry. Orchestrator decision D46 (12-orchestrator-wave-plan.md) records the orchestrator calls in the wave-16 package specs: wave 15 accepted with the integrator's two fixes for SEC-12 fallout (.env.example variables, pilot rehearsal env), SEC-12's out-of-Owns demo and pilot edits, policyengine V11, and Q-900 (the keyset export no longer aborts on a changed count); Q-918 closed with CHANGELOG upgrade notes. Wave 16: a keyset walk that meets an answer without nextAfter is INCOMPLETE and the slot retries (POL-06); the demo realm gets the audience mapper and loses its guard waiver, and the pilot base turns JWT validation on itself (ID-04, ADR-0142). After wave 16 the loop shifts to watching CI and the founder gates, with small waves only when new findings appear.

**Context**: ADR-0139 item 5 lets ScheduledPolicySweeper switch to keyset paging when the first answer carries nextAfter. After the switch, an answer with no nextAfter field gave keysetAnswered() false and nextCursor() null, and the sweep returned COMPLETED. During a mixed-version catalog rollout an older replica ignores after and answers page 0 by offset, so the slot would be recorded complete with every page after the first skipped, against the class's own rule that a slot is recorded only after every page is read (Q-917).

**Decision**: Once a scheduled sweep has switched to keyset paging for a slot, only an answer that carries nextAfter explicitly as null completes the walk. An answer to an after request with no nextAfter field ends the walk as SweepResult.INCOMPLETE before its rows are evaluated: the slot is not recorded (runDue treats INCOMPLETE like ABORTED and reports the fires with recorded=false), the next tick retries it, a WARN names each slot with the policy id, the page reached, the cursor and the as-of instant, and policyengine.schedule.slots{result=keyset-incomplete} counts each slot (policyengine.schedule.sweeps{result=incomplete} counts the sweep). CatalogPageDTO already tells an absent field (Java null) from an explicit null (empty text), so no DTO or client change is needed.

**Consequences**: Fire ids are deterministic per (policy, slot, asset), so actions already sent for the genuine keyset pages before the walk is found incomplete repeat harmlessly on the retry. There is no backoff or retry cap while an old replica keeps answering; the WARN repeats every tick (Q-923). The new counters have no observability row or alert yet (Q-921). A first page with nextAfter null and last=false still completes; CAT-04 sends that only for a sort the sweeper never sends. The mixed-version path is proved with mocks of the catalog client and the real CatalogPageDTO shape; a live sweep stays under Q-905.

**Integrator note (wave 16)**: merged clean as 134484c1, first. Two files change, both in da-policyengine (ScheduledPolicySweeper and ScheduledPolicySweeperKeysetTest, 5 new tests, 4 of them killed by removing the INCOMPLETE return per the package report). No other code in the repository switches on SweepResult. The package commit carries a Co-Authored-By trailer naming Sonnet 5.5, as the harness supplied it (accurate, kept per D22).

---

## ADR-0140 - One database password per role from the environment, own roles for the exporter and the backup agent, and a strict boot guard under prod

**Date**: 2026-11-03
**Status**: Accepted (SEC-12, hardening wave 15; orchestrator decision D45, recorded in ADR-0137; closes Q-176, Q-186 (database and JWT rows), Q-247, Q-297, Q-449 and Q-455)

> Numbered 0140 by assignment at the wave-15 integration; SEC-12 supplied a draft (ADR-SEC-12, number to be assigned). No source cites the draft number.

**Context**: Every service connected as da_<service>_user with a password fixed in 00-create-multiple-databases.sh and repeated as an application.properties default (Q-176). The Postgres exporter and the backup agent used the bootstrap superuser (Q-247, Q-297, Q-455). DevSecretGuard could only warn about database defaults and JWT validation off, because a deployment could not change the passwords (Q-186, Q-449).

**Decision**:
1. The init script takes the password of each role from <DATABASE>_DB_PASSWORD (database name upper-cased, non-alphanumerics to underscore) and falls back to the published <database>_pass only when the variable is unset or empty. The password reaches psql through the process environment and \getenv with log_statement off for that session, so it is never in the SQL text, a command line or the server log. 01-create-single-database.sh follows the same rule. The script creates two platform roles: dlap_monitor (pg_monitor, nothing else) from DLAP_MONITOR_DB_PASSWORD and dlap_backup (REPLICATION, pg_read_all_data, EXECUTE on pg_control_system, pg_control_checkpoint and pg_reload_conf, ALTER SYSTEM on max_slot_wal_keep_size, no superuser) from DLAP_BACKUP_DB_PASSWORD. Roles are cluster objects, so there is no dlap_common change; existing volumes are moved forward by upgrades/sync-database-roles.sh (idempotent, --dry-run, --quiet, --dev-defaults), which is not a ledgered delta because passwords rotate, and which the pilot up.sh runs after run-upgrades.sh.
2. Compose hands the same variables to PostgreSQL and to each service (SPRING_DATASOURCE_PASSWORD, plus DB_PASS so the unused dlap.common-db.password placeholders resolve). The dev pair leaves them empty, so the quickstart boots with the published defaults; the pilot base overlay and the demo overlay require them and POSTGRES_PASSWORD with no fallback, and their launchers check that all 13 are set, not CHANGE_ME, not a published default, all different and different from POSTGRES_PASSWORD. deployment/platform/database/gen-db-passwords.sh fills the 13 variables of an environment file and prints names only.
3. DevSecretGuard is strict by default under prod: a published development database password or JWT issuer or audience validation off refuses the start. Under dev without prod the findings stay advisory. dlap.security.dev-secret-guard.strict overrides either way; dlap.security.dev-secret-guard.accepted may accept a named JWT flag (never a database default, an empty secret or a published secret). The demo accepts dlap.security.jwt.validate-audience this way because its realm issues no audience claim (Q-913).
4. postgres-exporter connects as dlap_monitor and the backup agent as dlap_backup. POSTGRES_PASSWORD keeps the dev fallback dlap only in the dev pair.

**Consequences**: A stock prod deployment without 13 real role passwords and JWT validation cannot start (intended). Operators set DB_PASS (or SPRING_DATASOURCE_PASSWORD and the DLAP_COMMON_DB_PASSWORD style variables) per service; seven services still carry an unused dlap.common-db.password property that forces DB_PASS (Q-910). A quickstart volume created before SEC-12 that runs the monitoring profile shows postgres-exporter pg_up 0 until sync-database-roles.sh --dev-defaults runs once or the volume is recreated (Q-918); a demo volume needs the same script without --dev-defaults (Q-912). Passwords are visible in docker inspect, as every other secret of the compose model. The Grafana admin password and the Keycloak database password are not on the guard (Q-916). The Helm chart and the test compose keep fixed passwords (Q-915).

**Integrator note (wave 15)**: merged clean as c6a0ba01, last. The orchestrator accepts, through this integration, the files outside SEC-12's literal Owns list that its reviewer listed: deployment/environments/demo/** (overlay environment, keycloak overlay accept line, .env.demo.example, up.sh, test-demo.sh), the pilot up.sh, up-test.sh, verify.sh and .env.pilot.example, the new tool gen-db-passwords.sh and the tests folder under deployment/platform/database, and the edits to 22 prod-profile context tests in 10 services (pending the decisions listed in the 2026-11-03 wave-15 SESSION_LOG entry). The integration found three gaps SEC-12 had not run into, fixed in two integration commits on wave/15 (the orchestrator may accept or drop them): .env.example did not list the 13 variables, so da-discovery InstallPathContractTest.everyVariableTheComposePairReadsIsDocumentedInTheExample failed in the full run (3418832e lists them commented out and empty); tls-e2e.sh and pilot-login-smoke.sh wrote no role passwords, so up.sh refused the TLS rehearsal, and the login smoke runs the pilot without the TLS overlay, so da-discovery refused to start with JWT validation off under prod (09d78d83 fills both environment files with gen-db-passwords.sh and turns the JWT checks on for da-discovery in the login smoke). With them: the quickstart from a clean clone is zero-step (368 s; the 13 roles, dlap_monitor and dlap_backup exist with their published defaults), the TLS rehearsal passes 387 checks with the sync step changing 14 roles, the login smoke 29 of 29, the demo boots under the strict guard (test-demo.sh 116 of 116), pilot up.sh --check passes with generated passwords and refuses DA_CATALOG_DB_PASSWORD=da_catalog_pass, and test-dr.sh --quick passes 339 checks. The pilot without its TLS overlay no longer starts unless the JWT checks are turned on (Q-919).

---

## ADR-0139 - The policy store refuses the two silent bypasses, the event path reads the catalog through a 30 s cache, and the sweep pages by keyset

**Date**: 2026-11-03
**Status**: Accepted (POL-05, hardening wave 15; orchestrator decision D45, recorded in ADR-0137; closes Q-881, Q-889, Q-861 and Q-437; implements the sweeper half of Q-445; amends ADR-0136 on the remaining LEGACY_RULE bypass and the kind Policy effect gap)

> Numbered 0139 by assignment at the wave-15 integration; POL-05 supplied a draft (ADR-NEW-POL-05). POL-05 cited decision D45 and question numbers in its code comments and SQL, not an ADR number, so nothing is renumbered.

**Context**: ADR-0136 left three documented gaps. A direct edit could set policy_kind to LEGACY_RULE and blank policy_document in one statement and hide an ENABLED deny from the PDP (Q-881). A kind Policy document that parsed fully and stated spec.effect: deny was ignored by the PDP (Q-889). The invalid-deny gauge of ADR-0133 was not documented or alerted on (Q-861). The lifecycle event consumer called the catalog twice per event, a high volume on the accessed topic (Q-437), and the scheduled sweep paged the activity report by offset, so a row written during a long sweep could shift a page and make it skip an asset (Q-445).

**Decision**:
1. Flyway V11 adds the trigger trg_policies_kind_guard on policies (BEFORE INSERT OR UPDATE, per row). It refuses a non-blank policy_document on a LEGACY_RULE row and a change of policy_kind from DSL to LEGACY_RULE, both with SQLSTATE check_violation (23514). A LEGACY_RULE row may become DSL with a document in one statement, which is what updatePolicy does. Existing rows already satisfy the rule (V10 made every row with a non-blank document DSL), so the migration rewrites nothing.
2. A kind Policy document that states spec.effect (any value, empty text included; an explicit null counts as absent) is refused when it is written: createPolicy and updatePolicy answer 400 through PolicyDslParser.lifecycleEffectDefect. The parser itself is unchanged, so a stored document of that shape still parses and still runs as a lifecycle policy. StoredPolicyValidator logs every stored one with its row id and counts it under policyengine.policies.invalid{check=access-policy}.
3. docs/observability.md lists dlap.pdp.policies.invalid.deny{scope=readable|unreadable}. The alert PolicyStoreUnreadableDeny fires on max by (service) of scope=unreadable above 0 for 5 minutes at severity critical, because such a document denies every PDP request. A promtool rule test covers firing, the for-duration, readable-only, a short blip and two instances.
4. EventPathCatalogLookup, used by LifecyclePolicyConsumer only, caches the two event-path catalog lookups (URI to asset id, asset by id) in one map keyed by kind and text. TTL dlap.policyengine.catalog-cache.ttl (default PT30S, PT0S turns the cache off); at most dlap.policyengine.catalog-cache.max-entries (default 10,000) entries with least-recently-used eviction. An entry is served only while its age on the policy clock is at least zero and strictly below the TTL. Only present answers are stored, so an unknown asset, a 404 or a down catalog is looked up again on the next event. Metrics: policyengine.catalog.cache.lookups{lookup=asset|uri,result=hit|miss} and the gauge policyengine.catalog.cache.entries. The PDP and the sweep keep reading the catalog directly. The P2D coverage freshness rule of ADR-0130 is therefore decided on catalog data at most one TTL old on the event path (D45).
5. ScheduledPolicySweeper requests page 0 by offset. When the answer carries nextAfter (JSON null counts as present, absent does not; CatalogPageDTO tells them apart), every later request is GET /api/v1/catalog/assets/activity?after=<nextAfter>&size=N with the statuses and as-of instant, and the sweep ends on the page whose nextAfter is null. A cursor equal to the previous one, or a cursor after an empty page, aborts the sweep and leaves the slot due; cursors are compared for equality only, because the catalog sorts by its database collation. An answer without nextAfter is read by offset as before. The mode is counted in policyengine.schedule.paging{mode=keyset}.

**Consequences**: A direct edit can no longer hide a deny by setting the kind and blanking the document together. Operators get an alert for the one state that denies everything. The event path makes at most one pair of catalog calls per asset per TTL, at the price of seeing a legal hold, label, retention or coverage change up to 30 s late; destructive actions still wait in ApprovalGate and da-autodelete's soft-delete admission refuses a legal-hold asset (Q-906). Any seed or restore script that writes a LEGACY_RULE row with a document now fails (none in the repository). A LEGACY_RULE row can still become a DSL row with any document by a direct edit, and policy_kind is not in the history snapshot (Q-908, Q-882). Enabling a stored kind Policy document with spec.effect through setPolicyStatus is not refused (Q-909). In keyset mode an answer that has lost its nextAfter field (an old catalog replica during a rollout) is recorded as a complete slot (Q-917).

**Alternatives rejected**: a CHECK constraint for the LEGACY_RULE rule (cannot see the old value); making the parser refuse spec.effect on kind Policy (turns every stored such document into an unparseable one); a negative cache for unknown assets (a new asset would be unresolved for the TTL and its event lost); a caffeine dependency (a build-file edit outside Owns for a 60-line bounded map); comparing keyset cursors for order in Java (differs from the database collation).

**Integrator note (wave 15)**: merged clean as f4a4ec73, fifth, after CAT-04. V11__policy_kind_guard.sql is the next free policyengine version (V10 was POL-04); the plan's section 8 reservation table is stale for policyengine (Q-907). The sweeper's keyset request shape matches CAT-04 as merged (its reviewer traced AssetActivityPageDTO.ofOffsetPage and the controller's checks). On the aws harness stack Flyway applied V11 (trg_policies_kind_guard present) and StoredPolicyValidator reported 0 invalid AccessPolicies and 0 unreadable documents. Both prometheus.yml and prometheus.tls.yml name the scrape job dlap-services, so the alert's job filter matches the pilot too (POL-05 note 9); deployment/platform/prometheus/check.sh passes with 36 alert rules.

---

## ADR-0138 - Keyset paging on the catalog activity endpoint

**Date**: 2026-11-03
**Status**: Accepted (CAT-04, hardening wave 15; orchestrator decision D45, recorded in ADR-0137; resolves the paging half of Q-445)

> Numbered 0138 by assignment at the wave-15 integration; CAT-04 supplied a draft (ADR-NEW-CAT-04).

**Context**: GET /api/v1/catalog/assets/activity paged by offset only. A concurrent insert shifted a page boundary, so the da-reporting export (RPT-01) detected the shift and aborted, and the POL-01 sweeper could skip an asset in a slot (repeats are covered by fireId, skips are not).

**Decision**: The endpoint accepts after=<assetUri> and size=N. With after set, it returns the next size assets whose assetUri sorts strictly after the cursor, in assetUri ascending order (the unique column asset_uri, compared and ordered by the database collation), and a nextAfter field that is the last row's assetUri while more rows follow and null on the last page. The response is the unchanged Spring page JSON plus nextAfter (AssetActivityPageDTO extends PageImpl), so offset callers are unaffected; nextAfter is also written on offset pages ordered by assetUri ascending, so a walk starts with an ordinary first page. after requires page 0 and assetUri,asc (400 otherwise); a blank after means offset paging; a cursor over 2048 characters is a 400. The last-page flag comes from one lookahead row; totalElements is the exact filtered count. Filters, row shape and asOf are shared with the offset path. da-reporting's expiry export walks by cursor.

**Consequences**: A walk neither skips nor repeats an asset that stays in the table; an asset inserted ahead of the cursor is visited, one behind it is not (the result is not a snapshot). Clients treat the cursor as opaque. The export keeps its repeated-row guard and adds cursor-progress, missing-cursor and row-limit growth guards; a changed row count no longer aborts the export, and the manifest records source.paging=keyset with the announced total next to the rows written (Q-900). A catalog that predates keyset paging (no nextAfter, last=false) is refused before the first byte. Each keyset page runs a count query (Q-901). Re-keying an asset during a walk can repeat it (Q-902). The OpenAPI page schema is renamed from PageAssetActivityRowDTO to AssetActivityPageDTO; the UI types were regenerated (UI bd4479d). The 250000-row export limit review stays open (Q-445, Q-903).

**Integrator note (wave 15)**: merged clean as b25be801, fourth, after GCP-03. docs/api/openapi/catalog.json and reporting.json were regenerated by the package; the integration found no further drift (see the wave-15 SESSION_LOG entry). The UI branch wave/15 (from master c1f7d31) carries bd4479d with the regenerated catalog.ts and reporting.ts; gitlink bump 6b956ca8. policyengine's CatalogPageDTO javadoc and a CatalogPageDTOTest comment still name the old schema (Q-904).

---

## ADR-0137 - A derived BigQuery read takes the entry's result, and a lost load-bearing JOBS column is drift

**Date**: 2026-11-03
**Status**: Accepted (GCP-03, hardening wave 15; orchestrator decision D45; closes Q-393 and Q-396)

> Numbered 0137 by assignment at the wave-15 integration; GCP-03 supplied a candidate text. Orchestrator decision D45 (12-orchestrator-wave-plan.md) records the orchestrator calls in the wave-15 package specs: wave 14 accepted as integrated (ADR numbers in merge order with the integrator's citation fix, out-of-plan Flyway versions reporting V5, autolabel V2, policyengine V10); wave 15 clears the remaining self-contained medium questions: the event-path catalog cache is bounded at a 30 s TTL so P2D freshness holds; keyset paging on activity with offset kept; the DevSecretGuard is strict by default only under the prod profile; exporter and backup use their own roles; the quickstart must stay zero-step. The package specs also carried the D45 calls for SCAN-02 (read cvssv2.score, Trivy --timeout 30m, test-tls and test-dr quick in CI with test-dr only on a deployment/ change, one fixed per-user harness lock path, a self-approval gate after the held gate), IDEM-02 (trim the fireId, blank is absent, over 255 characters refused and acknowledged; a name-UUID fireId of asset, label and compliance source in the compliance sink), GCP-03 (the entry's failure on derived reads; COLUMN_LOST for a lost load-bearing column) and POL-05 (V11 trigger, 400 for spec.effect on kind Policy, the invalid-deny alert). SCAN-02 and IDEM-02 supplied no ADR of their own; their notes follow this entry's integrator note.

**Context**: The GCP audit parser set SUCCESS on every read and write derived from a BigQuery job (v1 jobservice.jobcompleted, v2 jobChange DONE) and looked only at the job's own error, so an entry with a non-zero status.code, a denied check, the Policy Denied log or VPC Service Controls metadata still produced successful reads (Q-393). The JOBS view transport treated only the 8 required columns as drift, so losing cache_hit, statement_type, error_result, destination_table or parent_job_id after the view had them made the run miscount silently (Q-396).

**Decision**:
1. A read or write derived from a job carries FAILURE when the entry's own result is FAILURE (GcpAuditParser.Record.derivedResult: non-zero status.code, a denied or ungranted authorizationInfo, the Policy Denied log, VPC SC metadata), else SUCCESS. A job that itself ended in error still emits no reads.
2. The JOBS transport remembers, in cursor state key load_bearing_columns, which of the five load-bearing optional columns (BqJobsFormat.LOAD_BEARING_OPTIONAL_COLUMNS) the last good run saw. A later run that lacks one returns one COLUMN_LOST run-problem marker instead of rows, with the cursor and recorded state unchanged, so the marker is the same bytes on every poll. A column the view never had is not lost; a column that comes back ends the quarantine. The marker is quarantined as UNKNOWN_ENVELOPE like COLUMN_DRIFT (the proto reason set is frozen); the kind COLUMN_LOST is in the excerpt.

**Consequences**: Failed and denied BigQuery reads no longer count as successful usage; a v2 entry that failed at the entry level still emits its derived reads, now as FAILURE (Q-899). A permanent column loss blocks the binding until the column returns or the stored state is reset by hand (Q-896). The quarantine reason is not distinct (Q-897). Cursors written before this change record the column set on their first good poll, so a loss in that one poll goes undetected (Q-898).

**Integrator note (wave 15)**: merged clean as 2e7c0011, third, after IDEM-02. The generated gcpaudit cases were appended at the end of each cell; no coverage-spec or catalog file changed. fixtureCoverage on the merged tree shows no score change against wave 14; only case counts grew (bqjobs authored cases 36 to 37, two gcpaudit cells one case each).

**SCAN-02 note (no ADR)**: merged clean as 220d0f72, first. scan/summary.py reads the Dependency-Check CVSS v2 score from cvssv2.score (and a score key beside or inside cvssData) as well as cvssData.baseScore and takes the maximum over v4, v3 and v2 (Q-327). trivy.sh passes --timeout, default 30m, overridable by TRIVY_TIMEOUT (Q-328). The harness host lock lives at <home>/.dlap/harness-lock for every caller (Windows USERPROFILE under Git Bash, else the passwd home, else HOME), independent of TMPDIR; DLAP_HARNESS_LOCK_DIR remains an override for the self-test (Q-577, first half; the restart: unless-stopped half is Q-891). cloud-lifecycle-e2e.sh has a fifteenth gate, self_approval: after the held gate the triggering admin's APPROVE must answer 403 with reason self-approval and leave the request PENDING (Q-592). The CI workflow has a pilot-tests job in the ci-gate needs list that runs test-tls.sh --quick on every run except the idle nightly and test-dr.sh --quick when a path under deployment/ changed, nightly, on manual runs and on unknown ranges (Q-542; the path filter lives in the job, not in plan.py, Q-892).

**IDEM-02 note (amends ADR-0135, no ADR)**: merged clean as 419f6e6e, second. The two gaps ADR-0135 named are closed. (1) da-autolabel normalises the fireId of a policy addLabel action (PolicyFireId): trimmed, blank treated as absent so it is no dedupe key (previously every blank-fireId action collapsed into one job), and a value longer than 255 characters, the width of dlap_autolabel_job.fire_id, is logged and acknowledged without calling the service, because a retry of an insert that cannot fit never succeeds and used to end in the dead-letter topic. LabelingRunServiceImpl.applyPolicyLabel applies the same trim and blank rule for any caller (Q-890). (2) CatalogEventComplianceLabelSink sets fireId = UUID.nameUUIDFromBytes(assetId|labelName|compliance), so da-reporting and da-autolabel record or apply a repeated compliance label once (Q-877). Consequence: a given (asset, label) compliance label is applied once for the life of its job row, including after a manual removal followed by a compliance service restart (Q-894); da-reporting and da-autolabel still differ on an over-length fireId (Q-895).

---

## ADR-0136 - A broken deny fails closed whatever its kind; a policy row without a document is told apart by policy_kind

**Date**: 2026-11-02
**Status**: Accepted (POL-04, hardening wave 14; orchestrator decision D44, recorded in ADR-0134; closes Q-870 and Q-871; amends ADR-0133 decision 1 on kind Policy and the ENABLED-row skip)

> Numbered 0136 by assignment at the wave-14 integration; POL-04 supplied a draft (ADR-NEW-POL-04) and had cited ADR-0134 in five code comments and one CHANGELOG line, which the integration commit 24d7a45f renumbers to ADR-0136 (number only).

**Context**: ADR-0133 made every ENABLED document the strict parser rejects fail closed, with two gaps left open by the review. UnparseableAccessPolicy.classify ignored any broken document whose kind reads Policy before it read spec.effect, so a deny whose only fault was its kind stayed ignored (Q-870). AccessDecisionService.refresh skipped every ENABLED row whose policyDocument was null or blank, so a deny whose document was blanked by a direct database edit failed open, and the row had no way to tell a blanked document from a legacy rule row that never had one (Q-871).

**Decision**:
1. kind Policy (any case) is ignored by the PDP only when spec.effect is absent (missing, null, or spec not a map). A document that states any effect is judged by it: effect allow is skipped; effect deny, a blank, non-text or other value is kept as an invalid deny with the scope that can be read (ADR-0133 decisions 2 and 3 unchanged). An explicit null effect (effect: ~) counts as absent, so such a kind Policy document is ignored; this reading of D44's 'absent' is deliberate and documented in policy-dsl.md v1.5.
2. policies gets a discriminator column policy_kind VARCHAR(16) NOT NULL, values LEGACY_RULE and DSL (CHECK constraint, no default, so a writer that does not classify a row is refused). Flyway V10 backfills it from the data: a row with a non-blank policy_document becomes DSL, every other row LEGACY_RULE, soft-deleted rows included (the V9 soft-delete guard trigger is disabled for that one UPDATE and re-enabled in the same transaction).
3. New rows set it from the API path that creates them: PolicyService.createPolicy and updatePolicy set DSL (a document is required on both). The entity default is DSL, so a row nobody classified fails closed.
4. An ENABLED DSL row whose document is null or blank is an invalid deny with no readable scope: every request is denied with reason_admin policy-store-invalid: <row id>, in observe mode too, until the document is restored or the policy disabled. It is counted in dlap.pdp.policies.invalid.deny{scope=unreadable} and named in the per-refresh WARN. An ENABLED LEGACY_RULE row without a document is skipped as before.

**Consequences**: A deny blanked or mis-kinded by a direct edit now blocks instead of failing open. A blank ENABLED DSL row blocks the whole PDP, the same blast radius ADR-0133 chose for an unreadable scope. The remaining bypass is a direct edit that sets policy_kind to LEGACY_RULE together with a blank document; that needs the same database access as the edit it would hide (Q-881). Any other writer of the policies table (raw SQL, restores) must now supply policy_kind (Q-884); the two raw seeds in policyengine tests were updated. The lifecycle paths (event consumer, schedule sweeper) still skip blank documents, which cannot produce a destructive action. A kind Policy document that parses fully as a lifecycle Policy and also carries spec.effect: deny is still ignored by the PDP, because the parser accepts it; D44 covers broken documents only (Q-889). The policy history snapshot is built field by field (PolicyHistoryService.snapshot), so the new column changes no content hash, and a kind change by direct edit is not visible in the history (Q-882).

**Alternatives rejected**: treating every blank ENABLED row as an invalid deny (breaks legacy rule rows, which V5 disabled but whose rows exist); inferring the kind from kind inside the document (gone with the document); a column default of LEGACY_RULE (a row written without a kind would silently lose the check); rejecting the edit with a database trigger (not needed for the stated threat and changes the soft-delete guard contract).

**Integrator note (wave 14)**: merged clean as b1366648, third, after IDEM-01. V10__policy_kind.sql is the next free policyengine version (V9 was AUD-02a) and no other wave-14 package touches policyengine migrations. UnparseableAccessPolicy.java (dsl/, POL-03's package in wave 13) is edited at one method because D44 requires it; the raw INSERTs in dsl/PolicyScheduleStoreTest and service/PolicyHistoryContainerTest supply policy_kind because of the NOT NULL column. No production writer of the policies table writes raw SQL (the only INSERTs are in tests; the lifecycle harnesses seed policies through POST /api/v1/policyengine/policies, so their rows are DSL rows with documents).

---

## ADR-0135 - Policy-action consumers record a fire once, keyed on fireId

**Date**: 2026-11-02
**Status**: Accepted (IDEM-01, hardening wave 14; orchestrator decision D44, recorded in ADR-0134; closes Q-855 and Q-856)

> Numbered 0135 by assignment at the wave-14 integration; IDEM-01 supplied a draft (ADR-NEW-IDEM-01).

**Context**: dlap.policies.actions is delivered at least once (log-event.md section 5.5), and LifecyclePolicyConsumer republishes a non-gated fire with the same fireId when its source event is redelivered (PolicyActionRequests: fire ids are name-based UUIDs of the trigger instance). ADR-0132 audited the proof and catalog consumers but left the action-topic consumers (Q-855, Q-856).

**Decision**:
1. fireId is the idempotency key of an action. A consumer that stores or applies an action does so once per fireId and still acknowledges every copy.
2. da-reporting PolicyActionConsumer: V5 adds policy_action_events.fire_id (varchar 255) with a partial unique index (WHERE fire_id IS NOT NULL). PolicyActionEventRecorder.recordOnce inserts the row first and flushes, sends the notification inside the same transaction, and marks the row dispatched. A repeated fireId stores nothing and sends nothing. A send that throws rolls the claim back, so the redelivery sends (at least once for the notification; a failed commit after a successful send can send again). A copy that loses the insert race gets a unique violation, which the consumer recognises (isRecorded) and acknowledges. Rows from before V5 and actions without a usable fireId (absent, blank or longer than 255) keep a null fire_id and are recorded every time.
3. da-autolabel PolicyActionLabelConsumer: V2 adds dlap_autolabel_job.fire_id with the same partial unique index. applyPolicyLabel returns the job of a known fireId without calling the catalog; otherwise it claims the fire (saveAndFlush) before the catalog call, inside the method's transaction, so a failed apply rolls the claim back and a concurrent copy waits on the index. Previously every copy posted the label again and wrote another job row, which the dashboard counted as another labeled asset.
4. da-autodelete and da-autoarchival were audited and need no change. autodelete: SoftDeleteAdmission derives the record id from the fireId and returns the existing row before any remote call; the primary key backs it; a refusal writes no row, so a held action released by the policy engine under the same fireId is still evaluated. autoarchival: a known approvalId or fireId returns the stored rule before a task is made; ux_archive_rules_fire backs it and ArchiveExecutor.prepare resolves the insert race. Each has a named duplicate test.

**Consequences**: A duplicate fire costs one indexed lookup. Two messages of different content but the same fireId collapse to the first (as in the Event Center feed, ADR-0132). Actions that carry no fireId, notably the addLabel events of the compliance sink (CatalogEventComplianceLabelSink), are not deduplicated in reporting or autolabel (Q-877); the catalog's unique asset-label constraint keeps the label itself single. In da-reporting the notification send now runs inside a database transaction, so a slow provider holds a connection for its duration (Q-879). In da-autolabel the fireId is used as received, without the trim and 255-character cap da-reporting applies (Q-890).

**Integrator note (wave 14)**: merged clean as bf7e6c65, second, after LOG-08. Flyway: da-reporting V5 (V4 was RPT-03's principal_identity_map) and da-autolabel V2 (the plan's conditional UI-03 reservation was never used); both are idempotent (ADD COLUMN IF NOT EXISTS, CREATE UNIQUE INDEX IF NOT EXISTS) and keep a null fire_id on existing rows. ILabelingRunService.applyPolicyLabel gained a fireId parameter; its only caller is PolicyActionLabelConsumer. No API or OpenAPI change. da-catalog SetRetentionActionListener is a fifth consumer of the topic, idempotent by its issuedAt monotonic upsert, without a duplicate test (Q-880).

---

## ADR-0134 - Backfill heartbeat age gauge and a bounded retry for coverage calls

**Date**: 2026-11-02
**Status**: Accepted (LOG-08, hardening wave 14; orchestrator decision D44; closes Q-846 and Q-829, and with Q-846 the heartbeat half of Q-826; refines the OBS-02 note of ADR-0131)

> Numbered 0134 by assignment at the wave-14 integration; LOG-08 supplied a draft (ADR-NEW-LOG-08). Orchestrator decision D44 (12-orchestrator-wave-plan.md) records the orchestrator calls in the wave-14 package specs: wave 13 accepted as integrated, with OBS-02 and UI-04 recorded as notes in ADR-0131, the interim batch-progress stuck-job alert until Q-846, and long package commit subjects kept. Wave 14: any broken document whose effect reads deny is an invalid deny whatever its kind; a policy_kind column (LEGACY_RULE, DSL) makes a blank ENABLED DSL row an invalid deny; policy-action consumers dedupe on fireId with unique indexes; CoverageHttp retries 429 and 5xx three times with Retry-After, never 403. The package reports cite D44 also for UI-05's scope (a generated autodelete contract for the soft-delete list and the restore call, /approvals in the shared route table). UI-05 supplied no ADR; its note follows this entry's integrator note.

**Context**: BackfillJobStuck was a batch-progress proxy because no meter exposed progress.heartbeat; it missed a job whose slices are empty and fired for a job in its last retries (Q-846). CoverageHttp ended a resource as UNKNOWN at the first 429 or 5xx (Q-829).

**Decision**:
1. BackfillMetrics registers dlap_logingest_backfill_heartbeat_age_seconds{binding}: seconds since the stored progress.heartbeat of the oldest RUNNING job of that binding, on the injected Clock (startedAt, then requestedAt, when no heartbeat is stored yet). The binding label follows the bounded-label rule (_all, largest value). 0 while the binding has no RUNNING job, NaN when the job table cannot be read. The table is read through BackfillJobs.list(null, RUNNING, 1000, 0) and cached 10 s; a binding's gauge is registered by the first read that finds a RUNNING job of it.
2. BackfillJobStuck: max by (service, binding) (dlap_logingest_backfill_heartbeat_age_seconds) > 900, for 5m, warning. 900 s equals BackfillProperties.MAX_BACKOFF_MINUTES.
3. CoverageHttp retries a 429 or 5xx at most 3 times (4 calls): wait 1 s doubled per retry, cap 30 s; Retry-After (seconds or HTTP date, read on the injected clock) replaces the wait and is capped at 30 s; an unreadable header falls back to the backoff. The last response is returned, so collectors keep their per-resource UNKNOWN outcome (ADR-0129, ADR-0131). A 403 and other 4xx are never retried; a call that does not complete is not retried (Q-872); an interrupted wait ends the call with CoverageTransportException and keeps the interrupt flag.

**Consequences**: The stuck-job alert is exact and names the binding. A throttled coverage call delays its collector by up to 7 s with plain backoff, up to 90 s with the maximum Retry-After; tests use CoverageFixtures.http(), which does not sleep, and later tests that run a 429 or 5xx through a real CoverageHttp should use it too. AWS SigV4 requests are re-sent as signed, well inside the 5-minute signature window. The first heartbeat series of a binding is exported one scrape late, and a binding's series stays at 0 after its job ends (Q-873).

**Integrator note (wave 14)**: merged clean as d569dddc, first. LOG-08 alone edits BackfillMetrics, CoverageHttp, alerts.yml, alerts.test.yml and docs/observability.md this wave; the group dlap-backfill-drift stays ahead of dlap-log-ingest. The edits to existing da-discovery coverage tests only switch them to CoverageFixtures.http() and add a request-count assertion. deployment/platform/prometheus/check.sh (promtool check config and rules, amtool) was run in full on the merged tree at the integration (Q-875). docs/observability.md still does not list POL-03's gauge dlap.pdp.policies.invalid.deny{scope}, which POL-04 now also feeds for blank DSL rows (Q-861).

**UI-05 note (no ADR)**: UI-05 (UI only; UI wave/14 c1f7d31 merges 3ad15ad and 267f344 from master a293fe4; gitlink bump 1eb0809d) adds gen:api:autodelete to gen:api and gen:api:check and commits the generated src/api/generated/autodelete.ts (from docs/api/openapi/autodelete.json at e0770e54, unchanged in this wave). fetchSoftDeletes reads DeletionTaskListResponse and DeletionTaskStatusDTO, so a renamed field fails typecheck (shown by a temporary rename: TS2339 at approvalApi.ts(599,43)); the restore sends RestoreRequest and reads the generated operation type, but the contract types the restore answer as an open map, so ruleRemoved, approvalWithdrawal and message are still read by key (Q-885). A new autodeleteApi client (/api/v1/autodelete, shared interceptors) in utils/api.ts replaces the absolute-URL workaround; ROUTES.approvals, Window.navigateToApprovals and a HIDDEN_IN_DEMO entry for approvals are added. App.tsx does not set window.navigateToApprovals (Q-886), and DefaultPageLayout.tsx and ApprovalPanels.tsx still import the APPROVALS_ROUTE alias (Q-887). Test doubles that mock ./api must now export autodeleteApi.

---

## ADR-0133 - Unparseable AccessPolicies fail closed; coverage freshness and coverage start on every path

**Date**: 2026-11-02
**Status**: Accepted (POL-03, hardening wave 13; orchestrator decision D43; amends the ADR-0130 consequences on Q-837 and Q-842; closes Q-842, Q-837 and the engine half of Q-434)

> Numbered 0133 by assignment at the wave-13 integration; POL-03 supplied a draft (ADR-NEW-POL-03). Orchestrator decision D43 is recorded in ADR-0131.

**Context**: AccessDecisionService.refresh skipped every stored document that did not parse, so a deny whose document broke (direct database edit, restored row, stricter parser) failed open (Q-842). The P2D freshness rule (ADR-0130 decision 4) applied on the sweep only, because the policy engine's CatalogAssetDTO did not read readCoverageObservedAt from the single-asset GET (Q-837). The engine could not prove a never-read asset idle because it did not read readCoverageSince (Q-434).

**Decision**:
1. Every ENABLED document the strict parser rejects is read again as a plain YAML tree (UnparseableAccessPolicy). kind reading Policy (any case) is ignored by the PDP. spec.effect reading allow (any case, trimmed) is skipped: skipping an allow can only deny more. Every other document, including effect deny, an unreadable effect and text that is not YAML, is kept as an invalid deny.
2. The scope of an invalid deny is its subject selector (spec.subjects map with roles and/or tiers as lists of non-blank plain values, at least one non-empty; any other shape unreadable as a whole) and its resource selector (spec.match as a non-empty map, evaluated as a deny AccessPolicy). An unreadable selector covers everything. Actions, condition and metadata.enabled are not read.
3. An invalid deny with a readable selector denies every request in its scope with reason_admin "policy-invalid: <id>"; one with no readable selector denies every request with "policy-store-invalid: <id>" until the document is fixed or the policy disabled. Both apply in observe mode and override any allow. The id is metadata.id when readable, else the row id. Reasons list store-invalid, then invalid, then valid denies.
4. Gauge dlap.pdp.policies.invalid.deny{scope=readable|unreadable}; one WARN per refresh lists the invalid denies and skipped allows. The start-up check names the PDP consequence and counts a document of unknown kind under check=unreadable.
5. CatalogAssetDTO reads readCoverageObservedAt and readCoverageSince as text; the snapshots of the catalog asset and the activity row carry both, and on the event path the catalog's values replace the event's for policies under the full-coverage rule. The ADR-0130 freshness rule therefore applies on the sweep, event and PDP paths. A missing time keeps the v1.2 behaviour with a WARN (sweep and event as before; PDP once per asset per refresh, at most 1000 assets).
6. Under FULL coverage that counts (fresh under the rule, face value otherwise) with a known readCoverageSince and no read recorded at or after it, the start is an exclusive upper bound of the last read and a recorded earlier read a lower bound: olderThan d is TRUE once the start is at least d old, youngerThan d FALSE; otherwise decided by the lower bound or UNKNOWN. Outside the rule the start is used only when no read is recorded.

**Consequences**: A broken deny now blocks its scope, and a document with no readable scope blocks the whole PDP until fixed; operators see the gauge and the per-refresh WARN. Never-read assets become retirable by lifecycle policies once coverage has run long enough. A FULL asset whose recorded read predates the coverage start is no longer judged on that read alone by destructive policies (stricter, previously a fail-open). A lifecycle document with unreadable kind is treated as a possible AccessPolicy (fail closed). On the allow side, every AccessPolicy falls under the full-coverage rule, so with fresh FULL coverage and a known readCoverageSince an allow with lastAccessedAt olderThan d can now be TRUE for a never-read asset where it used to be UNKNOWN (proven bounds; POL-03 review).

**Alternatives rejected**: skipping every unparseable document (fails open); denying every request for any unparseable document (blast radius beyond D43); honouring readable actions or conditions of a broken deny (narrows a deny whose author intent is unknown); typing the new DTO fields as Instant (one malformed value would make the whole asset unknown).

**Integrator note (wave 13)**: merged clean as 5c707f99, fourth, after RTR-01, whose da-policyengine edits (kafka/consumer/LifecycleEventStreamConsumer, service/EventSubscriptionService) do not overlap POL-03's access/**, dsl/** and client/dto/** files. CatalogAssetDTO reads the field names COV-02 put on AssetOutputDTO (readCoverageObservedAt, readCoverageSince), checked on the merged tree. This ADR supersedes the statements in the ADR-0130 consequences that freshness applies on the sweep path only and that an unparseable deny fails open (ADR-0130 is not edited, the log is append-only). The docs/specs/policy-dsl.md edits outside the access-policy section (title v1.4, the "Coverage start (v1.4)" paragraph) are accepted: the replaced sentence became false because of this package. Left open by the review: a broken document whose kind reads Policy is ignored before its effect is read, so a deny-shaped document with only its kind wrong is ignored (Q-870); an ENABLED row whose policyDocument is null or blank is still skipped (Q-871). The gauge is not yet listed in docs/observability.md (Q-861).

---

## ADR-0132 - The router acknowledges after the broker acknowledged its send; the Event Center consumer is idempotent

**Date**: 2026-11-02
**Status**: Accepted (RTR-01, hardening wave 13; orchestrator decision D43; closes Q-823, Q-824 for the consumers it names and the rest of Q-783)

> Numbered 0132 by assignment at the wave-13 integration; RTR-01 supplied a draft (ADR-NEW-RTR-01). Orchestrator decision D43 is recorded in ADR-0131.

**Context**: The da-discovery router acknowledged the inbound record right after kafkaTemplate.send without waiting for the result, so a send that failed after the producer's delivery timeout lost the event (at-most-once on the typed topics, Q-823). Q-824 asked for an audit of the remaining consumers for event_id idempotence, because delivery is at least once (log-event.md 5.5).

**Decision**:
1. DiscoveryLogEventProcessor flushes the producer and waits for the send future for at most dlap.discovery.spine-publish-timeout (default 30 s, the same property the spine producers use) before it acknowledges. A refused, timed out, null or interrupted send throws; the record stays unacknowledged and the container retries it (1 s doubling to 60 s) and then dead-letters it. Meter dlap.discovery.router.send.failed{reason=timeout|error}. A timed-out send may still reach the broker later and publish the event twice; consumers already handle that. The producer flush is deliberate: without it each record waits out linger.ms (5 ms default in Kafka 4, 20 ms in the prod profile).
2. da-policyengine LifecycleEventStreamConsumer derives the feed id from the message (name-based UUID of the topic plus fireId, else eventId or event_id, else the payload text). EventSubscriptionService.processIncomingEvent ignores an id already in stream_event_ledger. A record that cannot be recorded is no longer swallowed: it is not acknowledged, so it is retried and then dead-lettered.
3. DeletionProofListener, ArchiveProofListener, AssetAccessedConsumer, AccessDecisionConsumer, LifecyclePolicyConsumer and the three catalog listeners were audited and found idempotent by their existing guards (status re-check under lock, conditional update, unique id plus existence check under an advisory lock, deterministic fire id, event_id claim, monotonic observation time). Each now has a named duplicate test.

**Consequences**: Router throughput per listener thread is bounded by the broker round trip: on an embedded single broker, 464 to 704 records/s per thread awaited (1.42 to 2.16 ms per record) against 12,944 to 15,590 records/s fire-and-forget (implementer's and reviewer's measurements; 63 records/s without the flush). A networked broker is slower per record; add partitions and listener concurrency if the spine ever needs more. kafkaTemplate.flush() runs on the shared template, so it also flushes the batches of the template's other publishers (harmless). Two messages of different content but the same fireId or eventId on one topic collapse in the Event Center feed (intended). Messages with no id are recognised by identical text, so two byte-identical id-less messages collapse into one feed row. The Event Center now rethrows persistence failures, so DLT traffic from group da-policyengine-event-stream-consumer after this change is previously silent loss surfacing.

**Integrator note (wave 13)**: merged clean as f6912521, third. The Event Center dedupe checks existsById before inserting; copies of one id share a key and therefore a partition, and a racing copy on another partition would be rejected by the primary key and skipped on retry. Duplicate fires still reach the action-topic consumers: da-reporting PolicyActionConsumer sends a notification and stores a row per delivery (Q-855), and the da-autodelete, da-autoarchival and da-autolabel action consumers are not audited for a repeated fireId (Q-856). The four existing discovery tests that switched to RouterKafkaMocks.acceptingTemplate() changed only their template mock.

---

## ADR-0131 - Per-resource coverage errors on AWS and Azure

**Date**: 2026-11-02
**Status**: Accepted (COV-03, hardening wave 13; orchestrator decision D43; extends ADR-0129; closes Q-830 and, with ADR-0129, Q-474 except the retry in Q-829)

> Numbered 0131 by assignment at the wave-13 integration; COV-03 supplied a draft (ADR-NEW COV-03). Orchestrator decision D43 (12-orchestrator-wave-plan.md) records the orchestrator calls in the wave-13 package specs: wave 12 accepted as integrated (UI types regenerated, PEP-03 recorded under D42 without its own ADR, four long package commit subjects kept). Wave 13: an unparseable deny AccessPolicy denies inside its readable scope, or everything when its scope is unreadable (policy-store-invalid), never fails open; unparseable allows may be skipped; P2D freshness on the event and PDP paths; the router acks only after the forward send succeeds, at a throughput cost; every remaining consumer gets a duplicate test. The package reports cite D43 also for copying COV-02's GCP per-resource rule to AWS and Azure (COV-03), for a heartbeat-age stuck-job alert, a drift flush counter only and no dashboard rows (OBS-02), and for UI-04's display and wording scope. OBS-02 and UI-04 supplied no ADR; their notes follow this entry's integrator note.

**Context**: ADR-0129 made a non-403 failure of one GCP bucket policy mark only that bucket UNKNOWN. The AWS and Azure collectors still ended the whole run on the same kind of error: a 404 for a bucket deleted mid-run, a 429 or 5xx on one bucket's GetBucketLocation, one region's DescribeTrails, or one account's diagnostic settings.

**Decision**:
1. AWS. A GetBucketLocation failure for one bucket that is not a refusal (any S3 status other than 403, or an SDK client error such as a timeout, after the SDK's own retries) leaves that bucket's region unknown; its observation is UNKNOWN with 's3:GetBucketLocation call for arn:aws:s3:::<bucket> answered <status or exception type>'. A DescribeTrails failure for one region (a non-403 CloudTrail error or a transport failure) makes the buckets of that region UNKNOWN with 'cloudtrail:DescribeTrails call for region <r>: <status>'. A trail whose status or selectors cannot be read (a CloudTrail error or a transport failure) stays unknown for the buckets it applies to, as before for refusals.
2. Azure. A non-403 failure of one storage account's diagnostic-settings read (404, 429, 5xx, timeout) makes that account UNKNOWN with 'Microsoft.Insights/diagnosticSettings/read call for <account id>/blobServices/default answered HTTP <status>'.
3. Unchanged and fail closed: S3 ListBuckets (any failure), STS GetCallerIdentity, the Azure storage account listing (a non-403 failure) and the Azure token. These end the collector, nothing is published and the catalog keeps its last value. A 403 on the Azure account listing still gives that subscription no observation.
4. An interrupted thread rethrows instead of becoming an UNKNOWN verdict.

**Consequences**: One throttled call costs the affected resources one collection cycle of UNKNOWN (never a false FULL); there is still no retry in the CloudTrail or ARM clients (Q-829). Because UNKNOWN is neither stale nor fresh and counts as unproven for destructive decisions (ADR-0130), a failing resource blocks destructive actions on itself only. A credential failure that appears only inside the per-resource loop makes every resource of that run UNKNOWN rather than failing the run, same as GCP (Q-845). The reason strings carry a status and a resource name, never a credential.

**Integrator note (wave 13)**: merged clean as 14ec1173, second, after OBS-02. COV-03's edit to deployment/iam/aws/reader/permissions.yaml outside its literal Owns changes two call-site line numbers only (S3BucketInventory.java 74 to 118 and 83 to 127, checked on the merged tree); no permission was added or removed, and no other wave-13 package edits the file. RTR-01 also edits da-discovery (logspine/DiscoveryLogEventProcessor), which no reader or Actor trace pins. S3BucketInventory.list() reads only the first ListBuckets page (pre-existing, Q-854).

**OBS-02 note (no ADR)**: OBS-02 (merged first, 549c8c25) adds the alerts ApprovalBreakGlassUsed (critical), ApprovalOutboxSendFailing, BackfillJobStuck, BackfillJobFailed and DriftStateFlushFailing with promtool unit tests, the counter dlap_logingest_drift_state_flush_failures_total, three cited doc fixes (Q-840, Q-831, Q-825) and the Q-788 precondition in AuditCheckpointContainerTest. Two calls recorded here at its request: (1) ApprovalBreakGlassUsed is critical although the severity legend said 'a component is down or failing for callers', because a break-glass release bypasses dual control; the legend now reads 'or a control was bypassed'. (2) Alert rules over counters that are created by their first increment also report a series that did not exist at the start of the window (m unless m offset W), because increase() needs two samples; the price is that a Prometheus with no history reports every non-zero such counter once in its first window (documented in docs/observability.md). BackfillJobStuck is a batch-progress proxy, not the heartbeat-age rule D43 specified, because no meter exposes progress.heartbeat (Q-846). The group dlap-backfill-drift must stay ahead of dlap-log-ingest in alerts.yml (HealthSnapshotServiceTest reads from dlap-log-ingest to the end of the file).

**UI-04 note (no ADR)**: UI-04 (merged last, 5203ff05; UI wave/13 a293fe4 merges 5e6c8df and bf18968) is display and wording: the expiry report prints readSignalText under Not read only and the coverage-checked date; the restore panel labels RULE_IN_FORCE, RULE_ENDED, RULE_ENDED_UNPROVEN and RESTORE_FAILED, shows the rule timing and the soft-delete window, words restore reasons and withdrawal outcomes, and offers Restore for an applied container rule awaiting proof (the service still answers 409 when it disagrees). In da-autodelete the task list fills ruleAppliedAt, ruleAgeDays and ruleEndsAt for an applied container rule awaiting proof, and docs/api/openapi/autodelete.json gains descriptions and nullable on the four fields. scheduledHardDeleteAt was already on DeletionTaskStatusDTO from an earlier wave (Q-623 was stale on the backend). The UI has no autodelete type generator (Q-865).

---

## ADR-0130 - Deny and destructive decisions fail closed on malformed or stale input; schedule slots wait for delivery

**Date**: 2026-11-02
**Status**: Accepted (POL-02, hardening wave 12; orchestrator decision D42; amends ADR-0081 decisions 1, 3 and 5; closes Q-436, Q-439, Q-441, Q-471, Q-472 and Q-431, and Q-475 on the sweep path)

> Numbered 0130 by assignment at the wave-12 integration; POL-02 supplied a draft (ADR-NEW-POL-02). Orchestrator decision D42 is recorded in ADR-0128.

**Context**: ADR-0081 made a malformed clause FALSE, so a deny AccessPolicy with a typo failed open (Q-441); an unknown operator in a map was silently ignored, so labels: {anyof: [PII]} matched every asset. Stored documents with Quartz crons became inert without a trace (Q-436). The sweeper recorded a slot even when sends failed (Q-439). On the event path the event's readCoverage and lastAccessedAt overrode the catalog's (Q-471). setRetention read stale reads at face value although it can lead to deletion (Q-472). A FULL coverage left behind by a failed collector run counted as FULL forever (Q-475).

**Decision**:
1. A clause is malformed when its name is unknown, its operator map is not a map, names no operator or an operator the clause does not define, an operator value has the wrong type, nameMatches is not a bounded RE2 pattern, or tagMatches is empty. A malformed clause is UNKNOWN in an AccessPolicy with effect deny and FALSE elsewhere. PolicyDslEvaluator.defects() is the single test, used by the evaluator, the start-up check and the docs-example guard.
2. At ApplicationReadyEvent, StoredPolicyValidator checks every live stored policy: AccessPolicies that do not parse or have defects, and documents whose schedule cron does not parse, are logged at WARN with row id and metadata.id and counted in the gauge policyengine.policies.invalid{check}. Nothing is rewritten; invalid crons stay fail-closed.
3. The full read-coverage rule applies to archive and delete actions, to setRetention (any spelling da-catalog accepts), and to every AccessPolicy. setRetention still needs no approval.
4. Under that rule, FULL counts only while readCoverageObservedAt is no older than dlap.policy.coverage.max-age (default P2D); an unparseable time is stale; a missing time keeps the ADR-0081 behaviour and is warned once per asset per sweep or event.
5. On the event path, a policy under the rule is judged on the catalog's readCoverage (unknown when the catalog does not answer) and on the later of the event's and the catalog's lastAccessedAt.
6. A schedule slot is recorded only when every published action was acknowledged by the broker within dlap.policyengine.schedule.send-timeout (default PT60S); a FAILED, failed-async or unacknowledged send keeps the slot due and is counted in FireCounts.failed (not persisted, since such a fire is never recorded).
7. Observe mode (dlap.pdp.default-decision=allow) keeps the operator's explicit allow default for UNKNOWN allow policies; deny policies still apply on UNKNOWN.

**Consequences**: Policies with mistyped operators that used to match everything stop matching (lifecycle) or start denying (deny). On a string clause, anyOf and noneOf together now both apply. A setRetention policy with a lastAccessedAt clause needs fresh FULL coverage before it fires. A broker outage keeps slots due and re-sweeps each tick until the broker returns or the next cron slot replaces the old one; consumers dedupe by fireId (a deterministic send failure re-sends every tick, Q-836). Freshness applies on the sweep path until the policy engine reads readCoverageObservedAt from the single-asset GET (Q-837). An AccessPolicy that does not parse at all is still skipped by AccessDecisionService.refresh, so an unparseable deny document fails open in observe mode (pre-existing, Q-842); the start-up check now logs and counts it.

**Alternatives rejected**: rewriting stored invalid crons (silently changes operator intent); an implicit deny for UNKNOWN in observe mode (defeats the purpose of observe mode); a per-asset retention comparison for setRetention (the engine does not see the asset's rule set); persisting a failed count column (a failed fire is never recorded, so it would always be 0).

**Integrator note (wave 12)**: merged clean as 5d726a3e, third, after COV-02. The activity-row field name matches COV-02 exactly (readCoverageObservedAt, ISO-8601 instant on AssetActivityRowDTO; read as text by CatalogActivityRowDTO). COV-02 also put readCoverageObservedAt and readCoverageSince on AssetOutputDTO, which the single-asset GET returns, so the catalog half of Q-837 is done; the policy engine's CatalogAssetDTO does not read them yet. The four edits outside the literal Owns list (ApprovalGate, PolicyActionProducer, CatalogActivityRowDTO, application.properties) are inside da-policyengine, which only POL-02 edits this wave.

---

## ADR-0129 - Coverage freshness, coverage start and per-bucket GCP coverage errors

**Date**: 2026-11-02
**Status**: Accepted (COV-02, hardening wave 12; orchestrator decision D42; relates to ADR-0047, ADR-0080 and ADR-0082; closes Q-444, the GCP half of Q-474, the catalog half of Q-475 and the exposure half of Q-434)

> Numbered 0129 by assignment at the wave-12 integration; COV-02 supplied a draft (ADR-NEW COV-02). Orchestrator decision D42 is recorded in ADR-0128.

**Context**: GcpCoverageRestClient asked for bucket policies without a policy version, so a bucket with IAM Conditions failed with a non-403 error, and any non-403 error aborted the collector for every project (Q-474). The activity row could not show when FULL coverage began, so the report could not say 'not read since X' (Q-434, Q-444), and it could not show how old a FULL was, which matters after a collector run that fails as a whole (Q-475).

**Decision**:
1. The bucket IAM read sends optionsRequestedPolicyVersion=3.
2. A non-403 failure on one bucket's policy (404, 429, 5xx, timeout) marks that bucket UNKNOWN with the HTTP status as the reason, and the run continues. A failure of the bucket listing or of the project and hierarchy reads still ends the collector, nothing is published, and the catalog keeps its last value (fail closed). An interrupted thread rethrows.
3. The activity row and the asset DTO expose readCoverageObservedAt (stored read_coverage_observed_at) and readCoverageSince (stored read_coverage_since) as ISO instants, null when unknown. readCoverageSince is reported only while coverage is FULL. A consumer that needs a fresh proof compares readCoverageObservedAt with its own clock.
4. The expiry report (contract 1.1, additive) appends readCoverageSince, readCoverageObservedAt and readSignalText. readSignalText is 'not read since YYYY-MM-DD' (UTC day of the start) only for a NOT_READ asset whose start is known. Without a start the report says no more than the state already says.

**Consequences**: Contract version moves 1.0 to 1.1; 1.0 positions are unchanged, so a positional CSV reader still works. A bucket whose policy cannot be read is UNKNOWN until the next run; a bucket already PARTIAL from other gaps stays PARTIAL, because the policy can only add gaps. There is no retry, so a transient 429 costs one run (Q-829). The same per-resource mapping for AWS regions and Azure accounts is still open (Q-830). The activity row always writes both fields (null when unknown); AssetOutputDTO omits them when null. A credential failure that happens only inside the bucket loop publishes UNKNOWN buckets rather than failing the run (never a false FULL; Q-845).

**Integrator note (wave 12)**: merged clean as e5d1bcf0, second. The UI API types for catalog and reporting were regenerated from the merged OpenAPI files on UI branch wave/12 and the new-ui gitlink bumped once. ExpiryReport.tsx does not render readSignalText yet (Q-832). AssetMapper.java is outside the literal Owns list; it is the only place the activity row is built and no other wave-12 package owns it.

---

## ADR-0128 - Ingest robustness: locked legacy polls, durable drift state, replay upgrade, duplicate-safe consumers, backfill metrics, byte-exact dead letters

**Date**: 2026-11-02
**Status**: Accepted (LOG-07, hardening wave 12; orchestrator decision D42; closes Q-363, Q-364, Q-404 and Q-550, and Q-558 and Q-783 in part)

> Numbered 0128 by assignment at the wave-12 integration; LOG-07 supplied a draft (ADR, next free number). Orchestrator decision D42 (12-orchestrator-wave-plan.md) records the orchestrator calls in the wave-12 package specs: after wave 11, lifecycle-aws passed in lean mode on GitHub (de3d48fa) and blocks again, no-dashes --all runs in CI (8dddbde8) and Q-399 is closed; the 5 remaining high questions all need a live account. Wave 12 takes the medium correctness and security set: malformed deny clauses are UNKNOWN; retention-shortening counts as destructive for the coverage rule (no approval); FULL older than P2D is UNKNOWN for destructive decisions; observe mode keeps the operator's explicit allow default; consumers dedupe on event_id (authorised log-event.md subsection); legacy polls under ShedLock; the reference PEP's decision cache keys on principal, action, resource, the sorted roles, the tier and a SHA-256 digest of the context (PEP-03, no separate ADR: the key shape is internal to the reference PEP and closes Q-461).

**Context**: Q-550, Q-364, Q-363, Q-783, Q-558 and Q-404 left da-logingest with doubled provider calls on two replicas, a drift report emptied by each restart, a replay store that kept a pass-through row after the corrected event was republished, no documented rule for duplicate publishes, no backfill meters and base64-quoted dead letters.

**Decision**:
1. The legacy CloudTrailLogSource and GcpLogSource polls run under ShedLock, one lock per source (logingest-legacy-aws-poll, logingest-legacy-gcp-poll), lockAtMostFor PT30M, lockAtLeastFor PT15S (covers replica clock skew). The registry pipeline keeps its per-stream advisory lock.
2. Unknown JSON paths and fill-ratio baselines persist in da-logingest Flyway V5 (unknown_path_observation, fill_baseline). Each instance restores on start, adds its own increments (counts summed, baseline GREATEST) every 5 minutes and on shutdown, and prunes rows unseen for 30 days. A baseline row is refreshed while the field is healthy, so a field that stays low retires after the retention window. The flush takes no cluster lock because it only adds.
3. A REPLAY-mode write of a full event upgrades a stored PARSE_STATUS_PARTIAL row of the same event_id in place, keeping its ingest_time. Any other duplicate is dropped. A seed-once replay (suppressRepublish) changes nothing, so the store never diverges from what was published.
4. Delivery is at least once: a kill can publish an event twice (log-event spec 5.5). Every state-changing consumer claims event_id before it applies an event; the da-discovery router is stateless and forwards a duplicate like the first copy. The catalog consumers and the router already met this; tests now pin it for seven event kinds and for the pass-through and full-event pair.
5. Backfill jobs expose dlap_logingest_backfill_records_read_total, _records_quarantined_total, _batches_total, _objects_failed_total{reason}, _job_duration_seconds{status} and dlap_logingest_backfill_jobs{status}. The binding label follows the bounded-label rule.
6. DlapKafkaFactoryBuilder.deadLetterRecoverer routes byte[] values through a ByteArraySerializer template copied from the service template's DefaultKafkaProducerFactory and all other values through the service template. A template that cannot be copied keeps the single-template behaviour and logs a warning. A dead-lettered undeserializable value is therefore the original bytes; anything that read the old base64 JSON string must change.

**Consequences**: V5 is the next logingest migration (LogIngestMigrationTest expects 1 to 5); DLT consumers or replay tools see raw bytes instead of a JSON string (no in-repo reader exists); each service gets a second Kafka producer for the byte-array template that lives for the process (Q-827); the byte-array factory copies the transaction id prefix, which would fence the service producer if a service ever enabled transactions (latent, Q-844); the drift report counts other replicas' sightings after their next flush and this instance's next restart (Q-828). Consumers outside da-catalog and the router are not yet audited for event_id idempotence (Q-824), and no alerts exist for the new series (Q-826).

**Integrator note (wave 12)**: merged clean as 6efbde4a, first. LOG-07's edits to deployment/iam/aws/reader/permissions.yaml and deployment/iam/gcp/reader/permissions.yaml change call-site line numbers only (ReaderRoleTraceabilityTest requires them after the ShedLock annotations moved the calls); COV-02 kept its own pinned GcpCoverageRestClient lines, so the two packages' trace edits do not overlap.

---

## ADR-0127 - The tree is dash-free; kept dashes are listed in an allowlist with a ceiling

**Date**: 2026-11-02
**Status**: Accepted (DOC-02, hardening wave 11; orchestrator decision D41; narrows Q-032 to the UI repository)

> Numbered 0127 by assignment at the wave-11 integration; DOC-02 supplied a draft (ADR-NEW-DOC-02). Orchestrator decision D41 (12-orchestrator-wave-plan.md) set the call this ADR implements: the em-dash cleanup covers everything except the append-only SESSION_LOG and DECISIONS.

**Context**: CLAUDE.md section 11 forbids the em-dash and en-dash everywhere. The added-lines guard only stopped new ones, so 1,525 remained in 245 files.

**Decision**:
1. Every tracked file outside new-ui holds no em-dash or en-dash, except files listed in scripts/ci-guards/pre/no-dashes.allowlist. Each entry is path|max|category|reason, where max is a ceiling on the number of lines with a dash.
2. Categories: append-only (docs/SESSION_LOG.md and docs/DECISIONS.md keep their past text; new entries must be dash-free), checksummed (a shipped Flyway migration or dlap_common upgrade delta: editing even a comment breaks the checksum), data (a fixture value, parser input or Unicode test that needs the character; none today).
3. no-dashes.sh keeps its added-lines mode for CI and gains --all for a whole-tree check. A listed file that gains a dash line fails, so kept dashes cannot spread; a listed file with fewer lines prints a note to lower or remove the entry.
4. Rewrites follow the sentence: colon after a label, comma pair or parentheses for an aside, plain spaced hyphen otherwise; meaning never changes.

**Consequences**: a new dash anywhere fails the added-lines guard in CI and the --all check locally. Orchestrator entries appended to SESSION_LOG and DECISIONS must be dash-free or the --all ceiling fails. A shipped migration with a dash can only be fixed by a new migration, not an edit (Q-817). The --all mode is not yet in CI (Q-815). The ceiling counts lines, so a dash replaced in place on a listed line is not caught (Q-822).

**Integrator note (wave 11)**: merged clean as 3208ac2f, last, as planned; no conflict with the four earlier merges, and none of their added lines carried a dash, so no other side needed a rewrite. The isolated commit 78655c5c (OPEN_QUESTIONS, OPERATING_PLAN, STATE_OF_THE_UNION) is kept (Q-813). `no-dashes.sh --all` passes on the merged tree before and after the wave-11 docs commit.

---

## ADR-0126 - The quickstart is one script, and its time is measured and published

**Date**: 2026-11-02
**Status**: Accepted (QS-01, hardening wave 11; orchestrator decision D41; CLAUDE.md section 10.7)

> Numbered 0126 by assignment at the wave-11 integration; QS-01 supplied a draft (ADR-NEW-QS-01-1, status proposed, amended in its review round). Orchestrator decision D41 put the quickstart timing in wave 11.

**Context**: CLAUDE.md section 10.7 requires a quickstart verified from a fresh clone. The README quickstart took 21 min 12 s from clone to the catalog page and its title had claimed "under 5 minutes". The causes were a 15 minute Gradle build that also builds and tests the UI frontend (built a second time in the UI Dockerfile), no wait for healthy containers, a 6.55 GB Ollama image the dev stack does not use, and stale facts. The review found that the backend-only mode scaled new-ui and gateway to 0, which still makes Compose build them.

**Decision**:
1. The quickstart is scripts/quickstart.sh. The README shows the one command and, below it, the same steps by hand. Tests (scripts/test-quickstart.sh, QS-T20 to QS-T23) fail when the README, the script and the compose file drift apart.
2. Gradle builds only the bootJar of the ten services and the UI shell, with :new-ui:buildFrontend and :new-ui:installFrontendDeps excluded. The compose file already serves the frontend baked into the UI image ahead of the jar's own resources.
3. The stack starts with ollama and ollama-model-pull scaled to 0 (the dev stack labels with the rules provider).
4. The script does not use `compose up --wait`: the dev compose health checks have no start_period, so a slow JVM reports unhealthy once and --wait gives up. The script polls container health and only an exited container or the time limit fails the run. Remove this when the compose gets start_period (Q-801).
5. The README states the measured wall time, what dominates it and what the figure leaves out. A time is never written as a goal: 5 min 46 s warm and 13 min 34 s with a cold Gradle cache on the stated machine.
6. Backend-only mode (--no-ui, or the fallback when the new-ui submodule cannot be fetched) names the services on the up line: postgresql, keycloak, kafka and the ten da-* services. new-ui and gateway are never selected, so their images are neither built nor started. In that mode only ollama is scaled to 0, because Compose rejects a scale of a service that is not selected (ollama-model-pull has no dependents). With the UI the whole project is started with both services scaled to 0.

**Consequences**: a clean-clone run is 3.7 times faster warm (21:12 to 5:46). The quickstart no longer claims five minutes. The script is a second place that names the ten services; QS-T04, QS-T06, QS-T07, QS-T09 and QS-T11 pin both start lines and compare the names with the compose pair. Getting under five minutes needs published images (Q-809).

**Integrator note (wave 11)**: merged clean as b8ea2eee. The quickstart was run again from a fresh clone of wave/11 at the integration; 352 s with the UI and 332 s backend only, both exit 0 (Q-811 resolved; details in the 2026-11-02 wave-11 SESSION_LOG entry).

---

## ADR-0125 - CI lean mode for the lifecycle harness, and a hosted profile for the soak

**Date**: 2026-11-02
**Status**: Accepted (CI-03, hardening wave 11; orchestrator decision D41; closes Q-765, Q-770 and Q-780, Q-787 in part)

> Numbered 0125 by assignment at the wave-11 integration; CI-03 supplied a draft (ADR-NEW-CI-03, with one sentence added to point 3 in its review round). Orchestrator decision D41 set the call this ADR implements: a lean CI mode under 6,000 MB, with lifecycle-aws in the gate's needs but advisory until it fits.

**Context**: the lifecycle-aws job died with exit 137 on a 7.9 GB runner when LocalStack started next to the whole stack (Q-787), and the 14,000,000 event soak needs 8.0 h and 20.7 GB (Q-780).

**Decision**:
1. cloud-lifecycle-e2e.sh has a CI lean mode (DLAP_E2E_CI_LEAN=1). e2e-tests/docker-compose.ci-lean.yml caps each Java heap with -Xmx in JAVA_TOOL_OPTIONS (an explicit -Xmx beats the Dockerfiles' MaxRAMPercentage; no collector flag may appear because the Dockerfiles pass -XX:+UseG1GC) and gives every container mem_limit with an equal memswap_limit, so swap cannot hide use. LocalStack's limit is a separate file because the service exists only in the aws retire overlay.
2. The retire phase stops the services it does not use before LocalStack starts: da-autolabel, da-autocompliance, da-reporting, da-admin-service, ollama. The list comes from tracing HTTP calls and Kafka topics. HTTP alone is not enough: the delete proof travels log event, da-discovery (DiscoveryLogEventProcessor), dlap.discovery.asset.deleted, da-autodelete (DeletionProofListener), so da-discovery stays.
3. The memory budget is a measured figure. The harness samples docker stats every 5 s and fails a lean run over 6,000 MiB. The measured peak is 5,111 MiB with 14 of 14 gates. Raising any limit in the overlay requires a new measurement. A lean run that took no sample fails, so a green lean run always carries a peak figure.
4. nightly-soak.yml defaults to a hosted profile (1,000,000 CloudTrail, 250,000 GCP, 250,000 Azure) in the shape of the measured lite run, with the arithmetic from 486.2 events/s and 1,482 bytes per event in the code. The full 14M load is a manual dispatch with a runner input.
5. iac-validate runs terraform validate and cfn-lint on deployment/iam from digest-pinned images and is in the CI gate.
6. clone_record in the aws retire phase checks that the copied row exists, not its status, so the sweeper cannot race it (Q-765).

**Consequences**: the lean peak is a property of the overlay, not of the services; heap ceilings can hide a leak as an OOM restart (ExitOnOutOfMemoryError makes it visible). The budget is enforced in MiB while D41 states MB (Q-800). The peak falls in the start phase with all containers up, and varied by about 600 MiB across runs on one host (Q-821). The hosted soak rate is unmeasured until its first dispatch (Q-795). Provider versions and cfn-lint's dependencies are not pinned (Q-797). lifecycle-aws turns blocking only after a green GitHub run (Q-787).

**Integrator note (wave 11)**: merged clean as 46780b5f. The lean aws run was repeated on the merged tree through the HARN-01 lock: 14 of 14 gates, peak 5,112 MiB (5,360 MB) at +268 s over 60 samples.

---

## ADR-0124 - The Azure archive Actor holds blob read and a tier-only blob write on named containers (amends ADR-0113)

**Date**: 2026-11-02
**Status**: Accepted (IAM-06, hardening wave 11; orchestrator decision D41; amends ADR-0113 point 3, follows ADR-0119 point 3 and ADR-0120; closes Q-761)

> Numbered 0124 by assignment at the wave-11 integration; IAM-06 supplied a draft (ADR-NEW-IAM-06, status proposed). Orchestrator decision D41 set the call this ADR implements: a separate Azure archive Actor with the two blob actions Azure requires, the delete Actor unchanged. ADR-0113 is not edited (the log is append-only); this entry is the amendment.

**Context**: Since EXEC-01 (ADR-0119), da-autoarchival reaches Azure Blob with DefaultAzureCredential, but no Azure Actor role covered it. Its listing, tier change, size read and restore stayed under out_of_role_calls. ADR-0113's never_grant banned blobs/read and blobs/write for every executor. Azure authorizes List Blobs and Get Blob Properties only with Microsoft.Storage/storageAccounts/blobServices/containers/blobs/read, and Set Blob Tier only with .../blobs/write (Microsoft, Permissions for Blob service operations). No narrower action exists.

**Decision**:
1. A separate archive Actor: its own instance of deployment/iam/azure/actor with executor=archive and a role_name that contains "archive" (enforced by a Terraform precondition). It holds exactly blobs/read and blobs/write, on the container scope, assignable to and assigned on the operator-named containers only, and only with object_actions=true. It holds no account action, because the management policy is rendered, never written. It shares no permission and no role definition with the delete Actor.
2. never_grant entries may name executors. blobs/read and blobs/write bind the delete Actor, and their exception text names the archive Actor as the single documented exception. ActorRoleTraceabilityTest fails on any other executor-scoped entry or any other held never_grant permission.
3. The write is narrowed with an Azure role assignment condition (ABAC, version 2.0), stored in the trace with its source and put by Terraform on the archive container assignments: every blob write that is not the Blob.Write.Tier suboperation meets a never-true expression (https://learn.microsoft.com/en-us/azure/storage/blobs/storage-auth-abac-attributes#sets-the-access-tier-on-a-blob). The read is not narrowed: Get Blob Properties and Get Blob are one "Read a blob" operation with no suboperation between them.
4. Every grant is traced: list (AzureManagementPolicyRuleProvider.java:124), size read (AzureCloudStorageService.java:148), tier change (:147) and restore (:199). The status reads (:238, :271) have no caller and are listed as unreachable. out_of_role_calls is empty and removed.
5. The guide recommends the native lifecycle management policy path: DLAP renders the rule, and the owner applies it with managementPolicies/write, so DLAP needs no blob data access and the archive Actor holds no role. Per-blob tiering is used for single-object scopes, the restore and the listing preview.
6. The delete Actor's actions, scopes and assignments are unchanged; its assignments carry no condition.

**Consequences**: The self-check expects the two actions of the archive executor on named containers when object-actions is true, and nothing when it is false. It cannot see the assignment condition (Q-793). One identity holding both Actors fails the check for each, which supports DEP-03's per-service identities. The condition text has not been applied to a live Azure assignment (Q-792). Rejected: merging the archive grants into the delete role (it would give the delete Actor blob write); granting Storage Blob Data Contributor (it allows content writes and deletes); an action-only condition with no OR expression (Microsoft documents conditions only with an expression).

**Integrator note (wave 11)**: merged clean as f620ba86. DOC-02's later merge rewrote comments and strings only, so the traced lines did not move: ActorRoleTraceabilityTest passes on the merged tree with an uncached run (see the wave-11 SESSION_LOG entry). The Actor trace needed no refresh.

---

## ADR-0123 - da-policyengine keeps the status of Spring MVC client errors and answers 409 for the same-name create race

**Date**: 2026-11-02
**Status**: Accepted (PE-02, hardening wave 11; orchestrator decision D41; completes ADR-0118, closes Q-757 and Q-758)

> Numbered 0123 by assignment at the wave-11 integration; PE-02 supplied a draft (ADR-NEW-PE-02-1). Orchestrator decision D41 (12-orchestrator-wave-plan.md) records the orchestrator calls in the wave-11 package specs: Q-757 maps only the live-name constraint to 409; Q-758 Spring client errors keep their status; Q-761 a separate Azure archive Actor; Q-759 and Q-774 closed as written (the full-stack drill runs quarterly and before go-live); the lean CI mode under 6,000 MB; the em-dash cleanup covers everything except the append-only SESSION_LOG and DECISIONS.

**Context**: ADR-0118 made GlobalExceptionHandler apply @ResponseStatus centrally and left two residuals: Spring MVC's own client errors (a malformed body, a path variable of the wrong type, an unsupported method) fell into the catch-all and answered 500 (Q-758), and the race of two concurrent creates of the same name, which both pass the name check and hit the partial unique index uq_policies_live_name (Flyway V9), answered 500 (Q-757).

**Decision**:
1. An exception that implements Spring's ErrorResponse (every Spring MVC exception and ResponseStatusException) answers its own status and headers (405 keeps Allow) in the module error shape {timestamp, status, error, message, path}. A 4xx carries Spring's detail text; a 5xx keeps its status with the fixed message 'An unexpected error occurred'; a 406 answers without a body.
2. A malformed or unreadable body (HttpMessageNotReadableException) answers 400 'Malformed request body'. A path or query value that does not convert (TypeMismatchException, which is not an ErrorResponse) answers 400 naming the parameter. Neither echoes the parser text, class names or the offending value.
3. A DataIntegrityViolationException whose constraint is uq_policies_live_name, and only that constraint, answers 409 with reason 'name-conflict' and a fixed message. The constraint is read from Hibernate's ConstraintViolationException; if Hibernate names none, the driver text 'violates unique constraint "uq_policies_live_name"' in the cause chain decides. A Hibernate constraint name that is another constraint always wins. Any other data integrity error stays 500 with the fixed message and a stack trace in the log only.

**Consequences**: Sequential and racing duplicate creates now answer the same 409 (the sequential one carries ConflictException's message, the racing one reason 'name-conflict' and no policy name). The same mapping covers any path that violates the live-name index, such as a rename in updatePolicy, though a commit-time race there is not proven (Q-790). No OpenAPI change: the error shape is unchanged; the 400 and 405 answers are not listed in the spec (Q-791). Tests: ClientErrorMappingWebMvcTest, GlobalExceptionHandlerClientErrorTest, LiveNameRaceContainerTest (PostgreSQL 15). If the index is renamed, LIVE_NAME_CONSTRAINT in GlobalExceptionHandler and LiveNameRaceContainerTest must change with it; the container test fails if they drift. This also settles the 405 and 415 half of Q-058 and the 400 half of Q-149 for da-policyengine.

**Integrator note (wave 11)**: merged clean as 01769927, first. No OpenAPI regeneration was needed (openapi-drift passes on the merged tree).

---


## ADR-0122 - Scale and soak harness: measured numbers only

**Date**: 2026-11-02
**Status**: Accepted (E2E-02, hardening wave 10; orchestrator decision D39; closes Q-742)

> Numbered 0122 by assignment at the wave-10 integration; E2E-02 supplied a draft (ADR-NEW-E2E-02, status proposed). Orchestrator decision D39 (12-orchestrator-wave-plan.md) set the call this ADR implements: flaws.cloud is downloaded only by the nightly workflow, never locally.

**Context**: The hardening plan asks for synthetic load of 10M CloudTrail, 2M GCP and 2M Azure records and for throughput, lag, Postgres growth and Kafka skew to be recorded, not claimed (E2E-02, D39). CI did not run the lifecycle harness (Q-742).

**Decision**:
1. Generators live in da-log-fixtures gen/scale/**, are seeded and deterministic from a seed and an end time, and need only the JDK. Nothing generated is committed; the only committed measurement output is e2e-tests/perf/results/ from lite runs.
2. CloudTrail enters through the production path: trail files in an S3 emulator read by one S3_TRAIL_FILES stream per account. GCP and Azure enter through the REST channel (POST /api/v1/logingest/events) as normalized events, because the log layer has no offline raw transport for them (the GCS sink has no endpoint override, there is no Azure transport). The raw record rides along as rawEvent, and a da-logingest test (ScaleRecordsParseTest) parses the raw GCP entries with the real parser.
3. The fixture replay seeder is not used for load: it is single threaded and blocks application start (ApplicationReadyEvent), which holds the stream pollers back.
4. run.sh has lite (200k + 50k + 50k, local, under the HARN-01 lock) and full (10M + 2M + 2M, nightly workflow only). A figure is written only when a run produced it; otherwise the report says "not measured". Named checks decide the exit status.
5. The perf stack sets spring.task.scheduling.pool.size (8) and the Hikari pool (40) through environment variables of the perf override, and the report states them, because both shape the numbers (defaults 1 and 10 starved the first runs).
6. The backfill resume test kills da-logingest with SIGKILL partway through a throttled backfill and compares the table with the generator's id list (counts by event_id and native_event_id, and the set difference).
7. flaws.cloud is downloaded at run time by the nightly workflow only, from the URL pinned in e2e-tests/perf/flaws-cloud.pin, checked against a pinned size and SHA-256 (while the pin says UNPINNED every run enforces the size only and prints the computed sha256), never stored in the repository (D8). The scheduled run always performs this check; a manual dispatch follows its boolean input. In GitHub expressions a schedule event has null inputs and null == false is true, so the flag is derived from github.event_name, not from comparing the input with false.
8. CI: job lifecycle-aws runs cloud-lifecycle-e2e.sh aws on a push to feature/deployment (Q-742); nightly-soak.yml runs the full mode on a weekly schedule until a first dispatch has measured its duration against the private-repo minutes pool (D15).

**Consequences**: At the measured lite rate (about 457 to 486 events/s end to end on a 20 CPU, 15.5 GiB Docker host) 14M events take about 8 hours and need about 20 GB of Postgres, so the full volume is unlikely to fit one hosted job; the workflow lets a manual run size the load (Q-780). Ingest throughput is bounded by synchronous per-event transactions with a Kafka send inside the transaction (LogEventStore), single threaded per stream. The flaws.cloud checksum stays unpinned until the first nightly run prints it (Q-784). Neither workflow has run on GitHub yet.

**Integrator note (wave 10)**: merged clean as 76550366. On wave/10 run.sh lite ran once through the lock (results kept outside the tree in D:/git/wt/w10-perf-results/lite-2026-11-02.md, not committed): 300,000 of 300,000 events stored in 569 s, 527.2 events/s end to end, median 504.5, best window 786.6, peak lag 17,336, Postgres +425.6 MiB (1,482 bytes per event), Kafka skew 1.13, quarantine 0.00 percent, all groups at lag 0; backfill resume killed at 10,281 of 40,000, job SUCCEEDED 159 s after the restart, 0 lost, 0 duplicated, 40,000 messages on the topic; 13 of 13 named checks. test-perf-lib.sh 43 passed; actionlint and actions-pinned pass. Neither workflow has run on GitHub.

---

## ADR-0121 - The point-in-time restore keeps the longer WAL copy and reads the WAL back; the Keycloak realm backup runs from the host

**Date**: 2026-11-02
**Status**: Accepted (DR-03, hardening wave 10; orchestrator decision D39; builds on ADR-0117 and ADR-0068; supersedes the wording of ADR-0117 point 4 that the quickstart realm backup is operator-run only; closes Q-752 and Q-751)

> Numbered 0121 by assignment at the wave-10 integration; DR-03 supplied a draft (ADR-NEW-DR-03-1, status proposed). Orchestrator decision D39 set the call: the Keycloak realm backup runs from a host cron job or a systemd timer.

**Context**: ADR-0117 recorded a point-in-time restore that exited 0 and replayed no WAL (Q-752), with the evidence lost, and a quickstart Keycloak realm that nothing backed up on a schedule because the agent has no Docker access (Q-751). Reproduction by hand showed the mechanism: the base backup's pg_wal.tar.gz holds the segment the backup ends in, cut at the end of the backup, while the archive copy of the same name goes on; restore-pitr.sh place() skipped any archived segment whose name already existed in pg_wal, so recovery stopped at the end of the shorter copy. The second DR-03 full-stack drill hit exactly that (segment 000000010000000000000006: 57,690 non-zero bytes in the base copy, 9,263,645 in the archive).

**Decision**:
1. restore-pitr.sh keeps, for every segment from the base backup's first, the copy with more non-zero bytes (the archive's on a tie) and counts replaced copies (REPLACED_BASE_SEGMENTS). A base copy longer than a partial archive copy is kept with a warning. restore_command stays 'false' (every segment is placed in pg_wal); the rule mirrors restore_command, which prefers the archive.
2. Before it reports ready, restore-pitr.sh reads the prepared pg_wal with pg_waldump from the START WAL LOCATION of backup_label and exits 1, removing the directory, when reading stops before the newest archived segment that holds WAL (more than 40 non-zero bytes). Skipped for target immediate, with --allow-gaps and a gap, with --no-replay-check, without pg_waldump, and when the archive holds nothing newer than the base backup. The report carries REPLAY_CHECK, BASE_START_LSN, REPLAY_STOP_LSN and NEWEST_SEGMENT. The full-stack drill requires REPLAY_CHECK=ok.
3. The quickstart Keycloak realm backup runs from the host: deployment/dr/keycloak-realm-backup.sh (lock, keycloak-realm.sh backup into a staging volume, shipping through DLAP_BACKUP_SHIP_CMD run on the host with SHA256SUMS.shipped last and retry of unshipped sets, retention that never removes an unshipped set, status.env, check for monitoring, settings file parsed and never executed). A cron.d file and a systemd service and timer are shipped in deployment/dr/cron and deployment/dr/systemd and shown line for line in the guide (section 8.7); a test compares them. The Docker socket is still not mounted into the agent. Moving the quickstart Keycloak to PostgreSQL is out of scope.

**Consequences**: A restore that would silently end at the base backup now fails closed. The check costs one more read of the archive (8 s prepare for 17 segments in the drill, against 7 s without it; Q-777); very large archives can use --no-replay-check. The realm's RPO in the quickstart is the schedule interval (24 hours by default, Q-776); Keycloak is stopped for the length of a container copy (6 s measured) and then needs its normal start-up time. The wrapper and the unit files are untested on a real VM and with a real object store (Q-775). Rejected: failing only through the drill (a production restore would still exit 0); mounting the Docker socket into dlap-backup (privilege escalation). Whether the drill runs nightly stays with the orchestrator (Q-774).

**Integrator note (wave 10)**: merged clean as fab27ae1. On wave/10 the drill ran through the lock (bash e2e-tests/with-harness-lock.sh --label w10-stack-drill -- bash deployment/dr/stack-drill.sh --out D:/git/wt/w10-dr-out): 57 checks passed, 0 failed, 673 s. RTO 208 s logical and 162 s PITR; RPO logical 116.9 s (35 markers), PITR 5.0 s (5 markers); REPLAY_CHECK=ok, REPLACED_BASE_SEGMENTS=0 on this run; the realm was exported and shipped by the scheduled wrapper and its check passed. 153 tables (200,299 rows) equal after the logical restore, 152 tables (200,303 rows) after PITR, Flyway history 54 rows identical. test-dr.sh --quick 340 passed, 0 failed, 2 skipped.

---

## ADR-0120 - The Azure delete Actor reads blob service properties; the self-check names the identity it checked (amends ADR-0113)

**Date**: 2026-11-01
**Status**: Accepted (IAM-05, hardening wave 10; orchestrator decision D39; amends ADR-0113; closes Q-743 and Q-727)

> Numbered 0120 by assignment at the wave-10 integration. IAM-05 supplied an amendment note for ADR-0113; DECISIONS.md is append-only, so the amendment is recorded as its own entry (as ADR-0114 did for ADR-0110). Orchestrator decision D39 set the call: the self-check keeps answering for the caller and says so; per-service identities stay with DEP-03.

**Context**: RET-08's Azure applier reads the account's blob service properties, but the Azure delete Actor did not grant Microsoft.Storage/storageAccounts/blobServices/read (Q-743). The GCP and Azure self-checks answer for the calling identity, not the Actor, and the report did not say so (Q-727). ADR-0113 point 6 described the runtime identity's sts:AssumeRole without a trace.

**Decision**:
1. Q-743: the Azure delete Actor holds Microsoft.Storage/storageAccounts/blobServices/read on the named storage accounts only (scope storage_account, executor delete, call site AzureBlobLifecycleRuleApplier.java:225), in the trace, the guide and the self-check stubs. Terraform yamldecodes it into the account role, assignable only to the named storage accounts.
2. ADR-0113 point 6 gets a trace: deployment/iam/aws/actor/permissions.yaml carries runtime_identity (sts:AssumeRole on the named Actor role ARNs, trust on the runtime role ARN with sts:ExternalId, session at most 3600 seconds), checked by ActorRoleTraceabilityTest. IAM-05 cited the da-logingest call until EXEC-01 merged; at the wave-10 integration (d60a8632) it cites the executors' own calls (ActorCredentials.java:175,191,193 in da-autodelete and da-autoarchival).
3. Q-727: the GCP and Azure self-checks keep answering for the calling identity. Every report names it: identityChecked, identityBasis (NAMED_ROLE for AWS, which simulates the configured Actor role ARN; CALLER for GCP and Azure) and identityNote; the guide says so. Nothing is impersonated, because token impersonation would hand da-admin-service the Actor's power. Per-service identities stay with DEP-03.

**Consequences**: The report record gains three additive JSON fields; docs/api/openapi/admin-service.json is regenerated (no UI types are generated for admin-service). identityChecked for GCP and Azure names the kind of identity, not the principal (Q-773). terraform validate and cfn-lint were not run (Q-770); the Terraform modules still only warn on a missing external id (Q-729).

**Integrator note (wave 10)**: merged clean as 9a5ba60d. At the integration the runtime identity cites the executors' calls (d60a8632, after EXEC-01's trace patch cf33e614); ActorRoleTraceabilityTest 13 of 13 and da-admin-service 255 tests, 0 failures on wave/10. admin-service.json passes openapi-drift; the UI generates no admin-service types, so no UI change was needed. Not changed at the integration: the Azure archive prose that still names the connection string (Q-761).

---

## ADR-0119 - The retire executors use one AWS SDK version, assume the Actor role with STS, reach Azure with DefaultAzureCredential and share one test-endpoint scheme that prod refuses

**Date**: 2026-11-02
**Status**: Accepted (EXEC-01, hardening wave 10; orchestrator decision D39; builds on ADR-0110, ADR-0113, ADR-0115, ADR-0116; closes Q-722, Q-724, Q-740, Q-745)

> Numbered 0119 by assignment at the wave-10 integration; EXEC-01 supplied a draft (ADR-NEW-EXEC-01, status Proposed). Orchestrator decision D39 set the calls: the executors move to AWS SDK 2.25.1 with sts (one version in the repo); Azure archive via DefaultAzureCredential, connection string deprecated and refused under prod; one test-endpoint property scheme, refused under prod.

**Context**: da-autodelete and da-autoarchival were on AWS SDK 2.20.162 while da-logingest and da-discovery use 2.25.1, and neither executor shipped the sts module, so the SDK default chain could not resolve a role_arn profile and the executors could not assume an Actor role (Q-722). da-autoarchival reached Azure only through a connection string that Azure RBAC does not govern and that may hold an account key (Q-724). Its test-endpoint names (RET-03) differed from da-autodelete's (RET-08), the harness passed neither to it (Q-740), and da-autodelete had no prod refusal (Q-745).

**Decision**:
1. One AWS SDK version in the repo: s3, rds, dynamodb, glacier move to 2.25.1 and software.amazon.awssdk:sts 2.25.1 (Apache-2.0) is added to both executors. com.azure:azure-identity 1.11.1 (MIT, the version da-autodelete uses) is added to da-autoarchival. AwsSdkSingleVersionTest fails if a module declares a different AWS SDK version.
2. Actor role credentials, configuration only, as ADR-0110 does for da-logingest: dlap.autodelete.aws.* and dlap.autoarchival.aws.* credentials-ref (an IAM role ARN, or the name of a variable that holds one, optionally env:NAME), external-id, sts-endpoint, sts-region. Empty keeps the SDK default chain, so an instance profile, environment keys, a shared profile or an EKS web identity token (IRSA) is the Actor. Set, the executor assumes the role with StsAssumeRoleCredentialsProvider, session name dlap-autodelete or dlap-autoarchival, with the default chain as the caller. A value that is no role ARN stops the start and the message never echoes it; the role and external id are never logged. One provider is shared by every S3, RDS and DynamoDB client of the executor. AWS SDK 2.25.1 closes a closeable credentials provider together with the client that holds it and the executors build a client per call in try-with-resources, so the shared provider is handed out behind a view that is not AutoCloseable and the bean closes the real one at shutdown.
3. Azure in da-autoarchival: blob data-plane calls (tier change, restore, status, listing) use BlobServiceClients signed with DefaultAzureCredential. The endpoint is dlap.autoarchival.azure.account-endpoint (https only), else https://<account>.blob.core.windows.net from the asset's location with the account name validated. dlap.autoarchival.azure.connection-string is a deprecated fallback used only when no endpoint is set; it logs a WARN at start-up and the prod profile refuses any value of it. The management-policy listing (a preview) still needs an explicit endpoint, as it needed a connection string before. da-autodelete already used DefaultAzureCredential.
4. One test-endpoint scheme in both executors: dlap.<service>.test-endpoint.{s3, s3-path-style, gcs, azure}, empty by default, TEST ONLY. The S3 test endpoint signs with the assumed role when one is configured, else with the default chain, else anonymously. The Azure test endpoint (autoarchival) is an https blob endpoint with a placeholder token, like the ARM stub of da-autodelete. da-autoarchival keeps its RET-03 names for one release as deprecated aliases: every @Value reads the new name first and the old one second (aws.endpoint-override, gcs.endpoint-override; aws.endpoint-access-key and aws.endpoint-secret-key keep signing for the S3 test endpoint), and EmulatorEndpointGuard logs a WARN per alias set that names its replacement. gcs.endpoint-project stays (it is the emulator's project, not an endpoint).
5. Both services refuse to start under the prod profile when any test endpoint (new or deprecated name) is set, and da-autoarchival also when the Azure connection string is set: da-autodelete TestEndpointGuard, da-autoarchival EmulatorEndpointGuard. The message names the property and never the value. Other profiles keep the endpoint (the harness and the container tests rely on it); each applier still logs its own WARN.
6. The aws lifecycle harness overlay gives da-autoarchival DLAP_AUTOARCHIVAL_TEST_ENDPOINT_S3 and the harness requires its start-up WARN to name the new property with no deprecated name in the log.

**Consequences**: The repo has one AWS SDK line. A deployment on a role in another account sets one variable per executor; IRSA needs none. The deprecated aliases and the Azure connection string are removed in the next release (Q-762). The Actor trace lists the three new SDK files and the moved call sites (the integrator applied EXEC-01's patch, cf33e614, Q-760), and the Azure archive Actor role does not exist yet, so the three Azure archive calls stay under out_of_role_calls until IAM adds a role (set-tier needs the blobs/write data action, which the trace's never_grant list currently forbids, Q-761). Rejected: building a provider per client (STS would be called for every client and the SDK would still close it), dropping the connection string at once (a one-release alias is cheaper than breaking a pilot), a shared credentials library (da-protos may not import provider SDKs; the two copies are 278 lines each and identical except names).

**Integrator note (wave 10)**: merged clean as 4e2e9c52. ActorRoleTraceabilityTest then failed as EXEC-01 predicted; the supplied patch applied cleanly on top of IAM-05 (cf33e614) and d60a8632 points runtime_identity at the executors' ActorCredentials calls; the class ran 13 of 13. On wave/10 da-autodelete ran 607 and da-autoarchival 663 tests, 0 failures, both ActorCredentialsMotoTest classes against motoserver/moto:5.1.4 included. cloud-lifecycle-e2e.sh aws passed all 14 gates on the first run (537 s; the clone_record race of Q-765 did not recur); da-autoarchival logged its start-up WARN naming dlap.autoarchival.test-endpoint.s3 with 0 deprecated names. OSV 703 runtime components (725 in wave 9), Dependency-Check 629 entries (647), both 0 at CVSS 7.0 or higher; Trivy image 0 on all ten service images; convergence OK over 12 boot jars.

---

## ADR-0118 - Every exception that declares a status keeps it across da-policyengine; approvals spec 1.2 (amends ADR-0112)

**Date**: 2026-11-01
**Status**: Accepted (PE-01, hardening wave 10; orchestrator decision D39; amends ADR-0112; closes Q-715 in part and Q-716)

> Numbered 0118 by assignment at the wave-10 integration. PE-01 supplied an optional amendment note for ADR-0112's consequences; DECISIONS.md is append-only, so it is recorded as its own entry. D39 accepted wave 9 as integrated and set the scope: 404 and 409 mapped centrally, every other unexpected exception stays 500.

**Context**: ADR-0112's consequences said that controllers other than PolicyController still answered 500 for 404 and 409 cases (Q-715), and docs/specs/approvals.md still described decisions as not chained and authors as not recorded (Q-716).

**Decision**:
1. GlobalExceptionHandler maps every exception class that declares @ResponseStatus centrally (AnnotatedElementUtils.findMergedAnnotation on the class, applied in the catch-all and in the IllegalArgument/IllegalState handler): ResourceNotFoundException answers 404 and ConflictException 409 for every controller; a 4xx carries the annotation reason or the exception message; a declared 5xx and every other unexpected exception answer 500 with the fixed message "An unexpected error occurred" and no stack trace, class name or exception text. AuditUnavailableException stays 503. PolicyController's local 404 and 409 handlers are removed; its audit handler stays.
2. docs/specs/approvals.md is v1.2: decisions and break-glass chained (govern.approval.decided, govern.approval.break_glass, key <id>:decision:<sub>, withdrawals <id>:withdrawn), 503 audit-unavailable on decisions, break-glass and withdraw, the replay rule, the orphan entry, authors recorded, the evaluation trigger excluded and the executors verifying; ApprovalSpecAuditChainTest pins the strings against the code.
3. Correction to ADR-0112 and Q-716: a decision whose transaction expires the request appends nothing (the expiry branch runs before any append). The orphan entry arises when an earlier attempt's commit was lost and the request then expired before the identical retry, which answers 409 not-pending (ApprovalDecisionAuditTest.aRequestThatExpiresBeforeTheRetryLeavesAnOrphanEntryAndTheRetryAnswers409).

**Consequences**: Any exception class carrying @ResponseStatus answers its status through the central handler (today only ResourceNotFoundException and ConflictException carry it in da-policyengine). The same-name create race (DataIntegrityViolationException on uq_policies_live_name) still answers 500 per D39 (Q-757). Spring MVC's own client errors (malformed JSON, bad path variable, 405) still fall into the catch-all and answer 500 (Q-758). Nine PolicyDraftController @PreAuthorize expressions repeat ROLE_POLICY_MANAGER, which makes those draft endpoints answer 400 for every caller (Q-756, high, pre-existing). No OpenAPI change.

**Integrator note (wave 10)**: merged clean as 5aa4e10c. On wave/10 da-policyengine ran 670 tests, 0 failures (PolicyEngineErrorMappingWebMvcTest, GlobalExceptionHandlerTest, ApprovalSpecAuditChainTest, ApprovalDecisionAuditTest on PostgreSQL); openapi-drift regenerated all 11 contracts with no difference and 0 possible breaking changes against f229fbd1, so the error mapping changed no contract. The duplicate authority in PolicyDraftController (Q-756) is pre-existing and was not touched by the integration.

---

## ADR-0117 - The restore drill runs on the real quickstart stack and records RTO and RPO as numbers

**Date**: 2026-11-01
**Status**: Accepted (DR-02, hardening wave 9; orchestrator decision D38; builds on ADR-0068)

> Numbered 0117 by assignment at the wave-9 integration; DR-02 supplied a draft (ADR-NEW-DR-02-1, status proposed). Orchestrator decision D38 (12-orchestrator-wave-plan.md) ran the unrun plan packages AUD-02a, IAM-04 and DR-02 in wave 9; DR-02 is the first full-stack restore drill with a measured RTO.

**Context**: DR-01 proved backups and restores on a database tier with simulated Flyway history. The G2 gate needs a restore of the product: services, Keycloak and the audit chain, with a measured restore time and a measured data point.

**Decision**:
1. The drill (deployment/dr/stack-drill.sh) brings up the dev compose pair with the pilot backup overlay (the real agent) and the e2e override, destroys it with docker compose down -v, and rebuilds it twice from the off-host volume alone: logical (restore.sh) and point in time (restore-pitr.sh). It holds the HARN-01 lock and uses the harness project name. The services are stopped before the backup so that the live database can be compared exactly (row count and content digest per table, Flyway history); this is a property of the test, the production logical backup runs with the services up.
2. RTO is the time from the destroyed stack to a verified stack (all ten services 200 UP, login, audit-verify, digests), with phases recorded; provisioning, clone, builds and fetch from a real store are excluded and stated as such. RPO is the newest marker (a row written once a second from a session of its own) that the restored stack holds against the newest the lost stack wrote.
3. Comparisons with recorded state are made before the services start; a restore's exit status alone is never accepted (the first full run showed a PITR that exited 0 and replayed nothing, Q-752).
4. The quickstart Keycloak keeps its state in the container (H2, no volume). deployment/dr/keycloak-realm.sh exports the realm from a docker commit snapshot with kc.sh export, encrypts it with the backup passphrase, and restores it through an import volume; the plain realm never reaches the host disk. It is an operator-run tool: the agent has no Docker access and the socket is not mounted into it (Q-751). The pilot's Keycloak is on PostgreSQL and needs none of this.
5. Audit streams without a running writer are seeded through the INSERT trigger as the stream's writer role, so the entries are accepted only if they extend the chain correctly; govern.approval is written by the real service (service-account withdraw).

**Consequences**: The drill takes about 11 minutes and 6 GB of memory and needs the service jars; it is a quarterly and pre-go-live check, not a CI job. The realm backup interval is the Keycloak RPO in the quickstart. The drill must be revisited now that AUD-02a chains approval decisions and the executors write their streams (Q-753). The customer-infrastructure drill (G2 wording) is still to run (Q-754).

**Integrator note (wave 9)**: on wave/9 the drill ran once more through the lock: 54 checks passed, 0 failed, 651 s. RTO 199 s logical and 159 s PITR; RPO logical at the backup point (40 markers, 114.5 s), PITR 3 markers (3.1 s); redo reached the newest archived segment, so Q-752 did not recur. Flyway history held 54 rows (51 plus the three wave-9 migrations) and govern.approval 3 real entries, since AUD-02a chains decisions. The guide's Q-NEW-DR-02 placeholders were renamed to Q-751 to Q-753 in the wave-9 docs commit.

---

## ADR-0116 - Unproven native rules end at the maximum age; test endpoints for the retire appliers; Azure versioning is read

**Date**: 2026-11-01
**Status**: Accepted (RET-08, hardening wave 9; orchestrator decision D38; builds on ADR-0109 and ADR-0108; closes Q-695, Q-699, Q-700, Q-713 (with ADR-0115), Q-714, Q-698 and in part Q-694)

> Numbered 0116 by assignment at the wave-9 integration; RET-08 supplied a draft (ADR-NEW-RET-08). Orchestrator decision D38 set the calls this ADR implements: unproven applied container rules also end after the maximum age (RULE_ENDED_UNPROVEN, Q-695); a test-only endpoint override for the appliers, guarded by a WARN only, and the rule lifecycle in the aws harness (Q-713, Q-714); the Azure versioning read (Q-694); the versioning read stays under dry run (Q-698).

**Context**: ADR-0109 ended only RULE_IN_FORCE records at dlap.autodelete.rule-in-force.max-age, so an applied bucket, prefix or container rule that no provider event ever proved kept deleting until someone restored it (Q-695). A restore of an applied rule whose apply SUCCEEDED record was still pending left the chain without it, because the sweeper never claims a RESTORED record (Q-699). Every withdraw 404 read as ENDPOINT_NOT_AVAILABLE, even the policy engine's own not-found (Q-700). The appliers could reach an emulator only through network aliases of the real AWS host names and a throwaway CA (Q-713), so no harness covered rule apply, proof, rule end, the versioned-bucket refusal or the withdraw (Q-714). Azure blob versioning was not read (Q-694). Planning under dry run reads versioning once per container rule (Q-698).

**Decision**:
1. The rule-end job (ShedLock autodelete-rule-in-force-end, SKIP LOCKED) also claims a record still AWAITING_PROOF whose disposition is an applied, non-dry-run native container rule (S3, GCS or Azure lifecycle) in AWAITING_PROOF or PROOF_OVERDUE, once max-age has passed since applied_at. It removes the rule without an approval and the record ends RULE_ENDED_UNPROVEN (Flyway V9, an end state). The removal is audit-chained as retire.delete.rule_ended_unproven (REQUESTED, then SUCCEEDED or FAILED, details.proven=false), logged as a WARN and counted by the counter dlap.autodelete.rules.ended_unproven. A refused removal waits for retry-after; a rule that is not found ends RESTORE_FAILED. Engine deletes and BigQuery table expirations are never claimed. A later proof still marks the disposition EXECUTED but does not move the record.
2. The job keeps its retry time in soft_delete_records.rule_end_retry_at (V9) for both RULE_IN_FORCE and unproven records; next_check_at belongs to the sweeper (the proof-overdue check of an AWAITING_PROOF row).
3. RuleRemover.remove writes a pending retire.delete.apply SUCCEEDED record first, then the intent, then removes the rule. When the pending record cannot be written nothing is removed and the record does not change.
4. A withdraw answer 404 whose body reason is not-found is REQUEST_NOT_FOUND; any other 404 and every 405 stay ENDPOINT_NOT_AVAILABLE. The restore stands either way.
5. dlap.autodelete.test-endpoint.s3 (with s3-path-style, default true), .gcs and .azure are TEST-ONLY endpoints of the appliers, empty by default. While one is set its applier logs a WARN at start-up. S3 uses the default credentials when they resolve, else anonymous; GCS uses no credentials; Azure accepts only an https Resource Manager endpoint and sends a placeholder token. A malformed value stops the service at start-up.
6. The Azure applier reads the storage account's blob service properties; isVersioningEnabled true is ENABLED and the executor refuses VERSIONED_BUCKET_UNSUPPORTED; false or absent is UNVERSIONED; no answer is UNKNOWN. New permission Microsoft.Storage/storageAccounts/blobServices/read (not yet in the Azure Actor trace, Q-743). Mock-tested only (Q-744).
7. The versioning read under dry run stays (documented, no code change).
8. cloud-lifecycle-e2e.sh aws gains a retire phase on LocalStack S3 (e2e-tests/docker-compose.lifecycle-aws.yml) with six gates: versioned_refused, withdrawn, rule_apply, rule_in_force (a CloudTrail trail file through the S3_TRAIL_FILES transport), rule_ended and rule_ended_unproven. DLAP_E2E_AWS_RETIRE=0 skips it.

**Consequences**: No applied DLAP rule outlives the maximum age, proven or not; an unproven end is a WARN and a metric an operator should alert on (Q-748), because it means the log source never showed the rule firing. The test endpoints are configuration a production deployment must never set (only a WARN guards it, per D38; Q-745). Azure accounts with blob versioning cannot be retired by a native rule until a live capture. Flyway V9 in da-autodelete. The task list gains the status RULE_ENDED_UNPROVEN; the restore answer can carry REQUEST_NOT_FOUND. Rejected: reusing next_check_at for the retry (it would mark a disposition PROOF_OVERDUE early through the sweeper), and a separate event type for the proven end (kept retire.delete.rule_ended unchanged). Upgrade note: RULE_IN_FORCE rows an earlier release deferred through next_check_at lose that wait after V9 and are retried at the next rule-end run.

**Integrator note (wave 9)**: on wave/9 da-autodelete ran 585 tests with 0 failures (RuleInForceExpiryContainerTest 5, S3LifecycleRuleApplierContainerTest 8, AutodeleteFlywayValidateTest 11), and cloud-lifecycle-e2e.sh aws passed all 14 gates in 548 s, the six retire gates included, with no throwaway CA; audit-verify.sh then reported 6 streams, 0 broken, with two retire.delete.rule_ended_unproven records. Its moved call sites made IAM-04's trace test fail until 0b728a10 (Q-726).

---

## ADR-0115 - Archive end states, a live GCS scope index, conditional claimer outcomes and an emulator guard (da-autoarchival)

**Date**: 2026-11-01
**Status**: Accepted (RET-09, hardening wave 9; orchestrator decision D38; builds on ADR-0100, ADR-0106, ADR-0111; closes Q-708, Q-709, Q-710, Q-711, Q-712 and the da-autoarchival side of Q-713)

> Numbered 0115 by assignment at the wave-9 integration; RET-09 supplied a draft (ADR-NEW-RET-09, status Proposed). Orchestrator decision D38 set the calls: a RULE_REMOVED task status and a GCS partial unique index in autoarchival Flyway V4 (Q-708, Q-710); identical recurring archive scopes are already-held, not refused (Q-711). Choices of RET-09 beyond D38, accepted by the reviewer and open for orchestrator confirmation (Q-737): the task status ALREADY_HELD, no task row for an all-held policy fire, the already-held event time is the holding row's creation time, rowUpdated=false on a lost conditional update.

**Context**: ADR-0111 left six residuals. A task whose assets all ended RULE_REMOVED rolled up as RESTORED, which reads as if objects were restored (Q-708). The reset cannot read a DIRECT_OBJECT row or an Azure render-only row (Q-709). The duplicate-scope check for GCS rules reads before the row is stored, so two approvals planned in the same instant both pass (Q-710). A recurring policy for an unchanged scope was refused with an audit refused record on every fire (Q-711). The claimer's own outcome writes were plain saves, so a reset of a slow worker's row could be overwritten (Q-712). The archival appliers had endpoint overrides but nothing kept them out of production (Q-713).

**Decision**:
1. Flyway V4 (da-autoarchival). The task status CHECK gains RULE_REMOVED and ALREADY_HELD. A partial unique index ux_archive_rules_live_gcs_scope covers (provider, scope_bucket, scope_prefix, native_class, after_days) for rows with provider GCP, mode NATIVE_RULE, not mocked, in PLANNED, EXECUTING, APPLIED or EXECUTED. Before creating it, V4 looks for existing duplicates and, if any exist, stops with a message naming each group and its rule ids and statuses, changing nothing. The README gives the recovery steps (set the extra rows REMOVED by hand; never restore them through the API, because that would remove the provider rule the kept row relies on).
2. RULE_REMOVED task status. When a restore ends every asset RULE_REMOVED (no failure, no genuinely restored asset) the task ends RULE_REMOVED, not RESTORED. A task with at least one restored asset and no failure is still RESTORED; any failure makes it PARTIALLY_COMPLETED. A RULE_REMOVED task is not restorable again.
3. Already held is already done. A fire or request for a scope an identical live GCS row holds raises DuplicateScopeException (a RuleException with reason DUPLICATE_SCOPE, so the dry run and the pre-approval check still answer 409). Task submission turns it into the asset status ALREADY_HELD, asks nobody to approve, and appends one retire.archive.already_held record (SUCCEEDED, correlation id = the holding row's approval id). The event is built from the holding row alone (event time = the row's creation time truncated to milliseconds, idempotency key holdingRuleId:already-held:assetId), so later fires append the identical event and the audit log returns the first entry. A policy fire whose assets are all held stores no task; an operator request stores a task with the new status ALREADY_HELD; mixed requests keep a task for the new assets and count held assets as neither pending nor failed.
4. The loser of a same-instant race on the index (DataIntegrityViolationException on insert with no row for the approval or fire) is reported as the same DuplicateScopeException naming the winner. Inside a caller's open transaction (the operator submit path) the failed insert still aborts that transaction, so that caller sees an error and repeats (Q-738); a policy fire has no outer transaction and gets the clean answer.
5. The claimer's APPLIED, RENDERED and FAILED writes (NATIVE_RULE, DIRECT_OBJECT, and the audit-unavailable path after the claim) are one conditional update each on status EXECUTING (finishIfExecuting, failIfExecuting). A lost update keeps the row as the reset left it and logs an error. The provider effect is still audited (rule_applied, rule_rendered, failed), with rowUpdated=false in the details when the row was not changed.
6. Rows the reset cannot read stay a manual fix. The README lists the provider checks and two conditional UPDATE statements (APPLIED when the object already has the class, FAILED otherwise and for every Azure render-only row). The reset still answers 409 UNREADABLE.
7. Test-only emulator endpoints (Q-713). The existing properties dlap.autoarchival.aws.endpoint-override, aws.endpoint-access-key, aws.endpoint-secret-key, gcs.endpoint-override and gcs.endpoint-project now reach the direct-object storage services as well as the lifecycle rule providers; Azure uses the Azurite connection string. EmulatorEndpointGuard refuses to start under the prod profile when any of them is set (or when the Azure connection string names the Azurite development store); the message names the property, never the value.

**Consequences**: A task list can now show RULE_REMOVED and ALREADY_HELD, and the audit log a new event type; the OpenAPI status field is a free string, so no spec changed (openapi-drift passes) but the UI must learn the two words (Q-737). A slow worker whose row was reset to FAILED (rule absent) may still have written the rule at the provider; the audit record says so and the row is not overwritten, but nothing finds that rule automatically (Q-739). V4 can stop a deployment that already holds duplicate live GCS rows; that is intended and documented. Test fixtures that planted several live rows on one scope now use one prefix per row. The archival and delete executors use different property names for their test endpoints, and da-autodelete has no prod-profile refusal (Q-740, Q-745).

**Integrator note (wave 9)**: on wave/9 da-autoarchival ran 625 tests with 0 failures (ArchiveRulesPostgresTest 24, ArchiveFlowContainerTest 10); V4 applied on a fresh database in every harness stack and the gcp, azure and aws lifecycle harnesses passed. The harnesses still do not exercise ALREADY_HELD, the race or the conditional writes, and no compose file passes the archival test endpoints (Q-740).

---

## ADR-0114 - BackfillRunner caps and quarantines read failures like StreamRunner (amends ADR-0110)

**Date**: 2026-11-01
**Status**: Accepted (LOG-06, hardening wave 9; orchestrator decision D38; amends ADR-0110 point 1 and its consequences; closes Q-702, Q-704, Q-705, Q-706 and Q-707)

> Numbered 0114 at the wave-9 integration. LOG-06 asked for no new ADR but for an amendment of ADR-0110 point 1 ("BackfillRunner is unchanged (Q-702)"). DECISIONS.md is append-only, so the amendment is recorded here and ADR-0110 is left as written. Orchestrator decision D38 carried the LOG-05 residuals (Q-702, Q-704 to Q-707) into LOG-06.

**Context**: ADR-0110 capped and quarantined read failures in StreamRunner only; BackfillRunner retried an unreadable object forever (Q-702). ProcessedObjectStore.attempts() had a default of 0, and ScopedObjects.forJob inherited it, so a backfill could never have reached a cap (Q-706). An S3 object above max-object-bytes could be sampled as a valid prefix and quarantined as INVALID_JSON (Q-705). The guide lacked examples, the attempts semantics and the invalid credentials-ref behaviour (Q-704, Q-707).

**Decision**:
1. ADR-0110 point 1, last sentence, now reads: Applies to the GCS and AWS transports. BackfillRunner counts and quarantines the same way (Q-702): the shared ReadFailureQuarantine counts in processed_object.attempts under the job's pseudo stream backfill-<job id>; below dlap.logingest.transports.max-read-attempts the job attempt fails and retries after its backoff; at the cap the object is quarantined as READ_FAILED, checkpointed under the job and counted in the job's quarantined figure, and the job goes on. Resetting a quarantined unreadable object stays a documented SQL step (docs/guides/log-sources.md 5.3).
2. ProcessedObjectStore.attempts() is abstract (Q-706), so a store cannot silently skip the cap; ScopedObjects.forJob delegates to the job's scope.
3. An S3 object above max-object-bytes is always quarantined as OVERSIZE_RECORD; max-object-bytes is never below the binding's max-record-bytes, and the oversize sample is max-record-bytes + 1 bytes (Q-705).
4. The attempt count keeps including ingest failures and transient read errors; there is no read_attempts column (Q-704). An invalid credentials-ref fails its partition at every poll until fixed, with nothing quarantined or counted (Q-707). The guide and the sample application.properties document both.

**Consequences**: A backfill job skips an unreadable object after max-read-attempts failed reads (default 5). With backfill.max-attempts at or below max-read-attempts minus 1 a job can end FAILED before the cap (documented, not validated, Q-734). The GCS sink still skips an oversize shard without a quarantine record (Q-735). READ_FAILED stays a detail until Q-701.

**Integrator note (wave 9)**: on wave/9 da-logingest ran 2,293 tests with 0 failures and 148 skipped (BackfillRunnerReadFailureTest 9, S3OversizeObjectTest 5, AwsS3RegionAndRoleLocalStackTest 3, QuarantinePipelineIntegrationTest 7), and the cross-cloud pipeline harness passed.

---

## ADR-0113 - Actor roles per retire executor, traced to call sites, with a provider-API self-check

**Date**: 2026-11-01
**Status**: Accepted (IAM-04, hardening wave 9; orchestrator decision D38; builds on ADR-0088 (IAM-03 Reader roles); closes Q-697 in part and Q-703)

> Numbered 0113 by assignment at the wave-9 integration; IAM-04 supplied a draft (ADR-NEW-IAM-04, status proposed). Orchestrator decision D38 ran the unrun plan package IAM-04 in wave 9 and named Microsoft.Storage/storageAccounts/blobServices/read for the Azure versioning read; IAM-04 left it out because no executor read existed on its base (see the integrator note and Q-743).

**Context**: The delete and archive executors (da-autodelete, da-autoarchival) change customer buckets, tables and storage accounts, but no executor IAM existed in the repository (Q-697, Q-694), so grants were hand-written and could drift from the code. The Reader roles (IAM-03) already prove a pattern: one permissions.yaml per cloud as the single source of truth, each permission tied to its call sites, Terraform reading it with yamldecode, and a test that fails on drift.

**Decision**:
1. Each cloud has deployment/iam/<cloud>/actor/permissions.yaml. A permission names its scope, its executors (delete, archive) and every call site (file, line, call text). The executors of a permission must equal the modules of its call sites.
2. An Actor holds only the calls the executors make on their approved paths. Calls that need no grant are recorded, not granted: tolerated_denials (size probes that continue on a denial, so delete Actors never read content), unreachable_calls and unreachable_sources (code the planner never reaches), out_of_role_calls (credentials Azure RBAC does not govern).
3. Scopes are named resources only: buckets (and their objects), BigQuery datasets, storage accounts and containers, all validated against ARNs and wildcards. Object actions are a separate scope, off by default. Nothing is granted on *, a project, folder, organization or subscription. Content permissions no executor may hold (version deletes, Object Lock bypass, BigQuery table data, Azure blob read and listKeys) sit in never_grant.
4. Deploy forms: Terraform for AWS, GCP and Azure, one module instance per executor; AWS CloudFormation with AWS::LanguageExtensions Fn::ForEach writing one inline policy per named bucket. da-admin-service ActorRoleTraceabilityTest keeps traces, Terraform and CloudFormation equal and fails on any wildcard object grant.
5. The self-check (GET /api/v1/permission-check and /{cloud}, ADMIN only) asks each provider's own dry-run API: AWS iam:SimulatePrincipalPolicy on the Actor role ARN (named buckets, an object probe key, a bucket nobody named, and account never-grant entries on *), GCP testIamPermissions on named buckets and one table per dataset, Azure Microsoft.Authorization/permissions on named accounts and containers. It reports missing and extra permissions against the committed trace (the jar carries a copy) and lists Reader permissions apart. The probes live under permissioncheck/providers/<cloud>/ and use no provider SDK. Every dlap.permission-check.* key defaults to empty, so every cloud reports NOT_CONFIGURED until an operator configures it.
6. DLAP's runtime identity (IRSA role or instance profile role) holds only sts:AssumeRole on the customer's DLAP role ARNs; every customer Reader and Actor role trusts that role ARN, never an account root, with a per-customer sts:ExternalId generated by the operator (Q-703).

**Consequences**: A new executor call without its permission, a moved call site, or a widened grant fails the build. RET packages that edit traced files must refresh line numbers in the same change. Operators can verify applied roles from the running system. The GCP and Azure checks answer for the caller, so they test the identity da-admin-service runs as or the token it is given (Q-727). The executors cannot assume an Actor role until they ship the sts module (Q-722). The Terraform modules only warn on a missing external id, CloudFormation refuses (Q-729). Live checks on real accounts are pending (Q-728).

**Integrator note (wave 9)**: after the RET-09 and RET-08 merges ActorRoleTraceabilityTest failed on 31 call sites whose lines those packages moved (Q-726); integrator commit 0b728a10 moved each line and the guide's call-site columns to the lines the test named, adding and removing no permission, and da-admin-service then ran 246 tests with 0 failures in the uncached wave run. D38's Azure blobServices/read grant for RET-08's new versioning read was tried and backed out, because the self-check stubs in AzurePermissionsProbeTest and PermissionCheckServiceTest must grant it too (Q-743).

---

## ADR-0112 - Policy history, and approval decisions in the audit chain

**Date**: 2026-11-01
**Status**: Accepted (AUD-02a, hardening wave 9; orchestrator decision D38; answers Q-687, Q-692 and Q-689, and Q-690 on the pilot side; extends ADR-0094, ADR-0095 and ADR-0108)

> Numbered 0112 by assignment at the wave-9 integration; AUD-02a supplied a draft (ADR-NEW-AUD-02a, status Proposed). Orchestrator decision D38 set the calls: approval decisions chained and fail closed (Q-687), idempotent audit replay (Q-692), policyengine Flyway V9 (Q-689). Q-692's wording "commits with the stored entry's time and reason" is implemented as "with the stored time, when the retry sends the same reason" because the chain holds only the reason's hash (Q-717, for orchestrator confirmation).

**Context**: Policies were edited in place and hard deleted, so a deleted or changed rule left no trace, and the stream govern.policy (seeded by AUD-01) had no writer. Approval decisions were immutable rows but not chained; only withdrawals were (ADR-0108), so the chain showed withdrawals but not who approved (Q-687). An audit entry whose own transaction failed to commit could block a retry forever, answering 500 (Q-692). Flyway V8 went to APPR-03 (Q-689).

**Decision**:
1. Policy changes are versioned. Every create, update, status change and delete adds one immutable row to dlap_policyengine.policy_versions (Flyway V9). Change types: CREATED, UPDATED, STATUS_CHANGED, DELETED, plus BASELINE, the state of a policy created before V9, recorded by SYSTEM at its first change. The row stores the exact snapshot JSON of the content (sorted keys, explicit nulls, schema dlap-policy-version/1); its SHA-256 is contentSha256. Triggers refuse UPDATE, DELETE and TRUNCATE of versions, any version that does not follow the newest one, and any version after a DELETED one.
2. Policies are soft deleted. A delete disables the policy and sets deleted_at and deleted_by_user_id; a trigger then freezes the row. A hard DELETE, a TRUNCATE and lowering current_version are refused. @SQLRestriction("deleted_at IS NULL") hides deleted policies from every entity query. Names are unique among live policies only (partial index).
3. Every policy change is chained in govern.policy, in the transaction of the change (draft publish included, since it creates a policy). Event type govern.policy.<change>; actor is the JWT sub, a service, or SYSTEM. Details: version, contentSha256, previousContentSha256, name, status. Key: <policyId>:v<n>:<32 hex of the change hash>. The version row stores the entry's seq and hash, so a changed version no longer matches the chain.
4. Approve, reject and break-glass are chained in govern.approval. Event types: govern.approval.decided (APPROVE, REJECT) and govern.approval.break_glass. Key: <approvalId>:decision:<sub>. The entry holds the sub and the SHA-256 of the comment, never its text, and not the volatile approval count. Decision ids are name-based per (request, approver).
5. One pattern for all governance appends (GovernanceAudit). Read the entry already stored under the change's key, then build the change at that entry's occurredAt, else the clock's time. Append last in the transaction, or replay the stored entry when its identity fields match. Any failure (AuditException, IllegalArgumentException, unreadable chain, mismatching prior entry, no writer) throws AuditUnavailableException: the transaction rolls back and the API answers 503 audit-unavailable. GlobalExceptionHandler maps AuditUnavailableException for every controller, so no path answers 500 for it; approvals map it through ApprovalException.
6. Read API: GET /api/v1/policyengine/policies/{id}/versions (no content) and /versions/{n} (with content). @PreAuthorize allows ADMIN, POLICY_MANAGER, DATA_ENGINEER, DATA_SCIENTIST, DATA_ANALYST and AUDITOR.
7. The pilot overlays state the audit route, DLAP_AUDIT_DATASOURCE_URL for da-policyengine; the TLS overlay uses verify-full with the pilot CA.

**Consequences**: The policy engine now needs dlap_common for every decision, policy edit and draft publish, not just withdrawals; without it those calls fail closed with 503. An entry whose own commit failed stays in the chain and is replayed only by an identical retry; a retry with another reason or comment answers 503 until the original text is sent (Q-717). A created policy's orphan entry names an id that never existed, because a retried create gets a new id; a decision whose transaction instead expires the request also leaves an orphan entry; an update refused 409 for a name conflict leaves an orphan BASELINE entry (Q-720). Hard deletes of policies are impossible for every role, the owner included; tooling that deleted policy rows must disable them instead. The chain holds no free text, so a version's content is checked by hashing the stored snapshot. GET, PUT, PATCH and DELETE on an unknown policy now answer 404 in PolicyController (was 500), other controllers still answer 500 for 404 and 409 cases (Q-715). Rejected alternatives: chaining the full policy document (the record is limited to 32 KiB and free text is not allowed); optimistic locking only (it would lose the gapless guarantee); writing the chain entry after the commit (it would allow a committed change without an entry); a 503 handler per controller (a new controller reaching a governance append would silently answer 500).

**Integrator note (wave 9)**: on wave/9 the uncached gw test ran da-policyengine 648 tests with 0 failures (ApprovalDecisionAuditContainerTest 3, PolicyHistoryContainerTest 6 on Docker). The aws lifecycle harness's two-user approvals left 7 govern.approval.decided entries and 3 govern.policy.created entries, and audit-verify.sh reported 6 streams, 0 broken; DR-02's drill restored 3 real govern.approval entries. On the TLS pilot the policy engine ran with DLAP_AUDIT_DATASOURCE_URL over verify-full and a service-account withdrawal appended govern.approval.withdrawn at seq 1 (audit-verify OK). The integrator regenerated the UI policyengine.ts (UI 4c34d2e). openapi-drift reports one possible breaking change: DELETE on a policy answers 204 instead of 200.

---

## ADR-0111 - Archive follow-ups close the claimed-row, restore and duplicate-scope gaps (da-autoarchival)

**Date**: 2026-11-01
**Status**: Accepted (RET-07, hardening wave 8; orchestrator decision D37; builds on ADR-0100, ADR-0106; closes Q-668, Q-669, Q-670, Q-672, Q-673 and Q-674)

> Numbered 0111 by assignment at the wave-8 integration; RET-07 supplied a draft without a number. Orchestrator decision D37 (12-orchestrator-wave-plan.md) set the wave-8 calls; for the archive path it carried the six RET-05 residuals (Q-668 to Q-674) into this package unchanged. The reviewer accepted the design choices beyond D37 recorded below (reset marks a present GCS rule already_present; a task of RULE_REMOVED assets rolls up as RESTORED; a restore of an EXECUTING row is refused; DIRECT_OBJECT and unreadable-provider rows answer 409 UNREADABLE).

**Context**: RET-05 (ADR-0106) left six residuals: a row can stay EXECUTING after a worker dies (Q-669), a fired rule's restore ended RESTORATION_FAILED (Q-670), the unproven-rule list was noisy for long-age rules (Q-672), two rows could share one id-less GCS rule (Q-673), the proof update and the restore save could overwrite each other (Q-674), and rows applied before Flyway V3 read already_present=false (Q-668).

**Decision**:
1. Reset. POST /api/v1/archival/rules/{ruleId}/reset, ADMIN only, works only on EXECUTING. It reads the provider first and never applies. Present: APPLIED with applied_at taken from the claim time. Absent: FAILED with RESET_RULE_ABSENT. Single-object rows and providers DLAP cannot read are refused (409 UNREADABLE). The intent is audited before the row changes (fail closed), the outcome after (retire.archive.reset_requested, retire.archive.rule_reset). A present GCS rule is recorded already_present=true because it cannot be told from a customer's identical rule, so a later restore leaves it (the safe direction of Q-608).
2. RULE_REMOVED is a normal end state. The asset keeps it and its message; the restore step is audited retire.archive.restore_rule_removed; a task whose assets are all restored or RULE_REMOVED ends RESTORED (Q-708).
3. Reconciliation counts a rule unproven only after max-applied plus its own after_days; the scan is capped at 5,000 rows and logs when it reaches the cap.
4. Planning refuses a second row for an identical live GCS scope (provider, bucket, prefix, class, age; statuses PLANNED, EXECUTING, APPLIED, EXECUTED; not mocked) with DUPLICATE_SCOPE naming the existing row. The check also runs in the dry run (409) and before an approval is asked. It reads before the row is stored, so it is not atomic (Q-710).
5. markExecutedIf and markRemovedIf are conditional updates on the loaded status. A restore of an EXECUTING row is refused. A restore follows a concurrent APPLIED to EXECUTED change once. The claimer's own outcome writes in ArchiveExecutor stay plain saves (Q-712).
6. Q-668 is accepted for the pilot with an operator check in da-autoarchival/README.md before the first restore of a GCS row applied before V3.

**Consequences**: A recurring GCS policy that fires again for an unchanged scope is refused each time with an audit refused record (Q-711). No Flyway migration was added (V3 stays the latest for da-autoarchival). The OpenAPI contract gains one operation. Audit failure on the reset outcome record and on rule-removal records is logged and not undone, as in the existing executor. The lifecycle harness does not exercise the reset, the duplicate-scope refusal or the conditional updates; unit and real-Postgres tests do (including a 20-round two-thread race).

**Integrator note (wave 8)**: the integrator committed RET-07's untracked ArchiveAuditResetTest on pkg/RET-07 as 7fa5c27f before the merge; on wave/8 da-autoarchival ran 576 tests with 0 failures (ArchiveRulesPostgresTest 17, ArchiveAuditResetTest 3), and the gcp and azure lifecycle harnesses passed. The harnesses do not exercise the reset, the duplicate-scope refusal or the conditional updates.

---

## ADR-0110 - Read failures are capped and quarantined; NUL is handled at the stores; S3 transports assume roles with STS

**Date**: 2026-11-01
**Status**: Accepted (LOG-05, hardening wave 8; orchestrator decision D37; builds on ADR-0105; closes Q-661, Q-662, Q-665, Q-666, Q-667 and Q-684)

> Numbered 0110 by assignment at the wave-8 integration; LOG-05 supplied a draft (ADR-NEW-LOG-05, status Proposed). Orchestrator decision D37 set the calls this ADR implements: NUL and repeated read failures quarantine centrally; sts is added for role credentials. D37 asked for the quarantine reason READ_FAILED; the frozen log_event.proto has no such value, so the reason is PARSER_EXCEPTION with a READ_FAILED detail until Q-701 is decided.

**Context**: An object that could not be read (AccessDenied, an unusable KMS key, a corrupt shard) was retried at every poll forever, because the exception left the iterator outside the per-batch handling and processed_object.attempts was never used (Q-665). A U+0000 in a record failed the PostgreSQL text or jsonb insert and stopped the partition the same way (Q-661). The S3 transports refused credentials-ref, so cross-account roles and EKS IRSA were unavailable (Q-662).

**Decision**:
1. A transport throws ObjectReadException (object reference, URI, cause) from the iterator and leaves it positioned past that object. StreamRunner counts each failure in processed_object.attempts. At dlap.logingest.transports.max-read-attempts (default 5) it delivers a quarantine record to every QuarantineSink (reason PARSER_EXCEPTION, reason_detail starting READ_FAILED and naming only the exception class, raw_ref naming the object, no content), checkpoints the object as DONE with 0 records and 1 quarantined, and continues the partition. Before the cap the partition stops with the cursor unmoved. If the quarantine write fails the object stays for the next poll. Applies to the GCS and AWS transports; BackfillRunner is unchanged (Q-702).
2. NUL handling is central: QuarantineBounds (used by the table and topic sinks) strips U+0000 from excerpt, detail, native id and raw reference; LogEventEntity strips it from every text column and escapes it as the six-character text in raw_event values and keys on persist and update, for every writer of log_events. raw_sha256 stays the hash of the original bytes. The AWS transports' local workaround is removed.
3. software.amazon.awssdk:sts 2.25.1 (Apache-2.0) is added. credentials-ref for the S3 transports is an IAM role ARN or the name of a variable holding one; the service assumes it with StsAssumeRoleCredentialsProvider using the default chain or the named profile as the caller, session name dlap-logingest, optional external-id and sts-endpoint location keys. Web identity (EKS IRSA) works through the default chain. Only SdkS3LogStore imports the SDK.
4. LocalStack tests pin the image by digest (the manifest list of localstack/localstack:3.4) and document the pre-pull; region discovery and STS AssumeRole run against LocalStack (Q-684, Q-667).
5. docs/guides/log-sources.md sections 5.1 to 5.4 list the transports (Q-666).

**Consequences**: One unreadable object costs max-read-attempts polls and one READ_FAILED quarantine row, then the stream goes on; the object is read again only by a backfill. READ_FAILED is a detail, not a QuarantineReason value, until the proto gains one (Q-701). The attempt count also includes earlier ingest failures of the same object version and transient read errors (Q-704). An excerpt that lost a NUL is not replayable from the table. The reader role must be allowed to assume the target role in the customer account; sts:AssumeRole on the service identity is a deployment concern (Q-703). The reader-role trace pins SdkS3LogStore.java call-site lines, so edits to that file must update deployment/iam/aws/reader/permissions.yaml and docs/guides/cloud-permissions.md together (LOG-05's review fix did so: lines 137, 153 and 170).

**Integrator note (wave 8)**: on wave/8 the full gw test ran every LOG-05 class (da-logingest 2,271 tests, 0 failures; AwsS3RegionAndRoleLocalStackTest 3 and QuarantinePipelineIntegrationTest 7 on Docker) and da-discovery ReaderRoleTraceabilityTest (19) with the moved call sites; OSV and Dependency-Check scanned the sts jar with 0 blocking findings; the retire round trip read trail files and access-log objects through the S3 transports with 0 quarantined records.

---

## ADR-0109 - A native delete rule is age-based, ends after a maximum age, and is refused on a versioned bucket

**Date**: 2026-11-01
**Status**: Accepted (RET-06, hardening wave 8; orchestrator decision D37; builds on ADR-0101, ADR-0104, ADR-0107; closes Q-677, Q-656, Q-682, Q-657, Q-683, Q-680 and the da-autodelete side of Q-676)

> Numbered 0109 by assignment at the wave-8 integration; RET-06 supplied a draft (ADR-NEW-RET-06). Orchestrator decision D37 set the calls this ADR implements: container delete rules must be age-based; RULE_IN_FORCE ends after a configurable maximum age (default P30D) with the rule removed, audit-chained, no approval; versioned buckets are refused for native delete rules until a live capture; GCS rule removal matches normalized fields and reports RULE_NOT_FOUND; dry-run-cloud-delete stays on. D37 wrote the withdraw path as /api/v1/approvals/{id}/withdraw; that is shorthand for the approval API base path, and both RET-06 and APPR-03 use /api/v1/policyengine/approvals/{id}/withdraw (Q-693).

**Context**: ADR-0107 made a proven bucket, prefix or container rule RULE_IN_FORCE, but nothing ended it: a date-based S3 rule, a GCS createdBefore rule or a zero-day Azure rule kept deleting every matching object, new ones included, until someone restored it (Q-677). An applied container rule waiting for its proof could not be stopped at all. An S3 expiration on a versioned bucket only adds delete markers, which are UPDATEs since ADR-0104, so it could never be proven (Q-656), and the matcher still accepted S3.CREATE.DELETEMARKER (Q-657). GCS removal matched by SDK equality and could report a removal that did not happen (Q-682). The idempotency guard covered only AWAITING_PROOF and PROOF_OVERDUE (Q-683). The approval withdrawal at restore depended on the restorer being allowed to decide (Q-676).

**Decision**:
1. A container rule is age-based: S3 Expiration.Days, GCS age, Azure daysAfterModificationGreaterThan (snapshots and versions daysAfterCreationGreaterThan), N = dlap.autodelete.native-rule.container-expiration-days, default 1, never less. A date-based or zero-day rule is refused when the hard delete is planned and again before it is applied, reason DATE_RULE_UNSUPPORTED (date-rule-unsupported).
2. A RULE_IN_FORCE record whose rule was applied longer ago than dlap.autodelete.rule-in-force.max-age (default P30D, measured on the injected Clock from applied_at) has its rule removed by the executor and ends RULE_ENDED (Flyway V8). The job is a ShedLock singleton (autodelete-rule-in-force-end) that claims each row FOR UPDATE SKIP LOCKED. Removing a rule deletes nothing, so it needs no approval; it is audit-chained as retire.delete.rule_ended (REQUESTED, then SUCCEEDED or FAILED). A refused removal leaves the record in force and is retried after dlap.autodelete.rule-in-force.retry-after (PT6H). Gauges dlap.autodelete.rules.in_force and dlap.autodelete.rules.restore_failed count the records; the task list shows ruleAppliedAt, ruleAgeDays and ruleEndsAt.
3. An applied container rule still AWAITING_PROOF is restorable; the restore removes the rule. A later proof marks its disposition EXECUTED but never moves a RESTORED, RULE_ENDED or RESTORE_FAILED record.
4. A native delete rule on a bucket whose versioning is Enabled or Suspended is refused, VERSIONED_BUCKET_UNSUPPORTED, read at planning (before approvers are asked) and right before the apply. An answer the platform did not give is never read as unversioned: the row waits, except under dry run, which records UNKNOWN. S3 is read with GetBucketVersioning (new permission s3:GetBucketVersioning), GCS from the bucket resource. Azure account versioning is not read yet (Q-694).
5. Every applier's remove() says whether every DLAP rule of the stored document was found. GCS rules are found by a normalized key (action type, storage class, age, prefixes, createdBefore and the other condition fields), not SDK equality. When not every rule is found the restore answers ruleRemoved=false, the record ends RESTORE_FAILED with reason rule-not-found, and the outcome is logged and audited FAILED; the same applies to the end of the maximum age.
6. A disposition with applied_at is never applied again, whatever its status; outside AWAITING_PROOF and PROOF_OVERDUE the row is refused already-applied and audited.
7. S3.CREATE.DELETEMARKER is no lifecycle signature.
8. A restore withdraws a pending HARD_DELETE approval through the policy engine's service-account-only POST /api/v1/policyengine/approvals/{id}/withdraw (ADR-0108) with the executor's own token and onBehalfOf = the restorer's JWT subject, never the restorer's token. 404 or 405 is ENDPOINT_NOT_AVAILABLE: logged, recorded in the restore details, and the restore stands (Q-700). A 409 is APPROVAL_ALREADY_DECIDED:<status> (ALREADY_CLOSED is retired).

**Consequences**: A container rule now deletes only objects at least a day old and stops by itself after 30 days, so a pilot never has an unbounded rule. Versioned buckets cannot be retired by a native rule until a live capture shows how such a rule is proven. Under dry run the planner makes one read call per container rule for its versioning (Q-698). RESTORE_FAILED needs a person to check the bucket. An unproven applied container rule is not covered by the maximum age (Q-695). The UI does not yet show the age or the new states (Q-696). Executor IAM outside this repository needs s3:GetBucketVersioning (Q-697). A restore can lose a pending apply-SUCCEEDED append (Q-699). Flyway V8 in da-autodelete; the da-autodelete OpenAPI contract gains three task fields.

**Integrator note (wave 8)**: on wave/8 the retire round trip ran da-autodelete with dry-run-cloud-delete=false against LocalStack S3 (reached under the real S3 host names through network aliases and a throwaway CA, Q-713): planning read UNVERSIONED for the target bucket and refused a versioning-Enabled and a versioning-Suspended bucket with versioned-bucket-unsupported before any approval was asked; after two approvals the executor applied an S3 rule with Expiration.Days 1 and no Date; a CloudTrail trail file delivered by AWS-01's transport proved it (RULE_IN_FORCE, task row with ruleAppliedAt, ruleAgeDays and ruleEndsAt); after a configured maximum age of PT15M the executor removed the rule from the bucket without an approval and the record ended RULE_ENDED, audited retire.delete.rule_ended REQUESTED then SUCCEEDED. A zero-day container age stopped the service at start. The deployed generator cannot produce a date-based rule, so that refusal is covered by unit tests only.

---

## ADR-0108 - A service account can withdraw a pending approval request (WITHDRAWN)

**Date**: 2026-11-01
**Status**: Accepted (APPR-03, hardening wave 8; orchestrator decision D37; extends ADR-0095; answers Q-676 on the policy-engine side)

> Numbered 0108 by assignment at the wave-8 integration; APPR-03 supplied a draft (ADR-NEW-APPR-03). Orchestrator decision D37 set the call this ADR implements: APPR-01 gains a service-account-only withdraw, WITHDRAWN is terminal. D37 wrote the path as /api/v1/approvals/{id}/withdraw, shorthand for the existing base path /api/v1/policyengine/approvals. Flyway V8 of da-policyengine, which section 8 of the plan reserved for AUD-02a, is used here; AUD-02a moves to V9 (Q-689).

**Context**: A pending HARD_DELETE approval outlives the reason for it when the person restores the record it waits on (Q-621). The executor cannot end the request: a service account never decides (approvals spec section 2 rule 4), and the person who restores is often barred from deciding by self-approval, a narrowed approver-roles setting or a missing MFA marker. RET-04 therefore withdrew with the restorer's own REJECT, which fails exactly when the restorer is the requester, and the request then sat pending until it expired (Q-676).

**Decision**:
1. Add POST /api/v1/policyengine/approvals/{id}/withdraw, callable only with ROLE_SERVICE_ACCOUNT (@PreAuthorize, and checked again in the service). Body: reason (at least 10 characters) and onBehalfOf (the sub of the person it acts for, required).
2. Add the terminal state WITHDRAWN, reachable only from PENDING under the row lock. It is not a decision: no decision row, no separation-of-duties check, no approver role or MFA marker. It is as immutable as REJECTED or EXPIRED, never executable and never releasable, so executors that accept only APPROVED and APPROVED_BREAK_GLASS need no change.
3. Store withdrawnBy (the token sub), onBehalfOf, withdrawReason and withdrawnAt (the injected Clock; equal to resolvedAt) on the request. Flyway V8 enforces that the four exist together with the state and never change afterwards.
4. Answers: 200 the request; 409 {"status": <current approval status>} when it is not PENDING (an overdue pending request is expired first and answers EXPIRED), with the HTTP number in httpStatus (Q-688); 404 when it does not exist; 400 for a bad body; 403 for any other caller.
5. Every withdrawal appends govern.approval.withdrawn to the AUD-01 stream govern.approval before the transaction commits. If the append fails the withdrawal is rolled back and the call answers 503 audit-unavailable (fail closed). The chain holds the SHA-256 of the reason, not the text; the DECIDED topic event carries who and for whom, not the reason. Metric policyengine.approvals.withdrawn{actionClass}.

**Consequences**: Executors can end a request they made pointless, whoever the restorer is. The set of states grows by one; consumers that switch on status must treat WITHDRAWN as terminal and not executable. The policy engine now depends on the audit database for this one operation (Q-690). Decisions are still not chained (Q-687). An orphan audit entry after a failed commit can block a later withdrawal of the same request (Q-692). Rejected alternatives: letting the executor send a REJECT (breaks the rule that a service account never decides), a hard DELETE of the request (the table forbids it and history is the point), and making WITHDRAWN a flavour of REJECTED (it would count as a decision by an eligible approver). docs/specs/approvals.md is v1.1.

**Integrator note (wave 8)**: on wave/8 the retire round trip (SESSION_LOG wave-8 entry, step g) restored a record waiting for its HARD_DELETE approval as DIRECTOR; da-autodelete's service account withdrew the request on the director's behalf: status WITHDRAWN, withdrawnBy the dlap-backend service account's subject, onBehalfOf the director's subject, a reason and withdrawnAt; a second withdraw answered 409 with status WITHDRAWN, an ADMIN user token 403, a late APPROVE 409 not-pending; govern.approval.withdrawn was appended and audit-verify reported the chain intact. The UI policy engine types were regenerated for the new fields (UI 4fe79f7).

---

## ADR-0107 - A rule in force is not a deleted bucket; restore removes the rule; the RET-02 review follow-ups

**Date**: 2026-11-01
**Status**: Accepted (RET-04, hardening wave 7; orchestrator decision D36; builds on ADR-0101, ADR-0102, ADR-0095; closes Q-617, Q-619, Q-624, Q-631, Q-632, Q-633, Q-635, Q-636 and Q-621 in part)

> Numbered 0107 by assignment at the wave-7 integration; RET-04 supplied a draft (ADR-NEW-RET-04). The draft's placeholder "ADR-NEW-RET-04" remains in da-autodelete/README.md and proof/ProofRecorder.java until a follow-up replaces it (Q-679). Orchestrator decision D36 (12-orchestrator-wave-plan.md) set the calls this ADR implements: Q-617, FULL read coverage stays enforced at the hard delete; Q-631, a bucket or container rule's first proven child delete marks the disposition EXECUTED but the record gets a rule-in-force state, not HARD_DELETED; dry-run-cloud-delete stays on.

**Context**: ADR-0101 decision 5 marks a disposition EXECUTED, and its record HARD_DELETED, when the provider's own delete event proves it. For a lifecycle rule on a whole S3 or GCS bucket or Azure container that event is the first child object the provider deletes; the bucket stays and other objects may not have expired yet, so "HARD_DELETED" overstated what happened (Q-631). The RET-02 review also left a dry run listed as COMPLETED (Q-636), a restore list that DIRECTOR and POLICY_MANAGER could not read (Q-624), refusals and policy dry runs without an audit record (Q-632), an apply that was repeated when its outcome record failed to append (Q-633), a dead provider class (Q-635), a KPI reading a table nothing writes (Q-619) and a hard-delete approval left pending after a restore (Q-621).

**Decision**:
1. A proof on a container-level native rule (S3, GCS, Azure lifecycle) means "the provider rule is proven to fire". The disposition becomes EXECUTED with that proof, as before, but the soft-delete record becomes the new state RULE_IN_FORCE (Flyway V7), with hard_deleted_at empty. HARD_DELETED is kept for targets that are gone: an object, a blob, a BigQuery table. RULE_IN_FORCE holds the asset (a second delete joins the row), stays on the would-delete list and is shown to users as RULE_IN_FORCE.
2. RULE_IN_FORCE is restorable. The restore removes only DLAP's own rule (rule ids dlap-retire-..., dlapretire...), keeps every other rule on the bucket or account, deletes the lifecycle configuration when DLAP's was the last rule, and says that objects the rule already expired are gone and are not restored. Intent is audited before the removal; a refused removal leaves the record RULE_IN_FORCE and is audited. NativeRuleApplier gains remove(); BigQuery table expiration has none.
3. Read coverage FULL stays enforced at the hard delete and not at soft-delete admission (Q-617, ADR-0101 decision 3 confirmed; the da-autodelete README documents it).
4. A restore of a record in HARD_DELETE_PENDING_APPROVAL withdraws its HARD_DELETE request. The published approval API has no withdraw operation; one REJECT by an eligible approver ends a request, and a service account never decides, so the restore sends that REJECT with the restoring person's own bearer token. The outcome (WITHDRAWN, ALREADY_CLOSED, REFUSED:<reason>, UNREACHABLE, NOT_APPLICABLE) is returned and audited; it never blocks the restore, because a RESTORED record is never executed.
5. The outcome of an apply is written best-effort after the effect: if the retire.delete.apply SUCCEEDED append fails, the disposition is saved as applied with success_audit_pending (V7), the sweep is not rolled back, and the next sweep writes the missing record without calling the provider. A disposition with applied_at is never applied twice (idempotency by disposition id; the guard in HardDeleteExecutor.decide fires for AWAITING_PROOF and PROOF_OVERDUE, every reachable path, Q-683).
6. Every refusal and every policy dry run is a retire.delete record: task assets with invalid ids, task rows that cannot be processed, approvals that could not be requested, refused restores (refused, with the machine reason) and policy dry runs (dry_run, stage policy_dry_run, executed false). Caller-supplied identifiers are cut to 512 characters before they become audit targets.
7. The task list shows a dry-run record as DRY_RUN with an empty error list, a rule in force as RULE_IN_FORCE, and never COMPLETED for either. GET /api/v1/deletion/tasks allows ADMIN, CDO, DATA_ENGINEER, POLICY_MANAGER, DIRECTOR and the service account, so everyone who can restore can find what to restore; the UI constants SOFT_DELETE_LIST_ROLES and RESTORE_ROLES match.
8. The storage-reclaimed KPI sums soft_delete_records.size_bytes (the catalog's size at admission, refreshed at the hard-delete step) over HARD_DELETED records only. An unknown size counts as zero and a rule in force counts nothing, so the figure is a lower bound. dlap_deletion_audit is no longer read.
9. The uncalled GcpDeletionProvider, its interface and the three types only they used are removed.

**Consequences**: A pilot sees "rule in force" instead of a deleted bucket, and a restore can stop the rule, but a rule keeps expiring everything in the bucket, new objects included, until it is removed; nothing ends a rule in force on its own yet (Q-677). The restore needs the extra permission to delete a lifecycle configuration (S3: covered by PutLifecycleConfiguration; Azure: managementPolicies/delete). The withdrawal works only when the policy engine lets the restorer decide (Q-676). Rule removal is verified against moto for S3 and against mocks for GCS and Azure; a live check waits for the LIVE sandboxes (Q-620, Q-678); GCS removal matches rules by SDK equality and can report a removal that did not happen (Q-682). New Flyway V7 in da-autodelete.

**Integrator note (wave 7)**: on wave/7 the retire round trip proved a bucket rule from a CloudTrail trail file delivered by AWS-01's transport: the disposition became EXECUTED and the record RULE_IN_FORCE with hard_deleted_at empty, and DIRECTOR listed the task as RULE_IN_FORCE (SESSION_LOG wave-7 entry, step g).

---

## ADR-0106 - Archive rows are claimed once; a rule that pre-existed is never removed; proof codes follow the AWS page; unproven rules are reconciled (da-autoarchival)

**Date**: 2026-11-01
**Status**: Accepted (RET-05, hardening wave 7; follows ADR-0100 (RET-03); closes Q-605, Q-607, Q-608, Q-609, Q-644)

> Numbered 0106 by assignment at the wave-7 integration; RET-05 supplied a draft (ADR-NEW-RET-05). Orchestrator decision D36 named these RET-03 follow-ups (Q-608, Q-609 race, Q-605, Q-607, Q-644) as RET-05's scope; the D36 call that dry-run-cloud-delete stays on is unchanged by this ADR.

**Context**: ArchiveExecutor.execute checked PLANNED and then acted with no lock, so the policy-event path, the approval-decided path and POST /rules/{id}/retry could apply one row twice (Q-609). A claim on the success path alone is not enough: the transient-failure and refusal branches, onApprovalDecided and rejectTask saved the whole stale detached entity (a merge; the id is assigned and there is no @Version), so a worker that lost the race could write PLANNED or REFUSED over the winner's EXECUTING or APPLIED row. A Kafka retry then applied the rule a second time, or a live rule was left REFUSED and orphaned on restore. GCS lifecycle rules carry no id, so an identical customer rule counted as DLAP's rule already in place and a restore deleted it (Q-608). The overview KPI counted COMPLETED tasks, a status the native-rule flow never sets, and the restore response did not say that objects already transitioned stay transitioned (Q-605). Nothing watched rules that never fire (Q-607). The proof expected S3.TRANSITION_DAA.OBJECT, which AWS does not document, and accepted the code-less S3.TRANSITION.OBJECT for any class (Q-644).

**Decision**:
1. Claim and conditional ends. Flyway V3 adds the status EXECUTING. ArchiveRuleRepository.claimPlanned is one conditional UPDATE (PLANNED to EXECUTING), committed on its own, taken after the approval and scope checks and before the REQUESTED audit record. Of N racing workers the database lets exactly one see a count of 1; the others return the row without an audit record or a provider call. Every other status write made by a worker that has not claimed the row (refusal, approval check failed, task rejected, approval decided as not executable) goes through ArchiveRuleRepository.endIfPlanned, one UPDATE ... WHERE status = 'PLANNED'. When it changes nothing the worker re-reads the row, writes no status and no refused record, and returns. After the claim the claimer owns the row and saves it normally. A worker that dies after the claim leaves the row EXECUTING; nothing re-runs it by itself (re-running could apply a rule twice and break the intent-first audit order). The reconciliation counts such rows (Q-669). EXECUTING counts toward the per-bucket rule cap.
2. Provenance. ApplyResult.alreadyPresent is stored as dlap_archive_rules.already_present. A restore of such a row leaves the provider rule in place, marks the row REMOVED (DLAP stops managing it), audits rule_removed with providerRuleKept=true and says so in the asset message. Rows applied before V3 read false (Q-668).
3. Archived KPI and restore wording. "Assets archived" counts rule rows EXECUTED (a provider log event proved the transition) plus legacy COMPLETED tasks; RULE_APPLIED and RULE_RENDERED tasks are pending. The restore response, the asset message, da-autoarchival/README.md and NATIVE_ARCHIVE_RULES.md say that restore removes the native rule and does not move already-transitioned objects back.
4. Reconciliation. ArchiveRuleReconciler is a ShedLock JDBC singleton (net.javacrumbs.shedlock 7.10.1, Apache-2.0, usingDbTime, lock table dlap_autoarchival.shedlock from V3, lockAtMostFor PT10M, lockAtLeastFor PT1M, plan rule R7). It reads on the injected Clock, changes no row and calls no provider. It counts real (not mocked) rules APPLIED longer than dlap.autoarchival.reconcile.max-applied (default P7D) with no proof, and rows EXECUTING longer than claim-timeout (default PT30M), logs the oldest 50 of each, and sets the gauges dlap.autoarchival.archive.rules.unproven and dlap.autoarchival.archive.rules.stuck. The gauge holds what the replica that ran last found; alert on the maximum across replicas.
5. AWS proof codes. Source: AWS documentation, "How S3 Lifecycle interacts with other bucket configurations", section "S3 Lifecycle and logging", https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html, retrieved 2026-11-01. The page lists these transition operations of the S3 server access log, mapped here to the row's class: S3.TRANSITION_SIA.OBJECT to STANDARD_IA, S3.TRANSITION_ZIA.OBJECT to ONEZONE_IA, S3.TRANSITION_INT.OBJECT to INTELLIGENT_TIERING, S3.TRANSITION_GIR.OBJECT to GLACIER_IR, S3.TRANSITION.OBJECT to GLACIER (Glacier Flexible Retrieval), S3.TRANSITION_GDA.OBJECT to DEEP_ARCHIVE. Each proves only its own class. Any other S3.TRANSITION name, including S3.TRANSITION_DAA.OBJECT, is no proof. The operation name is stripped before both the transition check and the class check.

**Consequences**: V3 is the next free autoarchival version after RET-03's V2. The OpenAPI contract gains RuleView.alreadyPresent; the status field is a free string in autoarchival.json, so EXECUTING needs no schema change. da-autoarchival now runs @EnableScheduling. The same AWS page says CloudTrail object-level logging does not capture lifecycle actions, so for AWS only the access log operations are credible proof (Q-671, Q-655). New write paths on dlap_archive_rules must either be the claimer's after claimPlanned, or use endIfPlanned; a plain save of a row loaded before the claim is not allowed. Restore (undoRule) and the proof update (ArchiveProofService.onEvent) still save a loaded row without a status check (Q-674).

---

## ADR-0105 - AWS log transports read the S3 log buckets; the access log text format is split in the transport

**Date**: 2026-11-01
**Status**: Accepted (AWS-01, hardening wave 7; answers Q-640 and Q-641; implements orchestrator decision D36)

> Numbered 0105 by assignment at the wave-7 integration; AWS-01 supplied a draft (ADR-NEW-AWS-01, status Proposed). Orchestrator decision D36 named AWS-01 the owner of the AWS transports (Q-640) and decided Q-641: the transport splits S3 access log text into the CloudWatch Logs field names and the parser contract does not change.

**Context**: The cloudtrail (s3-file) and s3accesslog (cwl-json) parsers of ADR-0102 had no transport, so a live AWS account could not prove an S3 native-rule delete from logs. The legacy CloudTrailLogSource uses LookupEvents, which never returns data events. S3 server access logs delivered to a bucket are space-delimited text, and the parser registry reads JSON only.

**Decision**:
1. Two pull transports under providers/aws/s3, registered only when dlap.logingest.transports.aws.enabled=true: S3_TRAIL_FILES (envelope s3-file) and S3_SERVER_ACCESS_LOGS (envelope cwl-json). Both use the OBJECT cursor: one batch per object, processed_object checkpoints by key and etag, the low watermark at the delivery time in the object name, and a late-object-lookback (default PT1H) on top of the family overlap so a late file is still found after a restart. No new Flyway migration.
2. Trail files: partitions are regions (location regions, else the single scope region, else the region folders under AWSLogs/[org/]<account>/CloudTrail/); each poll lists the day folders of its window; each gzip file's Records array is split with RecordFraming.
3. Access logs: the transport splits each positional line into the CloudWatch Logs field names (bucket_owner_id ... source_region) with schema_version_id V_1_0 and bucket_arn null, time as an ISO instant, "-" as null, numbers as numbers, trailing unknown fields ignored, quoted fields resolved by the shape of the next field. The parser contract and the mapping do not change.
4. An object that cannot be read as UTF-8 text (bad gzip, not UTF-8, over max-object-bytes) is delivered as one record (its first max-record-bytes + 1 bytes, NUL bytes dropped) that the registry quarantines; the object is then checkpointed and never retried. Read and list failures are thrown and retried on the next poll (Q-665).
5. Client settings come only from stream location keys (region, endpoint, path-style, profile) and the scope; credentials from the SDK default chain or a named profile. credentials-ref is refused until the STS module ships (Q-662). Only SdkS3LogStore imports the AWS SDK.

**Consequences**: S3 lifecycle deletes recorded in CloudTrail or in server access logs reach the registry from a live account. The s3lifecycle event names stay unverified until a live capture (Q-639). Assume-role and web-identity credentials need the STS module (Q-662). The text format's key is used as written (Q-663). New dependency software.amazon.awssdk:s3 2.25.1 (Apache-2.0), in da-logingest/build.gradle.kts outside the literal Owns (Q-660). The reader role (IAM-03) does not yet grant the object reads the transports make, and da-discovery ReaderRoleTraceabilityTest fails on SdkS3LogStore (Q-685).

**Integrator note (wave 7)**: on wave/7, with LocalStack S3 on the aws lifecycle stack, da-logingest on the registry pipeline with the switch on read a trail file and an access-log text object; their events reached dlap.discovery.asset.deleted through da-discovery, and the trail file's s3lifecycle DeleteObject proved a native S3 rule (SESSION_LOG wave-7 entry, step g; Q-664).

---

## ADR-0104 - Only a delete of the current object is a DELETE; native-rule needs the lifecycle signature

**Date**: 2026-11-01
**Status**: Accepted (PARSE-03, hardening wave 7; implements orchestrator decision D36; closes Q-650, Q-651, Q-654; refines ADR-0102 points 3 and 4 and ADR-0099 point 4)

> Numbered 0104 by assignment at the wave-7 integration; PARSE-03 supplied a draft (ADR-NEW-PARSE-03). Orchestrator decision D36 set the calls: Q-650, no s3lifecycle event may yield a bucket-level DELETE; Q-651, version-qualified deletes are UPDATE with the version carried; Q-654, executedBy native-rule needs the lifecycle signature. Point 3 below supersedes ADR-0102 point 3 ("delete markers are DELETE").

**Context**: ADR-0102 classified S3 expirations and delete markers as DELETE and kept object operations off bucket URIs only for rows with asset kind S3_OBJECT. Three gaps followed:
- An unlisted s3lifecycle event name fell to the verb heuristics and could become a DELETE of a bucket (Q-650).
- A version-qualified delete read as a delete of a live object (Q-651).
- Every AWSService principal read as executedBy native-rule (Q-654).

**Decision**:
1. Unlisted S3 lifecycle events are updates. The cloudtrail mapping has catch-all s3lifecycle rows that classify every unlisted event name as UPDATE in category MANAGEMENT. The frozen mapping schema has no service-wide row, so there is one operation-prefix row per capital letter, since eventNames are API action names. CloudTrailParser turns any s3lifecycle event that only the verb heuristics classified into UPDATE as well, keeping the heuristic warning.
2. An s3lifecycle record that names no object has no target at all: no id and no canonical URI. No consumer can normalize it to the bucket.
3. Only a delete of the current object without a version id is a DELETE. These become an UPDATE of s3://bucket/key in category MANAGEMENT:
   - a delete that names a version: CloudTrail requestParameters.versionId, or an access log version_id on S3.EXPIRE.OBJECT, REST.DELETE.OBJECT or BATCH.DELETE.OBJECT;
   - a delete that only adds a delete marker: S3.CREATE.DELETEMARKER, or a CloudTrail DeleteObject response with x-amz-delete-marker true.
   The target's technical_metadata carries versionAction.kind (DELETE_VERSION or CREATE_DELETE_MARKER) and versionAction.versionId when the record names one. MANAGEMENT keeps the event from moving lastWrittenAt or creating a disposition.
4. A noncurrent expiration is recognized only by its version id. The S3 Lifecycle and logging table (lifecycle-and-other-bucket-config, opened 2026-11-01) lists no separate operation for it; S3.EXPIRE.OBJECT is the only permanent deletion.
5. Lifecycle State executedBy native-rule needs a SYSTEM principal and the provider's lifecycle signature (da-catalog LifecycleSignature). The signature is the one da-autodelete DeletionProofMatcher uses, plus the S3 transition operations ArchiveProofService reads:
   - AWS: the s3lifecycle event source, the lifecycle.s3.amazonaws.com principal or caller, or S3.EXPIRE.OBJECT, S3.CREATE.DELETEMARKER or S3.TRANSITION*.OBJECT;
   - GCP: InternalTableExpired or bigquery.googleapis.com, or the GCS lifecycle user agent;
   - Azure: any SYSTEM principal.
   Any other SYSTEM actor gives engine when its principal is configured as one, else no value.
6. Parser versions: cloudtrail-record@1.1.0 and s3-access-log@1.1.0.

**Consequences**:
- In a versioned bucket, an S3 Expiration rule's delete markers no longer prove a native delete rule. Only S3.EXPIRE.OBJECT on the current object, or an s3lifecycle DeleteObject without a version, does. A versioned bucket's rule therefore stays AWAITING_PROOF until a current-object expiration is seen (Q-656).
- The catalog merges versionAction.* into an object row's technical metadata as "the last version action seen". The keys are not yet in the frozen docs/specs/log-event.md or lifecycle-state.md.
- Where CloudTrail carries the x-amz-delete-marker and x-amz-version-id headers is unverified until a live capture (Q-639). The S3 lifecycle page also states that lifecycle actions are not in CloudTrail object-level logging (Q-655).
- A GCS SYSTEM delete without the lifecycle user agent is no longer native-rule (Q-610 keeps the user agent unverified).
- DeletionProofMatcher still lists S3.CREATE.DELETEMARKER, now a dead entry (Q-657).

**Alternatives rejected**:
- A service-wide classification row: it needs a mapping schema change, and docs/specs/log-parser-contract.md is frozen.
- Quarantining unlisted lifecycle events: that would lose the record.
- DATA_WRITE for version deletes: removing a noncurrent version would then look like a fresh write of the current object.

**Integrator note (wave 7)**: on wave/7 the retire round trip fed, through AWS-01's transports, an unlisted s3lifecycle event naming only the bucket, an s3lifecycle DeleteObject of a noncurrent version and an S3.EXPIRE.OBJECT line with a version id; none proved the bucket rule (SESSION_LOG wave-7 entry, step g).

---

## ADR-0103 - Service-to-service TLS trust and executor wiring in the pilot

**Date**: 2026-11-01
**Status**: Accepted (WIRE-01, hardening wave 6; closes Q-602, Q-604 and Q-613)

> Numbered 0103 by assignment at the wave-6 integration; WIRE-01 supplied a draft (next free number).

**Context**: da-autoarchival and da-autodelete re-read approvals at the policy engine (docs/specs/approvals.md section 9 rule 3). Their code defaults are plain HTTP (localhost:8083 and http://da-policyengine:8080); the pilot TLS overlay serves the policy engine over HTTPS with a certificate of the pilot CA.

**Decision**:
1. Every service-to-service URL that differs between the dev compose and the TLS pilot is named in the service's SPRING_APPLICATION_JSON in both places, and smoke-tls.sh --static fails when an https value is missing or a plain-HTTP value remains.
2. Trust of the pilot CA comes from the JVM default through JDK_JAVA_OPTIONS (-Djavax.net.ssl.trustStore=/tls/jvm-truststore.p12) and nothing else; HTTP clients in the services (JDK HttpClient, Feign) must not install a private SSLContext. PolicyEngineApprovalClientTlsTest pins this for the archive client.
3. The overlay is proven live by smoke-tls.sh --executors (H7), which makes each executor read an unknown approval and requires the policy engine's 404 as the executor's outcome.

**Consequences**: No code change in the executors. An operator who replaces JDK_JAVA_OPTIONS must keep a trust store holding the pilot CA. --executors writes two refused probe rows and chained refusal records, so it is for rehearsals only (Q-647). The gcp lifecycle harness still cannot reach the archive executor's approval re-read, because its asset is bq:// (Q-646).

---

## ADR-0102 - Provider lifecycle actions are parsed as DELETE or ARCHIVE with a SYSTEM principal

**Date**: 2026-11-01
**Status**: Accepted (PARSE-02, hardening wave 6; answers Q-612 on the parser side and Q-596 for native-rule attribution on S3 lifecycle and BigQuery expiry)

> Numbered 0102 by assignment at the wave-6 integration; PARSE-02 supplied a draft (ADR-NEW-PARSE-02).

**Context**: The delete proof (da-autodelete DeletionProofMatcher, ADR-0101) and the Lifecycle State emitter (da-catalog, ADR-0099) depend on the parsers: a native rule is proven only by a successful change_type DELETE on the target's canonical URI with the platform's signature, and is attributed to the native rule only when the principal is SYSTEM. No registry parser read CloudTrail or S3 server access logs, and GCP System Event entries had no principal type (Q-612, Q-596).

**Decision**:
1. Two registry families: cloudtrail (AWS_CLOUDTRAIL, s3-file envelope) and s3accesslog (AWS_S3_SERVER_ACCESS_LOG, cwl-json envelope, schema V_1_0). Mapping YAML holds the classification; parser code holds only the target, the principal fallback and the access log key suffix.
2. A provider's own lifecycle action is the system's action: CloudTrail userIdentity.type AWSService (or invokedBy with no type), any S3 access log operation S3.*, and a GCP System Event entry that names no caller get principal.type SYSTEM. An entry that names a caller keeps it.
3. Expirations and delete markers are DELETE (category DATA_WRITE, asset kind S3_OBJECT); storage class transitions are ARCHIVE (routed to dlap.discovery.asset.archived); aborted multipart uploads, DeleteObjects calls and bucket configuration removals (DeleteBucket*) are UPDATE, never a delete.
4. An object operation never falls back to its bucket's URI. A record that names only the bucket gets no canonical URI and is quarantined as UNRESOLVABLE_ASSET_ID, so no log line can delete a bucket in the catalog by accident.
5. Until a family's catalog cell can be claimed at the 0.99 coverage gate, its parser runs the full contract on every case through UnclaimedCellsContractTest (a test-only subclass of the frozen base). The base itself and its skip rule are unchanged.

**Consequences**: S3 lifecycle and BigQuery expiry proofs work end to end in tests. The s3lifecycle eventName spellings are assumptions shared with RET-02 and RET-03 until a live capture confirms them (Q-639). The parsers are inert until AWS transports exist (Q-640).

Review notes recorded at integration: point 4 holds only for rows the mapping classifies with asset kind S3_OBJECT; an unlisted s3lifecycle eventName that starts with a delete verb falls to the verb heuristic and can still reach a bucket URI (Q-650). A version-qualified delete reads as a delete of the current object (Q-651). The AWSService to SYSTEM mapping applies to every AWS service, so for example a replication delete reads as executedBy native-rule in da-catalog; the delete proof is unaffected because it also requires the lifecycle signature (Q-654).

---

## ADR-0101 - The delete executor runs on native lifecycle primitives, re-verified approvals and log proof

**Date**: 2026-10-31
**Status**: Accepted (RET-02, hardening wave 5; implements D20 and D23; contracts docs/specs/approvals.md v1.0 section 9 and docs/specs/audit-log.md v1)

> Numbered 0101 by assignment at the wave-5 integration; RET-02 supplied a draft (ADR-XXXX).

**Context**: da-autodelete deleted cloud data when a soft-delete window ended, honoured a requester override of approval on the task API, approved tasks locally with one person, treated "the SDK call returned" as "deleted", and defaulted to real deletes. D20 asks that DLAP prefer each platform's own lifecycle primitive and prove from the platform's logs that it fired; D23 makes engine deletes safe by default.

**Decision**:
1. Two-step retire. An approved delete first enters a soft-delete window (nothing in the cloud is touched). The window is at least dlap.autodelete.window-floor-days (default 7); a shorter window, a zero window or hardDelete true is raised to the floor, never refused. When the window ends the executor asks the policy engine for its own HARD_DELETE approval with at least 2 distinct approvers (on behalf of the task submitter for task deletes).
2. Approvals are re-read, never trusted from Kafka. The executor reads GET /approvals/{id} as the service account and refuses unless it is executable, for the same asset and fire, of a class that covers the action, with enough distinct approvers, none of them the requester, the submitter, an excluded party or the task submitter; the Kafka approval block must agree with that record. An unreachable policy engine or catalog is retried, never read as allowed.
3. Read coverage is enforced where data is destroyed. FULL read coverage (COV-01) is required before the hard delete; at soft-delete admission it is only recorded, because admission touches nothing and is reversible. Legal hold is checked at both steps, an unknown hold counting as held.
4. Native rule first. S3 bucket: lifecycle Expiration.Date (next UTC midnight); GCS bucket: Delete rules createdBefore the next UTC date plus noncurrent versions; Azure Blob container: a lifecycle management rule on the container prefix (accounts listed in configuration); BigQuery table: expirationTime. Single objects have no exact native rule and use the engine delete; anything else is refused before approvers are asked. Appliers merge into the existing configuration and never remove rules DLAP did not write.
5. Applied is not executed. A row becomes HARD_DELETED only when the provider's own delete event (dlap.discovery.asset.deleted, from provider logs, after the apply time, with the platform's lifecycle signature for native rules) is seen; the database refuses an EXECUTED disposition without proof. hard_deleted_at is the provider's event time.
6. Every executed or refused action is a record in the retire.delete audit stream (ADR-0094), intent before effect.
7. One sweeper job under ShedLock with per-row FOR UPDATE SKIP LOCKED claims (Q-548).
8. dlap.autodelete.dry-run-cloud-delete defaults to true; under dry run every rule is checked and the exact rule document is recorded and shown in the would-delete list.
9. The task API never deletes: it asks for one approval per asset on behalf of the caller (JWT subject) and ignores triggeredBy and requireApproval from the body; local approve/reject answer 409.

**Consequences**: A pilot sees what would be deleted, and why, before anything is applied. Lifecycle primitives delete on the platform's schedule (up to a day or more), so proof is asynchronous and overdue proofs are flagged, not assumed. GCS and Azure lifecycle log signatures are unverified until a live capture (Q-610, Q-611). A versioned S3 bucket keeps its delete markers. Append and page blobs are not covered by the Azure rule. For a bucket or container rule, the first matching child-object delete event marks the whole disposition executed (Q-631). New Flyway V5 and V6 in da-autodelete; ShedLock 7.10.1 (Apache-2.0) joins the module.

---

## ADR-0100 - Archive on the provider's native lifecycle primitive, proved from the provider's log

**Date**: 2026-10-31
**Status**: Accepted (RET-03, hardening wave 5; implements D20)

> Numbered 0100 by assignment at the wave-5 integration; RET-03 supplied a draft (ADR-NEW-RET-03).

**Context**: DLAP's archive executor copied each object to a colder storage class itself, reported COMPLETED as soon as its own call returned, ran on a task-level approval one administrator could grant alone, and fell back to a configuration default tier (STANDARD_ARCHIVE) that is no storage class on any cloud, so every task that used the default failed at the provider. Retention executed by an owned engine also duplicates what S3, GCS and Azure already do (research/21 A2) and asks the customer to grant DLAP write access to every object.

**Decision**:
1. A bucket or prefix archive writes the cloud's own lifecycle rule: an S3 lifecycle Transition, a GCS lifecycle SetStorageClass rule, an Azure management policy rule. The provider moves the objects on its schedule. A single object, which a prefix rule cannot isolate, gets a direct storage class change under the same controls.
2. The rule is merged into the bucket's existing rules (never replacing them), carries a deterministic id (dlap-archive-<12 hex> from approval, scope, class, age) and is read back. Azure's policy is written through Azure Resource Manager, which this service does not use: the document is rendered and the task ends RULE_RENDERED.
3. Tiers are COOL, COLD (default), ARCHIVE or a provider class; anything that is not an archive class is refused, not guessed. Limits are enforced when planning and again right before applying: provider rule caps, DLAP's per-bucket cap, age floor and ceiling, prefix bytes, bounded dry-run listing.
4. approvals.md section 9 is enforced: no approval object, no run; the approval is re-read from the policy engine as ROLE_SERVICE_ACCOUNT before every action (ARCHIVE class, same asset, executable). The re-read also binds the approval to what it was given for, because the location, prefix, tier and age of a policy-path archive come from a Kafka event that anyone who can write to the topic can forge: the approval's own assetUri, provider and prefix must match the stored action, and the storage class must match the tier the approval names or, when it names none (the usual case for a policy without targetStorageClass), the configured default tier read when the action runs. The age must match the approved afterDays or, when silent, the minimum of that resolved class. A mismatch, a default that changed since planning, or an approval that names no location is refused ASSET_MISMATCH and audited DENIED before any provider call. One approval id is one decision (unique key); a REST request asks for an approval naming the caller and runs on the DECIDED event after another re-read; the single-administrator task approval no longer starts anything. Rejecting a waiting task refuses its rows and writes the refusal to the chain.
5. Every step is a chained retire.archive audit record written through AUD-01's AuditLogWriter as da_autoarchival_user. The REQUESTED record is written before the provider call; if it cannot be written the action does not run.
6. An archive is EXECUTED only from a successful provider log event (a transition operation in scope, not before the apply began, not a replay, on a row that was really applied, class matching). DLAP's own write, the provider's 200 answer and the task status never set it. The task counts an asset as archived only then.

**Consequences**: DLAP needs lifecycle-configuration permission on the bucket, not write access to objects. The proof depends on the provider logging the transition: S3 and Azure do (operation names to be confirmed on a live account, Q-600), GCS Cloud Audit Logs do not record Lifecycle Management actions, so a GCS rule stays RULE_APPLIED until an object-level observation source exists (Q-601). A rule keeps applying to objects written later, which the dry run states. Restore removes the rule; objects that already moved are retrieved with the provider's tooling (Q-605). A policy whose approval carries no assetUri or provider can no longer run an archive. Changing the default tier between a policy fire and its execution makes the pending silent-tier actions refuse (fail closed) and they must be fired again. New Flyway V2 for da-autoarchival, no dlap_common change, no new dependency.

**Alternatives rejected**: Keep per-object copy (O(objects) calls, no provider scheduling, wide write grants). Mark executed when the apply call returns (claims a transition nobody has seen). Apply Azure policies through ARM now (new dependency and role, no emulator to test against). Trust the event's location or tier because the approval id and asset id match (a forged event could then archive an unapproved bucket or pick a colder, costlier class). Store the planned tier on the approval at creation (needs a change in da-policyengine, which RET-03 does not own).

---

## ADR-0099 - Lifecycle State provenance store and withholding rule (da-catalog)

**Date**: 2026-10-31
**Status**: Accepted (LSE-01, hardening wave 5; implements ADR-0097)

> Numbered 0099 by assignment at the wave-5 integration; LSE-01 supplied a draft (ADR-NEW-LSE-01).

**Context**: Lifecycle State 0.1 (ADR-0097, docs/specs/lifecycle-state.md section 3.8) requires a provenance entry with Log Event ids for every known createdAt, lastReadAt, lastWrittenAt, sizeBytes and disposition, and for a non-null readCoverage. The Asset Descriptor keeps only evidence channels. dlap_catalog.processed_event keeps ids without the field each one set, and it is pruned.

**Decision**:
1. da-catalog keeps its own evidence in Flyway V6 (dlap_catalog.lifecycle_evidence, lifecycle_coverage_evidence). AssetEventApplier and ReadCoverageApplier publish a Spring application event inside their transactions, under the row lock. LifecycleEvidenceRecorder stores an event id only when the value the event offers equals the value the row holds after the existing rules ran, so the recorder never computes a value. Rows for a superseded value are removed. A redelivered or replayed event also publishes, so a replay backfills evidence.
2. Withholding rule: a stored value that no stored 64-hex Log Event id proves is emitted as null (unknown, R1) and counted in dlap.catalog.lifecycle_state.withheld. It is never emitted with an invented or reconstructed id.
3. Ids per field are capped at dlap.catalog.lifecycle-state.max-event-ids-per-field (default 100), keeping the lowest ids. This is deterministic for any engine given the same events. A provider retention setting (retention:expirationTime, retention:retentionPeriodDays) keeps only its latest crawl.
4. executedBy: an event whose principal id is in dlap.catalog.lifecycle-state.engine-principals is engine; principal type SYSTEM is native-rule; any other actor gives disposition null (spec section 3.7). A delete on a held asset cannot be represented in 0.1. It is emitted as null with a warning.
5. retention is a read-only evaluation by RetentionEvaluator at asOf (spec section 4 step 1), not the stored sweep result. asOf comes from the injected Clock and is read once per request or export.
6. A dedicated ObjectMapper (LifecycleStateJson) renders the wire form, so global JSON settings cannot change the bytes (R6). The OKF export is ADMIN only because it is a bulk copy of the catalog. It has an offline twin, the Gradle task exportLifecycleStateOkf.
7. Time precision: every time is compared and keyed in its stored form, the value the dlap_assets TIMESTAMPTZ column holds after a write. That form is whole microseconds, rounded up when the sub-microsecond part exceeds 499 ns, which is the PostgreSQL JDBC driver's rule. Log Event times carry up to nine fraction digits, so the raw event time would never match the value read back.

**Consequences**: Values applied before the upgrade report null until new events or a replay arrive (Q-597). Provenance can be checked only against the evidence table, not against pruned processed_event rows. Two small edits in kafka/ and coverage/ couple those appliers to an application event, not to the lifecycle package's beans; slices without the recorder are unaffected. An emitted time can differ from its cited event's event_time by less than 1 microsecond, because of the catalog's storage precision (Q-599). readCoverage provenance names the CRAWLER channel as a constant, since coverage observations come only from da-discovery collectors.

---

## ADR-0098 - The administrator who triggers an on-demand evaluation is an excluded approver

**Date**: 2026-10-31
**Status**: Accepted (FU-4B, hardening wave 5; follows ADR-0095; closes Q-569)

> Numbered 0098 by assignment at the wave-5 integration; FU-4B supplied a draft (ADR-NEW-FU-4B).

**Context**: POST /api/v1/policyengine/{policyId}/evaluations (ROLE_ADMIN) fires the policy's actions through ApprovalGate since wave 4B. The request it creates is made by policy:<policyDbId>, as on the event and schedule paths, so the administrator who pressed the button was not a party to it. An ADMIN holds an approver role: for archive and soft delete (one approver) they could approve their own evaluation, and for a hard delete (two approvers) they could be one of the two. That is the separation-of-duties gap dual control exists to close.

**Decision**: The evaluation endpoint passes the caller's JWT sub to the gate. ApprovalService.hold adds it to the request's excluded_subjects. requestedBy and requesterKind stay policy:<id> and POLICY, so the request shape, the unique pending-request rule and the audit and event payloads do not change. The sub is the raw string from the token, not a parsed UUID, because decisions compare sub strings. A call without a bearer token that carries a sub is refused with 403 before the evaluation runs. Break-glass already refuses excluded subjects, so the triggering admin cannot release their own request either. Event-trigger and schedule-trigger fires name no person and are unchanged.

**Consequences**: An on-demand archive or delete needs a person other than the caller (two others for a hard delete); the lifecycle harnesses approve with cdo and director and need no change; a single-admin installation must hold a second approver account (CDO, DIRECTOR or POLICY_MANAGER) to release an on-demand destructive action, or use break-glass with a second administrator. The IPolicyEvaluationService three-argument methods remain as default methods that use the user id as the subject. docs/specs/approvals.md sections 5, 6.1.1 and 13 gap 1 still describe the old behaviour (Q-590).

---

## ADR-0097 - Lifecycle State v0.1 is the open, vendor-neutral record of one asset's lifecycle

**Date**: 2026-10-31
**Status**: Accepted (draft 0.1; founder-approved open-source USP, GTM decision 2026-10-19)

**Context**: The open project needs one small record that other tools can read without adopting the engine: when an asset was created, last read and written, how complete its read history is, its size, its retention, any legal hold, and how it was disposed of, each with the log evidence behind it. The Asset Descriptor holds all of this but is the engine's internal model.

**Decision**: docs/specs/lifecycle-state.md defines Lifecycle State 0.1, with a JSON Schema (docs/specs/schemas/lifecycle-state-0.1.json), an OpenLineage dataset facet schema, examples, and mappings to Open Knowledge Format v0.1, AuthZEN resource attributes, Unity Catalog tags and Knowledge Catalog aspects. Null means unknown and is never read as "never"; PARTIAL, NONE or null coverage counts as unknown for destructive decisions; time fields come from provider events or observations; a legal hold blocks every new disposition, and a held asset may keep an archive executed before the hold but never a delete. LifecycleStateSchemaTest (da-protos) validates every example and 18 rule-breaking variants.

**Consequences**: The spec records seven places where it differs from the Asset Descriptor (its comparison table), among them no stored fixed retention date, legalHold set for any ON_HOLD, and no disposition field in the descriptor; the engine does not emit Lifecycle State yet, and an emitter plus the OKF export are follow-up packages. da-protos gains com.networknt:json-schema-validator 1.4.0 as a test-only dependency, the version da-logingest already uses.

---

## ADR-0096 - Pilot services expose the Prometheus scrape on a TLS management port

**Date**: 2026-10-31
**Status**: Accepted for the TLS pilot (FU-4A, hardening wave 4B; implements Q-518, and D31 for Q-514)

> Numbered 0096 by assignment at the wave-4B integration; FU-4A supplied a draft (proposed, number to be assigned).

**Context**: Under the pilot TLS overlay the actuator answers TLS on the application port, and the pilot set no management port at all, so Prometheus could not scrape the services. The prod profile of da-discovery additionally switches the Prometheus export off.

**Decision**: Under docker-compose.pilot.tls.yml every Spring service serves a management server on port 9091 with SSL on, using the service's own certificate and key; /actuator/prometheus is open without a token on that port only (the OBS-01 scrape filter chain) and nothing publishes the port. The pilot's Prometheus runs prometheus.tls.yml, which differs from prometheus.yml only by scheme https and ca_file /tls/ca.crt on the dlap-services job; test-prometheus-tls.py enforces that. Grafana, Alertmanager and the two exporters stay plain HTTP on the internal network. The pilot base overlay sets MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true for da-discovery. The realm dlap uses sslRequired=all under the TLS overlay (DLAP_REALM_SSL_REQUIRED, default external for the quickstart and the non-TLS pilot).

**Consequences**: The application port no longer serves actuator health, so compose health checks stay TCP probes; a realm that already exists keeps its sslRequired until changed in the admin console; a service added to the pilot needs a certificate entry in gen-certs.sh, the MANAGEMENT_SERVER_SSL_CERTIFICATE pair in the overlay and a target in both Prometheus files.

---

## ADR-0095 - Approvals and dual control live in da-policyengine

**Date**: 2026-10-31
**Status**: Accepted (APPR-01, hardening wave 4B)

> Numbered 0095 by assignment at the wave-4B integration; APPR-01 supplied a draft (ADR-NEW-APPR-01). Spec: docs/specs/approvals.md v1.0.

**Context**: Policy fires of archive and delete were published straight to dlap.policies.actions, and a requireApproval fire was published and then ignored by every executor, so an approval-gated action could never run (policy-dsl.md known gap 2). SOC 2 and customer pilots need separation of duties on destructive actions, and RET-02/RET-03 need a contract to build executors against.

**Decision**:
1. The approval service is part of da-policyengine (package com.dlap.policyengine.approval, Flyway V7), next to the code that fires actions; no new service.
2. Every non-dry-run archive, soft delete and hard delete fire of the event and schedule paths, and every requireApproval fire, is held as an approval request and not published. Shadow mode holds nothing.
3. A request needs N distinct approvers by JWT sub: defaults 1 archive, 1 soft delete, 2 hard delete, 1 other; floors 1/1/2/1 enforced at start-up and by a database CHECK; a policy (requiredApprovers) or API caller may raise N to at most 10, never lower it.
4. The requester, the submitter, the firing policy's authors and any ROLE_SERVICE_ACCOUNT token never decide. Approvers hold ADMIN, CDO, DIRECTOR or POLICY_MANAGER (narrowable); production sets require-mfa-marker=true.
5. Every decision records sub, username, roles and a mandatory comment; decision rows are immutable through database triggers. One REJECT ends a request; requests expire (default P7D).
6. Break-glass is ADMIN-only with a justification of at least 20 characters; it is logged at ERROR, counted (policyengine.approvals.break_glass) and published on dlap.approvals.break-glass.
7. An approved policy fire is released on dlap.policies.actions with requireApproval false and an approval block; all messages go through a transactional outbox relayed by a ShedLock job. Executors MUST verify the approval block (docs/specs/approvals.md section 9).
8. Topics dlap.approvals.requested, dlap.approvals.decided, dlap.approvals.break-glass are constants in DlapConstants.KafkaTopic.

**Consequences**: Destructive automation now needs people in the loop by default; the REST on-demand evaluation path and the executors are not yet covered (closed by a follow-up, Q-569, and RET-02/RET-03); the immutability triggers stop the service account but not a database owner, which the AUD-01 hash chain (ADR-0094) covers.

---

## ADR-0094 - Tamper-evident audit log

**Date**: 2026-10-31
**Status**: Accepted (AUD-01, hardening wave 4B)

> Numbered 0094 by assignment at the wave-4B integration; AUD-01 supplied a draft (ADR-NEW-AUD-01). Spec: docs/specs/audit-log.md v1.

**Context**: Destructive lifecycle actions, approvals, policy changes and access changes need a record that an administrator cannot quietly change (plan AUD-01; RET-02, RET-03, AUD-02a/b and DR-02 depend on it). Writers live in several services that own separate databases.

**Decision**:
1. The log lives in dlap_common (dlap_common.audit_log and dlap_common.audit_streams), owned by the bootstrap superuser. Each stream is one SHA-256 hash chain with a single writer role registered in audit_streams. The hash input is 'DLAP-AUDIT-1' LF stream LF seq LF prev_hash LF canonical-record, where the canonical record is printable-ASCII sorted-key JSON.
2. The database enforces the chain. A BEFORE INSERT trigger checks the head, the hash and the writer role. ENABLE ALWAYS triggers refuse UPDATE, DELETE and TRUNCATE for every role. Service roles get SELECT and INSERT only.
3. A writer appends in its own transaction on a dlap_common connection, not in the caller's. A destructive action writes REQUESTED before its effect and a final outcome after it, and fails closed when the intent cannot be written.
4. A superuser rewrite is detected by Ed25519 checkpoints. da-admin-service signs each stream head after verifying what was appended since the previous checkpoint. It stores the checkpoints in dlap_admin.audit_checkpoints (immutable signed part, but owned by da_admin_user, so it is a working copy) and exports them to write-once storage (S3 Object Lock, a locked GCS bucket or Azure version-level immutability), which is the anchor. The sinks speak each provider's REST API with the JDK HTTP client (SigV4 for S3), so no cloud SDK enters the service, and each refuses a target that is not write-once.
5. Three verifiers apply one rule set: the Java verifier, the API, and scripts/audit-verify.sh, which uses only pg_catalog and OpenSSL and trusts no DLAP code. A modified row (ENTRY_HASH_MISMATCH), a middle delete (SEQ_GAP) and a reorder (PREV_HASH_MISMATCH) are caught by the chain alone; a consistent rewrite (ANCHOR_MISMATCH) and a tail delete (ANCHOR_BEYOND_HEAD) need a checkpoint.
6. The checkpoint job is serialised across replicas by pg_try_advisory_xact_lock in dlap_common, plus the (stream_id, seq) primary key, instead of ShedLock. R7 asks for ShedLock, but adding it needs da-admin-service/build.gradle.kts, which AUD-01 does not own (Q-564).

**Consequences**: Audit rows can never be erased, so records must not hold personal data or secrets (actor.id is the JWT sub or client id). Entries after the newest checkpoint stay exposed to a superuser until the next checkpoint (unanchoredEntries shows the window). Anyone holding both the signing key and database superuser rights can forge history, so the key must stay outside the DBA's reach. The provider-side lock enforcement is unproven until a live bucket check runs (Q-563). The delta needs a true superuser, which managed PostgreSQL does not offer (Q-567). Changes to the contract need a new version prefix or format.

---

## ADR-0093 - Backfill jobs and replay by source re-read

**Date**: 2026-10-31
**Status**: Accepted (LOG-04, hardening wave 4B; implements D26 for Q-362)

> Numbered 0093 by assignment at the wave-4B integration; LOG-04 supplied a draft (ADR-NEW-LOG-04-1).

**Context**: Design B6 reserved backfill_job in logingest V3 and left the API to a later package. Q-362 asked how a quarantined record that the table cannot replay (truncated, or with an email or address masked) is replayed without storing raw provider data. The founder chose option (b) in D26: re-read raw_ref through the bounded backfill.

**Decision**:
1. A backfill job is a row of dlap_logingest.backfill_job (V3, no new migration). Its cursor is the progress JSON of the row: one position (low watermark, high watermark, transport state, done) per transport partition, plus counters. The position moves only after a batch reached its outcomes, the rule a stream cursor follows, so a batch in flight when the process dies is read again and the deterministic event ids absorb it. In BACKFILL mode a stored id is not published again (IngestMode.shouldPublish), so a resumed job neither loses nor duplicates an event.
2. A history read is cut into slices (slice-time 1 h for time cursors, slice-object 1 day for object cursors) and each slice is read by BoundedReader, which paces every batch (BatchPacer: the lower of max-batches-per-second and the stream's rate-limit). A slice is closed only when the transport's last batch moved the low watermark to the slice end; a transport that stops short fails the attempt instead of leaving a gap.
3. One job per binding is the partial unique index of V3. One runner per job is a PostgreSQL advisory lock on (backfill-job, job id), the arrangement StreamPollScheduler uses for streams, so the scheduler needs no ShedLock (plan rule R7 concerns singleton jobs). A job whose process died stays RUNNING; the lock dies with the session; the next tick on any replica resumes it.
4. Every transition is a guarded UPDATE that names the states it starts from. A cancel cannot be overwritten by a progress write: the runner sees zero updated rows and stops.
5. A job keeps its object checkpoints in processed_object under a pseudo stream id (backfill-<job id>), so history is not hidden by what the stream already read and a finished job leaves the stream's checkpoints alone.
6. Failures keep the job RUNNING with exponential backoff (30 s doubling, capped at 15 min) and only the exception class is stored; after max-attempts (5) consecutive failures, or at once when the binding, stream, transport or backfill.allowed is gone from the configuration, the job is FAILED.
7. Only ROLE_ADMIN may start, list, read or cancel a job. A stream must set backfill.allowed: true in the bindings file (default false); the window is at most max-window (90 days) and must end in the past.
8. Quarantine replay (D26): rows the table cannot replay go to SourceReplayHook (the quarantine package owns the SPI, backfill/ implements it). SourceReplayer searches the event-time window around the record (or a lookback before quarantined_at when the event time is unknown) through BoundedReader, matches by SHA-256 plus container (plus index for object transports), and ingests only the matched bytes in REPLAY mode under the stored raw_ref. REPLAY, not BACKFILL, because replay must republish the pass-through event id (Q-363). Bytes that differ at the same place are CHANGED and never replayed. No raw provider data is stored.

**Consequences**: No schema change and no application.properties change (defaults live in BackfillProperties, prefix dlap.logingest.backfill). Backfill needs the registry pipeline. A transport cannot fetch one record by reference, so a replay reads a window bounded by replay-time-pad, replay-max-window and the slice size; a per-record reader on the transport SPI would make it constant cost (Q-553).

---

## ADR-0092 - Lock bounds for fixedDelay singleton jobs

**Date**: 2026-10-31
**Status**: Accepted (HA-01, hardening wave 4B; refines plan rule R7)

> Numbered 0092 by assignment at the wave-4B integration; HA-01 supplied a draft (proposed number: next free after the wave-4B ADRs).

**Context**: Plan rule R7 requires ShedLock with usingDbTime() and lockAtMostFor of at least twice the expected run. It does not say how to set lockAtLeastFor. For a fixedDelay job, every replica runs its own timer. If lockAtLeastFor is short, each replica's timer wins the lock in turn and the cluster runs about once per replica per interval, which is not a single run.

**Decision**: A fixedDelay singleton job sets lockAtLeastFor to the job's interval, by default read from the same interval property (dlap.compliance.signal-sync.lock-at-least-for falls back to interval-ms), and keeps lockAtMostFor above twice the longest expected run and above the interval. The first run is delayed by an initial-delay property so tests can drive ticks. A cron job keeps a short lockAtLeastFor (about a minute) because its schedule already fixes the slots. A job that must drain a backlog quickly (for example the catalog retention-due publisher) keeps a very short lockAtLeastFor on purpose.

**Consequences**: The cluster runs the job about once per interval whatever the replica count. A crashed holder delays the job by at most lockAtMostFor (30 minutes for the signal sync, so one missed 15 minute cycle). The lock is measured from lock acquisition, so the start of the work can lag the lock by the time the run needs to start. Every new scheduled job is covered by a ratchet test (ScheduledJobsLockCoverageTest in da-autocompliance) and by a row in docs/guides/high-availability.md; a platform-wide ratchet is Q-552.

---

## ADR-0091 - The Expiry Report page presents the report and may only say less than the service

**Date**: 2026-10-20
**Status**: Proposed (RPT-02, hardening wave 4A; extends ADR-0082)

> Numbered 0091 by assignment at the wave-4A integration; RPT-02 supplied a draft (ADR-NEW-RPT-02-1).

**Context**: The page is the first screen that tells a customer an asset was or was not read. ADR-0082 decides what the service says. The page must not say more than that, whatever a later service, a proxy or a stale cache delivers, and a CSV that reaches an auditor must be the file the service described.

**Decision**:
1. The page adds no business value. It shows the service's values, a dash for a missing one, and never zero, stale or fresh for an unknown (ADR-0046).
2. Wording of the read signal lives in one table (utils/reportApi.ts, READ_SIGNAL_VIEWS). "Not read" is the label of NOT_READ only, and the page shows it only when the same row's readCoverage is FULL. NOT_READ with any other coverage, a missing state and a state the page does not know are shown as Unknown. The page can therefore say less than the service, never more.
3. A failed request keeps no rows: the table, its summary and the Download button are replaced by the failure and a retry. A 200 whose body is not a report page is a failure.
4. One report instant per query. Page 1 lets the service choose now; pages 2 onward and the CSV send that instant back, so the file matches the rows on screen. Apply filters and Refresh start a new instant. A person may pin the date.
5. The CSV is fetched as a blob, then checked against its manifest (size, then SHA-256 with Web Crypto) before it is saved. No manifest, or a mismatch, withholds the file; the person may save it anyway after reading why, and the page then says to treat it as incomplete. Where the browser cannot compute SHA-256 and the size matches, the file is saved and the checksum is shown for sha256sum. Where the X-Export-Id header is unreadable the file is saved with a warning.
6. Role gating of the navigation item and the page is presentation. The list is the nine human roles of the Access Model; the reporting service enforces it. The page makes no request for a user without one of them.
7. The known logging gaps are code (KNOWN_LOGGING_GAPS), each with the file that backs it, and a test pins their count and key phrases. Adding a coverage rule in the services should add a gap here.

**Consequences**: A service change to a state name or a column is a contract change (contractVersion 1.0) and breaks reportApi.test.ts and ExpiryReport.test.tsx first. With more than one reporting instance the in-memory manifest (ADR-0082 decision 5) can be absent on the instance that answers the manifest call, and the UI will then withhold a good file until the person saves it anyway; persisting manifests removes that. Holding the CSV in browser memory bounds the usable export at the service cap.

**Alternatives rejected**: a plain link or anchor download (it cannot send the bearer token and cannot check the manifest); saving first and verifying afterwards (a bad file would already be on the disk); showing the page's own judgement of idle time (that is business logic and belongs to the catalog).

---

## ADR-0090 - Pilot transport security through one compose overlay

**Date**: 2026-10-20
**Status**: Proposed (SEC-06, hardening wave 4A; answers Q-104 and the Q-459 companion)

> Numbered 0090 by assignment at the wave-4A integration; SEC-06 supplied a draft (ADR-NEW-SEC-06).

**Context**: The read-only pilot (ADR-0049 model M3) ran every hop in plain text, every Kafka client could read and write every topic, JDBC carried no sslmode, and every resource server accepted any token signed by the realm (threat model B1-T1, B1-I1, B2-I1, B3-S1, B4-S1, B5-*, B6-I1, B10-*). The work plan demands encryption of every hop, Kafka SASL_SSL with SCRAM and ACLs, verify-full JDBC, issuer and audience checks, configuration by environment only and a smoke script per hop. There is no Helm chart yet.

**Decision**:
1. One overlay, deployment/environments/pilot/docker-compose.pilot.tls.yml, applied by up.sh after the base, changes configuration only: environment, mounts and two one-shot containers. No application file, image or Java class changes.
2. Trust: a private CA per pilot VM, created by tls/gen-certs.sh on the VM and never committed; its key never enters a container. The entry point may carry the customer's own certificate. Every client verifies its server; the JVMs use a passwordless PKCS12 trust store of the JDK roots plus the pilot CA through JDK_JAVA_OPTIONS.
3. Key isolation: tls-init gives each private file to one consumer group (root:<gid> 0440) and each container joins only its group; the PostgreSQL key belongs to uid 999 (0600) because PostgreSQL drops supplementary groups.
4. Kafka: SASL_SSL with SCRAM-SHA-512 for services, one user per service; the broker's SSL and CONTROLLER listeners require client certificates; StandardAuthorizer with deny by default; the ACL matrix tls/kafka-acls.txt is derived from the code and test-tls.sh fails when code and file disagree. Consumers may create the topics they subscribe to; only da-policyengine and da-autocompliance may write dlap.policies.actions, only da-logingest and da-discovery dlap.log.event.normalized.
5. PostgreSQL: TLS 1.2 minimum, every TCP connection without TLS rejected; every JDBC URL uses sslmode=verify-full with the pilot CA.
6. Identity: Keycloak is served over TLS only and to browsers on the same origin through the gateway, so the services accept exactly two issuers (the public origin and https://keycloak:8443). Every one of the ten resource servers validates the issuer and the audience dlap-api. The audience is required everywhere because the dlap-mcp-gateway client (PEP-02) carries ROLE_SERVICE_ACCOUNT and would otherwise open every machine-to-machine endpoint; its tokens now open only da-mcp-pep. The realm must put dlap-api into dlap-ui and dlap-backend access tokens; until it does the pilot fails closed.
7. The SPA's build-time plain-HTTP origins are rewritten by the gateway to the request origin, so the pilot needs no UI build of its own.
8. No Java TLS startup guard (Q-104): TLS is a property of the deployment, enforced by the overlay and proven by smoke-tls.sh; a guard in the services would break deployments that terminate TLS in a sidecar or mesh.
9. Every harness that builds a stack from docker-compose.pilot.*.yml runs under the overlay with throwaway key material of its own (the DR-01 restore drill does, through gen-certs.sh --out <work>/tls --force), so the overlay is exercised wherever the pilot model is.

**Consequences**: the pilot needs gen-certs.sh before the first start (tls-init stops the start otherwise) and PILOT_PUBLIC_ORIGIN must match what browsers use. Tests over the overlay list must assert order, not the exact list. Keycloak's development mode keeps its HTTP listener open until the production Keycloak overlay (IAM-02) runs kc.sh start. The monitoring scrape path and Ollama stay plain HTTP. A Helm chart (DEP-02) must reproduce the same pieces. Renewal is gen-certs.sh --renew plus a restart.

---

## ADR-0089 - Production identity on Keycloak 26

**Date**: 2026-10-20
**Status**: Accepted (IAM-02, hardening wave 4A)

> Numbered 0089 by assignment at the wave-4A integration; IAM-02 supplied a draft (ADR-NEW-IAM-02).

**Context**: The quickstart, the harnesses and the pilot imported one development realm on Keycloak 23.0.1 with five seeded people sharing a published password, the password grant on dlap-backend, Google and Facebook brokers with placeholder secrets, and the scripts preview feature on. The pilot ran Keycloak in development mode on a file inside the container, so accounts and OTP devices died with it.

**Decision**:
1. One Keycloak release, 26.8.1 pinned by digest, in the quickstart, the production image and the demo overlay; bump them together (test_one_patched_release_everywhere).
2. Two realm files. keycloak-import/dlap-realm.json stays the development and harness realm (seeded people kept). production/dlap-realm.json is the template for the pilot and every production install: no people, no identity provider, no password or implicit grant, PKCE S256, secrets without defaults, events on. It is imported once into an empty database; later changes go through the admin console.
3. MFA is bound to a marker composite role dlap-mfa-required, included by ROLE_ADMIN, ROLE_CDO and ROLE_POLICY_MANAGER. Conditional OTP in the browser, direct-grant and post-broker flows applies to every account holding it, also after an identity provider's sign-in. Any future approver role gets MFA by including the marker.
4. Corporate sign-in uses Keycloak's generic OpenID Connect provider (Okta, Microsoft Entra ID documented); consumer social brokers are not shipped.
5. Production Keycloak runs an image built once with kc.sh build (postgres, health on, metrics off, scripts, impersonation, device flow, CIBA and Kerberos disabled) and starts with start --optimized. The front-end URL is fixed (KC_HOSTNAME) so browser and service tokens carry one issuer; back-channel URLs stay dynamic.
6. Every client carries the basic scope (sub), and basic is a realm default scope, because Keycloak 25 and later add sub only through it.

**Consequences**: the pilot no longer needs PILOT_ALLOW_DEV_REALM; Keycloak data is in the pilot backups; services that discover the issuer at start (da-discovery's login registration) need explicit endpoints and an explicit grant type when the issuer is fixed; a Helm chart must reuse the same image and template (production/README.md).

---

## ADR-0088 - Reader roles are generated from a per-cloud permission trace

**Date**: 2026-10-20
**Status**: Accepted (IAM-03, hardening wave 4A)

> Numbered 0088 by assignment at the wave-4A integration; IAM-03 supplied a draft (ADR-NEW-IAM-03).

**Context**: A read-only pilot asks the customer to grant DLAP cloud access. The permission list must be the minimum the code actually calls. It must hold no write permission and must stay true as crawlers and transports change. Hand-written policies drift silently: the old how-to listed permissions no code needed (logging.logs.list, s3:GetObject) and missed ones it does need (logging.privateLogEntries.list, bigquery.jobs.listAll).

**Decision**:
1. Each cloud has one trace file, deployment/iam/<cloud>/reader/permissions.yaml. Every entry gives the permission, its scope, its purpose and every call site as file, line and call text. Files that import a cloud client but call nothing are listed with a reason.
2. Terraform modules read the trace with yamldecode and hard-code no permission. The AWS CloudFormation template repeats the list in long-form YAML and must equal the trace.
3. ReaderRoleTraceabilityTest in da-discovery enforces the trace:
   - call sites exist and hold the call;
   - read-only verbs per cloud;
   - content reads (storage.objects.get, bigquery.tables.getData) are granted on the GCP audit-log sink only;
   - no AWS content action;
   - no Azure data action;
   - no Databricks data privilege;
   - every cloud-client file of da-discovery and da-logingest is scoped;
   - every permission a COV-01 coverage client names is held;
   - the guide names every permission.
4. The single write-verb exception is bigquery.jobs.create. Every BigQuery read is a job, and the role holds no table or dataset write permission, so the job cannot change data.
5. Databricks gets BROWSE, USE_CATALOG and USE_SCHEMA, never SELECT. The crawler must request include_browse rather than the role widening.
6. Proof that the roles grant nothing more uses read-only live checks: GCP testIamPermissions and the AWS policy simulator. Emulators do not enforce IAM.
7. Write paths (archive, delete, label write-back, grant projection) get separate Actor roles in IAM-04 and never extend the Reader.

**Consequences**:
- A new cloud call fails the build until its permission and call site are traced.
- Customers get one auditable file per cloud.
- cfn-lint cannot validate IAM action names, so action-name correctness rests on the live checks.
- The test reads files outside the da-discovery module. Gradle does not see them as inputs until the build declares them (open question).

---

## ADR-0087 - One-shot asset_uri re-key with a reversible map

**Date**: 2026-10-20
**Status**: Accepted (ID-03, hardening wave 4A). Related: ADR-0047 (canonical asset identifiers), ADR-0055 (CAT-02a matching on normalize(asset_uri)), ADR-0046 (asset model)

> Numbered 0087 by assignment at the wave-4A integration; ID-03 supplied a draft (ADR-NEW-ID-03).

**Context**:
- Rows written before CAT-02a hold pre-canonical URIs such as 'b', 'projects/_/buckets/b' or 'p:d.t'.
- The catalog finds them through the V3 normalized-URI index, so two rows can name one asset.
- ADR-0047 makes a canonical-form change depend on an ID-03 re-key.
- Critic item 10 asks for the re-key and for keys to switch only after a drain.

**Decision**:
1. The re-key is a command mode of the da-catalog image: DLAP_CATALOG_REKEY_COMMAND = dry-run, apply or revert.
   - It is not a service endpoint and not a new image. It uses the service's role and database.
   - It stops the catalog's Kafka listeners, prints a JSON report and exits: 0 done, 1 failed, 2 refused.
2. Normalization is the event matcher's own: CatalogEventDecoder.normalizeStored with the row's type, provider and region, plus the configured default hints.
3. In a collision group the oldest row by (created_ts, asset_id) survives with its asset_id. This is the row AssetMatcher already returns. native_asset_id gets the first native form seen when it is empty.
4. A merge moves label assignments, cloud resources, lineage edges, daily usage, processed events and retention rules to the survivor.
   - Duplicates are dropped and same-day usage is summed.
   - Of two POLICY rules of one policy, the later-issued one stays.
   - On the asset row: blank fields are filled, JSON metadata is merged with the survivor's keys winning, first-seen times take the least value and last-seen times the greatest.
   - Size and read coverage come from the newer observation, status from the later lifecycle event, and any legal hold holds the survivor.
5. service_type and cloud_provider follow the canonical ID, also for rows whose URI is already canonical but whose type contradicts it. The report counts these as kindChanges.
6. Apply is one transaction:
   - LOCK TABLE dlap_assets IN SHARE ROW EXCLUSIVE MODE with a lock_timeout, then a re-plan inside the transaction.
   - It refuses while an unpublished retention-due event names an old URI.
   - An empty plan records no run, so a second apply is a no-op.
7. Reversibility:
   - asset_uri_rekey_map records old and new identity and the survivor for every changed row.
   - asset_uri_rekey_undo records ordered jsonb before-images and after-keys.
   - Revert replays the log backwards for the latest applied run only.
   - It refuses on identity drift, on a reused old URI, on unknown asset columns, or when any inverse does not touch exactly one row.
8. The re-key leaves three things alone:
   - retention_audit (append-only);
   - other services' databases, which resolve old ids through the map;
   - asset_uri_canonical entries of rows re-keyed in place, which go stale and are recomputed by the catalog.
9. Producers switch to canonical Kafka keys only after the topics are drained (upgrade-guide runbook).

**Consequences**:
- The catalog holds one row per canonical URI.
- The normalized-match path of AssetMatcher then only serves rows that do not normalize and rows that later writers add.
- The re-key needs a maintenance window. Measured: a merge costs about 15 ms and an in-place re-key under 0.3 ms.
- Other services' stale references (Q-491) and the merged-hold audit entry (Q-493) remain to be handled.

---

## ADR-0086 - Drift join on event time, canonical asset keys and a principal identity map

**Date**: 2026-10-20
**Status**: Accepted (RPT-03, hardening wave 4A)

> Numbered 0086 by assignment at the wave-4A integration; RPT-03 supplied a draft (ADR-NEW-RPT-03).

**Context**: da-reporting joins PDP access decisions (dlap.access.decision) with observed reads (dlap.discovery.asset.accessed) to find UNENFORCED_READ and ENFORCEMENT_BYPASS drift (ADR-0040). Before this change:
- the consumers stamped an event that had no time with Instant.now();
- they acknowledged malformed events without counting them;
- they joined on raw ids, so a BigQuery read logged as projects/p/datasets/d/tables/t never met the PDP's bq://p/d/t;
- they compared the cloud principal (an IAM email, an ARN, an Entra object id) with the DLAP subject, so every governed read became a finding;
- the result depended on arrival order: a decision consumed after its read left a false finding behind.

**Decision**:
1. Business time is event time only. An event without a time, or with an unparsable time, is skipped. The injected Clock serves only to reject times more than dlap.drift.max-future-skew-seconds ahead (default 300), because a future-stamped ALLOW would pre-authorize reads. Every skip is counted on dlap.drift.events.skipped{stream,reason}.
2. Only recorded, successful, fully parsed DATA_READ log events are reads. DATA_READ_OBSERVED names no requester and is skipped.
3. Both sides key on the AssetIdNormalizer canonical URI, rolled up to the catalog asset (ADR-0046, ADR-0047). An id that does not normalize is kept as written and counted as form=native.
4. Read principals map to DLAP subjects through dlap_reporting.principal_identity_map (reporting Flyway V4). The key is normalized (IAM member prefix stripped, emails and GUIDs lower-cased); an AWS assumed-role session also tries its role ARN; a provider-specific entry wins over an ANY entry. An unmapped principal keeps its normalized key as the subject. The map is fed by SQL (MANUAL rows) or by a seed file that owns the keys it names (SEED rows), applied at start-up; a configured file that is missing or invalid fails the start.
5. Findings are a function of the buffered events, not of arrival order. A read reconciles against the buffered decisions; a decision reconciles every buffered read of its key in [t, t + window]. A late ALLOW deletes the UNENFORCED_READ (counted as retracted); a late DENY yields ENFORCEMENT_BYPASS. Each transaction takes a PostgreSQL advisory lock on hash(asset, subject) so concurrent listeners or replicas see each other.
6. The normative text is enforcement-contract.md v1.1, section 6.1.

**Consequences**: Drift findings mean what they claim for producers that already agree on canonical ids and subjects, and for cloud principals once they are mapped. Operators must maintain the map, and unmapped lookups are visible on dlap.drift.principal.resolutions{outcome=unmapped}. A retracted finding is deleted, not tombstoned: its buffered read and decision rows remain as evidence. The join now needs PostgreSQL for the advisory lock, which is already the only supported database.

---

## ADR-0085 - Gate the coverage of the change, not of the project; Subframe-synced code is exempt from two lint rules

**Date**: 2026-10-20
**Status**: Proposed (QA-02, hardening wave 3C)

> Numbered 0085 by assignment at the wave-3C integration; QA-02 supplied a draft (ADR-NEW-QA-02-1).

**Context**: The hardening plan wants a coverage gate. Measured project line coverage is 50.2% (29,497 of 58,764 lines, 5,081 tests, 14 modules, JaCoCo 0.8.14), from 26.8% (da-discovery) to 90.8% (da-logingest). A project-wide threshold of 70% would fail every build today, and a threshold below 50% would let new code pass on the strength of old code. The UI lint had 384 findings and ran as a non-blocking step. The UI sources under src/ui are synchronised by Subframe (.subframe/sync.json, directory ./src/ui) and regenerated on every sync.

**Decision**:
1. Every Java module runs JaCoCo; the post-test guard scripts/ci-guards/post-test/coverage.sh requires at least 70% of the Java lines a change adds or modifies under da-*/src/main/java to be executed by a test. Only lines JaCoCo lists as executable count; line coverage, not branch coverage; the ratio is over the whole change; a changed module with no JaCoCo report fails; the threshold is DLAP_COVERAGE_MIN. No project-wide threshold is set. The last 40 commits before the package would have scored 91.4%, so the rule is achievable.
2. UI lint is blocking: eslint src --max-warnings 0, all rules error, unused disable directives error. An exception is an inline disable with a written reason. The generated Subframe components (src/ui/components, DialogLayout, DrawerLayout) have only no-explicit-any and no-unused-vars off, because every finding in them is generator output that the next sync would bring back. The hand-maintained DefaultPageLayout.tsx is linted normally.

**Consequences**: New backend code needs tests that execute it. Legacy code with low coverage does not block unrelated work. Generated protobuf classes (in da-protos build output) never appear in a diff, so they do not affect the gate. A Subframe sync that introduces some other kind of finding in src/ui/components still fails the lint. Revisit the threshold (raise it, add branch coverage) when the project figure passes 70%. Lombok-generated code may count against a changed line until a root lombok.config marks it (Q-464).

---

## ADR-0084 - The PEP binds the AuthZEN subject to the verified token

**Date**: 2026-10-20
**Status**: Proposed (PEP-02, hardening wave 3C; implements D26 and Q-382 option b; extends ADR-0076)

> Numbered 0084 by assignment at the wave-3C integration; PEP-02 supplied a draft (ADR-NEW-PEP-02).

**Context**: After SEC-07 (ADR-0076) the PEP authenticates its callers, but principalId, roles and tier in McpAccessRequest were still asserted by the caller, so an authenticated caller could ask for the verdict of any principal with any tier (threat B10-S1 residual, Q-183, Q-382). No realm client could obtain a token with the audience dlap-mcp-pep (Q-381).

**Decision**:
1. SubjectBinder binds the request to the verified token before the decision cache and the PDP see it.
2. A caller with ROLE_SERVICE_ACCOUNT (an MCP gateway speaking for the agents and users behind it) may name principalId, roles and tier in the body. A body without principalId falls back to the token sub.
3. Any other caller is asked about as itself: principalId is the token sub, roles are the token's realm roles without the ROLE_ prefix (only ROLE_ authorities count; the FACTOR_* authorities Spring Security 7 adds are not roles), tier is the token's tier claim or absent. Roles and tier in the body are ignored. A body naming a different principal (exact, case-sensitive match on sub) is refused with 403 in the deny shape and never reaches the PDP.
4. The ADR-0076 gate (ROLE_SERVICE_ACCOUNT or ROLE_ADMIN on POST /mcp/v1/authorize) is unchanged. The subject id is sub, not preferred_username: it is stable and cannot be renamed.
5. The realm gets a confidential client-credentials client dlap-mcp-gateway whose service account carries ROLE_SERVICE_ACCOUNT and whose tokens carry the audience dlap-mcp-pep through an oidc-audience-mapper (the audience string the PEP tests and live check use; PRODUCTION.md section 4 shows dlap-api for the other services, which is a different audience).

**Consequences**: A gateway that names the subject keeps working unchanged if it authenticates as a service account. A gateway that forwards a user's own token can no longer ask about someone else. Decision-log events at the PDP carry the bound subject. The cache key is built from the bound subject, so a user token can never read another principal's cached verdict. The shipped realm now contains a second client with a published default secret in dev; production launchers must reject it (Q-458).

**Alternatives rejected**: (a) keep the body as is under a gateway-trust model (leaves impersonation by any ROLE_ADMIN token); (c) always from the token (breaks gateways that speak for many agents with one service-account token).

---

## ADR-0083 - Dev-default boot guard (DevSecretGuard)

**Date**: 2026-10-20
**Status**: Proposed (SEC-05, hardening wave 3C)

> Numbered 0083 by assignment at the wave-3C integration; SEC-05 supplied a draft (ADR-NEW-SEC-05-1).

**Context**: The repository ships working development secrets so that the quickstart boots with no setup: the dlap-backend client secret (change-me-dev-only), the sample AES key of .env.example, the Keycloak admin login, and per-service database passwords of the form <db>_pass. They are public. Before this decision nothing stopped a service from running in production with one of them, or with an empty secret; PRODUCTION.md only said not to (threat-model rows B2-S2, B2-S6, B3-S1, B6-I2, B6-S1, B11-E1, B11-I1).

**Decision**:
1. da-protos ships com.dlap.common.security.DevSecretGuard, an auto-configuration (AutoConfiguration.imports) that registers a static BeanFactoryPostProcessor. It runs before the data source, the Kafka factories and the JWT decoder exist. Every service that uses da-protos gets it.
2. It applies under the profiles named by dlap.security.dev-secret-guard.profiles (default prod; an empty value means prod). There is no switch to turn it off.
3. Refused (start fails): a secret-named setting equal to change-me-dev-only, Password123! or the sample encryption key; a password-named setting equal to admin, changeme or change-me; an empty dlap.service-auth.client-secret (only checked while dlap.service-auth.enabled=true), app.encryption.key, keycloak.admin.password or dlap.pdp.token.
4. Advisory (warning, refused when dlap.security.dev-secret-guard.strict=true): database passwords that are <db>_pass or dlap, and JWT issuer or audience validation off. They are advisory because the init scripts still create the <db>_pass logins without a variable to change them (Q-176).
5. Detection is by setting name over every enumerable property source, with placeholders resolved the way the service resolves them, so a shipped default such as ${DLAP_SERVICE_CLIENT_SECRET:change-me-dev-only} is seen and a secret inside SPRING_APPLICATION_JSON or the OS environment is found. The failure message names the setting and the property source and never the value.
6. One list: DevComposeSecretsContractTest fails when the dev compose pair gives a secret-named variable a default the guard would accept, and DevSecretGuardShippedDefaultsTest pins the findings for the 11 shipped application.properties. A new dev secret default must be added to the guard.
7. The dev compose pair follows: the client secret travels in DLAP_SERVICE_AUTH_CLIENT_SECRET and not inside SPRING_APPLICATION_JSON, PostgreSQL logs DDL only, da-autolabel uses the rules provider, the Keycloak and Grafana admin passwords come from the environment with the fallback changeme.

**Consequences**: A prod deployment that forgot a secret fails at start with a message that says which one. The quickstart is unchanged outside prod. Database passwords stay a warning until Q-176; then the default of strict can be flipped (Q-449). The guard sees only services that use da-protos, and only values that are exactly the published defaults: it does not judge secret strength. Keycloak, Grafana, PostgreSQL and the new-ui JVM are not covered. The dlap-mcp-gateway client secret added by PEP-02 lives in the realm, outside the guard's reach (Q-458).

---

## ADR-0082 - Asset Activity and Expiry report contract, read-signal state and export integrity

**Date**: 2026-10-20
**Status**: Accepted for 0.x (RPT-01, hardening wave 3C). Extends D9, ADR-0046 and the CAT-02b catalog activity API.

> Numbered 0082 by assignment at the wave-3C integration; RPT-01 supplied a draft (ADR-NEW-RPT-01-1).

**Context**: The Expiry report is the first product surface that tells a customer an asset was or was not read. A missing last read has three different causes: the asset was not read while read logging was complete, read logging was partial, or read logging was off or unknown. Showing all three as "not read" would be wrong and could drive a delete. The CSV is also handed to auditors, who need to know the file is whole and unaltered.

**Decision**:
1. The report adds no business value. Age, idle days, retention status and expiry are the catalog's, evaluated against one asOf instant that reporting resolves once and sends with every page. Reporting derives only account (from the canonical URI, only where the URI carries it), readSignalState and mode.
2. readSignalState is READ, NOT_READ, PARTIAL_COVERAGE, NO_COVERAGE or UNKNOWN. NOT_READ is reachable only when the catalog reports readCoverage FULL and no read is recorded. PARTIAL, NONE, null and any other text never produce it. readCoverage is shown as delivered. idleDays is empty when no read is recorded.
3. The report is never partial or empty because of a failure. The activity call has no circuit-breaker fallback; a catalog failure is 503. Filters are validated before the call so a bad request cannot open the breaker.
4. The CSV streams page by page and never exists in memory or on disk. The manifest (SHA-256, bytes, rows, columns, asOf, mode, filters, source) is computed in the same pass that sends the bytes. An export checks the announced row count, repeated ids and the row limit; the catalog pages by offset, so a change during the export aborts it instead of producing a shifted file. After the response is committed the stream cannot change its status, so a failed export ends with an unterminated quoted field (a CSV reader errors) and gets no manifest; a file without a manifest is incomplete.
5. Manifests are kept in the serving instance's memory for 24 hours (500 at most) and logged at INFO. Persisting them needs a reporting Flyway migration, which is reserved to RPT-03 (V4); revisit when HA or audit needs it (AUD-01).
6. Access follows the Access Model: the nine human roles read reports; the machine principal does not. Reporting calls the catalog with its own service account, so reporting roles are the only gate.

**Consequences**: RPT-02 renders readSignalState and links to the manifest. COV-01 (ADR-0080) now backs readCoverage with the dlap_assets column, and read_coverage_since bounds a FULL run, but the activity row does not carry that start yet, so the report cannot print "not read since X" (Q-444). The contract is versioned (contractVersion 1.0); changing a column name or a state value is a contract change.

---

## ADR-0081 - Three-valued policy evaluation, read coverage for destructive actions, and the scheduled policy sweep

**Date**: 2026-10-20
**Status**: Proposed (POL-01, hardening wave 3C)

> Numbered 0081 by assignment at the wave-3C integration; POL-01 supplied a draft (ADR-XXXX placeholder).

**Context**: PolicyDslEvaluator treated a missing instant as "no match" and measured ages against Instant.now(), so a deny-if-stale AccessPolicy allowed data of unknown age (plan section 2 fact 5). Schedule triggers were parsed and never fired. Actions on the log-spine path carried assetId null. Critique correction 2 requires PARTIAL coverage to count as unknown for destructive actions.

**Decision**:
1. Every match clause is TRUE, FALSE or UNKNOWN; the block is the Kleene conjunction (FALSE dominates, then UNKNOWN, in either operand order; pinned by MatchOutcomeTest). A value the snapshot does not carry is UNKNOWN, never stale, fresh, zero or empty. A malformed clause or an unknown clause name is FALSE (a policy defect, not missing data).
2. A lifecycle policy fires only on TRUE. An AccessPolicy with effect deny applies on TRUE or UNKNOWN; with effect allow only on TRUE. The PDP combining algorithm (ADR-0040) is unchanged.
3. For lifecycle policies whose action type contains archive or delete, and for all AccessPolicies, a recorded lastAccessedAt counts as proof of no later read only under readCoverage FULL; under PARTIAL, NONE or unknown it is a lower bound (olderThan passed is UNKNOWN, youngerThan failed is UNKNOWN). Other actions read it at face value. No recorded read is UNKNOWN even under FULL coverage, because the engine does not know the coverage start.
4. createdAt is a new clause on cloudCreatedAt (provider creation time); the catalog row time and discoveredAt never stand in for it.
5. Schedule triggers fire through one cluster-wide sweeper (ShedLock JDBC, database time, lock policy-schedule-sweeper, lockAtMostFor PT1H). Cursor per policy in dlap_policyengine.policy_schedule_state (Flyway V6, together with the shedlock table). A policy first seen starts at now (never retroactive); missed slots coalesce into one fire; one sweep pages the catalog activity report as of one instant for all due policies, statuses ACTIVE and ARCHIVED; a slot is recorded only after every page (at-least-once with respect to catalog reads). Crons are six-field Spring CronExpression in UTC by default; a schedule trigger without a parseable cron is a parse error.
6. Every producer path publishes one PolicyActionRequest v1 record with top-level assetId, assetUri, provider and serviceType (null when unknown, never guessed). fireId is a name-based UUID of the trigger instance. An event whose asset cannot be identified through the catalog publishes nothing.
7. dlap.policyengine.actions.enabled=false is an engine-wide shadow mode: nothing is published, would-be actions are logged and counted.

**Consequences**: Policies that relied on a missing label set satisfying noneOf, or on stale reads under incomplete coverage, stop firing destructive actions until COV-01 (ADR-0080) stamps FULL coverage. The sweeper reads coverage from the activity row (the column); the event and PDP paths read the single-asset GET, which does not carry readCoverage, so they see unknown coverage and fail closed (Q-479). Stored policies with a Quartz seven-field or invalid schedule cron become unparseable and inert. The event consumer makes up to two catalog calls per event when a policy listens on the topic. Consumers can deduplicate repeats by fireId.

**Alternatives rejected**: an in-memory schedule (lost on restart, violates the persisted-state guardrail); a per-slot ShedLock row as cursor (table growth, implicit semantics); defaulting unknown coverage to FULL (the failure the critique names).

---

## ADR-0080 - Per-asset read coverage on the log spine

**Date**: 2026-10-20
**Status**: Accepted (COV-01, hardening wave 3C; critique correction 2)

> Numbered 0080 by assignment at the wave-3C integration; COV-01 supplied a draft (ADR-NEW-COV-01).

**Context**: Logging blind spots are per resource and per principal (CloudTrail selectors that leave out buckets or prefixes, GCP audit-config exemptions and per-service enablement, unlogged public GCS objects, Azure diagnostic settings without StorageRead), but the CAT-03 read signal is per binding. A missing read history must never read as "not read".

**Decision**:
1. **Model.** Each asset carries readCoverage FULL, PARTIAL or NONE, or null for unknown, with readCoverageReasons. FULL only when the provider configuration proves every data read is recorded and an enabled binding of the recording channel captures reads. Every level but FULL counts as unknown for destructive actions. Unknown names the missing permission when a read was refused.
2. **Storage.** dlap_common.dlap_assets gets read_coverage, read_coverage_reasons (jsonb array), read_coverage_observed_at (orders observations) and read_coverage_since (start of the current unbroken FULL run, which bounds any "not read since" claim). Written only by the catalog coverage applier via SQL; the Asset entity maps them insertable=false, updatable=false so an entity save never overwrites them. Fresh databases from 02, existing ones from upgrades/2026-10-20_asset_read_coverage.sql. No backfill.
3. **Transport: the existing log spine**, not the ResourceEvent path. A coverage observation is a DlapLogEvent on dlap.log.event.normalized with source_format DLAP_READ_COVERAGE/config-observation, origin ORIGIN_CRAWLER, change_type unspecified (never routed, never state-changing for lifecycle consumers), event_time the collector's read time, target_resource.id the root URI and the level, reasons, service types, channel and scope in target_resource.technical_metadata. Written in the single encoding (ADR-0078). The spine needs no new topic or proto change; the catalog reads it in its own consumer group and skips non-observations with a text check before decoding.
4. **Granularity.** One observation per subject root (s3://bucket, gs://bucket, bq://project, az://account), applied to the root asset and every asset under root + "/" of the named service types.
5. **Combination with bindings.** The catalog combines the observation with the stored binding statuses of the scope: a capturing binding of the channel lets the configuration decide; for BigQuery, a GCP_BQ_JOBS_VIEW binding alone gives PARTIAL "bqjobs only: job reads only"; a non-capturing binding of the channel gives unknown; no binding gives NONE.
6. **Retention.** AssetReadCoverageSource becomes the primary ReadCoverageSource: reads are captured only for FULL assets whose binding signal captures them, from the later of the two starts up to the earlier of the binding low watermark and the last observation plus dlap.catalog.coverage.validity-grace (default PT0S). dlap.catalog.coverage.require-asset-coverage=false restores CAT-03's binding-only signal.
7. **Collectors** run in da-discovery as a ShedLock singleton, off by default, one switch per cloud. Provider code lives only in providers/<cloud>/coverage. Calls use the JDK HTTP client with JSON over HTTPS (AWS SigV4 from the SDK's existing signer module), so no SDK module or build change is needed.
8. **Fail closed** where the public documentation is silent: GCP exemptions (count only, no principals), public or fine-grained-ACL buckets, Azure audit category group, and BigQuery cross-project read logging (PARTIAL until dlap.discovery.coverage.gcp.bigquery-cross-project-reads-verified=true after a live check).

**Consequences**: Every LAST_ACCESSED retention rule stays UNKNOWN until the collectors run and bindings capture reads; that is the intended fail-closed default. Column values follow binding changes at the next collector run; the retention signal re-checks the binding at each sweep. The router counts observations as missing_change_type drops until it learns to skip the source kind (Q-423). Q-231 is answered: per-asset read coverage lives in dlap_assets.

---

## ADR-0079 - Services provision their own Kafka topics

**Date**: 2026-10-20
**Status**: Proposed (FU-3B, hardening wave 3C). Parent: D26 (Q-336, Q-369, Q-380) and ADR-0071.

> Numbered 0079 by assignment at the wave-3C integration; FU-3B supplied a draft (ADR-NEW-FU-3B-1).

**Context**: Every topic relied on broker auto-create. A production cluster switches it off, so dead-letter publishes (DlapKafkaFactoryBuilder publishes to <topic>.DLT) and the first send to any topic would be rejected, and the settings the design needs (compaction for dlap.logingest.binding.status, 30-day retention for dlap.log.event.quarantine, a retention for dlap.catalog.asset.retention-due) could not be applied. da-discovery also carried a kafka.* block that no client read, and its only reader, which made prod fail fast without SPRING_KAFKA_BOOTSTRAP_SERVERS, had been removed by KAFKA-01.

**Decision**:
1. The service that produces a topic declares it, and its .DLT topic, as NewTopic beans in config/KafkaTopicsConfiguration.java. Names come from DlapConstants.KafkaTopic. Spring's KafkaAdmin creates what is missing at start-up; a broker that is down is logged, not fatal.
2. The beans are on unless dlap.kafka.provision-topics=false (clusters whose topics are managed elsewhere). Settings are under dlap.kafka: topic-partitions and topic-replication-factor default to -1, the broker's num.partitions and default.replication.factor, so the dev compose (3), the demo (1) and a configured production cluster each keep their own sizing; dead-letter topics have 1 partition (the producer picks the partition) and keep 14 days; quarantine keeps 30 days; retention-due keeps 14 days, twice the 7-day outbox retention; binding-status is compacted.
3. KafkaAdmin does not change the configs of an existing topic. Existing clusters alter them once with kafka-configs (docs/guides/upgrade.md). spring.kafka.admin.modify-topic-configs is deliberately not enabled: it would let a start-up rewrite settings an operator tuned.
4. da-discovery reads Kafka client settings only from spring.kafka.*. The prod profile sets spring.kafka.bootstrap-servers with no default (fail fast, tested in a forked JVM). The keys the code reads with @Value (kafka.topics, kafka.group, kafka.topic.name, kafka.admin sizing) stay under the application's own kafka key, which is not Spring's namespace.

**Consequences**: Five near-identical KafkaTopicsConfiguration classes (a shared helper in da-protos is the follow-up, Q-418). A service that renames a producing topic through configuration must provision the renamed topic. Every start against an unreachable broker logs one KafkaAdmin ERROR. dlap.assets.changes and the constants nobody produces are not provisioned (Q-417, Q-420).

---

## ADR-0078 - One encoding on dlap.log.event.normalized, and delivery that is awaited

**Date**: 2026-10-20
**Status**: Proposed (SPINE-01, hardening wave 3C). Completes the ADR-0045 decision on the single encoding and the consumers-first switch order.

> Numbered 0078 by assignment at the wave-3C integration; SPINE-01 supplied a draft (ADR-NEW-SPINE-01-1).

**Context**: ADR-0045 fixed the wire form of DlapLogEvent as one proto3 JSON object and ordered the switch: consumers read both forms first, producers switch afterwards. CAT-02 (catalog) and DISC-02 (router) made the readers tolerant. The three active producers (da-logingest LogEventPublisher, CrawlerAssetSpineBridge, LogAnalyzerSpinePublisher) still serialized the event to a String and sent it through the JSON template, which quotes it (two serializations, a JSON string on the topic). None of them waited for the send: a broker outage left rows in log_events (or crawler events acknowledged), the stream cursor moved on, and the events never reached the spine, which the LogPublishFailures alert text already admitted.

**Decision**:
1. Every producer prints the event once with DlapLogEventCodec.encode and sends the text through a String-valued producer. A producer that builds a flat map or tree reads it through DlapLogEventCodec.decode first, so the wire is canonical whatever shape was built (enum names, origin and source_format instead of the source marker, unset strings as empty). The quoted form stays readable by decode (spec section 12) for records written before the switch.
2. Every producer waits for the broker's acknowledgement (30 s by default; dlap.logingest.publish-timeout, dlap.discovery.spine-publish-timeout) and fails the unit of work when it does not come: LogEventPublishException rolls back the row's transaction and stops the poll before its cursor commit; SpinePublishException reaches the Kafka listener's error handler. Event ids are deterministic and consumers idempotent, so a timeout that leaves the record in flight is harmless.
3. da-logingest owns its String-valued producer inside LogEventPublisher, built by DlapKafkaFactoryBuilder (ADR-0071), not as a bean: any KafkaTemplate bean disables Spring Boot's auto-configured template and the ProducerListener wiring. linger.ms defaults to 0 for it because every send is awaited.
4. A failed send is counted once: the ProducerListener counts failures it saw, and the exception then carries no Kafka error in its cause chain (StoreDispositionAspect walks it); timeouts and failures before the producer took the record carry a Kafka error and are counted by the aspect.
5. A producer contract test asserts DlapLogEventCodec.decodeStrict accepts the bytes the broker receives (spec section 8).

**Consequences**: Each event costs one broker round trip: measured 764 events/s per thread (1.31 ms per event) at linger.ms=0 on a local Docker broker, against 24,933 events/s when sends are batched and awaited together. A 10 million event backfill on one thread takes hours (Q-414). Awaiting per batch is unsafe while LogEventStore commits one row per event. ResourceEventListener still swallows SpinePublishException on the crawler path, so that leg is not retried until Q-411 lands. The dormant AWS, Azure and Oracle analyzers still publish LogEvent{payload} objects on the topic and stay outside the contract until ADR-0045 deletes them (Q-416).

---

## ADR-0077 - Wire type names and closed trust lists for Kafka JSON

**Date**: 2026-10-20
**Status**: Proposed (SEC-03, hardening wave 3C; threat model B5-E1, CWE-502)

> Numbered 0077 by assignment at the wave-3C integration; SEC-03 supplied a draft (ADR-NEW-SEC-03). The topic name in Consequences and the LogEvent defaultImpl line are corrections from the review.

**Context**: Seven services set spring.json.trusted.packages=* and two discovery event types (LogEvent.payload, ResourceEvent.payload) used @JsonTypeInfo(Id.CLASS), so a Kafka record could choose which class Jackson builds in the consumer. KAFKA-01 (ADR-0071) had already switched the service consumers to fixed value types with type headers off, which made the wildcard dead configuration but left it one setting away from live.

**Decision**:
1. A JSON Kafka consumer never trusts a wildcard. The default trusted list is the one wire-contract package com.dlap.common.logevent; the header-driven discovery factory trusts only com.dlap.discovery.event.service.type. A service that needs another package adds it by name in its own properties and a test.
2. Polymorphic event payloads are typed by a logical name in the property @type, resolved against a closed @JsonSubTypes list on the field (Id.NAME). A class name is never a type id. ResourceEvent.payload lists 18 names, one per concrete CloudResource subclass (AWS, AWS_S3, AWS_EC2, AWS_RDS, AWS_GLUE, AWS_PROCESSING_JOB, AZURE, AZURE_VM, AZURE_PROCESSING_JOB, GCP, GCP_GCS, GCP_BIGQUERY, GCP_CLOUDSQL, GCP_COMPUTE, GCP_BIGQUERY_JOB, OCI, UNITY, DATABRICKS); a name is part of the wire contract and is never reused or renamed. A test scans the classpath and fails when a subclass has no name. LogEvent.payload lists ResourceChange only and keeps it as defaultImpl, so the dormant analyzers' payload (with an ignored @class) still reads.
3. No legacy alias for @class on ResourceEvent: records written before this change dead-letter once on first read (deserialization failures are not retried). Crawls repeat, so the assets reappear. Aliases would have kept a class-name vocabulary alive on the wire (Q-401).
4. CloudResource keeps its class-level NAME type info on cloudProvider; it serves REST output, and renaming its type ids would change that API.
5. Admin-only request bodies carry Bean Validation limits and the handler takes @Valid. Optional fields stay optional. A secret field (MLConfigRequest.apiKey) is excluded from toString.

**Consequences**: A producer or a header can no longer select a class; an unknown or missing type name on ResourceEvent is a deserialization failure that goes to the dead-letter topic. Adding a CloudResource subclass requires adding its name to ResourceEvent (the build fails otherwise). The ResourceEvent wire shape changes (@type replaces @class), which matters to any out-of-tree consumer of the resource-events topic (KAFKA_TOPIC_RESOURCE_EVENTS, default discovery-resource-events) that read @class; none exists in the repository, and catalog and policyengine do not read that topic. LogEvent.payload differs on purpose: an unknown @type reads as ResourceChange (defaultImpl) instead of being refused, which is safe because no class can be chosen. da-llm-client gains a compileOnly Bean Validation API.

---

## ADR-0076 - The reference MCP PEP is a JWT resource server

**Date**: 2026-10-20
**Status**: Accepted (SEC-07, hardening wave 3B)

> Numbered 0076 by assignment at the wave-3B integration; SEC-07 supplied a draft (ADR-NEW-SEC-07).

**Context**: POST /mcp/v1/authorize was permitAll on every path and the request body carries principalId, roles and tier. Any network peer could obtain the PDP's verdict for any principal through the PEP's own ROLE_SERVICE_ACCOUNT token (threat B10-S1) and probe the surface. CLAUDE.md section 9 recorded this as an exception (Q-071).

**Decision**: da-mcp-pep authenticates its callers itself, at the HTTP edge.
1. Every request except /actuator/health (and its probe groups) needs a bearer token. Verification: signature against the Keycloak realm key set (jwk-set-uri), validity period, issuer in dlap.security.jwt.expected-issuer (exact match, comma list), audience in dlap.security.jwt.expected-audience. Failure is 401 with a Bearer challenge. Anonymous callers get 401 on any path, including unknown ones and the API description.
2. The issuer and audience checks are mandatory and have no development default or off switch, unlike the dlap.security.jwt.validate-* switches of the other ten services. The PEP refuses to start when either value or the key set address is blank: a default that accepts any realm token would turn every realm client into a caller who can ask for verdicts.
3. POST /mcp/v1/authorize carries @PreAuthorize hasAnyAuthority('ROLE_SERVICE_ACCOUNT','ROLE_ADMIN'), the rule the PDP applies to its callers (ADR-0034). Other valid tokens get 403.
4. All other actuator endpoints stay denied to every caller; Prometheus stays open on the management port only (OBS-01).
5. CLAUDE.md section 9 loses its da-mcp-pep exception; the handler-coverage allowlist is empty.

**Consequences**: Operators must set the issuer and audience (and, until IAM-02, add an audience mapper and a service-account client for each MCP gateway, Q-381). The subject of the evaluation is still asserted by the authenticated caller in the request body; binding it to the verified token is a separate decision (Q-183, Q-382). MCP transport (stdio, SSE) authentication remains out of scope.

**Alternatives rejected**: reuse of the optional validate-* flags (allows a PEP that is unauthenticated by omission); authenticated() without a role (any realm user could ask); IP allow-listing (not a token, not auditable).

---

## ADR-0075 - Catalog retention: rule store, outbox, read signal and hold

**Date**: 2026-10-20
**Status**: Proposed (CAT-03, hardening wave 3B; implements ADR-0046 decisions 7 to 11)

> Numbered 0075 by assignment at the wave-3B integration; CAT-03 supplied a draft with a placeholder number.

**Context**: ADR-0046 fixed what the catalog stores and how retention is decided, but not how the evaluator runs, where POLICY and MANUAL rules live, how "reads are captured" reaches the catalog, or how a status change reaches the retire services without being lost. Spec: docs/specs/asset-descriptor.md "Retention in the catalog (CAT-03)".

**Decision**:
1. **Rule store.** POLICY and MANUAL rules are rows of dlap_catalog.retention_rule (Flyway V4); a POLICY rule is unique per (asset, policy) and a later firedAt replaces it. LABEL_DEFAULT rules are configuration. PROVIDER rules are read from technical_metadata (retentionPeriodDays gives CREATED plus days, expirationTime gives FIXED_DATE). The evaluator reads all four on every run and never reads the retention_* result columns back.
2. **Sweep.** One cluster-wide pass (ShedLock JDBC on database time, lockAtMostFor PT2H) over assets that are not DELETED, in keyset pages of asset_id, at the injected Clock's instant. An unchanged result with a fresh retention_evaluated_at is neither locked nor written. A changed asset is re-read under the SELECT FOR UPDATE row lock the event consumers take and evaluated in its own short transaction, so the sweep never holds two locks and never decides on evidence an event consumer has just changed. Only the retention_* columns are written, by JDBC.
3. **Transactional outbox for retention-due.** A status change writes the event to dlap_catalog.retention_outbox in the same transaction (unique eventId); a second ShedLock singleton publishes in order and marks a row published after the broker acknowledged it. Delivery is at least once and never for a change that rolled back.
4. **Read signal.** dlap.logingest.binding.status is stored as the latest status per binding. An asset's reads are captured only when every enabled binding of its provider and scope reports READ_COVERAGE_CAPTURED with a start and a low watermark; coverage starts at the latest start and is complete through the earliest low watermark V. A LAST_ACCESSED rule is DUE only when its upper-bound expiry is at or before min(asOf, V); between V and asOf it is UNKNOWN (READ_COVERAGE_STALE). An asset that cannot be placed in a scope has no coverage. This refines the ADR-0046 bounds table and never makes a rule due that the table calls unknown. COV-01's per-asset coverage replaces the binding-level rule through the ReadCoverageSource interface.
5. **LAST_MODIFIED has no upper bound**, because lastModifiedAt comes only from audit-log writes and no channel is stored; such a rule can be RETAINED but not DUE (Q-372).
6. **Fail closed on unreadable provider settings.** A provider setting that is present and unparseable makes the asset UNKNOWN (never DUE, never NO_POLICY); RETAINED stays.
7. **Legal hold.** The legalHold flag changes only through Asset.placeLegalHold and releaseLegalHold, by a named role (ADMIN, CDO, DIRECTOR, POLICY_MANAGER), with a reason and an audit row; the asset is evaluated again in the same transaction. A configured hold label (dlap.catalog.retention.legal-hold-label, blank by default) holds the asset (ON_HOLD, reason HOLD_LABEL) while assigned, without touching the flag.
8. **setRetention listener.** Reads dlap.policies.actions under its own group, acts only on setRetention, skips dryRun and requireApproval actions, defaults the basis to LAST_ACCESSED, and sends an action it cannot understand to the dead-letter topic.

**Consequences**: Until a binding reports captured reads with a start and a watermark, every LAST_ACCESSED rule is UNKNOWN (intended). The retire services act on retention-due events with status DUE and legalHold false, and re-read the asset first. Disabling the publisher stops the outbox from draining and from being pruned. A stale POLICY rule outlives its policy until removed (Q-374). Full-entity updates of Asset elsewhere can overwrite retention_* with stale values; the next sweep repairs them and may announce a repeat transition (Q-379).

**Alternatives rejected**: publishing from the sweep after commit (loses events on a crash); per-page transactions (many row locks at once, deadlock risk against the consumers' lock order); writing the result through the Asset entity (rewrites every JSON column); mapping a hold label by setting legalHold (an unaudited, automatic release); binding-level coverage from any one captured binding (a jobs binding would vouch for buckets it cannot see).

---

## ADR-0074 - Quarantine storage, replay rules and parser-health seams

**Date**: 2026-10-20
**Status**: Proposed (LOG-03, hardening wave 3B)

> Numbered 0074 by assignment at the wave-3B integration; LOG-03 supplied a draft (ADR-NEW-LOG-03-1).

**Context**: Design B3 and test strategy L8 require that a record the parsers cannot take is kept, visible and replayable, and that a format change is detected before reports go wrong. The parse contract (ADR-0054) and RegistryIngestService are frozen; Flyway V3 reserved the quarantine_record table with a masked excerpt and no raw column.

**Decision**:
1. The quarantine table is the system of record; the topic dlap.log.event.quarantine is the notification. The table sink runs first and any sink exception fails the ingest of the record, so a cursor never passes a record whose quarantine row could not be written. Both sinks re-apply masking (emails and IP addresses) and the bounds (excerpt at most 2048 bytes, ceiling 8192; reason_detail at most 1024 bytes).
2. Topic values are sent as JSON objects through the shared JsonSerializer, not as pre-encoded text, so the new topics do not repeat the double encoding of dlap.log.event.normalized (checked against a real embedded broker).
3. Retention of quarantine rows is 30 days by quarantined_at, a ShedLock singleton with database time, deleted in batches.
4. Replay re-runs a row only when its stored excerpt is exactly the original record (not truncated; SHA-256 and size equal). A masked or truncated row is counted and left alone, because replaying a masked copy would store the mask as a real identity. Replay is ADMIN only, runs in REPLAY mode, is idempotent through replayed_at and unchanged event ids, takes a cluster-wide advisory lock and supports a dry run.
5. Metrics need no change to frozen code: a @Primary ParserRegistry decorator records every outcome with its detected version and shape fingerprint; an aspect on LogEventStore.write records published and duplicate events and Kafka failures; a ProducerListener bean counts failed sends.
6. Label cardinality is bounded: version takes at most 32 values per (source, format), then reads other; the binding label exists only while the service has at most 50 bindings, then reads _all; the fill-ratio field label is a closed set of ten.
7. Read coverage is derived conservatively: CAPTURED when a data-read event arrived within 24 hours, with read_coverage_since the earliest known read event time; NOT_CAPTURED when every transport of the binding is in dlap.logingest.health.no-read-transports (default LOOKUP_EVENTS); NONE_OBSERVED otherwise once events are seen. The read signal gauge is 0 only when data writes arrive and no read does.
8. The startup self-test is a bundled golden set (dlap/log-formats/golden/*.json, one case per file, extendable by every family) run through the real registry and mapper; its result is the logParsers health component. It stops the service only when dlap.logingest.health.selftest-fail-fast is set.
9. The spine alert reads the catalog counters globally, because dlap_spine_unmatched_target_ratio is emitted by nothing (Q-365).

**Consequences**: Replay is exact but, with a masked 2 KB excerpt, effective only for small records without addresses; real cloud records need a raw-bytes decision or a bounded backfill from raw_ref (Q-362). Drift unknown paths and fill baselines live in memory per instance (Q-364). The decorator and aspect can go if the ingest service later offers a native outcome hook (Q-368).

---

## ADR-0073 - BigQuery INFORMATION_SCHEMA.JOBS is job-read history, parsed as GCP_BQ_JOBS_VIEW

**Date**: 2026-10-20
**Status**: Proposed (GCP-02, hardening wave 3B). Builds on ADR-0045, ADR-0047, ADR-0054 and docs/specs/log-event.md, none of which it changes.

> Numbered 0073 by assignment at the wave-3B integration; GCP-02 supplied a draft (ADR-NEW-GCP-02-1).

**Context**: BigQuery job history is the only read signal that needs no audit-log configuration, and the zero-access scan (SCAN-01) already counts reads from it. The pilot needs the same numbers from the engine. The view has no version field and no operation name, lists one row per job rather than per table, carries every user's query text, and does not see Storage Read API or tabledata.list reads.

**Decision**:
1. Source kind GCP_BQ_JOBS_VIEW, envelope jobs-view-row, one named version jobs-2026-10. Transport id BQ_JOBS_VIEW with stream location keys location (required) and billing-project (optional), project from scope.project-id. Application Default Credentials only.
2. A record is a row as a JSON object keyed by column name plus one transport-added key, location, because a job id is only unique with it.
3. Read semantics equal the zero-access scan: a QUERY job that is DONE, not failed, not a cache hit, not a parent, whose statement type is not on the scan's no-read list reads every referenced table and logical view except the table it writes. Failed jobs give FAILURE events. Principals are typed as the scan types them (Google service agents are SYSTEM). BqJobsScanOracleTest runs the scan's SQL against the parser's output and must stay green.
4. Writes: destination_table unless it is a hidden anonymous result table. DML, CTAS, LOAD DATA, load and copy jobs are DATA_WRITE; table DDL is MANAGEMENT. A copy job's source and an extract job's table are not in the view, so they are not recorded.
5. Identity: the frozen fan-out rule. The first event (the write, else the first read in canonical order) keeps GCP_BQ_JOBS_VIEW|project|location|job_id; every other event appends '#' and the canonical table id.
6. Query text, job stages and timeline are never selected. The transport selects an allow-list of columns from the live schema, so they are neither read, billed, delivered, quarantined nor stored. raw_event keeps the same allow-list.
7. Run-level problems are records, not exceptions: a missing required column or a refused location produces one marker record {"dlap_run_problem": ...} that the registry quarantines as UNKNOWN_ENVELOPE, byte-identical on every poll of the same problem, and the cursor does not move. A missing permission or any other refusal fails the poll loudly.
8. Window: event time is end_time; the poll reads [low - overlap, now - 5 minutes) with a 30-minute overlap; nothing older than the 180-day retention is requested; a stream without backfill.allowed starts 24 hours back; the creation_time partition floor is 6 hours before the window; the watermark reaches the window end only on the last batch.

**Consequences**: Read history from this source is labelled job reads only (Q-356). Consumers decide whether SYSTEM-typed reads move last_accessed_at (Q-355). Assumptions only a real project can confirm are in Q-353. Per-binding impersonation is not supported (Q-359).

---

## ADR-0072 - GCP Cloud Audit Logs parsing and transports

**Date**: 2026-10-20
**Status**: Proposed (GCP-01, hardening wave 3B)

> Numbered 0072 by assignment at the wave-3B integration; GCP-01 supplied a draft (ADR-NEW-GCP-01).

**Context**: GCP-01 adds the gcpaudit family under the frozen parser contract (ADR-0054): three envelopes (api-proto, json, bq-sink-row), four fixture versions (auditlog-2021, auditlog-2025, bq-auditmetadata, bq-auditdata-legacy) and three transports. Several choices are not fixed by the contract text.

**Decision**:
1. Version ids: detection yields auditlog, bq-auditmetadata or bq-auditdata-legacy. auditlog-2021 and auditlog-2025 are fixture profiles, registered as aliases in the VersionRange so a pin to them is read and validated (Q-127); no record is detected as a profile. Bindings pin auditlog.
2. An entry with neither timestamp nor receiveTimestamp is QUARANTINED:MISSING_REQUIRED_FIELD, not skipped (Q-341).
3. sharedEventId of a BigQuery read is the job name (projects/P/jobs/J, location dropped) or the Storage Read sessionName, so a tableDataRead and the referencedTables of the same job give one usage per job and table. Native keys stay distinct (fan-out key#canonicalUri).
4. Tables of anonymous datasets (names starting with _) are never data assets. A wildcard table read (events_*) is recorded on the dataset asset with technicalMetadata bigquery.wildcard_table.
5. BigQuery v2 jobs: a finished job reads referencedTables and referencedViews; a cache hit, a failed job and a SCRIPT parent read nothing; a script child reads and links to its parent as PARENT; a CancelJob with an errorResult is not FAILURE.
6. BigQuery v1 jobcompleted: reads are referencedTables, referencedViews, tableDataReadEvents, tableCopy.sourceTables and extract.sourceTable; writes are the destination of a load or copy, and of a DML or CTAS query. A failed job, or one with no reads or writes, is one MANAGEMENT job event.
7. FAILURE when any of: status.code != 0; an authorizationInfo check with granted false or left out (proto3 JSON omits a false bool); policyViolationInfo; VpcServiceControlAuditMetadata; any entry of the Policy Denied log; a BigQuery job that ended with an error.
8. Sink rows are parsed through a LogEntry view (BqSinkRows): column names matched case-insensitively; protopayload_auditlog becomes protoPayload; metadataJson, requestJson and responseJson are parsed into objects; servicedata_v1_* becomes serviceData with its @type; a code detector (BqSinkRowDetector) detects the version. A sink stores microseconds, so each project should be read through one transport (Q-345).
9. api-proto legacy BigQuery serviceData is kept as {typeUrl, base64} by the transport and decoded with DynamicMessage against a shipped FileDescriptorSet generated from vendored googleapis at ba591f1d and kept in sync by AuditDataDecoderTest.
10. The fixtures compile the vendored protos with a plain protoc task, not the protobuf Gradle plugin, whose generateProto cannot be stored in the configuration cache.
11. Transports: LOGGING_API limits requests per project with a sliding window (at most 60 in any 60 s), not a token bucket (which allows up to 119 in one minute). BQ_SINK reads results through the REST API, so the JVM needs no Arrow add-opens (Q-293). Identifiers are validated before they enter SQL; window bounds are query parameters.
12. The three bq-auditmetadata cells ship as supported:false until the coverage spec stops requiring VPC SC variants of v2 entries (Q-340). The contract test still runs their cases.

**Consequences**: Downstream usage dedups on (sharedEventId, target). Pins are per stream, not per service (Q-349). Live behaviour (paging, shard naming, sink casing, gRPC method names) waits on LIVE-00 (Q-347). Read events of BigQuery jobs take the job's result only, not the entry's (Q-393).

---

## ADR-0071 - One Kafka factory builder for every service

**Date**: 2026-10-20
**Status**: Proposed (KAFKA-01, hardening wave 3B)

> Numbered 0071 by assignment at the wave-3B integration; KAFKA-01 supplied a draft (ADR-NEW-KAFKA-01).

**Context**: Eleven hand-built Kafka configurations ignored spring.kafka.* apart from bootstrap-servers, so security, SASL and SSL never reached them (critique item 5). Idempotence was explicit in only 2 of 10 services, and dead letters assumed the DLT had as many partitions as its source.

**Decision**: Services build Kafka clients only through com.dlap.common.kafka.DlapKafkaFactoryBuilder, constructed from KafkaProperties and the BeanFactory. The builder applies the connection details and the producer and consumer factory customizers. Precedence, lowest first: builder defaults (auto.offset.reset=earliest), service defaults, spring.kafka.*, connection details, serdes named in code (they fix the wire format), invariants. Invariants: enable.idempotence=true and acks=all; enable.auto.commit=false. A contradicting setting fails factory construction. Dead-letter records go to <topic>.DLT with no partition, so the producer's partitioner places them by key. Listener container factories apply spring.kafka.listener.* except type (always record listeners); manual-ack factories force MANUAL_IMMEDIATE.

**Consequences**: Env-only transport security (SEC-06) and SEC-03's trusted-package work act through properties alone. DLT ordering per key is kept; DLT partition numbers no longer mirror the source. New Kafka code must not call new DefaultKafka*Factory (CLAUDE.md wording proposed in Q-338). da-discovery's prod profile no longer fails fast without SPRING_KAFKA_BOOTSTRAP_SERVERS (Q-334).

---

## ADR-0070 - Scanner gate to green: security patch levels in the root build, Debian 13 distroless base, reasoned CPE suppressions

**Date**: 2026-10-20
**Status**: Proposed (VULN-01, hardening wave 3B)

> Numbered 0070 by assignment at the wave-3B integration; VULN-01 supplied a draft (ADR-NEW-VULN-01).

**Context**: ADR-0069 made OSV-Scanner, Dependency-Check and Trivy block at CVSS 7 or HIGH. After BOOT-01 and DEPS-01 the gate was red on pre-existing dependency CVEs (Q-304): OSV 32 groups, Dependency-Check 367 findings, Trivy da-catalog 22. Spring Boot 4.1.1 is the newest 4.1 release, so its BOM cannot be bumped.

**Decision**:
1. Security patch levels live in one SecurityPatches block of the root build.gradle.kts, each row naming the advisories it closes: Spring Boot BOM properties set as project properties (tomcat.version, jackson-2-bom.version, jackson-bom.version, grpc-java.version); Gradle constraints for libraries the Boot BOM does not manage; and, for a module that pins a vulnerable version itself, a dependency resolve rule registered in afterEvaluate (the dependency-management plugin turns a module's own pin into its managed version, so a constraint or resolutionStrategy.force cannot raise it). The DEPS-01 convergence anchors read the raised properties, so the alignment stays one source. A row is removed when the BOM or the module reaches the version by itself.
2. da-discovery gets the Google Cloud libraries BOM 26.90.0 followed by the Spring Boot BOM from the root build, as da-logingest does, so every boot jar converges with no exception rows.
3. The service images use gcr.io/distroless/java21-debian13:nonroot and state USER 65532:65532 (numeric, so Kubernetes runAsNonRoot can verify it). The debian12 Java 21 image lags (JDK 21.0.10 and unpatched libexpat1 and liblcms2-2 on 2026-10-20).
4. A Dependency-Check finding is suppressed only when it is a CPE false positive shown in the notes (version confusion such as a Netty CPE on reactor-netty, an umbrella CPE, another product or language), matched by file path or package URL plus the CVE names or the one wrong CPE, with an until date no later than 2027-04-30. The real library the CPE is about stays scanned without suppression.

**Consequences**: OSV, Dependency-Check and Trivy pass with 0 OSV and 0 Trivy suppressions and 13 Dependency-Check suppressions. The Keycloak admin client moves to its own 26.0.x line; KeycloakAdminClientCompatibilityTest guards it against the Keycloak 23.0.1 server the stacks ship (Q-332). Module build files keep their old pins until their owners remove them (Q-329). Every suppression and patch row must be re-checked on a Spring Boot or gRPC bump (Q-333).

---

## ADR-0069 - Security scanning in CI: scanners, thresholds, data sources and suppression policy

**Date**: 2026-10-20
**Status**: Proposed (CI-02b, hardening wave 3A)

> Numbered 0069 by assignment at the wave-3A integration; CI-02b reserved no number and supplied a draft (ADR-NEW-CI-02b).

**Context**: CI had a gitleaks job (CI-02a) and a report-only OWASP Dependency-Check step that never gated a change. The work plan asks for SAST, dependency and image scanning that fail on serious findings, with Dependabot kept. Constraints: the repository is private on the GitHub Free plan (a fixed monthly Actions-minute pool, no code-scanning upload, no branch protection), build files are owned by other packages in this wave, and the project requires that nothing is silenced without a reason (CLAUDE.md section 5, work-plan critique items 12, 18 and 19: named tests, measured numbers).

**Decision**:
1. Seven scanners, each run through a wrapper script that pins the container image by release and digest and is callable locally with the same result: gitleaks (existing), Semgrep CE with project rules in .semgrep.yml (ERROR blocks, WARNING is reported and names the package that clears it), Trivy over files (lockfiles, Dockerfile, compose, Terraform, Kubernetes) and over every built image (HIGH and CRITICAL with a fix), OSV-Scanner over the runtime classpath and lockfiles (CVSS 7.0 or higher blocks), OWASP Dependency-Check over the built Spring Boot jars (--failOnCVSS 7), and the community Semgrep packs as unpinned advice on nightly and manual runs only. Dependabot stays unchanged.
2. OSV-Scanner and Dependency-Check read the dependency set without a build file change: OSV through the CycloneDX SBOM generated from the licence report that LIC-01 already produces, Dependency-Check through the jars that bootJar already builds.
3. The policy lives in tested scripts, not in CI YAML: scan/summary.py (Dependency-Check, Trivy), semgrep/report.py (Semgrep and the fixture comparison) and pre/osv.sh each have an offline self-test run by selftest.sh. Development-only packages and unscored advisories are reported and never block.
4. Dependency-Check takes its NVD data from the Dependency-Check project mirror (no API key); NVD_API_KEY is an optional secret that switches to the NVD API and is exposed only to the scan step. The Maven Central and OSS Index analyzers are off.
5. Cost control follows D15: Semgrep, Trivy files and OSV run on every event (they need no build); Trivy image scans run where the image is already built (pull requests, nightly, manual); Dependency-Check runs nightly, manually and when a build file, lockfile, suppression file, scan script or the workflow changes (plan.py output scan_deps).
6. Suppression policy: a finding is fixed or suppressed in the scanner's own file with the narrowest matcher, a reason, and an expiry date no more than a year away; pre/scan-suppressions.sh fails the build on an entry without either, on an expired entry, on a match-everything matcher, and on an inline nosemgrep without a rule id and a reason. A rule-level path exclusion in .semgrep.yml needs a comment that names the proof (a test or a documented design fact).
7. No SARIF upload: code scanning is not available for private repositories on the Free plan; results go to the job log, annotations and the step summary, and reports are kept as 14-day artifacts.

**Consequences**: The gate is red on the integrated tree until the residue after BOOT-01 and DEPS-01 is decided with reasoned, dated suppressions, upgrades or follow-up packages (counts on wave/3a are in the 2026-10-20 wave-3A SESSION_LOG entry). Four scanner images sit on Docker Hub or ghcr and need a periodic bump (Dependabot does not track digests in scripts). Dependency-Check depends on a best-effort mirror; the NVD key is the fallback. Semgrep WARNING rules for SEC-03 and SEC-05 become ERROR when those packages merge.

**Alternatives rejected**: the Dependency-Check Gradle plugin and the OSV Gradle lockfiles (need build-file edits owned by BOOT-01); Trivy on the repository only (misses OS packages of the distroless base); one scan per module for Dependency-Check (the dependency set is shared and the minutes pool is fixed); registry rule packs as the blocking set (unpinned, so a green commit could turn red without a code change).

---

## ADR-0068 - Pilot backups: a sidecar agent with streamed WAL, verified logical sets and an encrypted off-host copy

**Date**: 2026-10-20
**Status**: Proposed (DR-01, hardening wave 3A). Does not change ADR-0045 onward or any frozen contract.

> Numbered 0068 by assignment at the wave-3A integration; DR-01 reserved no number and supplied a draft (ADR-NEW-DR-01).

**Context**: The read-only pilot (model M3, ADR-0049) runs the quickstart pair plus overlays (ADR-0058) on one VM and needs an RPO of 15 minutes, an RTO of 4 hours and a tested restore (threat model B6-D1, B6-I3, B11-I2, AR-04). Facts that shaped the design: up.sh applies the overlays in byte order, so a backup overlay sorts before the base overlay, which replaces the postgresql command and ports with !override, and archive_mode would need a server restart anyway; the postgres image accepts replication connections without a password on 127.0.0.1; Keycloak keeps its state in an embedded H2 file inside its container (no volume); Kafka is one broker with replication factor 1; DLAP_ENCRYPTION_KEY exists only in the environment file and protects every stored cloud credential.

**Decision**:
1. A backup agent runs as the sidecar dlap-backup of the pilot overlay, from the database's own image (postgres:15.1, so client and server match), sharing the postgresql network namespace, uid 999, read-only root file system, no capabilities, no published port. The database service is not modified.
2. WAL archiving is by streaming (pg_receivewal, a physical replication slot, synchronous flush), not archive_command. The slot is bounded by max_slot_wal_keep_size 4GB (set by the agent with ALTER SYSTEM), so a stalled agent loses the archive instead of filling the database disk; an invalidated or missing slot starts a new archive generation and a new base backup. The archive directory is keyed by system identifier, timeline and generation.
3. Three layers: WAL plus a weekly base backup (point-in-time restore), and a daily pg_dump -Fc -C per database with the roles (no password hashes). The row counts of a set are taken from the dump itself, and every restore recounts and fails on any difference. The restore drill (restore-drill.sh) is both the package's test and the quarterly procedure.
4. The off-host copy goes through an operator-supplied command called once per file with the file and an object key; DLAP links no cloud SDK. Everything is encrypted before it leaves the VM (openssl AES-256-CBC, PBKDF2-HMAC-SHA256, 600000 iterations, a salt per file) with a passphrase file; without one the shipper refuses. Integrity is by SHA256SUMS and SHA256SUMS.shipped, the last file shipped, which marks a set complete. The WAL segment in progress is uploaded again whenever it has grown. Copies on the VM are not encrypted by DLAP (same trust zone as the database volume, AR-04).
5. Key escrow: DLAP_ENCRYPTION_KEY is in no backup. A 64-bit fingerprint of it is recorded in every backup and a restore refuses an environment with another one. key-escrow.sh can seal the environment file with a passphrase for offline storage.
6. Not protected: Kafka (events in flight) and Keycloak's embedded H2 file. Keycloak is covered automatically once IAM-02 runs it on PostgreSQL, because the agent backs up every non-template database it finds.

**Consequences**: One extra container and a volume on every pilot VM; a documented RPO bound that depends on the operator's off-host command; the sidecar must be restarted or recreated after the database container is (it exits by itself after 90 s without a database, and docker compose up -d recreates it after a recreate); restore from a store that holds the backups of two clusters needs --system-id or --base for a named restore point; the agent connects as the bootstrap superuser.

**Alternatives rejected**: archive_command and archive_mode (needs a restart, and the overlay order stops an overlay from setting them); pgBackRest or Barman (extra images and a configuration language; revisit with CloudNativePG in the Helm chart); encrypting the copies on the VM (the database volume next to them is not encrypted either); backing up Kafka (single broker, short retention, sources can be re-read); a Keycloak volume mount or realm export now (IAM-02 owns Keycloak and will move it to PostgreSQL).

---

## ADR-0067 - Dependency alignment of the boot jars

**Date**: 2026-10-20
**Status**: Accepted (DEPS-01, hardening wave 3A)

> Numbered 0067 by assignment at the wave-3A integration; DEPS-01 reserved no number and supplied a draft (ADR-NEW-DEPS-01).

**Context**: The Spring Boot stack and the cloud SDKs share protobuf-java, gRPC, Netty, Jackson and Guava. Inside one boot jar, two versions of these resolve to one silently, which breaks at runtime: protobuf gencode checks, gRPC internal APIs, Netty buffers. Critic item 13 calls this a known hotspot for da-logingest, which will carry the GCP, AWS and Azure SDKs.

**Decision**:
1. Spring Boot's BOM is the alignment for protobuf, gRPC, Netty (with netty-tcnative on its own line), Jackson 2 (with jackson-annotations on its major.minor line) and Jackson 3. A module that imports a vendor BOM, such as com.google.cloud:libraries-bom, imports the Spring Boot BOM again after it, so Spring Boot wins.
2. Guava is not managed by Spring Boot. Its line is declared in the root build: 33.6.0, the line the Google Cloud libraries BOM 26.90.0 brings. Gradle ships the -jre jar for both the -jre and -android variants, so the suffix is not compared.
3. verifyDependencyConvergence, in the root check and build tasks and in the pre CI hook, fails when any library of these families in any enabled boot jar's runtimeClasspath resolves off the alignment. One alignment for all jars also catches conflicts between modules.
4. Known divergences are exact rows (module, family, version, reason) in CONVERGENCE_EXCEPTIONS. A row that no longer matches fails the check, so the list only shrinks.
5. Vendor SDK sets are versioned by their vendor BOM. Each library is declared without a version and with a justification line.

**Consequences**: A Spring Boot upgrade moves the alignment automatically. A vendor BOM built against older protobuf or gRPC runs on the newer Spring Boot line; GcpProviderDependencySetTest shows this works for the GCP set. Adding the AWS or Azure SDKs later must pass the same check. da-discovery is excepted until its build is aligned (Q-292). The root task resolves each subproject's classpath from outside that project, which Gradle 9 turns into an error (Q-322).

---

## ADR-0066 - Jackson 2 compatibility on Spring Boot 4.1 (BOOT-01)

**Date**: 2026-10-19
**Status**: Accepted, 2026-10-19 (BOOT-01, hardening wave 3A).

**Context**: Spring Boot 4 makes Jackson 3 (`tools.jackson`) its default and keeps Jackson 2 only through the deprecated `spring-boot-jackson2` module, which the Spring Boot 4.1 reference says "will be removed in a future Spring Boot 4.x release". 134 source files use the Jackson 2 API (`com.fasterxml.jackson.databind`), the services inject a Jackson 2 `ObjectMapper`, the Kafka JSON serializers are Spring Kafka's Jackson 2 classes, and Jackson 3 changes defaults that can change JSON bodies (FAIL_ON_NULL_FOR_PRIMITIVES and SORT_PROPERTIES_ALPHABETICALLY are on, dates are written as text; the migration guide offers spring.jackson.use-jackson2-defaults to align them). BOOT-01 must not change REST response shapes.

**Decision**: Stay on Jackson 2 for the 4.1 line. Every service adds `spring-boot-jackson2` (root build) and sets `spring.http.converters.preferred-json-mapper=jackson2`, so Spring MVC, the Feign clients and the `RestClient` use the Spring Boot-configured Jackson 2 `ObjectMapper`. Jackson settings move from `spring.jackson.*` to `spring.jackson2.*`. Jackson 3 stays on the class path (Flyway 12 and the web starter depend on it) and serves no HTTP body; because Spring Framework 7 prefers it for hand-built clients, da-discovery's own RestTemplate and RestClient instances use Jackson2ClientConverters. Hibernate's JSON columns keep Hibernate's Jackson 2 format mapper, which Hibernate 7.4 prefers when both are present.

**Consequences**: JSON bodies, Kafka JSON payloads and jsonb texts are unchanged (`ProdProfileContextTest#httpJsonStaysOnJackson2` in ten services). The horizon: before the build moves to a Spring Boot 4.x release without `spring-boot-jackson2`, a dedicated package moves the code to Jackson 3 (package renames, unchecked exceptions, Spring Kafka's Jackson 3 serializers, `use-jackson2-defaults` or explicit features to keep the JSON form, contract regeneration as the proof; Q-281). Until then every Spring Boot 4.x minor bump checks the module is still shipped.

---

## ADR-0065 - Spring Boot 4.1 platform (BOOT-01)

**Date**: 2026-10-19
**Status**: Accepted, 2026-10-19 (BOOT-01, hardening wave 3A, D21).

**Context**: The services ran Spring Boot 3.2.5, which has had no free security fixes since 2024-12-31 (api.spring.io generations feed, launch-readiness audit section 3). Hibernate ORM 6.x is LGPL-2.1 (Q-105) and the licence gate carried a dated exception for it. Hibernate ORM 7.0 and later are Apache-2.0 (Hibernate 7.0 migration guide). Spring Boot 4.0 free support ends 2026-12-31; 4.1 free support runs to 2027-07-31.

**Decision**:
1. Spring Boot 4.1.1 (the latest 4.1.x on Maven Central on 2026-10-19), with the Hibernate ORM 7.4.5.Final, Spring Framework 7.0.9, Spring Security 7.1.1, Spring Kafka 4.1.1 and Testcontainers 2.0.5 that its BOM pins. No BOM version is overridden.
2. The Spring Boot and dependency-management plugin versions are declared once, in the root build; modules request the plugins without a version. The Spring Cloud BOM (2025.1.3, the train start.spring.io maps to Spring Boot >=4.0.0 and <4.2.0-M1) is imported from the root build wherever the dependency-management plugin is applied; no module pins a Spring Cloud version.
3. Gradle 8.14.6, not 9.x: Spring Boot 4.1 needs 8.14 or later, and Gradle 9 would also force the protobuf, Jib and node-gradle plugins and the `buildDir` and `dependencyProject` uses forward. The Java 21 toolchain stays.
4. Third-party lines chosen from the projects' own statements: springdoc-openapi 3.1.1 (start.spring.io range for Spring Boot 4.0 and 4.1), with `springdoc.api-docs.version=openapi_3_0` so the published contracts stay OpenAPI 3.0.1; ShedLock 7.10.1 (its README matrix: 7.x tested with Spring 7.0 and Spring Boot 4.x; closes Q-111); Spring Shell 4.0.3 and Spring Cloud GCP 8.2.4 (start.spring.io ranges); JHipster framework 9.4.0 (its parent POM is spring-boot-dependencies 4.1.1); resilience4j-spring-boot4 2.4.0.
5. Protobuf: the runtime follows the Spring Boot BOM (protobuf-java 4.35.1); the classes stay generated by protoc 3.25.3. Protobuf supports gencode of major version V on runtimes V and V+1 (protobuf.dev cross-version runtime guarantee), and da-discovery's Google Cloud BOM pins the older 4.29.4 runtime, which newer gencode would refuse.
6. Forced removals, each unused: io.confluent:kafka-protobuf-serializer and the Confluent repository (Q-107, and the old swagger-annotations behind Q-272), hypersistence-utils-hibernate-62 (replaced by Hibernate's `@JdbcTypeCode(SqlTypes.JSON)`), spring-data-r2dbc 3.3.2, dropwizard metrics-core, the JHipster 8 BOM and the never-applied Kotlin plugin declaration. Spring Shell 4's GlassFish EL (EPL-2.0) is excluded because Tomcat's EL (Apache-2.0) already provides jakarta.el.
7. da-discovery moves from Undertow (dropped by Spring Boot 4) to Tomcat, like the other services. Its Spring Shell commands keep their Spring Shell 3 behaviour through a gated `springShellApplicationRunner`: a regular start never reaches the shell; a shell command runs once, non-interactively.
8. The licence gate's EXCEPTIONS table is empty; the self-test keeps the exception mechanism covered with its own table (`--exceptions`).

**Consequences**: One-line Spring Boot bumps from now on. Hibernate 6.5, 6.6 and 7.x behaviour changes apply (native-query dates as java.time, merge of a missing generated-id row fails, stricter mapping validation); the ones DLAP hit are fixed and listed in docs/guides/upgrade-boot4.md. A Gradle 9 move is a separate change. Q-105, Q-107, Q-111 and Q-272 close.

---

## ADR-0064 - OpenAPI contracts are generated by per-service tests and gated by a post-test hook

**Date**: 2026-10-19
**Status**: Proposed (API-01, hardening wave 2B)

> Numbered 0064 by assignment at the wave-2B integration; API-01 reserved no number and supplied a draft.

**Context**: Work-plan rule R13 needs a committed, diffable API contract per service, a gate that fails when it goes stale, and typed clients for the UI. The services cannot be started in CI without a database, Kafka and Keycloak, and the plan forbids a Gradle plugin and a root build edit.

**Decision**:
1. Each service has one OpenApiSpecExportTest in its own test source set. It builds the OpenAPI document with springdoc in a small web application context made from the controllers, controller advice and OpenAPI beans of the module's main classes. Collaborators are inert stand-ins. No database, Kafka, Keycloak, Docker, Spring Security or actuator is involved, so the contract is the business API only.
2. The contract is the whole code-level API: controllers that are conditional or profile specific (for example the discovery dev examples and the GCP log configuration) are included, because the contract must not depend on the environment that generates it. Deployment switches decide what is reachable, not what is documented.
3. Contracts live in docs/api/openapi/<service>.json in canonical form (sorted keys, two-space indent, LF, ASCII only, no servers entry). DLAP_OPENAPI_UPDATE=1 with Gradle --rerun regenerates them; --rerun is required because the Gradle build cache would otherwise restore a passing result and skip the test.
4. The gate has two layers: the test itself fails in the module job when its contract is stale, and scripts/ci-guards/post-test/openapi-drift.sh regenerates all contracts through Gradle and fails on any difference or new file, checks that every module with a controller has the test, rejects orphan contracts and compares the shared support block of the 11 test copies byte for byte (the copies exist because no shared test module is allowed without a build edit). It also prints breaking-change warnings against the base commit; they never fail the build.
5. UI types are generated with openapi-typescript 7.13.0 (MIT, pinned exactly) into frontend/src/api/generated for catalog, reporting and policyengine; TypeScript moves to 5.9.3 as required by its peer dependency.

**Consequences**: Any controller change must be accompanied by a regenerated contract (the test says how). A new module with a controller needs a copy of the test (the hook says so). The 11 copies of the support block are edited together. The workaround for the swagger-annotations classpath conflict in the support block is removed once the build is fixed (Q-272).

**Alternatives rejected**: springdoc-openapi-gradle-plugin (starts the application, needs infrastructure, edits the build); a full @SpringBootTest with Testcontainers per service (Docker, slow, heavier than the 11 existing ProdProfileContextTests); the openapi-diff Java tool (a download, replaced by an in-repo comparison, Q-278); openapi-typescript 6.7.6 (no TypeScript 5 requirement but unmaintained).

---

## ADR-0063 - Hosted demo topology: internal service network, two layer edge, restore based reset

**Date**: 2026-10-19
**Status**: Proposed (DEMO-01, hardening wave 2B)

> Numbered 0063 by assignment at the wave-2B integration; DEMO-01 reserved no number and supplied a draft.

**Context**: The public demo shows the lifecycle on synthetic data to prospects. It must be unable to call a cloud, fit a 2 vCPU / 8 GB VM, expose only TCP 80 and 443, and return to its starting state every night. ADR-0058 allows an overlay on the quickstart pair but forbids redefining `build` or `image` of base services.

**Decision**:
1. The demo is `deployment/environments/demo/docker-compose.demo.yml` on top of the quickstart pair. It adds one service (Caddy, pinned by digest) and changes environment, ports, limits, profiles, mounts and networks of the others.
2. The service network `dlap-network` is `internal: true`. Caddy, the nginx gateway and Keycloak also join a second network so their ports can be published. No cloud call is possible by construction; log sources, discovery switches, deletes (dry run) and archival (mock) are also set by literals that an environment file cannot change.
3. Edge in two layers. Caddy: TLS for two host names, an allow list for Keycloak (realm endpoints and static resources), the maintenance page, and the final security headers (the deferred `>` header form replaces the nginx copies, so each header appears once). nginx: the built SPA, API routing with per-request DNS resolution, refusals (test-rule, actuator, API documents), rate, connection, body and time limits. Directives that must be exclusive are `handle` blocks, because Caddy orders `respond` before `reverse_proxy` whatever the file order.
4. Reset by restore, not by replay. seed.sh replays the fixtures once through the real spine and takes one custom-format dump per database. The nightly reset stops the JVMs, restores every database (`pg_restore -C --clean --if-exists`), wipes Kafka (broker and volume), restarts in two waves, smoke tests the asset count, and switches the maintenance page off. A failed reset leaves the page on and the status failed. Keycloak, Caddy, the gateway and the Postgres roles are outside the reset.
5. The services run under `prod` (da-logingest `prod,demo` for the replay seeder). da-discovery consumers are forced to `earliest` because its prod profile reads from `latest` and would lose a replay that precedes the group join.
6. The start is staged in two waves through `depends_on` (wave 2 waits for wave 1 to be healthy), so the replay runs after the consumers are up.

**Consequences**:
- One demo per Docker host (container names are pinned in the quickstart files).
- Visitors' changes never survive a night. Accounts the founder creates in Keycloak do, once DEMO-02 gives Keycloak a persistent store.
- The overlay cannot pin the base images by digest (ADR-0058 forbids redefining `image`); that stays with the quickstart files (Q-192).
- Third-party assets the UI hotlinks (Google Fonts, Unsplash, Cloudinary) are blocked by the content security policy unless the founder allows them with DEMO_CSP_*_EXTRA.

**Alternatives rejected**: replay at every reset (cannot return to the same state after a day of changes); a single Caddy layer (loses the per-request DNS resolution and the SPA/static handling that the stack already has in nginx); one host name with a Keycloak path prefix (the browser origin split is what keeps the identity provider apart); HTTP/3 (would publish UDP 443); a custom demo monolith (misrepresents the architecture).

---

## ADR-0062 - Actuator exposure and the monitoring stack

**Date**: 2026-10-19
**Status**: Proposed (OBS-01, hardening wave 2B)

> Numbered 0062 by assignment at the wave-2B integration; OBS-01 reserved no number and supplied a draft.

**Context**: Q-086 (da-policyengine answered 401 on /actuator/health), Q-031, Q-177 (actuator on 8380 and 8385 while Prometheus scraped 8080), critique item 12 (services must accept the public issuer), CLAUDE.md section 9 (only /actuator/health and Swagger are exempt from authentication). Ten of eleven services declared no Prometheus registry, so the scrape endpoint did not exist, and the shipped prometheus.yml could not be loaded.

**Decision**:
1. /actuator/health and /actuator/health/** are public on every service and show status only (show-details stays when-authorized). Every other actuator endpoint, /actuator/info included, needs a token on both ports.
2. /actuator/prometheus is served on a separate management server (env MANAGEMENT_SERVER_PORT) and is open without a token there and nowhere else. Implemented as a second SecurityFilterChain per service, active only when the management port differs from the application port (@ConditionalOnManagementPort(DIFFERENT)), matching EndpointRequest.to("prometheus"), at highest precedence. With no management port set, metrics need a token like everything else, so a default deployment exposes none. The management port is for the container network, never a public address.
3. The dev stack keeps the actuator on the application port by default (the README, authzen-pdp-verify.ps1 and the e2e harnesses call /actuator/health there). docker-compose.monitoring.yml, an override file allowed by ADR-0058 (D4), moves the actuator of every service to 9091, unpublished on the host; the monitoring profile scrapes that port.
4. dlap.security.jwt.expected-issuer accepts a comma separated list so one deployment takes the internal and the public Keycloak URL. A single value behaves as before; the check stays off by default (Q-014).
5. Alertmanager ships a null receiver. The repository holds no notification credentials; operators add a receiver in their own copy (CLAUDE.md section 10.2; Q-211).
6. Alert rules read only series that exist (platform series, the two exporters, documented DLAP meters). Each rule has a promtool unit test with a firing and a quiet case. Rules for series nobody emits were removed.

**Consequences**: Scraping needs the overlay in dev and MANAGEMENT_SERVER_PORT in every other deployment (pilot, Helm; Q-245). Any container on the network can read the management port, so DEP-02 needs a NetworkPolicy that limits it to Prometheus (Q-250). The security chain code is copied into ten modules; a shared home (da-protos or a small library) is a follow-up (Q-248). /actuator/info is no longer anonymous on eight services.

**Alternatives rejected**: (a) permitAll on /actuator/prometheus on the application port, because it publishes metrics on the public port; (b) scraping with a Keycloak client-credentials token through Prometheus's oauth2 block, rejected for the dev stack because it puts a client secret into the monitoring configuration, and kept as the option for deployments that cannot isolate a management port; (c) moving the actuator to a management port in the default dev stack, because it breaks the README, authzen-pdp-verify.ps1 and the harness waits.

---

## ADR-0061 - The retire path refuses what the asset-id normalizer silently drops

**Date**: 2026-10-19
**Status**: Proposed (ID-02, hardening wave 2B). Extends ADR-0047 (canonical asset IDs) for archive, restore and delete callers; does not change the frozen contract in docs/specs/asset-id.md.

> Numbered 0061 by assignment at the wave-2B integration; ID-02 reserved no number and supplied a draft.

**Context**: ADR-0047 makes AssetIdNormalizer the only code that parses asset identifiers (plan rule R8). The normalizer is built for identity, so it drops information that is irrelevant to identity but decisive for a destructive action: the query string and fragment of an http(s) URL (a SAS token, versionId, generation, snapshot), and the partition or snapshot decorator of a BigQuery table (t$20240101, t@123). It also preserves object keys byte for byte, including trailing spaces. The retire path (da-autoarchival, da-autodelete) previously matched URI prefixes by hand and therefore never saw these cases.

**Decision**:
1. Every location on the retire path is parsed by AssetIdNormalizer, with the catalog provider, asset type and region as hints when a catalog row is the source. The provider comes from the identifier; a stated provider that contradicts it is refused.
2. The retire path fails closed on any input where the normalizer would drop information that could narrow the target: an http(s) URL with a query or fragment; a BigQuery decorator; a wildcard table; an object key with leading or trailing whitespace. Only exactly one object, or one whole table, instance or table-store resource, is a delete target; buckets, datasets, jobs and other ARNs are not.
3. Error messages and logs carry such a URL without its query string, so a SAS token or signature never reaches a log line, an audit row or an error.
4. The AWS SDK acts in the account of the ambient credentials. An RDS or DynamoDB ARN target is accepted only when its account is listed in dlap.autodelete.aws.allowed-account-ids; the default is empty, so every ARN target is refused until an operator opts in. The region-qualified rds:// and dynamodb:// short forms carry no account and keep their dedicated parser.
5. Canonical forms are handed to provider services as the canonical URI, except Azure blob objects, which are handed over as azure://account/container/path until the Azure services adopt the normalizer (transitional).

**Consequences**:
- All aliases of one resource dispatch identically, and an ARN, a projects/_/buckets/... name, s3a:// or abfss:// location no longer needs a caller-side rewrite.
- Some inputs that were accepted by prefix are now refused: names the normalizer rejects as malformed (for example one-character buckets), decorated BigQuery tables, whitespace-edged keys, version-selecting URLs. These were either invalid, or would have acted on more than the caller named.
- RDS and DynamoDB ARN deletes need operator configuration (Q-236).
- The Azure services and ArchivalTaskServiceImpl.providerFromLocation still parse by hand (Q-237, Q-238).
- Any new retire-path parser must call LocationSafety before and after AssetIdNormalizer for the same reasons; the tests named in the ID-02 acceptance list fail if a guard is removed.

---

## ADR-0060 - Log-source bindings, the registry ingest path and a partitioned replay store

**Date**: 2026-10-19
**Status**: Proposed (LOG-02bc, hardening wave 2B)

**Context**: ADR-0045 decision 8 calls for one configuration-driven parse path, and ADR-0054 froze the parser contract. Several pieces were still missing:
- Nothing read or checked a bindings file.
- REST ingest and the fixture replay bypassed the registry, so their events carried no binding, source format, origin or parse status (threat B7-S2).
- There was no transport SPI, cursor store, object checkpoint store or poll lock.
- log_events was one unpartitioned jsonb table pruned row by row on event_time. That deletes a backfill the night it lands and does not scale to data events (critic item 20).

**Decision**:
1. **Bindings file.** `LogSourcesProperties` binds under `dlap.logingest` and is imported with `spring.config.import=optional:file:${DLAP_LOGINGEST_SOURCES_FILE:/etc/dlap/log-sources.yml}`. The default path exists because Spring Boot refuses an import placeholder that resolves to nothing.
   - Hierarchy: defaults, sources, bindings, streams. The nearest level wins, then the family mapping-YAML settings, then the contract defaults.
   - `enabled: false` cascades down. `field-overrides` merge with the nearer key winning; `operation-overrides` concatenate, nearest first.
   - A stream has a transport id and a `location` map. A field override is one JSON pointer.
   - Enumerations and durations bind as text, so the validator, not the binder, reports bad values.
2. **Validation.** `LogSourceConfigValidator` enforces the design B2 rules and reports each finding under a named rule id. Findings name paths and environment variables, never values.
   - A pin that no registered parser reads is an error. A pin above the highest known minor is only a warning, because such records parse as the highest known minor.
   - Under the prod profile an error fails startup. Under any other profile the binding is marked INVALID and does not run. `dlap.logingest.config-validation=auto|strict|warn` overrides this.
   - The legacy sources must be switched off on the registry pipeline.
3. **Hash and mirror.** The effective configuration is serialized as canonical JSON with sorted keys and hashed with SHA-256.
   - `/actuator/info` shows the hash with each binding's state and hash, but not scopes, locations or credential references.
   - At startup the bindings are mirrored to `source_binding` with state ACTIVE, INACTIVE (legacy pipeline), DISABLED, INVALID, REMOVED (kept for references) or MIGRATED. MIGRATED rows are never touched.
4. **Normalized records.** ADR-0054 decision 2 ties a source kind to one provider, so the DLAP_NORMALIZED pass-through of design B1 becomes one source kind per provider: `DLAP_NORMALIZED_<PROVIDER>`, envelope `ingest-dto`, version `v1`.
   - The native key is the caller's requestId, otherwise `operation|eventTime|targetResourceId|principalId`. Event ids therefore equal the legacy pipeline's, and dedup works across pipelines.
   - The category is derived from the change type, as log-event.md section 4.3 does, and flagged PARSE_WARNING_HEURISTIC_CLASSIFICATION.
   - Built-in bindings: `dlap-rest-<provider>` (STREAM) and `dlap-replay-<provider>` (REPLAY), with persist-raw ALL. The `dlap-` binding ids, the `DLAP_NORMALIZED_` kinds and the REST and FIXTURE_REPLAY transports are reserved.
5. **Registry ingest path.** For each event, in one transaction: take an advisory lock on the event id, check for a stored copy, insert, then publish unless the id was already stored. REPLAY mode republishes, per the frozen rule.
   - Events that fail the binding's filters are dropped as SKIPPED:FILTERED_BY_BINDING.
   - Quarantined records go to every QuarantineSink bean. With none registered they are logged without the excerpt.
   - The fixture seeder replays once per start unless `dlap.logingest.replay.republish-stored=true`.
   - REST answers 202 PUBLISHED or DUPLICATE, or 422 QUARANTINED or SKIPPED. The legacy pipeline answers as before.
6. **Transports.** LogTransport beans self-register and are found by transport id. They declare their required location keys, which the validator checks.
   - The StreamRunner takes a session advisory lock per (binding, stream) and reads `[low watermark - overlap, now - settle)`.
   - It commits the cursor only after a batch has reached its outcomes, and checkpoints objects in processed_object, keyed by the SHA-256 of the object key and the etag.
   - Stream polls need no ShedLock: the advisory lock is the singleton guard.
7. **Storage.** log_events is partitioned by month of ingest_time, with a default partition. The primary key is (event_id, ingest_time).
   - event_id uniqueness comes from `pg_advisory_xact_lock`, taken by every writer, legacy included.
   - Retention creates the coming months, drops whole months older than `now - retention-days`, and deletes old rows from the default partition. It runs as a ShedLock 6.10.0 JDBC singleton using database time, with lockAtMostFor PT2H.
   - V4 copies the legacy watermarks into MIGRATED bindings and cursors and leaves source_watermarks for the legacy sources.

**Consequences**:
- Wave-3 families add mapping YAML, parser beans and transports under providers/<cloud>; nothing central changes.
- Operators get named, testable configuration errors and an auditable hash.
- REST pushes and fixture replays are told apart from provider logs by binding_id and source_format. REST pushes still carry ORIGIN_CLOUD_LOG until Q-185 adds an origin value.
- Retention granularity is one month: a row lives between retention-days and retention-days plus one month.
- V3 copies the existing log_events table inside the migration transaction.
- The legacy path gains the event-id lock and keeps its behavior otherwise.
- New runtime dependency: ShedLock 6.10.0 (Apache-2.0).

**Alternatives rejected**:
- A non-partitioned unique table of event ids: an extra write per event, with its own retention.
- Partitioning by event_time: retention would prune backfills on arrival.
- One multi-provider DLAP_NORMALIZED kind: the frozen registry forbids it without a contract change.
- Failing every profile on an error: design B2 asks dev to warn.
- Running the replay seeder in STREAM mode to get no-republish: it would stamp fixtures ORIGIN_CLOUD_LOG.
- ShedLock for stream polls: polls are per stream at per-stream intervals, so the advisory lock fits better.

---

## ADR-0059 - Independent coverage specs are the denominator of the fixture coverage score

**Date**: 2026-10-19
**Status**: Proposed (SPEC-01, hardening wave 2A)

> Numbered 0059 by assignment at the wave-2A integration; SPEC-01 reserved no number and supplied a draft.

**Context**: The FIX-01 score divided covered cases by the operations and variants in each family's catalog.yaml, which the parser package writes, so the 0.99 gate was self-graded (hardening critic item 6). Emulator output and generator output counted the same as real record shapes.

**Decision**:
1. Each log family has a read-only `da-log-fixtures/src/main/resources/dlap-fixtures/<family>/coverage-spec.yaml` (schema `dlap-coverage-spec/1`), written from vendor documentation by a package that does not build that parser. Every entry cites an https `doc_url`. Entries are documented (scored), unverified or deferred (listed with a reason, not scored).
2. The score counts every scored (envelope, version) cell of the spec. catalog.yaml only says which cells the parser claims supported. A supported row counted against a parser's own catalog fails the gate, except the tooling self-test family `selftest`.
3. Every case has an evidence class. CONFIRMED: own-account or partner capture, or a `confirmed_by` entry (proto-round-trip, processing-library, otel-expected) naming re-checkable evidence. AUTHORED: hand-written from docs or a public dataset. GENERATED: generator output without confirmation. EMULATOR: emulator capture. Operations count every class except EMULATOR; variants count CONFIRMED and AUTHORED only; confirmed coverage is reported separately and is not part of the score.
4. `scripts/ci-guards/post-test/coverage-spec-lock.sh` fails any non-merge commit that changes a coverage spec together with parser code or graded cases. Spec changes go through an orchestrator-owned spec package (critic item 23).

**Consequences**: Parser packages use the spec's ids in meta.yaml; a new family needs a spec before its parser can claim support; names nobody has confirmed stay unscored until a capture (LIVE-00 to LIVE-03) confirms them. The generator-only class depends on meta.yaml declaring `generator`; a generated case that omits it counts as AUTHORED until generators stamp it themselves (review note). The guard enforces only the commit boundary; package ownership stops a parser branch from editing a spec in a separate commit.

---

## ADR-0058 - Compose override files for e2e, demo and pilot (amends ADR-0037)

**Date**: 2026-10-19
**Status**: Accepted (DEP-01, hardening wave 2A; decision D4)

> Numbered 0058 by assignment at the wave-2A integration; DEP-01 reserved no number and supplied the text as an ADR-0037 amendment.

**Context**: ADR-0037 keeps one canonical stack, the `environments/dev/` compose pair, and treats production as documentation. The hardening program needs three deployments of that same stack that differ only in limits, published ports, profiles and a few variables: the e2e harnesses (HARN-01), the hosted demo (DEMO-01) and the read-only pilot (DEP-01). Copying the pair three times is the drift trap that ADR-0037 removed.

**Decision**:
1. Compose override files that sit on top of `environments/dev/docker-compose.yml` and `docker-compose.microservices.yml` are allowed for e2e, demo and pilot. They live under `deployment/environments/<name>/` and are named `docker-compose.<name>.*.yml`; a launcher applies them in sorted order.
2. An override may change environment, published ports (with the `!override` tag, because compose appends port lists), limits, profiles, mounts and may add services. It may not redefine a service's `build` or `image`, and it may not copy a service block. There is still exactly one canonical stack.
3. Paths in an override are relative to the first compose file, `deployment/environments/dev`. Docker Compose 2.24 or later is required for `!override`.
4. The pilot overlay (`environments/pilot/`) is not production. `deployment/PRODUCTION.md` stays the production path, and section 9 of it points at the pilot.

**Consequences**:
- The service definitions exist once. A change to the quickstart reaches the demo and the pilot unless an override deliberately changes it.
- Each overlay needs an offline check of its merged model, so a quickstart change that breaks an overlay's promise fails a script instead of a customer VM. `environments/pilot/verify.sh` is the first.
- A secret default that the quickstart publishes stays published. The pilot launcher refuses to start with it (`environments/pilot/up.sh`).
- D10 (pilot model, Q-114) is still the founder's; the pilot overlay ships on the proposed default.

---

## ADR-0057 - Remove MVEL: DSL-only policy evaluation, typed subscription filters, bounded RE2/J regex

**Date**: 2026-10-19
**Status**: Accepted (SEC-04, hardening wave 2A)

**Context**:
- Policy `rules[]` and subscription `rules[]` held MVEL expressions run through Easy Rules (base `da-policyengine/build.gradle.kts:29-32`, `PolicyEvaluationService.java:160-173`, `EventSubscriptionService.java:583-614`). MVEL compiles caller text into code. SEC-01 closed the caller-supplied paths for roles below ADMIN but kept the engine, and every condition stored earlier by any role still ran (Q-056).
- The subscription matcher never matched (`then("true")` then `facts.get("true")`, Q-058).
- The DSL `nameMatches` clause compiled a user pattern with `java.util.regex` and no bound (base `PolicyDslEvaluator.java:126-135`): a short pattern can backtrack exponentially (ReDoS).
- ADR-0031 made the dlap/v1 DSL the canonical policy form and left the MVEL path to be retired once nothing depended on it.

**Decision**:
1. No expression language in the policy engine. easy-rules-core, easy-rules-mvel and mvel2 are removed. A resolution rule in `da-policyengine/build.gradle.kts` fails the build when org.mvel or org.jeasy is requested, directly or transitively. `scripts/ci-guards/pre/no-mvel.sh` checks every module: build declarations, imports and production references by default; resolved runtime and test classpaths with `--resolved`.
2. A policy is evaluated only through its DSL document (`policyDocument`) with `PolicyDslEvaluator`, on every path: the Kafka consumer, the AuthZEN PDP and the REST evaluation endpoint. No document gives NOT_APPLICABLE, an unparseable document gives ERROR, and neither fires an action. Saving or enabling a policy requires a document.
3. `rules[]` stays on the wire only to be refused. A non-empty `rules[]` on POST/PUT `/policies`, POST/PUT `/events/subscriptions` and POST `/events/config/validate` is HTTP 400 for every role, ROLE_ADMIN included. Bean validation answers before the handler's role check; the services refuse it again for any other caller.
4. Subscriptions select events with a typed filter: `eventTypes`, `severities`, `sourceServices` (the known emitting services only), `assetPrefixes` (plain string prefixes of canonical asset URIs, ADR-0047; event identifiers pass through `AssetIdNormalizer`) and `labels` (any of them, compared without case). An empty list does not constrain, all present lists must hold, and a field the event cannot answer does not match. Limits: 50 values per field, labels up to 255 characters, prefixes up to 2048 (`AssetIdNormalizer.MAX_URI_LENGTH`), no control characters.
5. A user-supplied regular expression (the DSL `nameMatches` clause) runs on RE2/J 1.8 (Go License, BSD-3-Clause, ASF Category A) through `filter/BoundedRegex`: at most 256 characters, RE2 syntax (no backreferences or lookaround), linear-time matching, find semantics. It is refused with HTTP 400 on policy save, enable and draft save, and fails closed in the evaluator.
6. Flyway policyengine V5 (`V5__retire_mvel_rules.sql`) copies every stored condition to `retired_mvel_rules` with the rule name, description, priority, enabled flag, payload, owner id, owner name, owner status before, owner creator and `retired_at`; disables every policy and every ACTIVE or PAUSED subscription that had a rule; deletes only archived rows from `policy_rules` and `event_rules`; adds `CHECK (false) NOT VALID` so both tables refuse new rows; and adds `event_subscription_asset_prefixes` and `event_subscription_labels`.
7. `conditionLogic` on policies and drafts stays a free-text note. It was never evaluated and is not evaluated now.

**Consequences**:
- Breaking API change: `rules[]` answers 400. The UI no longer sends it. The lifecycle e2e harnesses seed DSL documents instead (SEC-04 harness patch, applied at the wave-2A integration).
- Policies and subscriptions that had rules are disabled after the upgrade. Owners re-author them as DSL documents or typed filters; `retired_mvel_rules` keeps the original text for review. A policy disabled by V5 without a DSL document cannot be re-enabled until it has one.
- An asset-prefix filter matches only events that carry a resolvable asset identifier. Events that name the asset only by catalog UUID (label assignment changes, policy actions without a snapshot URI) do not match yet.
- A `nameMatches` pattern that used lookaround or backreferences, valid before, now fails closed and is refused on save.
- `policy_rules`, `event_rules`, the `PolicyRule` and `EventRule` entities and their repositories stay mapped, empty and read-only until a later package drops them.
- `SubscriptionFilter` treats an event with no severity as INFO, so a HIGH-only filter now drops it, where the old matcher let it through (review note).
- The REST evaluation ignores `evaluationContext` and only logs it at debug (review note).

**Alternatives rejected**:
- Keep MVEL for ROLE_ADMIN only (the SEC-01 state): an admin token or a stored row still executes code on the server, and MVEL has no dependable sandbox.
- SpEL or another expression language: the same class of risk.
- Translate stored MVEL into DSL documents: MVEL is a general language, so any translation is a guess, and a wrong guess on an archive or delete policy destroys data. Archive, disable and re-author instead.
- Keep java.util.regex with a length cap only: a short pattern can still backtrack exponentially; only a linear-time engine bounds the cost.

---

## ADR-0056 - dlap_common upgrades go through a ledgered, checksum-verified runner

**Date**: 2026-10-19
**Status**: Accepted (DB-02, hardening wave 2A)

**Context**: The shared dlap_common schema is created by the init scripts under deployment/platform/database/init-scripts/, which Postgres runs only on an empty data directory. Changes after that ship as dated idempotent deltas under deployment/platform/database/upgrades/, and nothing applied them: neither compose nor the harnesses referenced the directory, and the old upgrade guide said to loop over the files by hand with no record of what a database had had. A kept database (the pilot from wave 5, any customer who upgrades) therefore never received a column or CHECK change, and Hibernate validate (ADR-0050) then failed at boot or an old constraint rejected new asset kinds at insert time. Flyway is not an option for this schema (CLAUDE.md section 6): several services must read it before any one of them runs migrations.

**Decision**: upgrades/run-upgrades.sh is the only supported way to move an existing dlap_common database forward. It records every applied delta in dlap_common.schema_upgrades (file_name, SHA-256 of the content with carriage returns removed, applied_at, applied_by). The table is created by 02-create-common-schema.sql on a fresh install and by the runner on a database that predates it; the two definitions are identical and a test compares them. Rules: (1) idempotent, a recorded file is skipped, so a second run applies nothing; (2) tamper-evident, a recorded file whose checksum changed is refused with exit 2 and nothing is applied, an applied delta is never edited and a new later one is added instead; (3) ordered, a pending file that sorts before the newest applied file is refused unless --allow-out-of-order, because deltas can redefine the same CHECK constraint; names are compared bytewise (COLLATE "C"); (4) every file is verified before any is applied, the first failing delta stops the run and is not recorded, exit 1; (5) a delta is applied and then recorded in two steps, a crash between them re-applies it next run, which is safe because every delta is idempotent; (6) one runner at a time, serialised by the caller; (7) service roles get no access to the ledger. Deltas are named YYYY-MM-DD_short_name.sql with a date later than every shipped delta. The per-service Flyway rule is unchanged. The pilot up.sh (DEP-01) and the Helm hook (DEP-02) call the runner; CLAUDE.md section 6 gains one line pointing at it.

**Consequences**: A database that had deltas applied by hand is brought current by one run: each delta is applied again (idempotent) and recorded. The proof is CommonSchemaUpgradeTest in da-catalog: a database built from the previous release's init scripts (fixtures from sdg 7984d1d), the real runner and deltas, then Hibernate validate, then equality with a fresh install. When a release is cut, the fixtures are replaced by that release's init scripts. A fresh install starts with an empty ledger, so its first runner pass re-records every delta as a no-op. The ledger needs a role that owns the dlap_common objects or a superuser, and the connection must name the database dlap_common because the 2026-06-28 delta reconnects to it by that name. AssetSchemaUpgradeContainerTest (ADR-0046) now excludes the ledger from its comparison of a bare-delta upgrade with a fresh install, because only the runner creates the ledger on an upgrade. As merged in wave 2A, the pilot up.sh does not call the runner yet (Q-142).

---

## ADR-0055 - Catalog event ingest: exactly-once effect, event-time state, normalized matching

**Date**: 2026-10-19
**Status**: Proposed (CAT-02a, hardening wave 2A)

**Context**: The catalog consumers stamped `Instant.now()` as last access and bumped `accessCount` on every redelivery and failure (`AssetLifecycleEventListener.java:97-110` at 7cc841a), let any DELETE set the status regardless of event order (`:84-88`), dropped events for unknown assets silently (`:163-171`), defaulted unknown providers to GCP (`DiscoveryAssetDiscoveredListener.java:133-211`), and the enrichment listener swallowed exceptions inside its transaction and acknowledged anyway (`LogEventEnrichmentListener.java:102-115`). Lookups used the raw `targetResource.id`, so log events rarely matched crawler rows, and DISC-02's canonical crawler IDs would duplicate rows stored in older forms (plan critic item 10). ADR-0045 fixes the clocks and dedup layers, ADR-0046 the field rules, ADR-0047 the identifiers; this ADR fixes how da-catalog applies them.

**Decision**:
1. **One path per record.** Every catalog consumer reads with `DlapLogEventCodec` and applies the record in one transaction; the offset is acknowledged after the commit. Failures propagate to the shared error handler: three retries (1 s, doubling), then `<topic>.DLT` with the partition left to the producer. `LogEventDecodeException` goes to the DLT without retries. Asset-change notifications publish after commit.
2. **Exactly-once effect.** `dlap_catalog.processed_event(consumer, event_id, asset_id)` is claimed with insert-or-skip after the `dlap_assets` row is locked `FOR UPDATE`, in the transaction of the change. Consumers: `catalog-asset-state`, `catalog-usage`, `catalog-enrichment`, `catalog-lineage` (a lineage claim on a downstream row that does not change takes no lock; its primary key keeps it idempotent). A superseding event also claims the superseded id. An event that matched no row leaves no claim, so a replay applies it once the row exists. Claims are kept 100 days, longer than the 90-day log replay window (design 06 B5 said 35, which would double-count replays), and pruned by a replica-safe job that needs no ShedLock because it is not a singleton.
3. **Usage.** `dlap_catalog.asset_usage_daily(asset_id, day, reads, writes)`, day = UTC date of `event_time`. A read is a successful `DATA_READ` (never `DATA_READ_OBSERVED`), a write a successful `DATA_WRITE`; each increment needs a `catalog-usage` claim on `LogEventIds.usageDedupKey` (shared key across accounts; the superseded id for a correction) and the asset. The table is a per-row history across incarnations: a late read of an earlier incarnation counts on its own day. The legacy `accessCount` (item 8) counts the current incarnation only.
4. **Targets.** `canonical_uri`, else `id`, normalized with event hints (provider; kind from service types, crawler type ids, CloudTrail and GCP resource types; project; region) and operator default hints (`dlap.catalog.ingest.match.default-hints.*`, single tenant per ADR-0049). Object targets roll up to their container; objects under `dlap.catalog.ingest.governed-prefixes` (default empty) also get a row. Crawler observations of ungoverned objects are skipped.
5. **Matching** in this order: exact canonical URI; `normalize(stored asset_uri)` with hints from the row, kept in the derived, self-healing table `dlap_catalog.asset_uri_canonical` (startup build, write-through, rate-limited catch-up on a miss, stale-entry repair; the oldest of duplicates wins, as ID-03 merges); the raw identifier only for unresolvable targets and only within the event's provider. Listeners wait for the startup build before they open the record transaction, so the wait holds no pooled connection; catch-up and stale repair run in short `REQUIRES_NEW` transactions bounded by a try-lock.
6. **Creation.** Only a successful management CREATE or a crawler observation (and, under a governed prefix, an object's creating write) creates a row: `asset_uri` canonical, provider and service type from the ID, `native_asset_id` the raw form. No default provider. Unmatched and unresolved targets are counted (`dlap_catalog_unmatched_target_total`, `dlap_catalog_unresolved_target_total`).
7. **Rule interpretations of ADR-0046** (to be confirmed by the contract owner, Q-135). (a) A crawler observation without a result status counts as successful for lifecycle, creation time, size and metadata, never for activity. (b) A CREATE is a status transition only on a DELETED row; on an ACTIVE, ARCHIVED or PENDING_DELETION row it changes neither status nor `lifecycle_event_time`, because crawlers publish every resource they see as a CREATE and, applied as a transition, each crawl would undo an archive or make a delayed ARCHIVE or DELETE stale. (c) A row a crawler creates takes the reported `created_time` as `lifecycle_event_time` when that is not after the crawl, else null; a row a CREATE log event creates takes the event time. (d) A new incarnation follows ADR-0046 section 4 to the letter: `recreated_at`, `cloud_created_at` and `lifecycle_event_time` take the CREATE's event time (a crawler's observation time when a crawl sees the resource again), and the activity maxima, size and retention evaluation are cleared unconditionally, including new-incarnation evidence delivered before the CREATE. A legal hold survives: it is an input, not evaluation output. (e) Proposed, not implemented: start the incarnation at an in-range `created_time` and keep evidence at or after that start. It needs the orchestrator's contract-change package for ADR-0046 section 4, asset-descriptor rule 5 and the `recreatedAt` field row.
8. **Legacy bag.** `dlap_usage_metrics.lastAccessedAt` and `accessCount` mirror event-time values for the asset API until CAT-02b moves its reader; a new incarnation resets `accessCount` to 0 and reads older than `recreated_at` do not add to it. Then the keys are dropped.
9. **Clock.** An injected `Clock` stamps operational times only.

**Consequences**:
- Redeliveries, overlap polls and replays no longer move counters or times; late events never move state backwards.
- Rows stored before canonical IDs are updated in place, not duplicated, until ID-03 re-keys them; ID-03 can use `asset_uri_canonical` for its dry run.
- New rows carry canonical URIs, so tooling that matched raw forms (harness predicates for `projects/...` and blob URLs) must accept `bq://` and `az://` (applied to the harness predicates at the wave-2A integration).
- Object CREATE events outside governed prefixes no longer create rows; lineage from failed operations is dropped; harvested owners follow event time.
- Under item 7(d), evidence of a recreated resource that is delivered before its CREATE is cleared and, because its claims are taken, a replay does not restore it; a recreation first seen by a crawl is dated at the crawl, later than the true creation. Item 7(e) would remove both effects.
- API writes in AssetService still save whole rows without a lock (CAT-02b).
- `processed_event` grows with event volume times matched rows until pruned.
- Metadata refresh and harvest paths do not yet check the incarnation boundary, so a delayed event older than `recreated_at` can still change descriptive fields (review note, folded into Q-135).

**Alternatives rejected**:
- An in-memory URI index: stale across replicas and empty after a restart.
- Raw-id matching across providers: bare names (`logs`) collide between clouds.
- Treating every CREATE as a transition: a crawl would reactivate archived rows and make delayed archives stale.
- Shipping item 7(e) ahead of the contract: wave-1 contracts change only through the orchestrator.
- JPA entities for the new tables: they would change the entity set CommonSchemaValidateTest pins, outside this package.
- A 35-day claim retention: shorter than the replay window.

---

## ADR-0054 - Frozen log parser contract: registry, canonical event, mapping YAML

**Date**: 2026-10-19
**Status**: Proposed (LOG-02a, hardening wave 2A)

**Context**: ADR-0045 decision 8 calls for one versioned, configuration-driven parse path, but the wave-3 families (GCP-01, CT-01, AZ-01, DBX-01 and the rest) cannot be built in parallel until its interfaces are fixed; critic item 13 split LOG-02 into LOG-02a (this contract), LOG-02b (bindings), LOG-02c (transports, cursors, migrations) and DEPS-01. Without a frozen contract each family would invent its own version handling, outcome vocabulary and settings location, and the plan's rules (unknown major quarantined, newer minor warned, no republish, persist-raw default, sharedEventID usage dedup, legacy default) would be enforced only by review.

**Decision**:
1. The contract is `com.dlap.logingest.parse.core` in da-logingest, specified in `docs/specs/log-parser-contract.md`: a `RawRecord` goes in, a `ParseResult` comes out (Parsed, Skipped with a `SkipReason`, or Quarantined with a `QuarantineReason` and an optional `PARSE_STATUS_PARTIAL` pass-through event), through one `ParserRegistry`. Source kinds, envelopes, versions and binding ids are open strings with a fixed syntax (`FormatNames`).
2. `VersionDetector` and `LogRecordParser` beans self-register. `ParserContractConfiguration` collects them and adds one declarative detector per mapping-YAML envelope. An ambiguous set fails startup: two detectors for one envelope, overlapping version ranges, mixed version schemes, a parser without a detector, one source kind claimed by two providers.
3. Versions are major-minor ranges (one major, floor to highest known) or named sets. A detected version no parser reads is quarantined as `UNKNOWN_MAJOR_VERSION` whatever the binding pins; a newer minor is parsed as the highest known with `PARSE_WARNING_NEWER_MINOR`; a differing pin follows `on-mismatch` (`QUARANTINE`, `PARSE_WITH_PIN`, or `PARSE_WITH_DETECTED`, the default). `UNKNOWN_MAJOR_VERSION` also covers a minor below a parser's floor and an unlisted named version, because the frozen `QuarantineReason` has no separate value.
4. `CanonicalEvent` enforces the producer rules at construction: business time only from the provider clock (no clock means `Skipped(MISSING_EVENT_TIME)`), `canonical_uri` only from `AssetIdNormalizer`, a known classification and result except on a pass-through. `CanonicalEventMapper` alone writes `DlapLogEvent` and `LogQuarantineRecord`, stamping source format, detected version, parser version, binding, origin from `IngestMode`, parse status, sorted warnings, and ingest time from the application's unique or primary `Clock` (UTC system clock otherwise).
5. `event_id` stays ADR-0045 decision 4: lowercase hex SHA-256 of `<SOURCE_PROVIDER>|<native key>`, with non-primary source kinds namespacing their key as `<SOURCE_KIND>|...`; this namespacing is how the plan's shorthand `SHA-256(source_kind|native_key)` is realized without invalidating stored ids. `IngestMode.shouldPublish` forbids republishing a stored id outside `REPLAY`. Usage dedup is `LogEventIds.usageDedupKey`: `shared_event_id` plus the target when CloudTrail `sharedEventID` is present (critic item 14).
6. `PersistRaw` defaults to `QUARANTINE_ONLY` for `DATA_READ`, `DATA_WRITE` and `DATA_READ_OBSERVED` and to `ALL` otherwise; `SAMPLED` chooses from the event id so every replica and replay agrees. The policy governs what the `log_events` writer keeps; the published event always carries `raw_event`.
7. Per-family settings and the declarative half of a parser live in one YAML per family, `dlap/log-formats/<family>/mapping.yaml`, contract version `spec-version: 1`, syntax fixed by the JSON Schema `dlap/log-formats/schema/log-format-mapping.schema.json` (draft 2020-12). `MappingSpecValidator` enforces the schema plus the rules a schema cannot express at startup, with no schema library at runtime; YAML is read with SnakeYAML's safe constructor. `MappingDrivenParser` executes a mapping, with hooks for target, related resources and raw event.
8. Operations missing from a classification table fall back to one shared `VerbHeuristics`, flagged `PARSE_WARNING_HEURISTIC_CLASSIFICATION`, which follows log-event.md section 4.3 (a metadata read is `MANAGEMENT`, not a data read): a metadata-read verb (`list`, `describe`, `lookup`, `search`) and a content-read verb on a permission noun (get-policy, get-acl, getIamPolicy) give `ACCESS`/`MANAGEMENT`; only content-read verbs (`get`, `read`, `head`, `download`, `select`, `query`, `scan`, `batch get`) give `ACCESS`/`DATA_READ`. A guessed metadata read never moves `last_accessed_at` or a usage count. Operations the verb rule gets wrong (BigQuery `tabledataservice.list` returns rows; `GetBucketLocation` returns configuration) go in the family's explicit table.
9. `dlap.logingest.pipeline` is `legacy` (default) or `registry`; any other value fails startup; `@ConditionalOnLegacyPipeline` and `@ConditionalOnRegistryPipeline` gate beans. This decision wires nothing into the live ingest path.
10. `parse/**` depends on JSON, Spring core and the da-protos contract only. An ArchUnit test forbids provider SDKs, the legacy sources, transports, and Kafka, JPA, web, JDBC and HTTP client types.
11. Every family proves itself by extending `AbstractParserContractTest` over its `da-log-fixtures` family: expected outcome and events, wire contract, determinism, unknown-field tolerance, seeded totality, a parser for each supported catalog version.

**Consequences**:
- LOG-02b builds `BindingConfig` from the bindings file and validates pins against `ParserRegistry.rangeFor`; LOG-02c adds transports that frame units with `RecordFraming` and call the registry; LOG-02b or LOG-03 applies `PersistRaw.persists` at the `log_events` writer and publishes `CanonicalEventMapper.quarantine` output and the unknown-field counts; wave-3 families add a mapping YAML, parser beans and a contract test and edit nothing central.
- Families must list in their classification tables every content-bearing list operation and every configuration-only get operation; the heuristic is a fallback, not a substitute for the table.
- The fallback never guesses `DATA_WRITE` (put, upload, insert and copy map to CREATE/MANAGEMENT), so families must also list content-write operations in their explicit table, or a guessed PutObject will not move `last_modified_at` (review note; the spec does not say this yet).
- The legacy pipeline is unchanged until a later package flips the default.
- Breaking changes to these types or to mapping `spec-version` 1 go through the orchestrator's contract-change package (critic item 23); additive changes need an ADR.
- Test-scope dependencies only: archunit 1.2.1, the da-log-fixtures test fixtures, networknt json-schema-validator 1.4.0, all Apache-2.0.

**Alternatives rejected**:
- Java enums for source kinds and envelopes: every family would edit a central type (decision D1).
- A JSON Schema library at runtime: a runtime dependency before DEPS-01; the Java validator plus a schema parity test gives the same guarantee.
- Letting `PARSE_WITH_PIN` parse a record whose major is unknown: a breaking format would be read by an old parser.
- One YAML per (source, version) as in the design draft: versions mostly share a mapping, so per-version deltas inside one family file avoid copies.
- A reads-first heuristic that makes every guessed read `DATA_READ`: it contradicts log-event.md 4.3 and lets guessed metadata reads inflate usage counts and `last_accessed_at`.

---

## ADR-0053 - Discovery connection resolution and scheduler singleton

**Date**: 2026-10-19
**Status**: Proposed (DISC-01, hardening wave 1)

> Numbered 0053 by assignment at the wave-1 integration; DISC-01 reserved no number. ADR-0048 stays reserved for EMU-01 (decision D5).

**Context**: The scan API depended on IAdminServiceClient whose only implementation was a dev-profile mock, so non-dev profiles could not start and the dev stack scanned made-up accounts. The scheduled trigger created jobs without a crawler executable (they never ran) and, with several replicas, fired once per replica per cron tick.

**Decision**:
1. Exactly one IAdminServiceClient bean per configuration. HttpAdminServiceClient (da-admin-service over the dlap.service-auth client-credentials token, fail closed) is used on every profile except dev and test; dlap.discovery.admin-client.mode=http forces it on dev. The mock can never be selected outside dev and test. Each M2M endpoint it calls needs an explicit ROLE_SERVICE_ACCOUNT entry on the admin side (ADR-0034).
2. The scheduled trigger runs as a cluster-wide singleton with ShedLock JDBC (usingDbTime, lock table in the service's own Flyway: discovery V10, lockAtLeastFor PT2M, lockAtMostFor PT30M), and attaches the default crawler executable to the saved job before submitting it.
3. ShedLock 6.10.0 is the standard for Spring Boot 3.x services; 7.x requires Spring Framework 7 and Spring Boot 4. This overrides plan rule R7 ("ShedLock JDBC 7.x") until the repo moves to Boot 4.

**Consequences**: A production deployment cannot scan stand-in accounts. Scan-runner roles can list non-secret connection summaries without admin access. Replicas no longer duplicate scheduled scans. Upgrading to Boot 4 later means bumping ShedLock to 7.x. The scheduler still uses a static connection-id list until the live pull is built. The live M2M path needs the da-admin-service CloudConnectionController allow-list change (Q-108) before it works.

---

## ADR-0052 - Honest offline labelling via a deterministic rules provider

**Date**: 2026-10-19
**Status**: Proposed (LBL-01, hardening wave 1)

> Numbered 0052 by assignment at the wave-1 integration; LBL-01 reserved no number.

**Context**: The demo labelled assets with MockLLMClient, which chose labels at random, and the pipeline recorded them as SMART_LABEL_ML (a model inference). A reviewer could not tell invented labels from real ones.

**Decision**:
1. Add provider type `rules` (RuleBasedLabelClient, bean rulesLLMClient) to da-llm-client, registered through LLMClientFactory. ILLMClient is unchanged (ADR-0044 preserved).
2. Rules are pure data (LabelRule: keyword or regex, fields NAME/PATH/METADATA, fixed confidence). The same input always yields the same suggestions in the same order. Every suggestion names its rule (LabelSuggestion.ruleId) and carries provenance source SMART_LABEL_RULE (LabelSuggestion.source, null for model providers).
3. The shipped set (DefaultLabelRules, version rules-v1) may only escalate: it never proposes PUBLIC or INTERNAL and never proposes ARCHIVED. It reads only name, path and business metadata, never row content, never reads existing labels back, and never echoes asset text into reasoning. Weak signals are kept below the 0.70 auto-apply threshold so they go to human review.
4. `rules` is a real provider selected explicitly; nothing falls back to it or to the mock silently. The mock stays an explicit opt-in (dlap.autolabel.allow-mock, default false) and is deterministic and honest about being a placeholder.
5. `rules` and `mock` never serve free-form completion (complete() throws), so llm.provider=rules does not resolve for policy drafting.

**Consequences**: Demos can explain every label. Labels are only as good as the naming; this is stated in the provider contract and README. Stamping SMART_LABEL_RULE end to end needs AutoLabelOrchestratorImpl to read LabelSuggestion.source (one line, outside LBL-01 Owns, Q-098). Changing rules, confidences or labels requires bumping DefaultLabelRules.VERSION. Custom rule sets are supported by declaring a RuleBasedLabelClient bean.

---

## ADR-0051 - Dev install path: one root .env, one AWS variable set, /actuator everywhere

**Date**: 2026-10-19
**Status**: Accepted (INST-01, hardening wave 1)

> Numbered 0051 by assignment at the wave-1 integration; INST-01 reserved no number.

**Context**:
- Docker Compose reads .env from the directory of the first compose file (deployment/environments/dev), not the repo root and not the working directory. The README told people to create a root .env, so it was ignored. env.dev.example told them to create a second one in the dev directory.
- The documented AWS variables (DLAP_AWS_*) fed spring.cloud.provider.aws.* in da-discovery, which no code reads. The beans read cloud.provider.aws.*. The compose set CLOUD_PROVIDER_AWS_ENABLED=false directly, so the documented set was dead.
- da-discovery served health on the JHipster base path /management while every other service, the Prometheus job and the health scripts use /actuator.
- The log-ingest GCP and CloudTrail sources are @Profile gated (gcp|prod, aws|prod) and the dev compose runs profile dev, so they could never start there, and the compose forwarded none of their settings.

**Decision**:
1. The dev stack has one env file: .env at the repo root, created from .env.example and passed with docker compose --env-file .env from the repo root. No second .env in the dev directory. Every ${VAR} the compose pair reads must be documented in .env.example (enforced by InstallPathContractTest).
2. The AWS discovery variable set is DLAP_AWS_ENABLED, _REGION, _ENDPOINT, _ACCESS_KEY and _SECRET_KEY. da-discovery binds them to cloud.provider.aws.* and aws.region. CLOUD_PROVIDER_AWS_* environment variables remain a valid override. Because config/application-dev.yml pins aws.enabled=false above the YAML, the dev compose maps DLAP_AWS_ENABLED onto CLOUD_PROVIDER_AWS_ENABLED until that pin is removed. Log-ingest keeps its own DLAP_LOGINGEST_GCP_* and DLAP_LOGINGEST_AWS_* groups, off by default, and DLAP_LOGINGEST_PROFILES carries the profile a source needs.
3. Every service exposes health at /actuator/health. da-discovery's management base path is /actuator.

**Consequences**:
- Anyone who set a variable in a root .env and relied on it being ignored now gets that value. .env.example values were audited for that (encryption key, Ollama model, Unity URIs).
- Later packages that edit the compose pair (OBS-01, SEC-05, IAM-02, INST-02) must keep the pass-through, or update .env.example and the contract test together.
- da-policyengine's /actuator/health still needs a token until its SecurityConfiguration changes (Q-086).

---

## ADR-0050 - Schema contract: validate by default, and text[] maps to String[]

**Date**: 2026-10-19
**Status**: Accepted (DB-01, hardening wave 1)

> Numbered 0050 by assignment at the wave-1 integration; DB-01 reserved no number.

**Context**: da-catalog and da-autodelete defaulted to ddl-auto=update. Update never checks types and let the shared dlap_common schema drift. Turning validate on exposed that Hibernate 6.4 maps every List<String> as jsonb once any entity in the persistence unit maps one as jsonb, which broke User.roles (text[]) at validation and at write time.

**Decision**: Every DB-backed service defaults to validate (CLAUDE.md section 6). Array columns are mapped as String[], never List<String>. Each service has a Docker-backed test that applies the real migrations or init scripts and lets Hibernate validate. Existing databases follow docs/guides/upgrade.md.

**Consequences**: Schema drift fails at boot instead of at runtime. Adding a column to a da-protos entity needs 02-create-common-schema.sql plus a dated delta, checked by CommonSchemaValidateTest. da-discovery still resolves ddl-auto to none through config/application.yml (Q-041).

---

## ADR-0049 - Single-tenant deployments through GA; the first hosted offer is single-tenant managed

**Date**: 2026-10-19
**Status**: Accepted (amends ADR-0013 and ADR-0023)

**Context**: ADR-0013 made v0.1.0 single-tenant and left multi-tenancy as "a 0.3.0+ decision" (`docs/DECISIONS.md:1367-1370`). ADR-0023 sketched per-tenant fan-out on one shared topic keyed by tenant id (`docs/DECISIONS.md:1006-1009`). The hardening round needs a pilot model and a first hosted offer. The code is single-tenant by construction:
- no table has a tenant column;
- asset URIs, label names, user emails and Keycloak subjects are unique per deployment (`deployment/platform/database/init-scripts/02-create-common-schema.sql:34-35,61,84` at 7984d1d);
- every service trusts one realm (`da-catalog/src/main/resources/application.properties:86`);
- topic names are compile-time constants (`DlapConstants.java:206-269` at 7984d1d);
- log sources use one process identity (`GcpLogSource.java:79-81` at 7984d1d);
- one key encrypts every stored credential (`da-admin-service/src/main/resources/application.properties:149`);
- the PDP and PEP caches have no tenant key (`AccessDecisionService.java:104`, `DecisionCache.java:87-95`).

Making all of this tenant-aware touches every service, the realm, the event contract and the UI.

**Decision**:
1. One deployment serves one customer through GA. ADR-0013's model extends from v0.1.0 to every release up to GA. No tenant column, header or claim is added speculatively.
2. Seven models are documented in `docs/architecture/deployment-models.md`: M0 zero-access scan kit; M1 quickstart; M2 hosted demo, synthetic data only; M3 read-only pilot; M4 self-hosted production, the default through GA; M5 single-tenant managed; M6 multi-tenant hosted, not offered.
3. The first hosted offer is M5: one Helm release per customer, with its own Postgres, Kafka, Keycloak realm, key, cloud identity, backups and audit chain.
   - Default placement is a DLAP-owned project, AWS account or Azure subscription dedicated to the customer. The customer's own account is an option.
   - Packing several customers onto one shared cluster waits for the DEP-03 isolation controls and a pen test (SEC-09).
   - Stateful services are never shared.
4. The pilot model (M3) is the proposed default for hardening decision D10. It stays proposed until the founder decides. The alternative is the M4 release on GKE.
   - It runs on a single VM in the customer's account: the quickstart pair plus the pilot compose overlays, in shadow mode.
   - It has the Reader role on the VM's own identity.
   - Every port except the TLS entry point is loopback-only.
   - DLAP holds no standing credential.
5. The hosted demo (M2) is the only deployment that many organizations use at once. That is safe only because it holds synthetic fixtures, so no customer data, log, credential or cloud connection ever enters it.
6. Multi-tenancy is reopened only by a new ADR. A trigger must fire: T1 demand, T2 fleet size, T3 measured footprint, T4 channel partner or T5 cross-customer feature. All preconditions must also hold: M5 is covered by a SOC 2 Type II report; RBAC-03, AUD-01, AUD-02a, AUD-02b, HA-02 and DB-02 have landed; an isolation suite exists before the first tenant_id column. Section 7 of the document is the requirement list.
7. The ADR-0023 shared-topic fan-out sketch is withdrawn. Kafka ACLs stop at the topic, so on a shared topic every consumer reads every tenant's events. Any future shared plane uses per-tenant topic prefixes with PREFIXED ACLs (KIP-290).

**Consequences**:
- Isolation stays a property of infrastructure, which is easy to audit. No query path spans customers.
- Every hosted customer costs one full stack. The per-release footprint is measured with the DEMO-01 method and feeds trigger T3.
- Before the first M5 contract, M5 needs: the Helm chart with workload identity (DEP-02, DEP-03); TLS (SEC-06), production Keycloak (IAM-02), the boot guard (SEC-05) and KMS (SEC-10); backups with a passed restore drill (DR-01, DR-02); an audit chain that records operator access (AUD-01, AUD-02b); a SOC 2 report covering the service.
- M5 also needs a data-processing agreement and a region choice per customer, because DLAP would hold the catalog, normalized audit events that name principals, labels, policies and decisions.
- Separation between business units inside one customer uses RBAC-02 and RBAC-03 scopes, not tenancy.
- Docs, the UI and listings never call DLAP multi-tenant. CLAIM-01 removed the stale gcp-marketplace claims.
- The "revisit in v0.3.0" note in `docs/OPERATING_PLAN.md` is superseded by the triggers.

---

## ADR-0047 - Canonical asset identifiers

**Date**: 2026-10-18
**Status**: Accepted (ID-01, hardening wave 1). Supersedes nothing. Related: ADR-0045 (log layer), ADR-0046 (asset time, size and retention; object events roll up to the parent).

**Context**: Crawlers, log sources, lineage and the retire path name the same resource in different forms, so log events rarely join to catalog assets. Examples:
- The S3 crawler writes s3://b while CloudTrail gives b (S3ResourceCrawler.java:145, CloudTrailLogSource.java:249).
- BigQuery appears as p:d.t from the crawler, projects/p/datasets/d/tables/t in audit logs, and p.d.t from SQL lineage. Delete accepts only bq://p/d/t or p.d.t (GcpCloudDeletionService.java:413-441).
- Azure has three blob dialects that the archive and delete paths disagree on.
- The catalog guesses provider and type from keywords and defaults to GCP (DiscoveryAssetDiscoveredListener.java:133-211).

**Decision**:
1. One rule for the form: short-scheme URIs where the ecosystem has one, provider-native IDs otherwise.
   - s3://{bucket}[/{key}]; the AWS ARN for other AWS kinds; athena://{region}/{account}/{queryExecutionId}.
   - gs://{bucket}[/{object}]; bq://{project}/{dataset}[/{table}]; bqjob://{project}/{jobId}; //cloudsql.googleapis.com/projects/{p}/instances/{i}.
   - az://{account}[/{container}[/{path}]] for Blob and ADLS Gen2; the lower-cased ARM ID for other Azure kinds.
   - uc://{metastore}/{catalog}[/{schema}[/{table}]] and uc://{metastore}/{catalog}/{schema}/volumes/{volume}.
   - dbx://{workspaceId}/{jobs|runs|queries}/{id}; snowflake://{account}/{db}/{schema}/{table}.
2. Identity is the URI. Account, project, region and location are attributes unless the native ID contains them.
3. Case rules:
   - Lower-cased: GCS buckets and GCP projects, Azure accounts and containers, ARM IDs, Unity Catalog names, RDS and Redshift identifiers, Glue database and table names.
   - Kept: S3 bucket names (legacy upper-case buckets exist), object keys, BigQuery dataset and table names, DynamoDB tables, Glue jobs.
   - Keys are raw UTF-8. Only http(s) URLs are percent-decoded.
4. Never guess. Ambiguous or under-specified forms are rejected with a reason, and destructive callers fail closed.
5. com.dlap.common.assetid.AssetIdNormalizer in da-protos is the only code that builds or parses asset IDs (rule R8). It is pure Java.
6. Each AssetKind maps to one service_type. The kind list is frozen for wave 1: the existing service types plus S3_OBJECT, GCS_OBJECT, BLOB_CONTAINER, BLOB_OBJECT, GLUE_DATABASE, GLUE_TABLE, UNITY_VOLUME, SNOWFLAKE_TABLE. Providers add DATABRICKS and SNOWFLAKE.
7. OpenLineage dataset naming is mapped both ways (openlineage.io/docs/spec/naming, 1.54.0).
8. Kafka keys become the canonical URI only after every writer emits canonical IDs (ID-03 runbook).

Departures from design B4, with reasons:
- S3 bucket case is kept. Lower-casing could point a delete at a different legacy bucket.
- BigQuery job location is an attribute, not identity. Most job names lack it.
- Databricks runs are dbx://{ws}/runs/{runId} with the job as parent and attribute. The Jobs API documents run_id as unique across jobs.
- Snowflake, volume and Cloud SQL rows are added.

**Consequences**:
- Changing a canonical form is breaking: it needs an ADR, a SPEC_VERSION bump, an ID-03 re-key and re-run consumer contract tests. Adding an alias or a kind is additive.
- Callers must pass hints from the binding: account, region, project, metastoreId, workspaceId, and a kind from service_type.
- dlap_assets.native_asset_id keeps the first raw form seen (AssetIdParseResult.raw()).

---
## ADR-0046 - Asset granularity, time, size and retention model

**Date**: 2026-10-18
**Status**: Accepted (CAT-01, hardening wave 1; decision D2, founder informed)

**Context**: The catalog cannot answer "how old is this, how big, when was it last read, when may it go". `dlap_common.dlap_assets` had no creation, last-read, last-modified, size or retention column (`deployment/platform/database/init-scripts/02-create-common-schema.sql:82-147` at 7984d1d). Last read lived in the `dlap_usage_metrics` JSONB, stamped with the consumer's `Instant.now()` and bumped on failures and redeliveries (`da-catalog/.../kafka/AssetLifecycleEventListener.java:97-110`). Events for assets missing from the catalog are dropped without a count (`:163-171`). A `deny-if-stale` clause treats an unknown time as not stale (`da-policyengine/.../dsl/PolicyDslEvaluator.java:169-173`). The CHECK constraints rejected Databricks, Snowflake and object-level kinds. ADR-0045 fixes which clock drives business state. This ADR fixes what the catalog stores and how retention is decided.

**Decision**:

1. **Granularity.** Assets are containers, datasets, tables and jobs. Object kinds (`S3_OBJECT`, `GCS_OBJECT`, `BLOB_OBJECT`) get a row only under a governed prefix that an operator declares in configuration (CAT-02 owns the setting). Object events roll up to the container (`CanonicalAssetId.rollUp()`, ADR-0047), so a container's activity times are maxima over its objects. Size never rolls up from events; a container's size comes only from a container-level observation.
2. **Unknown targets.** An event whose rolled-up target matches no row changes nothing and is counted (`dlap_catalog_unmatched_target_total{provider,kind,category}`). The event stays in da-logingest's store. Activity events never create rows, because a binding's log can name another account's bucket or a public dataset. Rows come from crawlers, CREATE events in the binding's own log, and operators. Once a row exists, a replay (`dlap-ingest-mode=REPLAY`) applies the missed activity; per-consumer dedup makes it safe.
3. **Time fields.** New nullable columns: `cloud_created_at`, `last_accessed_at`, `last_modified_at`, `last_permission_change_at`, `lifecycle_event_time`, `recreated_at`, `size_observed_at`. All are business times (ADR-0045 rule). The `last_*` fields are maxima over SUCCESS events of the matching category (`DATA_READ` and `DATA_READ_OBSERVED`; `DATA_WRITE`; `PERMISSION`). `cloud_created_at` is the minimum known creation time. Status transitions apply only when `event_time >= lifecycle_event_time`.
4. **Incarnations.** A CREATE applied to a DELETED row starts a new incarnation. Status becomes ACTIVE, `recreated_at` and `cloud_created_at` take the CREATE's event time, and the activity maxima, size and retention evaluation are cleared. Evidence older than `recreated_at` never changes the row again, so a reused bucket name cannot inherit the old bucket's idleness.
5. **Provenance.** `cloud_created_source`, `last_access_source` and `discovery_source` hold an evidence channel, written `<CHANNEL>[:<SOURCE_KIND>]`. The channels are CRAWLER, AUDIT_LOG, INVENTORY, PROVIDER_METADATA, USAGE_QUERY and MANUAL. These are open strings with no CHECK, like the ADR-0045 source kinds.
6. **Size.** `size_bytes` (BIGINT, >= 0) holds the latest observation by `size_observed_at`. Null is unknown; 0 is a real size.
7. **Retention rules are kept at their source.** A rule is either a period in days counted from a basis (enum `RetentionBasis`: CREATED, LAST_MODIFIED, LAST_ACCESSED) or, with basis FIXED_DATE, an expiry instant. Expiry = basis time + the rule's days; a FIXED_DATE rule carries its expiry itself. Each rule stays where it comes from, and the evaluator reads it there on every run. POLICY rules are `setRetention` actions, which CAT-03's listener records in a catalog-owned retention rule store (a table in da-catalog's own schema) keyed by asset and policy id. LABEL_DEFAULT rules are configuration. PROVIDER rules are provider settings a crawler recorded in `technical_metadata`: a GCS bucket retention period is CREATED plus days, and a BigQuery table expiration time is FIXED_DATE. MANUAL rules are rows in the same rule store, written through the API by a named role and audited.
8. **Evaluation by bounds.** For each rule the evaluator (CAT-03, with an injected Clock) bounds the true expiry from the evidence: `lo <= expiry <= hi`. DUE needs `hi <= asOf`. RETAINED needs `lo > asOf`. Anything else is UNKNOWN. Upper bounds come from exact provider state, from `discovered_at` for creation (the asset existed when DLAP first saw it), and, for last access, only from read coverage captured since some instant S (binding `READ_COVERAGE_CAPTURED`; COV-01 adds it per asset). Logged reads and writes give lower bounds only. With several rules, the asset is DUE only if every rule is DUE, and RETAINED if any rule is. This is the precise meaning of "unknown is never stale or fresh".
9. **The retention columns on the asset are evaluation output.** `retention_days`, `retention_basis`, `retention_source` and `retention_policy_id` name the deciding rule of the last evaluation and are rewritten on every run (cleared for NO_POLICY). Nothing writes a rule into them, and the evaluator never reads them back. `retention_status` (enum `RetentionStatus`: UNKNOWN, NO_POLICY, RETAINED, DUE, ON_HOLD) is NOT NULL DEFAULT 'UNKNOWN'. `retention_expires_at` is the expiry the last evaluation found: exact (for FIXED_DATE, the rule's own date copied from its source) or the proving bound. It is null for UNKNOWN and NO_POLICY, and under ON_HOLD it holds what the rules alone give, for information. `retention_evaluated_at` is the evaluator's as-of instant, the only non-business time. Because no rule input sits on the asset row, an evaluation, a hold or a release cannot overwrite or lose a rule.
10. **Legal hold.** `legal_hold` is NOT NULL DEFAULT false, the one input among the retention columns, and only an audited action by a named role sets or releases it. A CHECK (`NOT legal_hold OR retention_status = 'ON_HOLD'`) means no reader can see a held asset as DUE. `Asset.placeLegalHold()` sets the hold and the status and keeps the last result for information. `Asset.releaseLegalHold()` clears the hold, returns the status to UNKNOWN and clears `retention_expires_at` and `retention_evaluated_at`, so the asset needs a fresh evaluation before any retire; it keeps every evidence field. Writers change the hold only through these two methods. Provider-native holds stay in `technical_metadata`; the provider enforces them and executors fail closed on them. The retire gate is `Asset.retentionPermitsRetire()` (DUE and not held), checked again on a fresh read before acting.
11. **Retention-due topic.** `dlap.catalog.asset.retention-due`, keyed by `asset_uri`, JSON, one message per status change with a deterministic `eventId`. The payload is specified in `docs/specs/asset-descriptor.md`.
12. **Storage.** The CHECK constraints widen to the frozen wave-1 lists: providers DATABRICKS and SNOWFLAKE; kinds S3_OBJECT, GLUE_DATABASE, GLUE_TABLE, GCS_OBJECT, BLOB_CONTAINER, BLOB_OBJECT, UNITY_VOLUME and SNOWFLAKE_TABLE. The report fields get sort and filter indexes. `02-create-common-schema.sql` and `upgrades/2026-10-18_asset_time_size_retention.sql` carry one identical, idempotent block, whose column comments mark the retention rule and result columns "Output only". There is no backfill: `dlap_usage_metrics.lastAccessedAt` holds processing time and is not copied. The key stays until CAT-02 moves its readers. The retention rule store is not in `dlap_common`.

**Consequences**:
- The expiry report (RPT-01), the retention sweep (CAT-03), the PDP snapshot (POL-01) and the retire executors read one set of indexed columns with one meaning.
- Until COV-01 lands, every LAST_ACCESSED rule evaluates UNKNOWN. This is intended: DLAP cannot yet prove an asset unread.
- Existing assets read UNKNOWN until CAT-02 and DISC-02 fill the times from event-time evidence or a backfill.
- CAT-03 must add the retention rule store (POLICY records and MANUAL rules) to da-catalog's own schema, plus an audited API for MANUAL rules. Until then only LABEL_DEFAULT and PROVIDER rules can be evaluated.
- Writers must set `retention_status = 'ON_HOLD'` with every hold. `Asset.placeLegalHold()` and the entity's pre-persist and pre-update hooks do this.
- A new retention status or basis needs a schema delta (CHECK). A new provenance channel or rule source does not.
- `dlap_catalog_unmatched_target_total` makes the identity gap visible instead of silent.

**Alternatives rejected**:
- One row per object everywhere: the catalog would grow with object count, and object events already roll up.
- Auto-creating assets from any activity event: this would pull other accounts' resources and public datasets into the catalog and the retire path.
- Treating `discovered_at` as an exact creation time: it can make an asset look younger than it is, which is a false RETAINED. It is used only as an upper bound.
- Nullable `retention_status` and `legal_hold`: a null would be a second spelling of UNKNOWN and of not held.
- Backfilling `last_accessed_at` from `dlap_usage_metrics`: it would put processing time into an event-time column.
- Storing rule inputs on `dlap_assets` (for example a `retention_fixed_expires_at` column, or an operator writing a MANUAL rule into the retention columns): one row holds one rule, so a run in which another rule decides would overwrite the operator's rule, and hold handling would have to tell input columns from output columns.

---

## ADR-0045 - One log-event contract and a versioned, configuration-driven log layer

**Date**: 2026-10-18
**Status**: Accepted (orchestrator decision D1; contract delivered by LOG-01)

**Context**: Logs are DLAP's differentiator, but the log path could not be trusted. Three producers put three shapes on `dlap.log.event.normalized` (`LogIngestServiceImpl.java:98-127`, `CrawlerAssetSpineBridge.java:121-162`, `LogAnalyzerSpinePublisher.java:94-117`), a dormant fourth exists (`AWSLogAnalyzer.java:295-299`, `OracleLogAnalyzer.java:296-300`), and the three active ones double-encode (a String sent through `JsonSerializer`), so every consumer compensates differently. The generated `com.dlap.proto.log.v1` classes had no main-source user and `docs/specs/log-event.md` described names and paths that no longer exist. Sources fill event time with `Instant.now()` when the provider gives none, classify operations by substring, and have no notion of format version, so a provider format change is either a silent drop or a silent misread. Nothing distinguishes "not read" from "read logging not captured".

**Decision**:

1. **One contract, additive within v1.** `dlap/log/v1/log_event.proto` is the schema of record. ADR-0045 adds fields 14 to 26 to `DlapLogEvent` (`source_format`, `source_schema_version`, `parser_version`, `binding_id`, `native_event_id`, `observed_time`, `event_category`, `origin`, `parse_status`, `related_resources`, `supersedes_event_id`, `shared_event_id`, `parse_warnings`), fields 8 to 12 to `TargetResource` (`canonical_uri`, `created_time`, `size_bytes` with explicit presence, `technical_metadata`, `relation`), `SourceProvider` UNITY, DATABRICKS, SNOWFLAKE and `ChangeType` ARCHIVE. New enums prefix their values with the enum name. A descriptor-diff unit test (`LogEventProtoContractTest`, no buf per D7) fails on any removal, renumbering, rename or retype against a committed baseline. Breaking changes go to a `dlap.log.v2` package and topic. A defect in a frozen part is fixed by an additive change and an ADR through an orchestrator-owned contract-change package that re-runs every consumer contract test.

2. **One wire encoding.** The value is a proto3 JSON object written by `DlapLogEventCodec.encode` (every non-message field printed, sorted map keys) and sent with a String or byte serializer. `DlapLogEventCodec.decode` also reads the legacy double encoding, Maps and Jackson trees, maps the legacy keys, and drops unknown fields and enum values with warnings (forward compatible). Rollout order: consumers adopt the codec first (CAT-02 catalog, DISC-02 router), then the producers switch in one step (SPINE-01). Binary protobuf is deferred.

3. **Three clocks.** `event_time` (provider occurrence) is the only clock for business state; `observed_time` (provider receipt or delivery) is for delay analysis; `ingest_time` is operational only. A source may name one documented provider fallback clock (GCP `receiveTimestamp`), flagged with a warning; with none, the record is skipped and counted. DLAP never stamps business time. Business maxima are monotonic and lifecycle transitions apply only when `event_time >= lifecycle_event_time`.

4. **Idempotency.** `event_id = lowercase hex SHA-256("<SOURCE_PROVIDER>|<native key>")`, byte-for-byte the formula `LogIngestServiceImpl.computeEventId` already uses with a non-blank `requestId`, so ids stored by the legacy pipeline and the wave-1 hotfixes survive the registry cutover. This deviates from the design draft's `source_kind|native_key`: the primary audit stream of each provider uses its bare native id (CloudTrail `eventID`; GCP `logName|timestamp|insertId` with the full log name and `Instant#toString` rendering), and every other source kind namespaces its key as `<SOURCE_KIND>|...`, which gives the same separation. Fan-out events use `native_key#canonical_uri`. Usage counters dedupe on `shared_event_id` plus the target when CloudTrail `sharedEventID` is present, otherwise on `event_id`. Dedup layers: `log_events` primary key with no republish of a known id except `dlap-ingest-mode=REPLAY`; a per-consumer processed-event table in the state-change transaction.

5. **Category is separate from routing.** `change_type` keeps routing onto the typed lifecycle topics; `event_category` (MANAGEMENT, DATA_READ, DATA_WRITE, PERMISSION, DATA_READ_OBSERVED) decides which business field an event may move. Only SUCCESS events move business fields. Legacy events derive a category from `change_type` and reports mark it heuristic. Unknown is never stale and never fresh.

6. **rawEvent conventions.** GCP: the LogEntry as REST-style JSON with `protoPayload` being the `AuditLog` printed through `JsonFormat` plus `@type`, and `serviceData` (and any unresolvable `status.details` entry) as `{typeUrl, base64}`. CloudTrail: the original record JSON, identical across envelopes. `raw_event` is a `Struct`, so parsers take exact numbers from the source before conversion.

7. **Every record has one outcome.** Parsed, parsed with warnings, skipped, or quarantined. Quarantined records go to `dlap.log.event.quarantine` as `LogQuarantineRecord` (deterministic `quarantine_id`, bounded and redacted excerpt, raw SHA-256, never raw values in `reason_detail`), optionally with a `PARSE_STATUS_PARTIAL` pass-through event that never changes state. Per-binding health goes to `dlap.logingest.binding.status` as `LogBindingStatus`, including `ReadCoverage`: an asset may be called "not read since D" only when its binding is `READ_COVERAGE_CAPTURED` with `read_coverage_since <= D`. `dlap.catalog.asset.retention-due` is reserved for retention transitions (ADR-0046).

8. **Versioned, configuration-driven parsing (LOG-02a/b/c).** One parse path: a registry keyed by (source kind, envelope, schema version), with the version detected per record and checked against the binding's pin or `auto` and an `on-mismatch` policy (unknown major quarantined, newer minor parsed with a warning). Source kinds and envelopes are open strings, not Java enums, and parsers, version detectors and transports self-register as Spring beans, so adding a family adds classes and a mapping YAML, not edits to a central switch. Per-family operation classification and field paths live in declarative YAML. The bindings file is authoritative and Postgres mirrors it with a config hash. The legacy sources stay behind `dlap.logingest.pipeline=legacy` until the registry passes the contract tests. No dependencies are pre-staged for later waves: DEPS-01 owns the logingest dependency set with a convergence check (DEPS-01 is defined in the private wave plan, decision D1 override, not in the work-package list).

9. **Placement and transport.** `parse/**` is JSON-only cloud-neutral code; SDK transports live under `providers/<cloud>/` (CLAUDE.md section 5). ADR-0023 is reaffirmed: no Pub/Sub, Event Hubs or S3 notification transport in 0.x; transports pull (APIs, sink objects, system tables) with event-time cursors and overlap windows.

**Consequences**:
- Every service reads and writes the spine through one class in da-protos; a producer contract test is `decodeStrict(encode(...))`.
- The legacy reader paths (double encoding, legacy keys, provider principal-type aliases) stay in the codec until SPINE-01 ships and every producer is on the single encoding; their use is countable through `DecodedLogEvent.isLegacy()`.
- The v1 contract can only grow. Field numbers 1 to 26 of `DlapLogEvent` and 1 to 12 of `TargetResource` are permanent.
- Reports can tell "not read" from "not captured", which is the honest answer the product sells.
- Cost: proto3 JSON prints default values, so messages are larger than a sparse encoding; `raw_event` numbers are doubles.
- Follow-ups: GCP-00 and CT-00 implement the native keys and rawEvent conventions on the legacy pipeline; LOG-02a/b/c build the registry; CAT-02 and DISC-02 adopt the codec; SPINE-01 switches producers; LOG-03 publishes quarantine and binding status; CAT-03 consumes binding status and emits retention-due.

---

## ADR-0044 — Shared LLM client module + draft-only natural-language policy authoring

**Date**: 2026-09-16
**Status**: Accepted

> Numbered 0044 by assignment; ADR-0040–0043 are in flight on parallel branches.

**Context**: Authoring a `dlap/v1` policy by hand is the slowest step in adopting the policy
engine, and the visual builder still needs the author to know the grammar. Letting an LLM
propose the document removes that barrier — but the same capability could just as easily
propose a `delete` nobody reviewed. Two blockers stood in the way. First, the `ILLMClient` →
`LLMClientFactory` abstraction lived only in `da-autolabel`
(`com.dlap.autolabel.llm.client`), so any second consumer would have had to duplicate it,
which CLAUDE.md §6 forbids. Second, the abstraction was label-shaped: every method took
asset content and returned label suggestions, with no free-form completion seam.

**Decision**: Two moves.

1. **Extract `da-llm-client`** as a plain library module (like `da-protos`; no `bootJar`).
   `ILLMClient`, `LLMClientFactory`, the OpenAI/Ollama/Mock provider clients, their
   `MLConfigRequest` config binding and the `LabelSuggestion`/`LLMResponse` models moved
   there under `com.dlap.llm.client` via `git mv`, with the OpenAI and httpclient5 SDK
   dependencies. No new external dependency. Beans are registered by an `@AutoConfiguration`
   with explicit `@Bean` methods rather than component scan — the classes now sit outside
   every service's `@SpringBootApplication` base package, and explicit beans pin the names
   callers resolve by string (`openAiLLMClient`, `ollamaLLMClient`, `mockLLMClient`).
   `ILLMClient` gains `complete(prompt, config)`, the provider-neutral free-form seam;
   `MockLLMClient` refuses it by design. A new `LlmProperties` binds the `llm.*` keys that
   already sat unbound in `da-autolabel/src/main/resources/application.properties` — key
   names unchanged, `llm.provider` added.

2. **`POST /api/v1/policyengine/policies/draft-from-text` drafts and stops.** It persists
   nothing and publishes nothing; the draft goes to the visual builder for a human to
   confirm through the existing `createPolicy`. The prompt's grammar is derived from the
   evaluator's clause switch and `docs/specs/policy-dsl.md` v1.1, and anything outside it is
   HTTP 422 with `warnings` and no DSL — the same philosophy as `PolicyDslTranslator`, which
   errors on an absent action rather than inventing one. Drafted `delete` and `archive`
   always come back with `dryRun: true` and `requireApproval: true`, and every draft carries
   `metadata.enabled: false`. With no provider configured the caller gets HTTP 503 naming
   the property to set; `MOCK` counts as unconfigured. API keys are never logged.

**Consequences**:
- One implementation of provider selection for the whole platform. `da-autolabel` keeps
  resolving its `MLConfigRequest` from the database and is otherwise unchanged; its
  `dlap.autolabel.allow-mock` gate is untouched.
- `MLConfigRequest` changed package but not class name, so its JSON wire shape, OpenAPI
  schema name and the JSON already persisted in `ml_global_config` are all unaffected.
- The LLM is a drafting aid, never an actor: no code path exists from a completion to a
  stored policy or a published action event. A model that hallucinates costs a human one
  rejected draft.
- Adding a provider (Gemini in 0.2.0 per ADR-0009) now means one class in `da-llm-client`
  plus one `@Bean`, and every consumer gets it.
- Cost and rate limits are the operator's to manage; the endpoint is gated to
  `ROLE_ADMIN` / `ROLE_POLICY_MANAGER` and does one completion per call.

---

## ADR-0043 — Cloud scanners whose SDK is unavailable depend on a thin job-list SPI, not the raw SDK

**Date**: 2026-07-06
**Status**: Accepted

**Context**: The GCP (C7) and AWS (V4-AWS) job scanners call the real cloud SDK
directly (their SDKs are on the classpath / gradle cache). Azure (Data Factory /
Synapse) and Databricks (Jobs / SQL) job scanners are needed for multi-cloud
completeness, but their SDKs are NOT in the sandbox gradle cache and cannot be
downloaded reliably — a hard verification blocker if the crawler imports raw SDK
types.

**Decision**: A scanner whose cloud SDK is unavailable depends on a **thin
job-list SPI** — a Java interface returning plain DTOs (no cloud-SDK types) —
with a default `@ConditionalOnMissingBean` **Stub** implementation (offline/dev)
and a documented, deferred slot for the real SDK-backed `@Component`. This
mirrors da-mcp-pep's `PdpClient` abstraction: `AzureJobListClient` /
`DatabricksJobListClient` + `Stub*` defaults. The crawler, resource model,
serviceType derivation, and tests are all real and verifiable with ZERO new
dependencies; the real SDK impl drops in later without touching the crawler.

**Consequences**: Azure/Databricks scanners compile + unit-test in the sandbox
now (architecture correct, testable), and cloud-neutrality improves (the crawler
never sees a provider SDK type — the SPI is the boundary). The cost: a real
deployment must supply the SDK-backed `*JobListClient` bean (a documented
per-deployment step). Applies going forward to any provider whose SDK is
unavailable at build time.

---

## ADR-0042 — Reference MCP PEP is a separate `da-mcp-pep` module: thin AuthZEN client + local decision cache, not coupled to the PDP

**Date**: 2026-07-06
**Status**: Accepted

**Context**: ADR-0040 named a reference MCP Policy Enforcement Point as a
v0.4.0 item ("a thin MCP middleware evaluating a locally-cached compiled
bundle"). The PDP (da-policyengine, C1) is the pull-based decision authority;
a PEP is a distinct plane that sits in front of an MCP server, intercepts each
tool-call/resource-read, asks the PDP for a verdict, and enforces it. Coupling
PEP code into da-policyengine would blur the plane separation the whole series
argues for.

**Decision**: The reference PEP is a **new Gradle module `da-mcp-pep`**, a
separate deployable. Its core is framework-light and pure so it unit-tests with
no DB/Kafka/PDP:
- `McpAccessRequest` (agent principal, tool, resourceUri, action, context) →
  `AuthZenRequestMapper` → the §2 AuthZEN evaluation request.
- `PdpClient` SPI; `HttpPdpClient` (Spring `RestClient` to
  `/access/v1/evaluation`, bearer service-account token) is the default; tests
  use a mock.
- `DecisionCache`: a local TTL cache (`dlap.mcppep.cache.ttl-seconds`) keyed on
  (principal, action, resource) — the "locally-cached compiled bundle" that
  keeps the hot path off a network round-trip; fail-closed on miss+PDP-error.
- `McpPolicyEnforcer`: map → cache-or-call → verdict; on ALLOW passes through
  and applies obligations (mask/redact_rows advertised to the caller); on DENY
  blocks; on PDP failure fails **closed** (deny) unless `observe` mode.
- A thin `McpPepController` shows the reference HTTP wiring.
The Spring app excludes DataSource/JPA/Flyway/Kafka autoconfig (a PEP is a
client, not a stateful service). Full MCP stdio/SSE transport is deferred; the
enforcement core + HTTP reference is the v0.4.0 slice.

**Consequences**: new module in settings.gradle.kts; inherits the root
subproject config (unused JPA/Kafka deps are inert, excluded at autoconfig).
The PEP depends only on the published AuthZEN contract (docs/specs/
enforcement-contract.md) — swappable for any AuthZEN PDP. Production hardening
(mTLS to PDP, token caching/refresh, real MCP transport) is documented, not
built.

---

## ADR-0041 — v0.4.0 scope: reference MCP PEP, multi-cloud job scanners, C8 frontend wiring, Docker-gated e2e

**Date**: 2026-07-06
**Status**: Accepted

**Context**: v0.3.0 completed the Consumed stage (C1–C5, C7; ADR-0040). Remaining
threads: the reference PEP (C6), the other-cloud job scanners that the GCP
BigQuery-job scanner (C7) established the pattern for, the C8 frontend call-site
wiring left after the util files landed, and the integration tests that need
Docker (not available in the build sandbox).

**Decision**: v0.4.0 =
- **P1 (C6)** reference MCP PEP as `da-mcp-pep` (ADR-0042). Sandbox-buildable.
- **P2** multi-cloud job scanners: AWS (Glue/Athena/EMR jobs), Azure (Data
  Factory/Synapse), Databricks (jobs/SQL) — each mirrors the GCP
  `GcpBigQueryJobResourceCrawler` pattern, gated by its provider profile,
  offline-stubbed like N5's RDS/Glue. Sandbox-buildable.
- **P3** C8 frontend: wire the shared `safeDate`/`useAppNavigate` utils into the
  audited call sites; incremental `window.navigateToX` → router migration.
  Needs owner-side `tsc`.
- **P4** Docker-gated e2e: the drift-join Kafka/DB integration test, the AuthZEN
  live curl, and the per-cloud lifecycle harnesses — owner-side (Docker).
- **P5** opportunistic: obligation *enforcement* helpers (a reference masking
  applier the PEP and gateways can reuse), and OpenTelemetry spans on the PDP
  hot path (carried from v0.2.0 N6).

Priority order for autonomous execution: C6 core → AWS job scanner → (budget
permitting) Azure/Databricks scanners → obligation masking helper. Frontend
wiring and Docker e2e are owner-gated.

**Consequences**: v0.4.0 deepens the Consumed stage into a runnable enforcement
reference and widens the job/lineage bestiary across clouds — both directly on
the north-star path. Each item is independently shippable and sandbox-verifiable
except the owner-gated P3/P4.

---

## ADR-0040 — Consumed stage: DLAP is an AuthZEN PDP over a dlap/v1 `AccessPolicy` kind; enforcement is pluggable, DLAP builds no gateway

**Date**: 2026-07-05
**Status**: Accepted

**Context**: The five-stage vision (Born → Enriched → Governed → Consumed →
Retired) is implemented end-to-end except **Consumed** — the per-request
verdict at the moment an agent reads data. No component in the nine services
owns it. Three market facts shape the answer: (1) the MCP gateway market is
crowded and commoditizing (Composio, TrueFoundry, MintMCP, Lunar, Portkey…),
so building a gateway competes where DLAP has no edge; (2) the OpenID
**AuthZEN Authorization API 1.0** went final (Standards Track, March 2026) and
standardizes exactly the PDP↔PEP contract, with a dozen-plus interoperable
implementations and a conformance-certification profile — and Keycloak, already
in DLAP's stack, ships experimental AuthZEN support since 26.7; (3) "let
customers' existing controls consume DLAP labels" alone is a static projection
— it cannot express per-request context (purpose, on-behalf-of, consent), it
clips policy to each platform's dialect, and it yields no unified decision log.

**Decision**: DLAP ships the **decision**, not the doorway.
1. **`da-policyengine` becomes an AuthZEN 1.0 PDP**: `POST /access/v1/evaluation`
   (+ batch `/evaluations`) per the spec. Deterministic evaluation, no LLM in
   the verdict path (AI senses; compiled policy decides). No new service.
2. **Access intent is a new `kind: AccessPolicy` in the existing dlap/v1 DSL**
   (ADR-0021/ADR-0031): `spec.subjects` (realm roles / ADR-0032 tiers / agent
   principals), `spec.actions`, `spec.match` (the *same* asset-selector clause
   set lifecycle policies use — labels are the join point), optional
   `spec.condition` (request context: purpose, consent, region), `spec.effect`
   (allow/deny) + `spec.obligations` (mask/redact). Stored in `policyDocument`,
   validated by `PolicyDslParser`, translated by the ADR-0031 machinery. One
   DSL, two kinds.
3. **Combining algorithm is fixed and boring**: explicit deny overrides allow;
   most-restrictive obligations union; default decision is configuration
   (`dlap.pdp.default-decision`, ships as `deny`; `allow` documented for
   brownfield observe-mode rollouts).
4. **Every verdict emits a decision-log event** onto the Kafka spine (new
   `KafkaTopic` constant, `dlap.access.decision`) — one replayable audit trail
   regardless of which PEP enforced. Enforcement-point access logs returning
   via `da-logingest` close the drift-detection loop (verdicts vs actual reads).
5. **Enforcement is pluggable, mirroring ADR-0035's orchestrator pattern**:
   - *Pull (primary)*: customers' existing MCP gateways / OPA sidecars /
     Keycloak call the AuthZEN endpoint. DLAP builds no gateway.
   - *Push-down (supplement)*: a later `PolicyProjectionSink` SPI (sibling of
     `ComplianceLabelSink`) compiles AccessPolicies into platform-native
     controls (Unity Catalog grants first, per Q-022). Detection-grade; the
     AuthZEN path is the source of truth.
   - *Reference PEP*: deferred ≥v0.4.0; a thin MCP middleware evaluating a
     locally-cached compiled bundle. Only if the ecosystem hasn't provided one.

**Consequences**:
- The Consumed stage lands as ~1 controller + 1 evaluator extension + 1 topic,
  not a tenth service — smallest possible surface for the largest vision gap.
- AuthZEN conformance becomes a certifiable, vendor-neutral integration point —
  the "neutral middle" positioning, standardized instead of invented.
- The PDP must answer from memory: ENABLED AccessPolicies and asset labels are
  cached (policy-change events + catalog lookups), p99 target ≤10ms; the
  resource snapshot may also be PEP-provided in `resource.properties`.
- What the agent does with data *after* an allowed read stays out of scope
  (model-governance layer), consistent with the published thesis.
- Spec artifact: `docs/specs/enforcement-contract.md` (AuthZEN profile,
  obligation semantics, decision-event schema).

---


## ADR-0039 — Cross-cloud offline E2E drives the neutral normalized seam, not per-cloud parsers

**Date**: 2026-07-05
**Status**: Accepted

**Context**: We need an end-to-end test proving the data-lifecycle control plane works for
GCP, AWS and Azure without live cloud credentials. Two designs were possible: (a) build a
raw provider-format file-replay path per cloud (CloudTrail JSON, GCP Cloud Audit Log
protoPayload, Azure Activity Log) driving each cloud's discovery log-analyzer; or (b) drive
the one already-cloud-neutral seam. The spine is neutral where it counts: everything from
`ILogIngestService.ingest(IngestRequestDTO)` onward (normalize → `LOG_EVENT_NORMALIZED` →
`DiscoveryLogEventProcessor` router → da-catalog upsert) carries GCP/AWS/Azure identically;
the only provider-specific step is (raw log → `IngestRequestDTO`). Design (a) would also have
forced un-stubbing the deliberately de-fabricated Azure log analyzer (ADR-0017), i.e.
inventing a parser we cannot live-verify.

**Decision**: Drive the cross-cloud E2E through the **neutral normalized seam**. A new
`FixtureReplaySeeder` in da-logingest (`@Profile({demo,dev})`, off by default, gated by
`dlap.logingest.replay.enabled`) replays per-cloud arrays of `IngestRequestDTO`
(`e2e-tests/fixtures/replay/<cloud>-events.json`) through `ILogIngestService.ingest()`
in-process — no credentials, no auth token. A parametrized harness
(`e2e-tests/cloud-pipeline-e2e.sh <gcp|aws|azure|all>`) mounts the fixtures via a test-only
compose override and asserts the per-cloud `dlp_assets` rows. da-catalog derives
`cloud_provider` + `service_type` from each event's URI, so fixtures need only
documentation-shaped URIs. Documentation-accurate **native** fixtures (CloudTrail / Cloud
Audit Log / Activity Log) are committed alongside as the modeled inputs, not parsed by the
harness.

**Consequences**:
- One offline test proves discovery → catalog for all three clouds against the real spine
  (Postgres/Kafka/Keycloak + da-logingest/da-discovery/da-catalog). Live-verified 2026-07-05:
  GCP 5 rows (GCS_BUCKET + BIGQUERY_TABLE), AWS 3 (S3_BUCKET), AZURE 3 (BLOB_STORAGE).
- Surfaced and fixed a real catalog bug: `deriveServiceType` matched the loose `bucket`
  keyword before `s3://`, so an S3 bucket (native kind `AWS::S3::Bucket`) mis-derived as
  GCS_BUCKET. Unambiguous provider-scheme URIs now win first.
- Azure archive is completed for parity (`AzureCloudStorageService`, mock-mode off by
  default) so the archive lifecycle no longer fails closed for Azure (mirrors the CP-4 GCS
  work: fail-closed dispatch, honest-by-default mock mode).
- Raw per-cloud file-replay analyzers remain future work; the neutral seam is the honest,
  deterministic driver until then. The Azure log analyzer stays de-fabricated (ADR-0017).

---

## ADR-0038 — Enforce the LOG_EVENT_NORMALIZED contract at the producer, not the consumer

**Date**: 2026-07-04
**Status**: Accepted

**Context**: `LOG_EVENT_NORMALIZED` had two producer shapes. da-logingest and
`CrawlerAssetSpineBridge` emit the flat `DlapLogEvent` (top-level `changeType`,
`targetResource{id,kind,region}`, `sourceProvider`); the router
(`DiscoveryLogEventProcessor`) and da-catalog's listeners consume exactly that. The log
analyzers instead emitted `LogEvent{ payload: @class ResourceChange }` — `changeType` nested
under `payload`, with the fully-qualified Java class name embedded on the wire. The router
reads a *top-level* `changeType` and DLQs when absent, so analyzer discoveries never reached
the catalog. The fix could either make the consumer tolerant of both shapes, or make the
outlier producer conform.

**Decision**: Enforce the contract at the **producer**. Analyzers publish through a new
`LogAnalyzerSpinePublisher` that emits the same flat `DlapLogEvent` shape as
`CrawlerAssetSpineBridge`. The router stays a thin router (route by `changeType`; no
per-producer format branches). The `@class`-on-the-wire coupling is removed.

**Consequences**:
- One wire contract on `LOG_EVENT_NORMALIZED`; new producers target it rather than teaching
  the consumer new formats. The router's "no `changeType` → DLQ" stays a true contract-
  violation signal.
- No fully-qualified Java class names on the wire (removes cross-service classpath coupling
  and a deserialization-gadget footgun).
- Producer-distributed transforms (better scaling than a single normalizing consumer).
- GcpLogAnalyzer (the only live analyzer) is converted now; AWS/Azure/OCI analyzers emit
  nothing today and adopt the publisher when implemented.

---

## ADR-0037 — One canonical quickstart stack; production is documented, not a second compose

**Date**: 2026-07-04
**Status**: Accepted

**Context**: Deployment shipped two parallel Docker Compose stacks —
`environments/dev/` (infra `docker-compose.yml` + `docker-compose.microservices.yml`)
and `environments/prod/docker-compose.yml` (a 608-line monolith). They drifted, and the
prod stack had **never booted**: its services connect to unprefixed DB names
(`/discovery`, `/catalog`, `/adminservice`) that the init script never creates (it makes
`da_discovery`, `da_catalog`, `da_admin`, …); its Keycloak points at a `keycloak` Postgres
DB that is never created; and it imports the dev seed realm (Password123! users, implicit
flow, wildcard `webOrigins`) verbatim into production. `environments/prod/start-prod.sh`
calls a `start-microservices.sh` that does not exist, and its `env.prod.example` is a
JHipster/`dalab`-brand relic. The dev stack, by contrast, boots with correct `da_*` names
and includes all ten services (incl. `da-logingest`).

Maintaining an environment-specific "prod" compose in-tree is also not the norm for the
projects DLAP is modeled on. Apache Hadoop ships documented `*-default.xml` + overridable
`*-site.xml` templates (no dev/prod duplication); Kafka ships one sample
`config/server.properties` and configures the image purely via env vars; Airflow ships a
*single* `docker-compose.yaml` explicitly labeled "quick-start, not for production" plus a
Helm chart and a Production Deployment doc for real installs. The pattern is: one runnable
quickstart, fully externalized config, and production as **documentation** (and/or Helm),
never a second hand-maintained stack that silently rots.

**Decision**:
- The `environments/dev/` compose pair is the **single canonical quickstart** — for local
  dev, evaluation, and demos. It boots clean from a fresh clone with safe dev defaults and
  seeded demo data (this is expected and correct for a quickstart, per the Airflow model).
- **Delete** `environments/prod/docker-compose.yml`, `environments/prod/start-prod.sh`, and
  the stale `env.prod.example` relics. Production is not a compose file we maintain.
- The DB init script (`00-create-multiple-databases.sh`) **honors** its
  `POSTGRES_MULTIPLE_DATABASES` list (previously read only for non-emptiness, then a
  hardcoded set was created — the drift trap), and optionally provisions a Keycloak DB
  when `KEYCLOAK_DB_NAME` is set (the production KC-on-Postgres path).
- Configuration is externalized through the single repo-root `.env.example`; secrets carry
  safe dev defaults for the quickstart and are documented as **must-change / fail-closed**
  for production in `deployment/PRODUCTION.md`.
- `deployment/PRODUCTION.md` is the authoritative production path: real secrets, external/HA
  Postgres, Keycloak-on-Postgres with a **hardened realm** (no seeded users, `sslRequired=all`,
  real client secrets, implicit flow off), TLS termination, `ddl-auto=validate`, resource
  limits, and a Kubernetes/Helm pointer.

**Consequences**:
- The P0 "prod compose won't boot / ships dev secrets" blockers dissolve: there is one stack,
  and it is the one that works. Production hardening is an explicit, reviewable checklist
  rather than a stale duplicate that reads as "supported."
- One less stack to keep in sync; the init database set has a single source of truth.
- A future Helm chart (post-launch) is the natural home for a real production topology; until
  then `PRODUCTION.md` carries it.
- The seed realm is now unambiguously a dev/quickstart artifact; production operators build a
  hardened realm per the guide. (Ships alongside the JWT issuer/audience hardening tracked in
  Q-014.)

---

## ADR-0036 — Pluggable CredentialProvider SPI for cloud-connection secrets

**Date**: 2026-07-04
**Status**: Accepted (addresses Q-019)

**Context**: Cloud-connection secrets were held only one way — an encrypted blob in DLAP's
own DB via `IEncryptionService` (since hardened to AES-256/GCM; a legacy ECB path remains
decrypt-only for lazy upgrade). A single static `DLAP_ENCRYPTION_KEY` guarding long-lived
provider secrets is materially weaker than a secret manager or federated identity (Q-019),
and there was no seam for a deployment to plug in its own.

**Decision**: Define a `CredentialProvider` SPI (`da-admin-service`) that resolves a
connection's credentials at use-time, selected by `dlap.credentials.provider`:
- **`encrypted-store`** (default) — the existing AES-256/GCM DB store; self-contained for dev.
- **`secret-manager`** — DLAP stores only a `secretRef`; a deployment-supplied
  `SecretResolver` bean (Vault / AWS/GCP/Azure managers) fetches the secret. DLAP never
  persists or logs it.
- **`federated`** (recommended for prod) — no stored secret; the caller uses the runtime's
  ambient identity (STS AssumeRole / Workload Identity / Azure federated).

`Request` carries non-secret `connectionParameters` + the encrypted blob; `Resolved` is
either concrete secret material or a federated marker. Provider-specific resolvers are
deployment beans, never bundled in `core`/`common` (§5/§9). Guide:
`docs/guides/credential-store.md`.

**Consequences**:
- Users plug in their secret manager or federated identity without forking DLAP; the
  contract ("what DLAP needs") is explicit and one property switches modes.
- The weak static-key store is now the *dev default*, not the only option. Q-019's "prefer
  federated / external manager, retire ECB" is met: GCM is the write path, and the SPI makes
  federated/secret-manager first-class. Remaining increment: migrate the existing callers
  (connection-test, discovery scan) to resolve through the SPI, and drop the legacy ECB
  decrypt once backfill is confirmed.

---

## ADR-0035 — Compliance via labels: ingest from the customer's tool, don't build native evaluators

**Date**: 2026-07-04
**Status**: Accepted (supersedes the "build per-framework evaluators" direction in Q-017)

**Context**: `da-autocompliance` evaluates controls by rolling up pre-existing report
findings (`checkId == controlId`); there are no per-framework evaluators (Q-017). Building
native GDPR / HIPAA / SOC 2 / PCI-DSS evaluators is very large (dozens of controls each,
inspecting security config DLAP does not harvest) and reinvents a mature category (AWS
Config, GCP SCC, Wiz, Prisma, OneTrust, ServiceNow GRC) it would always trail.

**Decision**: DLAP is the data-lifecycle **orchestrator**, not a compliance scanner. It
**ingests compliance posture from the customer's existing tool and projects it onto asset
labels**; the Policy DSL enforces lifecycle actions on those labels. Two cloud-neutral SPIs
(mirroring `ILLMClient` / `ExternalCatalogIntegration`):
- **`ComplianceSignalProvider`** (source) — pull `ComplianceSignal(assetUri, framework,
  controlId, status, detail)` from the external tool. Default `NoopComplianceSignalProvider`.
- **`ComplianceLabelSink`** (sink) — write the label onto the catalog asset. Default
  `LoggingComplianceLabelSink` (observe before wiring a write path); production wires the
  catalog label API (allow-listed for the M2M service account, ADR-0034).

`ComplianceLabelMapper` projects a signal onto the canonical, policy-matchable label
`compliance:<framework>:<status>` (spec: `docs/specs/compliance-labels.md`).
`ComplianceSignalSyncService` composes source → map → sink, gated off by default
(`dlap.compliance.signal-sync.enabled`). The keystone on the enforcement side —
`tagMatches` (cloud-native tag map-equality) — is now implemented in the DSL evaluator
(it was a fail-closed stub); the `labels` clause already matched DLAP labels.

**Consequences**:
- New frameworks/tools plug in without touching the aggregation or the policy engine.
- Zero-config default is unchanged (findings roll-up, informational). Labels turn compliance
  posture into enforceable lifecycle policy.
- Q-017 is reframed from "build 4 frameworks" (huge, wrong category) to "integrate + enforce
  via labels" (moderate). A real external adapter + catalog-writing sink are the remaining
  per-deployment increments.

**Reconciliation (2026-07-04) — DLAP is the authoritative label store.** Labels are written
authoritatively by (1) auto-label (rules + LLM on business/technical metadata) and (2) manual
UI/API. Compliance fits that model as *another writer into the same store*, with two flows:
- **DLAP-native (primary)** — compliance controls are expressed as *control-policies* over
  DLAP's own labels + asset metadata (region, encryption, last-access): a policy stamps a
  `compliance:<framework>:<status>` label via an `addLabel` action and/or drives a lifecycle
  action. This uses the policy engine + the existing `addLabel` pipeline; examples ship in
  `docs/examples/compliance-policies/`.
- **External (supplement)** — `ComplianceSignalProvider` ingests an existing CSPM/GRC tool's
  findings and writes them *through the same authoritative pipeline*, so DLAP's catalog stays
  the source of truth (the tool is an input signal, like a crawler is for assets).

Accordingly the default `ComplianceLabelSink` is now `CatalogEventComplianceLabelSink`, which
resolves `assetUri → assetId` (catalog `GET /assets/resolve`) and publishes an `addLabel`
policy-action event onto `POLICY_ACTION_EVENT` — the same topic da-autolabel already consumes.
`LoggingComplianceLabelSink` remains an opt-in observe-only alternative.

---

## ADR-0034 — Least-privilege ROLE_SERVICE_ACCOUNT for M2M; the service account is no longer an admin

**Date**: 2026-07-04
**Status**: Accepted

**Context**: Inter-service (M2M) calls authenticate with a Keycloak client-credentials token
for the `dlap-backend` client (`ServiceTokenProvider`, attached to every Feign call by the
shared `ServiceAuthAutoConfiguration` RequestInterceptor). That service account was granted
`ROLE_ADMIN` — the highest privilege — in the realm import itself, purely so its calls would
satisfy the various `@PreAuthorize` clauses. A leaked or misused service token could therefore
do anything an administrator can (user management, destructive admin ops), which violates least
privilege for an Apache-incubation-bound platform.

**Decision**: Introduce a dedicated machine principal **`ROLE_SERVICE_ACCOUNT`** and grant it
by **explicit per-endpoint allow-list** (chosen over a composite-of-human-roles or a broad
service tier):
1. `ROLE_SERVICE_ACCOUNT` is a realm role in every import; `service-account-dlap-backend` is
   remapped from `ROLE_ADMIN` to `ROLE_SERVICE_ACCOUNT`. `ROLE_ADMIN` still exists for humans.
   It is added to `DlapConstants.UserRole` but documented as a machine principal **outside** the
   4-tier human Access Model (ADR-0032).
2. It is added to the `@PreAuthorize` of **exactly** the 25 endpoints reached service-to-service —
   the complete `@FeignClient` surface, which is authoritative because the service-auth token is
   attached *only* via the Feign interceptor: da-catalog asset reads/counts/governance + label
   add/remove (13), da-policyengine policy counts (6), da-discovery job reads (2), and the
   archival/deletion/labeling/compliance task-list reads (1 each). The SA is denied everywhere it
   is not explicitly opened.
3. Cutover is downtime-free: endpoints accept **both** `ROLE_ADMIN` and `ROLE_SERVICE_ACCOUNT`
   during the window, so cached admin tokens keep working until they refresh to the service role.

**Consequences**:
- A leaked service token can now reach only the allow-listed M2M endpoints, not the admin surface.
- Verified live: the SA token carries only `ROLE_SERVICE_ACCOUNT`; allow-listed endpoints return
  200; non-allow-listed (business-metadata, lineage, usage) return 403.
- Cost/upkeep: a *new* M2M call must add `ROLE_SERVICE_ACCOUNT` to its target endpoint (explicit
  exposure is the intent). The alternative composite-role model (Keycloak-only, no endpoint edits)
  was rejected in favor of this tighter, auditable model.
- `POST /assets` retains a pre-existing `@systemSecurityService.isSystemUser` bypass (orthogonal
  to this change); it is not part of the allow-list.

---

## ADR-0033 — Crawler discoveries reach the catalog via the log-event spine (synthetic DlapLogEvent)

**Date**: 2026-07-04
**Status**: Accepted

**Context**: Two discovery paths existed but never joined. Resource crawlers publish
`ResourceEvent`s to `discovery-resource-events-dev`, which `CatalogResourceEventProcessor`
persists into da-discovery's **internal** `cloud_resource` inventory. Separately, the
shared `dlp_assets` catalog is written **only** by the log-event spine
(`DiscoveryLogEventProcessor` consumes `LOG_EVENT_NORMALIZED`, routes to typed lifecycle
topics, da-catalog upserts). Nothing bridged the two, so a crawler-only provider — Unity
Catalog, which has no cloud audit-log stream — was discovered yet never became a catalog
asset. ADR-0021 anticipated "crawlers that emit synthetic `DlapLogEvent`s (`source=crawler`)"
but no component did. Three latent defects compounded it, none provider-specific:
- `getSupportedResourceTypes()` is a `@PostConstruct`-populated field; on a CGLIB-proxied
  crawler it returns `null`, and `getCrawlersForTypes` filtered on it → NPE/empty
  (crawl FAILED before listing a table).
- The shared manual-ack consumer factory deserializes to `Object` with type headers off,
  so a `ResourceEvent`-typed listener could not convert the `LinkedHashMap` — every crawler
  event had been dead-lettering for the life of the project (`cloud_resource` was empty;
  the resource-events `.DLT` held every event ever published).
- The da-discovery producer set `retries`+`acks=all` but not `enable.idempotence`
  (CLAUDE.md §11).

**Decision**:
1. Add **`CrawlerAssetSpineBridge`** (`IEventSubscriber<ResourceEvent>`, priority 20, after
   the inventory processor at 10). On CREATE/UPDATE/DELETE it republishes a minimal
   synthetic normalized event onto `LOG_EVENT_NORMALIZED`. The existing router fans it to
   the typed topics and da-catalog upserts the asset; because that is the same topic the
   lineage/metadata harvester consumes, **discovery now feeds lineage automatically**. This
   is provider-general — it closes the crawler→catalog gap for GCP/AWS/Azure/OCI/Unity alike.
2. **Serialization convention** on `LOG_EVENT_NORMALIZED` is a pre-serialized JSON **String**
   (as `da-logingest.LogEventPublisher` does), sent through the `<String,Object>` JsonSerializer
   template → double-encoded value. The router consumes it as `@Payload String`; da-catalog's
   enrichment listener unwraps the textual node. Typed topics keep the `JsonNode`-object form.
   This asymmetry is load-bearing; a raw `JsonNode` to `LOG_EVENT_NORMALIZED` dead-letters at
   the router.
3. Fix the three enabling defects: resolve `getCrawlersForTypes` from the authoritative
   `crawlersByResourceType` map (never the proxy field); add a dedicated
   `RESOURCE_EVENT_FACTORY` that fixes the value type to `ResourceEvent` (polymorphic payload
   still resolves via its own `@class`); enable producer idempotence.
4. da-catalog recognises `UNITY` provider + `unity_table` kind; `dlp_assets` CHECK admits
   `UNITY` + `UNITY_{CATALOG,SCHEMA,TABLE}` (init script + `upgrades/2026-07-03_…`);
   `DlapConstants` gains the constants. Bridge gated by `dlap.discovery.catalog-bridge-enabled`
   (default on).

**Consequences**:
- Crawled resources now populate `dlp_assets`, not just the internal inventory. Idempotent
  upserts (by asset URI) + producer idempotence make re-discovery and retries benign.
- Verified live: a Unity Catalog crawl lands all 4 sample tables as `UNITY/UNITY_TABLE` with
  zero happy-path dead-letters.
- Completes UC-5's last hop. The pre-existing resource-event `.DLT` backlog (≈40) is stale
  and left in place (already-failed records; sandbox).
- Follow-up: crawler discovery events carry no principal, so metadata harvest adds little for
  them today; richer harvest for crawler-origin assets is future work.

---

## ADR-0032 — DLAP Access Model: a normative 4-tier persona model over 10 Keycloak roles

**Date**: 2026-07-03
**Status**: Accepted (resolves Q-024)

**Context**: Authorization had drifted across three vocabularies: `DlapConstants.UserRole`
(8 roles, missing DIRECTOR/DATA_SCIENTIST, inventing a phantom VP), the Keycloak realm
(the correct 10 `ROLE_*` roles), and ~125 `@PreAuthorize` annotations referencing five
roles that exist in NO realm — `ROLE_USER` (×87), `ROLE_DATA_STEWARD` (×78),
`ROLE_COMPLIANCE_OFFICER` (×16), `ROLE_ANALYST` (×2), `ROLE_SYSTEM` (×1) — plus unprefixed
`hasAnyRole('USER'/'DATA_STEWARD')` variants. These phantom clauses are dead branches
(they match nobody), so endpoints work today only because `ROLE_ADMIN` is also listed;
the model is nonetheless unenforced and misleading.

**Decision**:
1. **Keycloak is the single source of truth** for role names. The canonical set is the
   **10 roles** already in the realm, organized into a normative **4-tier Access Model**:
   - **Executive** — `ADMIN`, `CDO`
   - **Leadership** — `DIRECTOR`, `POLICY_MANAGER`
   - **Practitioner** — `DATA_ENGINEER`, `DATA_SCIENTIST`
   - **Reader** — `DATA_ANALYST`, `AUDITOR`, `VIEWER`
2. `DlapConstants.UserRole` is reconciled to exactly these 10 (add DIRECTOR +
   DATA_SCIENTIST, remove phantom VP) and gains a `RoleTier` grouping. `VP` is superseded
   by `DIRECTOR` (Leadership).
3. **Phantom `@PreAuthorize` roles are swept to real ones** by a fixed mapping (replacing a
   dead branch only ever widens access to the intended persona; `ROLE_ADMIN` stays present
   everywhere, so no existing login is narrowed):
   - `ROLE_USER` → Practitioner + `DATA_ANALYST` (`DATA_ENGINEER, DATA_SCIENTIST, DATA_ANALYST`) — "any working user".
   - `ROLE_DATA_STEWARD` → Leadership (`POLICY_MANAGER, DIRECTOR`) — governance/stewardship.
   - `ROLE_COMPLIANCE_OFFICER` → `AUDITOR`.
   - `ROLE_ANALYST` → `DATA_ANALYST`.
   - `ROLE_SYSTEM` → `ADMIN` (service-to-service).
   - Unprefixed `hasAnyRole('X')` forms are normalized to `hasAnyAuthority('ROLE_X')`.
4. **CDO and Director get READ-ONLY User Management** (list users/roles); create/update/
   delete/assign remain `ADMIN`-only.
5. The persona → role → permission **matrix is normative** and published in the
   Open-Standard spec set at `docs/specs/access-model.md` (beside the DSL spec).

**Consequences**:
- The sweep is mechanical + safe-by-construction, but every service must recompile and
  representative logins (admin/cdo/director/dataengineer/datascientist) must be verified
  against representative endpoints — the acceptance gate for the change.
- The `new-ui` dev Keycloak realm (lowercase, incomplete role set) is aligned to the
  production realm as a follow-up so cross-realm login is consistent.
- No data migration: roles are Keycloak-side; existing tokens already carry the 10 roles.

---

## ADR-0031 — Unify the policy model on the `dlap/v1` DSL; the visual builder authors it, the backend owns the translation

**Date**: 2026-07-03
**Status**: Accepted (resolves the "policies are inert / create fails / rules too simple" gaps in the 2026-07-03 checkpoint)

**Context**: Three unaligned policy representations coexisted, and no path
connected them, so no policy authored through the UI ever evaluated:
1. **Visual builder** (`PolicyWizardFlow`) — collects structured conditions +
   action toggles, then flattens them to a human `WHEN … THEN …` string sent as
   `rules` (a field the draft DTO doesn't even model) and stores user prose in
   `description`.
2. **Structured model** (`Policy` / `PolicyRule`, MVEL) — the legacy jeasy-rules
   path, explicitly marked "remaining" legacy in `PolicyEvaluationService`.
3. **`dlap/v1` DSL** (`PolicyDsl` + `PolicyDslParser` + `PolicyDslEvaluator`,
   ADR-0021) — the canonical evaluatable model. `LifecyclePolicyConsumer` loads
   every `ENABLED` policy and **parses its `Policy.description` as DSL YAML**
   (an interim overload the code flags: "stored in `Policy.description` for
   v0.1.0; a dedicated field lands … along with the UI editor").

Two compounding defects made every wizard-authored policy inert:
- **Prose in the DSL slot** — the builder writes human text to `description`;
  the evaluator parses `description` as YAML → parse fails → policy skipped.
- **Dropped rules on publish** — `PolicyService.convertDraftToPolicy` is a
  placeholder ("This is simplified — real implementation would convert …") that
  never maps the draft's rules into the policy, and never sets status, so a
  published policy defaults to `DISABLED` (the evaluator only loads `ENABLED`).

**Decision**:
1. The **`dlap/v1` DSL YAML is the single canonical, evaluatable, portable
   representation.** It moves off the overloaded `description` onto a dedicated
   `Policy.policyDocument` TEXT column (the field the code anticipated). The
   evaluator reads `policyDocument`, falling back to `description` for
   back-compat with any v0.1.0 rows.
2. The **visual builder stays the primary human authoring surface** and is
   *enhanced*, not replaced — its field/operator vocabulary aligns to the DSL
   `spec.match` clause set so conditions map 1:1.
3. **The backend owns the structured→DSL translation** (`PolicyDslTranslator`),
   not the frontend: DSL knowledge stays co-located with the parser/evaluator/
   spec in `da-policyengine`, is unit-testable in Java, and is reused by every
   authoring surface. The builder posts structured conditions/actions; the
   backend serializes them to canonical YAML and stores it in `policyDocument`.
4. The **raw-YAML editor is a secondary, round-tripping advanced view** over the
   same `policyDocument`. Both create paths **validate through `PolicyDslParser`
   on save** — invalid YAML returns 400 with the parser message instead of
   persisting an inert policy.
5. **Publish means activate**: `convertDraftToPolicy` builds the DSL from the
   draft and sets status `ENABLED` so a published policy actually fires.

**Consequences**:
- A dedicated `policy_document` column is added by Flyway
  (`da-policyengine` V3). A single column suffices — per-version history is
  already carried by the drafts table; revisit a `policy_documents` table only
  if independent DSL versioning is needed.
- Sandbox stance (no live data) means no data migration for the description→
  document move; the evaluator's description fallback covers any residual rows.
- The legacy MVEL `Policy/PolicyRule` path is not deleted in this ADR; it is
  superseded in direction and may be retired once no policy depends on it.
- `docs/specs/policy-dsl.md` example still shows the pre-rename `dalab/v1` /
  `dalab.*` topics; corrected alongside this work.

**Follow-ups**: builder field vocabulary alignment + raw-editor validation UX +
detail-view DSL rendering + enable/disable wiring (frontend increment);
seed real DSL policies and prove end-to-end (asset upsert → match → action event).

---

## ADR-0030 — Scope the MySQL JDBC driver (GPLv2, Category X) to tests only; exclude it from the released da-discovery jar

**Date**: 2026-07-03
**Status**: Accepted

**Context**: `da-discovery/build.gradle.kts` declared
`implementation("com.mysql:mysql-connector-j")`. MySQL Connector/J is licensed
GPLv2 (with the FOSS Exception), which is **Apache Software Foundation
Category X** — prohibited from being included in an Apache release artifact.
An audit of the module showed:
- `da-discovery`'s own persistence datasource is **PostgreSQL**
  (`spring.datasource.driver-class-name=org.postgresql.Driver` in
  `application.properties`, `application-dev.yml`, `application-prod.yml`,
  `config/application.yml`) — MySQL is not used for the service's own storage.
- **No** main/runtime code imports `com.mysql` or loads the driver. RDS
  discovery reads database metadata through cloud provider APIs, not by opening
  a JDBC connection; `RdsResource` only names `mysql` as an engine string.
- The **only** consumer is the test harness
  `src/test/java/.../sd/config/MysqlTestContainer.java`, a Testcontainers
  `MySQLContainer` whose `getDriverClassName()` returns
  `com.mysql.cj.jdbc.Driver`.

**Decision**: Demote the dependency from `implementation` to
`testImplementation`. Because only tests need the driver, this removes it from
the distributed jar's compile and runtime classpaths entirely — the released
artifact contains no GPLv2 code — while keeping the MySQL Testcontainers
integration working. This is preferred over `compileOnly`/optional-driver
patterns, which are for drivers that main code references and expects the
end-user to supply at runtime; here main code has zero references, so
test-scoping is both correct and simpler.

**Consequences**: The DLAP release no longer ships a Category X dependency
(clears the OSS launch license-compliance guardrail). MySQL-backed
Testcontainers tests continue to compile and run (test classpath retains the
driver). If future main-code work ever needs live MySQL JDBC connectivity
(e.g. direct MySQL RDS scanning), that would reintroduce the licensing question
and must be handled via the optional user-provided-driver (`compileOnly`)
pattern with its own ADR — it is out of scope here.

---

## ADR-0029 — Adopt Apache Unity Catalog as an optional external catalog integration; retire the dead in-tree stub

**Date**: 2026-06-18
**Status**: Accepted

**Context**: `da-discovery` carried a half-built "Unity Catalog" stub —
`UnityCatalogManager` (raw `HttpURLConnection`, hardcoded
`http://localhost:8080`, only `createCatalog`/`createSchema`), an **empty**
`UnityCatalogController`, an unwired `@Bean`, and vestigial `unity.catalog.*`
config threaded through `DADiscoveryAgent` and `GCPConfigService`. None of it
was reachable on the graph, and there was **no** `io.unitycatalog` dependency.
The real catalog is `da-catalog` (system of record, `dlap_common.dlap_assets`
via the `Asset` entity) fed by the discover→catalog→label→policy→lifecycle
Kafka pipeline. new-ui already moved its catalog page off the old "Unity
Catalog endpoint" to the da-catalog API.

Unity Catalog (the Apache 2.0 OSS project, `io.unitycatalog`) is a tabular
metastore: a 3-level `catalog.schema.table` namespace over Delta/Iceberg,
volumes, functions, and models, with a stable OpenAPI REST surface. DLAP's
`Asset`/`CloudResource` model is broader and lifecycle-centric (buckets,
warehouse tables, RDS, blobs + labels + policy status). They are
**complementary**, not substitutes.

**Decision**:
1. **Retire the dead stub** (`UnityCatalogManager`, `UnityCatalogController`,
   `UnityCatalogConfig`, `CatalogLiterals`, and the unused `unity.catalog.*` /
   `unitycatalog.*` config + getters). Building on it is the regression risk.
2. **Integrate the official client** `io.unitycatalog:unitycatalog-client`
   behind an `ExternalCatalogIntegration` SPI, **profile-gated** on
   `dlap.catalog.unity.enabled` (default **false**). `da-catalog` stays the
   system of record; UC is an interoperability layer.
3. **Sink** (`da-catalog`): on asset upsert, tabular assets (BigQuery/Glue/
   Delta/Iceberg) are registered as UC `catalog.schema.table` and DLAP labels
   are mapped to UC table **properties**. UC becomes the open, queryable
   metastore view of DLAP-governed data.
4. **Source** (`da-discovery`): a UC discovery provider (peer of AWS/GCP)
   lists UC tables and emits `ResourceEvent`s into the existing pipeline, so
   DLAP governs lakehouse assets cataloged in UC.

**Consequences**: Additive and reversible — flag-off reproduces today's
behavior bit-for-bit (no regression); flag-on makes UC participate across both
discovery and catalog (holistic). The client is Apache 2.0 (clears the OSS
guardrails) and provider-neutral (no cloud-SDK-in-`common` violation). A
`unitycatalog` Docker service (compose `unity` profile, mirroring LocalStack)
and a Testcontainers smoke test back the integration. Supersedes the dead
stub; does not change the `Asset` system-of-record decision (ADR-0028).

---

## ADR-0028 — CloudResource is an additive richer model in da-protos alongside Asset; supersedes the ADR-0022 retirement direction

**Date**: 2026-06-13
**Status**: Accepted (v0.2.0 N4; supersedes the retirement stance of ADR-0022, reframes ADR-0021's P0-E)

**Context**: ADR-0021/0022 decided to retire `da-discovery`'s internal
`CloudResource` in favor of `Asset` (`dlap_common`) as the single source
of truth, deferring the physical deletion to v0.2.0. The v0.2.0 handoff
(Workstream E / N4) instead asks to *implement* `CloudResource` in
`da-protos` and migrate `Asset` callers to it where it is "more
expressive (large-asset metadata, cross-region replication,
asset-of-assets)". Those are opposite directions. Owner ruling: pick the
option requiring **less refactoring, no module breakage, and the better
long-term design**.

Assessment: retiring `da-discovery`'s `CloudResource` is the *most*
refactoring and *highest* breakage risk — it is a 40-file, JOINED-
inheritance domain model (`aws_resource`, `gcs_resource`,
`cloud_sql_resource`, … all subtypes) that the crawlers build directly.
ADR-0022 itself deferred deletion for exactly that risk. Implementing a
shared `CloudResource` in `da-protos` is purely **additive**.

**Decision**: Add `CloudResource` to `da-protos`
(`com.dlap.common.model.entity.CloudResource`, table
`dlap_common.dlap_cloud_resources`) as a richer, optional model that
**complements** `Asset` rather than replacing it:

- `Asset` stays the lightweight canonical cross-service asset (the
  single source of truth on the auto-flow path). `CloudResource`
  carries provider-native detail that does not belong on `Asset`:
  hierarchy (`parent_resource_id` + `has_children` → asset-of-assets),
  cross-region replication (`replica_regions`), and the raw
  provider payload (`provider_native_json`). A nullable `asset_id`
  links a `CloudResource` to its canonical `Asset`.
- Nothing existing is touched: no `Asset` caller is migrated, and
  `da-discovery`'s internal `CloudResource` is left in place. Adoption
  is opt-in and documented in `docs/specs/cloud-resource.md`.
- The shared `CloudResource` is the convergence target: `da-discovery`'s
  bespoke entity can later collapse onto it (closing the original P0-E
  duplication) without a flag-day rewrite.

**Schema**: per CLAUDE.md §6, `dlap_common` is bootstrapped by raw SQL
init scripts (not Flyway, because services read the schema before any one
runs migrations). The table is therefore added to BOTH
`02-create-common-schema.sql` files (dev + platform) plus a delta under
`deployment/platform/database/upgrades/` — not a Flyway migration (which
the handoff wording suggested).

**Consequences**:
- Two `CloudResource` types coexist transitionally (da-protos shared +
  da-discovery internal, different packages). Documented as the
  convergence direction; the duplication is bounded and opt-in.
- ADR-0021's P0-E "Asset is the single source of truth" still holds for
  the auto-flow path; `CloudResource` is supplementary detail, not a
  competing canonical asset.
- ADR-0022's "retire and migrate to Asset" work item is withdrawn in
  favor of this additive approach.

---

## ADR-0027 — Rename the open-source project DALab → DLAP; retain DALab as donor / commercial brand

**Date**: 2026-06-07
**Status**: Accepted (supersedes ADR-0011)

**Context**: ADR-0011 resolved Q-004 by keeping "DALab" as the public
name. That decision is now reversed. `dalab.ai` (Data Automation Labs)
is a live commercial SaaS marketing five "Smart Agents" that map 1:1
to this repo's lifecycle stages. A trademark conflict blocks an Apache
Software Foundation donation under the "DALab" name. "DLAP" (Data
Lifecycle Automation Platform) is niche enough to clear cleanly. The
donor pattern mirrors Yahoo → Apache Hadoop, LinkedIn → Apache Kafka,
and UC Berkeley AMPLab → Apache Spark: the commercial parent stays a
distinct brand from the Apache project.

**Decision**: Rename the open-source project to **DLAP** tree-wide:
Java packages (`com.dalab.*` → `com.dlap.*`), the `DalabConstants`
class, Kafka topic prefix (`dalab.*` → `dlap.*`), env-var prefix
(`DALAB_*` → `DLAP_*`), config property prefix (`dalab.*` → `dlap.*`),
database schemas/tables/columns (`dalab_*` → `dlap_*`), the Keycloak
realm (`dalab` → `dlap`) and its clients, the Gradle root project name,
and project-referencing documentation. **Retain DALab / Data Automation
Labs / dalab.ai** wherever the text refers to the commercial donor
(README donor blurb, NOTICE/LICENSE copyright, governance contact
emails, the `Data-Automation-Lab` GitHub org slug) and wherever the
text is historical record (CHANGELOG `[0.1.0]`, prior session-log and
ADR entries, the `v0.1.0` tag).

**Consequences**:
- v0.2.0 is the first release under the DLAP name and the first cut
  shaped for public Apache incubation. It carries **breaking wire
  changes**: Kafka topic renames and env-var renames. Consumers and
  deployments must migrate per `migration/RENAME.md`.
- To ease consumer cutover, `da-catalog` originally **dual-published** the
  asset change event to BOTH the new and the legacy (`dalab.*`) topic for a
  30-day window. **Update 2026-06-16:** the platform has no consumers yet, so
  the shim was removed before the window elapsed — the topic rename is a hard
  cutover with no backward-compatibility layer.
- Database identifier renames require a migration on existing prod
  databases (per-schema/table/column `ALTER … RENAME`, or recreate for
  dev/test), documented in `migration/RENAME.md`.
  **Update 2026-10-31 (hardening wave 4B, CLEAN-01, D24):** `migration/RENAME.md` was
  removed before the public launch, together with the other legacy paths (backup/,
  cursor-handoff/, prompts/, design-docs/, qa/, the root role-test scripts and Postman files,
  the da-discovery JHipster scaffold and the donor GCP setup scripts); git history keeps it,
  and CHANGELOG.md and docs/guides/upgrade.md record the removal.
- Workstreams B–F operate on the renamed (`com.dlap.*`) tree.

**Supersedes**: ADR-0011 (kept DALab as the public name; now reversed).

---

## ADR-0026 — Oracle Cloud Infrastructure (OCI) path: gate with `@Profile("oracle")` for v0.2.0; defer real productionization beyond v0.3.0

**Date**: 2026-05-12
**Status**: Accepted (v0.2.0 N0 scope)

**Context**: An `oracle` cloud path exists in `da-discovery` from a prior contribution: 11 Java files spanning `common/auth/impl/oracle/`, `common/config/cloud/impl/oracle/`, `crawler/model/oracle/`, `crawler/service/oracle/`, and `log/service/oracle/`. The code uses real `com.oracle.bmc.*` OCI SDK calls and pulls six `com.oracle.oci.sdk:oci-java-sdk-*` deps in `da-discovery/build.gradle.kts` (lines 149-154). References to Oracle types appear in `CloudResource.java` (as a `@JsonSubTypes.Type` entry keyed `OCI`), `CloudResourceFactory.java`, and `DiscoveryServiceConfig.java`. The `DiscoveryServiceConfig` Oracle bean wiring already uses `@Autowired(required = false)`, so Spring won't fail to start if the Oracle beans aren't present.

Oracle is not on the v0.1.0 / v0.2.0 / v0.3.0 published roadmap (GCP-first → AWS → Azure). Three options were considered:

- **Option A — Gate only.** Apply `@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)` consistently to all Oracle Spring beans. Keep deps. Oracle code stays compilable; doesn't instantiate by default. Lowest disruption.
- **Option B — Gate + sub-module.** Move Oracle code to a separate Gradle sub-project that's disabled by default. Cleaner separation, more refactor cost.
- **Option C — Remove entirely.** Delete the 11 files and six deps. Reintroduce when prioritized. Cleanest, but loses prior work.

**Decision**: **Option A** with the existing `@ConditionalOnProperty(name = "cloud.provider.oci.enabled", ...)` pattern. Audit reveals 6 of 7 Oracle Spring beans are already gated (`OracleAuthenticationServiceImpl`, `OracleConfigService`, `OracleConfigServiceImpl`, `OracleComputeInstanceCrawler`, `OracleDiscoveryServiceImpl`, `OracleResourceCrawler`, `OracleLogAnalyzer`); only `OracleLoggingAdapter` lacks the gate and gets it added. The 4 non-bean files (`IOracleAuthenticationService` interface, `OracleLogEntryAdapter` POJO, `OracleResource` record, etc.) need no annotation. Oracle SDK deps remain in `da-discovery/build.gradle.kts` so the tree compiles. `CloudResource.java`'s `@JsonSubTypes` keeps the `OCI` subtype registration so wire format stays stable — deserialization of inbound OCI events without the property active falls through the optional-autowire path that already exists. Update README "Project status" to declare Oracle as property-gated and not on the v0.2.0/v0.3.0 roadmap.

Also clean up the 12 `*.bak` files (11 `.java.bak` in `da-discovery/` plus `gradlew.bak`). Sandbox cannot unlink files on the Cowork mount, so this is an owner-side cleanup: `find . -name "*.bak" -not -path "./.git/*" -delete` on Windows. They are already excluded by `.gitignore:51` (`*.bak`); this just clears working-tree clutter for sane grep/IDE results.

**Consequences**:
- Default builds boot without Oracle beans; `@Autowired(required = false)` returns null and discovery service maps don't include an `oracle` entry. No runtime regression for the GCP / AWS / Azure path.
- A user who wants to exercise Oracle code in dev sets `cloud.provider.oci.enabled=true` (env: `CLOUD_PROVIDER_OCI_ENABLED=true`); the conditional beans activate.
- If Oracle is later promoted to a real roadmap cloud, the gating is reversible by one annotation deletion per file. No architectural debt accrues from this decision.
- Six OCI SDK deps stay in the dependency graph (~12 MB). Acceptable for v0.2.0; revisit if dependency-bloat audits surface this in v0.3.0.
- The 12 `*.java.bak` files leaving the tree is purely cosmetic but removes ~2000 lines of stale grep matches.

**Future ADR triggers**: a contributor proposing real OCI productionization (real audit-log ingest, real OCI scanners on `da-discovery`'s newer architecture) would supersede this ADR. ADR-0023 (Kafka-only event transport) and ADR-0021 (log-enriched architecture) would shape that work.

---

## ADR-0025 — M1.2 surgical wave finishes in two phases: architectural markers in v0.1.0, physical migrations in v0.2.0

**Date**: 2026-04-21
**Status**: Accepted (closes the M1.2 plan with the same phasing pattern as ADR-0024)

**Context**: M1.2 has three multi-hour surgeries: R-P0-5 (providers/<cloud>/ relocation, 16 files), R-P1-12 (ComplianceServiceImpl split, 1188 lines), R-P0-4 (da-discovery Liquibase → Flyway port, 48 changelog files). ADR-0024 split R-P1-12 into phase-1 markers + phase-2 migration. This ADR applies the same shape to R-P0-5 and R-P0-4 and closes M1.2 as "architecturally compliant in v0.1.0; physical migration in v0.2.0."

**Decision**:

**R-P0-5 providers/<cloud>/ refactor**
- Phase 1 (v0.1.0): the architectural intent is encoded in (a) CLAUDE.md §9 ("no provider SDK in core/ / common/ / api/"), (b) ADR-0004 (providers/<cloud>/ layout), (c) the per-cloud {@code @Profile("<cloud>")} gates already in place on AWSDiscoveryServiceImpl (P0-3) and AWSLoggingAdapter (P0-2-N7). Code-level enforcement is documentary only in v0.1.0; an ArchUnit boundary rule lands in v0.2.0 alongside the physical relocation. New code MUST target {@code providers/<cloud>/} per CLAUDE.md.
- Phase 2 (v0.2.0): physically relocate the 16 files identified by the M1 audit (da-discovery/common/auth/impl/{aws,azure,oracle}/*, da-discovery/log/service/{aws,azure,gcp,oracle}/*, da-discovery/crawler/service/{aws,azure,gcp,oracle}/*) into providers/<cloud>/ subpackages, add an ArchUnit rule that fails the build if anything under core/ / common/ / api/ imports a provider SDK.

**R-P0-4 da-discovery Liquibase → Flyway port**
- Phase 1 (v0.1.0): no new Liquibase changelogs accepted; new schema work in da-discovery lands as Flyway V{N}__*.sql under db/migration/ even while the legacy Liquibase changelogs continue to apply on boot. This stops further drift.
- Phase 2 (v0.2.0): port the 48 existing Liquibase changelogs (~110 changesets) into a sequential Flyway V1..VN series, delete the Liquibase plugin + config + changelog tree, set spring.flyway.baseline-on-migrate to handle existing prod databases that have already applied the legacy schema. Per-changeset port + verify cycle is contained to da-discovery's own database; no cross-service blast radius.

**R-P1-12 ComplianceServiceImpl split** — covered separately by ADR-0024.

**Consequences**:
- M1.2 closes as "phase-1 complete; phase-2 v0.2.0". The platform launches v0.1.0 without behavioural regressions; the architecture's direction is documented and partially enforced.
- v0.2.0 inherits three contained migrations, each independent: ComplianceServiceImpl methods (ADR-0024 phase-2), providers/<cloud>/ relocation (this ADR R-P0-5 phase-2), Liquibase → Flyway port (this ADR R-P0-4 phase-2). None blocks the others.
- v0.1.0 docs (README, CHANGELOG) describe the deferral honestly so adopters know what they're getting.

---

## ADR-0024 — ComplianceServiceImpl god-class split: phase-1 interfaces + thin facades land in v0.1.0; method-body migration in v0.2.0

**Date**: 2026-04-21
**Status**: Accepted (refines ADR-0020 which deferred the split)

**Context**: ADR-0020 deferred R-P1-12 with reason "refactor risk + sandbox cannot compile-test." The 1188-line {@code ComplianceServiceImpl} has clean concerns — KPI / dashboard, control management, report generation (with @Async), scan / violation — but moving 33 methods across 4 services without an interactive build loop is genuinely high-risk.

**Decision**: Two-phase split.

**Phase 1 (this commit, v0.1.0)** — architectural marker:
- Define four interfaces: {@code IComplianceKpiService}, {@code IComplianceControlService}, {@code IComplianceReportService}, {@code IComplianceScanService}. Each enumerates its methods in final signatures.
- Provide four thin-facade impl classes ({@code Compliance*ServiceImpl}) that delegate to the existing {@code IComplianceService} bean via {@code @Lazy} injection. Callers can wire to any new interface today and receive identical behaviour.
- The original {@code ComplianceServiceImpl} stays put; method bodies unchanged.

**Phase 2 (v0.2.0)** — physical migration:
- For each method, copy the body from {@code ComplianceServiceImpl} into the matching {@code Compliance*ServiceImpl}, move its repository + mapper fields with it, remove the delegate call. Run tests after each method. The {@code @Async} + {@code @Lazy self} proxy in {@code processReportGenerationAsync} moves into {@code ComplianceReportServiceImpl}.

**Consequences**:
- Phase 1 ships zero behavioural change. The new interfaces give callers a roadmap and let test-only consumers wire to focused surfaces today.
- Phase 2 is contained to {@code da-autocompliance} (no cross-service blast radius) and can land incrementally — one concern at a time per commit.
- The original {@code ComplianceServiceImpl} stays deprecation-free in v0.1.0; deprecation marker lands with phase 2 once all four sub-services are physically migrated.

---

## ADR-0023 — Kafka is the only event transport; per-cloud pub-sub services are rejected

**Date**: 2026-04-21
**Status**: Accepted (corrects P0-C's initial implementation; supersedes the Pub/Sub assumption from ADR-0010 commentary)

**Context**: P0-C's first implementation used Google Cloud Pub/Sub as
the inbound transport for GCP Audit Log events (`Subscriber.newBuilder()`).
Owner flagged the architectural break: Pub/Sub is GCP-specific, charges
per message (egress + storage), and forces the platform to learn a new
transport per cloud (Pub/Sub for GCP, SNS+SQS or Kinesis for AWS,
Event Grid for Azure, Streaming for OCI). The whole point of
{@code da-logingest} is that downstream services see one stream
regardless of cloud — that constraint extends UP into how we
PULL events too.

**Decision**: Kafka is the only event transport inside the DALab
platform. Per-cloud log adapters in {@code da-logingest} use
each cloud's native LOG-QUERY API (pull-based) to fetch audit events
and publish normalized {@code DalabLogEvent}s to the platform's own
Kafka topic {@code dalab.log.event.normalized}. Cloud-native pub-sub
products are not used as DALab's transport plane.

Per-cloud pull mechanisms:

| Cloud | API | Frequency | Resume mechanism |
|---|---|---|---|
| GCP   | Cloud Logging `entries.list` with filter `logName:cloudaudit.googleapis.com` | every 60s (configurable) | last-seen `timestamp` watermark stored in {@code dalab_logingest.source_watermarks} |
| AWS   | CloudTrail `LookupEvents` or CloudWatch Logs `FilterLogEvents` | every 60s | watermark on `EventTime` (CloudTrail) or `lastEventTimestamp` (CWL) |
| Azure | Azure Monitor `ActivityLogs.list` | every 60s | watermark on `eventTimestamp` |
| OCI   | Audit `ListEvents` | every 60s | watermark on `event_time` |

The pull pattern is intentional: it tolerates outages
({@code da-logingest} catches up after a restart from the
watermark), it has no per-message cost beyond the cloud's standard
API quota, and it terminates inside the platform's own Kafka — no
external transport dependency for downstream consumers.

**Consequences**:
- {@code GcpAuditLogSubscriber} (P0-C first cut) renames to
  {@code GcpLogSource}; rewrites to use {@code google-cloud-logging}
  with {@code @Scheduled} polling. The Pub/Sub dependency
  ({@code com.google.cloud:google-cloud-pubsub}) is removed from
  {@code da-logingest/build.gradle.kts}; the logging dependency
  ({@code com.google.cloud:google-cloud-logging}) is added.
- A new persistence table {@code dalab_logingest.source_watermarks}
  tracks the last-seen timestamp per (provider, account) so
  {@code da-logingest} resumes from where it left off after restarts.
- AWS, Azure, OCI sources (deferred to v0.2.0+) follow the same
  pull-into-Kafka pattern. Each cloud's pull API requires its own
  credential type but emits the same {@code DalabLogEvent} shape.
- The {@code DalabLogEvent} contract and downstream pipeline
  (DiscoveryLogEventProcessor → typed topics → consumers) are
  unchanged. ADR-0021's architecture is preserved end-to-end on
  Kafka.
- Per-tenant fan-out (multi-tenant work in 0.3.0+ per ADR-0013)
  becomes simpler: each tenant gets its own Kafka topic partition
  on the same {@code dalab.log.event.normalized} topic, keyed by
  tenant id. No per-tenant Pub/Sub subscription provisioning.
- Cost framing: Kafka is self-hosted in the DALab stack (KRaft mode,
  bundled in compose / Helm). Operators pay storage + compute on
  their own infrastructure; no per-message cloud charges.

---

## ADR-0022 — CloudResource retirement (P0-E) deferred to v0.2.0 [retirement direction withdrawn by ADR-0028]

**Date**: 2026-04-21
**Status**: Accepted (partial implementation of ADR-0021's P0-E)

**Context**: ADR-0021 mandates that `da-discovery`'s internal
`CloudResource` model retire in favor of `Asset` in `dalab_common` as
the single source of truth. Audit found `CloudResource` referenced in
40 files (entity, repository, factory, 5+ crawler classes,
CatalogServiceImpl, ResourceEventListener, CatalogResourceEventProcessor,
controllers, tests). A safe retirement is multi-hour surgery; the
Cowork sandbox cannot compile-test, so the risk of subtle breakage
without an interactive build loop is real.

**Decision**: Land the *runtime-correctness* half of P0-E now and
defer *physical-deletion* to v0.2.0. Specifically:

- The auto-flow critical path (crawler → catalog → label → policy →
  archive/delete) DOES NOT depend on `CloudResource` after P0-A
  through P0-N. Crawlers publish to
  `dalab.discovery.asset.discovered`; `da-catalog`'s
  `DiscoveryAssetDiscoveredListener` ingests into `Asset`. Lifecycle
  log events flow through `DiscoveryLogEventProcessor` to typed
  topics, where `AssetLifecycleEventListener` (in da-catalog) updates
  `Asset` directly. `CloudResource` reads/writes happen only on the
  legacy `CatalogServiceImpl` query path (used by some
  discovery-internal REST endpoints).
- `CloudResource` is marked `@Deprecated` with a Javadoc note pointing
  at v0.2.0 deletion.
- v0.2.0's M1.2-equivalent work removes the entity, repository,
  factory, the `ResourceEventListener` + `CatalogResourceEventProcessor`
  legacy crawler-side chain, and migrates the few remaining query
  endpoints to `Asset`.

**Consequences**:
- P0-E status: "code-side compliant; physical deletion deferred to
  v0.2.0 per this ADR."
- v0.1.0 launches with a duplicated-but-orphaned `CloudResource`
  table in `da-discovery`'s schema. Operators who query the
  service-internal `/api/v1/discovery/resources` endpoint still see
  rows; the rows just don't drive the auto-flow.
- v0.2.0 work item: "Retire CloudResource entity + repository +
  factory + ResourceEventListener + CatalogResourceEventProcessor;
  migrate /api/v1/discovery/resources callers to /api/v1/catalog/assets."

---

## ADR-0021 — Log-event spine and policy-driven lifecycle as the runtime architecture

**Date**: 2026-04-21
**Status**: Accepted (supersedes the partial wiring from M2/M3/M4/M5; binds the revised P0 execution plan)

**Context**: A post-M8-prep audit found the platform was wired
event-by-event but not architecturally — per-cloud log analyzers
emitting a legacy `LogEvent` type to a shared topic, an internal
`CloudResource` table duplicating `Asset`, no central interpreter
turning audit-log changes into asset-lifecycle events, and policies
that only fired the archive/delete legs of the lifecycle. Owner
clarified the intended architecture during M8 prep:

1. **No backward compatibility constraints.** Legacy types and topics
   can be retired without migration.
2. **Per-cloud log sources** (one impl per cloud) own audit-log
   subscription + normalization. Each emits `DalabLogEvent` to the
   single normalized topic.
3. **A central interpreter** consumes the normalized topic, decides
   what kind of asset-lifecycle event each log entry represents
   (CREATE / UPDATE / DELETE / ACCESS / PERMISSION / ARCHIVE in
   data-asset terms), and routes to typed lifecycle topics.
4. **Every downstream service** (catalog, autolabel, autoarchival,
   autodelete, autocompliance, policyengine) subscribes only to the
   typed lifecycle topics it cares about.
5. **Policies in the admin UI** are the configuration mechanism for
   the whole pipeline. Modular, editable. A policy can trigger on any
   lifecycle event and emit any of a fixed action set.

**Decision**: The runtime architecture is:

```
Per-cloud LogSource (in da-logingest):
    GcpLogSource / AwsCloudTrailSource / AzureActivitySource / OracleAuditSource
  → publishes DalabLogEvent → dalab.log.event.normalized

DiscoveryLogEventProcessor (in da-discovery, single instance):
  → subscribes to dalab.log.event.normalized
  → maps DalabLogEvent.changeType → one of six typed lifecycle topics:
      dalab.discovery.asset.discovered
      dalab.discovery.asset.modified
      dalab.discovery.asset.deleted
      dalab.discovery.asset.accessed
      dalab.discovery.asset.permission-changed
      dalab.discovery.asset.archived
  → republishes the same DalabLogEvent payload on the typed topic
    so downstream consumers see both the cloud-native detail and the
    asset-lifecycle classification

Downstream consumers (subscribe per role):
  da-catalog        — all six topics (state of record)
  da-autolabel      — discovered, modified (label revisit)
  da-autoarchival   — accessed (last-accessed driving retention)
  da-autodelete     — deleted (reconciliation + cleanup)
  da-autocompliance — permission-changed (IAM drift)
  da-policyengine   — all six (policy match)

Policy evaluation (in da-policyengine):
  → subscribes to all dalab.discovery.asset.* lifecycle topics
  → loads policies from PolicyRepository (admin-UI-managed)
  → for each event, finds matching enabled policies via the DSL
    parser (docs/specs/policy-dsl.md)
  → publishes dalab.policy.action.event per match
  → existing PolicyActionEventListener in da-autoarchival + new one
    in da-autodelete (M5) execute the action
```

**Consequences**:

- **Legacy `LogEvent` type (`da-discovery/event/service/type/LogEvent.java`)
  retires.** Single canonical event type: `DalabLogEvent` (v1 proto +
  JSON, M2 work). No bridge / translation adapter — clean break per
  owner directive 1.
- **Per-cloud log analyzers in `da-discovery` relocate to `da-logingest`**
  and rename. `GcpLogAnalyzer` → `GcpLogSource`. Each becomes a thin
  audit-log subscriber that normalizes its native format to
  `DalabLogEvent` and publishes. The bulk of the existing logic
  (operation-to-change-type mapping via `LogAnalyzerProperties`) moves
  with them.
- **`CatalogLogEventProcessor` retires.** Its role (interpret log
  events for catalog updates) is replaced by `DiscoveryLogEventProcessor`
  (interpret + route) + a per-topic listener in `da-catalog` that
  consumes the typed lifecycle topics.
- **`CloudResource` table in `da-discovery`'s internal schema retires.**
  All asset state in `Asset` (`dalab_common`). Discovery becomes a
  thin crawler + log interpreter; it does not own a duplicate
  catalog.
- **Crawler discovery output converges with log-event output.**
  Crawlers publish `DalabLogEvent`s with `changeType=CREATE` and
  source=`crawler`. `DiscoveryLogEventProcessor` routes them to
  `dalab.discovery.asset.discovered` the same way a real cloud-audit
  CREATE would. One ingest pipeline, two upstream sources.
- **The Policy DSL gains the full action and trigger surface.**
  `docs/specs/policy-dsl.md` extended to v1.1: triggers include all
  six lifecycle events; actions add `alert.{email,slack,webhook}`,
  `setRetention`, `tagAsset`, `evaluateCompliance`, `runDiscovery`,
  `addLabel`, `removeLabel`.
- **`evaluatePolicyForAssetInternal` (legacy rule-based) retires.**
  Single evaluation path: the DSL parser. `PolicyEngineMetricsService`,
  `IPolicyService`, controllers continue to work; only the rule
  matcher gets swapped out.
- **Admin UI gets a `PolicyEditor.tsx`** that CRUD's policies in the
  DSL. Modular and editable per owner directive 3.
- **M3 acceptance shifts.** Verifying discovery against a real GCP
  project now also verifies the log-event spine (GcpLogSource pulls
  audit logs, DiscoveryLogEventProcessor
---

## Restored historical ADRs (0001, 0008-0020)

These ADRs were authored earlier but were progressively dropped from `DECISIONS.md`
by sliding-window edits during v0.1.0 work. Restored 2026-06-07 from
the last good snapshot at commit `b253f13`. Numbering gap 0002-0007:
those ADRs were referenced in commit messages and CLAUDE.md but never
formally recorded in this file. The gap is preserved rather than
back-filled so historical references stay correct.

---

## ADR-0020 — Defer ComplianceServiceImpl god-class split (R-P1-12) from M0 to M1

**Date**: 2026-04-21
**Status**: Accepted (alters M0 scope set in 2026-04-21 ADR-0017 milestone plan)
**Context**: The original M0 milestone included R-P1-12: split
`da-autocompliance/ComplianceServiceImpl.java` (59 KB / 1188 lines /
~33 methods) into focused services. M0's other items (R-P0-2 mock
fallbacks, R-P1-2 Kafka publish, R-P1-11 legacy doc retirement,
R-P1-8 + R-P2-1 license headers) all completed cleanly. The
god-class split clusters into four concerns: KPI/dashboard,
control management, report management, scan/violation tracking.
Clean split is 4 new services + a thin facade preserving the
`IComplianceService` contract — ~2-4 hours of careful surgery.
The Cowork sandbox cannot compile-test the result (JDK 11 JRE
only; project requires JDK 21), so any move-and-rename mistake
ships unverified to the user's Windows env. M1's structural
cleanup batch is a more natural home: it already touches all
9 services for the providers/<cloud>/ refactor (R-P0-5) and
the DLQ + manual-ack work (R-P1-6); the autocompliance split
piggybacks on that compile-test cycle.
**Decision**: R-P1-12 moves from M0 to M1. M0 still tags
`m0-foundations` once the rest of the milestone closes. M1's
acceptance criteria gain a clause: `da-autocompliance`
god-class split into ≥4 focused services with the original
class retained as a delegating facade for backward compat;
existing `da-autocompliance` tests still pass.
**Consequences**:
- M0 ships ~1 day earlier than the original plan.
- M1 grows from ~5 to ~6 eng-days.
- v0.1.0 timeline unchanged (the slack came from M0 over-scoping
  for a non-critical-path item).
- No production behavior change in M0 from this deferral; the
  god-class is functional today and was launch-blocking only on
  maintainability grounds.

---

## ADR-0019 — `feature/deployment` is the long-running execution branch through M0-M7

**Date**: 2026-04-21
**Status**: Accepted
**Context**: The completion plan spans 8 milestones over ~6-8 weeks
on `feature/deployment`. Cutting fresh branches per milestone adds
merge churn without isolation benefit (one contributor; sequential
work). The fresh squashed donation repo for public launch (ADR-0007
Option B) is a separate cut.
**Decision**: All M0-M7 work lands on `feature/deployment`. Each
milestone closes with a signed annotated tag (`m0-foundations`,
`m1-cloud-neutral`, etc.). M8 cuts the public donation repo from
the M7 tagged commit; v0.1.0 signed tag lives on the donation repo.
**Consequences**:
- No mid-flight branch surgery on the internal repo.
- `feature/deployment` accumulates ~50-100 commits before public cut;
  squash at donation time produces a clean public history.
- Git submodule pointer bumps for `new-ui` ride along on
  `feature/deployment` commits during M7.

---

## ADR-0018 — Trademark name-clearance for "DALab" / "Data Automation Lab" runs as a P0 task before any public-launch artifact

**Date**: 2026-04-21
**Status**: Accepted
**Context**: ADR-0011 confirmed DALab as the public name. Name
collision with existing software, services, or registered marks
would force a costly post-launch rename. USPTO + EUIPO + common-law
sweep are cheap insurance run before M8 announcement, not after.
**Decision**: Run trademark clearance as the first M8 task. If
collision found, surface fallback names; collision-free name is a
prerequisite for the v0.1.0 public tag.
**Consequences**:
- M8 cannot complete without a clearance result.
- Fallback names are pre-staged: candidates derived from the
  capitalized lifecycle stages (e.g. "OpenLifecycle", "Lifecyclr",
  "DataLifecycle"). Owner picks if rename needed.
- Code-level renames (modules, packages, schemas, topics, configs)
  are scoped under R-P2-6 if invoked.

---

## ADR-0017 — Continuous execution between user-gated milestone reviews

**Date**: 2026-04-21
**Status**: Accepted (supersedes the prior batch-with-approval cadence)
**Context**: The five-batch cadence with per-batch approval (set
2026-04-21 morning) was scoped to cleanup work where each batch
delivered independent value. Completion work (M0-M8) is sequential —
M3 cannot begin without M2; M4 cannot begin without M3. Per-batch
approval introduces dead time without correctness benefit.
**Decision**: Owner gates only at milestone boundaries. Between
gates, execution is continuous. Each milestone closes with a tagged
commit, an updated `OPERATING_PLAN`, and a `SESSION_LOG` entry.
Owner reviews the tagged commit, runs build/test on Windows, and
either approves the gate or surfaces issues for fix-forward.
**Consequences**:
- No sandbox-side wait between sub-tasks within a milestone.
- Owner workload concentrates at 8 gate points instead of 24+ batch
  points.
- A milestone can be paused or rolled back at its tag without
  affecting earlier milestones.

---

## ADR-0016 — Reference implementation IS the platform; no separation between standard and product

**Date**: 2026-04-21
**Status**: Accepted (resolves "open standard vs platform" framing)
**Context**: ADR-0012 framed v0.1.0 as a public OSS launch. Some
projects split the artifact into "spec" and "implementation"
repositories (e.g. JCP). DALab is a single-vendor effort with a
small contributor base; splitting now adds maintenance overhead
with no upside.
**Decision**: One repository, one Apache-licensed reference
implementation that is the product. Wire and data contracts are
documented as specs under `docs/specs/` (DalabLogEvent, policy DSL,
event topics). Adopters extend or fork; they do not need to
implement the spec from scratch.
**Consequences**:
- `docs/specs/` becomes a first-class top-level inside `/docs`.
- M2 produces `docs/specs/log-event.md` (R-P1-9).
- M5 produces `docs/specs/policy-dsl.md`.
- The Apache HTTP Server precedent applies: project ships a
  deployable artifact that also documents the protocol it speaks.

---

## ADR-0015 — Ollama is the default LLM provider; OpenAI is opt-in

**Date**: 2026-04-21
**Status**: Accepted (refines ADR-0009)
**Context**: ADR-0009 purged Gemini; v0.1.0 ships OpenAI + Ollama
behind `ILLMClient`. Default-provider matters for first-run
experience: OpenAI requires an API key and metered billing; Ollama
runs locally with zero credentials. Apache OSS adopters expect
zero-config out of the box.
**Decision**: `LLMClientFactory` resolves the provider from
`dalab.llm.provider` config; default value is `ollama`. OpenAI
selected explicitly via `dalab.llm.provider=openai` plus
`OPENAI_API_KEY` env. README quickstart uses Ollama. Production
adopters can switch to OpenAI in a single config flip.
**Consequences**:
- `docker-compose.dev.yml` includes an Ollama service for the
  out-of-the-box demo.
- `MockLLMClient` retained for tests; default profile in CI is
  `mock` so tests don't hit Ollama or OpenAI.
- Adding Gemini, Anthropic, or other providers in 0.2.0+ does not
  change this default decision.

---

## ADR-0014 — Postgres is the only persistence engine in v0.1.0

**Date**: 2026-04-21
**Status**: Accepted
**Context**: `da-logingest` ingests cloud audit logs; in production
that volume can justify a search/time-series engine (OpenSearch,
ClickHouse). For v0.1.0 demo-quality, Kafka retention plus Postgres
covers every read path: structured asset/label/audit data in
Postgres, event stream in Kafka with retention.
**Decision**: Postgres-only persistence in v0.1.0. Each service
owns its own schema (CLAUDE.md §6 unchanged). No OpenSearch, no
Elasticsearch, no time-series DB, no S3-equivalent object store
for audit-log archival.
**Consequences**:
- `da-logingest` writes a per-event row to its Postgres schema and
  publishes to Kafka. Adopters with high event volumes hit
  Postgres scaling limits and trigger 0.2.0 OpenSearch decision.
- Reporting time-series charts query Postgres with windowed
  aggregation; acceptable up to ~10M events.
- Admin can configure log retention policy (default: 90 days) to
  keep Postgres footprint bounded.

---

## ADR-0013 — Single-tenant per deployment in v0.1.0

**Date**: 2026-04-21
**Status**: Accepted
**Context**: Each lifecycle service today implicitly assumes a
single-tenant model: no tenant-scoping in `Asset`, `Label`,
`AssetLabelAssignment`, or any service repository. Adding
multi-tenancy requires schema changes across all 9 services plus
auth-scoped query enforcement everywhere. The owner's go-to-market
question (run-it-yourself OSS vs hosted SaaS) is not yet decided
and does not need to block v0.1.0.
**Decision**: v0.1.0 is single-tenant per deployment. Each adopter
runs their own stack (`docker compose up`); no tenant isolation,
no cross-tenant queries. Multi-tenant is a 0.3.0+ decision and
gets its own ADR if pursued.
**Consequences**:
- `Asset`, `Label`, `User`, `AssetLabelAssignment` schemas remain
  un-tenanted in `da-protos`.
- Keycloak realm is single-realm per deployment.
- Cloud-account credentials in `da-admin-service` are deployment-
  scoped, not tenant-scoped.
- Hosted SaaS framing (if pursued) becomes a 0.3.0+ effort with a
  schema migration cost.

---

## ADR-0012 — Solo-owner Apache-ready public fork now; ASF incubation later

**Date**: 2026-04-21
**Status**: Accepted (resolves Q-005)
**Context**: Apache incubation requires ≥3 initial committers from ≥2
organizations plus an ASF champion. The current repo has a single
primary contributor and no champion. Delaying the full cleanup until a
roster materializes blocks every other Apache-readiness task.
**Decision**: Pivot R-P2-6 from "Apache incubation proposal" to
"public OSS launch prep." Ship a public, Apache-2.0-licensed,
DCO-signed, governance-complete GitHub repo under the owner's control.
Incubation proposal is a separate, later milestone contingent on
community formation.
**Consequences**:
- All Apache-readiness work still lands (license headers, NOTICE, DCO,
  CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, CHANGELOG, issue templates,
  PR template). These are table stakes for any serious OSS release,
  not incubation-only.
- R-P2-6 retitled "Public OSS launch prep (fresh squashed donation
  repo, governance docs, v0.1.0 tag)." Incubation proposal moves to a
  post-v0.1.0 milestone.
- Q-005 closed. No committer-roster or champion blocker on this push.
- ADR-0007 Option B (fresh squashed donation repo) still applies as
  the cut mechanism for the public fork.

---

## ADR-0011 — Project public name is DALab

**Date**: 2026-04-21
**Status**: Superseded by ADR-0027 (2026-06-07) — project renamed to DLAP
**Context**: Internal name is DALab (Data Automation Lab). Codebase
uses `dalab_common` schema, `dalab.*` config prefixes, `da-*` module
names, `com.dalab.*` Java packages. A rename would touch every module,
schema, topic, Docker image, and config key.
**Decision**: Keep DALab as the external name. No rename. Run a naming
audit to eliminate stray `da-` / `dalab_` / `DALab` / `dalab` casing
inconsistencies so the public name is spelled identically everywhere.
**Consequences**:
- Q-004 closed.
- READMEs, CHANGELOG, CONTRIBUTING, SECURITY, issue/PR templates all
  reference "DALab" (capitalized when prose, `dalab` when code/config).
- GLOSSARY.md gets a canonical-spelling entry.

---

## ADR-0010 — `da-logingest` is a new service, not a module inside `da-discovery`

**Date**: 2026-04-21
**Status**: Accepted (resolves Q-003; reinforces ADR-0003)
**Context**: ADR-0003 designates `DalabLogEvent` as the canonical
log-event type and the `dalab.log.event.normalized` topic as its
transport. The producer has to live somewhere. Two options: a new
service (`da-logingest`) or a new module inside `da-discovery`.
**Decision**: New service — `da-logingest`, port 8090.
Cloud adapters (`AWSLoggingAdapter`, `GCPLoggingAdapter`,
`AzureLoggingAdapter`, `OracleLoggingAdapter`) relocate from
`da-discovery` to `da-logingest`. `da-discovery` becomes a consumer of
`dalab.log.event.normalized` rather than a direct log-SDK caller.
**Consequences**:
- "Log-enriched" becomes a standalone story at incubation review
  time — one service owns log ingestion end-to-end.
- R-P1-10 scope expands from scaffold to full service (port, Dockerfile,
  Flyway, Kafka producer, Keycloak client, health check, tests).
- `da-discovery` loses the `log/` package tree; downstream refactor
  touches `ILogAnalyzer` consumers that currently call into adapters
  directly.
- Port 8090 added to `DalabConstants` port registry; docker-compose
  and Terraform manifests updated.
- One more service to document, deploy, monitor, and scale.

---

## ADR-0009 — Gemini purged from docs/config/factory for v0.1.0

**Date**: 2026-04-21
**Status**: Accepted (resolves Q-002)
**Context**: `ILLMClient` Javadoc, CLAUDE.md §12, and
`application.properties` reference Gemini as a supported provider. No
`GeminiLLMClient` exists. Users configuring `llm.provider=gemini` hit a
`LLMClientFactory` fallback or runtime error. Shipping with false
configuration surface is worse than shipping without the feature.
**Decision**: Remove every Gemini reference from runtime code, config,
and docs for v0.1.0. Preserve the `ILLMClient` abstraction so a
`GeminiLLMClient` can be added in 0.2.0 without further refactoring.
**Consequences**:
- R-P1-1 retitled "Purge Gemini references (Q-002 resolution)."
- No Google Generative AI SDK dependency is added. Apache license
  compatibility review avoided for v0.1.0.
- `LLMClientFactory` drops the `gemini` case. `application.properties`
  Gemini keys removed. CLAUDE.md §12 lists only OpenAI and Ollama as
  shipped providers.
- 0.2.0 roadmap entry: implement `GeminiLLMClient` against the now-stable
  `ILLMClient` interface.

---

## ADR-0008 — Port `da-discovery` to Flyway (confirms ADR-0002)

**Date**: 2026-04-21
**Status**: Accepted (resolves Q-001; confirms provisional ADR-0002)
**Context**: ADR-0002 proposed standardizing on Flyway and flagged
`da-discovery`'s Liquibase stack as the exception to port.
Owner confirmed 2026-04-21: standardize on Flyway.
**Decision**: Port `da-discovery` from Liquibase to Flyway. All ~40
existing Liquibase changelogs under `src/main/resources/config/liquibase/`
collapse into a sequential Flyway series `V1__init_discovery_schema.sql`
through `V{N}__…`. Liquibase Gradle plugin, config, and changelog tree
are deleted after migration parity is confirmed against a fresh
database.
**Consequences**:
- ADR-0002 status upgraded from Provisional to Accepted.
- R-P0-4 unblocked. Port happens in Batch 5.
- CLAUDE.md §6 stands with no exception.
- `da-discovery/discard/changelog/00000000000000_initial_schema.xml`
  retired in Batch 1 (R-P1-3a) is orthogonal — it was already dead.
- Hibernate `ddl-auto` stays `validate` in prod, `none` in tests,
  across the port.

---

## ADR-0001 — `docs/` becomes canonical; `PROGRESS.md` is legacy

**Date**: 2026-04-18
**Status**: Accepted
**Context**: Prior project state was tracked across `PROGRESS.md`,
`docs/TODO.md`, `docs/pending-work.md`, `docs/tasks.md`,
`docs/workinglog.md`. Contradictions between these files were the
norm: `PROGRESS.md` marks M2 "secrets hardening" complete on 2026-04-12,
but `da-discovery/src/main/resources/config/application-dev.yml:167`
still carries a plaintext JWT secret.
**Decision**: `docs/` is the single source of truth going forward.
`OPERATING_PLAN.md` supersedes 
