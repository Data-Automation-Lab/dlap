# Quickstart realm (`dlap-realm.json`)

The realm the quickstart compose pair imports (`deployment/environments/dev/docker-compose.yml`, service
`keycloak`). It is for development, evaluation and the e2e harnesses. **Never import it anywhere another
person can reach**: it seeds people with a published password. Production and the pilot import
`../production/dlap-realm.json` instead (`../production/README.md`).

## Seeded people

All five share the development password `Password123!`.

| Account | Role | Group |
|---|---|---|
| `admin` | `ROLE_ADMIN` | Administrators |
| `cdo` | `ROLE_CDO` | CDOs |
| `director` | `ROLE_DIRECTOR` | Directors |
| `dataengineer` | `ROLE_DATA_ENGINEER` | DataEngineers |
| `datascientist` | `ROLE_DATA_SCIENTIST` | DataScientists |

The harnesses sign in as `admin` through the password grant of `dlap-backend`.

## Roles and groups

The ten `ROLE_*` roles of `DlapConstants.UserRole` and one group per role. `ROLE_SERVICE_ACCOUNT` is held
only by the service accounts of `dlap-backend` and `dlap-mcp-gateway` (ADR-0034).

## Clients

| Client | Type | Notes |
|---|---|---|
| `dlap-ui` | public | Authorization code only; redirect URIs and web origins are the `localhost` and `127.0.0.1` origins on ports 3000 and 8081, no wildcard origin. Access tokens carry the audience `dlap-api`. |
| `dlap-backend` | confidential | Client credentials for service-to-service calls, plus the password grant for the harnesses. Secret from `DLAP_BACKEND_CLIENT_SECRET` (fallback `change-me-dev-only`). Access tokens carry the audience `dlap-api`. |
| `dlap-mcp-gateway` | confidential | Client credentials for MCP gateways (PEP-02). Secret from `DLAP_MCP_GATEWAY_CLIENT_SECRET`. Tokens carry the audience `dlap-mcp-pep` only. |

The `basic` client scope puts `sub` and `auth_time` into the tokens: Keycloak 25 and later no longer add
`sub` without it, and the services and the PEP take the subject from `sub`. `basic` and `dlap` are also the
realm's default client scopes, so a client added later in the admin console gets them; a client created with
an explicit `defaultClientScopes` list must name `basic` itself.

## Settings

Registration and e-mail password reset are off (an administrator creates accounts and resets passwords),
brute-force protection is on, remember-me is on, login with e-mail is allowed. No identity providers.

## Changing it

Edit the file, then recreate the container so the empty database imports it again:
`docker compose -f docker-compose.yml -f docker-compose.microservices.yml up -d --force-recreate keycloak`
(from `deployment/environments/dev`). Run `../tests/test-realm-templates.py` and `../tests/dev-realm-smoke.sh`.
