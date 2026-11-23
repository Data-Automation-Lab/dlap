# DLAP Keycloak

Identity for DLAP: Keycloak 26.8.1, realm `dlap`. Every compose file in the repository runs the same release,
pinned by digest: `quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`.
Bump the quickstart, the demo overlay and `Dockerfile` together.

## Files

| Path | What it is |
|---|---|
| `keycloak-import/dlap-realm.json` | Quickstart realm: seeded people with a published password, for development and the harnesses only (`keycloak-import/README.md`) |
| `production/dlap-realm.json` | Production realm template: no seeded people, MFA for administrators and approvers, no password grant, events on (`production/README.md`) |
| `Dockerfile` | Production image: `kc.sh build` run once with PostgreSQL, health on, metrics off and the scripts, impersonation, device, CIBA and Kerberos features disabled; starts with `start --optimized` |
| `keycloak-health-check.sh` | Quickstart health check: `/health/ready` on the management port 9000 |
| `tests/pilot-login-smoke.sh` | Starts the pilot's production Keycloak on PostgreSQL and signs in through the browser flow, with and without OTP and through an identity provider (`tests/oidc_login.py`) |
| `tests/dev-realm-smoke.sh` | Starts the quickstart Keycloak and checks the dev seed, the features and the tokens the harnesses rely on |
| `tests/test-realm-templates.py` | Offline checks of both realms, the Dockerfile and the Keycloak services of the compose files |

## Where each realm runs

| Stack | Server | Database | Realm |
|---|---|---|---|
| Quickstart and harnesses (`deployment/environments/dev`) | `start-dev --import-realm` | embedded file in the container | quickstart realm |
| Read-only pilot (`deployment/environments/pilot`, overlay `docker-compose.pilot.keycloak.yml`) | `start --optimized --import-realm`, image from `Dockerfile` | PostgreSQL database `keycloak` | production template |
| Hosted demo (`deployment/environments/demo/keycloak`) | `start --import-realm` | PostgreSQL database `keycloak` | demo realm |

Production outside the pilot follows [`deployment/PRODUCTION.md`](../../PRODUCTION.md), section 3.

## Quickstart notes

- The admin console is `http://localhost:8180/admin`, account `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`
  from the root `.env` (fallback `admin` / `changeme`).
- The realm is imported when the container's database is new. A restart keeps what the container has; a
  recreated container (`docker compose up -d --force-recreate keycloak`) imports the file again. Keycloak 26
  has no overwrite strategy for `--import-realm`.
- Health is on port 9000 inside the container, not on 8080.
- No identity providers. The Google and Facebook brokers were removed (IAM-02); corporate sign-in goes
  through an OpenID Connect provider as `production/README.md` describes for Okta and Entra ID.

## Tests

```bash
python3 deployment/platform/keycloak/tests/test-realm-templates.py          # offline, seconds
bash deployment/platform/keycloak/tests/dev-realm-smoke.sh                   # Docker, about 2 minutes
bash deployment/platform/keycloak/tests/pilot-login-smoke.sh [--with-services]  # Docker, 3 to 10 minutes
```

The two smoke scripts take the host-wide harness lock (`e2e-tests/harness-lib.sh`) and run in their own
compose project.
