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

<#
  Live verification of the Consumed-stage AuthZEN PDP (ADR-0040).
  Runs the docs/specs/enforcement-contract.md section 2 flow against the
  running dev stack: fetch a ROLE_SERVICE_ACCOUNT token from Keycloak
  (client_credentials on dlap-backend), then POST an AuthZEN evaluation to
  da-policyengine and check that an unauthenticated call is rejected with 401.

  Prereq: dev compose up (Keycloak :8180, da-policyengine :8083) AND a
  da-policyengine image that includes the C1 PDP commit (rebuild if it
  predates feature/deployment c606802).

  The client secret is read from DLAP_BACKEND_CLIENT_SECRET; the fallback is
  the dev-only placeholder from the realm import
  (deployment/platform/keycloak/keycloak-import/dlap-realm.json).

  Runs on Windows PowerShell 5.1 and PowerShell 7+:
    powershell -NoProfile -ExecutionPolicy Bypass -File e2e-tests/authzen-pdp-verify.ps1
  Exit code 0 when every check passes, 1 otherwise.
#>

$ErrorActionPreference = 'Stop'
$KC     = if ($env:DLAP_KEYCLOAK_URL) { $env:DLAP_KEYCLOAK_URL } else { 'http://localhost:8180' }
$PE     = if ($env:DLAP_POLICYENGINE_URL) { $env:DLAP_POLICYENGINE_URL } else { 'http://localhost:8083' }
$REALM  = 'dlap'
$CLIENT = 'dlap-backend'
$SECRET = if ($env:DLAP_BACKEND_CLIENT_SECRET) { $env:DLAP_BACKEND_CLIENT_SECRET } else { 'change-me-dev-only' }

$failures = 0
function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "FAIL: $msg" -ForegroundColor Red; $script:failures++ }

# Decode the payload segment of a JWT. JWTs are base64url (RFC 7515), so map
# '-' and '_' back to the standard alphabet and restore padding first.
function ConvertFrom-JwtPayload([string]$jwt) {
  $seg = $jwt.Split('.')[1].Replace('-', '+').Replace('_', '/')
  switch ($seg.Length % 4) { 2 { $seg += '==' } 3 { $seg += '=' } }
  $json = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($seg))
  return $json | ConvertFrom-Json
}

# HTTP status code from an Invoke-RestMethod error (5.1 and 7+), or $null.
function Get-HttpStatus($err) {
  $resp = $err.Exception.Response
  if ($null -eq $resp) { return $null }
  return [int]$resp.StatusCode
}

Section '1. PDP health'
try {
  $health = (Invoke-RestMethod "$PE/actuator/health").status
  Write-Host "status: $health"
  if ($health -ne 'UP') { Fail "da-policyengine health is '$health', expected UP" }
} catch {
  Fail "health check failed: $_"
}

Section '2. Service-account token (client_credentials)'
$tok = Invoke-RestMethod -Method Post `
  -Uri "$KC/realms/$REALM/protocol/openid-connect/token" `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ grant_type = 'client_credentials'; client_id = $CLIENT; client_secret = $SECRET }
$access = $tok.access_token
$claims = ConvertFrom-JwtPayload $access
# The DLAP realm maps realm roles to a top-level 'roles' claim; services fall
# back to realm_access.roles (see da-policyengine SecurityConfiguration).
$roles = @()
if ($claims.roles) { $roles += $claims.roles }
if ($claims.realm_access -and $claims.realm_access.roles) { $roles += $claims.realm_access.roles }
$roles = $roles | Select-Object -Unique
Write-Host "token acquired; roles: $($roles -join ', ')"
if ($roles -notcontains 'ROLE_SERVICE_ACCOUNT') { Fail 'token lacks ROLE_SERVICE_ACCOUNT; the PDP will answer 403' }

Section '3. AuthZEN evaluation (spec section 2 payload)'
$payload = @{
  subject  = @{ type = 'agent'; id = 'svc-marketing-agent'; properties = @{ roles = @('DATA_ANALYST') } }
  action   = @{ name = 'read' }
  resource = @{ type = 'asset'; properties = @{ labels = @('EU_RESIDENT') } }
  context  = @{ purpose = 'campaign-analytics'; consent = $true }
} | ConvertTo-Json -Depth 6

try {
  $resp = Invoke-RestMethod -Method Post -Uri "$PE/access/v1/evaluation" `
    -Headers @{ Authorization = "Bearer $access" } `
    -ContentType 'application/json' -Body $payload
  Write-Host 'HTTP 200, response:' -ForegroundColor Green
  $resp | ConvertTo-Json -Depth 6
  if ($resp.decision -isnot [bool]) { Fail "response 'decision' is not a boolean" }
  if (-not ($resp.context -and $resp.context.id)) { Fail "response lacks context.id (decision id)" }
  Write-Host "decision = $($resp.decision)  (default-deny is EXPECTED if no AccessPolicy is seeded;"
  Write-Host " a 200 with a boolean 'decision' and a context.id proves the PDP endpoint and contract are live)"
} catch {
  Fail "evaluation call failed (HTTP $(Get-HttpStatus $_)): $_"
}

Section '4. Negative control: no token must be 401'
try {
  Invoke-RestMethod -Method Post -Uri "$PE/access/v1/evaluation" -ContentType 'application/json' -Body $payload | Out-Null
  Fail 'got a response without a token'
} catch {
  $code = Get-HttpStatus $_
  if ($code -eq 401) { Write-Host 'as expected: 401 unauthorized' -ForegroundColor Green }
  else { Fail "expected 401 without a token, got HTTP $code ($_)" }
}

Section 'Verdict'
if ($failures -eq 0) {
  Write-Host 'PASS: all AuthZEN PDP checks passed' -ForegroundColor Green
  exit 0
}
Write-Host "FAIL: $failures check(s) failed" -ForegroundColor Red
exit 1
