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
# Guard: every third-party GitHub Action is pinned to a full commit SHA, and
# actions/cache, actions/upload-artifact and actions/download-artifact are not
# on the removed v1 to v3 lines. A pin carries its release as a trailing
# comment, for example:
#   uses: actions/checkout@<40 hex> # v7.0.1
# Local actions (./path) and docker:// references pinned by sha256 are allowed.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$root" || exit 2

shopt -s nullglob globstar
files=(.github/workflows/*.yml .github/workflows/*.yaml .github/actions/**/action.yml .github/actions/**/action.yaml)
if [ "${#files[@]}" -eq 0 ]; then
  echo "actions-pinned: no workflow files; skipping"
  exit 77
fi

uses_re='^[[:space:]-]*uses:[[:space:]]*'
all="$(grep -nE "$uses_re" "${files[@]}" /dev/null || true)"

unpinned="$(
  printf '%s\n' "$all" \
    | grep -vE 'uses:[[:space:]]*(\./|docker://[^[:space:]]+@sha256:[0-9a-f]{64})' \
    | grep -vE 'uses:[[:space:]]*[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}([[:space:]]|$)' \
    | grep -E 'uses:' || true
)"

old_major="$(
  printf '%s\n' "$all" \
    | grep -E 'uses:[[:space:]]*actions/(cache|upload-artifact|download-artifact)@' \
    | grep -E '#[[:space:]]*v[0-3]\.' || true
)"

status=0
if [ -n "$unpinned" ]; then
  echo "actions-pinned: actions not pinned to a full commit SHA:" >&2
  echo "$unpinned" | sed 's/^/  /' >&2
  status=1
fi
if [ -n "$old_major" ]; then
  echo "actions-pinned: removed major versions (v1 to v3) of cache/artifact actions:" >&2
  echo "$old_major" | sed 's/^/  /' >&2
  status=1
fi
[ "$status" -eq 0 ] && echo "actions-pinned: OK (${#files[@]} workflow file(s))"
exit "$status"
