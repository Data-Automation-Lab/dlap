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
# kafka-init (SEC-06): the one-shot container of the pilot TLS overlay that gives the broker its
# users and ACLs before any service starts.
#
# It runs in the broker's own image, talks to the broker's mutual-TLS listener SSL
# (kafka:29094) as the super user CN=dlap-kafka-admin, and then:
#   1. creates or updates one SCRAM-SHA-512 user per file in /tls/scram (<principal>.password);
#   2. adds the ACLs of /opt/dlap-tls/kafka-acls.txt (format in that file).
# Both steps are idempotent, so the container runs on every `up`. ACLs are only added: to take one
# away, remove the line and run kafka-acls --remove by hand (docs/guides/transport-security.md).
#
# Inputs (mounted by docker-compose.pilot.tls.yml):
#   /tls/dlap-kafka-admin.keystore.p12, /tls/dlap-kafka-admin.password, /tls/ca.crt
#   /tls/scram/*.password
#   /opt/dlap-tls/kafka-acls.txt
# Environment: DLAP_KAFKA_INIT_BOOTSTRAP (default kafka:29094), DLAP_KAFKA_INIT_ACLS (default
# /opt/dlap-tls/kafka-acls.txt), DLAP_KAFKA_INIT_TLS (default /tls), DLAP_KAFKA_INIT_DRY_RUN=1 prints
# the commands instead of running them (test-tls.sh uses it).
#
# Exit: 0 done; 1 a Kafka command failed; 2 an input is missing or a line of the ACL file is wrong.

set -euo pipefail

BOOTSTRAP="${DLAP_KAFKA_INIT_BOOTSTRAP:-kafka:29094}"
ACLS="${DLAP_KAFKA_INIT_ACLS:-/opt/dlap-tls/kafka-acls.txt}"
TLS="${DLAP_KAFKA_INIT_TLS:-/tls}"
DRY_RUN="${DLAP_KAFKA_INIT_DRY_RUN:-0}"
ITERATIONS=8192

die() { echo "kafka-init: $*" >&2; exit 2; }
say() { echo "kafka-init: $*" >&2; }

[ -f "$ACLS" ] || die "no ACL file at $ACLS"
[ -d "$TLS/scram" ] || die "no SCRAM passwords in $TLS/scram: run deployment/environments/pilot/tls/gen-certs.sh"
if [ "$DRY_RUN" != 1 ]; then
  for f in dlap-kafka-admin.keystore.p12 dlap-kafka-admin.password ca.crt; do
    [ -s "$TLS/$f" ] || die "missing $TLS/$f: run deployment/environments/pilot/tls/gen-certs.sh"
  done
fi

# The admin client configuration lives in a private temporary file: it holds the keystore password.
umask 077
CONFIG="$(mktemp)"
trap 'rm -f "$CONFIG"' EXIT
if [ "$DRY_RUN" != 1 ]; then
  pw="$(cat "$TLS/dlap-kafka-admin.password")"
  cat >"$CONFIG" <<EOF
security.protocol=SSL
ssl.keystore.type=PKCS12
ssl.keystore.location=$TLS/dlap-kafka-admin.keystore.p12
ssl.keystore.password=$pw
ssl.key.password=$pw
ssl.truststore.type=PEM
ssl.truststore.location=$TLS/ca.crt
ssl.endpoint.identification.algorithm=https
EOF
fi

run() {
  if [ "$DRY_RUN" = 1 ]; then
    printf '%s\n' "$*"
  else
    "$@" >/dev/null
  fi
}

acl() { # acl <principal> <resource flags...> -- <operations...>
  local principal="$1"; shift
  local resource=() ops=()
  while [ "$1" != "--" ]; do resource+=("$1"); shift; done
  shift
  for op in "$@"; do ops+=(--operation "$op"); done
  run kafka-acls --bootstrap-server "$BOOTSTRAP" --command-config "$CONFIG" --add \
    --allow-principal "User:$principal" "${ops[@]}" "${resource[@]}"
}

topics() { # topics <suffix> <names...>: --topic flags, each name with the suffix appended
  local suffix="$1"; shift
  local n
  for n in "$@"; do printf '%s\n' --topic "$n$suffix"; done
}

# --- 1. SCRAM users ----------------------------------------------------------------------------

users=0
for file in "$TLS"/scram/*.password; do
  [ -e "$file" ] || die "no SCRAM password files in $TLS/scram"
  principal="$(basename "$file" .password)"
  [[ "$principal" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || die "not a principal name: $principal"
  password="$(cat "$file")"
  [[ "$password" =~ ^[0-9a-f]{32,}$ ]] || die "the password of $principal is not the hex string gen-certs.sh writes"
  if [ "$DRY_RUN" = 1 ]; then
    printf '%s\n' "kafka-configs --alter --entity-type users --entity-name $principal --add-config SCRAM-SHA-512=[iterations=$ITERATIONS,password=***]"
  else
    kafka-configs --bootstrap-server "$BOOTSTRAP" --command-config "$CONFIG" --alter \
      --entity-type users --entity-name "$principal" \
      --add-config "SCRAM-SHA-512=[iterations=$ITERATIONS,password=$password]" >/dev/null
  fi
  users=$((users + 1))
done
say "SCRAM users: $users"

# --- 2. ACLs -----------------------------------------------------------------------------------

lines=0
lineno=0
while IFS= read -r line || [ -n "$line" ]; do
  lineno=$((lineno + 1))
  line="${line%%#*}"
  read -r -a words <<<"$line" || true
  [ "${#words[@]}" -gt 0 ] || continue
  [ "${#words[@]}" -ge 3 ] || die "$ACLS:$lineno: expected <principal> <verb> <name>..."
  principal="${words[0]}"; verb="${words[1]}"; names=("${words[@]:2}")
  [ -f "$TLS/scram/$principal.password" ] || [ "$DRY_RUN" = 1 ] \
    || die "$ACLS:$lineno: $principal has no SCRAM password in $TLS/scram"
  mapfile -t plain < <(topics "" "${names[@]}")
  mapfile -t dlt < <(topics ".DLT" "${names[@]}")
  case "$verb" in
    consume)
      # Create: a consumer that subscribes before the topic exists makes the broker create it (as in
      # the quickstart). Without it the broker answers TOPIC_AUTHORIZATION_FAILED and Spring stops
      # the listener container for good.
      acl "$principal" "${plain[@]}" -- Read Describe Create
      acl "$principal" "${dlt[@]}" -- Write Describe Create
      ;;
    produce)
      acl "$principal" "${plain[@]}" -- Write Describe Create
      ;;
    provision)
      acl "$principal" "${plain[@]}" "${dlt[@]}" -- Create Describe DescribeConfigs Alter
      ;;
    group)
      for g in "${names[@]}"; do
        acl "$principal" --group "$g" --resource-pattern-type prefixed -- Read
      done
      ;;
    describe)
      [ "${names[*]}" = "*" ] || die "$ACLS:$lineno: describe takes only *"
      acl "$principal" --topic '*' -- Describe DescribeConfigs
      acl "$principal" --group '*' -- Describe
      acl "$principal" --cluster -- Describe
      ;;
    *) die "$ACLS:$lineno: unknown verb $verb (consume, produce, provision, group, describe)" ;;
  esac
  lines=$((lines + 1))
done <"$ACLS"
say "ACL lines applied: $lines"
