#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# No-invented-data guard (hardening package UI-01). Fails when production code draws random
# numbers: a random value in a response, metric or report is invented data that a screen then
# presents as measured. Every value must come from stored data, or be null with an
# "unavailable" flag.
#
# Scope: Java and Kotlin sources under da-*/src/main. Flagged: Math.random, StrictMath.random,
# Random, ThreadLocalRandom, SplittableRandom, RandomGenerator.
# Not flagged: SecureRandom (cryptographic use), comment-only lines, test sources.
# Allowed by name: any package called "demo" (seed and demo data) and MockLLMClient (the
# deterministic-by-contract test double for the LLM provider).
#
# Needs GNU grep (word boundaries); present on the CI runners and in Git Bash.
#
# Usage:  scripts/ci-guards/pre/no-invented-data.sh              scan the repository
#         scripts/ci-guards/pre/no-invented-data.sh --self-test  prove the scan itself works
# Exit:   0 = clean (or self-test passed); 1 = violations listed on stderr; 2 = self-test failed.

set -euo pipefail

PATTERN='\b(Math\.random|StrictMath\.random|ThreadLocalRandom|SplittableRandom|RandomGenerator|Random)\b'

# scan_root <root>: print "file:line:text" for every violation under <root>/da-*/src/main.
scan_root() {
  local root="$1"
  (
    cd "$root"
    # Force text mode (-a): some real source files are misclassified as binary by grep and would
    # otherwise be skipped silently.
    find . -type f \( -name '*.java' -o -name '*.kt' \) -path './da-*/src/main/*' -print0 2>/dev/null \
      | { grep -zvE '/demo/|/MockLLMClient\.(java|kt)$' || true; } \
      | { xargs -0 -r grep -HnaE "$PATTERN" || true; } \
      | { grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|/\*|\*)' || true; }
  )
}

self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  local main="$tmp/da-fake/src/main/java/x"
  mkdir -p "$main/demo" "$tmp/da-fake/src/test/java/x"
  printf 'class Bad { double d = Math.random(); }\n'                          > "$main/Bad.java"
  printf 'class Bad2 { java.util.Random r = new java.util.Random(); }\n'      > "$main/Bad2.java"
  printf 'class Bad3 { int n = java.util.concurrent.ThreadLocalRandom.current().nextInt(); }\n' > "$main/Bad3.java"
  printf 'class Seed { int n = java.util.concurrent.ThreadLocalRandom.current().nextInt(); }\n' > "$main/demo/Seed.java"
  printf 'class MockLLMClient { java.util.Random r = new java.util.Random(); }\n' > "$main/MockLLMClient.java"
  printf 'class Crypto { java.security.SecureRandom r = new java.security.SecureRandom(); }\n' > "$main/Crypto.java"
  printf '// Math.random is banned here\n/* new Random() */\n/**\n * uses Random\n */\nclass Note { }\n' > "$main/Note.java"
  printf 'class T { double d = Math.random(); }\n' > "$tmp/da-fake/src/test/java/x/T.java"

  local hits files expected
  hits="$(scan_root "$tmp")"
  files="$(printf '%s\n' "$hits" | sed -n 's|^\./da-fake/src/main/java/x/\([A-Za-z0-9]*\)\.java:.*|\1|p' | sort -u | tr '\n' ' ')"
  expected="Bad Bad2 Bad3 "
  if [ "$files" != "$expected" ]; then
    echo "SELF-TEST FAILED: expected violations in [$expected] but found [$files]" >&2
    printf '%s\n' "$hits" >&2
    return 2
  fi
  echo "SELF-TEST OK: flagged [$expected]; allowed demo, MockLLMClient, SecureRandom, comments and tests."
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
hits="$(scan_root "$ROOT")"
if [ -n "$hits" ]; then
  echo "ERROR: random number generation in production code (invented data):" >&2
  printf '%s\n' "$hits" >&2
  echo "Use stored data, or return null with an 'unavailable' flag. Seed and demo data belongs in a 'demo' package." >&2
  exit 1
fi
echo "OK: no Math.random, Random or ThreadLocalRandom in da-*/src/main (demo packages and MockLLMClient excepted)."
