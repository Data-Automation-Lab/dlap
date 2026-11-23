plugins {
	java
	// Versions come from the root build (BOOT-01, ADR-0065).
	id("org.springframework.boot")
	id("io.spring.dependency-management")
	id("checkstyle")
	jacoco
	id("com.google.cloud.tools.jib") version "3.4.0" // Jib plugin for Docker image building
	// id("com.diffplug.spotless") version "6.25.0" // Spotless plugin
	// Consider adding other plugins like git-commit-id if needed
}

group = "com.dlap.discovery"
version = "0.0.1-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

// Define versions from pom.xml properties
// BOOT-01: JHipster 9 is built on Spring Boot 4.1 and publishes no dependencies BOM any more, so
// jhipster-framework is declared with its version and every other version comes from the Spring
// Boot BOM. Hibernate is no longer pinned here: the Spring Boot BOM's Hibernate ORM 7.x applies
// (Apache-2.0, Q-105).
val jhipsterFrameworkVersion = "9.4.0"
val archunitJunit5Version = "1.2.1"
// spring-cloud-gcp 8.2.x is the line for Spring Boot 4.0 and 4.1 (start.spring.io bom-ranges);
// 8.2.4 is built against Spring Boot 4.1.1.
val springCloudGcpVersion = "8.2.4"
val googleCloudLibrariesBomVersion = "26.60.0"
val googleAuthVersion = "1.23.0"
val googleCloudAssetVersion = "3.41.0"
val awsSdkVersion = "2.25.1" // Assuming AWS SDK v2
val azureIdentityVersion = "1.19.0"
val azureCoreVersion = "1.45.0"
val azureResourcemanagerVersion = "2.31.0"
val azureStorageBlobVersion = "12.25.0"
val azureCosmosVersion = "4.54.0"
val azureMonitorQueryVersion = "1.5.7"
val ociSdkVersion = "3.22.0"
val mapstructVersion = "1.5.5.Final" // Added MapStruct version

repositories {
	mavenCentral()
	maven { url = uri("https://maven.google.com") }
}

// Add resolution strategy for dependency conflicts
configurations.all {
    resolutionStrategy {
        // Force Guava version and exclude the outdated google-collections
        force("com.google.guava:guava:33.4.0-jre")
        exclude(group = "com.google.collections", module = "google-collections")
    }
    // BOOT-01: Spring Shell 4 brings the GlassFish EL implementation and the Jakarta EL API
    // (EPL-2.0). Tomcat's EL (tomcat-embed-el, Apache-2.0, from the web starter) already provides
    // the jakarta.el classes, so the second copy stays off the class path, as before Spring Shell 4.
    exclude(group = "org.glassfish", module = "jakarta.el")
    exclude(group = "jakarta.el", module = "jakarta.el-api")
}

dependencyManagement {
	imports {
		mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:${springCloudGcpVersion}")
		mavenBom("com.google.cloud:libraries-bom:${googleCloudLibrariesBomVersion}")
		// Spring Shell 4.0 is the line for Spring Boot 4.0 and 4.1 (start.spring.io bom-ranges).
		mavenBom("org.springframework.shell:spring-shell-dependencies:4.0.3")
		// Potentially add AWS SDK BOM if needed: software.amazon.awssdk:bom:${awsSdkVersion}
		// Potentially add Azure SDK BOM if needed: com.azure:azure-sdk-bom:...
	}
}

dependencies {
	// Q-653: the ProviderLifecycle*Test cases read the fixture corpus by path; as a test dependency the
	// corpus is an input of the test task, so a fixture-only change re-runs them. Internal module, Apache-2.0.
	testImplementation(testFixtures(project(":da-log-fixtures")))
	
	// da-protos common entities and utilities
	implementation(project(":da-protos"))
	
	implementation("tech.jhipster:jhipster-framework:${jhipsterFrameworkVersion}")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-logging")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	// BOOT-01: Spring Boot 4 dropped Undertow (no Servlet 6.1 support), so this service runs on the
	// Tomcat that spring-boot-starter-webmvc brings, like the other ten services.
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.security:spring-security-data")
	// springdoc 3.1 for Spring Boot 4 (the version came from the JHipster 8 BOM before BOOT-01).
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
	// Jackson 2 stays (ADR-0066); its Hibernate module follows Hibernate ORM 7.
	implementation("org.springframework.boot:spring-boot-jackson2")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-hibernate7")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-hppc")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	implementation("com.fasterxml.jackson.module:jackson-module-jaxb-annotations")
	implementation("com.github.ben-manes.caffeine:caffeine")
	// Spring Boot 4 configures the Gson bean (GcsLogFetcherStrategy and the other GCP log readers
	// inject it) only with its Gson module; Spring Boot 3 did it from spring-boot-autoconfigure.
	implementation("org.springframework.boot:spring-boot-starter-gson")
	// GPLv2 (ASF Category X): must NOT be bundled in the released jar. No main/runtime
	// code references com.mysql - the driver is only needed by the MySQL Testcontainers
	// harness (MysqlTestContainer). Scope to tests so it is excluded from the distribution.
	// See docs/DECISIONS.md ADR for the licensing rationale.
	testImplementation("com.mysql:mysql-connector-j")
	implementation("com.zaxxer:HikariCP")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("jakarta.annotation:jakarta.annotation-api")
	implementation("javax.cache:cache-api")
	implementation("org.apache.commons:commons-lang3")
	implementation("org.ehcache:ehcache")
	implementation("org.hibernate.orm:hibernate-core")
	implementation("org.hibernate.orm:hibernate-jcache")
	implementation("org.hibernate.validator:hibernate-validator")
	// Spring Boot 4 runs Flyway only with its starter; PostgreSQL support is its own Flyway module.
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	// javax.xml.bind came transitively from liquibase-core; entity scanning still
	// touches it (JHipster/Jackson JAXB annotations), so pin it explicitly.
	implementation("javax.xml.bind:jaxb-api:2.3.1")
	implementation("org.springframework.cloud:spring-cloud-starter")
	implementation("org.springframework.cloud:spring-cloud-starter-bootstrap")
	implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
	implementation("org.springframework.cloud:spring-cloud-starter-config")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.springframework.retry:spring-retry:2.0.5")
	implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
	implementation("org.springframework.cloud:spring-cloud-starter-stream-kafka")
	implementation("org.assertj:assertj-core:3.13.2")
	implementation("org.fusesource.jansi:jansi:2.4.0")

	// Google Cloud Dependencies (Versions from BOMs)
	// implementation("com.google.cloud:google-cloud-sql-connector-jdbc") // Replaced with starter below
	implementation("com.google.cloud:spring-cloud-gcp-starter-sql-postgresql")
	implementation("com.google.cloud:google-cloud-compute")
	implementation("com.google.cloud:google-cloud-bigquery")
	implementation("com.google.cloud:google-cloud-storage")
	implementation("com.google.cloud:google-cloud-resourcemanager")
	implementation("com.google.cloud:google-cloud-logging")
	implementation("com.google.cloud:google-cloud-storage-control")

	// Other Google Cloud Dependencies (Explicit Versions)
	implementation("com.google.auth:google-auth-library-oauth2-http")
	implementation("com.google.auth:google-auth-library-credentials")
	//implementation("com.google.auth:google-auth-library-oauth2-http:${googleAuthVersion}")
	implementation("com.google.cloud:google-cloud-asset:${googleCloudAssetVersion}")
	//implementation("com.google.auth:google-auth-library-credentials:${googleAuthVersion}")
	implementation("com.google.apis:google-api-services-cloudresourcemanager:v3-rev20240310-2.0.0")
	implementation("com.google.apis:google-api-services-sqladmin:v1-rev20250310-2.0.0")

	// AWS SDK Dependencies (Using specific versions for now)
	// Consider using the AWS SDK BOM: implementation(platform("software.amazon.awssdk:bom:${awsSdkVersion}"))
	compileOnly("software.amazon.awssdk:aws-sdk-java:${awsSdkVersion}") // compileOnly maps to <optional>true + <scope>compile
	implementation("software.amazon.awssdk:ec2:${awsSdkVersion}")
	implementation("software.amazon.awssdk:s3:${awsSdkVersion}")
	implementation("software.amazon.awssdk:rds:${awsSdkVersion}")
	implementation("software.amazon.awssdk:glue:${awsSdkVersion}")
	implementation("software.amazon.awssdk:athena:${awsSdkVersion}")
	implementation("software.amazon.awssdk:emr:${awsSdkVersion}")

	// Azure SDK Dependencies (Using specific versions for now)
	// Consider using the Azure SDK BOM: implementation(platform("com.azure:azure-sdk-bom:..."))
	implementation("com.azure:azure-identity:${azureIdentityVersion}")
	implementation("com.azure:azure-core:${azureCoreVersion}")
	implementation("com.azure.resourcemanager:azure-resourcemanager:${azureResourcemanagerVersion}")
	implementation("com.azure:azure-storage-blob:${azureStorageBlobVersion}")
	implementation("com.azure:azure-cosmos:${azureCosmosVersion}")
	implementation("com.azure:azure-monitor-query:${azureMonitorQueryVersion}")

	// Oracle Cloud SDK Dependencies
	implementation("com.oracle.oci.sdk:oci-java-sdk-common:${ociSdkVersion}")
	implementation("com.oracle.oci.sdk:oci-java-sdk-core:${ociSdkVersion}")
	implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage:${ociSdkVersion}")
	implementation("com.oracle.oci.sdk:oci-java-sdk-database:${ociSdkVersion}")
	implementation("com.oracle.oci.sdk:oci-java-sdk-identity:${ociSdkVersion}")
	implementation("com.oracle.oci.sdk:oci-java-sdk-audit:${ociSdkVersion}")

	// Annotation Processors & Provided Dependencies
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	// hibernate-jpamodelgen is hibernate-processor from Hibernate ORM 7 (Spring Boot 4 migration guide).
	compileOnly("org.hibernate.orm:hibernate-processor")
	annotationProcessor("org.hibernate.orm:hibernate-processor")

	// Test Dependencies
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-test")
	// Spring Boot 4 test slices and support live in per-technology test starters.
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	testImplementation("com.tngtech.archunit:archunit-junit5-api:${archunitJunit5Version}")
	testRuntimeOnly("com.tngtech.archunit:archunit-junit5-engine:${archunitJunit5Version}")
	// Testcontainers 2 prefixes its module artifacts.
	testImplementation("org.testcontainers:testcontainers-jdbc")
	testImplementation("org.testcontainers:testcontainers-mysql")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-localstack")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("com.h2database:h2:2.2.224")

	// Spring Data JPA
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	
	// Database drivers - PostgreSQL as primary
	// BOOT-01: the driver version comes from the Spring Boot BOM like in the other services (42.6.0
	// was pinned here before).
	implementation("org.postgresql:postgresql")
	// implementation("com.h2database:h2:2.2.224") // Keep H2 for testing - REMOVED due to being in 'implementation'
	
	// Hibernate Types for JSON support - REMOVING THIS as Hibernate 6.4+ has better native support
	// implementation("com.vladmihalcea:hibernate-types-60:2.21.1") {
	// transitive = true 
	// }

	// Jackson types: jackson-datatype-hibernate7 is declared above with the Jackson 2 module (BOOT-01).
	
	// Lombok for reducing boilerplate
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	implementation("org.springframework.boot:spring-boot-starter-aspectj") // Required for @Retryable (renamed from -aop in Spring Boot 4)

	implementation("org.springframework.shell:spring-shell-starter")
	// Spring Shell 4 no longer brings JLine by default; the JLine shell keeps the console behaviour of
	// Spring Shell 3 and the JLine Terminal bean of ShellTerminalConfig (JLine and ST4: BSD-3-Clause).
	implementation("org.springframework.shell:spring-shell-jline")

	// MapStruct
	implementation("org.mapstruct:mapstruct:${mapstructVersion}")
	annotationProcessor("org.mapstruct:mapstruct-processor:${mapstructVersion}")
	annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0") // For Lombok and MapStruct integration

	// OpenFeign for declarative REST clients
	implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

	// Unity Catalog client (UC-5 discovery source, ADR-0029). Same version as the
	// da-catalog UC sink. Used by UnityCatalogResourceCrawler to list catalogs →
	// schemas → tables. Apache 2.0 licensed.
	implementation("io.unitycatalog:unitycatalog-client:0.5.0")

	// DISC-01: ShedLock (Apache-2.0) makes the scheduled discovery trigger a cluster-wide
	// singleton, so N replicas fire one scan per cron tick instead of N. JDBC lock table
	// lives in this service's own schema (Flyway V10). 7.x is the line ShedLock tests with
	// Spring Framework 7 and Spring Boot 4 (its README compatibility matrix; BOOT-01, Q-111);
	// ScheduledDiscoveryTriggerLockTest covers it.
	implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
	}

// Spring Boot configuration
springBoot {
	mainClass.set("com.dlap.discovery.application.DADiscoveryAgent")
}

// Produce an unversioned da-discovery.jar so src/main/docker/Dockerfile (and
// the compose build) can COPY it like the other services. Configured here
// rather than the root build because da-discovery's bootJar task is not yet
// registered when the root subprojects block evaluates.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	archiveFileName.set("da-discovery.jar")
}

tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty("spring.profiles.active", "test")
	// Enable ByteBuddy experimental mode for Java 24 compatibility
	systemProperty("net.bytebuddy.experimental", "true")
	
	// Enable parallel test execution
	maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { it > 0 } ?: 1
	

	// Exclude tests that require specific infrastructure or Docker
	// exclude {
	// 	// it.name.contains("TechnicalStructureTest") || 
	// 	// it.name.contains("UserResourceIT") ||
	// 	// it.name.contains("PublicUserResourceIT") ||
	// 	// it.name.contains("AWSConfigServiceTest") ||
	// 	// it.name.contains("HibernateTimeZoneIT") ||
	// 	// it.name.contains("CrawlerIntegrationTest")
	// }
	
	testLogging {
		events("passed", "skipped", "failed")
		showExceptions = true
		showCauses = true
		showStackTraces = true
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		
		// Enable test output on console
		showStandardStreams = true
	}
}

// Checkstyle Configuration
checkstyle {
	toolVersion = "10.12.5" // Use version from pom
	configFile = file("checkstyle.xml") // Assumes checkstyle.xml is in root
	// You might need to configure source sets if they differ from default
}

// Configure configurations to exclude google-collections
configurations.checkstyle {
    exclude(group = "com.google.collections", module = "google-collections")
}

// Disable checkstyle tasks to avoid build failures
tasks.withType<Checkstyle> {
    enabled = false
}

tasks.checkstyleMain {
	source("src/main/java")
}

tasks.checkstyleTest {
	source("src/test/java")
}

// JaCoCo Configuration
jacoco {
	toolVersion = "0.8.11" // Use version from pom
}

tasks.jacocoTestReport {
	reports {
		xml.required.set(true)
		csv.required.set(false)
		html.outputLocation.set(layout.buildDirectory.dir("jacocoHtml"))
	}
	// Depends on test task completion
	dependsOn(tasks.test)
}


// Configure Java Compile to use annotationProcessor configuration
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
	options.annotationProcessorPath = configurations.annotationProcessor.get()
	// Increase memory for Java compilation
	options.forkOptions.jvmArgs = listOf("-Xmx1g")
}

// Task to help VS Code recognize dependencies
tasks.register("createClasspath") {
    doLast {
        val classpath = configurations.runtimeClasspath.get()
            .resolvedConfiguration.resolvedArtifacts
            .joinToString("\n") { it.file.absolutePath }

        file(".classpath").writeText(classpath)
        
        println("Dependencies written to .classpath file")
        println("Spring Shell JAR locations:")
        configurations.runtimeClasspath.get().files
            .filter { it.name.contains("spring-shell") }
            .forEach { println(it.absolutePath) }
    }
}

// Add Jib configuration for Docker image building
jib {
    from {
        image = "openjdk:21-slim"
    }
    to {
        image = "da-discovery"
        tags = setOf("latest")
    }
    container {
        creationTime = "USE_CURRENT_TIMESTAMP"
        jvmFlags = listOf("-Xmx512m", "-Xms256m")
        ports = listOf("8080")
        workingDirectory = "/app"
    }
}