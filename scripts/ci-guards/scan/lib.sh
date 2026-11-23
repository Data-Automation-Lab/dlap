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
# Helpers shared by the scanner wrappers (scripts/ci-guards/semgrep/run.sh, scan/trivy.sh,
# scan/odc.sh). Source it; it runs nothing. Work package CI-02b.

# Git Bash: paths handed to docker.exe or to a Windows python go through cygpath, and
# MSYS_NO_PATHCONV=1 keeps Git Bash from rewriting the container-side paths.
export MSYS_NO_PATHCONV=1

# native <path>: a path the Windows docker and python can open (Git Bash paths such as /d/git/x are not).
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# find_python: print python3 or python, or return 1.
find_python() { command -v python3 || command -v python; }

# stage_tree <repo root> <destination>: copy every tracked or not-ignored file of the working tree to the
# destination, so a scanner sees what a git-aware scan sees without needing git inside its container (a
# linked worktree has a .git file that points outside the mounted directory) and without walking build
# output over a slow Windows bind mount. A gitlink such as new-ui is copied as an empty directory.
# Falls back to the root itself (prints it) when it is not a git work tree.
stage_tree() {
  local src="$1" dest="$2"
  rm -rf "$dest" && mkdir -p "$dest"
  if (cd "$src" && git rev-parse --git-dir >/dev/null 2>&1); then
    (cd "$src" && git ls-files -z --cached --others --exclude-standard \
      | tar --no-recursion --null -T - -cf - | tar -x -C "$dest")
    printf '%s' "$dest"
  else
    printf '%s' "$src"
  fi
}

# ensure_image <image reference>: make the image available locally, with three attempts. A registry that
# answers "too many requests" or drops the connection once must not turn into a failed security gate.
ensure_image() {
  local image="$1" attempt delay
  docker image inspect "$image" >/dev/null 2>&1 && return 0
  for attempt in 1 2 3; do
    if docker pull --quiet "$image" >/dev/null; then return 0; fi
    delay=$((attempt * 10))
    echo "pull of $image failed (attempt $attempt of 3); retrying in ${delay}s" >&2
    sleep "$delay"
  done
  echo "cannot pull $image" >&2
  return 1
}
