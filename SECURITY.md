# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| 0.x (current branch) | Yes - active development; security fixes land on `main`. No LTS guarantee until v1.0. |
| Pre-0.1.0 snapshots | Best-effort; upgrade to latest `main` |

## Reporting a vulnerability

Do **not** open a public GitHub issue for security vulnerabilities.

1. Use GitHub private vulnerability reporting: open the repository's **Security** tab and choose **Report a vulnerability** (`https://github.com/Data-Automation-Lab/dlap/security/advisories/new`). Describe the issue, the affected components, and the steps to reproduce (proof-of-concept is welcome). The project has no security mailbox.
2. Expect an acknowledgment within **48 hours** (business days).
3. Expect a remediation plan or mitigation guidance within **14 days** for confirmed issues, or an explanation if the report is declined.

## Disclosure policy

- Coordinated disclosure: we work with reporters before public disclosure.
- Default embargo window: **90 days** from acceptance unless a shorter critical window is agreed (for example, active exploitation).
- Credit: we credit reporters in release notes unless they request anonymity.

## Scope

**In scope:** the **eleven** Spring Boot services in this repository, the two shared libraries, the React frontend, deployment manifests under `deployment/`, and any first-party scripts under `deployment/scripts` or `e2e-tests/` that ship with the repo.

Counted from `settings.gradle.kts`:

- **Ten services in the canonical dev compose** - `da-discovery`, `da-catalog`, `da-policyengine`, `da-reporting`, `da-autolabel`, `da-autoarchival`, `da-autodelete`, `da-autocompliance`, `da-admin-service`, `da-logingest`.
- **`da-mcp-pep`** - the eleventh Spring Boot service: the standalone reference MCP Policy Enforcement Point (ADR-0040). It is **not** in the dev compose and must be run separately, but it is first-party code and in scope.
- **`da-protos`** - shared entities and `DlapConstants`. Library, not a service.
- **`da-llm-client`** - shared LLM provider abstraction (`ILLMClient` / `LLMClientFactory`, ADR-0044). Library, not a service. Reports involving prompt handling or provider credentials belong here.
- **`new-ui`** - the React operator UI (a submodule; in scope when present in your tree).

**Out of scope:** third-party dependencies (report upstream), forks with custom patches, or deployments that diverge from documented configuration without merging security fixes from upstream.

## Threat model

[docs/architecture/threat-model.md](docs/architecture/threat-model.md) is the STRIDE threat model: a data-flow diagram, twelve trust boundaries, and every threat mapped to the hardening package that closes it or to an accepted risk. The pen-test scope and the SOC 2 risk assessment start from it.

What it means for a deployment today:

- **Run the quickstart only on a single-user machine or an isolated VM.** It ships seeded users with a known password, default admin passwords and dev secrets, publishes PostgreSQL, Kafka, Keycloak and every service on all host interfaces, and encrypts no hop (accepted risk AR-01).
- **Keep `da-autodelete` and `da-autoarchival` stopped wherever other people can reach the stack.** Kafka has no authentication, so a forged record on `dlap.policies.actions` can schedule a real cloud delete, and a lifecycle policy or a deletion task can delete without a second approver (threats B5-T1, B8-E1).
- **Turn on JWT issuer and audience validation** (`deployment/PRODUCTION.md` section 4). Both are off by default, so a token minted for any client of the realm is accepted (B3-S1).
- **Rotate the `dlap-backend` client secret.** Eight services share it, so one leak acts as all of them (B2-S6).
- **Configure the reference PEP's issuer and audience.** `da-mcp-pep` is a JWT resource server: it answers 401 without a token whose issuer and audience match its configuration, and it takes the subject from the token unless the caller is a service account. Earlier revisions of this file said it accepted unauthenticated requests (B10-S1); that was closed before this release.
- **Give discovery and log ingest a read-only cloud identity.** They run as the process identity, so any write permission it holds is reachable from those services (B7-E1).

A documented open threat is known. A report that shows one is exploitable in a configuration the documentation calls hardened, or that finds a threat the model does not list, is in scope.

## Known security considerations

- **Credential encryption in da-admin-service:** `BasicEncryptionServiceImpl` encrypts cloud-connection credentials with **AES-256/GCM** (`AES/GCM/NoPadding`, 12-byte random IV, 128-bit tag). All new writes use the `v2:` envelope. A **decrypt-only** legacy path remains for rows written by the old AES-128/ECB implementation (`v1:` and unprefixed Base64); those rows are lazily re-encrypted to GCM on the next write that touches them, so no migration job is required.

  *Correction:* earlier revisions of this file stated that da-admin-service still used AES/ECB and that startup emitted a WARN until migrated. Both are wrong. The GCM migration has shipped; startup logs at **INFO**, not WARN (`BasicEncryptionServiceImpl.init`), and the pointer to "M2 (Security hardening)" was dangling - **M2** in [docs/OPERATING_PLAN.md](docs/OPERATING_PLAN.md) is the *Log-event spine* milestone.

  The service **fails fast on startup** if `DLAP_ENCRYPTION_KEY` is missing or does not resolve to exactly 32 bytes. Use `openssl rand -hex 32`; a 32-character raw string is accepted for backward compatibility but carries roughly half the entropy.
- **Authentication:** Services use **Keycloak**-issued JWTs. `DlapConstants.UserRole` (`da-protos`) declares **ten** role constants: nine human personas across the four tiers of the ADR-0032 access model - `ADMIN`, `CDO` (Executive); `DIRECTOR`, `POLICY_MANAGER` (Leadership); `DATA_ENGINEER`, `DATA_SCIENTIST` (Practitioner); `DATA_ANALYST`, `AUDITOR`, `VIEWER` (Reader) - plus the machine principal `SERVICE_ACCOUNT`, which is deliberately outside the tier model and granted only an explicit per-endpoint allow-list (ADR-0034). Earlier revisions of this file said "eight roles".
- **Secrets:** Production paths must use environment variables or Spring `${ENV:default}` placeholders - see [.env.example](.env.example). The shipped Keycloak realm's `dlap-backend` client secret defaults to the literal `change-me-dev-only` and **must** be rotated before any non-dev deployment - see [docs/launch/keycloak-prod-setup.md](docs/launch/keycloak-prod-setup.md).

## Transport & at-rest encryption

**The quickstart enables no transport encryption; the read-only pilot encrypts every hop.** The compose stack in `deployment/environments/dev/` is a local development stack and stays plain text by design. The pilot (`deployment/environments/pilot/`, ADR-0049 model M3) applies the TLS overlay `docker-compose.pilot.tls.yml` on top of it, which encrypts every hop of the default profiles, verifies every server certificate and authenticates every client, through configuration only (SEC-06; [docs/guides/transport-security.md](docs/guides/transport-security.md)). `deployment/environments/pilot/tls/smoke-tls.sh` proves each hop on a running pilot. The only other TLS server the repository can run is the opt-in `tls` profile of `da-discovery` (see [TLS profile](#tls-profile-da-discovery)).

| Channel | Quickstart (`deployment/environments/dev/`) | Pilot with the TLS overlay |
|---|---|---|
| Browser → gateway | nginx on plain HTTP `8081` (`gateway/nginx.conf`) | TLS 1.2/1.3 on one entry point with HSTS and a CSP (`tls/nginx.pilot.tls.conf`); no plain-HTTP listener |
| Gateway → UI and services | plain HTTP | TLS; every upstream certificate verified against the pilot CA |
| Service → Keycloak, browser → Keycloak | `http://keycloak:8080`, `start-dev`, `KC_DB=dev-file`; port `8443` published without a certificate | HTTPS only (`KC_HTTP_ENABLED=false`); browsers reach Keycloak on the same origin through the gateway |
| Service → service | plain HTTP with the shared service token | `https://` URLs, verified through a JVM trust store of the JDK roots plus the pilot CA |
| Service → Kafka | every listener `PLAINTEXT`; no SASL, no ACLs | `SASL_SSL` with `SCRAM-SHA-512`, one user per service, deny-by-default ACLs per topic (`tls/kafka-acls.txt`); controller and inter-broker listeners on mutual TLS; no plain-text listener |
| Service → PostgreSQL | JDBC URLs without `sslmode` | `sslmode=verify-full` on every JDBC URL; the server refuses every TCP connection without TLS (`tls/pg_hba.conf`) |
| JWT validation | signature and expiry only (issuer and audience checks off) | issuer **and** audience checked by every service (`dlap-api`; the realm must carry the audience mapper, see the guide) |
| PEP → PDP | `dlap.pdp.base-url` defaults to `http://localhost:8083` | not part of the pilot stack; use an `https://` PDP URL |

The pilot overlay leaves the optional `monitoring` scrape path (Prometheus, Grafana, Alertmanager), the optional Ollama of the `llm` profile and the development emulators on plain HTTP; the guide lists every such hop and why ("Outside the overlay"). There is no Helm chart yet, so no Kubernetes transport configuration either.

**At rest**, the only encryption the repository implements is **application-level**: the AES-256/GCM credential encryption in `da-admin-service` described above, which protects cloud-connection secrets stored in Postgres. DLAP configures **no** database-level, volume-level or object-storage encryption - disk and backup encryption are the operator's responsibility, as is key management for `DLAP_ENCRYPTION_KEY` (the compose reads it from the environment; use a secret manager in production).

Deployments that expose DLAP beyond a single trusted host without the pilot TLS overlay or an equivalent (every row of the table above) are running unauthenticated-in-transit infrastructure, and reports of that configuration are **out of scope** as a documented default rather than a vulnerability. See `deployment/PRODUCTION.md` and [docs/guides/transport-security.md](docs/guides/transport-security.md).

## TLS profile (da-discovery)

`da-discovery` has one opt-in Spring profile, `tls`, that serves HTTPS with HTTP/2 (`da-discovery/src/main/resources/config/application-tls.yml`). It is off by default (`config/application.yml` keeps `- tls` commented out), no compose file activates it, and no other service has a TLS profile.

The repository ships **no keystore**. To use the profile, add `tls` to the active profiles (for example `SPRING_PROFILES_ACTIVE=dev,tls`) and set both variables. Neither has a default:

| Variable | Value |
|---|---|
| `DLAP_TLS_KEYSTORE_PATH` | Path to a PKCS12 keystore holding one key entry, kept outside the repository |
| `DLAP_TLS_KEYSTORE_PASSWORD` | Password of that keystore. Supply it from a secret store, never from a tracked file |

Create the keystore with the JDK `keytool`, which prompts for the password so none lands in shell history, or convert your CA-issued certificate to PKCS12:

```bash
keytool -genkeypair -alias dlap -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=<host>" -ext "SAN=dns:<host>" \
  -storetype PKCS12 -keystore /secure/path/keystore.p12
```

The profile **fails fast**. When a variable is unset, Spring Boot keeps the literal `${...}` text as the property value and the embedded server aborts startup while loading the keystore, so nothing is served over a missing or default key:

| Startup error | Cause |
|---|---|
| `Could not load store from '${DLAP_TLS_KEYSTORE_PATH}'` | `DLAP_TLS_KEYSTORE_PATH` is unset |
| `Could not load store from '<path>'` with `keystore password was incorrect` | `DLAP_TLS_KEYSTORE_PASSWORD` is unset or wrong |

`TlsProfileTest` (`da-discovery/src/test/java/com/dlap/discovery/config/TlsProfileTest.java`) covers both failures, serving HTTPS with a generated keystore, and the absence of key material under `src/main/resources`.

## Key material previously committed (burned)

A self-signed TLS keystore, `da-discovery/src/main/resources/config/tls/keystore.p12`, was committed in `3f2c92c` (2026-04-12) and stayed in the tree until the change that added this section. Its password (`password`) was committed in plaintext in `application-tls.yml` in the same commit. From `a24e7e9` (2026-04-20) it remained as the default of `${SERVER_SSL_KEY_STORE_PASSWORD:password}`, until `2e9dd03` (2026-07-03) removed the default. **Treat the key as burned: it is known to everyone who had read access to the private pre-release repository. This public repository started from a fresh commit and has never contained the file.** The commit ids in this section are from that private history.

| Item | Detail |
|---|---|
| Keystore | PKCS12, alias `selfsigned`, one private key entry (2048-bit RSA, SHA256withRSA) |
| Certificate | Self-signed, `CN=Java Hipster, OU=Development, O=org.aialabs.dg.crawler` (the JHipster scaffold certificate) |
| Validity | 2024-07-15 to 2298-04-29 |
| SHA-256 fingerprint | `33:7B:CB:85:29:E7:D3:74:86:46:76:18:73:30:BA:AE:55:A9:7B:D9:74:A0:43:8C:E8:B9:BE:2C:B5:BF:11:20` |

- **Burned.** The file and its password remain in the history of the private pre-release repository, which was not rewritten. This public repository is a fresh cut without that history and does not carry the file. Assume the key is public all the same.
- **Rotated out.** The file is gone from the tree. `application-tls.yml` has no default keystore path, alias or password, and the profile refuses to start without an operator keystore (previous section). The repository holds no replacement key, by design: every deployment generates its own. The old key only ever protected the opt-in `tls` profile, which no compose file or image activates.
- **If you used it.** Stop trusting or pinning the fingerprint above, remove it from every trust store, and generate a fresh keystore as shown in the previous section.
- **Guards.** `.gitignore` ignores `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.truststore`, `*.pem`, `*.key` and `*.p8`. `TlsProfileTest` fails if key material appears under `da-discovery/src/main/resources`. A release check: `git ls-files | grep -iE '\.(p12|jks|pem|key)$'` must print nothing.

## Dependency policy

Apache License 2.0-compatible dependencies only. Run `./gradlew dependencyInsight` when evaluating new libraries.
