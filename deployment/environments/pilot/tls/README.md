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

# Pilot TLS overlay: tools

The files behind `../docker-compose.pilot.tls.yml` (SEC-06). The guide is
[`docs/guides/transport-security.md`](../../../../docs/guides/transport-security.md).

| File | Role |
|---|---|
| `gen-certs.sh` | Run once on the pilot VM: the pilot CA, one certificate per compose name, the Kafka keystores, the JVM trust store, the SCRAM passwords and the per-service Kafka logins, into `generated/` (never committed). |
| `tls-init.sh` | The overlay's first container: copies `generated/` into the volume `pilot-tls` and gives every private file to one container's group. |
| `kafka-init.sh`, `kafka-acls.txt` | The one-shot container that creates the SCRAM users and the topic ACLs before any service starts, and the ACL matrix it reads. |
| `pg_hba.conf` | PostgreSQL refuses every TCP connection without TLS. |
| `nginx.pilot.tls.conf` | The entry point: TLS only, verified TLS upstreams, Keycloak on the same origin, security headers. |
| `smoke-tls.sh` | Proves every hop of a running pilot; `--static` checks the merged compose model offline; `--executors` also makes `da-autoarchival` and `da-autodelete` call `GET /approvals` on the policy engine over HTTPS (check H7; it writes two refused probe rows, so use it on a rehearsal only). |
| `tls-e2e.sh` | A throwaway pilot started with `../up.sh`, checked with `smoke-tls.sh` and removed, under the harness lock. `--monitoring` also starts the `monitoring` and `actions` profiles and asks Prometheus whether every target is up. |
| `test-tls.sh` | Tests of the tools above, and of `kafka-acls.txt` against the code. |

```bash
deployment/environments/pilot/tls/gen-certs.sh --public-host <name the browsers use>
deployment/environments/pilot/up.sh
deployment/environments/pilot/tls/smoke-tls.sh
```

Two details of the overlay worth knowing before you read it:

- **Realm `dlap` is imported with `sslRequired` `all`** (Q-514, D31). The Keycloak overlay imports with `external`; this overlay sets `DLAP_REALM_SSL_REQUIRED=all` for Keycloak, whose HTTP listener is off anyway. The import happens once, so a realm that exists keeps its setting. `smoke-tls.sh` reports the live value.
- **The action executors reach the policy engine over HTTPS** (WIRE-01, Q-602, Q-604, Q-613). With the `actions` profile on, `da-autoarchival` and `da-autodelete` re-read every approval at `https://da-policyengine:8080` before they act. Both default to plain HTTP (the dev and e2e compose), so the overlay names the URL in each service's `SPRING_APPLICATION_JSON` (`dlap.services.da-policyengine.url` and `dlap.policyengine-service.url`). The pilot CA is trusted through `JDK_JAVA_OPTIONS`, which gives the JVM the trust store `/tls/jvm-truststore.p12`; the archive client is a plain JDK `HttpClient` and takes its trust from the JVM default, so a deployment that replaces `JDK_JAVA_OPTIONS` must keep a trust store that holds the CA. Both services run as the `dlap-backend` client, whose tokens carry `ROLE_SERVICE_ACCOUNT`, which `GET` and `POST /approvals` need. Their Kafka grants are in `kafka-acls.txt` (`da-autoarchival`: `dlap.policies.actions`, `dlap.approvals.decided`, `dlap.log.event.normalized`; `da-autodelete`: `dlap.policies.actions`, `dlap.discovery.asset.deleted`; `consume` includes Write on the `.DLT` topics; groups by prefix).
- **The management port 9091 is TLS too** (Q-518). Every Spring service serves `/actuator/health` and `/actuator/prometheus` without a token on that port, over TLS with its own certificate, to the compose network only. The application port serves no actuator endpoint. Prometheus (profile `monitoring`) runs [`prometheus.tls.yml`](../../../platform/prometheus/prometheus.tls.yml), scrapes `https://<service>:9091` and verifies the certificate against the pilot CA; Alertmanager, Grafana and the two exporters stay plain HTTP on the container network.
