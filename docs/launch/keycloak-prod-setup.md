# Keycloak production setup

This guide covers what differs between the dev / test Keycloak realm that
ships in `deployment/platform/keycloak/keycloak-import/` and what production
deployments need.

Every identifier below is taken from
`deployment/platform/keycloak/keycloak-import/dlap-realm.json` - the file the
dev compose actually mounts (`deployment/environments/dev/docker-compose.yml:83`,
`../../platform/keycloak/keycloak-import:/opt/keycloak/data/import`).

## What the dev realm bundles

The realm id and name are both **`dlap`** (`dlap-realm.json:2-3`). Keycloak
runs in `start-dev --import-realm` mode in the dev compose and is published on
host port **8180**.

| Client | Purpose | Dev credential |
|---|---|---|
| `dlap-ui` | React SPA public client (`publicClient: true`, `serviceAccountsEnabled: false`) | None - public clients have no usable secret |
| `dlap-backend` | M2M client for service-to-service Feign auth (`serviceAccountsEnabled: true`, `publicClient: false`, ADR-0034) | `secret: "${DLAP_BACKEND_CLIENT_SECRET:change-me-dev-only}"` |

There is **no** `dlap-services` client in the shipped realm. `dlap-backend` is
the M2M client, and its service-account user is `service-account-dlap-backend`,
holding exactly one realm role: `ROLE_SERVICE_ACCOUNT`.

> **Env-var name mismatch - read before deploying.** The realm import
> substitutes **`DLAP_BACKEND_CLIENT_SECRET`**, but the services read
> **`DLAP_SERVICE_CLIENT_SECRET`** (see the property block below). Both must be
> set, to the same value. Likewise, `ServiceAuthAutoConfiguration` has a
> built-in fallback client id of `dlap-services`
> (`da-protos/src/main/java/com/dlap/common/auth/ServiceAuthAutoConfiguration.java:76`),
> which does not exist in the realm - every service's `application.properties`
> overrides it to `dlap-backend`, so do not rely on the code default.

Bundled realm roles (`dlap-realm.json:49-180`) - ten:

`ROLE_ADMIN`, `ROLE_CDO`, `ROLE_DIRECTOR`, `ROLE_POLICY_MANAGER`,
`ROLE_DATA_ENGINEER`, `ROLE_DATA_SCIENTIST`, `ROLE_DATA_ANALYST`,
`ROLE_AUDITOR`, `ROLE_VIEWER`, and the machine principal
`ROLE_SERVICE_ACCOUNT`.

Bundled users: five demo users (`admin`, `cdo`, `director`, `dataengineer`,
`datascientist`) plus the `service-account-dlap-backend` service account.
Group → role assignments are pre-seeded so the demo tour shows per-role
landing pages immediately.

> `deployment/platform/keycloak/keycloak-import/dlap-realm.json` is the only dev realm.
> Edit that copy; no other realm copy exists in the repository.

## What production must change

### 1. Rotate every dev secret

The dev secret default (`change-me-dev-only`) is in source control and on
every adopter's laptop. Production must generate a fresh value:

```bash
# Generate a strong client secret
DLAP_BACKEND_CLIENT_SECRET=$(openssl rand -base64 48 | tr -d '/+=' | cut -c1-64)
echo "$DLAP_BACKEND_CLIENT_SECRET"   # store in your secrets manager

# The services read the same value under a different variable name
DLAP_SERVICE_CLIENT_SECRET="$DLAP_BACKEND_CLIENT_SECRET"
```

Set `DLAP_BACKEND_CLIENT_SECRET` on the Keycloak container (the realm import
substitutes it at boot) and `DLAP_SERVICE_CLIENT_SECRET` on each Spring
service:

```yaml
environment:
  - DLAP_BACKEND_CLIENT_SECRET=...   # Keycloak container
  - DLAP_SERVICE_CLIENT_SECRET=...   # each da-* service container
```

`dlap-ui` is a public client, so there is no UI client secret to rotate. If
you convert it to a confidential client, add and manage that secret yourself -
the shipped realm does not define one.

### 2. Enable the M2M client in each service

Each service that participates in M2M Feign calls reads four properties,
defaulted to disabled (e.g.
`da-autoarchival/src/main/resources/application.properties:101-104`):

```properties
dlap.service-auth.enabled=${DLAP_SERVICE_AUTH_ENABLED:false}
dlap.service-auth.token-uri=${KEYCLOAK_AUTH_SERVER_URL:http://localhost:8180}/realms/dlap/protocol/openid-connect/token
dlap.service-auth.client-id=${DLAP_SERVICE_CLIENT_ID:dlap-backend}
dlap.service-auth.client-secret=${DLAP_SERVICE_CLIENT_SECRET:change-me-dev-only}
```

Set `DLAP_SERVICE_AUTH_ENABLED=true` in production. The dev profile leaves it
off so local-test flows keep working without Keycloak being primed;
`ServiceAuthAutoConfiguration` is `@ConditionalOnProperty` on this flag.

### 3. Replace the bundled demo users

The bundled groups + users are for the demo tour only. Production:

- Delete the demo users via the Keycloak admin console (or omit them from your
  realm import). Keep `service-account-dlap-backend`.
- Configure your identity provider - either an external IdP federation
  (Google / Microsoft / Okta via the existing `identityProviders` block) or an
  internal user-federation source (LDAP, AD).
- Map external group claims onto the nine human role names in
  `DlapConstants.UserRole`
  (`da-protos/src/main/java/com/dlap/common/constants/DlapConstants.java:147-169`).
  Never map a human to `ROLE_SERVICE_ACCOUNT` - it is a machine principal and
  is deliberately outside the 4-tier access model (ADR-0032, ADR-0034).

### 4. Restrict CORS / redirect URIs

The dev realm gives `dlap-ui` `webOrigins: ["*"]` and four localhost redirect
URIs - `http://localhost:3000/*`, `http://127.0.0.1:3000/*` and their `https`
equivalents (`dlap-realm.json:314-324`). Production must narrow both to the
public domain:

```json
{
  "clientId": "dlap-ui",
  "redirectUris": ["https://dlap.example.com/*"],
  "webOrigins": ["https://dlap.example.com"]
}
```

### 5. Token lifespans

The shipped realm sets `accessTokenLifespan: 300` - **5 minutes**, not the one
hour earlier revisions of this guide claimed (`dlap-realm.json:10`). SSO
sessions are `ssoSessionIdleTimeout: 1800` / `ssoSessionMaxLifespan: 36000`
(`:12-13`). The `ServiceTokenProvider` cache re-fetches when fewer than
`REFRESH_BUFFER_SECONDS = 30` seconds remain
(`da-protos/src/main/java/com/dlap/common/auth/ServiceTokenProvider.java:59,126`),
so a 5-minute lifespan means roughly one token fetch every 4.5 minutes per
service. That is workable; raise the lifespan only if the token endpoint load
is measurably a problem, and lower the SSO timeouts if your threat model
requires it.

### 6. Refresh-token policy for `dlap-backend`

The client-credentials flow does not need refresh tokens, and disabling them
removes a stolen-token expansion vector. The shipped realm does **not** set
`client_credentials.use_refresh_token` on `dlap-backend` either way - earlier
revisions of this guide claimed it was already disabled; it simply is not
present, so Keycloak's default applies. Set it explicitly to `"false"` in the
client's `attributes` block for production. `ServiceTokenProvider` never uses a
refresh token - it re-runs the client-credentials grant - so turning it off
costs nothing.

### 7. Audit log + SIEM

Enable Keycloak's event logging (`events-enabled: true`,
`events-store-provider: jpa`) and forward the events to your SIEM. The bundled
realm has events disabled to keep dev noise low.

### 8. Run Keycloak in production mode behind TLS

The dev compose runs `start-dev` with `KC_DB=dev-file` and serves plain HTTP
on the container's `8080`; every service is wired to
`KEYCLOAK_AUTH_SERVER_URL=http://keycloak:8080`. Production must switch to
`kc.sh start` with a persistent database, terminate TLS, and update every
service's `KEYCLOAK_AUTH_SERVER_URL` to the `https://` issuer. See
`deployment/PRODUCTION.md`.

### 9. Back up the realm

The production realm-import is the disaster-recovery source of truth. Store it
in version control alongside your platform chart; re-export periodically via
`kc.sh export` to capture user / group state.

## Quick verification

After setting `DLAP_SERVICE_AUTH_ENABLED=true` on a service, confirm the M2M
token flow works:

```bash
curl -s -X POST \
  -d "grant_type=client_credentials" \
  -d "client_id=dlap-backend" \
  -d "client_secret=$DLAP_SERVICE_CLIENT_SECRET" \
  https://keycloak.example.com/realms/dlap/protocol/openid-connect/token | jq .
```

Against the dev stack, substitute
`http://localhost:8180/realms/dlap/protocol/openid-connect/token`.

Expected: `200 OK` with an `access_token`. If you get `401`, the secret is
wrong; if `404`, the realm or client id is wrong. Decode the token and confirm
`ROLE_SERVICE_ACCOUNT` is present before assuming the grant is complete.

## Standing items

- **JWT signing-key rotation**: the shipped realm import defines no key
  providers and no rotation policy (`dlap-realm.json` has no `components`
  block), so the realm signs with the keys Keycloak generates on first start.
  Rotation is an operator procedure (add a new key provider with a higher
  priority, then retire the old one; see the Keycloak Server Administration
  Guide). Define the procedure and a review cadence before production; nothing
  in this repository performs or schedules it.
- **Previously-leaked dev secrets** (Q-006 follow-up): rotate
  `change-me-dev-only` in any non-dev deployment that ever booted with it.
