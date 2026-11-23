<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Transport security in the pilot

How the read-only pilot (ADR-0049 model M3, `deployment/environments/pilot/`) encrypts every hop and
authenticates every client. It is the TLS overlay `docker-compose.pilot.tls.yml` plus the tools in
`deployment/environments/pilot/tls/` (SEC-06). The quickstart stack in `deployment/environments/dev/`
stays plain text by design (SECURITY.md, "Transport & at-rest encryption").

The overlay changes configuration only: compose environment variables, mounted files and one-shot
containers. No application file, image or Java class changes.

## Trust model

- **One private CA per pilot.** `tls/gen-certs.sh` creates it on the pilot VM. It signs one certificate
  per compose name (`postgresql`, `kafka`, `keycloak`, `new-ui`, `gateway` and the ten `da-*` services)
  and two client certificates for the broker's mutual-TLS listeners (`kafka`, `dlap-kafka-admin`). The CA
  key (`generated/ca/ca.key`) never enters a container; keep a copy offline once the pilot runs.
- **The entry point can carry the customer's certificate.** `--public-cert` and `--public-key` put the
  customer's own certificate (for example from their internal CA) on the gateway. Every other
  certificate stays on the pilot CA, which only the pilot's containers trust.
- **Every client verifies its server.** nginx checks each upstream for the name in `proxy_pass`; the
  JDBC driver uses `sslmode=verify-full`; the Kafka clients check the broker for the name `kafka`; the
  JVMs check Keycloak and each other through a trust store holding the JDK's public roots plus the pilot
  CA.
- **Clients authenticate.** Browsers and services present Keycloak tokens whose issuer **and audience**
  every resource server checks; services log in to PostgreSQL with SCRAM passwords and to Kafka with one
  SCRAM user each; the broker and the admin tool use certificates.
- **Each private key reaches one container.** `tls-init` copies the generated files into the volume
  `pilot-tls` as `root:<group> 0440`, one group per consumer, and each container joins only its own
  group (`group_add`). A Spring service cannot read another service's key, although all of them run as
  uid 65532. PostgreSQL's key belongs to its user (uid 999, `0600`), because PostgreSQL refuses a key its
  group can read and drops supplementary groups.

## Every hop

| Hop | Mechanism | Plain-text door | Smoke check (`smoke-tls.sh`) |
|---|---|---|---|
| Browser to gateway | nginx TLS 1.2/1.3 on one port (`PILOT_TLS_BIND_ADDRESS:PILOT_TLS_PORT`, default `127.0.0.1:443`); HSTS, CSP, X-Frame-Options, nosniff, Referrer-Policy, Permissions-Policy (Q-190) | No plain-HTTP listener; plain text on the TLS port gets `400` and no content | `browser -> gateway` |
| Gateway to new-ui and to every service | `proxy_pass https://<name>`, `proxy_ssl_verify on` with the pilot CA | The services serve TLS only on their port (Tomcat answers plain text with `400 ... requires TLS`) | `gateway -> *`, `peer -> da-*` |
| Browser to Keycloak | Same origin: the gateway proxies `/realms/` and `/resources/` to `https://keycloak:8443`; `/realms/master/` is not served | Keycloak's HTTP port is off (`KC_HTTP_ENABLED=false`) | `browser -> keycloak` |
| Service to Keycloak (keys, tokens) | `https://keycloak:8443` in `KEYCLOAK_AUTH_SERVER_URL`, the JWK set URI and every `token-uri` | as above | `service -> keycloak` |
| Service to service (Feign, RestClient) | `https://` URLs in each `SPRING_APPLICATION_JSON`; the JVM trust store (`JDK_JAVA_OPTIONS`) | as gateway row | `peer -> da-*`, `logs` |
| Service to PostgreSQL | `?sslmode=verify-full&sslrootcert=/tls/ca.crt` on every `SPRING_DATASOURCE_URL`; server `ssl=on`, TLS 1.2 minimum | `tls/pg_hba.conf`: every TCP connection without TLS is rejected, loopback included; only the Unix socket inside the container is exempt | `service -> postgres` (incl. `pg_stat_ssl` for every service database) |
| Service to Kafka | `SASL_SSL`, `SCRAM-SHA-512`, one user per service, ACLs per topic (`tls/kafka-acls.txt`) | No `PLAINTEXT` listener; no published port | `service -> kafka`, `kafka acl` |
| Kafka to Kafka | Controller and inter-broker listeners are SSL with a required client certificate; the broker and `dlap-kafka-admin` are the only super users | none | `kafka -> kafka` |
| Monitoring profile exporters | postgres-exporter `verify-full`; kafka-exporter SASL_SSL as `dlap-kafka-exporter` (describe only) | as above | static checks |

`smoke-tls.sh --static` checks the merged compose model for every row without running anything (each
service's TLS listener, JDBC URL, Kafka settings, issuer and audience settings, trust store, that no
`http://` URL is left and that no `SPRING_APPLICATION_JSON` key of the quickstart went missing).

## Set it up

From the repository root, on the pilot VM:

```bash
./gradlew build -x test
deployment/environments/pilot/tls/gen-certs.sh --public-host pilot.customer.internal
# or, with the customer's own certificate for the entry point:
#   gen-certs.sh --public-host pilot.customer.internal --public-cert entry.crt --public-key entry.key
```

Add to `deployment/environments/pilot/.env.pilot`:

```properties
PILOT_PUBLIC_ORIGIN=https://pilot.customer.internal   # what the browsers type; :port when not 443
PILOT_TLS_BIND_ADDRESS=0.0.0.0                         # default 127.0.0.1 (SSH tunnel only)
PILOT_TLS_PORT=443
# PILOT_TLS_DIR=/srv/dlap-pilot-tls                    # when gen-certs.sh wrote elsewhere (--out)
# PILOT_JWT_AUDIENCE=dlap-api                          # the default
```

Then `deployment/environments/pilot/up.sh` as usual. `tls-init` stops the start, naming
`gen-certs.sh`, when the key material is missing. Check the running pilot:

```bash
deployment/environments/pilot/tls/smoke-tls.sh
```

Through an SSH tunnel instead of an open port: keep `PILOT_TLS_BIND_ADDRESS=127.0.0.1`, set
`PILOT_PUBLIC_ORIGIN=https://localhost:8443`, run `ssh -L 8443:127.0.0.1:443 <vm>` and open
`https://localhost:8443` (the gateway certificate names `localhost`; import `generated/ca/ca.crt` into the
browser or accept it once).

The browser-side origin matters twice: Keycloak writes it into the `iss` of browser tokens, and the
services accept exactly `PILOT_PUBLIC_ORIGIN/realms/dlap` and `https://keycloak:8443/realms/dlap`. A
browser that reaches the pilot under another name gets tokens the services refuse (401).

The SPA image carries the quickstart's build-time origins (`http://localhost:8081` for the API and
`http://localhost:8180` for Keycloak). The gateway rewrites both, in the JavaScript it serves, to the
origin of the request, so the pilot needs no UI build of its own; the smoke test checks that the bundle
names no plain-HTTP origin.

## JWT issuer and audience

Every resource server (the ten `da-*` services) runs with

```properties
DLAP_SECURITY_JWT_VALIDATE_ISSUER=true
DLAP_SECURITY_JWT_EXPECTED_ISSUER=${PILOT_PUBLIC_ORIGIN}/realms/dlap,https://keycloak:8443/realms/dlap
DLAP_SECURITY_JWT_VALIDATE_AUDIENCE=true
DLAP_SECURITY_JWT_EXPECTED_AUDIENCE=dlap-api
```

**Every service validates the audience in the pilot** (decision for the companion of Q-459). The
reason is the `dlap-mcp-gateway` client of PEP-02: its tokens carry `ROLE_SERVICE_ACCOUNT` and the
audience `dlap-mcp-pep`, and are handed to third-party MCP gateways. Without an audience check every
service would accept such a token on its machine-to-machine endpoints. With the check, the MCP
gateway's tokens open the PEP and nothing else, and a token for any other client of the realm opens
nothing. `da-mcp-pep` keeps its own audience (`dlap-mcp-pep`, ADR-0084).

This needs the realm to put `dlap-api` into the access tokens of `dlap-ui` (browsers) and
`dlap-backend` (service accounts), with an `oidc-audience-mapper` (`included.custom.audience:
dlap-api`, access token only) on each of the two clients. The quickstart realm has no such mapper today:
until it, or the production realm template, has one, every request to a service under the overlay is
refused with `401` (fail closed), and `smoke-tls.sh` reports `dlap-backend token carries the audience
dlap-api` as FAIL with the fix. `tls-e2e.sh` rehearses with a copy of the quickstart realm that has the
mapper and says so in its output.

The smoke test proves the checks per service with three probes on an unknown path: no token (`401`),
a `dlap-backend` token (accepted: not `401`), a `dlap-mcp-gateway` token (`401`, `aud` named in
`WWW-Authenticate`) and a `dlap-backend` token minted for a foreign issuer name (`401`, `iss` named).
The foreign-issuer probe is skipped when Keycloak fixes its host name (production mode with
`KC_HOSTNAME`), because no token with a foreign issuer can be minted then.

## Kafka users and ACLs

The broker has three listeners: `SASL_SSL` (`kafka:29092`, SCRAM-SHA-512, for the services), `SSL`
(`kafka:29094`, mutual TLS, inter-broker and admin) and `CONTROLLER` (`kafka:29093`, mutual
TLS). Authorization is `StandardAuthorizer` with `allow.everyone.if.no.acl.found=false`: a request without
a matching ACL is denied.

`kafka-init` runs after the broker is healthy and before any service starts. It creates one SCRAM user
per file in `generated/scram/` and adds the ACLs of `tls/kafka-acls.txt`:

| Verb | Grants |
|---|---|
| `consume T` | Read, Describe and Create on T; Write, Describe and Create on `T.DLT`. Create because a listener that subscribes before the producer has created the topic makes the broker create it; without the grant the broker answers `TOPIC_AUTHORIZATION_FAILED` and Spring stops the listener for good (seen in the first rehearsal). A consumer still cannot write to T. |
| `produce T` | Write, Describe and Create on T |
| `provision T` | Create, Describe, DescribeConfigs and Alter on T and `T.DLT` |
| `group P` | Read on every consumer group whose id starts with P |
| `describe *` | Describe and DescribeConfigs on every topic, Describe on every group and on the cluster |

The file is checked against the code by `tls/test-tls.sh` (T2): every `@KafkaListener` topic and group,
every `KafkaTemplate` topic and every topic a `KafkaTopicsConfiguration` declares must be granted to its
service. It also pins who may write the two topics where a forged record does the most harm (threat
model B5-T1, B5-T2): `dlap.policies.actions` only `da-policyengine` and `da-autocompliance` (its
compliance label actions), `dlap.log.event.normalized` only `da-logingest` and `da-discovery` (its
read-coverage observations, COV-01). A new listener or topic without a line in the file fails T2.

ACLs are only added. To take one away, delete its line and remove it on the broker, for example:

```bash
up.sh exec kafka kafka-acls --bootstrap-server kafka:29094 --command-config <admin.properties> \
  --remove --force --allow-principal User:da-catalog --operation Write --topic <topic>
```

(`<admin.properties>` as in `kafka-init.sh`, with the `dlap-kafka-admin` keystore.)

## Renew and rotate

| What | How |
|---|---|
| Leaf certificates (397 days by default) | `gen-certs.sh --renew`, then `up.sh up -d --force-recreate tls-init` and restart the containers (`up.sh restart`) |
| The CA (10 years by default) | `gen-certs.sh --force` makes a new CA, certificates and passwords; restart the whole pilot. Browsers that trusted the old CA must trust the new one |
| A service's Kafka password | delete `generated/scram/<service>.password`, run `gen-certs.sh`, `up.sh up -d --force-recreate tls-init kafka-init <service>` |
| The expiry dates | `generated/MANIFEST` lists subject, names, expiry and fingerprint of every certificate |

## Outside the overlay

These are not encrypted, or not verified, by this overlay. Each is a reasoned choice or an open item.

| Hop | State | Why, and who closes it |
|---|---|---|
| Prometheus to the services, Grafana and Alertmanager (profile `monitoring`) | Plain HTTP on the internal network; the platform `prometheus.yml` scrapes `http://` targets | Metrics carry no token or record content. The scrape configuration is not part of this overlay (open question). |
| da-autolabel to Ollama (profile `llm`) | Plain HTTP (`DLAP_AUTOLABEL_OLLAMA_BASE_URL`) | Ollama has no TLS listener. The pilot labels with the rules provider (ADR-0052); enable `llm` only with a TLS proxy in front of Ollama. |
| LocalStack and Unity Catalog OSS (profiles `aws`, `unity`) | Plain HTTP | Development emulators; never with customer data. |
| The backup agent (DR-01) to PostgreSQL | TLS without certificate verification (libpq `sslmode=prefer` on the shared loopback) | The connection never leaves the database container's network namespace. |
| Keycloak to its database | Keycloak uses `KC_DB=dev-file` in the base pilot | When the production Keycloak overlay (IAM-02) moves it to PostgreSQL, its `KC_DB_URL` must carry `sslmode=verify-full&sslrootcert=/tls/ca.crt` (pg_hba.conf already refuses it without TLS) (open question). |
| The services to the cloud APIs | TLS by the provider SDKs, verified with the JDK's public roots (kept in the JVM trust store) | Unchanged. |
| Secrets in the container environment | The Kafka SCRAM login of each service is in its environment, like the other secrets (threat model B11-I1) | `docker inspect` shows it to whoever controls the Docker daemon, who also controls the volumes. |
| Keycloak's own HTTP listener while it runs `start-dev` | Open on `keycloak:8080` inside the compose network | Keycloak's development mode always opens it: `KC_HTTP_ENABLED=false` and `--http-enabled=false` are ignored there (checked on 23.0.1). No service and no route of the gateway uses it. The quickstart realm path (`PILOT_ALLOW_DEV_REALM=1`) is a lab setting; the production Keycloak overlay (IAM-02, `kc.sh start`) honours `KC_HTTP_ENABLED=false`. `smoke-tls.sh` reports it as WARN while Keycloak runs `start-dev`. |

The Keycloak container healthcheck only opens the TLS port (the image has no TLS client); the smoke
test checks the real answer.

## Audit log route

The policy engine appends to the tamper-evident audit log in `dlap_common` (docs/specs/audit-log.md)
on its own connection, next to its own database: every approval decision (approve, reject,
break-glass) and every withdrawal goes to the stream `govern.approval`, and every policy change
(create, update, status change, delete) to `govern.policy` (AUD-02a). Each of these fails closed: when
the append cannot be made, the change is not committed and the call answers 503 `audit-unavailable`.
So the policy engine needs a working route to `dlap_common` wherever approvals or policy edits are used
(Q-690).

| Setting (environment) | Pilot base overlay | TLS overlay | Default when unset |
|---|---|---|---|
| `dlap.audit.datasource.url` (`DLAP_AUDIT_DATASOURCE_URL`) | `jdbc:postgresql://postgresql:5432/dlap_common` | `jdbc:postgresql://postgresql:5432/dlap_common?sslmode=verify-full&sslrootcert=/tls/ca.crt` | `spring.datasource.url` with the database name replaced by `dlap_common`, query string kept |
| `dlap.audit.datasource.username` (`DLAP_AUDIT_DATASOURCE_USERNAME`) | unset | unset | the service's own `spring.datasource.username` |
| `dlap.audit.datasource.password` (`DLAP_AUDIT_DATASOURCE_PASSWORD`) | unset | unset | the service's own `spring.datasource.password` |

What the route needs, all present in the platform init scripts the pilot mounts:

- the role is `da_policyengine_user`, the registered writer of both streams (`audit_streams.writer_role`);
  any other role is refused by the insert trigger, so do not point the setting at a shared or admin user;
- `GRANT CONNECT ON DATABASE dlap_common TO da_policyengine_user` (`01-create-schemas-and-users.sql`)
  and SELECT plus INSERT on `dlap_common.audit_log` (the audit block of `02-create-common-schema.sql`);
- in the TLS overlay, `tls/pg_hba.conf` admits the connection only over TLS (`hostssl all all all
  scram-sha-256`), and the URL verifies the server against the pilot CA like `SPRING_DATASOURCE_URL`.

**Helm or production.** Set `DLAP_AUDIT_DATASOURCE_URL` explicitly to the `dlap_common` database with
`sslmode=verify-full` and the CA of the database server. When `dlap_common` lives on another server than
the policy engine's own database, the derived default points at the wrong server; set the URL, and set
the user and password to the `da_policyengine_user` credentials of that server. A managed PostgreSQL
offers no true superuser for the audit delta (Q-567), so create the audit tables there before the first
start. Check the route after a deployment with one approval decision or policy edit and
`SELECT stream_id, max(seq) FROM dlap_common.audit_log GROUP BY stream_id`, or run
`scripts/audit-verify.sh`.

Rehearsal, 2026-11-01 (AUD-02a): `tls-e2e.sh --keep` on Docker Desktop (Windows 11), pilot up after
314 s, `smoke-tls.sh` 343 checks, 0 failed, 0 warnings. On that pilot the `dlap-backend` service account
(token through the gateway, verified against the pilot CA) created a HARD_DELETE request (201) and
withdrew it (200, `WITHDRAWN`; a second withdraw 409). `dlap_common.audit_log` then held
`govern.approval.withdrawn` under the key `<id>:withdrawn` at seq 1, written over the verify-full URL
above (the only TCP route `pg_hba.conf` admits is `hostssl`), and `scripts/audit-verify.sh` reported
6 streams verified, 0 broken.

## Helm

There is no Helm chart yet (DEP-02 follows P1). A chart needs the same pieces: a Secret per service with
its certificate and key (cert-manager with a private `Issuer` is the natural source), the pilot CA as a
ConfigMap, the same environment variables, a Job in place of `kafka-init` (or the operator's own Kafka
user and ACL resources, for example Strimzi `KafkaUser`), PostgreSQL with `hostssl` only, and
NetworkPolicies that admit only the hops in the table above. These are recorded as open questions for
DEP-02.

## Rehearsal, 2026-10-20

`tls-e2e.sh --no-build` on Docker Desktop 28.3.0 (Windows 11, 20 CPUs, 15.5 GiB for Docker), the
quickstart realm with the rehearsal audience mapper, every default-profile service:

- the pilot was up 264 s after `up.sh up -d` (every container running, every healthcheck healthy; the
  DR-01 backup agent is only required to run);
- `smoke-tls.sh`: 292 checks, 0 failed, 1 warning (Keycloak's development-mode HTTP listener, above);
- `pg_stat_ssl`: 28 TCP sessions, all TLS 1.3, at least 3 per service database;
- Kafka: 11 SCRAM users, 401 ACL entries listed by the broker, consumer groups with members for all
  six consuming services; every service refused a token without `dlap-api` (401) and one of a foreign
  issuer (401), and accepted the `dlap-backend` token;
- memory after the smoke test (`docker stats`): the ten JVMs 422 to 656 MiB each, Kafka 548 MiB,
  Keycloak 481 MiB, PostgreSQL 299 MiB, new-ui 210 MiB, nginx 3 MiB; all within the base overlay's limits.

The monitoring exporters were checked by hand on the same stack: `kafka_brokers 1` from the Kafka
exporter (SASL_SSL as `dlap-kafka-exporter`) and `pg_up 1` from the PostgreSQL exporter (`verify-full`).

The DR-01 restore drill (`deployment/dr/restore-drill.sh`) builds its database tier from every
`docker-compose.pilot.*.yml`, so it runs under this overlay too: it creates key material of its own
with `gen-certs.sh --out <work>/tls --force`, points `PILOT_TLS_DIR` at it and deletes it at the end.
On 2026-10-20 (`bash deployment/dr/test-dr.sh`, 20000 events): 31 drill checks passed, 0 failed,
255 s, including "the agent's WAL stream runs over TLS" and "no TCP session of stack A is without
TLS"; the logical and both point-in-time restores matched every table under the TLS-only `pg_hba.conf`.

## Verify

| Command | What it shows |
|---|---|
| `deployment/environments/pilot/tls/test-tls.sh` | The tools on their own: certificates, keystores, the passwordless JVM trust store, file ownership after `tls-init`, PostgreSQL with `pg_hba.conf`, `nginx -t`, the ACL file against the code, `kafka-init` in dry-run mode. Docker for the container part; `--quick` without. |
| `deployment/environments/pilot/tls/smoke-tls.sh --static` | The merged compose model, offline. |
| `deployment/environments/pilot/tls/smoke-tls.sh` | Every hop of a running pilot. |
| `deployment/environments/pilot/tls/tls-e2e.sh` | A throwaway pilot (own compose project, fresh secrets and certificates) started with `up.sh`, checked with the smoke test and removed, under the host-wide harness lock. |
| `deployment/environments/pilot/verify.sh` | DEP-01's overlay checks, which still pass with the TLS overlay (loopback ports, limits, prod profile). |
