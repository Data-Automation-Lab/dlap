# da-discovery

Multi-cloud scanner for the Discovery stage. It crawls AWS, GCP, Azure and Unity Catalog
resources, reads each asset's audit-log coverage, and publishes what it finds onto the log
spine that `da-logingest` and `da-catalog` consume (ADR-0033).

| | |
|---|---|
| Port | 8091 on the host in the dev compose stack, 8080 inside the container |
| Database | its own PostgreSQL schema, managed by Flyway (`src/main/resources/db/migration`) |
| Image | `src/main/docker/Dockerfile` (distroless Java 21, built from `build/libs/da-discovery.jar`) |
| Configuration | `src/main/resources/application.properties` and `src/main/resources/config/` |

## Build and test

Run from the repository root, with JDK 21:

```bash
./gradlew :da-discovery:compileJava
./gradlew :da-discovery:test
```

## Run

The service starts with the rest of the stack:

```bash
docker compose -f deployment/environments/dev/docker-compose.yml \
               -f deployment/environments/dev/docker-compose.microservices.yml up
```

See the repository `README.md` for the quickstart and `docs/getting-started.md` for the
full setup. Cloud permissions the crawlers need are in `docs/guides/cloud-permissions.md`.

## Service documentation

- `docs/how-to/running-discovery-crawlers.md` - run and schedule crawlers
- `docs/how-to/how-to-use-log-analyzer.md` - the log analyzer
- `docs/how-to/using-spring-shell.md` - the Spring Shell commands
- `docs/features/` - event-driven architecture and metrics
- `docs/spring/spring-structure.md` - package layout
