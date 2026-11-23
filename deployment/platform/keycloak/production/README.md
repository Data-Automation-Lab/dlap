# Production Keycloak realm template (IAM-02)

`dlap-realm.json` in this folder is the realm a production or pilot Keycloak imports. The quickstart realm
(`../keycloak-import/dlap-realm.json`) is for development and the harnesses only: it seeds five people with
the published password `Password123!` and opens the password grant on `dlap-backend`.

| | Quickstart realm | Production template |
|---|---|---|
| People | five seeded accounts | none; accounts come from your identity provider or an operator |
| Server | `kc.sh start-dev`, embedded file database | `kc.sh start --optimized` on PostgreSQL (`../Dockerfile`) |
| MFA | none | one-time code (TOTP) for `ROLE_ADMIN`, `ROLE_CDO`, `ROLE_POLICY_MANAGER` and every role that includes `dlap-mfa-required` |
| Password grant | on for `dlap-backend` | off on every client; `dlap-ui` is authorization code with PKCE (S256) only |
| Identity providers | none | none; add yours (Okta and Entra ID below) |
| Events | off | login and admin events stored for 30 days |
| Audience `dlap-api` | in `dlap-ui` and `dlap-backend` tokens | the same; the pilot TLS overlay makes every service require it |

## What the template contains

- **Roles.** The ten `ROLE_*` roles of `DlapConstants.UserRole` and the marker role `dlap-mfa-required`.
  `ROLE_ADMIN`, `ROLE_CDO` and `ROLE_POLICY_MANAGER` are composites that include the marker. The marker is
  not a DLAP authority: `dlap-ui` has no scope mapping for it, so it never reaches a token.
- **Groups.** One group per role (`Administrators`, `CDOs`, `PolicyManagers`, ...). Put people in groups,
  not roles.
- **Flows.**
  - `dlap browser` (the browser flow): session cookie, identity-provider redirect (`kc_idp_hint`), or user
    name and password followed by `dlap browser mfa`. That subflow runs only for accounts holding
    `dlap-mfa-required`. An account without an OTP device must register one before any token is issued.
  - `dlap direct grant`: the same rule for the password grant. No shipped client enables that grant.
  - `dlap post broker mfa`: set it as the **post login flow** of every identity provider. Accounts holding
    `dlap-mfa-required` then present a DLAP one-time code after the provider's own sign-in.
- **Clients.**
  - `dlap-ui`: public, PKCE S256, exact redirect URIs `$DLAP_APP_URL/` and `$DLAP_APP_URL/silent-check-sso.html`,
    full scope off (only the nine people roles reach a token).
  - `dlap-backend` and `dlap-mcp-gateway`: client credentials only, no refresh token, secrets from
    `DLAP_BACKEND_CLIENT_SECRET` and `DLAP_MCP_GATEWAY_CLIENT_SECRET`, both held only by
    `ROLE_SERVICE_ACCOUNT` service accounts. `dlap-mcp-gateway` tokens carry the audience `dlap-mcp-pep`
    and not `dlap-api`, so a gateway's token opens the PEP and nothing else.
- **Settings.** Brute-force protection (5 failures, 15 minutes at most), password policy
  `length(12) and notUsername and notEmail and passwordHistory(3)`, 5-minute access tokens, 30-minute idle
  and 8-hour maximum sessions, refresh tokens revoked on use, no self-registration, no password reset by
  e-mail, no remember-me.

Variables read at import, as `${NAME}` placeholders: `DLAP_BACKEND_CLIENT_SECRET`,
`DLAP_MCP_GATEWAY_CLIENT_SECRET`, `DLAP_APP_URL` (no default), `DLAP_API_AUDIENCE` (default `dlap-api`) and
`DLAP_REALM_SSL_REQUIRED` (default `external`).

## Require SSL (`sslRequired`)

The template reads `sslRequired` from `DLAP_REALM_SSL_REQUIRED`, default `external` (Q-514, D31):

| Value | Meaning | Where it is used |
|---|---|---|
| `external` | Plain HTTP is accepted from loopback and private addresses, HTTPS is required from everywhere else | the pilot without the TLS overlay, where the internal network talks to Keycloak on `http://keycloak:8080`; any other import that sets nothing |
| `all` | Every request must be HTTPS, also from private addresses | the pilot with the TLS overlay, which sets `DLAP_REALM_SSL_REQUIRED=all` for Keycloak: every hop to Keycloak is TLS and its HTTP listener is off |

The quickstart realm (`../keycloak-import/dlap-realm.json`) keeps `external`. The value is read at import
only, like the rest of the file: a realm that exists already keeps what it has. To change a running realm,
use the admin console (Realm settings, General, Require SSL) or import into a new database. The master
realm is a separate setting (its default is `external`); with the HTTP listener off it is reachable over
TLS only anyway. `deployment/environments/pilot/tls/smoke-tls.sh` reads the live value of realm `dlap`
through the admin API and fails if the TLS pilot runs with anything but `all`.

## Import once, then change in the console

`kc.sh start --optimized --import-realm` imports the file only when the realm does not exist. After that
the realm lives in the PostgreSQL database `keycloak`, and an edit of this file changes nothing. Make later
changes in the admin console (they are recorded as admin events), or import into a new database. Keep the
database in your backups: it holds accounts, OTP devices and events (the pilot's DR-01 backup covers every
non-template database).

## First start

1. Start Keycloak with `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD`. Keycloak creates
   that master-realm account at the first start only and marks it temporary.
2. Sign in to the admin console on the loopback port (`ssh -L 8180:127.0.0.1:8180 <vm>`, then
   `http://localhost:8180/admin`).
3. In realm `master`, create a permanent administrator with a one-time code, then delete the temporary
   account. `da-admin-service` signs in with `KEYCLOAK_ADMIN_USER` and `KEYCLOAK_ADMIN_PASSWORD`; give it the
   permanent account, or a confidential client with a service account and the `realm-management` roles it
   needs, set through `KEYCLOAK_CLIENTID`, `KEYCLOAK_CLIENT_SECRET` and `KEYCLOAK_ADMIN_REALM`.
4. In realm `dlap`, add your identity provider (below), or create accounts and add them to groups.
5. Sign in once as every role that needs MFA and register the authenticator app.

## Identity providers: Okta and Microsoft Entra ID

DLAP uses Keycloak's generic **OpenID Connect v1.0** provider for both. Keycloak's `google`, `facebook`
and `microsoft` social providers are not used: they serve consumer accounts. Both setups share these
settings in Keycloak (realm `dlap`, Identity providers, OpenID Connect v1.0):

| Setting | Value |
|---|---|
| Alias | `okta` or `entra`; the redirect URI below contains it |
| Discovery endpoint | the provider's `.well-known/openid-configuration` (table below) |
| Client authentication | Client secret sent as post |
| PKCE | on, S256 |
| Validate signatures | on, with the JWKS URL from discovery |
| Default scopes | `openid profile email` |
| Trust e-mail | off |
| Sync mode | `force`, so a group removed at the provider removes the DLAP role at the next sign-in |
| First login flow | `first broker login` |
| Post login flow | `dlap post broker mfa` |

The redirect URI to register at the provider is
`<public Keycloak URL>/realms/dlap/broker/<alias>/endpoint`.

Then add one **Claim to Role** mapper per DLAP group (sync mode `force`): claim `groups` (Okta) or
`roles` (Entra, app roles), claim value the provider's group or app-role name, role the DLAP role. Keep the
mapping one to one: `dlap-admins` to `ROLE_ADMIN`, `dlap-viewers` to `ROLE_VIEWER`, and so on.
`deployment/platform/keycloak/tests/pilot-login-smoke.sh` (check K10) configures a provider exactly this
way against a throwaway upstream realm and signs in through it.

### Okta

1. Applications, Create App Integration, **OIDC - OpenID Connect**, **Web Application**.
2. Grant type: Authorization Code. Sign-in redirect URI: the redirect URI above. Sign-out redirect URI:
   optional.
3. Assignments: only the Okta groups that should reach DLAP.
4. Groups claim: on the authorization server you use, add a claim `groups`, included in the ID token,
   value type Groups, filter for example "starts with `dlap-`". On the org authorization server this is the
   app's Sign On tab, "Groups claim filter".
5. Copy the client ID and secret into the Keycloak provider. Discovery endpoint:
   `https://<your-org>.okta.com/oauth2/default/.well-known/openid-configuration` for the default custom
   authorization server, or `https://<your-org>.okta.com/.well-known/openid-configuration` for the org one.

### Microsoft Entra ID

1. App registrations, New registration, single tenant. Redirect URI, platform **Web**: the redirect URI
   above.
2. Certificates & secrets: a client secret, with an expiry date you track.
3. App roles: one per DLAP group (`dlap-admins`, `dlap-viewers`, ...), allowed member type Users/Groups.
   Enterprise applications, the app, Users and groups: assign the roles to Entra groups. Properties:
   **Assignment required** = Yes, so nobody outside those groups can sign in.
4. Use the `roles` claim, not the `groups` claim: `groups` carries object IDs and is dropped when a user is
   in more than 200 groups (the overage case).
5. Copy the application (client) ID and the secret into the Keycloak provider. Discovery endpoint:
   `https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration`.

### MFA with a provider

The provider's own MFA (Okta policies, Entra Conditional Access) applies at its sign-in. The post login
flow adds the DLAP one-time code for accounts holding `dlap-mfa-required` on top, so an approver needs MFA
even if the provider's policy is weaker. To rely on the provider alone, remove the post login flow from the
provider; that is a security decision to record, not a default.

## Pilot

`deployment/environments/pilot/docker-compose.pilot.keycloak.yml` builds `../Dockerfile`, runs
`kc.sh start --optimized --import-realm` with this template on the pilot's PostgreSQL (database and role
`keycloak`), fixes the front-end URL to `DLAP_PILOT_ID_URL` (one issuer for browser and service tokens),
and writes `DLAP_PILOT_APP_URL` into the `dlap-ui` redirect URIs. With the TLS overlay, set both to
`PILOT_PUBLIC_ORIGIN`; `up.sh` refuses the start when they differ. That overlay also makes PostgreSQL refuse
plain connections, so it sets Keycloak's `KC_DB_URL` to `?sslmode=verify-full&sslrootcert=/tls/ca.crt`, serves
Keycloak on HTTPS only and imports the realm with `sslRequired` `all` (see "Require SSL"). The database
role `keycloak` has its own secret, `KEYCLOAK_DB_PASSWORD` (Q-541). Check a configuration with
`deployment/platform/keycloak/tests/pilot-login-smoke.sh` (add `--with-services` to also start da-catalog
and da-discovery against it; it leaves the TLS overlay out) and, for the TLS pilot,
`deployment/environments/pilot/tls/tls-e2e.sh`.

## What a Helm chart needs

There is no chart yet (DEP-02 follows P1). A chart's identity part needs:

- the image built from `../Dockerfile` (or the same build options), run as `start --optimized`;
- an external PostgreSQL database and role for Keycloak, with `sslmode=verify-full` in `KC_DB_URL`;
- this template as a ConfigMap mounted at `/opt/keycloak/data/import/dlap-realm.json`, plus
  `--import-realm`;
- Secrets for the bootstrap admin, the two client secrets and the database password;
- `KC_HOSTNAME` set to the public URL, `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, `KC_PROXY_HEADERS` matching
  the ingress, and readiness and liveness probes on `/health/ready` and `/health/live` of port 9000;
- for more than one replica, the distributed caches (`KC_CACHE=ispn`, the production default) with a
  cache stack the cluster network allows, which the single-VM pilot does not need.
