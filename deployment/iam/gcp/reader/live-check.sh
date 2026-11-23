#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# Live check of the GCP Reader role (IAM-03). Emulators do not enforce IAM, so this runs against
# a real project after "terraform apply". It changes nothing: every call is a read.
#
#   1. The dlapReader role in the project holds exactly the project-scope permissions of
#      permissions.yaml (gcloud iam roles describe).
#   2. Acting as the Reader account, projects.testIamPermissions on the project grants every
#      project-scope permission and none of the write or content probes below. This tests the
#      account's effective permissions, so a broad role granted elsewhere (Editor, Owner,
#      a predefined BigQuery role) also fails the check.
#
# Needs: gcloud signed in as a user who may impersonate the account
# (roles/iam.serviceAccountTokenCreator on it), and curl.
#
# Usage:  live-check.sh <project-id> <reader-service-account-email>
#         live-check.sh --self-test   offline: runs both checks against stubbed gcloud and curl
# Exit:   0 = pass; 1 = a check failed; 2 = usage, tool or self-test error.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRACE="$HERE/permissions.yaml"

# scope_permissions <scope>: the permissions of permissions.yaml whose scopes list holds <scope>.
scope_permissions() {
  awk -v want="$1" '
    /^  - permission: / { perm = $3; next }
    /^    scopes: / {
      line = $0; sub(/^    scopes: /, "", line); gsub(/[][ ]/, "", line)
      n = split(line, parts, ",")
      for (i = 1; i <= n; i++) if (parts[i] == want && perm != "") print perm
      perm = ""
    }' "$TRACE" | sort -u
}

# Write and content permissions the Reader must not hold on a monitored project.
PROBES="bigquery.datasets.create
bigquery.datasets.delete
bigquery.datasets.update
bigquery.tables.create
bigquery.tables.delete
bigquery.tables.getData
bigquery.tables.update
bigquery.tables.updateData
cloudsql.instances.update
iam.serviceAccountKeys.create
logging.logEntries.create
logging.sinks.create
resourcemanager.projects.setIamPolicy
storage.buckets.create
storage.buckets.delete
storage.buckets.setIamPolicy
storage.buckets.update
storage.objects.create
storage.objects.delete
storage.objects.get
storage.objects.update"

# The external tools, replaced by stubs in --self-test.
run_gcloud() { gcloud "$@"; }
run_curl() { curl "$@"; }

# check <project> <reader>: runs both checks, prints the result, returns 0 or 1.
check() {
  local project="$1" reader="$2" fail=0 want have token asked body granted missing extra
  want="$(scope_permissions project)"

  echo "1. dlapReader in $project holds exactly the project-scope permissions"
  have="$(run_gcloud iam roles describe dlapReader --project "$project" --format='value(includedPermissions)' \
    | tr ';,' '\n\n' | sed '/^$/d' | sort -u)"
  if [ "$have" = "$want" ]; then
    echo "   pass: $(echo "$want" | wc -l | tr -d ' ') permissions match"
  else
    echo "   FAIL: role and permissions.yaml differ (< role, > permissions.yaml):"
    diff <(echo "$have") <(echo "$want") | sed 's/^/   /' || true
    fail=1
  fi

  echo "2. effective permissions of $reader on $project"
  token="$(run_gcloud auth print-access-token --impersonate-service-account="$reader")"
  asked="$( { echo "$want"; echo "$PROBES"; } | sort -u)"
  body="{\"permissions\":[$(echo "$asked" | sed 's/.*/"&"/' | paste -sd, -)]}"
  granted="$(run_curl -sS -X POST \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    "https://cloudresourcemanager.googleapis.com/v3/projects/$project:testIamPermissions" \
    -d "$body" | grep -oE '"[a-zA-Z]+\.[a-zA-Z]+\.[a-zA-Z]+"' | tr -d '"' | sort -u || true)"
  missing="$(comm -23 <(echo "$want") <(echo "$granted"))"
  extra="$(comm -12 <(echo "$PROBES" | sort -u) <(echo "$granted"))"
  if [ -z "$missing" ]; then
    echo "   pass: every project-scope permission is granted"
  else
    echo "   FAIL: not granted: $(echo "$missing" | paste -sd' ' -)"
    fail=1
  fi
  if [ -z "$extra" ]; then
    echo "   pass: no write or content probe is granted"
  else
    echo "   FAIL: the account holds write or content permissions: $(echo "$extra" | paste -sd' ' -)"
    fail=1
  fi
  return "$fail"
}

# self_test: the parser reads every scope, and check passes on an exact grant and fails on a
# missing permission, an extra role permission and a granted write probe.
self_test() {
  local errors=0 n_project n_org n_bucket n_dataset
  n_project="$(scope_permissions project | wc -l | tr -d ' ')"
  n_org="$(scope_permissions organization | wc -l | tr -d ' ')"
  n_bucket="$(scope_permissions log_sink_bucket | wc -l | tr -d ' ')"
  n_dataset="$(scope_permissions log_sink_dataset | wc -l | tr -d ' ')"
  echo "parsed: project=$n_project organization=$n_org log_sink_bucket=$n_bucket log_sink_dataset=$n_dataset"
  [ "$n_project" -gt 0 ] && [ "$n_org" -gt 0 ] && [ "$n_bucket" -gt 0 ] && [ "$n_dataset" -gt 0 ]     || { echo "self-test: parser found an empty scope"; errors=1; }
  scope_permissions log_sink_bucket | grep -qx 'storage.objects.get' || { echo "self-test: storage.objects.get missing from log_sink_bucket"; errors=1; }
  scope_permissions log_sink_dataset | grep -qx 'bigquery.tables.getData' || { echo "self-test: bigquery.tables.getData missing from log_sink_dataset"; errors=1; }
  scope_permissions project | grep -qx 'storage.objects.get' && { echo "self-test: storage.objects.get leaked into project"; errors=1; }
  scope_permissions project | grep -qx 'bigquery.tables.getData' && { echo "self-test: bigquery.tables.getData leaked into project"; errors=1; }

  ROLE_PERMS="$(scope_permissions project | paste -sd';' -)"
  GRANTED="$(scope_permissions project)"
  run_gcloud() {
    case "$1 $2" in
      "iam roles") echo "$ROLE_PERMS" ;;
      "auth print-access-token") echo "stub-token" ;;
      *) echo "unexpected gcloud $*" >&2; return 2 ;;
    esac
  }
  run_curl() { echo "{\"permissions\":[$(echo "$GRANTED" | sed '/^$/d; s/.*/"&"/' | paste -sd, -)]}"; }

  expect() { # expect <0|1> <case name>
    if check stub-project stub@example.iam.gserviceaccount.com >/dev/null; then got=0; else got=1; fi
    if [ "$got" = "$1" ]; then echo "ok   $2"; else echo "FAIL $2 (exit $got, want $1)"; errors=1; fi
  }
  expect 0 "exact grant passes"
  GRANTED="$(scope_permissions project | sed 1d)"; expect 1 "missing permission fails"
  GRANTED="$(scope_permissions project; echo storage.objects.delete)"; expect 1 "granted write probe fails"
  GRANTED="$(scope_permissions project)"; ROLE_PERMS="$ROLE_PERMS;storage.buckets.create"; expect 1 "extra role permission fails"
  [ "$errors" = 0 ] && { echo "self-test passed"; return 0; }
  echo "self-test failed"
  return 2
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi
if [ "$#" -ne 2 ]; then
  echo "usage: $0 <project-id> <reader-service-account-email> | --self-test" >&2
  exit 2
fi
for tool in gcloud curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing tool: $tool" >&2; exit 2; }
done
check "$1" "$2"
