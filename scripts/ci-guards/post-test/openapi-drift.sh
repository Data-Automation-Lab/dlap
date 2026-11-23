#!/usr/bin/env bash
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
# Guard (API-01): the committed OpenAPI contracts, docs/api/openapi/<service>.json, are what the
# controllers produce now.
#
# Each service has a test, OpenApiSpecExportTest, that builds its OpenAPI document from the real
# controllers (no plugin, no running service) and compares it with the committed file. This hook
# is the project-wide gate around those tests:
#
#   1. Every da-* module with a controller has an OpenApiSpecExportTest, and every test has a
#      committed contract that is a JSON OpenAPI 3 document. A contract without a test is an orphan.
#   2. The support code between "BEGIN SHARED OPENAPI SUPPORT" and "END SHARED OPENAPI SUPPORT" is
#      byte-identical in every OpenApiSpecExportTest, so the copies cannot drift apart.
#   3. Every service is regenerated (DLAP_OPENAPI_UPDATE=1 through Gradle) and the working tree must
#      not change: any difference or new file under docs/api/openapi is an uncommitted API change,
#      and the hook fails. Locally this rewrites the files, so review and commit them.
#   4. Warning only, never a failure: each contract is compared with the one at DLAP_CI_BASE_SHA and
#      likely breaking changes are listed (path or operation removed, new required parameter or
#      request property, response status, property or type removed or changed). Same idea as the
#      openapi-diff tool, without a Java download; it is an aid to the reviewer, not a verdict.
#
# Usage:
#   bash scripts/ci-guards/post-test/openapi-drift.sh              the CI hook (runs Gradle, needs JDK 21)
#   DLAP_OPENAPI_SKIP_GRADLE=1 bash ...                            checks 1, 2 and 4 only (seconds)
#   bash scripts/ci-guards/post-test/openapi-drift.sh --self-test  prove the hook itself works
#
# Environment: DLAP_CI_ROOT, DLAP_CI_BASE_SHA (CI hook contract); DLAP_OPENAPI_SKIP_GRADLE=1;
# DLAP_OPENAPI_GRADLE_ARGS replaces the default Gradle arguments; DLAP_OPENAPI_REGEN_CMD replaces
# the whole regenerate step (the self-test uses it; do not set it in CI).
#
# Exit 0 pass, 1 violation, 2 usage error, 77 skip (no export test exists in this checkout).
set -uo pipefail

SHARED_BEGIN='BEGIN SHARED OPENAPI SUPPORT'
SHARED_END='END SHARED OPENAPI SUPPORT'

# The Python helper: JSON validation and the breaking-change comparison. Embedded so the hook is one file.
read -r -d '' PYCODE <<'PY'
import json
import sys

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def validate(path):
    try:
        doc = load(path)
    except (OSError, ValueError) as error:
        print("not valid JSON: %s" % error)
        return 1
    if not isinstance(doc, dict) or not str(doc.get("openapi", "")).startswith("3."):
        print("not an OpenAPI 3 document (no openapi: 3.x)")
        return 1
    if not isinstance(doc.get("info"), dict) or not isinstance(doc.get("paths"), dict):
        print("info or paths is missing")
        return 1
    return 0


def deref(doc, node):
    seen = set()
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        if not isinstance(ref, str) or not ref.startswith("#/") or ref in seen:
            return {}
        seen.add(ref)
        current = doc
        for part in ref[2:].split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            current = current.get(part, {}) if isinstance(current, dict) else {}
        node = current
    return node if isinstance(node, dict) else {}


def schema_changes(old_doc, old, new_doc, new, side, where, out, seen):
    pair = (str(old.get("$ref")) if isinstance(old, dict) else "", str(new.get("$ref")) if isinstance(new, dict) else "")
    if pair[0] and pair[1]:
        if pair in seen:
            return
        seen = seen | {pair}
    old = deref(old_doc, old)
    new = deref(new_doc, new)
    for key in ("type", "format"):
        if old.get(key) and new.get(key) and old[key] != new[key]:
            out.append("%s: %s changed from %s to %s" % (where, key, old[key], new[key]))
    if isinstance(old.get("enum"), list) and isinstance(new.get("enum"), list):
        removed = [v for v in old["enum"] if v not in new["enum"]]
        added = [v for v in new["enum"] if v not in old["enum"]]
        if side == "request" and removed:
            out.append("%s: enum values no longer accepted: %s" % (where, removed))
        if side == "response" and added:
            out.append("%s: enum values added to a response: %s" % (where, added))
    old_props = old.get("properties") if isinstance(old.get("properties"), dict) else {}
    new_props = new.get("properties") if isinstance(new.get("properties"), dict) else {}
    old_required = old.get("required") if isinstance(old.get("required"), list) else []
    new_required = new.get("required") if isinstance(new.get("required"), list) else []
    for name, schema in old_props.items():
        if name not in new_props:
            if side == "response":
                out.append("%s: property '%s' removed from the response" % (where, name))
            continue
        schema_changes(old_doc, schema, new_doc, new_props[name], side, "%s.%s" % (where, name), out, seen)
        if side == "request" and name in new_required and name not in old_required:
            out.append("%s: property '%s' is now required" % (where, name))
    if side == "request":
        for name in new_props:
            if name not in old_props and name in new_required:
                out.append("%s: new required property '%s'" % (where, name))
    if isinstance(old.get("items"), dict) and isinstance(new.get("items"), dict):
        schema_changes(old_doc, old["items"], new_doc, new["items"], side, where + "[]", out, seen)


def operations(doc):
    found = {}
    for path, item in (doc.get("paths") or {}).items():
        if not isinstance(item, dict):
            continue
        for method in METHODS:
            if isinstance(item.get(method), dict):
                found["%s %s" % (method.upper(), path)] = (item, item[method])
    return found


def parameters(doc, item, operation):
    found = {}
    for source in (item.get("parameters") or [], operation.get("parameters") or []):
        for raw in source:
            parameter = deref(doc, raw)
            if parameter.get("name"):
                found[(parameter.get("in"), parameter["name"])] = parameter
    return found


def breaking(old_doc, new_doc):
    out = []
    old_ops = operations(old_doc)
    new_ops = operations(new_doc)
    for key, (old_item, old_op) in sorted(old_ops.items()):
        if key not in new_ops:
            out.append("%s: operation removed" % key)
            continue
        new_item, new_op = new_ops[key]
        old_params = parameters(old_doc, old_item, old_op)
        new_params = parameters(new_doc, new_item, new_op)
        for name, parameter in sorted(old_params.items()):
            if name not in new_params:
                out.append("%s: %s parameter '%s' removed" % (key, name[0], name[1]))
                continue
            changed = new_params[name]
            if changed.get("required") and not parameter.get("required"):
                out.append("%s: %s parameter '%s' is now required" % (key, name[0], name[1]))
            schema_changes(old_doc, parameter.get("schema") or {}, new_doc, changed.get("schema") or {},
                           "request", "%s: %s parameter '%s'" % (key, name[0], name[1]), out, frozenset())
        for name, parameter in sorted(new_params.items()):
            if name not in old_params and parameter.get("required"):
                out.append("%s: new required %s parameter '%s'" % (key, name[0], name[1]))
        old_body = deref(old_doc, old_op.get("requestBody") or {})
        new_body = deref(new_doc, new_op.get("requestBody") or {})
        if new_body.get("required") and not old_body.get("required"):
            out.append("%s: the request body is now required" % key)
        for media, content in (old_body.get("content") or {}).items():
            new_content = (new_body.get("content") or {}).get(media)
            if new_content is None:
                out.append("%s: request content type %s no longer accepted" % (key, media))
            else:
                schema_changes(old_doc, content.get("schema") or {}, new_doc, new_content.get("schema") or {},
                               "request", "%s: request body" % key, out, frozenset())
        old_responses = old_op.get("responses") or {}
        new_responses = new_op.get("responses") or {}
        for code, response in sorted(old_responses.items()):
            if not str(code).startswith("2"):
                continue
            if code not in new_responses:
                out.append("%s: response %s removed" % (key, code))
                continue
            old_response = deref(old_doc, response)
            new_response = deref(new_doc, new_responses[code])
            for media, content in (old_response.get("content") or {}).items():
                new_content = (new_response.get("content") or {}).get(media)
                if new_content is None:
                    out.append("%s: response %s content type %s removed" % (key, code, media))
                else:
                    schema_changes(old_doc, content.get("schema") or {}, new_doc, new_content.get("schema") or {},
                                   "response", "%s: response %s" % (key, code), out, frozenset())
    return out


def main(argv):
    if argv[0] == "validate":
        return validate(argv[1])
    if argv[0] == "breaking":
        for line in breaking(load(argv[1]), load(argv[2])):
            print(line)
        return 0
    return 2


sys.exit(main(sys.argv[1:]))
PY

check() {
  local root py failures=0 warnings=0 file module service key
  root="${DLAP_CI_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
  cd "$root" || exit 2
  py="$(command -v python3 || command -v python || true)"

  fail() { echo "openapi-drift: $*" >&2; failures=$((failures + 1)); }

  local -a tests=()
  local module_dir
  for module_dir in da-*/; do
    [ -d "$module_dir" ] || continue
    while IFS= read -r file; do
      tests+=( "$file" )
    done < <(find "${module_dir%/}" -path '*/src/test/*' -name OpenApiSpecExportTest.java -not -path '*/build/*' 2>/dev/null | sort)
  done
  if [ "${#tests[@]}" -eq 0 ]; then
    echo "openapi-drift: no OpenApiSpecExportTest in this checkout; skipping"
    exit 77
  fi
  if [ -z "$py" ]; then
    fail "python3 is needed (JSON validation and the breaking-change warnings) and was not found"
    exit 1
  fi

  # 1. A test per module with a controller, a valid committed contract per test, no orphan contracts.
  local -A key_of_module=() module_of_key=()
  for file in "${tests[@]}"; do
    module="${file%%/*}"
    key="$(sed -n 's/^[[:space:]]*private static final String SERVICE = "\([^"]*\)";.*/\1/p' "$file" | head -n 1)"
    if [ -z "$key" ]; then
      fail "$file declares no 'private static final String SERVICE = \"<key>\";'"
      continue
    fi
    key_of_module["$module"]="$key"
    module_of_key["$key"]="$module"
  done
  local dir
  for dir in da-*/; do
    module="${dir%/}"
    [ -d "$module/src/main" ] || continue
    if grep -rqE '@(Rest)?Controller\b' --include='*.java' "$module/src/main" 2>/dev/null \
        && [ -z "${key_of_module[$module]:-}" ]; then
      fail "$module has a controller but no OpenApiSpecExportTest; copy it from another service"
    fi
  done
  for key in "${!module_of_key[@]}"; do
    file="docs/api/openapi/$key.json"
    if [ ! -f "$file" ]; then
      fail "$file is missing (${module_of_key[$key]}); create it with: DLAP_OPENAPI_UPDATE=1 ./gradlew :${module_of_key[$key]}:test --tests '*OpenApiSpecExportTest*' --rerun"
    else
      local problem
      problem="$("$py" -c "$PYCODE" validate "$file" 2>&1)"
      [ -z "$problem" ] || fail "$file: $problem"
    fi
  done
  if [ -d docs/api/openapi ]; then
    for file in docs/api/openapi/*.json; do
      [ -e "$file" ] || continue
      key="$(basename "$file" .json)"
      [ -n "${module_of_key[$key]:-}" ] || fail "$file has no OpenApiSpecExportTest (orphan contract); delete it or add the test"
    done
  fi

  # 2. The shared support code is identical everywhere.
  local reference="" reference_file="" sum
  for file in "${tests[@]}"; do
    sum="$(sed -n "/$SHARED_BEGIN/,/$SHARED_END/p" "$file" | tr -d '\r' | cksum | cut -d' ' -f1,2)"
    if ! grep -q "$SHARED_END" "$file" || ! grep -q "$SHARED_BEGIN" "$file"; then
      fail "$file lacks the '$SHARED_BEGIN' / '$SHARED_END' markers"
      continue
    fi
    if [ -z "$reference" ]; then
      reference="$sum"; reference_file="$file"
    elif [ "$sum" != "$reference" ]; then
      fail "$file: the shared OpenAPI support code differs from $reference_file; edit it in every service at once"
    fi
  done

  # 3. Regenerate everything and require a clean tree under docs/api/openapi.
  if [ "${DLAP_OPENAPI_SKIP_GRADLE:-}" = "1" ]; then
    echo "openapi-drift: DLAP_OPENAPI_SKIP_GRADLE=1; contracts not regenerated"
  else
    local rc=0
    if [ -n "${DLAP_OPENAPI_REGEN_CMD:-}" ]; then
      bash -c "$DLAP_OPENAPI_REGEN_CMD"; rc=$?
    else
      local -a gradle_args=() tasks=()
      # shellcheck disable=SC2206
      gradle_args=( ${DLAP_OPENAPI_GRADLE_ARGS:---no-daemon --console=plain --no-watch-fs --max-workers=2} )
      for module in $(printf '%s\n' "${!key_of_module[@]}" | sort); do
        tasks+=( ":$module:test" "--tests" "*OpenApiSpecExportTest*" "--rerun" )
      done
      DLAP_OPENAPI_UPDATE=1 bash ./gradlew --continue "${gradle_args[@]}" "${tasks[@]}"; rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
      fail "regenerating the contracts failed (exit $rc); see the Gradle output above"
    elif git rev-parse --git-dir >/dev/null 2>&1; then
      local dirty
      dirty="$(git status --porcelain --untracked-files=all -- docs/api/openapi)"
      if [ -n "$dirty" ]; then
        fail "the OpenAPI contracts differ from the controllers; regenerate and commit them:"
        printf '%s\n' "$dirty" | sed 's/^/  /' >&2
        git --no-pager diff --stat -- docs/api/openapi 2>/dev/null | sed 's/^/  /' >&2
      else
        echo "openapi-drift: ${#module_of_key[@]} contract(s) regenerated; no difference from the committed files"
      fi
    else
      echo "openapi-drift: not a git checkout; skipping the uncommitted-change check"
    fi
  fi

  # 4. Likely breaking changes against the base commit (warnings only).
  local base="${DLAP_CI_BASE_SHA:-}"
  if [ -z "$base" ] || [ -z "${base//0/}" ] || ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo "openapi-drift: no usable base commit for event '${DLAP_CI_EVENT:-local}'; breaking-change warnings skipped"
  else
    local old tmp
    tmp="$(mktemp -d)"
    for file in docs/api/openapi/*.json; do
      [ -e "$file" ] || continue
      old="$tmp/$(basename "$file")"
      git show "$base:$file" >"$old" 2>/dev/null || continue
      local line
      while IFS= read -r line; do
        [ -n "$line" ] || continue
        warnings=$((warnings + 1))
        if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
          echo "::warning title=OpenAPI breaking change::$(basename "$file" .json): $line"
        else
          echo "openapi-drift: WARNING $(basename "$file" .json): $line" >&2
        fi
      done < <("$py" -c "$PYCODE" breaking "$old" "$file" 2>&1)
    done
    rm -rf "$tmp"
    echo "openapi-drift: compared with $(git rev-parse --short "$base"): $warnings possible breaking change(s)"
  fi

  if [ "$failures" -gt 0 ]; then
    echo "openapi-drift: $failures problem(s)" >&2
    exit 1
  fi
  echo "openapi-drift: ok"
  exit 0
}

self_test() {
  local script tmp repo hook base trunk passed=0 failed=0 out rc py
  script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  py="$(command -v python3 || command -v python || true)"
  [ -n "$py" ] || { echo "openapi-drift self-test: python3 not found" >&2; return 2; }
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT
  repo="$tmp/repo"
  mkdir -p "$repo/scripts/ci-guards/post-test"
  git -C "$repo" init -q
  git -C "$repo" config user.email selftest@example.invalid
  git -C "$repo" config user.name selftest
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config core.autocrlf false
  cp "$script" "$repo/scripts/ci-guards/post-test/openapi-drift.sh"
  hook="$repo/scripts/ci-guards/post-test/openapi-drift.sh"

  put() { mkdir -p "$(dirname "$repo/$1")"; printf '%s\n' "$2" >"$repo/$1"; }
  commit() { git -C "$repo" add -A && git -C "$repo" commit -q -m "$1"; }
  reset() { git -C "$repo" checkout -q "$trunk" && git -C "$repo" reset -q --hard "$base" && git -C "$repo" clean -q -fd; }
  expect() {
    # expect <description> <wanted exit code> <text the output must contain or empty> [env assignments...]
    local desc="$1" want="$2" text="$3"
    shift 3
    out="$(cd "$repo" && env -u DLAP_CI_ROOT -u GITHUB_ACTIONS DLAP_OPENAPI_SKIP_GRADLE=1 "$@" bash "$hook" 2>&1)"
    rc=$?
    if [ "$rc" -ne "$want" ]; then
      failed=$((failed + 1)); echo "  FAIL  $desc (exit $rc, wanted $want)" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
    elif [ -n "$text" ] && ! printf '%s\n' "$out" | grep -qF -- "$text"; then
      failed=$((failed + 1)); echo "  FAIL  $desc (output lacks '$text')" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
    else
      passed=$((passed + 1)); echo "  ok    $desc"
    fi
  }
  not_expect() {
    # not_expect <description> <text the output must NOT contain> [env assignments...]
    local desc="$1" text="$2"
    shift 2
    out="$(cd "$repo" && env -u DLAP_CI_ROOT -u GITHUB_ACTIONS DLAP_OPENAPI_SKIP_GRADLE=1 "$@" bash "$hook" 2>&1)"
    if printf '%s\n' "$out" | grep -qF -- "$text"; then
      failed=$((failed + 1)); echo "  FAIL  $desc (output has '$text')" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
    else
      passed=$((passed + 1)); echo "  ok    $desc"
    fi
  }

  local shared_a shared_b test_a test_b
  shared_a='    // BEGIN SHARED OPENAPI SUPPORT
    static final class OpenApiSupport { }
    // END SHARED OPENAPI SUPPORT'
  shared_b='    // BEGIN SHARED OPENAPI SUPPORT
    static final class OpenApiSupport { int changed; }
    // END SHARED OPENAPI SUPPORT'
  test_a="class OpenApiSpecExportTest {
    private static final String SERVICE = \"alpha\";
$shared_a
}"
  test_b="class OpenApiSpecExportTest {
    private static final String SERVICE = \"beta\";
$shared_a
}"
  local spec_v1 spec_v2
  spec_v1='{"openapi":"3.0.1","info":{"title":"t","version":"v0"},"paths":{"/a":{"get":{"operationId":"a","parameters":[{"name":"q","in":"query","required":false,"schema":{"type":"string"}}],"responses":{"200":{"description":"ok","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Out"}}}}}},"post":{"operationId":"b","requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/In"}}}},"responses":{"200":{"description":"ok"}}}},"/gone":{"get":{"operationId":"g","responses":{"200":{"description":"ok"}}}}},"components":{"schemas":{"Out":{"type":"object","properties":{"id":{"type":"string"},"name":{"type":"string"}}},"In":{"type":"object","required":["id"],"properties":{"id":{"type":"string"},"note":{"type":"string"}}}}}}'
  spec_v2="$spec_v1"

  echo "openapi-drift.sh self-test"
  put da-alpha/src/main/java/a/AlphaController.java '@RestController class AlphaController { }'
  put da-alpha/src/test/java/a/OpenApiSpecExportTest.java "$test_a"
  put docs/api/openapi/alpha.json "$spec_v1"
  put scripts/ci-guards/post-test/.keep ""
  commit "base"
  base="$(git -C "$repo" rev-parse HEAD)"
  trunk="$(git -C "$repo" symbolic-ref --short HEAD)"

  expect "a consistent module passes" 0 "ok"
  expect "no breaking-change warnings without a base" 0 "breaking-change warnings skipped"

  put da-beta/src/main/java/b/BetaController.java '@RestController class BetaController { }'
  commit "a controller without a test"
  expect "a module with a controller and no export test fails" 1 "da-beta has a controller but no OpenApiSpecExportTest"
  reset

  put da-beta/src/test/java/b/OpenApiSpecExportTest.java "$test_b"
  commit "a test without a contract"
  expect "an export test without a committed contract fails" 1 "docs/api/openapi/beta.json is missing"
  reset

  put docs/api/openapi/stale.json "$spec_v1"
  commit "orphan contract"
  expect "a contract without an export test fails" 1 "orphan contract"
  reset

  put docs/api/openapi/alpha.json 'not json'
  commit "broken contract"
  expect "a contract that is not JSON fails" 1 "not valid JSON"
  reset

  put docs/api/openapi/alpha.json '{"swagger":"2.0","info":{},"paths":{}}'
  commit "swagger 2"
  expect "a contract that is not OpenAPI 3 fails" 1 "not an OpenAPI 3 document"
  reset

  put da-beta/src/main/java/b/BetaController.java '@RestController class BetaController { }'
  put da-beta/src/test/java/b/OpenApiSpecExportTest.java "class OpenApiSpecExportTest {
    private static final String SERVICE = \"beta\";
$shared_b
}"
  put docs/api/openapi/beta.json "$spec_v1"
  commit "shared block differs"
  expect "a changed shared support block fails" 1 "the shared OpenAPI support code differs"
  reset

  put da-alpha/src/test/java/a/OpenApiSpecExportTest.java "class OpenApiSpecExportTest {
    private static final String SERVICE = \"alpha\";
}"
  commit "no markers"
  expect "a test without the shared markers fails" 1 "lacks the 'BEGIN SHARED OPENAPI SUPPORT'"
  reset

  # regenerate step: a command that rewrites the contract is an uncommitted API change
  out="$(cd "$repo" && env -u DLAP_CI_ROOT -u GITHUB_ACTIONS DLAP_OPENAPI_REGEN_CMD="printf '%s\n' '$spec_v1' > docs/api/openapi/alpha.json" bash "$hook" 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && printf '%s\n' "$out" | grep -qF "no difference from the committed files"; then
    passed=$((passed + 1)); echo "  ok    a regeneration that changes nothing passes"
  else
    failed=$((failed + 1)); echo "  FAIL  a regeneration that changes nothing passes (exit $rc)" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
  fi
  out="$(cd "$repo" && env -u DLAP_CI_ROOT -u GITHUB_ACTIONS DLAP_OPENAPI_REGEN_CMD="printf '%s\n' '{\"openapi\":\"3.0.1\",\"info\":{},\"paths\":{\"/new\":{}}}' > docs/api/openapi/alpha.json" bash "$hook" 2>&1)"; rc=$?
  if [ "$rc" -eq 1 ] && printf '%s\n' "$out" | grep -qF "differ from the controllers" && printf '%s\n' "$out" | grep -qF "alpha.json"; then
    passed=$((passed + 1)); echo "  ok    a regeneration that changes a contract fails and names the file"
  else
    failed=$((failed + 1)); echo "  FAIL  a regeneration that changes a contract fails (exit $rc)" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
  fi
  reset
  out="$(cd "$repo" && env -u DLAP_CI_ROOT -u GITHUB_ACTIONS DLAP_OPENAPI_REGEN_CMD="cp docs/api/openapi/alpha.json docs/api/openapi/extra.json" bash "$hook" 2>&1)"; rc=$?
  if [ "$rc" -eq 1 ] && printf '%s\n' "$out" | grep -qF "extra.json"; then
    passed=$((passed + 1)); echo "  ok    a regeneration that adds a file fails"
  else
    failed=$((failed + 1)); echo "  FAIL  a regeneration that adds a file fails (exit $rc)" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
  fi
  reset
  out="$(cd "$repo" && env -u DLAP_CI_ROOT -u GITHUB_ACTIONS DLAP_OPENAPI_REGEN_CMD="exit 3" bash "$hook" 2>&1)"; rc=$?
  if [ "$rc" -eq 1 ] && printf '%s\n' "$out" | grep -qF "regenerating the contracts failed (exit 3)"; then
    passed=$((passed + 1)); echo "  ok    a failing regeneration fails the hook"
  else
    failed=$((failed + 1)); echo "  FAIL  a failing regeneration fails the hook (exit $rc)" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
  fi
  reset

  # breaking-change warnings: never a failure
  put docs/api/openapi/alpha.json "$("$py" -c '
import json, sys
doc = json.loads(sys.argv[1])
del doc["paths"]["/gone"]
doc["paths"]["/a"]["get"]["parameters"][0]["required"] = True
doc["paths"]["/a"]["get"]["parameters"].append({"name": "must", "in": "query", "required": True, "schema": {"type": "string"}})
del doc["components"]["schemas"]["Out"]["properties"]["name"]
doc["components"]["schemas"]["In"]["required"].append("note")
doc["components"]["schemas"]["In"]["properties"]["extra"] = {"type": "string"}
doc["components"]["schemas"]["In"]["required"].append("extra")
doc["paths"]["/new"] = {"get": {"operationId": "n", "responses": {"200": {"description": "ok"}}}}
print(json.dumps(doc))
' "$spec_v1")"
  commit "breaking changes"
  expect "breaking changes are warnings, not failures" 0 "possible breaking change" DLAP_CI_BASE_SHA="$base"
  expect "a removed operation is reported" 0 "GET /gone: operation removed" DLAP_CI_BASE_SHA="$base"
  expect "a parameter that became required is reported" 0 "query parameter 'q' is now required" DLAP_CI_BASE_SHA="$base"
  expect "a new required parameter is reported" 0 "new required query parameter 'must'" DLAP_CI_BASE_SHA="$base"
  expect "a property removed from a response is reported" 0 "property 'name' removed from the response" DLAP_CI_BASE_SHA="$base"
  expect "a request property that became required is reported" 0 "property 'note' is now required" DLAP_CI_BASE_SHA="$base"
  expect "a new required request property is reported" 0 "new required property 'extra'" DLAP_CI_BASE_SHA="$base"
  not_expect "a new path is not reported" "GET /new" DLAP_CI_BASE_SHA="$base"
  expect "GitHub annotations are used in Actions" 0 "::warning title=OpenAPI breaking change::alpha: GET /gone: operation removed" DLAP_CI_BASE_SHA="$base" GITHUB_ACTIONS=true
  reset

  put docs/api/openapi/alpha.json "$("$py" -c '
import json, sys
doc = json.loads(sys.argv[1])
doc["paths"]["/new"] = {"get": {"operationId": "n", "responses": {"200": {"description": "ok"}}}}
doc["components"]["schemas"]["Out"]["properties"]["more"] = {"type": "string"}
print(json.dumps(doc))
' "$spec_v1")"
  commit "additive change"
  expect "an additive change reports nothing" 0 "0 possible breaking change" DLAP_CI_BASE_SHA="$base"
  reset

  expect "an unknown base is skipped" 0 "breaking-change warnings skipped" DLAP_CI_BASE_SHA=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef

  rm -rf "$repo/da-alpha" "$repo/docs"
  git -C "$repo" add -A && git -C "$repo" commit -q -m "remove everything"
  expect "a checkout without export tests skips" 77 "skipping"

  out="$(bash "$hook" --bogus 2>&1)"; rc=$?
  if [ "$rc" -eq 2 ]; then passed=$((passed + 1)); echo "  ok    an unknown argument is a usage error"
  else failed=$((failed + 1)); echo "  FAIL  an unknown argument is a usage error (exit $rc)" >&2; fi

  echo "openapi-drift.sh self-test: $passed passed, $failed failed"
  [ "$failed" -eq 0 ]
}

case "${1:-}" in
  "") check ;;
  --self-test) self_test ;;
  *) echo "usage: openapi-drift.sh [--self-test]" >&2; exit 2 ;;
esac
