#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# Live check of the AWS Reader role (IAM-03). Emulators do not enforce IAM, so this runs against
# a real account after the stack or "terraform apply". It changes nothing: it uses the IAM policy
# simulator (iam:SimulatePrincipalPolicy), which evaluates policies without calling the services.
#
#   1. Every account-scope action of permissions.yaml is allowed for the role, and, for each log
#      bucket named on the command line, every log_bucket action on the bucket and every
#      log_bucket_objects action on an object of it.
#   2. None of the write and content probes below is allowed, and no object read or listing is
#      allowed on a bucket that is not a named log bucket.
# The simulator evaluates every policy attached to the role, so a broad policy attached next to
# the Reader policy also fails check 2. Service control policies are not simulated.
#
# Needs: the AWS CLI signed in as a principal with iam:SimulatePrincipalPolicy.
#
# Usage:  live-check.sh <reader-role-arn> [log-bucket ...]
#           log-bucket: the cloudtrail_log_bucket and s3_access_log_bucket the role was applied with
#         live-check.sh --self-test   offline: runs both checks against a stubbed aws CLI
# Exit:   0 = pass; 1 = a check failed; 2 = usage, tool or self-test error.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRACE="$HERE/permissions.yaml"

# reader_actions: the actions of permissions.yaml.
reader_actions() { awk '/^  - permission: / { print $3 }' "$TRACE" | sort -u; }

# scope_actions <scope>: the actions of permissions.yaml granted on that scope.
scope_actions() {
  awk -v want="$1" '
    /^  - permission: / { name = $3; next }
    /^    scopes: / { line = $0; gsub(/.*\[|\].*/, "", line); n = split(line, s, /, */)
                      for (i = 1; i <= n; i++) if (s[i] == want) print name }
  ' "$TRACE" | sort -u
}

# A bucket name that is never a log bucket: object reads and listings on it must be denied.
NOT_A_LOG_BUCKET="dlap-live-check-not-a-log-bucket"

# Write and content actions the Reader must not be allowed on any resource.
PROBES="athena:GetQueryResults
athena:StartQueryExecution
cloudtrail:DeleteTrail
cloudtrail:PutEventSelectors
cloudtrail:StopLogging
ec2:TerminateInstances
glue:DeleteTable
glue:UpdateTable
iam:PassRole
rds:DeleteDBInstance
s3:DeleteBucket
s3:DeleteObject
s3:PutBucketPolicy
s3:PutLifecycleConfiguration
s3:PutObject"

# The external tool, replaced by a stub in --self-test.
run_aws() { aws "$@"; }

# simulate <role> <actions...>: "action decision" per line, on every resource.
simulate() {
  local role="$1"; shift
  run_aws iam simulate-principal-policy --policy-source-arn "$role" --action-names "$@" \
    --query 'EvaluationResults[].[EvalActionName,EvalDecision]' --output text
}

# simulate_on <role> <resource-arn> <actions...>: "action decision" per line, on that resource.
simulate_on() {
  local role="$1" resource="$2"; shift 2
  run_aws iam simulate-principal-policy --policy-source-arn "$role" --action-names "$@" \
    --resource-arns "$resource" \
    --query 'EvaluationResults[].[EvalActionName,EvalDecision]' --output text
}

# check <role-arn> [log-bucket ...]: runs both checks, prints the result, returns 0 or 1.
check() {
  local role="$1" fail=0 want denied allowed partition bucket n
  shift
  partition="$(echo "$role" | cut -d: -f2)"
  want="$(scope_actions account)"
  echo "1. every Reader action is allowed for $role"
  # shellcheck disable=SC2046,SC2086
  denied="$(
    simulate "$role" $want | awk '$2 != "allowed" { print $1 }'
    for bucket in "$@"; do
      simulate_on "$role" "arn:$partition:s3:::$bucket" $(scope_actions log_bucket) \
        | awk -v b="$bucket" '$2 != "allowed" { print $1 "@" b }'
      simulate_on "$role" "arn:$partition:s3:::$bucket/dlap-live-check-probe" $(scope_actions log_bucket_objects) \
        | awk -v b="$bucket" '$2 != "allowed" { print $1 "@" b "/*" }'
    done
  )"
  n="$(echo "$want" | wc -l | tr -d ' ')"
  for bucket in "$@"; do
    n=$((n + $(scope_actions log_bucket | wc -l) + $(scope_actions log_bucket_objects | wc -l)))
  done
  if [ "$#" -eq 0 ]; then
    echo "   note: no log bucket named; the log bucket actions are not checked"
  fi
  if [ -z "$denied" ]; then
    echo "   pass: $n actions allowed"
  else
    echo "   FAIL: not allowed: $(echo "$denied" | paste -sd' ' -)"
    fail=1
  fi
  echo "2. no write or content probe is allowed"
  # shellcheck disable=SC2086
  allowed="$(
    simulate "$role" $PROBES | awk '$2 == "allowed" { print $1 }'
    simulate_on "$role" "arn:$partition:s3:::$NOT_A_LOG_BUCKET" s3:ListBucket \
      | awk '$2 == "allowed" { print $1 "@other-bucket" }'
    simulate_on "$role" "arn:$partition:s3:::$NOT_A_LOG_BUCKET/dlap-live-check-probe" s3:GetObject \
      | awk '$2 == "allowed" { print $1 "@other-bucket/*" }'
  )"
  if [ -z "$allowed" ]; then
    echo "   pass: every probe is denied"
  else
    echo "   FAIL: the role allows: $(echo "$allowed" | paste -sd' ' -)"
    fail=1
  fi
  return "$fail"
}

# self_test: the parser reads the actions, and check passes when exactly the Reader actions are
# allowed (the log bucket actions on the named log bucket only) and fails on a denied Reader
# action, a denied log bucket read, an allowed probe and an object read on another bucket.
self_test() {
  local errors=0 n log
  n="$(reader_actions | wc -l | tr -d ' ')"
  echo "parsed: $n actions"
  [ "$n" -gt 0 ] || { echo "self-test: no action parsed"; errors=1; }
  [ "$(scope_actions log_bucket_objects)" = "s3:GetObject" ] \
    || { echo "self-test: s3:GetObject is not the only log_bucket_objects action"; errors=1; }
  [ -z "$(scope_actions account | grep -x 's3:GetObject')" ] \
    || { echo "self-test: s3:GetObject is in the account scope"; errors=1; }
  # ALLOWED: "action" lines are allowed on every resource, "action resource" lines on that one.
  log="trail-logs"
  exact() {
    scope_actions account
    scope_actions log_bucket | sed "s|\$| arn:aws:s3:::$log|"
    scope_actions log_bucket_objects | sed "s|\$| arn:aws:s3:::$log/dlap-live-check-probe|"
  }
  ALLOWED="$(exact)"
  run_aws() {
    local a printing=0 resource="" actions="" next=""
    for a in "$@"; do
      if [ "$next" = resource ]; then resource="$a"; next=""; continue; fi
      case "$a" in
        --action-names) printing=1; continue ;;
        --resource-arns) printing=0; next=resource; continue ;;
        --*) printing=0; continue ;;
      esac
      [ "$printing" = 1 ] && actions="$actions $a"
    done
    for a in $actions; do
      if echo "$ALLOWED" | grep -qx "$a" || { [ -n "$resource" ] && echo "$ALLOWED" | grep -qx "$a $resource"; }; then
        printf '%s\tallowed\n' "$a"
      else
        printf '%s\timplicitDeny\n' "$a"
      fi
    done
  }
  expect() { # expect <0|1> <case name>
    if check arn:aws:iam::000000000000:role/stub "$log" >/dev/null; then got=0; else got=1; fi
    if [ "$got" = "$1" ]; then echo "ok   $2"; else echo "FAIL $2 (exit $got, want $1)"; errors=1; fi
  }
  expect 0 "exact allow passes"
  ALLOWED="$(exact | sed 1d)"; expect 1 "denied Reader action fails"
  ALLOWED="$(exact | grep -v '^s3:GetObject ')"; expect 1 "denied log bucket read fails"
  ALLOWED="$(exact; echo athena:GetQueryResults)"; expect 1 "allowed content probe fails"
  ALLOWED="$(exact; echo s3:GetObject)"; expect 1 "object read on every bucket fails"
  ALLOWED="$(exact; echo "s3:GetObject arn:aws:s3:::$NOT_A_LOG_BUCKET/dlap-live-check-probe")"
  expect 1 "object read on a bucket that is not a log bucket fails"
  [ "$errors" = 0 ] && { echo "self-test passed"; return 0; }
  echo "self-test failed"
  return 2
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi
if [ "$#" -lt 1 ]; then
  echo "usage: $0 <reader-role-arn> [log-bucket ...] | --self-test" >&2
  exit 2
fi
command -v aws >/dev/null 2>&1 || { echo "missing tool: aws" >&2; exit 2; }
check "$@"
