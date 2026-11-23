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

# Backup and restore tools

The tools behind [`docs/guides/backup-and-restore.md`](../../docs/guides/backup-and-restore.md) (DR-01). Read that guide first: it has the state inventory, the targets (RPO 15 minutes, RTO 4 hours), the procedures and the measured results. This folder holds the code.

The backup agent runs as the `dlap-backup` service of the pilot overlay [`../environments/pilot/docker-compose.pilot.backup.yml`](../environments/pilot/docker-compose.pilot.backup.yml) and mounts this folder read-only at `/opt/dr`. Everything runs in the `postgres:15.1` image, the same image as the database.

| File | What it does |
| --- | --- |
| `agent.sh` | The sidecar's main process: streams WAL, schedules the backups, ships, trims, and answers `status --check` for the health check. `once <job>` runs one job. |
| `backup.sh` | Logical backup of every database: one `pg_dump -Fc -C` each, roles without passwords, row counts taken from the dumps, checksums. |
| `base-backup.sh` | Physical base backup (`pg_basebackup`), the start of a point-in-time restore. Also trims old sets and the WAL archive. |
| `ship.sh` | Copies sets and WAL off the VM through the operator's command (`DLAP_BACKUP_SHIP_CMD`), encrypted with the passphrase file. |
| `restore.sh` | Restores a logical set into a running PostgreSQL, checks the key fingerprint, and compares every table's row count with the set. |
| `restore-pitr.sh` | Prepares a data directory from a base backup plus WAL up to a time, an LSN, a named restore point or the latest moment. Keeps the longer of the base backup's and the archive's copy of a segment, and reads the prepared WAL with `pg_waldump` before it reports ready (Q-752). |
| `key-escrow.sh` | Fingerprint of `DLAP_ENCRYPTION_KEY`, check against backup sets, seal and unseal a file with a passphrase. |
| `rowcounts.sh` | Row counts (and content digests) of every table, to compare before and after. |
| `restore-drill.sh` | The database-tier drill (DR-01): build, fill, protect, destroy, restore two ways, compare. Uses `docker-compose.drill.yml`. |
| `stack-drill.sh` | The full-stack drill (DR-02): the real quickstart stack with its ten services, destroyed with its volumes and rebuilt from the off-host copy, with measured RTO and RPO, row digests, Flyway history, `audit-verify`, health and login checked. Uses `docker-compose.stack-drill.yml` and `docker-compose.stack-drill.restore.yml`. |
| `keycloak-realm.sh` | Backup and restore of the Keycloak realm of the quickstart (`start-dev`, embedded database, no volume): `kc.sh export` of a snapshot, encrypted, into the off-host store; `restore` decrypts into the import volume of a fresh Keycloak. |
| `keycloak-realm-backup.sh` | The scheduled wrapper of `keycloak-realm.sh` for the host's cron or systemd timer (Q-751): lock, export into a staging volume, ship through `DLAP_BACKUP_SHIP_CMD` with retry, retention, `status.env`, and `check` for monitoring. `cron/` and `systemd/` hold the files section 8.7 of the guide shows. |
| `test-dr.sh` | The named tests: static checks, the overlay model, `tests/unit.sh` against a real PostgreSQL, `tests/stack-unit.sh` (drill arguments, compose model, realm tool), and the drills. |
| `lib.sh` | Shared functions. |

```bash
deployment/dr/test-dr.sh --quick     # static checks, overlay model, container tests, stack tools: the quick set, 5 minutes (Docker)
deployment/dr/test-dr.sh             # plus the database-tier restore drill
deployment/dr/test-dr.sh --stack-drill   # plus the full-stack drill (needs the service jars)
deployment/dr/restore-drill.sh       # only the database-tier drill; prints the timings
bash deployment/dr/stack-drill.sh --out /tmp/drill-evidence
                                     # only the full-stack drill (about 11 minutes; it takes the host-wide harness lock itself)
DLAP_SHELLCHECK_IMAGE=rhysd/actionlint:1.7.12 deployment/dr/test-dr.sh --quick   # shellcheck without installing it
```

Exit status convention for every tool: 0 success, 1 a step failed, 2 a refusal or a bad argument (nothing was changed).
