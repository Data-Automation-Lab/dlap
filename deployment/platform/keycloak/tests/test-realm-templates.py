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

"""Offline checks of the DLAP Keycloak realms, image and compose services (IAM-02).

Reads the files in the checkout; needs no Docker and no network. Standard library only.

    python3 deployment/platform/keycloak/tests/test-realm-templates.py [-v]

Each check_* function returns a list of problems (empty when the file is right). The tests run every
check on the real files and expect no problem, then run it on a copy with one deliberate defect and expect
that defect to be named, so a check that passes everything fails here.

What is checked:
  production template  no seeded people, no identity provider, MFA for ROLE_ADMIN, ROLE_CDO,
                       ROLE_POLICY_MANAGER through the marker role on the browser, direct-grant and
                       post-broker flows, no password or implicit grant, PKCE and exact redirect URIs on
                       dlap-ui, events on, the dlap-api audience on dlap-ui and dlap-backend only,
                       secrets without a published default, every DlapConstants.UserRole role
  quickstart realm     the dev seed is kept, no identity provider, the basic scope on every client, the
                       dlap-api audience on dlap-ui and dlap-backend
  images and compose   one Keycloak 26.x release pinned by digest everywhere, the scripts feature off,
                       no social-login variables, the pilot runs start --optimized on PostgreSQL with the
                       production template, the orphan realm copy is gone
"""

import copy
import json
import pathlib
import re
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[3]
PRODUCTION_REALM = ROOT / "deployment/platform/keycloak/production/dlap-realm.json"
DEV_REALM = ROOT / "deployment/platform/keycloak/keycloak-import/dlap-realm.json"
DOCKERFILE = ROOT / "deployment/platform/keycloak/Dockerfile"
DEV_COMPOSE = ROOT / "deployment/environments/dev/docker-compose.yml"
DEMO_KEYCLOAK = ROOT / "deployment/environments/demo/keycloak/docker-compose.demo.keycloak.yml"
PILOT_KEYCLOAK = ROOT / "deployment/environments/pilot/docker-compose.pilot.keycloak.yml"
PILOT_TLS = ROOT / "deployment/environments/pilot/docker-compose.pilot.tls.yml"
# Every other file that starts a Keycloak server for a check or a test (Q-507, Q-508, Q-509). Each must run
# the same release as the compose files above. The two Java tests name the tag only: Testcontainers takes
# no digest after a tag.
TEST_COMPOSE = ROOT / "deployment/environments/test/docker-compose.yml"
PEP_LIVE_CHECK = ROOT / "da-mcp-pep/scripts/keycloak-live-check.sh"
ADMIN_COMPAT_TEST = ROOT / ("da-admin-service/src/test/java/com/dlap/adminservice/config/"
                            "KeycloakAdminClientCompatibilityTest.java")
PRODUCTION_COMPAT_TEST = ROOT / ("da-admin-service/src/test/java/com/dlap/adminservice/config/"
                                 "KeycloakProductionRealmCompatibilityTest.java")
CONSTANTS = ROOT / "da-protos/src/main/java/com/dlap/common/constants/DlapConstants.java"
ORPHAN_REALM = ROOT / "deployment/environments/dev/docker/keycloak/realm-config/dlap-realm.json"

MFA_MARKER = "dlap-mfa-required"
MFA_ROLES = ("ROLE_ADMIN", "ROLE_CDO", "ROLE_POLICY_MANAGER")
SEEDED_PEOPLE = ("admin", "cdo", "director", "dataengineer", "datascientist")
AUDIENCE = "${DLAP_API_AUDIENCE:dlap-api}"
# The production template reads sslRequired from the environment (Q-514, D31): external by default, all under
# the pilot TLS overlay.
SSL_REQUIRED = "${DLAP_REALM_SSL_REQUIRED:external}"
JAVA_IMAGE = re.compile(r'"quay\.io/keycloak/keycloak:([0-9][^"@]*)"')
# An identity provider or its settings, not the OTP application names ("totpAppGoogleName").
SOCIAL = r'"providerId":\s*"%s"|%s_(CLIENT|APP)|%s(apis)?\.com'
IMAGE = re.compile(r"quay\.io/keycloak/keycloak:([0-9][^\s@\"']*)(@sha256:[0-9a-f]{64})?")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def user_roles():
    """The ROLE_* names of DlapConstants.UserRole, read from the Java source."""
    source = CONSTANTS.read_text(encoding="utf-8")
    body = source[source.index("class UserRole"):]
    body = body[:body.index("private UserRole()")]
    return {"ROLE_" + value for value in re.findall(r'public static final String \w+ = "([A-Z_]+)";', body)}


def flow(realm, alias):
    return next((f for f in realm.get("authenticationFlows", []) if f["alias"] == alias), None)


def mfa_problems(realm, top_alias, otp_authenticator, label):
    """A top-level flow must reach a CONDITIONAL subflow gated on the marker role that requires OTP."""
    problems = []
    seen = set()
    queue = [top_alias]
    gated = False
    while queue:
        alias = queue.pop()
        if alias in seen:
            continue
        seen.add(alias)
        current = flow(realm, alias)
        if current is None:
            problems.append("%s: flow '%s' does not exist" % (label, alias))
            continue
        executions = current.get("authenticationExecutions", [])
        conditions = [e for e in executions if e.get("authenticator") == "conditional-user-role"]
        if conditions:
            config_alias = conditions[0].get("authenticatorConfig")
            config = next((c for c in realm.get("authenticatorConfig", []) if c["alias"] == config_alias), {})
            settings = config.get("config", {})
            otp = [e for e in executions if e.get("authenticator") == otp_authenticator]
            if (settings.get("condUserRole") == MFA_MARKER and settings.get("negate", "false") == "false"
                    and conditions[0].get("requirement") == "REQUIRED"
                    and otp and otp[0].get("requirement") == "REQUIRED"):
                gated = True
        for execution in executions:
            if execution.get("flowAlias"):
                if execution.get("requirement") == "DISABLED":
                    continue
                queue.append(execution["flowAlias"])
    if not gated:
        problems.append("%s: no subflow requires %s for accounts holding %s" % (label, otp_authenticator, MFA_MARKER))
    return problems


def client(realm, client_id):
    return next((c for c in realm.get("clients", []) if c["clientId"] == client_id), None)


def audiences(c):
    return [m["config"].get("included.custom.audience") for m in (c or {}).get("protocolMappers", [])
            if m.get("protocolMapper") == "oidc-audience-mapper"
            and m["config"].get("access.token.claim") == "true"]


def column_problems(realm):
    """Keycloak stores descriptions and names in varchar(255) columns; a longer one aborts the import."""
    problems = []
    items = [("client", c) for c in realm.get("clients", [])]
    items += [("role", r) for r in realm.get("roles", {}).get("realm", [])]
    items += [("flow", f) for f in realm.get("authenticationFlows", [])]
    for kind, item in items:
        for key in ("description", "name"):
            if len(item.get(key) or "") > 255:
                problems.append("%s %s: %s is %d characters, more than 255" % (
                    kind, item.get("clientId") or item.get("name") or item.get("alias"), key, len(item[key])))
    return problems


def default_scope_problems(realm):
    """A client created later (admin console or API) gets the realm's default scopes. A realm file that
    declares its own client scopes and no defaults leaves that list empty on import (measured on 26.8.1),
    so such a client issues tokens without sub. Without declared scopes Keycloak builds its own defaults,
    which include basic."""
    declared = realm.get("defaultDefaultClientScopes")
    if declared is None and "clientScopes" not in realm:
        return []
    if "basic" not in (declared or []):
        return ["the realm default client scopes lack basic, so a client added later has no sub claim"]
    return []


def check_production(realm):
    """Problems in the production realm template."""
    problems = column_problems(realm) + default_scope_problems(realm)
    people = [u["username"] for u in realm.get("users", []) if not u.get("serviceAccountClientId")]
    if people:
        problems.append("seeded people: %s" % ", ".join(people))
    if any(u.get("credentials") for u in realm.get("users", [])):
        problems.append("a user carries credentials")
    if realm.get("identityProviders") or realm.get("identityProviderMappers"):
        problems.append("identity providers are configured")
    for key, want in (("registrationAllowed", False), ("bruteForceProtected", True), ("eventsEnabled", True),
                      ("adminEventsEnabled", True), ("enabled", True)):
        if realm.get(key) is not want:
            problems.append("%s is %r, not %r" % (key, realm.get(key), want))
    if realm.get("sslRequired") != SSL_REQUIRED:
        problems.append("sslRequired is %r, not the placeholder %s (default external, all under the TLS overlay)"
                        % (realm.get("sslRequired"), SSL_REQUIRED))
    roles = {r["name"]: r for r in realm.get("roles", {}).get("realm", [])}
    missing = sorted(user_roles() - set(roles))
    if missing:
        problems.append("roles missing: %s" % ", ".join(missing))
    if MFA_MARKER not in roles:
        problems.append("marker role %s is missing" % MFA_MARKER)
    for name in MFA_ROLES:
        composites = roles.get(name, {}).get("composites", {}).get("realm", [])
        if MFA_MARKER not in composites:
            problems.append("%s does not include %s" % (name, MFA_MARKER))
    if realm.get("browserFlow") != "dlap browser":
        problems.append("browserFlow is %r" % realm.get("browserFlow"))
    if realm.get("directGrantFlow") != "dlap direct grant":
        problems.append("directGrantFlow is %r" % realm.get("directGrantFlow"))
    problems += mfa_problems(realm, "dlap browser", "auth-otp-form", "browser flow")
    problems += mfa_problems(realm, "dlap direct grant", "direct-grant-validate-otp", "direct grant flow")
    problems += mfa_problems(realm, "dlap post broker mfa", "auth-otp-form", "post broker flow")
    for c in realm.get("clients", []):
        name = c["clientId"]
        if c.get("directAccessGrantsEnabled"):
            problems.append("%s allows the password grant" % name)
        if c.get("implicitFlowEnabled"):
            problems.append("%s allows the implicit flow" % name)
        if c.get("fullScopeAllowed", True):
            problems.append("%s has full scope" % name)
        secret = c.get("secret")
        if secret is not None and not re.fullmatch(r"\$\{[A-Z_]+\}", secret):
            problems.append("%s secret is not a placeholder without a default: %r" % (name, secret))
    ui = client(realm, "dlap-ui")
    if ui is None:
        problems.append("dlap-ui is missing")
    else:
        if ui.get("attributes", {}).get("pkce.code.challenge.method") != "S256":
            problems.append("dlap-ui does not require PKCE S256")
        for uri in ui.get("redirectUris", []) + ui.get("webOrigins", []):
            if "*" in uri or uri == "+":
                problems.append("dlap-ui has a wildcard redirect URI or origin: %s" % uri)
        mapped = next((m["roles"] for m in realm.get("scopeMappings", []) if m.get("client") == "dlap-ui"), [])
        if MFA_MARKER in mapped or "ROLE_SERVICE_ACCOUNT" in mapped:
            problems.append("dlap-ui tokens could carry %s or ROLE_SERVICE_ACCOUNT" % MFA_MARKER)
    for name in ("dlap-ui", "dlap-backend"):
        if AUDIENCE not in audiences(client(realm, name)):
            problems.append("%s tokens lack the audience %s" % (name, AUDIENCE))
    gateway = client(realm, "dlap-mcp-gateway")
    if gateway is not None and audiences(gateway) != ["dlap-mcp-pep"]:
        problems.append("dlap-mcp-gateway audiences are %s, not only dlap-mcp-pep" % audiences(gateway))
    for name in ("dlap-backend", "dlap-mcp-gateway"):
        c = client(realm, name)
        if c is None or not c.get("serviceAccountsEnabled") or c.get("standardFlowEnabled"):
            problems.append("%s is not a client-credentials-only client" % name)
    return problems


def check_dev(realm, text):
    """Problems in the quickstart realm."""
    problems = column_problems(realm) + default_scope_problems(realm)
    if realm.get("sslRequired") != "external":
        problems.append("the quickstart realm has sslRequired %r, not external" % realm.get("sslRequired"))
    people = {u["username"]: u for u in realm.get("users", [])}
    for name in SEEDED_PEOPLE:
        if name not in people:
            problems.append("dev seed account %s is missing" % name)
    if realm.get("identityProviders") or realm.get("identityProviderMappers"):
        problems.append("identity providers are configured")
    for word in ("google", "facebook"):
        if re.search(SOCIAL % (word, word, word), text, re.I):
            problems.append("the realm still mentions %s" % word)
    if not any(s["name"] == "basic" for s in realm.get("clientScopes", [])):
        problems.append("the basic client scope (sub claim) is missing")
    for c in realm.get("clients", []):
        if "basic" not in c.get("defaultClientScopes", []):
            problems.append("%s lacks the basic default scope" % c["clientId"])
    for name in ("dlap-ui", "dlap-backend"):
        if AUDIENCE not in audiences(client(realm, name)):
            problems.append("%s tokens lack the audience %s" % (name, AUDIENCE))
    if audiences(client(realm, "dlap-mcp-gateway")) != ["dlap-mcp-pep"]:
        problems.append("dlap-mcp-gateway tokens carry more than dlap-mcp-pep")
    return problems


def service_block(compose_text, service):
    """The text of one top-level service of a compose file."""
    match = re.search(r"^  " + re.escape(service) + r":\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)", compose_text, re.S | re.M)
    return match.group(1) if match else ""


def keycloak_block(compose_text):
    """The text of the top-level keycloak service of a compose file."""
    return service_block(compose_text, "keycloak")


def check_images(texts):
    """texts maps a file label to its text. One 26.x release, pinned by digest, in every file."""
    problems = []
    found = set()
    for label, text in texts.items():
        refs = IMAGE.findall(text)
        if not refs:
            problems.append("%s names no Keycloak image" % label)
        for version, digest in refs:
            if not version.startswith("26."):
                problems.append("%s runs Keycloak %s, not 26.x" % (label, version))
            if not digest:
                problems.append("%s runs Keycloak %s without a digest" % (label, version))
            found.add((version, digest))
    if len(found) > 1:
        problems.append("more than one Keycloak image: %s" % sorted(found))
    return problems


def check_java_images(texts, release):
    """texts maps a label to a Java source. Each names the Keycloak tag of the compose files' release."""
    problems = []
    for label, text in texts.items():
        tags = JAVA_IMAGE.findall(text)
        if not tags:
            problems.append("%s names no Keycloak image" % label)
        for tag in tags:
            if tag != release:
                problems.append("%s runs Keycloak %s, not %s" % (label, tag, release))
    return problems


def release_of(text):
    """The one release the compose files pin, for example 26.8.1."""
    return IMAGE.search(text).group(1)


def check_dev_compose(text):
    problems = []
    block = keycloak_block(text)
    if not block:
        return ["no keycloak service"]
    if re.search(r"^\s*- KC_FEATURES=[^\n]*scripts", block, re.M):
        problems.append("the scripts feature is switched on")
    if not re.search(r"KC_FEATURES_DISABLED=[^\n]*\bscripts\b", block):
        problems.append("the scripts feature is not disabled by name")
    if re.search(r"GOOGLE_|FACEBOOK_", block):
        problems.append("social-login variables are passed to Keycloak")
    if "start-dev" not in block:
        problems.append("the quickstart does not run start-dev")
    return problems


def check_dockerfile(text):
    problems = []
    disabled = re.search(r"KC_FEATURES_DISABLED=(\S+)", text)
    if not disabled or "scripts" not in disabled.group(1).split(","):
        problems.append("the image does not disable the scripts feature")
    if not re.search(r"KC_DB=postgres", text):
        problems.append("the image is not built for PostgreSQL")
    if "kc.sh build" not in text and "kc.sh\", \"build" not in text:
        problems.append("the image does not run kc.sh build")
    if not re.search(r'CMD \["start", "--optimized"\]', text):
        problems.append("the image does not start with --optimized")
    return problems


def check_pilot_overlay(text):
    problems = []
    block = keycloak_block(text)
    if not re.search(r'- "start"\n\s+- "--optimized"', block):
        problems.append("the pilot does not run start --optimized")
    if "production/dlap-realm.json:/opt/keycloak/data/import/dlap-realm.json" not in block:
        problems.append("the pilot does not import the production template")
    if "keycloak-import" in block:
        problems.append("the pilot mounts the quickstart realm")
    if not re.search(r"KC_DB_URL=jdbc:postgresql://", block):
        problems.append("the pilot Keycloak is not on PostgreSQL")
    if "dev-file" in block:
        problems.append("the pilot Keycloak uses the embedded file database")
    if "DLAP_API_AUDIENCE=${PILOT_JWT_AUDIENCE:-dlap-api}" not in block:
        problems.append("the realm audience does not follow PILOT_JWT_AUDIENCE")
    # sslRequired is a literal here, so a variable of the caller cannot weaken the realm; the TLS overlay raises it.
    if "- DLAP_REALM_SSL_REQUIRED=external\n" not in block:
        problems.append("the pilot realm import does not set sslRequired external")
    # One secret for the bootstrap admin and the database role was Q-541: the database password is its own,
    # required variable with no fallback.
    for expression in re.findall(r"KEYCLOAK_DB_PASSWORD[:=]?\s*\"?\$\{([^\n]*)", text):
        if "KEYCLOAK_ADMIN_PASSWORD" in expression:
            problems.append("KEYCLOAK_DB_PASSWORD falls back to KEYCLOAK_ADMIN_PASSWORD")
        if not expression.startswith("KEYCLOAK_DB_PASSWORD:?"):
            problems.append("KEYCLOAK_DB_PASSWORD is not required")
    if len(re.findall(r"\$\{KEYCLOAK_DB_PASSWORD:\?", text)) != 2:
        problems.append("KEYCLOAK_DB_PASSWORD must be required in both places that read it (role and Keycloak)")
    return problems


def check_tls_overlay_realm(text):
    """The TLS overlay raises the realm to sslRequired all and closes Keycloak's HTTP listener."""
    problems = []
    block = keycloak_block(text)
    if "- DLAP_REALM_SSL_REQUIRED=all\n" not in block:
        problems.append("the TLS overlay does not import the realm with sslRequired all")
    if "- KC_HTTP_ENABLED=false" not in block:
        problems.append("the TLS overlay leaves Keycloak's HTTP listener open")
    return problems


def check_pilot_discovery(text):
    """da-discovery's login registration must start without discovering the fixed issuer."""
    problems = []
    block = service_block(text, "da-discovery")
    if "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_PROVIDER=pilot" not in block:
        problems.append("da-discovery still discovers the issuer at start")
    # Without issuer-uri nothing supplies the grant type, and Spring refuses the registration
    # ("authorizationGrantType cannot be null"): da-discovery stops at start.
    if "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_AUTHORIZATIONGRANTTYPE=authorization_code" not in block:
        problems.append("the da-discovery registration has no grant type")
    for endpoint in ("AUTHORIZATIONURI", "TOKENURI", "JWKSETURI", "USERINFOURI"):
        if "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_PILOT_%s=" % endpoint not in block:
            problems.append("the pilot provider has no %s" % endpoint)
    return problems


class RealFiles(unittest.TestCase):
    """The files in the checkout pass every check."""

    def test_production_template(self):
        self.assertEqual([], check_production(read_json(PRODUCTION_REALM)))

    def test_quickstart_realm(self):
        self.assertEqual([], check_dev(read_json(DEV_REALM), DEV_REALM.read_text(encoding="utf-8")))

    def test_one_patched_release_everywhere(self):
        self.assertEqual([], check_images({
            "dev compose": keycloak_block(DEV_COMPOSE.read_text(encoding="utf-8")),
            "demo overlay": keycloak_block(DEMO_KEYCLOAK.read_text(encoding="utf-8")),
            "Dockerfile": DOCKERFILE.read_text(encoding="utf-8"),
            "test compose": keycloak_block(TEST_COMPOSE.read_text(encoding="utf-8")),
            "da-mcp-pep live check": PEP_LIVE_CHECK.read_text(encoding="utf-8"),
        }))

    def test_the_java_tests_run_the_same_release(self):
        release = release_of(DEV_COMPOSE.read_text(encoding="utf-8"))
        self.assertEqual([], check_java_images({
            "KeycloakAdminClientCompatibilityTest": ADMIN_COMPAT_TEST.read_text(encoding="utf-8"),
            "KeycloakProductionRealmCompatibilityTest": PRODUCTION_COMPAT_TEST.read_text(encoding="utf-8"),
        }, release))

    def test_no_server_start_still_names_the_old_release(self):
        for path in (TEST_COMPOSE, PEP_LIVE_CHECK, ADMIN_COMPAT_TEST):
            self.assertNotIn("23.0.1", path.read_text(encoding="utf-8"), str(path))

    def test_pep_live_check_issues_tokens_with_a_subject(self):
        # Keycloak 26 puts sub into tokens through the basic client scope only (Q-507).
        text = PEP_LIVE_CHECK.read_text(encoding="utf-8")
        self.assertIn('"defaultClientScopes": ["basic", "dlap"]', text)

    def test_tls_overlay_imports_the_realm_with_ssl_all(self):
        self.assertEqual([], check_tls_overlay_realm(PILOT_TLS.read_text(encoding="utf-8")))

    def test_dev_compose(self):
        self.assertEqual([], check_dev_compose(DEV_COMPOSE.read_text(encoding="utf-8")))

    def test_dockerfile(self):
        self.assertEqual([], check_dockerfile(DOCKERFILE.read_text(encoding="utf-8")))

    def test_pilot_overlay(self):
        self.assertEqual([], check_pilot_overlay(PILOT_KEYCLOAK.read_text(encoding="utf-8")))

    def test_pilot_discovery_registration(self):
        self.assertEqual([], check_pilot_discovery(PILOT_KEYCLOAK.read_text(encoding="utf-8")))

    def test_orphan_realm_copy_is_gone(self):
        self.assertFalse(ORPHAN_REALM.exists(), str(ORPHAN_REALM))


class Defects(unittest.TestCase):
    """Each check names the defect it exists for."""

    def setUp(self):
        self.production = read_json(PRODUCTION_REALM)
        self.dev = read_json(DEV_REALM)
        self.dev_text = DEV_REALM.read_text(encoding="utf-8")

    def assertNamed(self, fragment, problems):
        self.assertTrue(any(fragment in p for p in problems), "%r not in %r" % (fragment, problems))

    def test_a_seeded_person_is_refused(self):
        realm = copy.deepcopy(self.production)
        realm["users"].append({"username": "admin", "enabled": True,
                               "credentials": [{"type": "password", "value": "x"}]})
        self.assertNamed("seeded people: admin", check_production(realm))

    def test_an_identity_provider_is_refused(self):
        realm = copy.deepcopy(self.production)
        realm["identityProviders"] = [{"alias": "google", "providerId": "google"}]
        self.assertNamed("identity providers", check_production(realm))

    def test_each_mfa_role_must_include_the_marker(self):
        for name in MFA_ROLES:
            realm = copy.deepcopy(self.production)
            role = next(r for r in realm["roles"]["realm"] if r["name"] == name)
            role["composites"]["realm"].remove(MFA_MARKER)
            self.assertNamed("%s does not include" % name, check_production(realm))

    def test_an_optional_otp_step_is_refused(self):
        realm = copy.deepcopy(self.production)
        for execution in flow(realm, "dlap browser mfa")["authenticationExecutions"]:
            if execution.get("authenticator") == "auth-otp-form":
                execution["requirement"] = "ALTERNATIVE"
        self.assertNamed("browser flow: no subflow", check_production(realm))

    def test_a_disabled_mfa_subflow_is_refused(self):
        realm = copy.deepcopy(self.production)
        for execution in flow(realm, "dlap direct grant")["authenticationExecutions"]:
            if execution.get("flowAlias") == "dlap direct grant mfa":
                execution["requirement"] = "DISABLED"
        self.assertNamed("direct grant flow: no subflow", check_production(realm))

    def test_a_condition_on_another_role_is_refused(self):
        realm = copy.deepcopy(self.production)
        for config in realm["authenticatorConfig"]:
            if config["alias"] == "dlap mfa required post broker":
                config["config"]["condUserRole"] = "ROLE_VIEWER"
        self.assertNamed("post broker flow: no subflow", check_production(realm))

    def test_the_built_in_browser_flow_is_refused(self):
        realm = copy.deepcopy(self.production)
        realm["browserFlow"] = "browser"
        self.assertNamed("browserFlow", check_production(realm))

    def test_a_password_grant_is_refused(self):
        realm = copy.deepcopy(self.production)
        client(realm, "dlap-backend")["directAccessGrantsEnabled"] = True
        self.assertNamed("dlap-backend allows the password grant", check_production(realm))

    def test_a_published_secret_default_is_refused(self):
        realm = copy.deepcopy(self.production)
        client(realm, "dlap-backend")["secret"] = "${DLAP_BACKEND_CLIENT_SECRET:change-me-dev-only}"
        self.assertNamed("not a placeholder", check_production(realm))

    def test_a_wildcard_redirect_is_refused(self):
        realm = copy.deepcopy(self.production)
        client(realm, "dlap-ui")["redirectUris"].append("${DLAP_APP_URL}/*")
        self.assertNamed("wildcard", check_production(realm))

    def test_events_off_is_refused(self):
        realm = copy.deepcopy(self.production)
        realm["adminEventsEnabled"] = False
        self.assertNamed("adminEventsEnabled", check_production(realm))

    def test_a_missing_audience_is_refused(self):
        realm = copy.deepcopy(self.production)
        client(realm, "dlap-ui")["protocolMappers"] = [
            m for m in client(realm, "dlap-ui")["protocolMappers"] if m["protocolMapper"] != "oidc-audience-mapper"]
        self.assertNamed("dlap-ui tokens lack the audience", check_production(realm))

    def test_the_gateway_must_not_reach_the_services(self):
        realm = copy.deepcopy(self.production)
        gateway = client(realm, "dlap-mcp-gateway")
        gateway["protocolMappers"].append(copy.deepcopy(
            next(m for m in client(realm, "dlap-backend")["protocolMappers"] if m["name"] == "dlap-api-audience")))
        self.assertNamed("dlap-mcp-gateway audiences", check_production(realm))

    def test_a_description_longer_than_the_column_is_refused(self):
        realm = copy.deepcopy(self.production)
        client(realm, "dlap-ui")["description"] = "x" * 256
        self.assertNamed("client dlap-ui: description is 256 characters", check_production(realm))

    def test_a_missing_dlap_role_is_refused(self):
        realm = copy.deepcopy(self.production)
        realm["roles"]["realm"] = [r for r in realm["roles"]["realm"] if r["name"] != "ROLE_AUDITOR"]
        self.assertNamed("roles missing: ROLE_AUDITOR", check_production(realm))

    def test_the_dev_seed_must_stay(self):
        realm = copy.deepcopy(self.dev)
        realm["users"] = [u for u in realm["users"] if u["username"] != "admin"]
        self.assertNamed("dev seed account admin", check_dev(realm, self.dev_text))

    def test_social_login_in_the_dev_realm_is_refused(self):
        self.assertNamed("mentions google", check_dev(self.dev, self.dev_text + ' "providerId": "google"'))

    def test_realm_defaults_without_basic_are_refused(self):
        realm = copy.deepcopy(self.dev)
        realm["defaultDefaultClientScopes"].remove("basic")
        self.assertNamed("realm default client scopes lack basic", check_dev(realm, self.dev_text))
        del realm["defaultDefaultClientScopes"]
        self.assertNamed("realm default client scopes lack basic", check_dev(realm, self.dev_text))
        realm = copy.deepcopy(self.production)
        realm["clientScopes"] = [{"name": "dlap", "protocol": "openid-connect"}]
        self.assertNamed("realm default client scopes lack basic", check_production(realm))

    def test_a_client_without_basic_scope_is_refused(self):
        realm = copy.deepcopy(self.dev)
        client(realm, "dlap-ui")["defaultClientScopes"].remove("basic")
        self.assertNamed("dlap-ui lacks the basic", check_dev(realm, self.dev_text))

    def test_an_old_or_unpinned_image_is_refused(self):
        self.assertNamed("not 26.x", check_images({"x": "image: quay.io/keycloak/keycloak:23.0.1"}))
        self.assertNamed("without a digest", check_images({"x": "image: quay.io/keycloak/keycloak:26.8.1"}))
        self.assertNamed("more than one", check_images({
            "a": "quay.io/keycloak/keycloak:26.8.1@sha256:" + "a" * 64,
            "b": "quay.io/keycloak/keycloak:26.8.0@sha256:" + "b" * 64}))

    def test_the_scripts_feature_is_refused(self):
        text = DEV_COMPOSE.read_text(encoding="utf-8").replace(
            "      - KC_FEATURES_DISABLED=scripts\n", "      - KC_FEATURES=scripts\n")
        problems = check_dev_compose(text)
        self.assertNamed("switched on", problems)
        self.assertNamed("not disabled by name", problems)
        dockerfile = DOCKERFILE.read_text(encoding="utf-8").replace("KC_FEATURES_DISABLED=scripts,", "KC_FEATURES_DISABLED=")
        self.assertNamed("scripts feature", check_dockerfile(dockerfile))

    def test_a_pilot_on_the_quickstart_realm_is_refused(self):
        text = PILOT_KEYCLOAK.read_text(encoding="utf-8").replace(
            "../../platform/keycloak/production/dlap-realm.json", "../../platform/keycloak/keycloak-import/dlap-realm.json")
        self.assertNamed("production template", check_pilot_overlay(text))
        text = PILOT_KEYCLOAK.read_text(encoding="utf-8").replace('      - "--optimized"\n', "")
        self.assertNamed("--optimized", check_pilot_overlay(text))

    def test_a_hardcoded_ssl_mode_in_the_template_is_refused(self):
        realm = copy.deepcopy(self.production)
        realm["sslRequired"] = "external"
        self.assertNamed("sslRequired is 'external'", check_production(realm))
        realm["sslRequired"] = "none"
        self.assertNamed("sslRequired", check_production(realm))

    def test_a_quickstart_realm_that_is_not_external_is_refused(self):
        realm = copy.deepcopy(self.dev)
        realm["sslRequired"] = "none"
        self.assertNamed("not external", check_dev(realm, self.dev_text))

    def test_a_tls_overlay_without_ssl_all_is_refused(self):
        text = PILOT_TLS.read_text(encoding="utf-8").replace("- DLAP_REALM_SSL_REQUIRED=all\n", "")
        self.assertNamed("sslRequired all", check_tls_overlay_realm(text))
        text = PILOT_TLS.read_text(encoding="utf-8").replace("- DLAP_REALM_SSL_REQUIRED=all\n", "- DLAP_REALM_SSL_REQUIRED=external\n")
        self.assertNamed("sslRequired all", check_tls_overlay_realm(text))
        text = PILOT_TLS.read_text(encoding="utf-8").replace("- KC_HTTP_ENABLED=false", "- KC_HTTP_ENABLED=true")
        self.assertNamed("HTTP listener", check_tls_overlay_realm(text))

    def test_a_pilot_realm_import_that_forwards_the_callers_ssl_mode_is_refused(self):
        text = PILOT_KEYCLOAK.read_text(encoding="utf-8").replace(
            "- DLAP_REALM_SSL_REQUIRED=external", "- DLAP_REALM_SSL_REQUIRED=${DLAP_REALM_SSL_REQUIRED:-external}")
        self.assertNamed("does not set sslRequired external", check_pilot_overlay(text))

    def test_a_database_password_that_falls_back_to_the_admin_password_is_refused(self):
        text = PILOT_KEYCLOAK.read_text(encoding="utf-8").replace(
            "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD must be set in .env.pilot}",
            "${KEYCLOAK_DB_PASSWORD:-${KEYCLOAK_ADMIN_PASSWORD:?x}}")
        problems = check_pilot_overlay(text)
        self.assertNamed("falls back to KEYCLOAK_ADMIN_PASSWORD", problems)
        self.assertNamed("is not required", problems)

    def test_an_old_server_in_a_java_test_is_refused(self):
        self.assertNamed("not 26.8.1", check_java_images(
            {"t": 'private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:23.0.1";'}, "26.8.1"))
        self.assertNamed("names no Keycloak image", check_java_images({"t": "class T {}"}, "26.8.1"))

    def test_a_discovery_registration_without_grant_type_is_refused(self):
        text = PILOT_KEYCLOAK.read_text(encoding="utf-8").replace(
            "      - SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_AUTHORIZATIONGRANTTYPE=authorization_code\n", "")
        self.assertNamed("no grant type", check_pilot_discovery(text))
        text = PILOT_KEYCLOAK.read_text(encoding="utf-8").replace(
            "REGISTRATION_OIDC_PROVIDER=pilot", "REGISTRATION_OIDC_PROVIDER=oidc")
        self.assertNamed("discovers the issuer", check_pilot_discovery(text))


if __name__ == "__main__":
    unittest.main(argv=sys.argv[:1] + sys.argv[1:])
