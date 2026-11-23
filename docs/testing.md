# Testing

DLAP tests are organized into tiers by what they need to run. Every tier below runs in
`./gradlew test`. A tier that needs Docker runs when a Docker daemon is reachable and reports an
`initializationError` (never a silent pass) when it is not, so run the suite with Docker. A test
may be switched off with `@Disabled` only under the rule in "Skipped and disabled tests" below.

## Test tiers

| Tier | What it covers | Needs | Runs by default |
|---|---|---|---|
| Unit (Mockito) | Service-layer business logic with mocked repositories/clients | Nothing | Yes |
| `@WebMvcTest` | Controller routing, auth, request/response shapes with mocked services | Nothing | Yes |
| `@DataJpaTest` | Repository queries against a real schema | Docker (Testcontainers Postgres), or in-memory H2 for a few legacy classes | Yes |
| Live integration | The wired path: real Spring context, Postgres, Kafka and the security filter chain | Docker (Testcontainers Postgres); Kafka runs inside the test JVM (`@EmbeddedKafka`) | Yes |

## How to run

```bash
# All deterministic tests for one service
./gradlew :da-reporting:test

# A single test class
./gradlew :da-policyengine:test --tests "com.dlap.policyengine.access.AccessDecisionServiceTest"

# Compile the tests without running them
./gradlew :da-autoarchival:compileTestJava
```

On this project's Windows development boxes run Gradle on JDK 21 (the default JDK 24 cannot
launch Gradle 8.7) and cap the workers: `--max-workers=2`.

## Live integration tests

These replaced two `@Disabled` skeletons. Each starts a Testcontainers Postgres through
`@ServiceConnection` (Flyway migrations, then Hibernate `validate`) and an in-JVM Kafka broker.

| Test | Location | What it proves |
|---|---|---|
| `DriftJoinIntegrationTest` | `da-reporting` | A DENY decision on `dlap.access.decision` followed by a read on `dlap.discovery.asset.accessed` becomes an `ENFORCEMENT_BYPASS` finding, a read with no decision becomes `UNENFORCED_READ`, an allowed read makes none, a redelivered read makes one, and `GET /api/v1/reporting/drift/findings` returns them. |
| `AuthZenPdpLiveIntegrationTest` | `da-policyengine` | `POST /access/v1/evaluation` over HTTP with a signed `ROLE_SERVICE_ACCOUNT` JWT returns an AuthZEN allow with the mask obligation from a seeded policy, publishes the decision event to Kafka, and answers 403 to a non-service role and 401 to no token or a token signed by another key. |
| `HibernateTimeZoneIT` | `da-discovery` | `hibernate.jdbc.time_zone=UTC` stores `Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime`, times and dates as expected on PostgreSQL. |

The wire contract, the combining logic and the correlation rules stay covered by the faster
tests next to them (`AuthZenConformanceTest`, `AccessDecisionServiceTest`,
`DriftDetectionServiceTest`).

## The real end-to-end path

The full discover, catalog, label, archive, delete flow needs the whole Docker Compose stack and
cloud credentials, so it is a shell harness, not a JUnit test:

- `e2e-tests/cloud-pipeline-e2e.sh` and `e2e-tests/cloud-lifecycle-e2e.sh` (per cloud),
- `e2e-tests/full-lifecycle-e2e.sh` and `e2e-tests/gcp-pipeline-e2e.sh`,
- `e2e-tests/authzen-pdp-verify.ps1` for the PDP against a live Keycloak.

Run one harness at a time: they share container names and ports.

## Skipped and disabled tests

- **`@Disabled`.** Allowed only with an open entry in `docs/OPEN_QUESTIONS.md` that names the
  test class and says who turns it back on. `scripts/ci-guards/pre/disabled-tests-tracked.sh`
  enforces this in CI. Run it locally with `bash scripts/ci-guards/pre/disabled-tests-tracked.sh`.
  `bash scripts/ci-guards/pre/disabled-tests-tracked.sh --list` shows every switched-off test and
  whether an open entry tracks it.
- **Environment gates.** No test depends on a running dev stack. The `@RequiresDocker` gate in
  da-discovery, which skipped tests unless Keycloak (:8180), Kafka (:9092) and Docker were all up
  and then ran them against a stack they could not start in, was removed: its tests were stale
  or duplicates, and the live ones were rewritten as web slices. Docker-backed tests use
  Testcontainers, so they start their own containers.
- **Mockito and time limits.** Test timeouts in da-discovery are 60 seconds because the first
  test in a JVM pays for class loading, Mockito's inline agent and Spring MVC start-up (4 s on an
  idle machine, over 12 s when the CPU is oversubscribed); the old 15 s limit made
  `WebConfigurerTest` fail under parallel load.
