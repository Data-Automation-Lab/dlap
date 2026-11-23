#!/bin/sh
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
# tls-init (SEC-06): the first container of the pilot TLS overlay. POSIX sh (busybox).
#
# Copies the output of gen-certs.sh (mounted read-only at /src) into the volume pilot-tls (mounted at
# /tls) and gives every private file to exactly one consumer: owner root, group <the consumer's
# group>, mode 0440. Each consumer container carries that group in group_add, so it reads its own key
# and nobody else's, whatever uid its image runs as. Certificates and the trust stores, which hold
# nothing secret, are 0444. PostgreSQL is the exception: it drops supplementary groups when it
# switches to its own user, and it refuses a key that its group can read, so its key belongs to the
# postgres user of the postgres:15.1 image (uid 999) with mode 0600.
#
# The groups (must match group_add in docker-compose.pilot.tls.yml):
#   61010 kafka (broker keystore, trust store, password)    61011 kafka-init (admin keystore, SCRAM)
#   61012 kafka-exporter (its SCRAM password)               61020 gateway
#   61030 keycloak                                          61040 new-ui
#   61101..61110 the ten Spring services, in the order of SERVICES below
#
# Exit: 0 done; 1 something is missing (the message names gen-certs.sh).

set -eu

SRC="${DLAP_TLS_INIT_SRC:-/src}"
DST="${DLAP_TLS_INIT_DST:-/tls}"
SERVICES="da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete da-autocompliance da-admin-service da-logingest"

fail() {
  echo "tls-init: $*" >&2
  echo "tls-init: generate the key material first: deployment/environments/pilot/tls/gen-certs.sh --public-host <name>" >&2
  echo "tls-init: (or point PILOT_TLS_DIR in the environment file at the folder it wrote)" >&2
  exit 1
}

[ -d "$SRC/certs" ] || fail "no certs folder in the mounted key material ($SRC)"
[ -d "$SRC/scram" ] || fail "no scram folder in the mounted key material ($SRC)"

# put <file under certs/> <owner:group> <mode>
put() {
  [ -s "$SRC/certs/$1" ] || fail "missing certs/$1"
  cp "$SRC/certs/$1" "$DST/$1.tmp"
  chown "$2" "$DST/$1.tmp"
  chmod "$3" "$DST/$1.tmp"
  mv -f "$DST/$1.tmp" "$DST/$1"
}

public() { put "$1" 0:0 0444; }

public ca.crt
public jvm-truststore.p12

put kafka.keystore.p12 0:61010 0440
put kafka.truststore.p12 0:61010 0440
put kafka.password 0:61010 0440
put dlap-kafka-admin.keystore.p12 0:61011 0440
put dlap-kafka-admin.password 0:61011 0440

for name in gateway keycloak new-ui; do
  case "$name" in
    gateway) gid=61020 ;;
    keycloak) gid=61030 ;;
    new-ui) gid=61040 ;;
  esac
  public "$name.crt"
  put "$name.key" "0:$gid" 0440
done

public postgresql.crt
put postgresql.key 999:999 0600

gid=61101
for svc in $SERVICES; do
  public "$svc.crt"
  put "$svc.key" "0:$gid" 0440
  gid=$((gid + 1))
done

mkdir -p "$DST/scram"
chown 0:61011 "$DST/scram"
chmod 0750 "$DST/scram"
count=0
for f in "$SRC"/scram/*.password; do
  [ -s "$f" ] || fail "no SCRAM passwords in scram/"
  base="$(basename "$f")"
  cp "$f" "$DST/scram/$base.tmp"
  chown 0:61011 "$DST/scram/$base.tmp"
  chmod 0440 "$DST/scram/$base.tmp"
  mv -f "$DST/scram/$base.tmp" "$DST/scram/$base"
  count=$((count + 1))
done
[ -s "$SRC/scram/dlap-kafka-exporter.password" ] || fail "missing scram/dlap-kafka-exporter.password"
cp "$SRC/scram/dlap-kafka-exporter.password" "$DST/kafka-exporter.password.tmp"
chown 0:61012 "$DST/kafka-exporter.password.tmp"
chmod 0440 "$DST/kafka-exporter.password.tmp"
mv -f "$DST/kafka-exporter.password.tmp" "$DST/kafka-exporter.password"

chown 0:0 "$DST"
chmod 0755 "$DST"
echo "tls-init: key material in place ($count SCRAM users)"
