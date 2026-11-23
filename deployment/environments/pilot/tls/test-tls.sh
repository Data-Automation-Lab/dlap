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
# Tests of the pilot TLS overlay's tooling (SEC-06). The live proof of each hop is smoke-tls.sh on a
# running pilot (tls-e2e.sh); these tests cover the pieces on their own and need no running pilot.
#
#   T1  gen-certs.sh: every certificate chains to the pilot CA, names the right host, has the right
#       key usage and an EC P-256 key; a second run changes nothing; --renew re-issues the leaves
#       under the same CA and keeps the passwords; the env files name their own SCRAM user; the JVM
#       trust store loads without a password and trusts the pilot CA.
#   T2  kafka-acls.txt against the code: every @KafkaListener topic and group, every KafkaTemplate
#       topic and every provisioned topic of a service is granted to that service, and nothing writes
#       the policy-action or normalized-log topics except the services the code shows.
#   T3  kafka-init.sh (dry run): the commands it would run for the ACL file, and its refusals.
#   T4  smoke-tls.sh --static and verify.sh pass on the merged model with the example environment.
#   T5  (Docker) tls-init.sh hands each private file to exactly one group with mode 0440 (postgres:
#       uid 999, 0600); PostgreSQL with tls/pg_hba.conf accepts TLS and refuses plain TCP; nginx accepts
#       tls/nginx.pilot.tls.conf (nginx -t).
#
# Usage:  test-tls.sh [--quick]     --quick skips T5 (no Docker)
# Needs: bash, openssl, keytool and java (JDK 21), python3; Docker for T5.
# Exit: 0 all passed (T5 may SKIP without Docker); 1 a test failed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PILOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PILOT_DIR/../../.." && pwd)"
QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
[ -n "$PYTHON" ] || { echo "test-tls.sh: python3 is required" >&2; exit 1; }

PASSED=0
FAILED=0
ok() { PASSED=$((PASSED + 1)); echo "PASS  $1"; }
ko() { FAILED=$((FAILED + 1)); echo "FAIL  $1${2:+  ($2)}"; }
skip() { echo "SKIP  $1${2:+  ($2)}"; }
is() { if eval "$2"; then ok "$1"; else ko "$1" "${3:-}"; fi; }

dk() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"; }
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- T1 gen-certs.sh --------------------------------------------------------------------------

G="$WORK/gen"
if bash "$SCRIPT_DIR/gen-certs.sh" --out "$G" --public-host pilot.example.test --public-host 10.0.0.5 >"$WORK/gen.log" 2>&1; then
  ok "T1 gen-certs.sh runs"
else
  ko "T1 gen-certs.sh runs" "$(tail -n 3 "$WORK/gen.log" | tr '\n' ' ')"
fi
C="$G/certs"
sans() { openssl x509 -in "$1" -noout -ext subjectAltName 2>/dev/null | sed -n '2p' | tr -d ' '; }
eku() { openssl x509 -in "$1" -noout -ext extendedKeyUsage 2>/dev/null | sed -n '2p' | tr -d ' '; }
for name in gateway keycloak postgresql new-ui kafka dlap-kafka-admin da-discovery da-catalog da-policyengine da-reporting \
            da-autolabel da-autoarchival da-autodelete da-autocompliance da-admin-service da-logingest; do
  is "T1 $name chains to the pilot CA" "openssl verify -CAfile '$C/ca.crt' '$C/$name.crt' >/dev/null 2>&1"
  is "T1 $name has an EC P-256 key" "openssl pkey -in '$C/$name.key' -noout -text 2>/dev/null | grep -q 'prime256v1\|P-256'"
  s="$(sans "$C/$name.crt")"
  case "$name" in
    gateway) is "T1 gateway names the public hosts, gateway and loopback" "[[ '$s' == *DNS:pilot.example.test* && '$s' == *IPAddress:10.0.0.5* && '$s' == *DNS:gateway* && '$s' == *DNS:localhost* ]]" "$s" ;;
    dlap-kafka-admin) is "T1 dlap-kafka-admin is a client certificate only" "[ '$(eku "$C/$name.crt")' = 'TLSWebClientAuthentication' ]" "$(eku "$C/$name.crt")" ;;
    kafka) is "T1 kafka is a server and client certificate for kafka" "[[ '$s' == DNS:kafka,* && '$(eku "$C/$name.crt")' == *Server* && '$(eku "$C/$name.crt")' == *Client* ]]" ;;
    *) is "T1 $name names $name first" "[[ '$s' == DNS:$name,* ]]" "$s" ;;
  esac
done
is "T1 the CA is a CA with pathlen 0" "openssl x509 -in '$C/ca.crt' -noout -ext basicConstraints | grep -q 'CA:TRUE, pathlen:0'"
is "T1 no leaf is a CA" "! openssl x509 -in '$C/da-catalog.crt' -noout -ext basicConstraints | grep -q 'CA:TRUE'"
is "T1 the CA key is outside certs/ (never mounted)" "[ -s '$G/ca/ca.key' ] && [ ! -e '$C/ca.key' ]"
is "T1 the Kafka keystore opens with its password" "keytool -list -keystore '$C/kafka.keystore.p12' -storetype PKCS12 -storepass:file '$C/kafka.password' 2>/dev/null | grep -q 'kafka, .*PrivateKeyEntry'"
is "T1 the Kafka trust store holds the pilot CA" "keytool -list -keystore '$C/kafka.truststore.p12' -storetype PKCS12 -storepass:file '$C/kafka.password' 2>/dev/null | grep -q 'dlap-pilot-ca'"
pw_count="$(ls "$G/scram" | grep -c '\.password$')"
is "T1 one SCRAM password per principal (10 services and the exporter)" "[ '$pw_count' = 11 ]" "$pw_count"
not_hex=""
for f in "$G"/scram/*.password "$C"/kafka.password "$C"/dlap-kafka-admin.password; do
  # Exactly 48 hex digits and one LF: tr removes the allowed bytes, so anything left (a CR) fails.
  [ "$(tr -d '0-9a-f' <"$f" | od -An -c | tr -d ' ')" = '\n' ] && [ "$(tr -cd '0-9a-f' <"$f" | wc -c | tr -d ' ')" = 48 ] \
    || not_hex+="$(basename "$f") "
done
is "T1 every password file is one line of 48 hex characters (no CR)" "[ -z \"\$not_hex\" ]" "$not_hex"
bad_env=0
for f in "$G"/env/*.env; do
  svc="$(basename "$f" .env)"
  grep -q "^SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG='org.apache.kafka.common.security.scram.ScramLoginModule required username=\"$svc\" password=\"$(cat "$G/scram/$svc.password")\";'$" "$f" || bad_env=$((bad_env + 1))
done
is "T1 each env file holds the SCRAM login of its own service" "[ '$bad_env' = 0 ] && [ \"\$(ls '$G/env' | wc -l | tr -d ' ')\" = 10 ]" "$bad_env wrong"

# The JVM trust store, loaded the way the services load it: JSSE without a password.
cat >"$WORK/TrustCheck.java" <<'JAVA'
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class TrustCheck {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(args[0])) {
            store.load(in, null);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        X509TrustManager tm = (X509TrustManager) tmf.getTrustManagers()[0];
        int roots = tm.getAcceptedIssuers().length;
        boolean pilot = store.isCertificateEntry("dlap-pilot-ca");
        boolean publicRoot = Collections.list(store.aliases()).stream().anyMatch(a -> {
            try {
                return ((X509Certificate) store.getCertificate(a)).getSubjectX500Principal().getName().contains("ISRG Root X1");
            } catch (Exception e) {
                return false;
            }
        });
        System.out.println("roots=" + roots + " pilot=" + pilot + " public=" + publicRoot);
    }
}
JAVA
if command -v java >/dev/null 2>&1; then
  tc="$(java "$(native "$WORK/TrustCheck.java")" "$(native "$C/jvm-truststore.p12")" 2>&1)"
  is "T1 the JVM trust store loads without a password and trusts the pilot CA and public roots" "[[ '$tc' == *pilot=true\ public=true* ]]" "$tc"
else
  ko "T1 the JVM trust store check" "java is not on PATH"
fi

fp() { openssl x509 -in "$1" -noout -fingerprint -sha256; }
before_ca="$(fp "$C/ca.crt")"; before_leaf="$(fp "$C/da-catalog.crt")"; before_pw="$(cat "$G/scram/da-catalog.password")"
bash "$SCRIPT_DIR/gen-certs.sh" --out "$G" >/dev/null 2>&1
is "T1 a second run changes nothing" "[ '$(fp "$C/ca.crt")' = '$before_ca' ] && [ '$(fp "$C/da-catalog.crt")' = '$before_leaf' ]"
bash "$SCRIPT_DIR/gen-certs.sh" --out "$G" --renew >/dev/null 2>&1
is "T1 --renew re-issues the leaves under the same CA" "[ '$(fp "$C/ca.crt")' = '$before_ca' ] && [ '$(fp "$C/da-catalog.crt")' != '$before_leaf' ] && openssl verify -CAfile '$C/ca.crt' '$C/da-catalog.crt' >/dev/null 2>&1"
is "T1 --renew keeps the SCRAM passwords" "[ '$(cat "$G/scram/da-catalog.password")' = '$before_pw' ]"
bash "$SCRIPT_DIR/gen-certs.sh" --out "$G" --force >/dev/null 2>&1
is "T1 --force makes a new CA" "[ '$(fp "$C/ca.crt")' != '$before_ca' ] && openssl verify -CAfile '$C/ca.crt' '$C/da-catalog.crt' >/dev/null 2>&1"
is "T1 a bad host name is refused (exit 2)" "bash '$SCRIPT_DIR/gen-certs.sh' --out '$WORK/bad' --public-host 'a b' >/dev/null 2>&1; [ \$? = 2 ]"
is "T1 --public-cert without --public-key is refused (exit 2)" "bash '$SCRIPT_DIR/gen-certs.sh' --out '$WORK/bad' --public-cert '$C/gateway.crt' >/dev/null 2>&1; [ \$? = 2 ]"
# Callers such as restore-drill.sh export MSYS_NO_PATHCONV for docker; gen-certs.sh must still work.
is "T1 gen-certs.sh works when the caller exports MSYS_NO_PATHCONV=1" "MSYS_NO_PATHCONV=1 bash '$SCRIPT_DIR/gen-certs.sh' --out '$WORK/nopathconv' --force >/dev/null 2>&1 && openssl verify -CAfile '$WORK/nopathconv/certs/ca.crt' '$WORK/nopathconv/certs/da-catalog.crt' >/dev/null 2>&1"
is "T1 a failing step names its line instead of exiting silently" "mkdir -p '$WORK/ro' && : > '$WORK/ro/ca' && bash '$SCRIPT_DIR/gen-certs.sh' --out '$WORK/ro' >'$WORK/ro.log' 2>&1; [ \$? != 0 ] && grep -q 'gen-certs.sh: a step failed (line' '$WORK/ro.log'"
cp "$C/gateway.crt" "$WORK/own.crt"; cp "$C/gateway.key" "$WORK/own.key"
bash "$SCRIPT_DIR/gen-certs.sh" --out "$WORK/own" --public-cert "$WORK/own.crt" --public-key "$WORK/own.key" >/dev/null 2>&1
is "T1 --public-cert puts the operator's certificate on the entry point" "cmp -s '$WORK/own.crt' '$WORK/own/certs/gateway.crt'"

# --- T2 the ACL file against the code ------------------------------------------------------------

t2="$("$PYTHON" - "$REPO_ROOT" "$SCRIPT_DIR/kafka-acls.txt" <<'PY'
import os
import re
import sys

root, acl_file = sys.argv[1], sys.argv[2]
MODULES = ["da-discovery", "da-catalog", "da-policyengine", "da-reporting", "da-autolabel", "da-autoarchival",
           "da-autodelete", "da-autocompliance", "da-admin-service", "da-logingest"]

const_src = open(os.path.join(root, "da-protos/src/main/java/com/dlap/common/constants/DlapConstants.java"),
                 encoding="utf-8").read()
kt = const_src[const_src.index("class KafkaTopic"):]
kt = kt[:kt.index("\n    }\n")]
TOPIC = dict(re.findall(r'String\s+([A-Z_]+)\s*=\s*"([^"]+)"', kt))

# The grants, as kafka-init.sh reads them.
grants = {}
for raw in open(acl_file, encoding="utf-8"):
    words = raw.split("#", 1)[0].split()
    if len(words) >= 3:
        grants.setdefault(words[0], {}).setdefault(words[1], set()).update(words[2:])


def props(module):
    """Flat key -> value of the module's application.properties and config/application.yml."""
    out = {}
    p = os.path.join(root, module, "src/main/resources/application.properties")
    if os.path.exists(p):
        for line in open(p, encoding="utf-8", errors="replace"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    y = os.path.join(root, module, "src/main/resources/config/application.yml")
    if os.path.exists(y):
        stack = []
        for line in open(y, encoding="utf-8", errors="replace"):
            if not line.strip() or line.lstrip().startswith("#") or line.lstrip().startswith("-"):
                continue
            m = re.match(r"^(\s*)([A-Za-z0-9_.-]+):\s*(.*)$", line.rstrip("\n"))
            if not m:
                continue
            indent = len(m.group(1))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, m.group(2)))
            value = m.group(3).split(" #", 1)[0].strip().strip("'\"")
            if value:
                out.setdefault(".".join(k for _, k in stack), value)
    return out


def resolve(expr, p, depth=0):
    """A topic or group expression: a constant, a literal or a ${property[:default]} placeholder."""
    expr = expr.strip()
    m = re.fullmatch(r"(?:com\.dlap\.common\.constants\.)?DlapConstants\.KafkaTopic\.([A-Z_]+)", expr)
    if m:
        return TOPIC[m.group(1)]
    m = re.fullmatch(r'"(.*)"', expr, re.S)
    if m:
        expr = m.group(1)
    m = re.fullmatch(r"\$\{([^:}]+)(?::([^}]*))?\}", expr)
    if m:
        key, default = m.group(1), m.group(2)
        value = p.get(key)
        if value is None:
            return default
        return resolve(value, p, depth + 1) if depth < 5 else value
    if "${" in expr and depth < 5:
        # A placeholder inside a longer name, for example "${spring.kafka.consumer.group-id}-proof".
        return re.sub(r"\$\{[^}]*\}", lambda x: resolve(x.group(0), p, depth + 1) or x.group(0), expr)
    return expr


problems = []
listeners = 0
for module in MODULES:
    p = props(module)
    g = grants.get(module, {})
    src_root = os.path.join(root, module, "src/main/java")
    constants = {}
    for dirpath, _, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".java"):
                continue
            text = open(os.path.join(dirpath, f), encoding="utf-8", errors="replace").read()
            for name, const in re.findall(r"(?:static\s+)?final\s+String\s+([A-Z_]+)\s*=\s*(?:com\.dlap\.common\.constants\.)?DlapConstants\.KafkaTopic\.([A-Z_]+)", text):
                constants[(f, name)] = TOPIC[const]
            local_groups = dict(re.findall(r'String\s+([A-Z_]+)\s*=\s*"([^"]+)"', text))
            for m in re.finditer(r"@KafkaListener\s*\(", text):
                depth, i = 1, m.end()
                while depth and i < len(text):
                    depth += {"(": 1, ")": -1}.get(text[i], 0)
                    i += 1
                body = text[m.end():i - 1]
                listeners += 1
                tm = re.search(r"topics\s*=\s*(\{[^}]*\}|\"[^\"]*\"|[A-Za-z_.]+)", body)
                if not tm:
                    problems.append(f"{module} {f}: a @KafkaListener without topics=")
                    continue
                items = [x for x in re.split(r",\s*", tm.group(1).strip("{} \n")) if x.strip()]
                for item in items:
                    topic = resolve(item, p)
                    if topic not in g.get("consume", set()):
                        problems.append(f"{module} {f}: consumes {topic} but kafka-acls.txt has no '{module} consume {topic}'")
                gm = re.search(r"groupId\s*=\s*(\"[^\"]*\"|[A-Za-z_.]+)", body)
                if gm:
                    raw = gm.group(1)
                    group = local_groups.get(raw, None) if not raw.startswith('"') else None
                    group = group or resolve(raw, p)
                else:
                    group = resolve("${spring.kafka.consumer.group-id}", p)
                group = resolve(group, p) if group and group.startswith("${") else group
                if not any(group.startswith(prefix) for prefix in g.get("group", set())):
                    problems.append(f"{module} {f}: consumer group {group} matches no '{module} group <prefix>'")
    # Topic provisioning (config/KafkaTopicsConfiguration.java).
    tc = os.path.join(src_root, "com/dlap", module.replace("da-", "").replace("-service", "service").replace("-", ""),
                      "config/KafkaTopicsConfiguration.java")
    if os.path.exists(tc):
        text = open(tc, encoding="utf-8").read()
        for const in re.findall(r"(?:topic|deadLetterTopic)\(\s*DlapConstants\.KafkaTopic\.([A-Z_]+)", text):
            if TOPIC[const] not in g.get("provision", set()):
                problems.append(f"{module} KafkaTopicsConfiguration: provisions {TOPIC[const]} but kafka-acls.txt has no '{module} provision {TOPIC[const]}'")
    # Any other topic a bean declares: TopicBuilder.name(<field>) or new NewTopic(<field>, ...), the field
    # read with @Value("${property:default}").
    for dirpath, _, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".java") or f == "KafkaTopicsConfiguration.java":
                continue
            text = open(os.path.join(dirpath, f), encoding="utf-8", errors="replace").read()
            fields = dict((name, expr) for expr, name in
                          re.findall(r'@Value\(\s*"([^"]+)"\s*\)\s*(?:private\s+|protected\s+|public\s+)?String\s+(\w+)', text))
            for arg in re.findall(r"TopicBuilder\.name\(\s*(\w+)", text) + re.findall(r"new\s+NewTopic\(\s*(\w+)\s*,", text):
                if arg in fields:
                    topic = resolve(fields[arg], p)
                    if topic not in g.get("provision", set()):
                        problems.append(f"{module} {f}: declares topic {topic} but kafka-acls.txt has no '{module} provision {topic}'")
    # Sends with a constant topic, a topic constant of the module, or a local "String topic = ...".
    for dirpath, _, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".java"):
                continue
            text = open(os.path.join(dirpath, f), encoding="utf-8", errors="replace").read()
            if "KafkaTemplate" not in text and "KafkaOperations" not in text:
                continue
            local = dict(re.findall(r"String\s+(\w+)\s*=\s*(?:com\.dlap\.common\.constants\.)?DlapConstants\.KafkaTopic\.([A-Z_]+)\s*;", text))
            for arg in re.findall(r"\b\w*[tT]emplate\s*\.send\(\s*([\w.]+)", text) + re.findall(r"\bkafka\s*\.send\(\s*([\w.]+)", text):
                topic = None
                m = re.fullmatch(r"(?:com\.dlap\.common\.constants\.)?DlapConstants\.KafkaTopic\.([A-Z_]+)", arg)
                if m:
                    topic = TOPIC[m.group(1)]
                elif arg in local:
                    topic = TOPIC[local[arg]]
                elif any(k[1] == arg for k in constants):
                    topic = next(v for k, v in constants.items() if k[1] == arg)
                if topic is None:
                    continue  # a topic chosen at run time; see the KNOWN list below
                if topic not in g.get("produce", set()):
                    problems.append(f"{module} {f}: sends to {topic} but kafka-acls.txt has no '{module} produce {topic}'")

# Topics chosen at run time, resolved by hand from the properties they read.
KNOWN = {
    "da-catalog": {"dlap.asset.label.assignment.changed"},             # AssetService labelAssignmentChangedTopic
    "da-policyengine": {"dlap.policies.actions"},                        # PolicyActionProducer policyActionEventTopic
    "da-discovery": {"discovery-resource-events", "discovery-log-events", "discovery-events",
                     "dlap.discovery.asset.discovered", "dlap.discovery.asset.modified", "dlap.discovery.asset.deleted",
                     "dlap.discovery.asset.accessed", "dlap.discovery.asset.permission-changed",
                     "dlap.discovery.asset.archived"},                   # crawlers, analyzers, DiscoveryLogEventProcessor
}
for module, topics in KNOWN.items():
    for topic in topics:
        if topic not in grants.get(module, {}).get("produce", set()):
            problems.append(f"{module}: produces {topic} (run-time topic) but kafka-acls.txt has no grant")

# Who may write the two topics a forged record does the most harm on (threat model B5-T1, B5-T2).
writers = {t: sorted(m for m, g in grants.items() if t in g.get("produce", set()))
           for t in ("dlap.policies.actions", "dlap.log.event.normalized")}
if writers["dlap.policies.actions"] != ["da-autocompliance", "da-policyengine"]:
    problems.append(f"dlap.policies.actions writers are {writers['dlap.policies.actions']}")
if writers["dlap.log.event.normalized"] != ["da-discovery", "da-logingest"]:
    problems.append(f"dlap.log.event.normalized writers are {writers['dlap.log.event.normalized']}")
unknown = sorted(set(grants) - set(MODULES) - {"dlap-kafka-exporter"})
if unknown:
    problems.append(f"principals without a service: {unknown}")

print(f"listeners={listeners}")
for p in problems:
    print("problem=" + p)
PY
)"
n_listeners="$(sed -n 's/^listeners=//p' <<<"$t2")"
is "T2 the scan found the services' @KafkaListener methods" "[ '${n_listeners:-0}' -ge 20 ]" "$n_listeners"
probs="$(sed -n 's/^problem=//p' <<<"$t2")"
is "T2 kafka-acls.txt grants every topic, group and provisioning the code uses, and no more writers" "[ -z \"\$probs\" ]" "$(echo "$probs" | head -n 5 | tr '\n' ';')"

# --- T3 kafka-init.sh dry run -----------------------------------------------------------------

D="$WORK/kinit"; mkdir -p "$D/scram"
for p in da-catalog da-policyengine da-reporting dlap-kafka-exporter; do printf '%048d\n' 0 | tr 0 a >"$D/scram/$p.password"; done
dry="$(DLAP_KAFKA_INIT_DRY_RUN=1 DLAP_KAFKA_INIT_TLS="$D" DLAP_KAFKA_INIT_ACLS="$SCRIPT_DIR/kafka-acls.txt" bash "$SCRIPT_DIR/kafka-init.sh" 2>&1)"
is "T3 kafka-init dry run succeeds" "grep -q 'ACL lines applied' <<<\"\$dry\""
is "T3 SCRAM users are created with SCRAM-SHA-512 and the password is not printed" "grep -q 'entity-name da-catalog --add-config SCRAM-SHA-512=\[iterations=8192,password=\*\*\*\]' <<<\"\$dry\" && ! grep -q 'aaaaaaaa' <<<\"\$dry\""
is "T3 da-policyengine may write dlap.policies.actions" "grep -E -- '--allow-principal User:da-policyengine --operation Write --operation Describe --operation Create .*--topic dlap.policies.actions( |$)' <<<\"\$dry\" >/dev/null"
is "T3 a consumer may write the dead-letter topic of what it reads" "grep -E -- '--allow-principal User:da-autodelete --operation Write --operation Describe --operation Create --topic dlap.policies.actions.DLT' <<<\"\$dry\" >/dev/null"

# WIRE-01 (Q-602): the two action executors, by name. T2 derives topics and groups from the code; these pin the
# grants the executors need for the approval flow, so dropping one from the file fails here even if the code moved.
is "T3 da-autoarchival reads policy actions, approval decisions and the normalized log (archive proof)" \
  "grep -E -- '--allow-principal User:da-autoarchival --operation Read --operation Describe --operation Create --topic dlap.policies.actions --topic dlap.approvals.decided --topic dlap.log.event.normalized\$' <<<\"\$dry\" >/dev/null"
is "T3 da-autoarchival may write the dead-letter topics of all three" \
  "grep -E -- '--allow-principal User:da-autoarchival --operation Write .*--topic dlap.policies.actions.DLT .*--topic dlap.approvals.decided.DLT .*--topic dlap.log.event.normalized.DLT\$' <<<\"\$dry\" >/dev/null"
is "T3 da-autoarchival-consumer-group, -approvals and -proof fall under one prefixed group grant" \
  "grep -E -- '--allow-principal User:da-autoarchival --operation Read --group da-autoarchival --resource-pattern-type prefixed' <<<\"\$dry\" >/dev/null"
is "T3 da-autodelete reads policy actions and the provider delete events (delete proof)" \
  "grep -E -- '--allow-principal User:da-autodelete --operation Read --operation Describe --operation Create --topic dlap.policies.actions --topic dlap.discovery.asset.deleted\$' <<<\"\$dry\" >/dev/null"
is "T3 da-autodelete-policy-actions and -deletion-proof fall under one prefixed group grant" \
  "grep -E -- '--allow-principal User:da-autodelete --operation Read --group da-autodelete --resource-pattern-type prefixed' <<<\"\$dry\" >/dev/null"
is "T3 neither executor can write a topic that is not a dead-letter topic" \
  "! grep -E -- '--allow-principal User:da-auto(archival|delete) --operation Write' <<<\"\$dry\" | grep -E -- '--topic [^ ]+' -o | grep -v '\\.DLT\$' | grep -q ."
is "T3 da-catalog has no Write on dlap.policies.actions" "! grep -E -- 'User:da-catalog --operation Write .*--topic dlap.policies.actions( |$)' <<<\"\$dry\" >/dev/null"
is "T3 groups are prefixed patterns" "grep -E -- 'User:da-reporting --operation Read --group da-reporting --resource-pattern-type prefixed' <<<\"\$dry\" >/dev/null"
printf '%s\n' 'da-catalog fly dlap.x' >"$WORK/bad-verb.txt"
is "T3 an unknown verb is refused (exit 2)" "DLAP_KAFKA_INIT_DRY_RUN=1 DLAP_KAFKA_INIT_TLS='$D' DLAP_KAFKA_INIT_ACLS='$WORK/bad-verb.txt' bash '$SCRIPT_DIR/kafka-init.sh' >/dev/null 2>&1; [ \$? = 2 ]"
printf 'x\n' >"$D/scram/da-reporting.password"
is "T3 a password that is not gen-certs.sh's is refused (exit 2)" "DLAP_KAFKA_INIT_DRY_RUN=1 DLAP_KAFKA_INIT_TLS='$D' DLAP_KAFKA_INIT_ACLS='$SCRIPT_DIR/kafka-acls.txt' bash '$SCRIPT_DIR/kafka-init.sh' >/dev/null 2>&1; [ \$? = 2 ]"

# --- T4 the merged model ------------------------------------------------------------------------

if command -v docker >/dev/null 2>&1; then
  st="$(bash "$SCRIPT_DIR/smoke-tls.sh" --static --env-file "$PILOT_DIR/.env.pilot.example" 2>&1)"; rc=$?
  is "T4 smoke-tls.sh --static passes" "[ $rc = 0 ]" "$(grep '^FAIL' <<<"$st" | head -n 3 | tr '\n' ';')"
  vf="$(bash "$PILOT_DIR/verify.sh" 2>&1)"; rc=$?
  is "T4 verify.sh (DEP-01) passes with the TLS overlay" "[ $rc = 0 ]" "$(tail -n 3 <<<"$vf" | tr '\n' ';')"
else
  skip "T4 merged model" "docker is not installed"
fi

# --- T5 containers ------------------------------------------------------------------------------

if [ "$QUICK" = 1 ] || ! docker info >/dev/null 2>&1; then
  skip "T5 container tests" "--quick or no Docker"
else
  vol="sec06-tls-test-$$"
  dk volume create "$vol" >/dev/null
  init_out="$(dk run --rm --network none -v "$vol:/tls" --mount "type=bind,source=$(native "$G"),target=/src,readonly" \
    --mount "type=bind,source=$(native "$SCRIPT_DIR/tls-init.sh"),target=/opt/tls-init.sh,readonly" \
    busybox:1.36.1-musl sh /opt/tls-init.sh 2>&1)"
  is "T5 tls-init.sh copies the material" "grep -q 'key material in place (11 SCRAM users)' <<<\"\$init_out\"" "$init_out"
  modes="$(dk run --rm -v "$vol:/tls:ro" busybox:1.36.1-musl sh -c 'cd /tls && stat -c "%n %u:%g %a" * scram/*' 2>&1)"
  mode_of() { awk -v f="$1" '$1 == f { print $2 " " $3 }' <<<"$modes"; }
  is "T5 the CA and certificates are public (0444)" "[ '$(mode_of ca.crt)' = '0:0 444' ] && [ '$(mode_of da-catalog.crt)' = '0:0 444' ]"
  is "T5 da-catalog's key is readable by its group only (61102, 0440)" "[ '$(mode_of da-catalog.key)' = '0:61102 440' ]" "$(mode_of da-catalog.key)"
  is "T5 da-logingest's key is readable by its group only (61110, 0440)" "[ '$(mode_of da-logingest.key)' = '0:61110 440' ]" "$(mode_of da-logingest.key)"
  is "T5 the postgres key belongs to uid 999 with 0600" "[ '$(mode_of postgresql.key)' = '999:999 600' ]" "$(mode_of postgresql.key)"
  is "T5 the broker keystore is the broker's (61010, 0440)" "[ '$(mode_of kafka.keystore.p12)' = '0:61010 440' ]"
  is "T5 SCRAM passwords are kafka-init's (61011, 0440)" "[ '$(mode_of scram/da-catalog.password)' = '0:61011 440' ]"
  is "T5 the exporter password is the exporter's (61012, 0440)" "[ '$(mode_of kafka-exporter.password)' = '0:61012 440' ]"
  is "T5 a Spring service user (65532) with another service's group cannot read da-catalog's key" \
    "! dk run --rm --user 65532:65532 --group-add 61101 -v '$vol:/tls:ro' busybox:1.36.1-musl cat /tls/da-catalog.key >/dev/null 2>&1"
  is "T5 the same user with da-catalog's group reads it" \
    "dk run --rm --user 65532:65532 --group-add 61102 -v '$vol:/tls:ro' busybox:1.36.1-musl cat /tls/da-catalog.key >/dev/null 2>&1"
  empty="$WORK/empty"; mkdir -p "$empty"
  is "T5 tls-init.sh fails without key material" \
    "! dk run --rm --network none -v '$vol:/tls' --mount 'type=bind,source=$(native "$empty"),target=/src,readonly' --mount 'type=bind,source=$(native "$SCRIPT_DIR/tls-init.sh"),target=/opt/tls-init.sh,readonly' busybox:1.36.1-musl sh /opt/tls-init.sh >/dev/null 2>&1"

  # PostgreSQL with the overlay's TLS settings and pg_hba.conf.
  pg="sec06-tls-test-pg-$$"
  dk run -d --name "$pg" -e POSTGRES_PASSWORD=tls-test-only -v "$vol:/tls:ro" \
    --mount "type=bind,source=$(native "$SCRIPT_DIR/pg_hba.conf"),target=/etc/dlap-tls/pg_hba.conf,readonly" \
    postgres:15.1 postgres -c ssl=on -c ssl_cert_file=/tls/postgresql.crt -c ssl_key_file=/tls/postgresql.key \
    -c ssl_min_protocol_version=TLSv1.2 -c hba_file=/etc/dlap-tls/pg_hba.conf >/dev/null
  for _ in $(seq 1 60); do dk exec "$pg" pg_isready -q -h 127.0.0.1 >/dev/null 2>&1 && break; sleep 1; done
  q() { dk exec -e PGPASSWORD=tls-test-only "$pg" psql -X -A -t "$1" -c "$2" 2>&1; }
  is "T5 postgres accepts verify-full over TLS" "[ \"\$(q 'host=localhost user=postgres dbname=postgres sslmode=verify-full sslrootcert=/tls/ca.crt' 'select ssl from pg_stat_ssl where pid = pg_backend_pid()')\" = t ]"
  is "T5 postgres refuses TCP without TLS" "grep -q 'no encryption' <<<\"\$(q 'host=localhost user=postgres dbname=postgres sslmode=disable' 'select 1')\""
  is "T5 postgres keeps the socket for the init scripts and up.sh" "[ \"\$(dk exec '$pg' psql -X -A -t -U postgres -c 'select 1' 2>&1)\" = 1 ]"
  is "T5 postgres refuses a TLS 1.1 client" "grep -qiE 'protocol version|unsupported protocol|alert' <<<\"\$(q 'host=localhost user=postgres dbname=postgres sslmode=require ssl_min_protocol_version=TLSv1 ssl_max_protocol_version=TLSv1.1' 'select 1')\""
  dk rm -f "$pg" >/dev/null 2>&1

  # nginx accepts the gateway configuration (upstream names resolved to loopback for the test).
  hosts=()
  for h in keycloak new-ui da-catalog da-policyengine da-reporting da-discovery da-autolabel da-autocompliance da-admin-service; do hosts+=(--add-host "$h:127.0.0.1"); done
  ng="$(dk run --rm "${hosts[@]}" -v "$vol:/tls:ro" --group-add 61020 \
    --mount "type=bind,source=$(native "$SCRIPT_DIR/nginx.pilot.tls.conf"),target=/etc/nginx/nginx.conf,readonly" \
    nginx:1.27-alpine nginx -t 2>&1)"
  is "T5 nginx -t accepts tls/nginx.pilot.tls.conf" "grep -q 'test is successful' <<<\"\$ng\"" "$(tail -n 2 <<<"$ng" | tr '\n' ' ')"
  dk volume rm "$vol" >/dev/null 2>&1
fi

echo
echo "test-tls.sh: $PASSED passed, $FAILED failed"
[ "$FAILED" = 0 ]
