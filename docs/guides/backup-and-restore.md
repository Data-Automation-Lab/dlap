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

# Backup and restore

What is stored where in a DLAP deployment, how it is backed up, how to get it back, and what was measured. It covers the read-only pilot (model M3, one VM, the quickstart pair plus the pilot overlays) and anything built on the same compose files. The tools are in [`deployment/dr/`](../../deployment/dr/README.md); the backup agent is the `dlap-backup` service of [`docker-compose.pilot.backup.yml`](../../deployment/environments/pilot/docker-compose.pilot.backup.yml).

**Status.** Tested against a real PostgreSQL 15.1 in containers, by a drill of the database tier (section 9), and by a drill of the whole quickstart stack (section 9.3): the real compose files, PostgreSQL, Kafka, Keycloak and the ten Java services are destroyed with every volume and rebuilt from the off-host copy alone, with the restore time (RTO) and the data point it stands at (RPO) measured as numbers. Not yet exercised on a customer VM or on the e2-standard-2 of the hosted demo. Numbers in this guide say where they were measured.

## 1. In short

- **Protected:** every PostgreSQL database (the 11 of the quickstart, plus any added later, such as the Keycloak database of IAM-02). That holds everything DLAP decides and remembers: the catalog, policies, reports, discovery state, the log replay store with its source cursors, the schema upgrade ledger.
- **Not protected, by design:** Kafka (events in flight), Prometheus and Grafana data, Ollama models. Section 3 says what each loss costs.
- **Keycloak.** In the pilot (IAM-02) Keycloak runs on PostgreSQL and the backups carry it. In the quickstart it runs on an embedded database inside the container, which the PostgreSQL backups do not carry: the realm file in git is re-imported at every start, but users and changes made at run time are lost when the container is recreated. `deployment/dr/keycloak-realm.sh` exports the whole realm (users with their password hashes, roles, clients and secrets), encrypted, and the full-stack drill restores it (sections 8.6 and 9.3). The backup agent has no access to Docker, so the export runs from the host: `deployment/dr/keycloak-realm-backup.sh` is the scheduled wrapper, with a cron file and a systemd timer in section 8.7. Until it is scheduled, the realm stands only at its last export (Q-751).
- **Three layers:** a continuous copy of the write-ahead log, a base backup each week, and a logical backup of each database each day. The log and the base backup give a restore to any moment (RPO 15 minutes); the logical backup is the simple restore and the portable one.
- **Off the VM:** the agent copies everything, encrypted, through a command the operator supplies. Without it the backups die with the VM, and `status` says so.
- **One secret is not in any backup:** `DLAP_ENCRYPTION_KEY`. Without it the stored cloud credentials cannot be read. Escrow it (section 7).
- **Do three things before go-live:** configure the off-host command and the passphrase (section 5), escrow the key and the environment file (section 7), and run the drill once on the real VM (section 9).

## 2. Targets and how they are met

| Target | Value | What meets it | Evidence |
| --- | --- | --- | --- |
| RPO, loss of the database disk | 15 minutes | `pg_receivewal` streams WAL to the backup volume with a synchronous flush, so the local archive receives each change as the server produces it (test P-07 measures the lag). The agent copies new WAL off the VM every `DLAP_WAL_CYCLE_SECONDS` (default 300). The off-host copy is therefore never more than one cycle plus the upload time behind. | `status` fails when it is more than `DLAP_RPO_SECONDS` (900) behind (test A-06). Drill: the off-host copy covered the last commit 34 s after it with a 30 s cycle, and 59 s in the large run, which wrote 3.9 GiB of WAL in four minutes (section 9.1). With the default 300 s cycle expect up to 300 s plus the upload. |
| RPO, loss of the whole VM | 15 minutes | The same, because the copy is off the VM. It holds only if `DLAP_BACKUP_SHIP_CMD` is set. | `status` warns when it is not (test A-08). |
| RTO | 4 hours | Provision a VM, start the database, restore, start the services. The restore is the part this package can measure; the rest is cloud and build time. | Section 2.1. Drill timings in section 9. |

Where PostgreSQL itself is down but its disk is fine, none of this is needed: start it again. These targets are for losing the disk or the VM.

### 2.1 The RTO budget

| Step | Time | Basis |
| --- | --- | --- |
| New VM, Docker, a clone of the repository, `./gradlew build -x test` for the jars | 30 to 60 minutes | Estimate. Depends on the cloud and on whether images are cached. Not measured here. |
| Fetch the backups from the off-host store | size over bandwidth | The store of the large drill held 1.58 GiB (a day of heavy load): about 17 s at 100 MB/s. Depends on the store and the link. |
| Start PostgreSQL from the init scripts and run the upgrade runner | 31 to 34 s | Measured in the drill on this host. |
| Logical restore | 38 s for a 1.09 GiB database, 50 s with fetch-side decryption and verification | Measured in the large drill: 246 MiB of dumps, 1.09 GiB of database, 1.0 million rows in the biggest table. About 29 MiB of database per second, plus a fixed cost of a few seconds per database. Scales with the data size: a restore is a load plus index builds. |
| Or point-in-time restore: unpack the base backup and WAL, then recovery | 164 to 184 s for 3.9 GiB of WAL | Measured in the large drill: 71 to 92 s to unpack the base backup and decrypt and unzip 228 segments, then 91 s of recovery. Scales with the WAL since the base backup, about 22 to 24 MiB of WAL per second. |
| Start the services | 2 to 4 minutes | Five cold starts of eight services on two CPUs took 121 to 216 seconds (measured for the demo overlay, package DEMO-01). |
| Compare, smoke test, hand back | 15 minutes | Estimate. |
| **Measured: the whole quickstart stack, from the loss to everything verified** | **196 s by logical restore, 166 s by point-in-time restore** (236 s and 233 s in a second run on a busier host) | Full-stack drill (section 9.3), 200,298 rows in 293 MiB of database. The start of PostgreSQL, the restore, the realm, Kafka, the agent and the ten services are in these numbers; VM provisioning, the repository clone, the jar build, the fetch from the off-host store and the human decision are not. The service images were already built on the host. |

At these rates a 10 GB pilot database restores logically in about 6 minutes and a 100 GB one in about an hour; a week of WAL at 1 GiB a day replays in about 5 minutes. Both are far inside four hours, and the budget is dominated by provisioning the VM. The numbers come from a Docker Desktop VM with 20 CPUs and 16 GB; an e2-standard-2 (2 vCPU, 8 GB) will be slower, so re-measure on the target VM with `restore-drill.sh --rows` (section 9) before quoting an RTO to a customer.

## 3. State inventory

Every place DLAP keeps state, what protects it, and what losing it costs. Citations are to the files that decide the answer.

| State | Where it lives | Protected by | If it is lost |
| --- | --- | --- | --- |
| **PostgreSQL data**: `dlap_common` (shared tables and the `dlap_catalog` schema, `schema_upgrades` ledger), `da_discovery`, `da_catalog`, `da_policyengine`, `da_reporting`, `da_autolabel`, `da_autoarchival`, `da_autodelete`, `da_autocompliance`, `da_admin`, `da_logingest` | Volume `postgresql-data` (`deployment/environments/dev/docker-compose.yml:48,330`); the list of databases is `POSTGRES_MULTIPLE_DATABASES` (`:56`) | Daily logical backup, weekly base backup, continuous WAL, off-host copy | Everything DLAP knows. This is what the package protects. |
| **Log replay store and source cursors**: `dlap_logingest.log_events` (monthly partitions, 90 days, `da-logingest/src/main/resources/application.properties:119`), `stream_cursor`, `processed_object`, `source_binding`, `shedlock` | The `da_logingest` database | Same as above | Cursors are what let a log source be read again from where it stopped (`V3__bindings_cursors_quarantine.sql:71-83`). Losing them means re-reading history or accepting a gap. |
| **Roles** | The PostgreSQL cluster | Names, attributes and memberships in `globals.sql` of every logical set. **No password hashes.** | Recreated by the stack's init scripts. The role passwords are not in the backup; they come from the init scripts (`00-create-multiple-databases.sh`, fixed per-service passwords, Q-176) and `POSTGRES_PASSWORD`. |
| **`DLAP_ENCRYPTION_KEY`** | The environment file of the stack (`.env.pilot`); read by `da-admin-service` (`application.properties:149`) | **Not in any backup.** Escrow (section 7). Every backup records its fingerprint, and a restore refuses a different key. | Every stored cloud credential becomes unreadable: the ciphertexts in `dlap_admin.dlap_cloud_connection.encrypted_credentials` stay but cannot be decrypted (`da-admin-service/src/main/java/com/dlap/adminservice/service/impl/BasicEncryptionServiceImpl.java:47-60,92-106`). Nothing else is lost. The connections can be entered again. With `dlap.credentials.provider=federated` nothing is stored and the key does not matter (ADR-0036). |
| **Other secrets**: `POSTGRES_PASSWORD`, `DLAP_BACKEND_CLIENT_SECRET`, `KEYCLOAK_ADMIN_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, the backup passphrase | `.env.pilot`, and a file on the host for the passphrase | Escrow set (section 7) | The stack cannot be rebuilt as it was. The client secret and the passwords can be replaced; the backup passphrase cannot, and every encrypted copy needs it. |
| **Keycloak** | While Keycloak runs as `start-dev` with `KC_DB=dev-file` (`docker-compose.yml`, the `keycloak` service): an embedded H2 file in the container's own file system, **no volume** | The realm baseline, `deployment/platform/keycloak/keycloak-import/dlap-realm.json`, is in git and imported at every start of a new container. Users and settings created at run time are protected only by `keycloak-realm.sh backup` (section 8.6), which the host's cron or a systemd timer runs through `keycloak-realm-backup.sh` (section 8.7). | Everything created in Keycloak after the last export is lost when the container is recreated (`down`, an image change). The pilot runs Keycloak on PostgreSQL (`deployment/PRODUCTION.md:104-105`; the init script creates the database when `KEYCLOAK_DB_NAME` is set, `00-create-multiple-databases.sh:74-75`). That database is dumped with the others (test B-14, B-15) and covered by the base backup and the WAL. |
| **Kafka** | Volume `kafka-kraft` (`docker-compose.yml:175,332`), one broker, replication factor 1 (`:161`), topics created on demand (`:159`), 168 hours retention (`:165`) | **Nothing.** | Events produced and not yet consumed are gone, and so are the consumers' committed offsets. Consumers read a new topic from the start (`spring.kafka.consumer.auto-offset-reset=earliest`, for example `da-logingest/src/main/resources/application.properties:58`). Section 3.1 says what comes back and what does not. |
| **Operator files**: the log-source bindings file (`DLAP_LOGINGEST_SOURCES_FILE`, default `/etc/dlap/log-sources.yml`, `da-logingest/src/main/resources/application.properties:106`), any edited overlay | Wherever the operator keeps them | Escrow set (section 7), or the operator's own version control | The sources must be configured again. The cursors are in the database, so reading resumes where it stopped. |
| **Monitoring**: Prometheus and Grafana data | Volumes `prometheus-data`, `grafana-data` (`docker-compose.yml:211,281`), profile `monitoring` | Nothing | Metrics history. Dashboards and rules come from git. |
| **Ollama models** | Volume `ollama-models`, profile `llm` | Nothing | Pulled again on the next start. |
| **Audit chain** (AUD-01) | `dlap_common.audit_log` and `audit_streams` (`docs/specs/audit-log.md`) | Inside the dumps, the base backup and the WAL. The drill checks it after every restore with `scripts/audit-verify.sh`. The signing key of the checkpoints is an operator-held secret file (audit-log.md, "Keys"): put it in the escrow set (section 7). The exported checkpoints live in the write-once sink, outside DLAP. | A lost chain cannot be rebuilt. A restored chain verifies from the genesis entry; entries written after the restore point are simply not there, and a checkpoint exported after that point then names a head the restored chain does not have (`ANCHOR_BEYOND_HEAD`), which is the correct report. |
| **The backups themselves** | Volume `dlap-backups` (or the folder in `DLAP_BACKUP_DIR`) and the off-host store | The off-host copy | A VM loss with no off-host copy loses the backups with the database. |

### 3.1 What Kafka loss costs, and what brings it back

The pipeline is: sources, `da-logingest`, Kafka, the consumers (catalog, policy engine, reporting, auto-label). The database copy of each stage is the record; Kafka only carries events between stages.

- **Lost for good:** events that were produced and not yet consumed when the broker's disk was lost. With consumers keeping up, that is seconds of events.
- **Comes back by itself:** crawler discoveries, because the next crawl emits them again (ADR-0033). Log events from a source with a time cursor, because the cursor is in PostgreSQL and the next poll starts at `low_watermark - overlap` (`V3__bindings_cursors_quarantine.sql:71-73`). The catalog applies each event once whatever the repeats (`processed_event`, ADR-0055).
- **Does not come back by itself:** events that logingest had already acknowledged to the cursor but the consumers had not yet applied. Re-reading them needs a bounded backfill or a replay, and the backfill API is a later package (`V3__bindings_cursors_quarantine.sql`, table `backfill_job`: "API is a later package"). The stored copy in `log_events` can be republished with the `dlap-ingest-mode: REPLAY` header (`docs/specs/log-event.md`, section on idempotency), but no job does it. This is an open question (Q-295).
- **A Kafka that is rebuilt empty is safe.** The services recreate the topics they use on first use and start reading from the beginning of an empty topic.

## 4. How it works

```text
  the VM
  +--------------------------------------------------------------------------------+
  |  postgresql  <---- 127.0.0.1, one shared network ---->  dlap-backup (the agent)  |
  |  data volume                                            /backups volume          |
  +--------------------------------------------------------------------------------+
  the agent writes   /backups/wal/<system id>-tl<n>/   continuously   pg_receivewal
                     /backups/logical/<stamp>/         daily          backup.sh
                     /backups/base/<stamp>/            weekly         base-backup.sh
  and copies, encrypted, to the off-host store every cycle           ship.sh, DLAP_BACKUP_SHIP_CMD
```

- **Where it runs.** `dlap-backup` is a second container from the same `postgres:15.1` image, started next to the database. It shares the database container's network, so it reaches the server on `127.0.0.1`, where the image's `pg_hba.conf` accepts replication connections without a password. It publishes no port and runs as uid 999 on a read-only file system with every capability dropped. It connects as the bootstrap superuser (`dlap`) because it must read every database and use the replication protocol; a narrower backup role is future work (Q-297).
- **WAL.** The agent creates a physical replication slot (`DLAP_WAL_SLOT`, default `dlap_dr`) and streams into a directory named after the cluster's system identifier and timeline. A segment in progress is a `.partial` file that holds every commit received so far. Restore uses it.
- **One server setting is changed:** `max_slot_wal_keep_size` becomes 4 GB if it was unlimited (`ALTER SYSTEM`, persisted in `postgresql.auto.conf`). A slot pins WAL on the database disk until the archive reads it; without a bound a stalled agent would fill the disk and stop the database. With the bound, a stalled agent loses the archive instead: the server invalidates the slot, the agent notices, replaces it, marks `CHAIN_BROKEN_UTC` in its status (a warning in `status`), and takes a new base backup at once. Older base backups then cannot reach moments after the gap, and the archive starts a new directory with the suffix `-g<N>` (tests A-37 to A-41).
- **Timelines.** A point-in-time restore starts a new timeline. The agent keys the archive by system identifier and timeline, so the restored cluster writes a new archive and the old one is never extended past its fork point (tests P-14, drill).
- **Restarts of the database container.** The agent shares the network of the `postgresql` container, so when that container is restarted the agent is left holding a network where nothing listens. The agent notices (no answer for `DLAP_DB_LOST_SECONDS`, default 90), exits, and Docker restarts it into the current network (tested: `docker restart` of the database, the agent back and healthy two minutes later, exit status 75 in the log). After the database container is **recreated** (an image change, `up -d --force-recreate postgresql`) the old network is gone: run `up.sh up -d`, which recreates `dlap-backup` as well (tested). Until then `status` shows `FAIL database`.
- **Logical backups.** One `pg_dump -Fc -C` per database and `pg_dumpall --globals-only --no-role-passwords`. Each set carries `rowcounts.tsv`: the number of rows of every table, counted from the dump itself, so a restore can be compared with it exactly, whatever was written while the dump ran. A set is written into a hidden folder and renamed when complete.
- **Retention.** 14 logical sets (`DLAP_BACKUP_KEEP_LOGICAL`), 3 base backups (`DLAP_BACKUP_KEEP_BASE`). The WAL archive is trimmed to what the oldest kept base backup needs (`pg_archivecleanup`).
- **Failure handling.** A failed run removes its half set and records the failure; the previous good set stays; the agent retries after `DLAP_BACKUP_RETRY_SECONDS` (30 minutes). A backup that is late fails the health check.

## 5. Turn it on

The overlay is applied by `up.sh` like every `docker-compose.pilot.*.yml`; nothing to enable. Without any setting it keeps backups on the VM's disk and warns that nothing leaves the VM. Settings go in `.env.pilot`:

| Setting | Meaning |
| --- | --- |
| `DLAP_BACKUP_DIR` | Host folder for the backups instead of the named volume. Use another disk than the database's. |
| `DLAP_BACKUP_PASSPHRASE_FILE` | Host path of a file holding one line, the passphrase that encrypts every copy before it leaves the VM. `openssl rand -base64 48 > file; chmod 600 file`. Escrow it. |
| `DLAP_BACKUP_SHIP_CMD` | The command that uploads one file (section 5.1). |
| `DLAP_ENCRYPTION_KEY_FINGERPRINT` | `deployment/dr/key-escrow.sh fingerprint --env-file .env.pilot`. Recorded in every backup. |
| `DLAP_BACKUP_AT`, `DLAP_BACKUP_BASE_AT`, `DLAP_BACKUP_BASE_EVERY_DAYS`, `DLAP_BACKUP_KEEP_LOGICAL`, `DLAP_BACKUP_KEEP_BASE`, `DLAP_WAL_CYCLE_SECONDS`, `DLAP_RPO_SECONDS`, `DLAP_WAL_MAX_KEEP_SIZE`, `DLAP_BACKUP_MIN_FREE_MB`, `DLAP_BACKUP_WARN_FREE_MB` | Schedule, retention and thresholds, defaults in [`deployment/dr/agent.sh`](../../deployment/dr/agent.sh). |

After the first start, the agent takes its first logical and base backup by itself (test A-23, A-24). Check:

```bash
deployment/environments/pilot/up.sh exec dlap-backup bash /opt/dr/agent.sh status
```

```text
OK    agent              running since 2026-10-19T09:00:12Z
OK    database           answers on 127.0.0.1:5432
OK    wal archive        streaming to 7698...-tl1, newest 000000010000000000000007.partial
OK    logical backup     set 20261019T090020Z, 3 minutes ago
OK    base backup        set 20261019T090020Z, 3 minutes ago
OK    off-host WAL       the off-host copy is 41s behind (RPO target 900s)
OK    off-host sets      all backup sets shipped (last pass 2026-10-19T09:03:41Z)
OK    backup space       899044 MiB free in /backups
RPO target 900s, RTO target 14400s
```

`status --check` exits 1 when any line is FAIL: no heartbeat for two minutes, the database does not answer, the receiver is not running, a logical backup older than 26 hours or a base backup older than 8 days, the off-host copy more than 900 seconds behind, a blocked off-host copy, a set that could not be uploaded, less than 1 GiB free on the backup volume. The container health check is the same command, so `docker compose ps` shows `unhealthy` and an alert on container health covers it. WARN lines are conditions to fix without urgency: no off-host command, no key fingerprint, a broken WAL chain.

### 5.1 The off-host command

DLAP links no cloud SDK. `DLAP_BACKUP_SHIP_CMD` is run once per file as `sh -c "$DLAP_BACKUP_SHIP_CMD" ship <file> <key>`: inside the command `$1` is the local file and `$2` the object key, a relative path such as `logical/20261019T020000Z/da_admin.dump.enc`. Exit 0 means stored. The command runs without `PGPASSWORD` and is stopped after `DLAP_BACKUP_SHIP_TIMEOUT` seconds (1800). Examples, to be run once by hand with a test file first:

```bash
# In .env.pilot the value MUST be in single quotes: Docker Compose expands $1 and $2 in an unquoted value.
DLAP_BACKUP_SHIP_CMD='rclone copyto "$1" "offsite:dlap-backups/$2"'
DLAP_BACKUP_SHIP_CMD='aws s3 cp "$1" "s3://<bucket>/$2" --only-show-errors'
DLAP_BACKUP_SHIP_CMD='gcloud storage cp "$1" "gs://<bucket>/$2"'
DLAP_BACKUP_SHIP_CMD='azcopy copy "$1" "https://<account>.blob.core.windows.net/<container>/$2"'
```

These four commands were **not** run against a live store in this package: there was no account. What was tested is the contract they plug into, with a stand-in command (tests S-01 to S-29, R-30 to R-38, P-33 to P-36, and the off-host volume of the drill). Run the command once by hand with a small file, and run the drill with the real command before go-live. The `dlap-backup` container needs the tool: see below.

The command needs its tool and credentials inside the `dlap-backup` container, which is the plain `postgres:15.1` image and has none of them. Add a small overlay of your own: `up.sh` applies every `docker-compose.pilot.*.yml`, and `ship` sorts after `backup`, so this file extends the service:

```yaml
# deployment/environments/pilot/docker-compose.pilot.ship.yml  (not part of the repository; yours)
services:
  dlap-backup:
    volumes:
      - /usr/local/bin/rclone:/usr/local/bin/rclone:ro      # a static binary
      - /etc/dlap/rclone.conf:/etc/dlap/rclone.conf:ro      # its configuration
    environment:
      - RCLONE_CONFIG=/etc/dlap/rclone.conf
```

A tool that is not a single static file (the AWS CLI, `gcloud`) belongs in your own image: build `FROM postgres:15.1` with the tool added, and set `image:` of `dlap-backup` to it in the same overlay (keep the PostgreSQL major version equal to the database's). Prefer the VM's own cloud identity over a stored key (the same rule as for the log sources); do not put a long-lived key in `.env.pilot`. Use the customer's own bucket in the customer's own account, with versioning or object lock on, and a lifecycle rule that expires old objects (the agent never deletes remote files).

What is shipped, and when:

| Key | Content | When |
| --- | --- | --- |
| `wal/<key>/<segment>.gz.enc` | A finished WAL segment, gzip then encrypted | Once, in the cycle after it finishes |
| `wal/<key>/<segment>.partial.gz.enc` | The segment in progress | Every cycle in which it has grown. This is what keeps the off-host copy within the RPO although a segment (16 MiB) can take hours to fill. |
| `logical/<set>/` and `base/<set>/` | `manifest.env` and `SHA256SUMS` as they are, every other file as `<name>.enc`, and **`SHA256SUMS.shipped` last**: the SHA-256 of every encrypted file as uploaded | After each backup. A set without `SHA256SUMS.shipped` is incomplete and is never restored from. |

## 6. What the encryption does and does not do

- Everything that leaves the VM is encrypted with the passphrase file: AES-256-CBC, a key from PBKDF2-HMAC-SHA256 with 600000 iterations and a random salt per file (`openssl enc`, test U-23 to U-25). Without a passphrase file the shipper refuses (exit 2, `status` FAIL) unless `DLAP_BACKUP_SHIP_PLAINTEXT=1` says plain files are acceptable. Do not set that for customer data: `log_events` holds principals, IPs and user agents.
- This is confidentiality for the object store. It is **not authentication**: CBC has no integrity check of its own. Integrity against damage comes from the SHA-256 lists, checked by every restore. Against someone who can write to the store, use object lock or versioning on the bucket and keep the passphrase out of reach of whoever holds the store credentials.
- **Copies on the VM are not encrypted by DLAP.** The backup volume has the same exposure as the database volume; encrypting disks is the platform's job (AR-04 in `docs/architecture/threat-model.md`). Do not put `DLAP_BACKUP_DIR` on a share that is more widely readable than the database.
- A role password is not in a backup (`globals.sql` is written with `--no-role-passwords`), and neither is any key. A stolen backup set, decrypted, gives data but no credential to log in with.

## 7. Key escrow

`DLAP_ENCRYPTION_KEY` has no copy in the database or in any backup, on purpose. Give it one, and keep that copy apart from the backups and the passphrase.

1. **At install time**, when the key is generated (`openssl rand -hex 32`):

   ```bash
   deployment/dr/key-escrow.sh fingerprint --env-file .env.pilot      # prints fp1:...
   echo 'DLAP_ENCRYPTION_KEY_FINGERPRINT=fp1:...' >> .env.pilot
   ```

   The fingerprint is a name for the key, not the key: SHA-256 of a fixed label and the key, truncated to 64 bits (`key-escrow.sh`). Every backup records it. A restore compares it with the environment it runs in and refuses a different key (tests R-20 to R-23). That turns "the credentials will not decrypt" from a surprise a week later into a message at restore time.

2. **Keep the escrow set** in the customer's secret manager, or sealed offline: `.env.pilot` (it carries the key and the other secrets), the backup passphrase file, and the log-source bindings file if one is used. Two copies in two places.

   ```bash
   deployment/dr/key-escrow.sh seal   --in .env.pilot --out escrow-2026-10-19.sealed --passphrase-file escrow.pass
   deployment/dr/key-escrow.sh unseal --in escrow-2026-10-19.sealed --out /tmp/check.env --passphrase-file escrow.pass
   ```

   `seal` is for a customer without a secret manager: it encrypts one file with a passphrase kept on paper or in a safe. It refuses to overwrite, and `unseal` fails with a message and writes nothing on a wrong passphrase or a damaged file (tests K-15 to K-24). Unseal once, right away, to prove the copy works.

3. **Check** whenever the key or a backup might have changed:

   ```bash
   deployment/dr/key-escrow.sh check --env-file .env.pilot --against /backups/logical/<set>
   ```

4. **After a restore** with the original key from escrow, the credentials work. If the key is truly lost, restore with `--ignore-key-fingerprint`, then delete the cloud connections and enter them again. Nothing else depends on the key.

SEC-10 may later add a KMS envelope for the key; this procedure then escrows the KMS key reference instead.

## 8. Restore

Pick the procedure by what was lost and how far back you need to go.

| Situation | Procedure |
| --- | --- |
| A table or database was damaged, the server runs | Section 8.2 for the databases involved |
| The database disk or the VM is lost, a day of loss is acceptable | Section 8.2 (logical) |
| The database disk or the VM is lost, you need the latest moment, or the moment before a mistake | Section 8.3 (point in time) |

Both sequences below were run literally, with the pilot's real `up.sh` in a scratch copy of `deployment/` with stand-in jars: every step, up to the final start of the eight Java services (which needs real builds), and the restored data compared equal to the original. Everything below assumes the stack's own scripts: `deployment/environments/pilot/up.sh` (it needs `.env.pilot` and the jars, so rebuild them first on a new VM) and the compose project name `dlap-pilot`. Replace the name if `COMPOSE_PROJECT_NAME` is set.

### 8.1 On a new VM

1. Install Docker, clone the repository at the release that wrote the backup (or a newer one: an older backup under newer code is expected to work, because `up.sh` runs the upgrade runner and the services run Flyway, but the drill does not cover it; a newer backup under older code is not supported).
2. Recover the escrow set: `.env.pilot` with the **same** `DLAP_ENCRYPTION_KEY`, and the backup passphrase file.
3. Build the jars and fetch the backups. `restore.sh` and `restore-pitr.sh` read a folder that has the layout of the off-host store, so fetching is a plain copy into a folder on the VM, for example with the command you used to upload, in reverse:

   ```bash
   rclone copy offsite:dlap-backups /srv/dlap-restore-src      # or aws s3 sync, gcloud storage cp -r, azcopy copy --recursive
   ```

### 8.2 Logical restore (to the last daily backup)

```bash
cd deployment/environments/pilot
./up.sh up -d postgresql              # empty volume: init scripts create roles and databases, the runner applies the deltas
./up.sh run --rm -T --entrypoint bash \
    -v /srv/dlap-restore-src:/restore-src:ro dlap-backup \
    /opt/dr/restore.sh --from /restore-src/logical/<newest set> --yes-replace-databases \
    --work-dir /backups/restore-tmp
./up.sh up -d                         # now the services
```

The backup volume is mounted by the service (`/backups`); the fetched folder is mounted with `-v`. Without `--yes-replace-databases` the script prints the plan (which databases, how large, how many sessions) and exits 2 having changed nothing.

What `restore.sh` does and refuses (tests R-01 to R-38):

- verifies the checksums, decrypts into a work folder if the set is encrypted, and recounts the rows of every dump against `rowcounts.tsv`;
- refuses a different `DLAP_ENCRYPTION_KEY_FINGERPRINT`, a missing role, a damaged or incomplete set, a wrong passphrase, other sessions on the target databases (unless `--terminate-connections`);
- drops and recreates each database from its dump, refreshes the statistics, counts the rows of every table and compares them with the set. **Any difference is a failure (exit 1).** On success it prints `RESTORE VERIFIED` with the totals and the elapsed time.

`--verify-only` checks a set without a server, for example against the off-host copy every week. `--databases a,b` restores only those.

### 8.3 Point-in-time restore

`up.sh up` starts PostgreSQL on an empty volume at once, which would initialise it and leave nothing to restore into. So the volume is made by hand, filled by `restore-pitr.sh`, and only then started, with `create` and `start`, which `up.sh` passes to Compose without its upgrade step.

```bash
cd deployment/environments/pilot
# 1. The volume the stack will use, labelled so that Compose adopts it without a warning.
docker volume create --label com.docker.compose.project=dlap-pilot \
    --label com.docker.compose.volume=postgresql-data dlap-pilot_postgresql-data
# 2. Fill it from the base backup and the WAL (runs as root in a throwaway container).
docker run --rm -v dlap-pilot_postgresql-data:/pgdata \
    -v /srv/dlap-restore-src:/restore-src:ro \
    -v "$PWD/../../dr":/opt/dr:ro \
    -v /path/to/backup-passphrase:/run/secrets/dlap_backup_passphrase:ro \
    --entrypoint bash postgres:15.1 \
    /opt/dr/restore-pitr.sh --from /restore-src --pgdata /pgdata --target latest
# 3. Start PostgreSQL alone. Recovery runs now; watch ./up.sh logs -f postgresql.
./up.sh create postgresql
./up.sh start postgresql
# 4. PostgreSQL accepts read-only connections while it still replays the WAL. Wait for the end of recovery.
until [ "$(docker exec dlap-postgresql psql -U dlap -d postgres -Atc 'SELECT pg_is_in_recovery()')" = f ]; do sleep 2; done
# 5. Now the upgrade runner may write, and the rest can start.
./up.sh up -d
```

`--target` is `latest` (everything that was archived), `time:2026-10-19T10:15:00Z`, `name:<restore point>` (created with `SELECT pg_create_restore_point('before_migration')`), `lsn:0/3000060`, or `immediate` (the base backup alone). The script picks the newest base backup older than a time target, unpacks it, copies the WAL segments of the archive into `pg_wal/` (using a `.partial` segment under its final name when that is all there is), warns of gaps and refuses unless `--allow-gaps`, and writes the target into `postgresql.auto.conf`. PostgreSQL replays up to the target, promotes, and opens on a new timeline. Tests P-08 to P-36.

Two things the script does with the WAL beyond copying it (DR-03, Q-752). First, the base backup streamed its own copy of the segment it ended in, and that copy stops where the backup stops, while the archive copy of the same name goes on to the disaster. Recovery stops at the first byte of WAL it cannot read, so the shorter copy would end the restore at the end of the base backup, with no error and exit 0. The script therefore keeps whichever copy of a segment holds more WAL (the archive's on a tie) and says so (`REPLACED_BASE_SEGMENTS` in the report). Second, before it reports the directory ready it reads the prepared `pg_wal/` with `pg_waldump`, from the start of the base backup, the way recovery will, and fails with exit 1 (and removes the directory again) when the reading ends before the newest archived segment that holds WAL, that is, when recovery would replay nothing of the archive beyond the base backup. The report names the check: `REPLAY_CHECK=ok`, `nothing-newer` (the archive holds nothing beyond the base backup), `failed`, or `skipped-*` with the reason (`--no-replay-check`, an `immediate` target, `--allow-gaps` with a gap, no `pg_waldump`). The reading costs one more pass over the archive; it is not timed on its own, but the prepare step took 8 s with it for 17 segments (272 MiB) in the DR-03 drill against 7 s without it in the DR-02 drill (section 9.3), and for a very large archive `--no-replay-check` skips it.

If the off-host store also holds the backups of a cluster restored earlier (the restored stack keeps shipping to the same place), a `name:` or `lsn:` target needs `--system-id` or `--base` to say which cluster made that restore point; the script refuses to guess.

After the server is up, `up.sh up -d` starts the agent with the rest. It sees a new timeline, starts a new archive directory and takes a new base backup, as the drill checks.

Leftovers in `postgresql.auto.conf` after recovery (`restore_command = 'false'` and the target) do no harm; remove them with `ALTER SYSTEM RESET` if tidiness matters.

### 8.4 After any restore

1. Compare. `restore.sh` has already counted every table against the set. A point-in-time restore has nothing recorded to compare with, because the loss is the reason for it; for a planned one (a rollback after a migration) run `rowcounts.sh --digest > before.tsv` first and `rowcounts.sh --digest --compare before.tsv` after.
2. Open the UI, sign in, open the catalog and a report.
3. Kafka starts empty. Read section 3.1 and decide whether a re-crawl or a source backfill is needed for the window since the backup.
4. Keycloak: in the quickstart restore the newest realm export (sections 8.6 and 8.7); without one, recreate the users that were added after the realm import.
5. Re-run `agent.sh status --check` after the first backups finish.

### 8.5 When it goes wrong

| Message | Cause | What to do |
| --- | --- | --- |
| `the server lacks N role(s)` | PostgreSQL was not started from the init scripts | Start it with the stack's volume empty and the init scripts mounted; the roles come from them. |
| `key fingerprint differs` | The environment has another `DLAP_ENCRYPTION_KEY` | Fetch the right key from escrow. If it is lost, `--ignore-key-fingerprint` and re-enter the cloud connections. |
| `does not match SHA256SUMS` / `SHA256SUMS.shipped` | A file is damaged or the upload was cut short | Fetch the set again; use the previous set. |
| `incomplete ... no SHA256SUMS.shipped` | The upload of the set did not finish | The set is not restorable; use the previous one. |
| `cannot decrypt ... wrong passphrase` | Another passphrase file | Use the one from the escrow set. |
| `other session(s) are connected` | A service is running | Stop the services, or `--terminate-connections`. |
| `the WAL sequence has a gap` | A segment is missing in the archive | Use an older target or base backup; `--allow-gaps` restores up to the gap only. |
| `segment N: the archive copy holds more WAL than the base backup's ... and replaces it` | Not an error. The base backup streamed its own copy of the segment it ended in, which stops at the end of the backup; the archive copy of the same name goes on. The longer copy is used (Q-752). | Nothing. `REPLACED_BASE_SEGMENTS` in the report counts them. |
| `segment N: the base backup's copy holds more WAL than the archive's ... and is kept` | A warning. The archive copy of that segment is shorter than the base backup's, which means the archive is behind or damaged at that point. | The restore stands at the longer copy. Check the off-host WAL copy before relying on a restore to the latest moment. |
| `recovery would stop at X, before the newest archived segment N` (exit 1, the directory is removed) | The WAL that was prepared cannot be read from the start of the base backup through to the newest archived segment: a damaged or truncated segment, so recovery would end there and replay nothing of the archive beyond it | Fetch the archive again. Use an older base backup, or an earlier target after finding the damaged segment. `--no-replay-check` skips the check (for a target before known damage, or an archive too large to read once more). |
| `the store holds base backups of N clusters` | The off-host store holds the backups of the original cluster and of the cluster restored from it, and the target (`name:`, `lsn:`) names a point only one of them has | Pass `--system-id <id of the cluster that was running then>` or `--base <set>`; the message lists both with their creation times. A `latest` or `time:` target needs no choice. |
| `no complete base backup ... older than the target` | The target is older than every kept base backup | Use the logical restore of that day, if the set is still kept. |
| `ROW COUNTS DIFFER` | The restored data is not what the set holds | Do not use it. Restore again; if it repeats, keep the evidence and use an older set. |
| PostgreSQL stops with `must specify restore_command` | A data directory made by hand | Use `restore-pitr.sh`; it writes the setting. |

### 8.6 The quickstart stack, with Compose

The quickstart (the dev compose pair plus the backup overlay) has no `up.sh`. This is the sequence `stack-drill.sh` runs, as commands. It restores PostgreSQL, the Keycloak realm and the ten services on a host that has the repository at the right release, the built jars (`./gradlew :da-catalog:bootJar` and the nine others, or let the drill script build them), the same `.env` (with the **same** `DLAP_ENCRYPTION_KEY`, from the escrow set) and the passphrase file.

Put the fetched backups in a Docker volume that has the layout of the off-host store (`logical/`, `base/`, `wal/`, `keycloak/`); the volumes are the names the override file reads:

```bash
cd <the repository>
export DR_STACK_SOURCE=<volume with the fetched backups>     # mounted read-only at /source
export DR_STACK_REMOTE=<volume the restored stack ships to>  # mounted at /remote; a new one, never the source
export DR_STACK_KC_IMPORT=<an empty volume>                  # the Keycloak import folder
export DLAP_BACKUP_PASSPHRASE_FILE=<host path of the passphrase file>
DC() { docker compose --env-file .env \
  -f deployment/environments/dev/docker-compose.yml -f deployment/environments/dev/docker-compose.microservices.yml \
  -f deployment/environments/pilot/docker-compose.pilot.backup.yml \
  -f deployment/dr/docker-compose.stack-drill.yml -f deployment/dr/docker-compose.stack-drill.restore.yml "$@"; }
APPS="da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete da-autocompliance da-admin-service da-logingest"
```

1. **The databases, logical restore** (to the newest set):

   ```bash
   DC up -d --wait postgresql        # empty volume: the init scripts create the roles and the empty databases
   DC run --rm -T --entrypoint bash dlap-backup /opt/dr/restore.sh --from /source/logical/<newest set> \
       --yes-replace-databases --work-dir /backups/restore-tmp
   ```

   **Or point in time** (to the last WAL that reached the store): fill the volume before PostgreSQL starts.

   ```bash
   DC up --no-start postgresql       # creates the network and the empty data volume <project>_postgresql-data
   docker run --rm -v <project>_postgresql-data:/pgdata -v "$DR_STACK_SOURCE":/remote:ro -v "$PWD/deployment/dr":/opt/dr:ro \
       -v "$DLAP_BACKUP_PASSPHRASE_FILE":/run/secrets/dlap_backup_passphrase:ro --entrypoint bash postgres:15.1 \
       /opt/dr/restore-pitr.sh --from /remote --pgdata /pgdata --target latest
   DC up -d --wait postgresql
   until [ "$(docker exec dlap-postgresql psql -U dlap -d postgres -Atc 'SELECT pg_is_in_recovery()')" = f ]; do sleep 2; done
   ```

2. **The Keycloak realm**, from the newest export:

   ```bash
   bash deployment/dr/keycloak-realm.sh restore --from-volume "$DR_STACK_SOURCE" --key keycloak/<newest> --realm dlap --import-volume "$DR_STACK_KC_IMPORT"
   DC up -d --no-deps --wait keycloak                                # imports the realm from the volume on its first start
   bash deployment/dr/keycloak-realm.sh wipe --import-volume "$DR_STACK_KC_IMPORT"   # the plain realm file is not kept
   ```

3. **Kafka, the agent and the services**:

   ```bash
   DC up -d --no-deps --wait kafka
   DC up -d --wait dlap-backup
   DC up -d --no-deps $APPS
   ```

4. **Verify** (the drill automates each line, section 9.3):

   ```bash
   for p in 8082 8083 8084 8085 8086 8087 8088 8089 8090 8091; do curl -s -o /dev/null -w "$p %{http_code}\n" http://localhost:$p/actuator/health; done
   DLAP_COMMON_DB_URL=dlap_common DLAP_PSQL="docker exec -i dlap-postgresql psql -U dlap" bash scripts/audit-verify.sh
   ```

   Then sign in with a realm user and open the catalog. A logical restore stands at the set; a point-in-time restore stands at the last shipped WAL. The Keycloak realm stands at its last export in both.

Start the services only after the databases are restored and the recovery has ended: the services run Flyway and write at start, and a restore that is compared with a recorded state must be compared before that.

### 8.7 Scheduling the Keycloak realm backup (DR-03)

Who needs this: a stack whose Keycloak is the quickstart one (`start-dev`, `KC_DB=dev-file`, the `keycloak` service of `docker-compose.yml`), because that realm lives in the container and the PostgreSQL backups do not carry it. The pilot's Keycloak is on PostgreSQL (`docker-compose.pilot.keycloak.yml`) and is already in every backup; it needs none of this. Moving the quickstart Keycloak to PostgreSQL is not part of this work.

Why it runs on the host: exporting the realm needs Docker (the script stops the Keycloak container for the length of a copy, snapshots it and exports from the snapshot, section 3), and the `dlap-backup` agent has no Docker access; mounting the Docker socket into it is rejected, because it would let a backup container take over the host. So cron or a systemd timer on the VM starts [`deployment/dr/keycloak-realm-backup.sh`](../../deployment/dr/keycloak-realm-backup.sh), which wraps `keycloak-realm.sh`:

1. It takes a lock, so two runs never overlap.
2. It runs `keycloak-realm.sh backup` into a Docker volume on the host (`dlap-keycloak-realm-backups`) as `keycloak/<UTC stamp>/`: the realm file encrypted with the backup passphrase, `manifest.env`, and `SHA256SUMS.shipped` last. The plain realm never reaches the host disk.
3. It hands every set that has not been shipped to your off-host command, oldest first, with `SHA256SUMS.shipped` last. The command has the contract of section 5.1 (`sh -c "$DLAP_BACKUP_SHIP_CMD" ship <file> <key>`), but it runs on the **host**, so the tool (`rclone`, `aws`, `gcloud`) must be installed there. A set whose shipping failed is tried again at the next run.
4. It keeps the newest 14 shipped sets in the volume and never removes an unshipped one.
5. It writes `status.env` in its state folder. `check` reads it: exit 1 when the last success is older than 26 hours or the last run failed.

Keycloak is stopped while the container is copied: 6 s in the drill (section 9.3), after which it needs its normal start-up time (33 s to be up again on the restored realm in the same drill) before it answers. The whole run took 53 s there, most of it the export from the snapshot and the shipping, with Keycloak running. Schedule it in a quiet hour. The realm's RPO is the interval, 24 hours with the daily schedule below; a user created after the last export is lost by a restore (section 9.3 shows it).

Settings go in `/etc/dlap/keycloak-realm-backup.env`, owner root, mode 600. The file is read line by line and never executed; quote the ship command in single quotes, as in `.env.pilot`:

```bash
DLAP_BACKUP_PASSPHRASE_FILE=/etc/dlap/backup-passphrase
DLAP_BACKUP_SHIP_CMD='rclone copyto "$1" "offsite:dlap-backups/$2"'
# optional, with their defaults:
#DLAP_KC_CONTAINER=dlap-keycloak
#DLAP_KC_REALM=dlap
#DLAP_KC_KEEP=14
#DLAP_KC_MAX_AGE_HOURS=26
```

Use the passphrase file of the backup agent, so one escrowed secret opens every backup (section 7). Run it once by hand and check, before scheduling it:

```bash
sudo /opt/dlap/deployment/dr/keycloak-realm-backup.sh run --env-file /etc/dlap/keycloak-realm-backup.env
sudo /opt/dlap/deployment/dr/keycloak-realm-backup.sh check --env-file /etc/dlap/keycloak-realm-backup.env
```

**Option A, cron.** Install the file [`deployment/dr/cron/dlap-keycloak-realm-backup`](../../deployment/dr/cron/dlap-keycloak-realm-backup) as `/etc/cron.d/dlap-keycloak-realm-backup` (owner root, mode 644). Adjust the path of the repository. Cron mails any output to `MAILTO`, so the daily run is silent when it works and the hourly check speaks only when the backup is late or failed:

```cron
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MAILTO=root
10 3 * * * root /opt/dlap/deployment/dr/keycloak-realm-backup.sh run --env-file /etc/dlap/keycloak-realm-backup.env >> /var/log/dlap-keycloak-realm-backup.log 2>&1
40 * * * * root /opt/dlap/deployment/dr/keycloak-realm-backup.sh check --quiet --env-file /etc/dlap/keycloak-realm-backup.env
```

**Option B, a systemd timer.** Install the two files of [`deployment/dr/systemd/`](../../deployment/dr/systemd/) in `/etc/systemd/system/`, adjust the path of the repository, and enable the timer. The service keeps its lock and status in `/var/lib/dlap-keycloak-backup` (`StateDirectory`). `Persistent=true` runs a missed backup when the host is next up.

```ini
# /etc/systemd/system/dlap-keycloak-realm-backup.service
[Unit]
Description=DLAP Keycloak realm backup (quickstart stack)
Documentation=file:///opt/dlap/docs/guides/backup-and-restore.md
Wants=docker.service
After=docker.service
ConditionPathExists=/etc/dlap/keycloak-realm-backup.env

[Service]
Type=oneshot
ExecStart=/opt/dlap/deployment/dr/keycloak-realm-backup.sh run --env-file /etc/dlap/keycloak-realm-backup.env
StateDirectory=dlap-keycloak-backup
Environment=DLAP_KC_STATE_DIR=/var/lib/dlap-keycloak-backup
UMask=0077
PrivateTmp=true
NoNewPrivileges=true
Nice=10
TimeoutStartSec=2h
```

```ini
# /etc/systemd/system/dlap-keycloak-realm-backup.timer
[Unit]
Description=Daily DLAP Keycloak realm backup

[Timer]
OnCalendar=*-*-* 03:10:00 UTC
Persistent=true
RandomizedDelaySec=120

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now dlap-keycloak-realm-backup.timer
systemctl list-timers dlap-keycloak-realm-backup.timer     # the next run
journalctl -u dlap-keycloak-realm-backup.service -n 50      # the last run
```

To be told of a failed run, add an `OnFailure=` line to the service that starts a unit of your own (mail, chat), and alert on `keycloak-realm-backup.sh check`, which also catches a timer that stopped firing.

**Restore from the off-host copy.** Fetch the three files of the newest `keycloak/<stamp>/` from your store into a folder, load them into a volume that has the layout `keycloak/<stamp>/...`, and restore as in section 8.6, step 2:

```bash
docker volume create dlap-kc-fetched
docker run --rm -v dlap-kc-fetched:/v -v "$PWD/fetched":/in:ro --entrypoint bash postgres:15.1 -c 'mkdir -p /v/keycloak && cp -r /in/keycloak/<stamp> /v/keycloak/'
bash deployment/dr/keycloak-realm.sh restore --from-volume dlap-kc-fetched --key keycloak/<stamp> --realm dlap --import-volume "$DR_STACK_KC_IMPORT"
```

`restore` refuses a set without `SHA256SUMS.shipped`, a file that does not match it, and a wrong passphrase, and leaves nothing behind in those cases (tests KR-07 to KR-10).

## 9. The drill

There are two drills. [`deployment/dr/restore-drill.sh`](../../deployment/dr/restore-drill.sh) (DR-01) drills the database tier and is the cheaper one; [`deployment/dr/stack-drill.sh`](../../deployment/dr/stack-drill.sh) (DR-02, section 9.3) drills the whole quickstart stack and is the one to run before go-live and every quarter. The database-tier drill builds a database tier from the real files, protects it with the real agent, destroys it, and restores it from the off-host copy alone.

```bash
deployment/dr/restore-drill.sh --rows 20000 --out /tmp/drill-evidence
```

It uses its own compose projects (`dlap-drill-<id>-a/b/c`), project-scoped container names, no published ports and a volume of its own as the off-host store, so it is safe beside a running pilot. It needs Docker with Compose 2.24, about 2 GB of memory and 1 GB of disk at the default size.

| Step | What is checked |
| --- | --- |
| Stack A | PostgreSQL from the init scripts; the upgrade runner; the demo seed; the Flyway migrations of all ten services, each as the role the service connects with; synthetic log events. The agent starts and takes its first backups by itself; the archive and the slot appear; the server bound is set. |
| Data and backups | More events; a logical backup; more events; a named restore point; more events; a change to a stored credential row. Counts and content digests of every table are recorded at each point. |
| Off-host | The WAL is copied within the cycle, the sets are complete, nothing on the off-host volume is plain. |
| Disaster | Stack A and its volumes are removed. |
| Stack B | A fresh PostgreSQL; `restore.sh` from the encrypted off-host set. Every table must have the same rows and the same digest as at the backup. The agent starts and takes its first backups in a new archive. |
| Stack C, twice | `restore-pitr.sh` from the base backup and WAL on the off-host volume, to the restore point and to the latest moment. Every table must equal the recorded state at that point, including what was only in the segment being written. The server is on timeline 2; the agent resumes in a new archive directory and `status --check` passes. |

### 9.1 Results

Two runs on the development host (Windows 11, Docker Desktop with 20 CPUs and 16 GB, Docker 28.3, Compose 2.38), 2026-10-20, from the commit that carries this guide. Both passed all 29 checks.

| Measure | Default drill (`--rows 20000`) | Large drill (`--rows 1000000`) |
| --- | --- | --- |
| Rows in the 127 tables at the disaster | 35,618 | 1,750,618 |
| Database size at the logical backup, at the disaster | 135 MiB, 147 MiB | 1.09 GiB, 1.75 GiB |
| Logical set (dumps), WAL archive on the VM, off-host store (compressed, encrypted) | 5.4 MiB, 64 MiB, 40 MiB | 246 MiB, 3.9 GiB (228 segments), 1.58 GiB |
| Logical backup of 12 databases | 13 s | 45 s |
| Off-host copy behind the last commit (WAL cycle 30 s) | 34 s | 59 s |
| PostgreSQL from the init scripts, plus the upgrade runner | 34 s | 31 s |
| Logical restore, whole `restore.sh` (of which database load) | 26 s (15 s) | 50 s (38 s) |
| Point-in-time restore to the restore point: prepare, recovery, total | 4 s, 24 s, 31 s | 92 s, 91 s, 184 s |
| Point-in-time restore to the latest moment: prepare, recovery, total | 4 s, 25 s, 31 s | 71 s, 91 s, 164 s |
| Whole drill | 333 s | 884 s |

What the 29 checks established, in the order they run:

- The agent took its first logical and base backup by itself, the archive streams into `<system id>-tl1`, `max_slot_wal_keep_size` is 4GB, and the ship command written single-quoted in the environment file reached the container with `$1` and `$2` intact.
- The off-host copy covered the last commit within the cycle; the logical set and a base backup are complete there (`SHA256SUMS.shipped`); WAL segments are there; nothing on the off-host volume is plain (no `*.dump`, no `*.tar.gz`); `status --check` passed.
- After the loss of stack A and its volumes, the logical restore of the encrypted off-host set exited 0, said `RESTORE VERIFIED`, and **every table of all 127 had the same rows and the same content checksum** as the original at the moment of the backup. The agent then ran on the restored stack, took its first backups into a new archive directory, and shipped again.
- The point-in-time restores to the named restore point and to the latest moment each exited 0, recovery ended on timeline 2, and **every table matched the recorded state at that point**: 30,618 rows at the restore point and 35,618 at the latest moment in the default run; 1,500,618 and 1,750,618 in the large one. The latest moment includes rows that were only in the WAL segment being written when the stack was lost.
- After the point-in-time restore the agent started a new archive directory for timeline 2 (`<system id>-tl2`), shipped it, and `status --check` passed.

This is a small-file drill of the database tier. It does not start the ten Java services on the restored data (that needs the built jars and is the integrator's harness run), and it does not use a real object store: the off-host volume stands in for it with the same command contract (section 5.1).

### 9.2 Quarterly

Run the drill on the pilot VM's host (or a copy of it) at install and every quarter, and keep the evidence folder. The full-stack drill of section 9.3 passed on the development host; a pass on a customer's own infrastructure, with the customer's own off-host command, is still to be done and is the last part of the G2 gate (DR-02). Between drills, `restore.sh --verify-only --from <set>` on the off-host copy proves a set is complete and uncorrupted without needing a server.

### 9.3 The full-stack drill (DR-02)

`stack-drill.sh` runs the loss on the real quickstart stack: the dev compose pair, the backup overlay of the pilot (the real agent), and the e2e override (fixture replay, delete executor in dry run, mock archival). It destroys the stack with every volume and brings it back from the off-host copy alone. Nothing is simulated except the off-host store, which is a Docker volume that survives `down -v` and receives the files through the same single-quoted `DLAP_BACKUP_SHIP_CMD` contract as a bucket (section 5.1).

```bash
export JAVA_HOME=<a JDK 21>      # only used to build a missing service jar; --rebuild forces the build
bash e2e-tests/with-harness-lock.sh --label stack-drill -- \
    bash deployment/dr/stack-drill.sh --rows 200000 --out /tmp/stack-drill-evidence
bash deployment/dr/stack-drill.sh --restore logical   # only the logical restore  (--restore pitr: only the point-in-time restore)
```

The script takes the host-wide harness lock by itself (`e2e-tests/harness-lib.sh`; the wrapper above is optional), runs under the compose project name derived from the checkout, and evicts only what the harness rules allow. It needs Docker with Compose 2.24, curl, openssl, about 6 GB of free memory and the ten service jars. Every secret is exported by the operator (`POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `DLAP_BACKEND_CLIENT_SECRET`, `DLAP_ENCRYPTION_KEY`, `DLAP_DRILL_PASSPHRASE`, `DLAP_DRILL_LOGIN_PASSWORD`) or generated for the run; they live in a mode 600 file of the evidence folder that the script deletes. The exit status is 0 only if every check passed.

| Step | What happens |
| --- | --- |
| Stack A | PostgreSQL, Kafka, Keycloak, the agent and the ten services start; every service must answer `/actuator/health` with 200 and status UP. |
| Seed | The fixture events are replayed through the log spine into the catalog; the passwords of the realm users `admin`, `cdo` and `director` are set at run time (state that is in no file in git); two approvals are created through the policy engine API, one decided by two people and one withdrawn by the service account (the only approval action that writes the `govern.approval` stream at this release); audit entries are appended to the five other streams through the trigger the writers use; `--rows` synthetic log events fill `da_logingest`; a marker table receives one row a second from a session of its own. `audit-verify` must pass on all 6 streams. |
| Protect | The services are stopped, so the live database can be compared exactly. Row counts and content digests of every table and the Flyway history of every service are recorded. The Keycloak realm is exported by the scheduled wrapper `keycloak-realm-backup.sh` (section 8.7), exactly as cron or the timer runs it, and shipped, encrypted, to the off-host volume by a host-side ship command; its `check` must pass. The agent takes a logical backup and ships it. |
| After the backup | The markers go on, a Keycloak user is created, and audit entries are appended: all of it after the backup point, so each restore shows where it stands. The drill waits until the agent has shipped the WAL past the last entry, then reads the newest marker. |
| Disaster | `docker compose down -v`: every container and every volume of the project is gone (checked). The off-host volume survives. |
| Stack B | Logical restore (section 8.6): PostgreSQL from the init scripts, `restore.sh` from the encrypted off-host set, the realm, Kafka, the agent, the services. |
| Stack C | Point-in-time restore of a new stack from the base backup and the WAL on the off-host volume, to the latest shipped moment, then the same. `restore-pitr.sh` reads the prepared WAL with `pg_waldump` before it reports ready (`REPLAY_CHECK=ok` in its report is a drill check, Q-752), and recovery must end in the newest archived segment. |

Checks after each restore. Before the services start: every table has the same row count and the same content digest as the recorded state (all 153 tables; for the logical restore the state at the backup, for the point-in-time restore the state at the disaster), the Flyway history is identical, and the marker table says where the restore stands. After the services start: no Flyway row changed and none failed; `audit-verify` passes on every stream (the chain has the entries after the backup when the WAL was replayed and stops at the backup when it was not); every service answers `/actuator/health` with 200 UP; the realm user `admin` signs in with the password set before the disaster, the token is accepted by the catalog and the policy engine, and the seeded approvals are `APPROVED` and `WITHDRAWN`; the Keycloak user created after the realm export is absent; for the point-in-time restore, recovery ended in the newest archived WAL segment.

#### Results

One run on the development host (Windows 11, Docker Desktop 28.3 with 20 CPUs and 16 GB, Compose 2.38), 2026-11-02, `--restore both --rows 200000`, 57 checks passed in 880 s (the jar build, 109 s, included). All times are wall-clock seconds on this host from `results.env` of the evidence folder. The first run (DR-02, 2026-11-01, 54 checks) measured 199 s and 162 s for the two restores and is kept in ADR-0117.

| Measure | Value |
| --- | --- |
| Stack at the backup | 153 tables in 11 databases (the nine service databases, `dlap_common` and the drill's marker database), 200,298 rows, 293 MiB; the set holds 13 dumps, two of them of empty databases (`dlap`, `da_catalog`) |
| Logical set, realm export, off-host store | 35.2 MiB of dumps, 74 KiB of realm (plain), 17 WAL segments (272 MiB) and a base backup besides |
| Stack A to ten healthy services | 35 s for PostgreSQL, Kafka and Keycloak, then 72 s for the services (113 s to build the images and create the containers on this fresh checkout) |
| Logical backup of 13 databases | 9 s |
| Realm backup through `keycloak-realm-backup.sh` (section 8.7) | 53 s in all: Keycloak stopped for 6 s, 36 s for the export, 10 s to ship |

| RTO, from the destroyed stack | Logical restore | Point-in-time restore |
| --- | --- | --- |
| PostgreSQL up on the restored data | 22 s (from the init scripts) | 23 s (prepare 8 s with the replay check, start and recovery 14 s) |
| Databases restored (`restore.sh` itself took 19 s) | 48 s | 23 s |
| Row counts and digests of 153 tables compared | 57 s | 32 s |
| Keycloak up on the restored realm | 90 s | 65 s |
| All ten services healthy | 184 s | 155 s |
| **Everything verified (login, audit-verify, approvals)** | **196 s** | **166 s** |

| RPO, where the restore stands | Logical restore | Point-in-time restore |
| --- | --- | --- |
| Stands at | the logical set, taken before marker 6 | the last WAL that reached the store, marker 30 |
| Written by the lost stack | marker 37 | marker 37 |
| Lost | 31 markers, 119.6 s of writes | 7 markers, 7.0 s of writes |

The point-in-time restore read 17 segments, from `...05` to `...15`; `restore-pitr.sh` reported `REPLAY_CHECK=ok` (reading ended at 0/1599BF68, in the newest segment) and `REPLACED_BASE_SEGMENTS=0`: in this run the base backup's copies were as long as the archive's.

A second run on the final commit (2026-11-02, 57 checks passed in 962 s) did meet the case of Q-752. Segment `...06` was in the base backup with 57,690 non-zero bytes and in the archive with 9,263,645: the base copy ended where the backup ended, the archive copy went on. The earlier script would have kept the base copy and recovery would have ended in the first 57 KiB of that segment; `restore-pitr.sh` now said `the archive copy holds more WAL than the base backup's ... and replaces it` (`REPLACED_BASE_SEGMENTS=1`), read the WAL through to `...15` (`REPLAY_CHECK=ok`, ending at 0/15B56CE0), and every table matched the state at the disaster. That run was slower on every step (the host was busier: 122 s for the services of stack A against 72 s), RTO 236 s for the logical restore and 233 s for the point-in-time restore, RPO 172.3 s and 3.0 s (50 and 3 markers); it is the upper figure of the two measurements. The case is also forced, without luck, by tests P-40 to P-44 (section 10), which cut the base copy and show that the restore recovers every row, and P-46 shows that a restore that cannot read through to the newest archived segment fails.

How to read these numbers.

- **RTO** is the time from the destroyed stack to a verified one. It leaves out what is not repeatable on one host: provisioning the VM and installing Docker, cloning the repository, building the jars, building the images (section 2.1 estimates 30 to 60 minutes for these), fetching the backups from a real object store, and the minutes before a person decides to restore. The drill runs on a fast host; the services took 68 to 100 s to start here and 2 to 4 minutes on two CPUs (section 2.1).
- **RPO of a logical restore** is the age of the newest set. The drill wrote for 120 s after the set, so it lost 120 s; in operation the agent takes a set daily, so this restore can lose up to a day. The point-in-time restore is what meets the 15 minute target.
- **RPO of a point-in-time restore** is how far the off-host WAL copy was behind. With the cycle of 30 s the drill lost 3 s (3, 4, 9, 3 and 7 s in five runs). With the default cycle of 300 s plus the upload, expect up to five minutes; the target is 900 s.
- **The Keycloak realm** is outside both: it stands at its last `keycloak-realm-backup.sh run` (section 8.7). The user created after the export was absent after both restores, as intended.
- The point-in-time restore is faster here because it skips the table load and the index builds; it replays 17 WAL segments of 16 MiB. With more data the restore grows as in the database-tier drill (section 9.1).

What the drill does not cover: the UI and the gateway (stateless, and the UI is a separate checkout), `da-mcp-pep` (not in the dev compose), a real object store, a host with the specification of the pilot VM, and Kafka (it is not protected, section 3.1; the restored stack starts it empty). The audit entries of five streams are appended by the drill through the writers' trigger because no service writes them at this release; the sixth, `govern.approval`, is written by the real service. When the executors and AUD-02a write their streams, the drill should drive those instead (Q-753).

In the first full run the point-in-time restore replayed none of the WAL after the base backup, although `restore-pitr.sh` exited 0: the stack came up in the state of the base backup. The digest comparison caught it and failed the drill. It did not occur in the runs after it (DR-02 runs 4 to 7, the wave-9 integration, the first DR-03 run), and the evidence of the first run was not kept, so the cause is not proven. The second DR-03 run met the same condition (a base copy of a segment far shorter than the archive's) and the new script handled it (section 9.3, Results), which is strong evidence for this cause. The most likely one is now fixed (Q-752): `restore-pitr.sh` skipped an archived segment when `pg_wal` already held a file of that name from the base backup, and the base backup's copy of the segment it ends in stops at the end of the backup, while the archive's goes on. On a busy cluster there are records between the end of the backup and the segment switch, so the two differ; recovery stops at the first byte it cannot read, which is the end of the shorter copy. The script now keeps the longer copy and reads the prepared WAL back before it reports ready (section 8.3). The drill checks `REPLAY_CHECK=ok` as well as that recovery ended in the newest archived segment, keeps the off-host volume of a failed run, and saves the PostgreSQL log and `restore-pitr.log`. This is the reason to compare a restored database with something recorded, and not to trust the exit status of a restore alone.

## 10. Named tests

`deployment/dr/test-dr.sh --quick` runs L, O, the container tests U to A, and the stack tools SD, SM, KR and KS (340 tests, about 6 minutes on the development host); without `--quick` it adds the database-tier drill D (29 checks); `--stack-drill` adds the full-stack drill F (57 checks, about 12 minutes without the jar build). L-06 runs shellcheck when it is installed, or in a container when `DLAP_SHELLCHECK_IMAGE` names an image that carries it.

| Prefix | What | Tests |
| --- | --- | --- |
| L | Files | Syntax, Apache header, no em-dash or en-dash, LF endings, executable bit, shellcheck, no literal secret in the compose files |
| O | Overlay model (`docker compose config`) | Same image as the database, shared network, no ports, uid 999, read-only, no capabilities, limits, dependencies, mounts, `DLAP_BACKUP_DIR` and the passphrase mount, ship command passed intact, health check, the database service untouched, ordering before the base overlay, `verify.sh` |
| U | Library | Row count comparison, counts taken from a dump, schedules, key/value files, encryption round trip, wrong passphrase |
| K | Key escrow | Fingerprint, key forms, env file parsing, check against sets, seal and unseal, wrong passphrase, damage, binary files |
| B | Logical backup | Set contents, manifest, counts equal the live counts, no password hash, failure leaves nothing, retention, lock, new database picked up |
| R | Logical restore | Refusals (consent, checksum, counts, role, key, sessions), restore after damage with digests equal, privileges, sequences, extensions, subset, shipped and encrypted sets |
| S | Shipping | Layout of keys, encryption, `SHA256SUMS.shipped` last, retry, partial segments, timeout, no `PGPASSWORD`, markers |
| P | Base backup and point in time | Base backup set, restore to a restore point, a time, the latest moment (partial segment), immediate; refusals; gaps; retention trims WAL; restore from the encrypted off-host copy. P-40 to P-49 (Q-752): a base copy of a segment shorter than the archive's is replaced and every row is recovered, a longer base copy is kept, WAL that cannot be read through to the newest archived segment fails the restore with exit 1 and no directory, the opt-out |
| A | Agent | `status` thresholds, settings refused, first backups by itself, supervision of the receiver, orderly stop, lost slot recovery |
| D | Drill | The table above |
| SD | `stack-drill.sh` arguments | `--help`, refusals with exit 2 for bad arguments (nothing is started and the lock is not taken), no literal credential, the properties the guide promises are checked |
| SM | Merged compose model of the full-stack drill | The ten services, the Keycloak import mount replaced by the import volume only with the restore override, the off-host volumes external and mounted as promised, the 30 s WAL cycle, the delete executor in dry run, the agent unchanged, the model fails without its variables |
| KR | `keycloak-realm.sh` against prepared volumes | Refusals (no passphrase, no container, key prefix or realm name that escapes the volume, a backup without `SHA256SUMS.shipped`, a damaged file), a wrong passphrase leaves nothing behind, a restored realm equals the exported bytes, the stored object is encrypted, `wipe` |
| KS | `keycloak-realm-backup.sh` (DR-03) | Refusals, a run without and with an off-host command, the settings file parsed and never executed, shipping order (`SHA256SUMS.shipped` last) and retry, a failing command or export, retention that never removes an unshipped set, `status.env` and `check` (`--quiet`, an old success, a failed run, no status), the lock (live and stale), and the cron file and both systemd units against the guide line for line |
| F | Full-stack drill | The table in section 9.3 |

## 11. Limits and what is open

- **Single host.** Everything here protects one PostgreSQL on one VM. Replication and failover are HA-02 (GA).
- **Logical backups are per database,** not one snapshot across databases; the physical backup and WAL are one consistent cluster. For a restore that must be consistent across databases, use the point-in-time restore.
- **Tablespaces** are not used and not supported by `restore-pitr.sh`.
- **The agent connects as the bootstrap superuser.** A backup role with only `REPLICATION` and read access is future work (Q-297).
- **Same PostgreSQL major version.** A physical restore needs the same major version and architecture. A logical restore may go to a newer major version.
- **Keycloak** in the quickstart is protected only by `keycloak-realm.sh backup`, which stops the container for the length of a copy; the host's cron or systemd timer must run it through `keycloak-realm-backup.sh` (section 8.7), and the realm's RPO is that interval. In the pilot Keycloak is in PostgreSQL.
- **The full-stack drill** has been run on one development host and has not covered the UI, the gateway, `da-mcp-pep` or a real object store (section 9.3). One of its first runs showed a point-in-time restore that did not replay the WAL; the most likely cause is fixed and tested (section 8.3, Q-752), but the original evidence was lost, so it is not proven.
- **No Kafka replay** of events lost in flight (section 3.1, Q-295).
- **Not yet exercised on a customer VM** or on the target e2-standard-2. The timings are from a Docker Desktop VM with 20 CPUs; the shape holds, the numbers need re-measuring (section 2.1).
- **Monitoring.** The agent exposes a health check and `status`; wiring an alert to the container health is up to the operator. A Prometheus metric is not written yet.
