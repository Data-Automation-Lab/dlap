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
# Key material for the pilot TLS overlay (SEC-06, docs/guides/transport-security.md).
#
# Creates a private CA for the pilot VM and, signed by it, one certificate per server name of the
# compose network, the Kafka keystores, a JVM trust store (the JDK's public roots plus the pilot CA),
# one SCRAM password per Kafka principal and one environment file per Spring service with its Kafka
# login. Nothing here leaves the VM: the CA signs internal names only (and the public host of the
# entry point, unless you bring your own certificate for it with --public-cert).
#
# Usage:  gen-certs.sh [options]
#   --out DIR           output folder (default: generated/ next to this script, or $PILOT_TLS_DIR)
#   --public-host NAME  DNS name or IP address the browsers use for the entry point; repeat for more.
#                       Default localhost. The first one should match PILOT_PUBLIC_ORIGIN.
#   --public-cert FILE  use your own certificate (PEM, leaf first, then the chain) for the entry point
#   --public-key FILE   its private key (PEM, unencrypted); both or neither
#   --days N            validity of the leaf certificates in days (default 397)
#   --ca-days N         validity of a new CA in days (default 3650)
#   --cacerts FILE      the JDK trust store to start the JVM trust store from (default: the cacerts
#                       of $JAVA_HOME, or of the JDK that holds keytool)
#   --renew             issue every leaf certificate and keystore again with the existing CA; the
#                       passwords stay
#   --force             start over: new CA, new certificates, new passwords
#   -h, --help          this text
#
# Without --renew or --force a second run only fills in what is missing, so it is safe to repeat.
# Needs openssl (1.1.1 or later) and keytool (JDK 11 or later).
#
# Output (all files 0600, folders 0700; the overlay's tls-init container hands each file to the one
# container that needs it):
#   ca/ca.key, ca/ca.crt           the pilot CA. ca.key never enters a container; keep a copy offline.
#   certs/                         what the containers mount (through tls-init), see below
#   scram/<principal>.password     Kafka SCRAM passwords, read by kafka-init
#   env/<service>.env              SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG of each Spring service
#   MANIFEST                       subject, names, expiry and SHA-256 fingerprint of every certificate
#
# Exit: 0 done; 1 a tool failed; 2 wrong arguments or a missing prerequisite.

set -Eeuo pipefail
# Git Bash on Windows: openssl and keytool may be native Windows programs that need the shell to turn
# /tmp/... into C:/... A caller that exports MSYS_NO_PATHCONV (restore-drill.sh does, for docker)
# would make them fail on every path, so this script always lets the shell convert its arguments.
unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL
# A failing step must say where it failed: several tools here run with their chatter discarded.
trap 'echo "gen-certs.sh: a step failed (line $LINENO, exit $?)" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${PILOT_TLS_DIR:-$SCRIPT_DIR/generated}"
PUBLIC_HOSTS=()
PUBLIC_CERT=""
PUBLIC_KEY=""
DAYS=397
CA_DAYS=3650
CACERTS=""
RENEW=0
FORCE=0

# The Spring services of the pilot, by compose service name. Each is a Kafka principal of the same
# name, has a server certificate for that name and an environment file.
SERVICES=(da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival
          da-autodelete da-autocompliance da-admin-service da-logingest)
# Other server names with a certificate: the entry point, the identity provider, the database and
# the static UI server. Kafka gets a keystore instead (below).
SERVERS=(keycloak postgresql new-ui)
# Kafka principals that are not Spring services.
EXTRA_PRINCIPALS=(dlap-kafka-exporter)

die() { echo "gen-certs.sh: $*" >&2; exit 2; }
say() { echo "gen-certs.sh: $*" >&2; }
usage() { awk '/^# Key material for the pilot/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out) [ $# -ge 2 ] || die "--out needs a folder"; OUT="$2"; shift 2 ;;
    --public-host) [ $# -ge 2 ] || die "--public-host needs a name"; PUBLIC_HOSTS+=("$2"); shift 2 ;;
    --public-cert) [ $# -ge 2 ] || die "--public-cert needs a file"; PUBLIC_CERT="$2"; shift 2 ;;
    --public-key) [ $# -ge 2 ] || die "--public-key needs a file"; PUBLIC_KEY="$2"; shift 2 ;;
    --days) [ $# -ge 2 ] || die "--days needs a number"; DAYS="$2"; shift 2 ;;
    --ca-days) [ $# -ge 2 ] || die "--ca-days needs a number"; CA_DAYS="$2"; shift 2 ;;
    --cacerts) [ $# -ge 2 ] || die "--cacerts needs a file"; CACERTS="$2"; shift 2 ;;
    --renew) RENEW=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

[[ "$DAYS" =~ ^[1-9][0-9]*$ ]] || die "--days must be a positive whole number"
[[ "$CA_DAYS" =~ ^[1-9][0-9]*$ ]] || die "--ca-days must be a positive whole number"
[ "${#PUBLIC_HOSTS[@]}" -gt 0 ] || PUBLIC_HOSTS=(localhost)
if [ -n "$PUBLIC_CERT" ] || [ -n "$PUBLIC_KEY" ]; then
  [ -n "$PUBLIC_CERT" ] && [ -n "$PUBLIC_KEY" ] || die "--public-cert and --public-key go together"
  [ -f "$PUBLIC_CERT" ] || die "no such file: $PUBLIC_CERT"
  [ -f "$PUBLIC_KEY" ] || die "no such file: $PUBLIC_KEY"
fi
for name in "${PUBLIC_HOSTS[@]}"; do
  [[ "$name" =~ ^[A-Za-z0-9.:-]+$ ]] || die "not a host name or IP address: $name"
done

command -v openssl >/dev/null 2>&1 || die "openssl is not installed"
command -v keytool >/dev/null 2>&1 || die "keytool is not installed (it comes with the JDK 21 that builds the jars)"

if [ -z "$CACERTS" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/lib/security/cacerts" ]; then
    CACERTS="$JAVA_HOME/lib/security/cacerts"
  else
    kt="$(command -v keytool)"
    kt="$(readlink -f "$kt" 2>/dev/null || printf '%s' "$kt")"
    CACERTS="$(dirname "$(dirname "$kt")")/lib/security/cacerts"
  fi
fi
[ -f "$CACERTS" ] || die "no JDK trust store at $CACERTS (pass --cacerts FILE)"

umask 077
if [ "$FORCE" = 1 ] && [ -d "$OUT" ]; then
  say "--force: removing $OUT"
  rm -rf "$OUT"
fi
mkdir -p "$OUT/ca" "$OUT/certs" "$OUT/scram" "$OUT/env" "$OUT/work"
WORK="$OUT/work"
trap 'rm -rf "$WORK"' EXIT

# --- helpers -----------------------------------------------------------------------------------

# A random password of 48 hex characters: no quoting problems in JAAS lines, env files or URLs.
# A Windows build of openssl ends the line with CR LF; the files hold the bare value and one LF.
new_password() { printf '%s\n' "$(openssl rand -hex 24 | tr -d '\r\n')"; }

# keep_or_make FILE: true (0) when FILE must be (re)made.
must_make() { [ ! -s "$1" ] || [ "$RENEW" = 1 ]; }

# The subjectAltName list for a set of names: IP addresses as IP:, the rest as DNS:.
san_of() {
  local out="" n
  for n in "$@"; do
    if [[ "$n" =~ ^[0-9.]+$ ]] || [[ "$n" == *:* ]]; then out+="${out:+,}IP:$n"; else out+="${out:+,}DNS:$n"; fi
  done
  printf '%s' "$out"
}

# issue NAME EKU NAMES...: an EC P-256 key and a certificate for CN=NAME signed by the pilot CA.
# The subject goes through a config file, never through -subj, so no shell (Git Bash included)
# rewrites it as a path.
issue() {
  local name="$1" eku="$2"; shift 2
  local key="$OUT/certs/$name.key" crt="$OUT/certs/$name.crt" cnf="$WORK/$name.cnf"
  must_make "$crt" || return 0
  cat >"$cnf" <<EOF
[req]
prompt = no
distinguished_name = dn
[dn]
CN = $name
O = DLAP pilot
[ext]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = $eku
subjectAltName = $(san_of "$@")
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
EOF
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$key" 2>/dev/null
  openssl req -new -key "$key" -config "$cnf" -out "$WORK/$name.csr"
  openssl x509 -req -in "$WORK/$name.csr" -CA "$OUT/ca/ca.crt" -CAkey "$OUT/ca/ca.key" \
    -CAserial "$OUT/ca/ca.srl" -CAcreateserial -days "$DAYS" -sha256 \
    -extfile "$cnf" -extensions ext -out "$crt" 2>/dev/null
  say "issued $name ($(san_of "$@"))"
}

# p12 NAME: a PKCS12 keystore holding NAME's key and certificate chain, password in NAME.password.
p12() {
  local name="$1" store="$OUT/certs/$1.keystore.p12" pass="$OUT/certs/$1.password"
  [ -s "$pass" ] || new_password >"$pass"
  must_make "$store" || return 0
  rm -f "$store"
  openssl pkcs12 -export -name "$name" -inkey "$OUT/certs/$name.key" -in "$OUT/certs/$name.crt" \
    -certfile "$OUT/ca/ca.crt" -passout stdin -out "$store" <"$pass"
}

# --- the CA ------------------------------------------------------------------------------------

if [ ! -s "$OUT/ca/ca.key" ] || [ ! -s "$OUT/ca/ca.crt" ]; then
  cat >"$WORK/ca.cnf" <<EOF
[req]
prompt = no
distinguished_name = dn
[dn]
CN = DLAP pilot CA $(date -u +%Y%m%d%H%M%S)
O = DLAP pilot
[ext]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$OUT/ca/ca.key" 2>/dev/null
  openssl req -new -x509 -key "$OUT/ca/ca.key" -config "$WORK/ca.cnf" -extensions ext \
    -days "$CA_DAYS" -sha256 -out "$OUT/ca/ca.crt"
  RENEW=1   # a new CA invalidates every leaf issued before
  say "created a new pilot CA"
fi
cp "$OUT/ca/ca.crt" "$OUT/certs/ca.crt"

# --- leaf certificates -------------------------------------------------------------------------

# The entry point: the public names, plus the loopback names for an SSH tunnel and the smoke test.
if [ -n "$PUBLIC_CERT" ]; then
  cp "$PUBLIC_CERT" "$OUT/certs/gateway.crt"
  cp "$PUBLIC_KEY" "$OUT/certs/gateway.key"
  say "entry point: using the certificate from $PUBLIC_CERT"
else
  issue gateway serverAuth "${PUBLIC_HOSTS[@]}" gateway localhost 127.0.0.1
fi
for name in "${SERVERS[@]}"; do
  issue "$name" serverAuth "$name" localhost 127.0.0.1
done
for svc in "${SERVICES[@]}"; do
  issue "$svc" serverAuth "$svc" localhost 127.0.0.1
done
# Kafka: the broker certificate also authenticates the broker to its own controller and inter-broker
# listeners (mutual TLS), and the admin certificate is the identity of kafka-init and the smoke test.
issue kafka "serverAuth, clientAuth" kafka localhost
issue dlap-kafka-admin clientAuth dlap-kafka-admin
p12 kafka
p12 dlap-kafka-admin

# --- trust stores ------------------------------------------------------------------------------

# The broker's trust store for its mutual-TLS listeners: the pilot CA only.
if must_make "$OUT/certs/kafka.truststore.p12"; then
  rm -f "$OUT/certs/kafka.truststore.p12"
  keytool -importcert -noprompt -alias dlap-pilot-ca -file "$OUT/ca/ca.crt" -storetype PKCS12 \
    -keystore "$OUT/certs/kafka.truststore.p12" -storepass:file "$OUT/certs/kafka.password" >/dev/null
fi

# The JVM trust store of every Spring service: the JDK's public roots (Google APIs and the other
# cloud endpoints) plus the pilot CA (Keycloak and the other services). It holds certificates only,
# so it is written without a password or MAC and the services need no secret to read it.
if must_make "$OUT/certs/jvm-truststore.p12"; then
  rm -f "$OUT/certs/jvm-truststore.p12"
  nopw=(-J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE)
  keytool "${nopw[@]}" -importkeystore -noprompt -srckeystore "$CACERTS" -srcstorepass changeit \
    -destkeystore "$OUT/certs/jvm-truststore.p12" -deststoretype PKCS12 -deststorepass unused-no-mac >/dev/null 2>&1
  keytool "${nopw[@]}" -importcert -noprompt -alias dlap-pilot-ca -file "$OUT/ca/ca.crt" \
    -keystore "$OUT/certs/jvm-truststore.p12" -storetype PKCS12 -storepass unused-no-mac >/dev/null
  say "JVM trust store: $(keytool -list -keystore "$OUT/certs/jvm-truststore.p12" -storetype PKCS12 -storepass unused-no-mac 2>/dev/null | grep -c 'trustedCertEntry') roots"
fi

# --- Kafka SCRAM principals --------------------------------------------------------------------

for principal in "${SERVICES[@]}" "${EXTRA_PRINCIPALS[@]}"; do
  [ -s "$OUT/scram/$principal.password" ] || new_password >"$OUT/scram/$principal.password"
done
for svc in "${SERVICES[@]}"; do
  pw="$(cat "$OUT/scram/$svc.password")"
  {
    echo "# Generated by deployment/environments/pilot/tls/gen-certs.sh (SEC-06): the Kafka login of $svc."
    echo "SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG='org.apache.kafka.common.security.scram.ScramLoginModule required username=\"$svc\" password=\"$pw\";'"
  } >"$OUT/env/$svc.env"
done

# --- manifest ----------------------------------------------------------------------------------

{
  echo "# Certificates of the pilot TLS overlay, written $(date -u +%Y-%m-%dT%H:%M:%SZ) by gen-certs.sh."
  for crt in "$OUT/ca/ca.crt" "$OUT"/certs/*.crt; do
    [ "$crt" = "$OUT/certs/ca.crt" ] && continue
    echo
    echo "file:    ${crt#"$OUT"/}"
    openssl x509 -in "$crt" -noout -subject -enddate -fingerprint -sha256 | sed 's/^/  /'
    openssl x509 -in "$crt" -noout -ext subjectAltName 2>/dev/null | sed -n '2p' | sed 's/^ */  names: /'
  done
} >"$OUT/MANIFEST"

chmod 700 "$OUT" "$OUT/ca" "$OUT/certs" "$OUT/scram" "$OUT/env"
find "$OUT" -type f -exec chmod 600 {} +
say "done: $OUT (see $OUT/MANIFEST). Keep ca/ca.key offline once the pilot runs."
