# Spring Component Structure in da-discovery Module

This document outlines the key Spring components within the `da-discovery` module, including Controllers, Services, Repositories, Entities, and Configuration classes.

## Controllers

Controllers handle incoming web requests. Classes annotated with `@RestController` are specialized versions of `@Controller` that assume RESTful behavior (i.e., response bodies are directly bound to the response).

- `com.dalab.discovery.client.rest.ConfigUpdate`: Likely handles updates to configurations via REST.
- `com.dalab.discovery.client.rest.DiscoveryExamplesController`: Provides example discovery-related REST endpoints.
- `com.dalab.discovery.client.rest.DiscoveryJobController`: Manages `DiscoveryJob` entities via REST APIs, allowing for creation, triggering, and status checks of jobs.
- `com.dalab.discovery.client.rest.DiscoveryStatsController`: Exposes discovery statistics via REST.
- `com.dalab.discovery.client.rest.GcpLogConfigController`: Manages GCP log configurations via REST.
- `com.dalab.discovery.client.rest.KafkaController`: Interacts with Kafka, possibly for message production or consumption, exposed via REST.
- `com.dalab.discovery.client.rest.DiscoveryConfigController`: Manages discovery configurations (global and connection-specific) through REST endpoints.
- `com.dalab.discovery.client.cli.ShellController`: Potentially provides REST endpoints to interact with shell commands or CLI features.

Additionally, `@ControllerAdvice` classes provide global exception handling and other cross-cutting concerns for controllers:

- `com.dalab.discovery.common.exception.DiscoveryExceptionHandler`: Global exception handler for discovery-related operations.
- `com.dalab.discovery.client.rest.errors.CrawlerExceptionTranslator`: Translates exceptions from the crawler component for REST responses.
- `com.dalab.discovery.client.rest.errors.ExceptionTranslator`: A more general exception translator for REST APIs.

## Services

Services encapsulate business logic. Key services identified:

- `com.dalab.discovery.config.service.DiscoveryConfigService`: Manages global and connection-specific discovery configurations.
- `com.dalab.discovery.job.service.impl.DiscoveryJobService`: Manages `DiscoveryJob` entities and their execution.
- `com.dalab.discovery.crawler.service.azure.AzureDiscoveryServiceImpl`: Implements `IDiscoveryService` for Microsoft Azure resources.
- `com.dalab.discovery.crawler.service.aws.AWSDiscoveryServiceImpl`: Implements `IDiscoveryService` for Amazon Web Services resources.
- `com.dalab.discovery.common.config.cloud.impl.azure.AzureConfigService`: Provides access to Azure configuration properties.
- `com.dalab.discovery.stats.service.DiscoveryStatsService`: Retrieves discovery statistics.
- `com.dalab.discovery.catalog.service.impl.CatalogServiceImpl`: (Inferred from `DiscoveryServiceConfig`) Likely handles catalog-related operations.
- Interfaces like `com.dalab.discovery.service.config.IDiscoveryConfigService` and `com.dalab.discovery.stats.service.IDiscoveryStatsService` define service contracts.

## Repositories

Repositories are responsible for data access. They are typically interfaces extending Spring Data JPA's `JpaRepository` or similar.

- `com.dalab.discovery.common.model.repository.IMetricsRepository`: Manages `MetricEntity` persistence.
- `com.dalab.discovery.common.model.repository.DiscoveryJobRepository`: Manages `DiscoveryJob` entities.
- `com.dalab.discovery.config.repository.GlobalDiscoveryConfigRepository`: Manages `GlobalDiscoveryConfig` persistence.
- `com.dalab.discovery.config.repository.ConnectionDiscoveryConfigRepository`: (Inferred from `DiscoveryConfigService`) Manages `ConnectionDiscoveryConfig` persistence.
- `com.dalab.discovery.common.model.repository.CloudResourceRepository`: Manages `CloudResource` entities.
- `com.dalab.discovery.common.model.repository.CrawlerUserRepository`: Manages `CrawlerUser` entities.
- The `com.dalab.discovery.common.model.repository` package seems to be a central location for many repository interfaces.

## Entities

Entities represent data that can be persisted in a database.

- `com.dalab.discovery.common.model.MetricEntity`: (Inferred from `IMetricsRepository`) Represents metrics data.
- `com.dalab.discovery.common.model.DiscoveryJob`: (Inferred from `DiscoveryJobRepository`) Represents a discovery job.
- `com.dalab.discovery.config.model.GlobalDiscoveryConfig`: (Inferred from `GlobalDiscoveryConfigRepository`) Represents global discovery configuration.
- `com.dalab.discovery.config.model.ConnectionDiscoveryConfig`: (Inferred from `DiscoveryConfigService`) Represents connection-specific discovery configuration.
- `com.dalab.discovery.common.model.CloudResource`: (Inferred from `CloudResourceRepository`) Represents a discovered cloud resource.
- `com.dalab.discovery.common.model.CrawlerUser`: (Inferred from `CrawlerUserRepository`) Represents a user in the crawler context.
- Entity classes are often found in `*.model` or `*.model.entity` subpackages (e.g., `com.dalab.discovery.common.model.entity`, `com.dalab.discovery.catalog.model`).

## Configuration Classes

Spring `@Configuration` classes are used for bean definitions and application setup.

- `com.dalab.discovery.common.config.DiscoveryServiceConfig`: Configures discovery services, crawlers, log analyzers, and catalog services.
- `com.dalab.discovery.catalog.config.PersistenceConfig`: Base configuration for persistence with Hibernate and Spring Data JPA.
- `com.dalab.discovery.common.config.DevOnlyDataSourceConfig`: Explicit DataSource configuration for the 'dev' profile.
- `com.dalab.discovery.crawler.config.CrawlerDatabaseConfiguration`: JPA configuration related to the crawler.
- `com.dalab.discovery.application.DADiscoveryAgent`: The main Spring Boot application class, which also includes component scanning and other configurations.

## Other Important Components

- **Mappers**: Classes responsible for converting between DTOs and Entities (e.g., `JobStatisticsMapper`, `CloudResourceMapper` mentioned in service constructors). These are often found in `*.mapper` packages.
- **Clients**:
  - `com.dalab.discovery.client.feign`: Contains Feign client interfaces for communication with other services.
  - `com.dalab.discovery.client.cli.DiscoveryCommands`: Shell commands.
- **Jobs & Scheduling**:
  - `com.dalab.discovery.job.*`: Packages related to job definition, execution, and scheduling.
  - `com.dalab.discovery.job.scheduler.IDiscoveryScheduler`: Interface for job scheduling.
- **Events**:
  - `com.dalab.discovery.event.*`: Likely contains application events and listeners.

## Further Exploration Points

- Identify specific Controller classes by searching for `@RestController` and `@Controller` annotations.
- Detail the entities further by examining their fields and relationships.
- Map out the dependencies between services.
- Document the purpose of components in packages like `sd`, `stats`, `log`, `application`, and `crawler` in more detail if required.

## Spring Components in Test Directory (`src/test/java`)

The `da-discovery/src/test/java` directory contains various Spring components and configurations to support unit and integration testing.

### Test Configurations (`@TestConfiguration`)

These classes provide specific bean definitions and configurations tailored for test environments:

- `com.dalab.discovery.sd.config.TestDatabaseConfiguration`: Configures an in-memory H2 database, `EntityManagerFactory`, and `PlatformTransactionManager` for tests. It specifies packages for JPA repositories and entities, ensuring the test environment can correctly load persistence-related components.
- `com.dalab.discovery.sd.config.TestDiscoveryConfiguration`: Provides a mock implementation of `IDiscoveryService` (`MockDiscoveryService`). This is useful for tests that depend on discovery operations but do not need to interact with actual cloud providers.
- `com.dalab.discovery.config.TestDatabaseConfig`: Another database configuration specifically for the "test" profile. It also sets up JPA, H2, and disables schema validation, which can be helpful to avoid conflicts with H2 when the main database uses features like JSONB.
- `com.dalab.discovery.sd.web.rest.errors.TestApplicationConfig`: Contains nested `@TestConfiguration` classes (e.g., `GcpSqlDisabledConfig`, `GcpCoreDisabledConfig`) to conditionally disable certain components, like Google Cloud Platform integrations, during tests.
- `com.dalab.discovery.sd.config.TestSecurityConfiguration`: Sets up a simplified security context for tests, often bypassing complex OAuth2 or IdP (Identity Provider) setups. This can include providing in-memory client registration and JWT decoders.
- `com.dalab.discovery.sd.config.TestConfig`: Offers mock implementations for cloud-specific configuration services (e.g., `AWSConfigService`, `AzureConfigService`, `OracleConfigService`). This allows tests to run without needing actual cloud credentials or connections.
- `com.dalab.discovery.client.rest.TestWebSecurityConfiguration`: Defines a `SecurityFilterChain` specifically for tests. It might configure simpler authentication mechanisms (like HTTP Basic) and define specific authorization rules for test scenarios, often disabling CSRF for ease of testing.
- `com.dalab.discovery.common.config.timezone.HibernateTimeZoneIT.TimeZoneTestConfig`: A nested static `@Configuration` class within an integration test (`HibernateTimeZoneIT`). It sets up `JdbcTemplate` and JPA repositories specifically for testing timezone handling in persistence.

### Integration Test Support

- `com.dalab.discovery.common.IntegrationTest`: This is a custom composite annotation (`@interface`). It bundles `@SpringBootTest` with a predefined set of test configurations (like those mentioned above) and test properties (e.g., disabling Liquibase, setting `spring.jpa.hibernate.ddl-auto=create-drop`). Using such an annotation standardizes the setup for integration tests across the module.
- `@SpringBootTest`: Widely used in classes like `DiscoveryStatsControllerTest`, `DiscoveryConfigControllerIntegrationTest`, `DefaultResourceCrawlerRegistryIntegrationTest`, and `UnifiedJobFrameworkIT`. This annotation boots up the full Spring application context (or a slice of it) for comprehensive integration testing.
- `@DataJpaTest`: Found in tests like `HibernateTimeZoneIT`, this annotation is used for testing JPA repositories. It typically auto-configures an in-memory database and rolls back transactions after each test.
- `@AutoConfigureMockMvc`: Commonly used in REST controller tests (e.g., `DiscoveryJobControllerTest`) to auto-configure `MockMvc`, a powerful tool for testing Spring MVC controllers without needing a running servlet container.
- `@MockBean`: Used extensively across test classes to replace real service dependencies with Mockito mocks. This allows for isolated testing of components by controlling the behavior of their collaborators.

### Test Utilities and Other Notable Components

- `com.dalab.discovery.sd.config.ISqlTestContainer`: An interface that suggests the use of the Testcontainers library. Testcontainers allow for programmable, lightweight, and disposable instances of real databases (or other services) running in Docker containers, providing a more realistic testing environment for database interactions than in-memory databases alone.
- `com.dalab.discovery.sd.config.SpringBootTestClassOrderer`: An implementation of JUnit 5's `ClassOrderer`. This can be used to define a specific order in which test classes are executed, which might be useful if some integration tests have dependencies or side effects that require a certain sequence.
- `com.dalab.discovery.WebConfigurerTestController`: A Spring MVC controller defined within the test sources. It's likely used by `WebConfigurerTest` to test specific web layer configurations or behaviors.

This structure indicates a robust testing strategy, employing both unit tests (with mocks) and integration tests (with a loaded Spring context and potentially Testcontainers) to ensure the reliability of the `da-discovery` module.
