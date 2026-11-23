#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Named tests of the backup and restore tools (DR-01), run against a real PostgreSQL.
#
# This file runs INSIDE a postgres:15.1 container, as the postgres user, with deployment/dr mounted
# read-only at /opt/dr. test-dr.sh starts it; do not run it on a workstation. It creates a private
# cluster under /tmp (listening on 127.0.0.1:5432, with the image's own trust rules for the loopback
# interface), seeds it with every kind of object the DLAP schemas use, and exercises the tools.
#
# Sections (test ids): U library, K key escrow, B logical backup, R logical restore, S shipping,
# P base backup and point-in-time restore, A agent. Usage: unit.sh [SECTION...]  (default: all)
# Exit: 0 every test passed; 1 a test failed.

set -uo pipefail

DR=/opt/dr
T=/tmp/dr-unit
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=dlap PGDATABASE=postgres
export DLAP_DR_DATA="$T/data"
unset PGPASSWORD DLAP_ENCRYPTION_KEY_FINGERPRINT DLAP_BACKUP_SHIP_CMD DLAP_BACKUP_PASSPHRASE_FILE

PASSED=0
FAILED=0
ok()  { PASSED=$((PASSED + 1)); echo "  ok    $*"; }
bad() { FAILED=$((FAILED + 1)); echo "  FAIL  $*" >&2; }
check() {
  local d="$1"
  shift
  if "$@" > /dev/null 2>&1; then
    ok "$d"
  else
    bad "$d"
    if [ -n "${OUT:-}" ]; then printf '        last output: %s\n' "$(printf '%s' "$OUT" | tail -n 6 | cut -c1-300)" >&2; fi
  fi
}
# run <command...>: capture the output in OUT and the status in RC.
run() { OUT="$("$@" 2>&1)"; RC=$?; export OUT RC; }
rc_is() { [ "$RC" -eq "$1" ]; }
out_has() { grep -qF -- "$1" <<< "$OUT"; }
out_lacks() { ! grep -qF -- "$1" <<< "$OUT"; }
q() { psql -X -qAt -v ON_ERROR_STOP=1 -d "$1" -c "$2"; }
qf() { psql -X -qAt -v ON_ERROR_STOP=1 -d "$1"; }
src() { ( source "$DR/lib.sh"; "$@" ); }
section() { echo; echo "== $*"; }
wanted() { [ "${#SECTIONS[@]}" -eq 0 ] && return 0; local s; for s in "${SECTIONS[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }

SECTIONS=("$@")

# pids_of <pattern>: process ids whose command line contains the pattern (no ps or pgrep in the image).
pids_of() {
  local p cmd
  for p in /proc/[0-9]*; do
    cmd="$({ tr '\0' ' ' < "$p/cmdline"; } 2> /dev/null || true)"
    case "$cmd" in *"$1"*) [ "${p#/proc/}" = "$$" ] || echo "${p#/proc/}" ;; esac
  done
}

# wait_for <seconds> <command...>: poll until the command succeeds.
wait_for() {
  local limit="$1" i=0
  shift
  while [ "$i" -lt "$limit" ]; do
    if "$@" > /dev/null 2>&1; then return 0; fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

# --- The cluster ------------------------------------------------------------------------------------

rm -rf "$T"
mkdir -p "$T/data" "$T/work"
export PGDATA="$T/pgdata"
initdb -D "$PGDATA" -U dlap --auth-local=trust --auth-host=trust --data-checksums > /dev/null || { echo "initdb failed" >&2; exit 1; }
pg_ctl -D "$PGDATA" -o "-c listen_addresses=127.0.0.1 -c port=5432 -c unix_socket_directories=$T" -l "$T/pg.log" -w start > /dev/null || { cat "$T/pg.log" >&2; exit 1; }
trap 'pg_ctl -D "$PGDATA" -m immediate stop > /dev/null 2>&1; for p in $(pids_of "pg_receivewal -D $T"); do kill "$p" 2> /dev/null; done' EXIT

seed() {
  q postgres "CREATE DATABASE dlap_common"
  q postgres "CREATE DATABASE da_admin"
  q postgres "CREATE DATABASE da_logingest"
  q postgres "CREATE ROLE da_unit_user LOGIN"
  # A role with a password: a dump of the roles that forgot --no-role-passwords would carry its hash.
  q postgres "CREATE ROLE da_unit_pw LOGIN PASSWORD '$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' ')'"
  qf dlap_common > /dev/null <<'SQL'
CREATE EXTENSION pgcrypto;
CREATE EXTENSION "uuid-ossp";
CREATE SCHEMA dlap_common;
CREATE TABLE dlap_common.schema_upgrades (file_name text PRIMARY KEY, checksum_sha256 text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now());
INSERT INTO dlap_common.schema_upgrades (file_name, checksum_sha256) VALUES ('2026-01-01_a.sql', 'aa'), ('2026-02-01_b.sql', 'bb');
CREATE TABLE dlap_common.dlap_assets (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(), name text NOT NULL, labels text[], meta jsonb,
  size_bytes numeric(20,2), created_at timestamptz NOT NULL DEFAULT now(), payload bytea);
INSERT INTO dlap_common.dlap_assets (name, labels, meta, size_bytes, created_at, payload)
SELECT 'Ünïcode "quoted" back\slash tab' || E'\t' || 'x ' || g || E'\nsecond line', ARRAY['a', 'b' || g], jsonb_build_object('n', g, 's', 'v"' || g),
       g * 1.25, timestamptz '2026-01-01 00:00:00+00' + g * interval '1 minute', gen_random_bytes(16)
  FROM generate_series(1, 2000) g;
CREATE INDEX idx_assets_name ON dlap_common.dlap_assets (name);
CREATE TABLE dlap_common.dlap_asset_label_assignments (asset_id uuid REFERENCES dlap_common.dlap_assets (id), label text, PRIMARY KEY (asset_id, label));
INSERT INTO dlap_common.dlap_asset_label_assignments SELECT id, 'pii' FROM dlap_common.dlap_assets LIMIT 500;
CREATE VIEW dlap_common.v_assets AS SELECT id, name FROM dlap_common.dlap_assets;
CREATE SEQUENCE dlap_common.seq_x START 1000;
SELECT setval('dlap_common.seq_x', 4242);
CREATE TABLE dlap_common."Odd Name" (id int);
INSERT INTO dlap_common."Odd Name" SELECT g FROM generate_series(1, 7) g;
CREATE TABLE dlap_common.empty_table (id int);
GRANT USAGE ON SCHEMA dlap_common TO da_unit_user;
GRANT SELECT ON dlap_common.dlap_assets TO da_unit_user;
SQL
  qf da_logingest > /dev/null <<'SQL'
CREATE SCHEMA dlap_logingest;
CREATE TABLE dlap_logingest.log_events (event_id text NOT NULL, ingest_time timestamptz NOT NULL, payload jsonb, PRIMARY KEY (event_id, ingest_time)) PARTITION BY RANGE (ingest_time);
CREATE TABLE dlap_logingest.log_events_2026_09 PARTITION OF dlap_logingest.log_events FOR VALUES FROM ('2026-09-01+00') TO ('2026-10-01+00');
CREATE TABLE dlap_logingest.log_events_2026_10 PARTITION OF dlap_logingest.log_events FOR VALUES FROM ('2026-10-01+00') TO ('2026-11-01+00');
CREATE TABLE dlap_logingest.log_events_default PARTITION OF dlap_logingest.log_events DEFAULT;
INSERT INTO dlap_logingest.log_events
SELECT 'e' || g, timestamptz '2026-08-15 00:00:00+00' + g * interval '1 hour', jsonb_build_object('g', g) FROM generate_series(1, 3000) g;
SQL
  qf da_admin > /dev/null <<'SQL'
CREATE SCHEMA dlap_admin;
CREATE TABLE dlap_admin.dlap_cloud_connection (id text PRIMARY KEY, name text, encrypted_credentials text);
INSERT INTO dlap_admin.dlap_cloud_connection VALUES ('c1', 'one', 'v2:AAAA:BBBB'), ('c2', 'two', NULL), ('c3', 'three', '');
SQL
}

# digests <db>: "table<TAB>md5 of its rows" for every ordinary table, in a stable order.
digests() {
  psql -X -qAt -v ON_ERROR_STOP=1 -F $'\t' -d "$1" <<'SQL'
SELECT format('SELECT %L, md5(coalesce(sum((''x'' || substr(md5(t::text), 1, 16))::bit(64)::bigint::numeric)::text, ''0'')) FROM %s t', format('%I.%I', n.nspname, c.relname), format('%I.%I', n.nspname, c.relname))
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname !~ '^pg_toast'
 ORDER BY 1
\gexec
SQL
}
counts() { src dr_target_rowcounts "$1"; }

seed

latest_set() { find "$DLAP_DR_DATA/logical" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' | LC_ALL=C sort | tail -n 1; }

# ======================================================================================================
# U: the library
# ======================================================================================================
if wanted U; then
section "U library"

mkdir -p "$T/u"
# dr_compare_rowcounts
printf 'a\tpublic.t\t5\na\tpublic.u\t0\n' > "$T/u/exp.tsv"
printf 'a\tpublic.t\t5\na\tpublic.u\t0\n' > "$T/u/act-same.tsv"
printf 'a\tpublic.t\t4\na\tpublic.u\t0\n' > "$T/u/act-diff.tsv"
printf 'a\tpublic.t\t5\n' > "$T/u/act-missing.tsv"
printf 'a\tpublic.t\t5\na\tpublic.u\t0\na\tpublic.extra_empty\t0\n' > "$T/u/act-extra-empty.tsv"
printf 'a\tpublic.t\t5\na\tpublic.u\t0\na\tpublic.extra_full\t3\n' > "$T/u/act-extra-full.tsv"
run src dr_compare_rowcounts "$T/u/exp.tsv" "$T/u/act-same.tsv"
check "U-01 equal counts compare equal (exit 0, summary counts tables and rows)" test "$RC" -eq 0 -a "$OUT" = "SUMMARY tables=2 rows=5 mismatches=0"
run src dr_compare_rowcounts "$T/u/exp.tsv" "$T/u/act-diff.tsv"
check "U-02 a differing count is a mismatch naming table, expected and actual" bash -c "[ \$RC -eq 1 ] && grep -q 'MISMATCH a public.t expected=5 actual=4' <<< \"\$OUT\""
run src dr_compare_rowcounts "$T/u/exp.tsv" "$T/u/act-missing.tsv"
check "U-03 a table missing from the target is a mismatch" bash -c "[ \$RC -eq 1 ] && grep -q 'public.u expected=0 actual=missing' <<< \"\$OUT\""
run src dr_compare_rowcounts "$T/u/exp.tsv" "$T/u/act-extra-empty.tsv"
check "U-04 a table the dump does not list is fine when it is empty" test "$RC" -eq 0
run src dr_compare_rowcounts "$T/u/exp.tsv" "$T/u/act-extra-full.tsv"
check "U-05 a table the dump does not list is a mismatch when it holds rows" bash -c "[ \$RC -eq 1 ] && grep -q 'public.extra_full expected=0 actual=3' <<< \"\$OUT\""

# dr_dump_rowcounts against a real dump: every kind of table, special characters, partitions.
pg_dump -Fc -C -d dlap_common -f "$T/u/common.dump"
pg_dump -Fc -C -d da_logingest -f "$T/u/logingest.dump"
src dr_dump_rowcounts "$T/u/common.dump" dlap_common > "$T/u/common.cnt"
src dr_dump_rowcounts "$T/u/logingest.dump" da_logingest > "$T/u/logingest.cnt"
counts dlap_common > "$T/u/common.live"
counts da_logingest > "$T/u/logingest.live"
check "U-06 counts taken from a dump equal the live counts (all tables of dlap_common)" cmp -s "$T/u/common.cnt" "$T/u/common.live"
check "U-07 counts of the dump include an empty table as 0 rows" grep -q $'dlap_common.empty_table\t0$' "$T/u/common.cnt"
check "U-08 counts of the dump include a table with a quoted name" grep -q $'dlap_common."Odd Name"\t7$' "$T/u/common.cnt"
check "U-09 rows with embedded newlines, tabs and backslashes count once each" grep -q $'dlap_common.dlap_assets\t2000$' "$T/u/common.cnt"
check "U-10 partitions are counted as leaf tables and add up (3000 rows)" bash -c "awk -F'\t' '{s+=\$3} END{exit s!=3000}' '$T/u/logingest.cnt' && ! grep -q 'log_events	' '$T/u/logingest.cnt'"
check "U-11 live and dump counts of the partitioned database agree" cmp -s "$T/u/logingest.cnt" "$T/u/logingest.live"

# schedules
d() { date -u -d "$1" +%s; }
check "U-12 daily: a first run (no success yet) is due at once" src dr_due_daily 02:30 0 "$(d '2026-10-19 01:00')"
check "U-13 daily: not due before today's slot" bash -c "source $DR/lib.sh; ! dr_due_daily 02:30 $(d '2026-10-18 02:31') $(d '2026-10-19 02:29')"
check "U-14 daily: due after the slot when the last success was yesterday" src dr_due_daily 02:30 "$(d '2026-10-18 02:31')" "$(d '2026-10-19 02:31')"
check "U-15 daily: not due again after a success today" bash -c "source $DR/lib.sh; ! dr_due_daily 02:30 $(d '2026-10-19 02:35') $(d '2026-10-19 09:00')"
check "U-16 daily: a host that was down at the slot catches up when it returns" src dr_due_daily 02:30 "$(d '2026-10-18 02:31')" "$(d '2026-10-19 15:00')"
check "U-17 every N days: not due after 3 of 7 days" bash -c "source $DR/lib.sh; ! dr_due_every 7 03:30 $(d '2026-10-16 03:40') $(d '2026-10-19 04:00')"
check "U-18 every N days: due after 7 days once the slot has passed" src dr_due_every 7 03:30 "$(d '2026-10-12 03:40')" "$(d '2026-10-19 03:31')"
check "U-19 every N days: not due before the slot even after 8 days" bash -c "source $DR/lib.sh; ! dr_due_every 7 03:30 $(d '2026-10-11 03:40') $(d '2026-10-19 03:00')"

# key/value files
KV="$T/u/status.env"
rm -f "$KV"
src dr_kv_set "$KV" A=1 B=two
src dr_kv_set "$KV" B=three C=4
check "U-20 dr_kv_set replaces a key, adds a key and keeps the others" bash -c "source $DR/lib.sh; [ \"\$(dr_kv $KV A)\" = 1 ] && [ \"\$(dr_kv $KV B)\" = three ] && [ \"\$(dr_kv $KV C)\" = 4 ] && [ \"\$(grep -c '^B=' $KV)\" = 1 ]"
check "U-21 dr_kv of an absent file or key is empty" bash -c "source $DR/lib.sh; [ -z \"\$(dr_kv $T/u/none.env A)\" ] && [ -z \"\$(dr_kv $KV Z)\" ]"
check "U-22 dr_epoch of nothing or garbage is 0" bash -c "source $DR/lib.sh; [ \"\$(dr_epoch '')\" = 0 ] && [ \"\$(dr_epoch 'not a date')\" = 0 ]"

# encryption
printf 'correct horse battery staple\n' > "$T/u/pass"
printf 'another passphrase\n' > "$T/u/pass2"
head -c 100000 /dev/urandom > "$T/u/plain.bin"
export DLAP_BACKUP_PASSPHRASE_FILE="$T/u/pass"
src dr_encrypt < "$T/u/plain.bin" > "$T/u/enc1"
src dr_encrypt < "$T/u/plain.bin" > "$T/u/enc2"
check "U-23 an encrypted copy decrypts to the original bytes" bash -c "source $DR/lib.sh; dr_decrypt < $T/u/enc1 | cmp -s - $T/u/plain.bin"
check "U-24 the ciphertext does not contain the plaintext and differs per run (random salt)" bash -c "! cmp -s $T/u/enc1 $T/u/plain.bin && ! cmp -s $T/u/enc1 $T/u/enc2"
check "U-25 a wrong passphrase does not decrypt" bash -c "source $DR/lib.sh; DR_PASSFILE=$T/u/pass2; ! dr_decrypt < $T/u/enc1 > /dev/null 2>&1"
unset DLAP_BACKUP_PASSPHRASE_FILE

# connection helpers
check "U-26 dr_databases lists every non-template database except postgres" bash -c "source $DR/lib.sh; [ \"\$(dr_databases | tr '\n' ' ')\" = 'da_admin da_logingest dlap_common ' ]"
check "U-27 DLAP_DR_DATABASES overrides the discovery" bash -c "source $DR/lib.sh; DLAP_DR_DATABASES='a, b' ; [ \"\$(dr_databases | tr '\n' ' ')\" = 'a b ' ]"
check "U-28 the archive key is <system id>-tl<timeline>" bash -c "source $DR/lib.sh; [[ \"\$(dr_archive_key)\" =~ ^[0-9]+-tl1\$ ]]"
mkdir -p "$T/u/gen/status"
printf 'GENERATION=2\n' > "$T/u/gen/status/archive-generation"
check "U-29 a broken chain gives the archive a generation suffix (-g2)" bash -c "source $DR/lib.sh; DR_DATA=$T/u/gen; [[ \"\$(dr_archive_key)\" =~ ^[0-9]+-tl1-g2\$ ]]"
check "U-30 dr_require_space passes when the space is there" src dr_require_space /tmp 1 "a test"
run bash -c "source $DR/lib.sh; dr_require_space /tmp 999999999999999999 'a huge file'"
check "U-31 and refuses (exit 2) with the sizes when it is not" bash -c "[ \$RC -eq 2 ] && grep -q 'not enough free space in /tmp for a huge file' <<< \"\$OUT\""
fi

# ======================================================================================================
# K: key escrow
# ======================================================================================================
if wanted K; then
section "K key escrow"
K="$DR/key-escrow.sh"
mkdir -p "$T/k"
KEY1="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
KEY2="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
printf '# comment\nPOSTGRES_PASSWORD=x\nDLAP_ENCRYPTION_KEY=%s\nOTHER=y\n' "$KEY1" > "$T/k/env1"
printf 'DLAP_ENCRYPTION_KEY="%s"\n' "$KEY1" > "$T/k/env1-quoted"
printf 'DLAP_ENCRYPTION_KEY=%s\nDLAP_ENCRYPTION_KEY=%s\n' "$KEY2" "$KEY1" > "$T/k/env1-last"
printf 'DLAP_ENCRYPTION_KEY=%s\n' "$KEY2" > "$T/k/env2"
printf 'DLAP_ENCRYPTION_KEY=CHANGE_ME_64_hex_characters\n' > "$T/k/env-placeholder"
printf 'DLAP_ENCRYPTION_KEY=abc123\n' > "$T/k/env-short"
printf 'DLAP_ENCRYPTION_KEY=%s\n' "0123456789abcdef0123456789abcdef" > "$T/k/env-legacy"
printf 'OTHER=1\n' > "$T/k/env-none"

run bash "$K" fingerprint --env-file "$T/k/env1"
FP1="$OUT"
check "K-01 fingerprint is fp1: and 16 hex digits" bash -c "[[ '$FP1' =~ ^fp1:[0-9a-f]{16}\$ ]]"
check "K-02 the fingerprint does not contain the key" bash -c "! grep -qF '$KEY1' <<< \"\$OUT\""
run bash "$K" fingerprint --env-file "$T/k/env1-quoted"
check "K-03 a quoted value gives the same fingerprint" test "$OUT" = "$FP1"
run bash "$K" fingerprint --env-file "$T/k/env1-last"
check "K-04 the last assignment in the file wins" test "$OUT" = "$FP1"
run bash "$K" fingerprint --env-file "$T/k/env2"
check "K-05 another key gives another fingerprint" test "$OUT" != "$FP1"
run env DLAP_ENCRYPTION_KEY="$KEY1" bash "$K" fingerprint
check "K-06 the key can come from the environment" test "$OUT" = "$FP1"
run bash "$K" fingerprint --env-file "$T/k/env-placeholder"
check "K-07 the placeholder of the example file is refused (exit 2)" rc_is 2
run bash "$K" fingerprint --env-file "$T/k/env-short"
check "K-08 a key of the wrong length is refused (exit 2) and says what is accepted" bash -c "[ \$RC -eq 2 ] && grep -q '64 hex characters' <<< \"\$OUT\""
run bash "$K" fingerprint --env-file "$T/k/env-legacy"
check "K-09 the 32 character legacy key is accepted with a warning" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN' <<< \"\$OUT\""
run bash "$K" fingerprint --env-file "$T/k/env-none"
check "K-10 a file without the key is refused (exit 2)" rc_is 2
run bash "$K" fingerprint --env-file "$T/k/missing"
check "K-11 a missing file is refused (exit 2)" rc_is 2

mkdir -p "$T/k/setA" "$T/k/setB" "$T/k/setU"
printf 'KEY_FINGERPRINT=%s\n' "$FP1" > "$T/k/setA/manifest.env"
printf 'KEY_FINGERPRINT=fp1:0000000000000000\n' > "$T/k/setB/manifest.env"
printf 'KEY_FINGERPRINT=unset\n' > "$T/k/setU/manifest.env"
run bash "$K" check --env-file "$T/k/env1" --against "$T/k/setA"
check "K-12 check passes when the set carries the fingerprint of the key" rc_is 0
run bash "$K" check --env-file "$T/k/env1" --against "$T/k/setA" --against "$T/k/setB"
check "K-13 check fails (exit 1) when one set was taken with another key" bash -c "[ \$RC -eq 1 ] && grep -q 'fp1:0000000000000000' <<< \"\$OUT\""
run bash "$K" check --env-file "$T/k/env1" --against "$T/k/setU"
check "K-14 a set without a fingerprint is a warning, not a failure" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN' <<< \"\$OUT\""

# seal / unseal
printf 'POSTGRES_PASSWORD=p\nDLAP_ENCRYPTION_KEY=%s\n' "$KEY1" > "$T/k/secrets.env"
chmod 600 "$T/k/pass" 2> /dev/null; cp "$T/u/pass" "$T/k/pass" 2> /dev/null || printf 'correct horse battery staple\n' > "$T/k/pass"
chmod 600 "$T/k/pass"
printf 'wrong one\n' > "$T/k/pass-wrong"; chmod 600 "$T/k/pass-wrong"
run bash "$K" seal --in "$T/k/secrets.env" --out "$T/k/sealed" --passphrase-file "$T/k/pass"
check "K-15 seal writes a file" bash -c "[ \$RC -eq 0 ] && [ -s $T/k/sealed ]"
check "K-16 the sealed file starts with a readable header and does not contain the key" bash -c "head -n 1 $T/k/sealed | grep -q '^DLAP-ESCROW-V1 check=[0-9a-f]\{16\}\$' && ! grep -aqF '$KEY1' $T/k/sealed"
run bash "$K" unseal --in "$T/k/sealed" --out "$T/k/opened" --passphrase-file "$T/k/pass"
check "K-17 unseal restores the file byte for byte" bash -c "[ \$RC -eq 0 ] && cmp -s $T/k/opened $T/k/secrets.env"
run bash "$K" unseal --in "$T/k/sealed" --out "$T/k/opened-wrong" --passphrase-file "$T/k/pass-wrong"
check "K-18 the wrong passphrase fails (exit 1) and writes nothing" bash -c "[ \$RC -eq 1 ] && [ ! -e $T/k/opened-wrong ] && ! ls $T/k/opened-wrong.* > /dev/null 2>&1"
cp "$T/k/sealed" "$T/k/damaged"
printf 'X' | dd of="$T/k/damaged" bs=1 seek=200 conv=notrunc 2> /dev/null
run bash "$K" unseal --in "$T/k/damaged" --out "$T/k/opened-damaged" --passphrase-file "$T/k/pass"
check "K-19 a damaged sealed file fails (exit 1) and writes nothing" bash -c "[ \$RC -eq 1 ] && [ ! -e $T/k/opened-damaged ]"
run bash "$K" seal --in "$T/k/secrets.env" --out "$T/k/sealed" --passphrase-file "$T/k/pass"
check "K-20 seal never overwrites (exit 2)" rc_is 2
run bash "$K" unseal --in "$T/k/secrets.env" --out "$T/k/opened-x" --passphrase-file "$T/k/pass"
check "K-21 a file that is not sealed is refused (exit 2)" rc_is 2
run bash "$K" seal --in "$T/k/secrets.env" --out "$T/k/sealed2" --passphrase-file "$T/k/none"
check "K-22 a missing passphrase file is refused (exit 2)" rc_is 2
: > "$T/k/pass-empty"
run bash "$K" seal --in "$T/k/secrets.env" --out "$T/k/sealed3" --passphrase-file "$T/k/pass-empty"
check "K-23 an empty passphrase file is refused (exit 2)" rc_is 2
head -c 5000 /dev/urandom > "$T/k/binary"
bash "$K" seal --in "$T/k/binary" --out "$T/k/binary.sealed" --passphrase-file "$T/k/pass" > /dev/null 2>&1
bash "$K" unseal --in "$T/k/binary.sealed" --out "$T/k/binary.opened" --passphrase-file "$T/k/pass" > /dev/null 2>&1
check "K-24 a binary file survives seal and unseal byte for byte" cmp -s "$T/k/binary" "$T/k/binary.opened"
fi

# ======================================================================================================
# B: logical backup
# ======================================================================================================
if wanted B; then
section "B logical backup"
BK="$DR/backup.sh"
export DLAP_ENCRYPTION_KEY_FINGERPRINT="fp1:0123456789abcdef"
run bash "$BK"
check "B-01 backup.sh exits 0 on a seeded cluster" rc_is 0
SET1="$(latest_set)"
S1="$DLAP_DR_DATA/logical/$SET1"
check "B-02 the set holds a dump per database, globals, row counts, manifest and checksums" bash -c "cd $S1 && ls | tr '\n' ' ' | grep -q 'SHA256SUMS' && [ -s da_admin.dump ] && [ -s da_logingest.dump ] && [ -s dlap_common.dump ] && [ -s globals.sql ] && [ -s rowcounts.tsv ] && [ -s manifest.env ]"
check "B-03 the checksums verify" bash -c "cd $S1 && sha256sum -c --quiet SHA256SUMS"
check "B-04 the manifest names format, databases, tables and rows" bash -c "grep -q '^FORMAT=dlap-dr-logical-1\$' $S1/manifest.env && grep -q '^DATABASES=da_admin,da_logingest,dlap_common\$' $S1/manifest.env && grep -q '^TABLES=' $S1/manifest.env && grep -q '^ROWS=' $S1/manifest.env"
check "B-05 the manifest records the key fingerprint from the environment" grep -q '^KEY_FINGERPRINT=fp1:0123456789abcdef$' "$S1/manifest.env"
check "B-06 the manifest counts the stored encrypted credentials (1 of 3 rows is non-empty)" grep -q '^ENCRYPTED_CREDENTIAL_ROWS=1$' "$S1/manifest.env"
check "B-07 the manifest records the schema upgrade ledger size (2)" grep -q '^SCHEMA_UPGRADES=2$' "$S1/manifest.env"
check "B-08 the manifest says no Keycloak database was backed up" grep -q '^KEYCLOAK_DATABASE=absent$' "$S1/manifest.env"
live="$(for d in da_admin da_logingest dlap_common; do counts "$d"; done)"
check "B-09 the row counts in the set equal the live counts of every table" bash -c "diff <(sort $S1/rowcounts.tsv) <(sort <<< \"\$1\") > /dev/null" _ "$live"
check "B-10 the globals file names the roles (also one that has a password) and holds no password hash" bash -c "grep -q 'CREATE ROLE da_unit_user' $S1/globals.sql && grep -q 'CREATE ROLE da_unit_pw' $S1/globals.sql && ! grep -q 'SCRAM-SHA-256\\|PASSWORD' $S1/globals.sql"
check "B-11 the set is private to its owner (mode 700 folder, 600 files)" bash -c "[ \"\$(stat -c %a $S1)\" = 700 ] && [ \"\$(stat -c %a $S1/da_admin.dump)\" = 600 ]"
check "B-12 status/logical.env records the success" bash -c "grep -q '^LAST_ATTEMPT_RESULT=ok\$' $DLAP_DR_DATA/status/logical.env && grep -q '^LAST_SUCCESS_SET=$SET1\$' $DLAP_DR_DATA/status/logical.env"
check "B-13 no .tmp folder is left behind" bash -c "[ -z \"\$(find $DLAP_DR_DATA/logical -name '.tmp-*')\" ]"

# a database added later is picked up; a Keycloak database is reported
q postgres "CREATE DATABASE keycloak"
qf keycloak > /dev/null <<'SQL'
CREATE TABLE kc (id int); INSERT INTO kc VALUES (1), (2), (3);
SQL
sleep 1
run bash "$BK" --no-prune
SET2="$(latest_set)"
check "B-14 a database created later is backed up without a configuration change" bash -c "[ -s $DLAP_DR_DATA/logical/$SET2/keycloak.dump ] && grep -q '^DATABASES=da_admin,da_logingest,dlap_common,keycloak\$' $DLAP_DR_DATA/logical/$SET2/manifest.env"
check "B-15 the manifest then says the Keycloak database is present" grep -q '^KEYCLOAK_DATABASE=present$' "$DLAP_DR_DATA/logical/$SET2/manifest.env"
q postgres "DROP DATABASE keycloak"

# failure path
run bash "$BK" --databases nosuchdb
check "B-16 a failing dump exits non-zero" test "$RC" -ne 0
check "B-17 a failed run leaves no half set and no new set" bash -c "[ -z \"\$(find $DLAP_DR_DATA/logical -name '.tmp-*')\" ] && [ \"\$(find $DLAP_DR_DATA/logical -mindepth 1 -maxdepth 1 -name '[0-9]*Z' | wc -l)\" = 2 ]"
check "B-18 the failure is recorded for the status check" grep -q '^LAST_ATTEMPT_RESULT=failed$' "$DLAP_DR_DATA/status/logical.env"
check "B-19 a failure does not erase the last success" grep -q "^LAST_SUCCESS_SET=$SET2\$" "$DLAP_DR_DATA/status/logical.env"
run bash "$BK" --databases 'bad;name'
check "B-20 a database name with unsupported characters is refused (exit 2)" rc_is 2
run bash "$BK" --keep 0
check "B-21 --keep 0 is refused (exit 2)" rc_is 2
run bash "$BK" --bogus
check "B-22 an unknown argument is refused (exit 2)" rc_is 2

# lock
( source "$DR/lib.sh"; DLAP_DR_DATA="$DLAP_DR_DATA"; dr_lock logical; sleep 6 ) &
HOLD=$!
sleep 1
run bash "$BK"
check "B-23 a second backup while one runs is refused (exit 2) and says why" bash -c "[ \$RC -eq 2 ] && grep -q 'holds the lock' <<< \"\$OUT\""
wait "$HOLD" 2> /dev/null

# retention
for i in 1 2 3; do sleep 1; bash "$BK" > /dev/null 2>&1; done
run bash "$BK" --keep 3
check "B-24 --keep 3 leaves the 3 newest sets" bash -c "[ \"\$(find $DLAP_DR_DATA/logical -mindepth 1 -maxdepth 1 -name '[0-9]*Z' | wc -l)\" = 3 ]"
check "B-25 the newest set is among them" test -d "$DLAP_DR_DATA/logical/$(latest_set)"
unset DLAP_ENCRYPTION_KEY_FINGERPRINT
fi

# ======================================================================================================
# R: logical restore
# ======================================================================================================
if wanted R; then
section "R logical restore"
BK="$DR/backup.sh"
RS="$DR/restore.sh"
export DLAP_ENCRYPTION_KEY_FINGERPRINT="fp1:0123456789abcdef"
rm -rf "$DLAP_DR_DATA/logical"
bash "$BK" > /dev/null 2>&1
SETR="$(latest_set)"
SR="$DLAP_DR_DATA/logical/$SETR"
D_COMMON="$(digests dlap_common | md5sum | cut -c1-32)"
D_LOG="$(digests da_logingest | md5sum | cut -c1-32)"
D_ADMIN="$(digests da_admin | md5sum | cut -c1-32)"

# server-free verification
run env PGPORT=1 bash "$RS" --from "$SR" --verify-only
check "R-01 --verify-only checks the set without a server (exit 0)" bash -c "[ \$RC -eq 0 ] && grep -q 'set verified' <<< \"\$OUT\""
run bash "$RS" --from "$SR" --verify-only --report "$T/work/verify.report"
check "R-02 --verify-only writes a report with RESULT=verified-only" grep -q '^RESULT=verified-only$' "$T/work/verify.report"

# consent and plan
run bash "$RS" --from "$SR"
check "R-03 without --yes-replace-databases nothing happens (exit 2) and the plan names the databases" bash -c "[ \$RC -eq 2 ] && grep -q 'plan: drop and recreate da_admin,da_logingest,dlap_common' <<< \"\$OUT\""
check "R-04 the refusal changed nothing" test "$(digests dlap_common | md5sum | cut -c1-32)" = "$D_COMMON"
run bash "$RS"
check "R-05 --from is required (exit 2)" rc_is 2
run bash "$RS" --from "$T/nowhere"
check "R-06 a missing set folder is refused (exit 2)" rc_is 2

# damage, then restore
q dlap_common "DELETE FROM dlap_common.dlap_asset_label_assignments"
q dlap_common "DROP VIEW dlap_common.v_assets"
q dlap_common "UPDATE dlap_common.dlap_assets SET name = 'damaged' WHERE name LIKE 'Ü%'"
q dlap_common "DROP TABLE dlap_common.empty_table"
q dlap_common "SELECT setval('dlap_common.seq_x', 1)" > /dev/null
q da_logingest "TRUNCATE dlap_logingest.log_events"
q da_admin "DROP SCHEMA dlap_admin CASCADE"
run bash "$RS" --from "$SR" --yes-replace-databases --report "$T/work/restore.report"
check "R-07 restore exits 0 and says RESTORE VERIFIED" bash -c "[ \$RC -eq 0 ] && grep -q 'RESTORE VERIFIED' <<< \"\$OUT\""
check "R-08 the content of dlap_common is identical to before (digest of every table)" test "$(digests dlap_common | md5sum | cut -c1-32)" = "$D_COMMON"
check "R-09 the content of the partitioned database is identical (3000 rows in partitions)" test "$(digests da_logingest | md5sum | cut -c1-32)" = "$D_LOG"
check "R-10 the dropped schema of da_admin is back with its rows" test "$(digests da_admin | md5sum | cut -c1-32)" = "$D_ADMIN"
check "R-11 the view and the empty table are back" bash -c "[ \"\$(psql -X -qAt -d dlap_common -c \"SELECT count(*) FROM dlap_common.v_assets\")\" = 2000 ] && [ \"\$(psql -X -qAt -d dlap_common -c 'SELECT count(*) FROM dlap_common.empty_table')\" = 0 ]"
check "R-12 the sequence has its old value" test "$(q dlap_common "SELECT last_value FROM dlap_common.seq_x")" = "4242"
check "R-13 the privilege granted to a role is restored" test "$(q dlap_common "SELECT has_table_privilege('da_unit_user', 'dlap_common.dlap_assets', 'SELECT')")" = "t"
check "R-14 the extensions are back" test "$(q dlap_common "SELECT string_agg(extname, ',' ORDER BY extname) FROM pg_extension WHERE extname IN ('pgcrypto', 'uuid-ossp')")" = "pgcrypto,uuid-ossp"
check "R-15 the report records RESULT=ok with table and row totals" bash -c "grep -q '^RESULT=ok\$' $T/work/restore.report && grep -q '^TABLES=[1-9]' $T/work/restore.report && grep -q '^ROWS=[1-9]' $T/work/restore.report"
check "R-16 statistics were refreshed (the planner has row estimates)" test "$(q dlap_common "SELECT reltuples::int FROM pg_class WHERE oid = 'dlap_common.dlap_assets'::regclass")" -gt 0

# a damaged set is refused before anything is touched
cp -r "$SR" "$T/work/damaged"
printf 'X' | dd of="$T/work/damaged/dlap_common.dump" bs=1 seek=100 conv=notrunc 2> /dev/null
q da_admin "UPDATE dlap_admin.dlap_cloud_connection SET name = 'sentinel' WHERE id = 'c1'"
run bash "$RS" --from "$T/work/damaged" --yes-replace-databases
check "R-17 a dump that does not match its checksum is refused (exit 2) before any change" bash -c "[ \$RC -eq 2 ] && grep -q 'does not match SHA256SUMS' <<< \"\$OUT\" && [ \"\$(psql -X -qAt -d da_admin -c \"SELECT name FROM dlap_admin.dlap_cloud_connection WHERE id='c1'\")\" = sentinel ]"
cp -r "$SR" "$T/work/lying"
awk -F'\t' 'BEGIN{OFS="\t"} $2 == "dlap_common.empty_table" { next } { print }' "$SR/rowcounts.tsv" > "$T/work/lying/rowcounts.tsv"
printf 'dlap_common\tdlap_common.dlap_assets\t1999\n' >> "$T/work/lying/rowcounts.tsv"
( cd "$T/work/lying" && grep -v ' rowcounts.tsv$' SHA256SUMS > S.tmp && sha256sum rowcounts.tsv >> S.tmp && mv S.tmp SHA256SUMS )
run bash "$RS" --from "$T/work/lying" --yes-replace-databases
check "R-18 a row count list that disagrees with the dumps is refused (exit 2) although its checksum is valid" bash -c "[ \$RC -eq 2 ] && grep -q 'does not match the dumps' <<< \"\$OUT\""
cp -r "$SR" "$T/work/ghostrole"
printf 'CREATE ROLE ghost_role;\n' >> "$T/work/ghostrole/globals.sql"
( cd "$T/work/ghostrole" && grep -v ' globals.sql$' SHA256SUMS > S.tmp && sha256sum globals.sql >> S.tmp && mv S.tmp SHA256SUMS )
run bash "$RS" --from "$T/work/ghostrole" --yes-replace-databases
check "R-19 a role the server lacks is refused (exit 2) and named" bash -c "[ \$RC -eq 2 ] && grep -q 'ghost_role' <<< \"\$OUT\""

# the key
run env DLAP_ENCRYPTION_KEY_FINGERPRINT="fp1:ffffffffffffffff" bash "$RS" --from "$SR" --yes-replace-databases
check "R-20 a different key fingerprint is refused (exit 2), naming both" bash -c "[ \$RC -eq 2 ] && grep -q 'fp1:0123456789abcdef' <<< \"\$OUT\" && grep -q 'fp1:ffffffffffffffff' <<< \"\$OUT\""
run env -u DLAP_ENCRYPTION_KEY_FINGERPRINT bash "$RS" --from "$SR" --yes-replace-databases
check "R-21 no fingerprint here while the set has one is refused (exit 2)" rc_is 2
run env DLAP_ENCRYPTION_KEY_FINGERPRINT="fp1:ffffffffffffffff" bash "$RS" --from "$SR" --yes-replace-databases --ignore-key-fingerprint
check "R-22 --ignore-key-fingerprint goes on with a warning" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN' <<< \"\$OUT\""
check "R-23 a matching fingerprint is reported (matches the set)" bash -c "cd /; bash $RS --from $SR --yes-replace-databases 2>&1 | grep -q 'matches the set'"

# sessions
( psql -X -qAt -d da_admin -c "SELECT pg_sleep(30)" > /dev/null 2>&1 ) &
SLEEPER=$!
sleep 1
run bash "$RS" --from "$SR" --yes-replace-databases --databases da_admin
check "R-24 a session on the target database is refused (exit 2) and counted" bash -c "[ \$RC -eq 2 ] && grep -q '1 other session' <<< \"\$OUT\""
run bash "$RS" --from "$SR" --yes-replace-databases --databases da_admin --terminate-connections
check "R-25 --terminate-connections closes it and restores" bash -c "[ \$RC -eq 0 ] && grep -q 'closing 1 session' <<< \"\$OUT\""
kill "$SLEEPER" 2> /dev/null; wait "$SLEEPER" 2> /dev/null

# a subset
q da_admin "UPDATE dlap_admin.dlap_cloud_connection SET name = 'sentinel2' WHERE id = 'c1'"
q da_logingest "DELETE FROM dlap_logingest.log_events WHERE event_id = 'e1'"
run bash "$RS" --from "$SR" --yes-replace-databases --databases da_logingest
check "R-26 --databases restores only that database" bash -c "[ \$RC -eq 0 ] && [ \"\$(psql -X -qAt -d da_admin -c \"SELECT name FROM dlap_admin.dlap_cloud_connection WHERE id='c1'\")\" = sentinel2 ] && [ \"\$(psql -X -qAt -d da_logingest -c 'SELECT count(*) FROM dlap_logingest.log_events')\" = 3000 ]"
run bash "$RS" --from "$SR" --yes-replace-databases --databases nope
check "R-27 a database that is not in the set is refused (exit 2)" rc_is 2
run bash "$RS" --from latest --yes-replace-databases --databases da_admin
check "R-28 --from latest picks the newest set under the data folder" bash -c "[ \$RC -eq 0 ] && grep -q 'set $SETR' <<< \"\$OUT\""
check "R-29 restore wrote its elapsed time (timing for the RTO evidence)" bash -c "grep -q 'restored in [0-9]*s' <<< \"\$OUT\""

# a restore that comes out wrong is a failure, not a success: a stand-in pg_restore deletes a row after the real one ran
mkdir -p "$T/shim"
cat > "$T/shim/pg_restore" <<'EOS'
#!/bin/bash
/usr/lib/postgresql/15/bin/pg_restore "$@"
rc=$?
case " $* " in
  *" -C "*) psql -X -qAt -h 127.0.0.1 -U dlap -d dlap_common -c "DELETE FROM dlap_common.dlap_asset_label_assignments WHERE ctid IN (SELECT ctid FROM dlap_common.dlap_asset_label_assignments LIMIT 1)" > /dev/null ;;
esac
exit "$rc"
EOS
chmod +x "$T/shim/pg_restore"
run env PATH="$T/shim:$PATH" bash "$RS" --from "$SR" --yes-replace-databases --databases dlap_common --report "$T/work/mismatch.report"
check "R-39 a restore whose row counts differ from the set exits 1 and says so" bash -c "[ \$RC -eq 1 ] && grep -q 'ROW COUNTS DIFFER' <<< \"\$OUT\" && grep -q 'MISMATCH dlap_common dlap_common.dlap_asset_label_assignments expected=500 actual=499' <<< \"\$OUT\""
check "R-40 the report says mismatch" grep -q '^RESULT=mismatch$' "$T/work/mismatch.report"
rm -rf "$T/shim"
run bash "$RS" --from "$SR" --yes-replace-databases --databases dlap_common
check "R-41 the next honest restore repairs it and verifies" bash -c "[ \$RC -eq 0 ] && grep -q 'RESTORE VERIFIED' <<< \"\$OUT\""

# an encrypted, shipped copy
mkdir -p "$T/remote"
printf 'a long random passphrase for the shipped copy\n' > "$T/work/pass"
chmod 600 "$T/work/pass"
export DLAP_BACKUP_PASSPHRASE_FILE="$T/work/pass"
export DLAP_BACKUP_SHIP_CMD='mkdir -p "$(dirname "'"$T"'/remote/$2")" && cp "$1" "'"$T"'/remote/$2"'
run bash "$DR/ship.sh" --sets
check "R-30 ship.sh --sets copies the set to the remote folder" bash -c "[ \$RC -eq 0 ] && [ -f $T/remote/logical/$SETR/SHA256SUMS.shipped ] && [ -f $T/remote/logical/$SETR/dlap_common.dump.enc ]"
unset DLAP_BACKUP_SHIP_CMD
check "R-31 the shipped dump is not a PostgreSQL archive (it is encrypted)" bash -c "! pg_restore -l $T/remote/logical/$SETR/dlap_common.dump.enc > /dev/null 2>&1"
q dlap_common "TRUNCATE dlap_common.dlap_assets CASCADE"
export PGDATABASE=postgres
run bash "$RS" --from "$T/remote/logical/$SETR" --yes-replace-databases --databases dlap_common
check "R-32 a shipped, encrypted set restores with the passphrase and verifies" bash -c "[ \$RC -eq 0 ] && grep -q 'RESTORE VERIFIED' <<< \"\$OUT\" && [ \"\$(psql -X -qAt -d dlap_common -c 'SELECT count(*) FROM dlap_common.dlap_assets')\" = 2000 ]"
check "R-33 the content is identical after restoring from the shipped copy" test "$(digests dlap_common | md5sum | cut -c1-32)" = "$D_COMMON"
printf 'not the passphrase\n' > "$T/work/pass-wrong"; chmod 600 "$T/work/pass-wrong"
run env DLAP_BACKUP_PASSPHRASE_FILE="$T/work/pass-wrong" bash "$RS" --from "$T/remote/logical/$SETR" --yes-replace-databases
check "R-34 the wrong passphrase is refused (exit 2) before anything changes" bash -c "[ \$RC -eq 2 ] && grep -q 'wrong passphrase' <<< \"\$OUT\""
cp -r "$T/remote/logical/$SETR" "$T/work/noshipped"
rm -f "$T/work/noshipped/SHA256SUMS.shipped"
run bash "$RS" --from "$T/work/noshipped" --yes-replace-databases
check "R-35 an encrypted copy without SHA256SUMS.shipped is refused as incomplete (exit 2)" bash -c "[ \$RC -eq 2 ] && grep -q 'incomplete' <<< \"\$OUT\""
cp -r "$T/remote/logical/$SETR" "$T/work/flipped"
printf 'X' | dd of="$T/work/flipped/dlap_common.dump.enc" bs=1 seek=300 conv=notrunc 2> /dev/null
run bash "$RS" --from "$T/work/flipped" --yes-replace-databases
check "R-36 a damaged encrypted file is refused (exit 2)" bash -c "[ \$RC -eq 2 ] && grep -q 'SHA256SUMS.shipped' <<< \"\$OUT\""
no_work_left() { [ -z "$(find /tmp "$DLAP_DR_DATA/tmp" -maxdepth 1 \( -name 'dlap-restore*' -o -name 'restore-plain.*' \) 2> /dev/null)" ]; }
check "R-37 the work folder of a decryption is removed afterwards (it was in the backup volume, not in /tmp)" no_work_left
run bash "$RS" --from "$T/remote/logical/$SETR" --verify-only
check "R-38 an encrypted set can be verified without a server" rc_is 0
unset DLAP_BACKUP_PASSPHRASE_FILE DLAP_ENCRYPTION_KEY_FINGERPRINT
fi

# ======================================================================================================
# S: shipping
# ======================================================================================================
if wanted S; then
section "S shipping"
SH="$DR/ship.sh"
export DLAP_DR_DATA="$T/ship-data"
rm -rf "$DLAP_DR_DATA" "$T/sremote"
mkdir -p "$DLAP_DR_DATA/logical" "$DLAP_DR_DATA/base" "$DLAP_DR_DATA/wal"
printf 'pass for shipping tests\n' > "$T/work/spass"; chmod 600 "$T/work/spass"

# a fabricated tree: one logical set, one base set, two WAL segments and one partial
mkdir -p "$DLAP_DR_DATA/logical/20261019T020000Z" "$DLAP_DR_DATA/base/20261019T030000Z" "$DLAP_DR_DATA/wal/7000000000000000001-tl1"
L="$DLAP_DR_DATA/logical/20261019T020000Z"
B="$DLAP_DR_DATA/base/20261019T030000Z"
W="$DLAP_DR_DATA/wal/7000000000000000001-tl1"
printf 'dump one\n' > "$L/a.dump"; printf 'globals\n' > "$L/globals.sql"; printf 'x\t1\n' > "$L/rowcounts.tsv"
printf 'SET=20261019T020000Z\n' > "$L/manifest.env"; ( cd "$L" && sha256sum a.dump globals.sql rowcounts.tsv manifest.env > SHA256SUMS )
head -c 3000 /dev/urandom > "$B/base.tar.gz"; head -c 2000 /dev/urandom > "$B/pg_wal.tar.gz"; printf 'backup manifest\n' > "$B/backup_manifest"
printf 'SET=20261019T030000Z\n' > "$B/manifest.env"; ( cd "$B" && sha256sum base.tar.gz pg_wal.tar.gz backup_manifest manifest.env > SHA256SUMS )
head -c 100000 /dev/zero > "$W/000000010000000000000001"; head -c 100000 /dev/zero > "$W/000000010000000000000002"; head -c 50000 /dev/zero > "$W/000000010000000000000003.partial"
printf '1\t0/1\n' > "$W/00000002.history"

# the stand-in "remote": records its calls
CALLS="$T/work/calls.log"
mkdir -p "$T/work"
cat > "$T/work/ship-ok.sh" <<'EOS'
#!/bin/sh
# $1 file, $2 key. Records the call, checks the file is readable and PGPASSWORD is not visible.
[ -r "$1" ] || exit 9
[ -z "${PGPASSWORD:-}" ] || exit 8
mkdir -p "$(dirname "$SHIP_REMOTE/$2")"
cp "$1" "$SHIP_REMOTE/$2"
echo "$2" >> "$SHIP_CALLS"
EOS
chmod +x "$T/work/ship-ok.sh"
export SHIP_REMOTE="$T/sremote" SHIP_CALLS="$CALLS"
export PGPASSWORD=must-not-leak

run bash "$SH" --all
check "S-01 without DLAP_BACKUP_SHIP_CMD nothing is shipped and the status says disabled (exit 0)" bash -c "[ \$RC -eq 0 ] && grep -q '^MODE=disabled\$' $DLAP_DR_DATA/status/ship-wal.env"
export DLAP_BACKUP_SHIP_CMD="$T/work/ship-ok.sh \"\$1\" \"\$2\""
run bash "$SH" --all
check "S-02 a ship command without a passphrase file is refused (exit 2)" bash -c "[ \$RC -eq 2 ] && grep -q 'no passphrase file' <<< \"\$OUT\""
check "S-03 the refusal is recorded as blocked for the status check" grep -q '^MODE=blocked$' "$DLAP_DR_DATA/status/ship-wal.env"
check "S-04 nothing was called" test ! -e "$CALLS"

export DLAP_BACKUP_PASSPHRASE_FILE="$T/work/spass"
run bash "$SH" --all
check "S-05 with a passphrase file the pass succeeds (exit 0)" rc_is 0
LC_ALL=C sort "$CALLS" > "$T/work/calls.sorted"
check "S-06 the keys follow the documented layout" bash -c "diff <(printf '%s\n' \
 base/20261019T030000Z/SHA256SUMS base/20261019T030000Z/SHA256SUMS.shipped base/20261019T030000Z/backup_manifest.enc base/20261019T030000Z/base.tar.gz.enc base/20261019T030000Z/manifest.env base/20261019T030000Z/pg_wal.tar.gz.enc \
 logical/20261019T020000Z/SHA256SUMS logical/20261019T020000Z/SHA256SUMS.shipped logical/20261019T020000Z/a.dump.enc logical/20261019T020000Z/globals.sql.enc logical/20261019T020000Z/manifest.env logical/20261019T020000Z/rowcounts.tsv.enc \
 wal/7000000000000000001-tl1/000000010000000000000001.gz.enc wal/7000000000000000001-tl1/000000010000000000000002.gz.enc wal/7000000000000000001-tl1/000000010000000000000003.partial.gz.enc wal/7000000000000000001-tl1/00000002.history.gz.enc | LC_ALL=C sort) $T/work/calls.sorted"
check "S-07 manifest.env and SHA256SUMS go as they are, the rest encrypted" bash -c "cmp -s $L/manifest.env $T/sremote/logical/20261019T020000Z/manifest.env && cmp -s $L/SHA256SUMS $T/sremote/logical/20261019T020000Z/SHA256SUMS && ! cmp -s $L/a.dump $T/sremote/logical/20261019T020000Z/a.dump.enc && ! grep -aq 'dump one' $T/sremote/logical/20261019T020000Z/a.dump.enc"
check "S-08 SHA256SUMS.shipped is shipped last of its set" bash -c "grep -n 'logical/20261019T020000Z' $CALLS | tail -n 1 | grep -q 'SHA256SUMS.shipped'"
check "S-09 SHA256SUMS.shipped matches the encrypted files as uploaded" bash -c "cd $T/sremote/logical/20261019T020000Z && sha256sum -c --quiet SHA256SUMS.shipped"
check "S-10 a shipped dump decrypts to the original" bash -c "source $DR/lib.sh; DR_PASSFILE=$T/work/spass; dr_decrypt < $T/sremote/logical/20261019T020000Z/a.dump.enc | cmp -s - $L/a.dump"
check "S-11 a shipped WAL segment decrypts and unzips to the original" bash -c "source $DR/lib.sh; DR_PASSFILE=$T/work/spass; dr_decrypt < $T/sremote/wal/7000000000000000001-tl1/000000010000000000000001.gz.enc | gunzip -c | cmp -s - $W/000000010000000000000001"
check "S-12 the status records how far the off-host WAL copy reaches" bash -c "grep -q '^PENDING=0\$' $DLAP_DR_DATA/status/ship-wal.env && grep -q '^WAL_SHIPPED_UPTO_EPOCH=[0-9]' $DLAP_DR_DATA/status/ship-wal.env"
check "S-13 the ship command did not see PGPASSWORD (it would have exited 8)" test -s "$CALLS"

: > "$CALLS"
run bash "$SH" --all
check "S-14 a second pass ships nothing that is already shipped, except the unchanged partial is not repeated either" test ! -s "$CALLS"
printf 'more wal\n' >> "$W/000000010000000000000003.partial"
run bash "$SH" --wal
check "S-15 a partial segment that has grown is shipped again, under its partial name" bash -c "[ \"\$(cat $CALLS)\" = 'wal/7000000000000000001-tl1/000000010000000000000003.partial.gz.enc' ]"
: > "$CALLS"
mv "$W/000000010000000000000003.partial" "$W/000000010000000000000003"
run bash "$SH" --wal
check "S-16 when the segment completes it is shipped under its final name" bash -c "[ \"\$(cat $CALLS)\" = 'wal/7000000000000000001-tl1/000000010000000000000003.gz.enc' ]"
check "S-17 the marker of the finished partial is gone" test ! -e "$DLAP_DR_DATA/ship/.done/wal/7000000000000000001-tl1/000000010000000000000003.partial"

# failures are retried
: > "$CALLS"
head -c 1000 /dev/zero > "$W/000000010000000000000004"
cat > "$T/work/ship-fail.sh" <<'EOS'
#!/bin/sh
exit 3
EOS
chmod +x "$T/work/ship-fail.sh"
run env DLAP_BACKUP_SHIP_CMD="$T/work/ship-fail.sh" bash "$SH" --wal
check "S-18 a failing ship command exits 1" rc_is 1
check "S-19 the status records the pending file and keeps the old shipped-up-to time" bash -c "grep -q '^PENDING=1\$' $DLAP_DR_DATA/status/ship-wal.env && grep -q 'could not be shipped' $DLAP_DR_DATA/status/ship-wal.env"
check "S-20 nothing was marked as shipped by the failure" test ! -e "$DLAP_DR_DATA/ship/.done/wal/7000000000000000001-tl1/000000010000000000000004"
check "S-21 no staging file is left behind" bash -c "[ -z \"\$(ls $DLAP_DR_DATA/ship/stage)\" ]"
run bash "$SH" --wal
check "S-22 the next pass ships exactly the file that failed" bash -c "[ \$RC -eq 0 ] && [ \"\$(cat $CALLS)\" = 'wal/7000000000000000001-tl1/000000010000000000000004.gz.enc' ]"

# backup sets that fail are retried as a whole
mkdir -p "$DLAP_DR_DATA/logical/20261020T020000Z"
L2="$DLAP_DR_DATA/logical/20261020T020000Z"
printf 'dump two\n' > "$L2/b.dump"; printf 'SET=20261020T020000Z\n' > "$L2/manifest.env"; ( cd "$L2" && sha256sum b.dump manifest.env > SHA256SUMS )
: > "$CALLS"
run env DLAP_BACKUP_SHIP_CMD="$T/work/ship-fail.sh" bash "$SH" --sets
check "S-27 a set that cannot be uploaded: exit 1, the status counts it, SHA256SUMS.shipped is not written" bash -c "[ \$RC -eq 1 ] && grep -q '^PENDING=1\$' $DLAP_DR_DATA/status/ship-sets.env && [ ! -e $DLAP_DR_DATA/ship/.done/logical/20261020T020000Z/SHA256SUMS.shipped ]"
run bash "$SH" --sets
check "S-28 the next pass uploads the set, and only the set that was missing" bash -c "[ \$RC -eq 0 ] && [ \"\$(grep -c '^logical/20261020T020000Z/' $CALLS)\" = 4 ] && ! grep -q '^logical/20261019T020000Z/' $CALLS && [ -f $T/sremote/logical/20261020T020000Z/SHA256SUMS.shipped ]"
check "S-29 the sets status is clean again and records the time of the last complete pass" bash -c "grep -q '^LAST_ERROR=\$' $DLAP_DR_DATA/status/ship-sets.env && grep -q '^LAST_OK_UTC=' $DLAP_DR_DATA/status/ship-sets.env"

# timeout
cat > "$T/work/ship-slow.sh" <<'EOS'
#!/bin/sh
sleep 20
EOS
chmod +x "$T/work/ship-slow.sh"
head -c 1000 /dev/zero > "$W/000000010000000000000005"
s0=$SECONDS
run env DLAP_BACKUP_SHIP_CMD="$T/work/ship-slow.sh" DLAP_BACKUP_SHIP_TIMEOUT=2 bash "$SH" --wal
check "S-23 a ship command that hangs is stopped after DLAP_BACKUP_SHIP_TIMEOUT (exit 1, well under 20 s)" test "$RC" -eq 1 -a $((SECONDS - s0)) -lt 15
bash "$SH" --wal > /dev/null 2>&1

# plain override
rm -rf "$T/sremote"; rm -rf "$DLAP_DR_DATA/ship/.done"; : > "$CALLS"
run env -u DLAP_BACKUP_PASSPHRASE_FILE DLAP_BACKUP_SHIP_PLAINTEXT=1 bash "$SH" --wal
check "S-24 DLAP_BACKUP_SHIP_PLAINTEXT=1 ships without a passphrase file, as .gz, with a warning" bash -c "[ \$RC -eq 0 ] && grep -q 'PLAIN' <<< \"\$OUT\" && [ -f $T/sremote/wal/7000000000000000001-tl1/000000010000000000000001.gz ] && gunzip -c $T/sremote/wal/7000000000000000001-tl1/000000010000000000000001.gz | cmp -s - $W/000000010000000000000001"

# markers disappear with their files
rm -f "$W/000000010000000000000001"
bash "$SH" --wal > /dev/null 2>&1
check "S-25 the marker of a segment that was trimmed from the archive is removed" test ! -e "$DLAP_DR_DATA/ship/.done/wal/7000000000000000001-tl1/000000010000000000000001"

# lock
( source "$DR/lib.sh"; dr_lock ship-wal; sleep 6 ) &
HOLD=$!
sleep 1
run bash "$SH" --wal
check "S-26 two WAL passes at once: the second is refused (exit 2)" rc_is 2
wait "$HOLD" 2> /dev/null
unset PGPASSWORD DLAP_BACKUP_SHIP_CMD DLAP_BACKUP_PASSPHRASE_FILE SHIP_REMOTE SHIP_CALLS
export DLAP_DR_DATA="$T/data"
fi

# ======================================================================================================
# P: base backup and point-in-time restore
# ======================================================================================================
if wanted P; then
section "P base backup and point-in-time restore"
export DLAP_DR_DATA="$T/pitr-data"
rm -rf "$DLAP_DR_DATA"
mkdir -p "$DLAP_DR_DATA"
q postgres "CREATE DATABASE pitr"
q pitr "CREATE TABLE t (id serial PRIMARY KEY, phase text NOT NULL, ts timestamptz NOT NULL DEFAULT clock_timestamp())"
KEY="$(src dr_archive_key)"
WALDIR="$DLAP_DR_DATA/wal/$KEY"
mkdir -p "$WALDIR"
pg_receivewal --create-slot --if-not-exists --slot=dlap_dr --no-password > /dev/null
pg_receivewal -D "$WALDIR" --slot=dlap_dr --synchronous --no-password 2> "$T/work/receiver.log" &
RPID=$!
sleep 2
add() { q pitr "INSERT INTO t (phase) SELECT '$1' FROM generate_series(1, $2)" > /dev/null; }
add before 100
run bash "$DR/base-backup.sh"
check "P-01 base-backup.sh exits 0" rc_is 0
BASE1="$(find "$DLAP_DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' | LC_ALL=C sort | tail -n 1)"
M="$DLAP_DR_DATA/base/$BASE1/manifest.env"
check "P-02 the set holds the tar files, the PostgreSQL manifest, checksums and a manifest.env" bash -c "cd $DLAP_DR_DATA/base/$BASE1 && sha256sum -c --quiet SHA256SUMS && [ -s base.tar.gz ] && [ -s pg_wal.tar.gz ] && [ -s backup_manifest ]"
check "P-03 the manifest names the archive key and the first WAL segment" bash -c "grep -q '^ARCHIVE_KEY=$KEY\$' $M && grep -Eq '^START_WAL=[0-9A-F]{24}\$' $M && grep -q '^FORMAT=dlap-dr-base-1\$' $M"
check "P-04 the set verifies as gzip" bash -c "gzip -t $DLAP_DR_DATA/base/$BASE1/base.tar.gz $DLAP_DR_DATA/base/$BASE1/pg_wal.tar.gz"
check "P-05 status/base.env records the success" grep -q '^LAST_ATTEMPT_RESULT=ok$' "$DLAP_DR_DATA/status/base.env"

add stage1 200
sleep 1
q pitr "SELECT pg_create_restore_point('rp_stage1')" > /dev/null
COUNT_STAGE1="$(q pitr 'SELECT count(*) FROM t')"
sleep 2
TARGET_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sleep 3
add stage2 300
sleep 1
COUNT_LAST="$(q pitr 'SELECT count(*) FROM t')"
sleep 2
check "P-06 the receiver is streaming and the segment in progress is a .partial file" bash -c "ls $WALDIR | grep -q '\\.partial\$'"
receiver_caught_up() { [ "$(q postgres "SELECT coalesce(max(pg_current_wal_lsn() - flush_lsn), -1) FROM pg_stat_replication WHERE application_name = 'pg_receivewal'")" = 0 ]; }
check "P-07 the receiver has flushed everything the server wrote: its flush position equals the server's WAL position within 5 s of the last commit" wait_for 5 receiver_caught_up

# disaster: the receiver and the server are gone; only $DLAP_DR_DATA survives
kill -9 "$RPID" 2> /dev/null; wait "$RPID" 2> /dev/null

restore_to() { # <pgdata> <target> [extra args...] -> RC, OUT
  local d="$1" t="$2"
  shift 2
  rm -rf "$d"
  run bash "$DR/restore-pitr.sh" --from "$DLAP_DR_DATA" --pgdata "$d" --target "$t" --report "$T/work/pitr.report" "$@"
}
# start_restored <pgdata> <port>: start a server on a restored directory and wait for the end of recovery
start_restored() {
  pg_ctl -D "$1" -o "-c listen_addresses=127.0.0.1 -c port=$2 -c unix_socket_directories=$T" -l "$1.log" -w -t 120 start > /dev/null 2>&1 || return 1
  # The server accepts read-only connections while it still replays WAL; the tests must wait for the end of recovery.
  RECOVERY_PORT="$2"
  wait_for 120 recovered
}
recovered() { [ "$(psql -X -qAt -p "$RECOVERY_PORT" -h 127.0.0.1 -d postgres -c 'SELECT pg_is_in_recovery()' 2> /dev/null)" = f ]; }
stop_restored() { pg_ctl -D "$1" -m fast stop > /dev/null 2>&1; }
qp() { psql -X -qAt -p "$1" -h 127.0.0.1 -d pitr -c "$2"; }

restore_to "$T/r-name" "name:rp_stage1"
check "P-08 restore-pitr.sh to a restore point exits 0 and prepares the directory" bash -c "[ \$RC -eq 0 ] && [ -f $T/r-name/recovery.signal ] && grep -q \"recovery_target_name = 'rp_stage1'\" $T/r-name/postgresql.auto.conf"
check "P-09 the directory is private (mode 700)" test "$(stat -c %a "$T/r-name")" = 700
check "P-10 the report names base set, archive key, segments and target" bash -c "grep -q '^RESULT=ok\$' $T/work/pitr.report && grep -q '^BASE_SET=$BASE1\$' $T/work/pitr.report && grep -q '^ARCHIVE_KEY=$KEY\$' $T/work/pitr.report && grep -q '^TARGET=name:rp_stage1\$' $T/work/pitr.report"
if start_restored "$T/r-name" 5441; then
  check "P-11 recovery to the restore point yields exactly the rows committed before it ($COUNT_STAGE1)" test "$(qp 5441 'SELECT count(*) FROM t')" = "$COUNT_STAGE1"
  check "P-12 the rows written after the restore point are not there" test "$(qp 5441 "SELECT count(*) FROM t WHERE phase = 'stage2'")" = "0"
  OUT="$(qp 5441 "INSERT INTO t (phase) VALUES ('after-restore')" 2>&1)"; RC=$?
  check "P-13 the restored server is promoted and accepts writes" test "$RC" -eq 0 -a "$(qp 5441 'SELECT pg_is_in_recovery()')" = f
  OUT="$(qp 5441 'SELECT timeline_id FROM pg_control_checkpoint()' 2>&1)"
  check "P-13b it runs on timeline 2" test "$OUT" = 2
  check "P-14 the restored cluster has the same system identifier and a new archive key (a new timeline)" bash -c "source $DR/lib.sh; PGPORT=5441; [ \"\$(dr_system_id)\" = \"$(src dr_system_id)\" ] && [ \"\$(dr_archive_key)\" != \"$KEY\" ] && [[ \"\$(dr_archive_key)\" == *-tl2 ]]"
  stop_restored "$T/r-name"
else
  bad "P-11 the restored server did not start (see $T/r-name.log)"; tail -n 20 "$T/r-name.log" >&2
fi

restore_to "$T/r-time" "time:$TARGET_TIME"
check "P-15 restore-pitr.sh to a time exits 0" rc_is 0
if start_restored "$T/r-time" 5442; then
  check "P-16 recovery to a time yields the rows committed before it (stage 1, not stage 2)" test "$(qp 5442 'SELECT count(*) FROM t')" = "$COUNT_STAGE1"
  stop_restored "$T/r-time"
else
  bad "P-16 the server restored to a time did not start"; tail -n 20 "$T/r-time.log" >&2
fi

restore_to "$T/r-latest" "latest"
check "P-17 restore-pitr.sh to the latest moment exits 0 and used the partial segment" bash -c "[ \$RC -eq 0 ] && grep -q '^USED_PARTIAL=[1-9]' $T/work/pitr.report"
check "P-17b the report says the replay check read the WAL through the newest segment (REPLAY_CHECK=ok)" grep -q '^REPLAY_CHECK=ok$' "$T/work/pitr.report"
if start_restored "$T/r-latest" 5443; then
  check "P-18 recovery to the latest moment yields every committed row ($COUNT_LAST), including those only in the partial segment" test "$(qp 5443 'SELECT count(*) FROM t')" = "$COUNT_LAST"
  stop_restored "$T/r-latest"
else
  bad "P-18 the server restored to the latest moment did not start"; tail -n 20 "$T/r-latest.log" >&2
fi

restore_to "$T/r-base" "immediate"
if start_restored "$T/r-base" 5444; then
  check "P-19 immediate recovery gives the state of the base backup (100 rows)" test "$(qp 5444 'SELECT count(*) FROM t')" = "100"
  stop_restored "$T/r-base"
else
  bad "P-19 immediate recovery did not start"; tail -n 20 "$T/r-base.log" >&2
fi

# refusals
mkdir -p "$T/r-full"; touch "$T/r-full/x"
run bash "$DR/restore-pitr.sh" --from "$DLAP_DR_DATA" --pgdata "$T/r-full"
check "P-20 a data directory that is not empty is refused (exit 2) and left alone" bash -c "[ \$RC -eq 2 ] && [ -f $T/r-full/x ] && [ \"\$(ls $T/r-full | wc -l)\" = 1 ]"
run bash "$DR/restore-pitr.sh" --from "$DLAP_DR_DATA" --pgdata "$T/r-x" --target tomorrow
check "P-21 an unknown target is refused (exit 2)" rc_is 2
run bash "$DR/restore-pitr.sh" --from "$DLAP_DR_DATA" --pgdata "$T/r-x" --target "time:2026-10-19 10:00"
check "P-22 a time in the wrong format is refused (exit 2)" rc_is 2
run bash "$DR/restore-pitr.sh" --from "$DLAP_DR_DATA" --pgdata "$T/r-x" --target "time:2000-01-01T00:00:00Z"
check "P-23 a time older than every base backup is refused (exit 2)" bash -c "[ \$RC -eq 2 ] && grep -q 'no complete base backup' <<< \"\$OUT\""
run bash "$DR/restore-pitr.sh" --from "$T/nowhere" --pgdata "$T/r-x"
check "P-24 a source without base/ is refused (exit 2)" rc_is 2
run bash "$DR/restore-pitr.sh" --from "$DLAP_DR_DATA" --pgdata "$T/r-x" --base 29990101T000000Z
check "P-25 a base backup that does not exist is refused (exit 2)" rc_is 2

# gaps: an archive with segments N, N+1 and N+3 (names only; recovery is never started)
GAPROOT="$T/gaproot"
rm -rf "$GAPROOT"
mkdir -p "$GAPROOT/base" "$GAPROOT/wal/$KEY"
LATEST_BASE="$(find "$DLAP_DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' -printf '%f\n' | LC_ALL=C sort | tail -n 1)"
cp -r "$DLAP_DR_DATA/base/$LATEST_BASE" "$GAPROOT/base/$LATEST_BASE"
GAP_START="$(grep '^START_WAL=' "$GAPROOT/base/$LATEST_BASE/manifest.env" | cut -d= -f2)"
GAP_N=$(( 16#${GAP_START:8:8} * 256 + 16#${GAP_START:16:8} ))
for off in 0 1 3; do
  n=$((GAP_N + off))
  cp "$WALDIR/$GAP_START" "$GAPROOT/wal/$KEY/$(printf '%s%08X%08X' "${GAP_START:0:8}" $((n / 256)) $((n % 256)))" 2> /dev/null     || cp "$WALDIR/$GAP_START.partial" "$GAPROOT/wal/$KEY/$(printf '%s%08X%08X' "${GAP_START:0:8}" $((n / 256)) $((n % 256)))"
done
rm -rf "$T/r-gap" "$T/r-gap2"
run bash "$DR/restore-pitr.sh" --from "$GAPROOT" --pgdata "$T/r-gap" --target latest
check "P-26 a gap in the WAL sequence is refused (exit 2), named, and the directory is removed again" bash -c "[ \$RC -eq 2 ] && grep -q 'gap' <<< \"\$OUT\" && [ -z \"\$(ls -A $T/r-gap 2>/dev/null)\" ]"
run bash "$DR/restore-pitr.sh" --from "$GAPROOT" --pgdata "$T/r-gap2" --target latest --allow-gaps
check "P-27 --allow-gaps goes on with the gap (exit 0)" rc_is 0
run bash "$DR/restore-pitr.sh" --from "$GAPROOT" --pgdata "$T/r-gap3" --target immediate
check "P-27b an immediate target needs no WAL, so a gap does not matter (exit 0)" rc_is 0
rm -rf "$GAPROOT" "$T/r-gap2" "$T/r-gap3"

# a store that holds the base backups of two clusters (the original and the one restored from it)
MIXROOT="$T/mixroot"
rm -rf "$MIXROOT" "$T/r-mix1" "$T/r-mix2" "$T/r-mix3"
mkdir -p "$MIXROOT/base" "$MIXROOT/wal"
cp -r "$DLAP_DR_DATA/base/$LATEST_BASE" "$MIXROOT/base/$LATEST_BASE"
cp -r "$DLAP_DR_DATA/base/$LATEST_BASE" "$MIXROOT/base/99990101T000000Z"
cp -r "$DLAP_DR_DATA/wal/." "$MIXROOT/wal/"
sed -i 's/^SYSTEM_ID=.*/SYSTEM_ID=9999999999999999999/' "$MIXROOT/base/99990101T000000Z/manifest.env"
( cd "$MIXROOT/base/99990101T000000Z" && grep -v ' manifest.env$' SHA256SUMS > S.tmp && sha256sum manifest.env >> S.tmp && mv S.tmp SHA256SUMS )
ORIG_SYSID="$(grep '^SYSTEM_ID=' "$MIXROOT/base/$LATEST_BASE/manifest.env" | cut -d= -f2)"
run bash "$DR/restore-pitr.sh" --from "$MIXROOT" --pgdata "$T/r-mix1" --target name:rp_stage1
check "P-37 a named restore point with base backups of two clusters is refused (exit 2)" rc_is 2
check "P-37b the refusal lists both clusters and says how to choose" bash -c "grep -q '9999999999999999999' <<< \"\$OUT\" && grep -q \"$ORIG_SYSID\" <<< \"\$OUT\" && grep -q -- '--system-id' <<< \"\$OUT\""
check "P-37c and it wrote nothing" test ! -e "$T/r-mix1"
run bash "$DR/restore-pitr.sh" --from "$MIXROOT" --pgdata "$T/r-mix2" --target name:rp_stage1 --system-id "$ORIG_SYSID" --report "$T/work/mix.report"
check "P-38 --system-id picks the cluster that made the restore point" bash -c "[ \$RC -eq 0 ] && grep -q '^BASE_SET=$LATEST_BASE\$' $T/work/mix.report"
run bash "$DR/restore-pitr.sh" --from "$MIXROOT" --pgdata "$T/r-mix3" --target latest --report "$T/work/mix.report"
check "P-39 the latest moment takes the newest base backup, whatever cluster it belongs to" bash -c "[ \$RC -eq 0 ] && grep -q '^BASE_SET=99990101T000000Z\$' $T/work/mix.report"
rm -rf "$MIXROOT" "$T/r-mix2" "$T/r-mix3"

# Q-752: the base backup's own pg_wal/ and the archive both hold a segment of the same name, and the
# base copy ends earlier (a base backup streams WAL only up to its own end; the archive goes on). On a busy
# cluster the segment after the one the backup ends in is such a segment. The store is built by hand so the
# case is exact: segment SS_NEXT of the base copy is cut after its second record, the archive keeps the rest.
SS_START="$(grep '^START_WAL=' "$DLAP_DR_DATA/base/$BASE1/manifest.env" | cut -d= -f2)"
SS_N=$(( 16#${SS_START:8:8} * 256 + 16#${SS_START:16:8} + 1 ))
seg_name() { printf '%s%08X%08X' "${SS_START:0:8}" $(( ($1) / 256 )) $(( ($1) % 256 )); }
SS_NEXT="$(seg_name "$SS_N")"
SS_AFTER="$(seg_name $((SS_N + 1)))"
SS_SRC="$WALDIR/$SS_NEXT"; [ -e "$SS_SRC" ] || SS_SRC="$WALDIR/$SS_NEXT.partial"
SS_WORK="$T/work/samename"
rm -rf "$SS_WORK"
mkdir -p "$SS_WORK/read" "$SS_WORK/base-wal"
cp "$SS_SRC" "$SS_WORK/read/$SS_NEXT"
SS_CUT_LSN="$(pg_waldump -p "$SS_WORK/read" -n 3 "$SS_NEXT" 2> /dev/null | sed -n '3s/.*lsn: \([0-9A-F]*\/[0-9A-F]*\),.*/\1/p')"
SS_CUT=$(( 16#${SS_CUT_LSN#*/} % 16777216 ))
head -c "$SS_CUT" "$SS_WORK/read/$SS_NEXT" > "$SS_WORK/base-wal/$SS_NEXT"
truncate -s 16777216 "$SS_WORK/base-wal/$SS_NEXT"
# same_store <dir> <archive layout: archive|zeroed>: a store with BASE1 whose pg_wal.tar.gz carries the cut copy
same_store() {
  local r="$1" layout="$2"
  rm -rf "$r"
  mkdir -p "$r/base" "$r/wal/$KEY"
  cp -r "$DLAP_DR_DATA/base/$BASE1" "$r/base/$BASE1"
  cp "$WALDIR/$SS_START" "$r/wal/$KEY/$SS_START"
  case "$layout" in
    archive) cp "$SS_SRC" "$r/wal/$KEY/$SS_NEXT.partial" ;;
    zeroed) head -c 16777216 /dev/zero > "$r/wal/$KEY/$SS_NEXT"; cp "$SS_SRC" "$r/wal/$KEY/$SS_AFTER.partial" ;;
  esac
  local d="$SS_WORK/base-pack"
  rm -rf "$d"
  mkdir -p "$d"
  tar -xzf "$r/base/$BASE1/pg_wal.tar.gz" -C "$d"
  cp "$SS_WORK/base-wal/$SS_NEXT" "$d/$SS_NEXT"
  ( cd "$d" && tar -czf "$r/base/$BASE1/pg_wal.tar.gz" ./* )
  ( cd "$r/base/$BASE1" && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%f\n' | LC_ALL=C sort | xargs sha256sum > SHA256SUMS )
}
same_store "$T/samestore" archive
check "P-40 premise: the base copy of $SS_NEXT holds less WAL than the archive's (so the old place() skipped the better copy)" bash -c "[ \$(tr -d '\\000' < $SS_WORK/base-wal/$SS_NEXT | wc -c) -lt \$(tr -d '\\000' < $SS_SRC | wc -c) ] && [ $SS_CUT -gt 0 ]"
rm -rf "$T/r-same"
run bash "$DR/restore-pitr.sh" --from "$T/samestore" --pgdata "$T/r-same" --target latest --report "$T/work/same.report"
check "P-41 a base copy shorter than the archive's of the same name: restore-pitr.sh exits 0, replaces it and says so" bash -c "[ \$RC -eq 0 ] && grep -q '^REPLACED_BASE_SEGMENTS=1\$' $T/work/same.report && grep -q 'replaces it' <<< \"\$OUT\""
check "P-42 pg_wal/$SS_NEXT is the archive's copy byte for byte" cmp -s "$T/r-same/pg_wal/$SS_NEXT" "$SS_SRC"
check "P-43 the replay check read the WAL through the newest archived segment" bash -c "grep -q '^REPLAY_CHECK=ok\$' $T/work/same.report && grep -q '^NEWEST_SEGMENT=$SS_NEXT\$' $T/work/same.report"
if start_restored "$T/r-same" 5446; then
  check "P-44 recovery replays the WAL after the cut: every committed row is there ($COUNT_LAST), not the rows up to the cut" test "$(qp 5446 'SELECT count(*) FROM t')" = "$COUNT_LAST"
  stop_restored "$T/r-same"
else
  bad "P-44 the server restored with a replaced segment did not start"; tail -n 20 "$T/r-same.log" >&2
fi
# the other way round: the base copy is the longer one and stays
rm -rf "$T/samestore2" "$T/r-same2"
mkdir -p "$T/samestore2/base" "$T/samestore2/wal/$KEY"
cp -r "$T/samestore/base/$BASE1" "$T/samestore2/base/$BASE1"
cp "$WALDIR/$SS_START" "$T/samestore2/wal/$KEY/$SS_START"
head -c "$SS_CUT" "$SS_SRC" > "$T/samestore2/wal/$KEY/$SS_NEXT.partial"
truncate -s 16777216 "$T/samestore2/wal/$KEY/$SS_NEXT.partial"
( cd "$T/samestore2/base/$BASE1" && d="$SS_WORK/base-pack2" && rm -rf "$d" && mkdir -p "$d" && tar -xzf pg_wal.tar.gz -C "$d" && cp "$SS_SRC" "$d/$SS_NEXT" && tar -czf pg_wal.tar.gz -C "$d" . && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%f\n' | LC_ALL=C sort | xargs sha256sum > SHA256SUMS )
run bash "$DR/restore-pitr.sh" --from "$T/samestore2" --pgdata "$T/r-same2" --target latest --report "$T/work/same2.report"
check "P-45 a base copy longer than a partial archive copy is kept (exit 0, nothing replaced, a warning names the segment)" bash -c "[ \$RC -eq 0 ] && grep -q '^REPLACED_BASE_SEGMENTS=0\$' $T/work/same2.report && grep -q 'is kept' <<< \"\$OUT\" && cmp -s $T/r-same2/pg_wal/$SS_NEXT $SS_SRC"
# the check itself: the archive holds a newer segment, but the copy of $SS_NEXT it offers is all zeros, so the
# only copy there is ends at the cut and recovery could never reach the newer segment
same_store "$T/samestore3" zeroed
rm -rf "$T/r-same3"
run bash "$DR/restore-pitr.sh" --from "$T/samestore3" --pgdata "$T/r-same3" --target latest --report "$T/work/same3.report"
check "P-46 WAL that recovery cannot read through to the newest archived segment fails the restore (exit 1) and names the segment" bash -c "[ \$RC -eq 1 ] && grep -q 'before the newest archived segment $SS_AFTER' <<< \"\$OUT\""
check "P-47 and the data directory is removed again, and the report says failed" bash -c "[ -z \"\$(ls -A $T/r-same3 2>/dev/null)\" ] && grep -q '^REPLAY_CHECK=failed\$' $T/work/same3.report && grep -q '^RESULT=failed\$' $T/work/same3.report"
rm -rf "$T/r-same3"
run bash "$DR/restore-pitr.sh" --from "$T/samestore3" --pgdata "$T/r-same3" --target latest --no-replay-check --report "$T/work/same3.report"
check "P-48 --no-replay-check goes on with the same store (exit 0) and the report says the check was skipped" bash -c "[ \$RC -eq 0 ] && grep -q '^REPLAY_CHECK=skipped-by-option\$' $T/work/same3.report"
run bash "$DR/restore-pitr.sh" --from "$T/samestore3" --pgdata "$T/r-same3b" --target immediate --report "$T/work/same3.report"
check "P-49 an immediate target needs no WAL, so the check does not apply (exit 0, skipped-immediate)" bash -c "[ \$RC -eq 0 ] && grep -q '^REPLAY_CHECK=skipped-immediate\$' $T/work/same3.report"
rm -rf "$T/samestore" "$T/samestore2" "$T/samestore3" "$T/r-same" "$T/r-same2" "$T/r-same3" "$T/r-same3b" "$SS_WORK"

# base backup retention trims the WAL
sleep 1
add retention 50
q postgres "SELECT pg_switch_wal()" > /dev/null
pg_receivewal -D "$WALDIR" --slot=dlap_dr --synchronous --no-password 2>> "$T/work/receiver.log" &
RPID=$!
sleep 2
for i in 1 2; do add more$i 50; q postgres "SELECT pg_switch_wal()" > /dev/null; sleep 1; bash "$DR/base-backup.sh" > /dev/null 2>&1; done
sleep 1
nsets="$(find "$DLAP_DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' | wc -l | tr -d ' ')"
check "P-28 three base backups exist before retention" test "$nsets" = 3
OLDEST_START_BEFORE="$(for s in "$DLAP_DR_DATA"/base/*Z; do dr=$(grep '^START_WAL=' "$s/manifest.env" | cut -d= -f2); echo "$dr"; done | sort | head -n 1)"
run bash "$DR/base-backup.sh" --keep 2
nsets="$(find "$DLAP_DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' | wc -l | tr -d ' ')"
check "P-29 with --keep 2 the oldest base backup is removed (2 left plus the new one counted: 2)" test "$nsets" = 2
OLDEST_START="$(for s in "$DLAP_DR_DATA"/base/*Z; do grep '^START_WAL=' "$s/manifest.env" | cut -d= -f2; done | sort | head -n 1)"
check "P-30 WAL older than the oldest kept base backup is trimmed from the archive" bash -c "[ ! -e $WALDIR/$OLDEST_START_BEFORE ] || [ '$OLDEST_START_BEFORE' = '$OLDEST_START' ]"
check "P-31 the segment the oldest kept base backup starts at is still there" test -e "$WALDIR/$OLDEST_START"
check "P-32 --prune-only removes no more than the rules say and exits 0" bash -c "bash $DR/base-backup.sh --prune-only --keep 2 > /dev/null 2>&1"
kill "$RPID" 2> /dev/null; wait "$RPID" 2> /dev/null

# a restore from the shipped, encrypted copy
export DLAP_BACKUP_PASSPHRASE_FILE="$T/work/pass"
[ -f "$T/work/pass" ] || { printf 'a long random passphrase for the shipped copy\n' > "$T/work/pass"; chmod 600 "$T/work/pass"; }
rm -rf "$T/pitr-remote"
export DLAP_BACKUP_SHIP_CMD='mkdir -p "$(dirname "'"$T"'/pitr-remote/$2")" && cp "$1" "'"$T"'/pitr-remote/$2"'
run bash "$DR/ship.sh" --all
check "P-33 ship.sh --all copies base backups and WAL to the remote folder" bash -c "[ \$RC -eq 0 ] && [ \"\$(find $T/pitr-remote/base -name SHA256SUMS.shipped | wc -l)\" = 2 ] && ls $T/pitr-remote/wal/*/ | grep -q 'gz.enc'"
unset DLAP_BACKUP_SHIP_CMD
rm -rf "$T/r-remote"
run bash "$DR/restore-pitr.sh" --from "$T/pitr-remote" --pgdata "$T/r-remote" --target latest
check "P-34 restore-pitr.sh restores from the encrypted copy with the passphrase (exit 0)" rc_is 0
if start_restored "$T/r-remote" 5445; then
  check "P-35 the cluster restored from the off-host copy has every row (100 + 200 + 300 + 50 + 50 + 50 + 50)" test "$(qp 5445 'SELECT count(*) FROM t')" = "$(q pitr 'SELECT count(*) FROM t')"
  stop_restored "$T/r-remote"
else
  bad "P-35 the cluster restored from the off-host copy did not start"; tail -n 20 "$T/r-remote.log" >&2
fi
printf 'not the passphrase\n' > "$T/work/pass-wrong"; chmod 600 "$T/work/pass-wrong"
run env DLAP_BACKUP_PASSPHRASE_FILE="$T/work/pass-wrong" bash "$DR/restore-pitr.sh" --from "$T/pitr-remote" --pgdata "$T/r-remote-bad"
check "P-36 the wrong passphrase fails and no data directory is left usable" bash -c "[ \$RC -ne 0 ]"
unset DLAP_BACKUP_PASSPHRASE_FILE
export DLAP_DR_DATA="$T/data"
fi

# ======================================================================================================
# A: the agent
# ======================================================================================================
if wanted A; then
section "A agent"
AG="$DR/agent.sh"
export DLAP_DR_DATA="$T/agent-data"
rm -rf "$DLAP_DR_DATA"
mkdir -p "$DLAP_DR_DATA/status"
ST="$DLAP_DR_DATA/status"
NOW="$(date -u +%s)"
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
good_state() {
  rm -rf "$ST"; mkdir -p "$ST"
  printf 'START_UTC=%s\nHEARTBEAT_UTC=%s\nHEARTBEAT_EPOCH=%s\n' "$(iso $((NOW - 7200)))" "$(iso "$NOW")" "$NOW" > "$ST/agent.env"
  printf 'RECEIVER=running\nARCHIVE_KEY=1-tl1\nNEWEST_FILE=000000010000000000000009\nUPDATED_UTC=%s\n' "$(iso "$NOW")" > "$ST/wal.env"
  printf 'LAST_SUCCESS_UTC=%s\nLAST_SUCCESS_SET=S1\nLAST_ERROR=\n' "$(iso $((NOW - 3600)))" > "$ST/logical.env"
  printf 'LAST_SUCCESS_UTC=%s\nLAST_SUCCESS_SET=B1\nLAST_ERROR=\n' "$(iso $((NOW - 86400)))" > "$ST/base.env"
  printf 'MODE=enabled\nLAST_PASS_UTC=%s\nWAL_SHIPPED_UPTO_UTC=%s\nPENDING=0\nLAST_ERROR=\n' "$(iso "$NOW")" "$(iso $((NOW - 100)))" > "$ST/ship-wal.env"
  printf 'MODE=enabled\nLAST_PASS_UTC=%s\nLAST_ERROR=\n' "$(iso "$NOW")" > "$ST/ship-sets.env"
}
status() { run env DLAP_ENCRYPTION_KEY_FINGERPRINT="${FP-fp1:aaaaaaaaaaaaaaaa}" DLAP_BACKUP_SHIP_CMD="${SHIPCMD-true}" bash "$AG" status "$@"; }

good_state
status --check
check "A-01 status --check of a healthy state exits 0 with only OK lines" bash -c "[ \$RC -eq 0 ] && ! grep -q '^FAIL\\|^WARN' <<< \"\$OUT\""
good_state; printf 'HEARTBEAT_EPOCH=%s\n' "$((NOW - 500))" >> "$ST/agent.env"
status --check
check "A-02 a heartbeat older than two minutes fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*agent.*no heartbeat' <<< \"\$OUT\""
good_state; printf 'RECEIVER=stopped\n' >> "$ST/wal.env"
status --check
check "A-03 a stopped WAL receiver fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*wal archive.*receiver is stopped' <<< \"\$OUT\""
good_state; printf 'LAST_SUCCESS_UTC=%s\n' "$(iso $((NOW - 30 * 3600)))" >> "$ST/logical.env"
status --check
check "A-04 a logical backup older than 26 hours fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*logical backup' <<< \"\$OUT\""
good_state; printf 'LAST_SUCCESS_UTC=%s\n' "$(iso $((NOW - 9 * 86400)))" >> "$ST/base.env"
status --check
check "A-05 a base backup older than 8 days fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*base backup' <<< \"\$OUT\""
good_state; printf 'WAL_SHIPPED_UPTO_UTC=%s\nPENDING=2\n' "$(iso $((NOW - 1200)))" >> "$ST/ship-wal.env"
status --check
check "A-06 an off-host copy more than 900 s behind fails: the RPO is broken (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*off-host WAL.*behind' <<< \"\$OUT\""
good_state; printf 'WAL_SHIPPED_UPTO_UTC=%s\n' "$(iso $((NOW - 800)))" >> "$ST/ship-wal.env"
status --check
check "A-07 an off-host copy 800 s behind is still within the RPO (exit 0)" rc_is 0
good_state; printf 'MODE=disabled\n' >> "$ST/ship-wal.env"
SHIPCMD='' status --check
check "A-08 no off-host copy is a warning, not a failure (exit 0)" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN.*off-host copy.*not configured' <<< \"\$OUT\""
good_state; printf 'MODE=blocked\nLAST_ERROR=no passphrase\n' >> "$ST/ship-wal.env"
status --check
check "A-09 a blocked off-host copy fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*off-host copy.*blocked' <<< \"\$OUT\""
good_state; printf 'LAST_OK_UTC=%s\n' "$(iso $((NOW - 7200)))" >> "$ST/ship-sets.env"
status --check
check "A-09b backup sets not shipped successfully for two hours fail (exit 1): the shipper is not running" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*off-host sets.*no complete pass' <<< \"\$OUT\""
good_state; printf 'LAST_ERROR=1 backup set(s) could not be shipped\nPENDING=1\n' >> "$ST/ship-sets.env"
status --check
check "A-09c a backup set that could not be shipped fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*off-host sets.*could not be shipped' <<< \"\$OUT\""
good_state
FP='' status --check
check "A-10 a missing key fingerprint is a warning (exit 0)" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN.*key escrow' <<< \"\$OUT\""
good_state; printf 'CHAIN_BROKEN_UTC=%s\n' "$(iso "$NOW")" >> "$ST/wal.env"
status --check
check "A-11 a broken WAL chain is a warning that names the time" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN.*chain was broken' <<< \"\$OUT\""
good_state
run env PGPORT=1 DLAP_ENCRYPTION_KEY_FINGERPRINT=fp1:aaaaaaaaaaaaaaaa DLAP_BACKUP_SHIP_CMD=true bash "$AG" status --check
check "A-11a an unreachable database fails (exit 1) and the message says how to recover from a restarted postgresql container" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*database.*docker compose up -d dlap-backup' <<< \"\$OUT\""
good_state
run env DLAP_BACKUP_MIN_FREE_MB=999999999 bash "$AG" status --check
check "A-11b a backup volume with too little free space fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*backup space.*MiB free' <<< \"\$OUT\""
run env DLAP_BACKUP_WARN_FREE_MB=999999999 DLAP_ENCRYPTION_KEY_FINGERPRINT=fp1:aaaaaaaaaaaaaaaa DLAP_BACKUP_SHIP_CMD=true bash "$AG" status --check
check "A-11c a backup volume under the warning level is a warning (exit 0)" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN.*backup space' <<< \"\$OUT\""
good_state
status --check --quiet
check "A-12 --quiet prints no OK line" test -z "$OUT"
good_state; rm -f "$ST/logical.env"; printf 'START_UTC=%s\nHEARTBEAT_EPOCH=%s\n' "$(iso $((NOW - 600)))" "$NOW" > "$ST/agent.env"
status --check
check "A-13 a first backup that is not there yet, ten minutes after start, is pending (a warning)" bash -c "[ \$RC -eq 0 ] && grep -q 'WARN.*logical backup.*none yet' <<< \"\$OUT\""
good_state; rm -f "$ST/logical.env"
status --check
check "A-14 a first backup still missing two hours after start fails (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'FAIL.*logical backup.*none has succeeded' <<< \"\$OUT\""
rm -rf "$ST"; mkdir -p "$ST"
status --check
check "A-15 with no status at all the agent is reported as not started (exit 1)" bash -c "[ \$RC -eq 1 ] && grep -q 'agent.*has not started' <<< \"\$OUT\""
status --bogus
check "A-16 an unknown status argument is refused (exit 2)" rc_is 2

run bash "$AG" once nothing
check "A-17 an unknown job is refused (exit 2)" rc_is 2
run env DLAP_BACKUP_AT=25:00 bash "$AG" once ship
check "A-18 a malformed DLAP_BACKUP_AT is refused (exit 2)" bash -c "[ \$RC -eq 2 ] && grep -q 'HH:MM' <<< \"\$OUT\""
run env DLAP_WAL_CYCLE_SECONDS=5 bash "$AG" run
check "A-19 a WAL cycle under 30 seconds is refused (exit 2)" rc_is 2
run env DLAP_WAL_MAX_KEEP_SIZE=lots bash "$AG" run
check "A-20 a malformed DLAP_WAL_MAX_KEEP_SIZE is refused (exit 2)" rc_is 2
run bash "$AG"
check "A-21 no command prints the usage and exits 2" rc_is 2
run bash "$AG" frobnicate
check "A-22 an unknown command is refused (exit 2)" rc_is 2

# the real thing: the agent supervising a receiver, taking its first backups, shipping
rm -rf "$DLAP_DR_DATA" "$T/aremote"
mkdir -p "$DLAP_DR_DATA"
printf 'agent test passphrase\n' > "$T/work/apass"; chmod 600 "$T/work/apass"
export DLAP_BACKUP_PASSPHRASE_FILE="$T/work/apass"
export DLAP_BACKUP_SHIP_CMD='mkdir -p "$(dirname "'"$T"'/aremote/$2")" && cp "$1" "'"$T"'/aremote/$2"'
export DLAP_WAL_CYCLE_SECONDS=30 DLAP_BACKUP_RETRY_SECONDS=5 DLAP_ENCRYPTION_KEY_FINGERPRINT="fp1:aaaaaaaaaaaaaaaa"
q postgres "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots" > /dev/null 2>&1 || true
bash "$AG" run > "$T/work/agent.log" 2>&1 &
APID=$!
wait_for 90 test -f "$DLAP_DR_DATA/status/logical.env" && wait_for 90 grep -q '^LAST_SUCCESS_UTC=' "$DLAP_DR_DATA/status/logical.env"
check "A-23 a fresh agent takes the first logical backup by itself" grep -q '^LAST_ATTEMPT_RESULT=ok$' "$DLAP_DR_DATA/status/logical.env"
wait_for 90 grep -q '^LAST_SUCCESS_UTC=' "$DLAP_DR_DATA/status/base.env"
check "A-24 a fresh agent takes the first base backup by itself" grep -q '^LAST_ATTEMPT_RESULT=ok$' "$DLAP_DR_DATA/status/base.env"
check "A-25 the replication slot exists and is active" test "$(q postgres "SELECT active FROM pg_replication_slots WHERE slot_name = 'dlap_dr'")" = "t"
check "A-26 the server's slot retention is bounded (max_slot_wal_keep_size = 4GB)" test "$(q postgres 'SHOW max_slot_wal_keep_size')" = "4GB"
check "A-27 WAL is being written into wal/<system id>-tl<timeline>" bash -c "ls $DLAP_DR_DATA/wal/*/ | grep -Eq '^[0-9A-F]{24}(\\.partial)?\$'"
wait_for 90 grep -q '^WAL_SHIPPED_UPTO_EPOCH=' "$DLAP_DR_DATA/status/ship-wal.env"
check "A-28 the first WAL cycle shipped the archive off-host" bash -c "grep -q '^PENDING=0\$' $DLAP_DR_DATA/status/ship-wal.env && ls $T/aremote/wal/*/ | grep -q 'gz.enc'"
wait_for 60 test -f "$T/aremote/logical/$(latest_set)/SHA256SUMS.shipped"
check "A-29 the first logical set was shipped after it was taken" test -f "$T/aremote/logical/$(latest_set)/SHA256SUMS.shipped"
run env DLAP_BACKUP_SHIP_CMD=true bash "$AG" status --check
check "A-30 status --check of the running agent exits 0" bash -c "[ \$RC -eq 0 ] && grep -q 'OK.*agent.*running since' <<< \"\$OUT\""
check "A-31 the log says what the agent is doing" bash -c "grep -q 'streaming WAL into wal/' $T/work/agent.log && grep -q 'job logical started' $T/work/agent.log"

# the receiver is supervised
RP="$(pids_of "pg_receivewal -D $DLAP_DR_DATA" | head -n 1)"
kill -9 "$RP" 2> /dev/null
wait_for 60 grep -q '^RECEIVER_STARTS=2$' "$DLAP_DR_DATA/status/wal.env"
check "A-32 a receiver that is killed is started again" bash -c "grep -q '^RECEIVER_STARTS=2\$' $DLAP_DR_DATA/status/wal.env"
check "A-33 the slot is active again" bash -c "for i in 1 2 3 4 5 6 7 8 9 10; do [ \"\$(psql -X -qAt -d postgres -c \"SELECT active FROM pg_replication_slots WHERE slot_name='dlap_dr'\")\" = t ] && exit 0; sleep 1; done; exit 1"

# a write after the last WAL cycle is in the archive within a second
# orderly stop
kill -TERM "$APID"
wait_for 30 bash -c "! kill -0 $APID 2>/dev/null"
wait "$APID" 2> /dev/null; ARC=$?
check "A-34 SIGTERM stops the agent with exit 0" test "$ARC" -eq 0
receiver_stopped() { grep -q '^RECEIVER=stopped$' "$DLAP_DR_DATA/status/wal.env" && [ -z "$(pids_of "pg_receivewal -D $DLAP_DR_DATA")" ]; }
check "A-35 the stop is recorded and the receiver is gone" receiver_stopped
check "A-36 no half-made backup is left" bash -c "[ -z \"\$(find $DLAP_DR_DATA -name '.tmp-*')\" ]"

# an archive that fell too far behind is replaced, and a new base backup is asked for
q postgres "ALTER SYSTEM SET max_slot_wal_keep_size = '32MB'" > /dev/null
q postgres "SELECT pg_reload_conf()" > /dev/null
q postgres "CREATE DATABASE agentdb" > /dev/null 2>&1 || true
q agentdb "CREATE TABLE IF NOT EXISTS filler (id serial, pad text)" > /dev/null
for i in 1 2 3 4 5 6; do
  q agentdb "INSERT INTO filler (pad) SELECT repeat('x', 1000) FROM generate_series(1, 20000)" > /dev/null
  q postgres "SELECT pg_switch_wal()" > /dev/null
done
q postgres "CHECKPOINT" > /dev/null
slot_lost() { [ "$(q postgres "SELECT coalesce(wal_status, 'reserved') FROM pg_replication_slots WHERE slot_name = 'dlap_dr'")" = lost ]; }
wait_for 20 slot_lost
check "A-37 a slot that falls behind the retention limit is invalidated by the server" test "$(q postgres "SELECT coalesce(wal_status, 'reserved') FROM pg_replication_slots WHERE slot_name = 'dlap_dr'")" = "lost"
BASES_BEFORE="$(find "$DLAP_DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' | wc -l | tr -d ' ')"
bash "$AG" run > "$T/work/agent2.log" 2>&1 &
APID=$!
wait_for 60 grep -q '^CHAIN_BROKEN_UTC=' "$DLAP_DR_DATA/status/wal.env"
check "A-38 the agent notices the lost slot, says so, and records when the chain broke" bash -c "grep -q 'was invalidated' $T/work/agent2.log && grep -q '^CHAIN_BROKEN_UTC=' $DLAP_DR_DATA/status/wal.env"
check "A-38b the archive moved to a new generation directory (-g1) so the receiver does not ask for WAL the server no longer has" bash -c "grep -q '^ARCHIVE_KEY=.*-g1\$' $DLAP_DR_DATA/status/wal.env && grep -q '^GENERATION=1\$' $DLAP_DR_DATA/status/archive-generation"
base_count() { find "$DLAP_DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' | wc -l | tr -d ' '; }
more_bases() { [ "$(base_count)" -gt "$BASES_BEFORE" ]; }
wait_for 90 more_bases
check "A-39 it takes a new base backup at once" test "$(find "$DLAP_DR_DATA/base" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' | wc -l | tr -d ' ')" -gt "$BASES_BEFORE"
check "A-39b the new base backup belongs to the new archive generation" bash -c "ls -d $DLAP_DR_DATA/base/*Z | tail -n 1 | xargs -I{} grep -q '^ARCHIVE_KEY=.*-g1\$' {}/manifest.env"
slot_active() { [ "$(q postgres "SELECT active FROM pg_replication_slots WHERE slot_name = 'dlap_dr'")" = t ]; }
check "A-40 the new slot is active" wait_for 30 slot_active
run env DLAP_BACKUP_SHIP_CMD=true bash "$AG" status
check "A-41 status then shows the broken chain as a warning" bash -c "grep -q 'WARN.*chain was broken' <<< \"\$OUT\""
kill -TERM "$APID"; wait "$APID" 2> /dev/null
q postgres "ALTER SYSTEM RESET max_slot_wal_keep_size" > /dev/null
q postgres "SELECT pg_reload_conf()" > /dev/null

# The database goes away for good. From a container that shares the network of the postgresql container this
# is what a restart of that container looks like: nothing listens any more. The agent must exit, not sit
# there reporting "running", so that Docker restarts it into the current network.
export DLAP_DB_LOST_SECONDS=30
bash "$AG" run > "$T/work/agent3.log" 2>&1 &
APID=$!
wait_for 60 grep -q 'streaming WAL into' "$T/work/agent3.log"
pg_ctl -D "$PGDATA" -m fast stop > /dev/null
wait_for 120 bash -c "! kill -0 $APID 2> /dev/null"
wait "$APID" 2> /dev/null; ARC=$?
check "A-42 an agent that loses the database exits with 75 after DLAP_DB_LOST_SECONDS" test "$ARC" -eq 75
check "A-43 and says why" grep -q 'exiting so that Docker restarts this container' "$T/work/agent3.log"
check "A-44 it stopped its receiver and its jobs on the way out" receiver_stopped
pg_ctl -D "$PGDATA" -o "-c listen_addresses=127.0.0.1 -c port=5432 -c unix_socket_directories=$T" -l "$T/pg.log" -w start > /dev/null
unset DLAP_DB_LOST_SECONDS
run env DLAP_DB_LOST_SECONDS=5 bash "$AG" run
check "A-45 DLAP_DB_LOST_SECONDS under 30 is refused (exit 2)" rc_is 2

unset DLAP_BACKUP_SHIP_CMD DLAP_BACKUP_PASSPHRASE_FILE DLAP_WAL_CYCLE_SECONDS DLAP_BACKUP_RETRY_SECONDS DLAP_ENCRYPTION_KEY_FINGERPRINT
export DLAP_DR_DATA="$T/data"
fi

echo
echo "unit.sh: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
