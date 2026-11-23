/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer

// BOOT-01 (ADR-0065): the platform versions live here once. Modules request these plugins without a
// version, so a Spring Boot bump is a one-line change. The Kotlin plugin (1.9.23) was declared but never
// applied to any module; Spring Boot 4 needs Kotlin 2.2 or later, so the unused declaration is dropped.
plugins {
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.protobuf") version "0.9.4" apply false
    // Licence inventory of the shipped (runtime) dependencies; LIC-01. Apache-2.0 (verified from
    // the 3.1.4 POM). The pass/fail policy lives in scripts/ci-guards/pre/dependency-licenses.sh.
    id("com.github.jk1.dependency-license-report") version "3.1.4"
}

licenseReport {
    // Every module plus the root; the plugin resolves the configurations lazily at task time.
    projects = arrayOf(project) + project.subprojects
    // Runtime only: what ships in the jars. Test-scope drivers (the MySQL Connector/J of ADR-0030)
    // are intentionally out of scope.
    configurations = arrayOf("runtimeClasspath")
    excludeOwnGroup = true
    excludeBoms = true
    // Read the licences declared by the module's own POM, not the union with every parent POM: a
    // parent that lists a permissive licence must not hide a copyleft-only child.
    unionParentPomLicenses = false
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    renderers = arrayOf<ReportRenderer>(
        // Machine-readable input of the policy script (all licences of a module, not just one).
        JsonReportRenderer("licenses.json", false),
        InventoryMarkdownReportRenderer("licenses.md", "DLAP runtime dependencies")
    )
}

// -------------------------------------------------------------------------------------------------
// VULN-01 (Q-304): security patch levels of the shipped jars.
//
// The scanner gate (OSV-Scanner, Dependency-Check, Trivy; ADR-0069) fails on a known vulnerability with
// a CVSS score of 7.0 or more. Each row below moves one library to a fixed release, on the line it
// already uses where that line has a fix, and names the advisory it closes. Remove a row once the
// Spring Boot BOM or the module that brings the library reaches that version by itself.
//
//   BOOT_BOM_PROPERTIES  version properties of the Spring Boot BOM (and of every BOM the dependency-
//                        management plugin imports). Set as project properties, which the plugin reads
//                        when it resolves a BOM; the convergence anchors read them too.
//   CONSTRAINTS          libraries the Spring Boot BOM does not manage: a Gradle constraint that raises
//                        the version wherever the library is resolved, in every module.
//   FORCED               libraries a module pins below the fixed release with an explicit version. A
//                        constraint or resolutionStrategy.force cannot raise those: the dependency-
//                        management plugin turns the module's own version into its managed version and
//                        applies it in a dependency resolve rule. A rule registered after the plugin's
//                        (afterEvaluate) runs last and sets the patched version.
//   GOOGLE_CLOUD_LIBRARIES_BOM  the Google Cloud libraries BOM for da-discovery (gRPC, see below).
// -------------------------------------------------------------------------------------------------

/** The security patch levels; see the comment block above. */
object SecurityPatches {
    /** Guava's patched release (CVE-2026-102554, fixed in 33.7.2); the convergence check aligns on it. */
    const val GUAVA_VERSION = "33.7.2"

    /**
     * One raised library: the group:name, the version, the advisories it closes, and the project paths
     * it applies to (empty: every project).
     */
    data class Patch(val module: String, val version: String, val reason: String, val projects: Set<String> = emptySet()) {
        /** Whether the patch applies to the project at [path]. */
        fun appliesTo(path: String): Boolean = projects.isEmpty() || path in projects
    }

    /** Spring Boot BOM properties raised to a later patch release on the same line. */
    val BOOT_BOM_PROPERTIES = linkedMapOf(
        // CVE-2026-65905, CVE-2026-65182, CVE-2026-68525 (fixed in 11.0.25).
        "tomcat.version" to "11.0.27",
        // Jackson 2: CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777
        // (fixed in 2.21.7). jackson-annotations stays on its 2.21 line.
        "jackson-2-bom.version" to "2.21.8",
        // Jackson 3: the same five advisories (fixed in 3.1.7).
        "jackson-bom.version" to "3.1.8",
        // grpc-netty-shaded 1.83.1 bundles Netty 4.2.15.Final: CVE-2026-44891, -56818, -56820, -56821,
        // -56822, -59898, -59899, -59900, -59901, -59919, -59920, -59921, -73507, -73508 (fixed in Netty
        // 4.2.16) and CVE-2026-59902, -59903, -62380, -75595, -75596 (fixed in 4.2.17), 19 in all, found by
        // Dependency-Check. gRPC 1.85.1 shades Netty 4.2.17.Final, the release the BOM's own
        // Netty already is (read from META-INF/io.grpc.netty.shaded.io.netty.versions.properties).
        "grpc-java.version" to "1.85.1",
    )

    /** The convergence anchor (DEPS-01) of each raised property, so the declared alignment follows it. */
    private val ANCHOR_PROPERTIES = mapOf(
        "com.fasterxml.jackson.core:jackson-databind" to "jackson-2-bom.version",
        "tools.jackson.core:jackson-databind" to "jackson-bom.version",
        "io.grpc:grpc-api" to "grpc-java.version",
    )

    /** The raised version of a convergence anchor (group:name), or null when the BOM's version stands. */
    fun anchorVersion(coordinate: String): String? = ANCHOR_PROPERTIES[coordinate]?.let { BOOT_BOM_PROPERTIES[it] }

    /** Raised wherever they are resolved; none of them is managed by the Spring Boot BOM. */
    val CONSTRAINTS = listOf(
        Patch("commons-io:commons-io", "2.20.0",
            "CVE-2024-47554 (fixed in 2.14.0); apache-mime4j 0.8.9 under the Keycloak admin client brought 2.11.0"),
        Patch("at.yawk.lz4:lz4-java", "1.11.4",
            "CVE-2026-106451 (fixed in 1.11.4, no fix on 1.10); kafka-clients 4.2.1 brings 1.10.1"),
        Patch("org.bouncycastle:bcprov-jdk15to18", "1.86",
            "CVE-2025-14813, CVE-2026-8763, CVE-2026-5598, CVE-2026-13506 (fixed in 1.85); the OCI SDK brings 1.74"),
        Patch("org.bouncycastle:bcpkix-jdk15to18", "1.86", "kept on the bcprov-jdk15to18 release"),
        Patch("org.bouncycastle:bcutil-jdk15to18", "1.86", "kept on the bcprov-jdk15to18 release"),
        Patch("com.azure:azure-security-keyvault-keys", "4.11.3",
            "CVE-2026-33117 (fixed in 4.10.6) and CVE-2026-85922 (fixed in 4.11.2); azure-resourcemanager 2.31 " +
                "and 2.36 bring 4.7.0 and 4.8.0"),
        Patch("com.azure:azure-security-keyvault-secrets", "4.11.3", "CVE-2026-85922 (fixed in 4.11.2)"),
        Patch("com.google.guava:guava", "$GUAVA_VERSION-jre",
            "CVE-2026-102554 (DoS on deserialising CompactHashMap and CompactHashSet, fixed in 33.7.2); the Google Cloud " +
                "libraries BOM 26.90.0 and the Google SDKs bring 33.6.0"),
        Patch("org.webjars:swagger-ui", "5.34.1",
            "CVE-2026-116803 and CVE-2026-116804 in the bundled DOMPurify 3.4.13 (fixed in 3.4.17, bundled from " +
                "swagger-ui 5.34.1); springdoc-openapi 3.1.1 brings swagger-ui 5.32.14"),
        Patch("org.xerial.snappy:snappy-java", "1.1.10.9",
            "CVE-2026-108106 (fixed in 1.1.10.9) and CVE-2026-93451 (affects through 1.1.10.8); kafka-clients 4.2.1 brings 1.1.10.7"),
    )

    /** Forced over a module's own lower pin (see the comment block above). */
    val FORCED = listOf(
        Patch("org.assertj:assertj-core", "3.27.7",
            "CVE-2026-24400 (fixed in 3.27.7); da-discovery pins 3.13.2 at implementation scope, so it ships",
            setOf(":da-discovery")),
        // The admin client has its own release line since Keycloak 25 (the keycloak-client project) and
        // no longer depends on the server's keycloak-core, which carries CVE-2024-10039 (fixed in 26.0.6)
        // at 24.0.4. Package and class names are unchanged.
        Patch("org.keycloak:keycloak-admin-client", "26.0.12",
            "CVE-2024-10039 in keycloak-core 24.0.4; da-admin-service pins admin client 24.0.4",
            setOf(":da-admin-service")),
        // keyvault-keys 4.11.3 is built on azure-core 1.60.0; da-discovery pins azure-core 1.45.0.
        Patch("com.azure:azure-core", "1.60.0",
            "the azure-core release azure-security-keyvault-keys 4.11.3 is built on", setOf(":da-discovery")),
        // The Spring Shell BOM manages JLine; the jline jar bundles every JLine module, remote-telnet too.
        Patch("org.jline:jline", "3.30.17",
            "CVE-2026-56740, CVE-2026-56741 (remote-telnet, fixed in 3.30.14); Spring Shell 4.0.3 brings 3.30.13",
            setOf(":da-discovery")),
        // da-discovery and da-logingest import the Google Cloud libraries BOM, which manages Guava 33.6.0:
        // the dependency-management plugin applies that managed version over the CONSTRAINTS row above.
        Patch("com.google.guava:guava", "$GUAVA_VERSION-jre",
            "CVE-2026-102554; the Google Cloud libraries BOM 26.90.0 manages Guava 33.6.0 in these two modules",
            setOf(":da-discovery", ":da-logingest")),
    )

    /**
     * da-discovery imports the Google Cloud libraries BOM 26.60.0 after the Spring Boot BOM, which ships
     * gRPC 1.70.0 (grpc-netty-shaded CVE-2025-55163, fixed in 1.75.0) and protobuf 4.29.4. The root build
     * imports the BOM da-logingest uses and then the Spring Boot BOM again, after the module's own
     * imports, so gRPC, protobuf and Guava follow the same alignment as every other boot jar (Q-292).
     * The module's force of Guava 33.4.0-jre no longer decides: the plugin's managed Guava wins, and the
     * Guava row of CONSTRAINTS raises it to the patched release.
     */
    const val GOOGLE_CLOUD_LIBRARIES_BOM = "com.google.cloud:libraries-bom:26.90.0"
}

allprojects {
    group = "com.dlap"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
        // BOOT-01 (Q-107): the Confluent repository is gone with the unused kafka-protobuf-serializer.
        maven { url = uri("https://maven.google.com") }
    }
}

subprojects {
    // BOOT-01 (ADR-0065): the Spring Cloud release train that matches Spring Boot 4.0 and 4.1
    // (start.spring.io lists 2025.1.3 for Spring Boot >=4.0.0 and <4.2.0-M1). It is imported wherever
    // the dependency-management plugin is applied, so no module pins a Spring Cloud artifact version.
    pluginManager.withPlugin("io.spring.dependency-management") {
        configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.3")
            }
        }
    }

    // VULN-01 (Q-304): the security patch levels (SecurityPatches). The BOM properties are project
    // properties, which the dependency-management plugin reads when it resolves an imported BOM.
    SecurityPatches.BOOT_BOM_PROPERTIES.forEach { (property, version) -> extra[property] = version }
    pluginManager.withPlugin("java") {
        SecurityPatches.CONSTRAINTS.forEach { patch ->
            dependencies.constraints.add("implementation", "${patch.module}:${patch.version}") {
                because(patch.reason)
            }
        }
        val forced = SecurityPatches.FORCED.filter { it.appliesTo(path) }.associate { it.module to it.version }
        if (forced.isNotEmpty()) {
            afterEvaluate {
                configurations.configureEach {
                    resolutionStrategy.eachDependency {
                        forced["${requested.group}:${requested.name}"]?.let { useVersion(it); because("VULN-01 security patch") }
                    }
                }
            }
        }
    }
    if (path == ":da-discovery") {
        // After the module's own dependencyManagement block, so these imports come last and win.
        afterEvaluate {
            configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
                imports {
                    mavenBom(SecurityPatches.GOOGLE_CLOUD_LIBRARIES_BOM)
                    mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
                }
            }
        }
    }

    // Apply common plugins to all subprojects except da-protos / da-llm-client (libraries)
    // and new-ui (standalone Gradle + embedded CRA)
    if (name != "da-protos" && name != "da-llm-client" && name != "new-ui" && name != "da-discovery") {
        apply(plugin = "org.springframework.boot")
        apply(plugin = "io.spring.dependency-management")
        apply(plugin = "java")
        
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        
        // Performance optimization for Java compilation
        tasks.withType<JavaCompile>().configureEach {
            options.isIncremental = true
            options.isFork = true
            options.forkOptions.jvmArgs?.addAll(listOf("-Xmx1g"))
        }
        
        dependencies {
            // Common dependencies for all microservices
            add("implementation", project(":da-protos"))
            // Spring Boot 4 starter names (ADR-0065): -web is now -webmvc, -aop is now -aspectj (the
            // resilience4j @CircuitBreaker aspects need AspectJ) and the OAuth2 starters carry a
            // security- prefix. Flyway and Kafka now need their own starters: with the bare libraries
            // the migrations would not run and no KafkaTemplate or listener factory would exist.
            add("implementation", "org.springframework.boot:spring-boot-starter-webmvc")
            add("implementation", "org.springframework.boot:spring-boot-starter-data-jpa")
            add("implementation", "org.springframework.boot:spring-boot-starter-flyway")
            // Flyway 10 and later ship the PostgreSQL support as its own module (Apache-2.0).
            add("implementation", "org.flywaydb:flyway-database-postgresql")
            add("implementation", "io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
            add("implementation", "org.springframework.boot:spring-boot-starter-aspectj")
            add("implementation", "org.springframework.boot:spring-boot-starter-security")
            add("implementation", "org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
            add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
            // Jackson 2 stays the JSON library of the services on Spring Boot 4.1 (ADR-0066): the
            // deprecated spring-boot-jackson2 module configures the Jackson 2 ObjectMapper the code
            // injects, and spring.http.converters.preferred-json-mapper=jackson2 keeps Spring MVC and
            // the Feign clients on it, so no JSON body changes. Spring Boot drops the module in a later 4.x.
            add("implementation", "org.springframework.boot:spring-boot-jackson2")
            // OBS-01: the Prometheus registry. Without it /actuator/prometheus does not exist and
            // the platform Prometheus has nothing to scrape. Apache-2.0, version from the Spring Boot
            // BOM (da-discovery declares the same artifact in its own build file).
            add("implementation", "io.micrometer:micrometer-registry-prometheus")
            add("implementation", "org.springframework.boot:spring-boot-starter-validation")
            add("implementation", "org.springframework.boot:spring-boot-starter-kafka")
            // springdoc 3.1 is the line for Spring Boot 4.0 and 4.1 (start.spring.io dependency range).
            // It also ends Q-272: the old swagger-annotations 2.1.10 came with the Confluent serializer.
            add("implementation", "org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
            add("implementation", "org.mapstruct:mapstruct:1.5.5.Final")
            // BOOT-01 (Q-107): io.confluent:kafka-protobuf-serializer was never referenced by any source
            // or configuration file and is removed with its repository.
            
            // Database
            add("runtimeOnly", "org.postgresql:postgresql")
            
            // Annotation processors
            add("compileOnly", "org.projectlombok:lombok")
            add("annotationProcessor", "org.projectlombok:lombok")
            add("annotationProcessor", "org.springframework.boot:spring-boot-configuration-processor")
            add("annotationProcessor", "org.mapstruct:mapstruct-processor:1.5.5.Final")
            add("annotationProcessor", "org.projectlombok:lombok-mapstruct-binding:0.2.0")
            
            // Test dependencies
            // Spring Boot 4 splits the test slices into per-technology test starters: @WebMvcTest and
            // MockMvc, @DataJpaTest, the Spring Security test support, Kafka test and the metrics test
            // annotations each come from their own starter. Testcontainers 2 prefixes its modules.
            add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
            add("testImplementation", "org.springframework.boot:spring-boot-starter-webmvc-test")
            add("testImplementation", "org.springframework.boot:spring-boot-starter-data-jpa-test")
            add("testImplementation", "org.springframework.boot:spring-boot-starter-security-test")
            add("testImplementation", "org.springframework.boot:spring-boot-starter-kafka-test")
            add("testImplementation", "org.springframework.boot:spring-boot-starter-actuator-test")
            // TestRestTemplate (spring-boot-resttestclient, from the webmvc test starter) needs the
            // RestTemplate support module at run time.
            add("testImplementation", "org.springframework.boot:spring-boot-restclient")
            add("testImplementation", "org.testcontainers:testcontainers-postgresql")
            add("testImplementation", "org.testcontainers:testcontainers-junit-jupiter")
            add("testImplementation", "org.springframework.boot:spring-boot-testcontainers")
        }
    }
    
    // Optimize test execution for parallel execution
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 2
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // JVM args for test execution
        jvmArgs("-Xmx512m", "-XX:+UseG1GC")
    }
    
    // Configure BootJar task for all Spring Boot projects so each produces an
    // unversioned <name>.jar that the per-service Dockerfile COPYs. da-protos
    // and da-llm-client (libraries) and new-ui (frontend) have no bootJar;
    // da-discovery packages its fat jar without the bootJar task (see its
    // build.gradle.kts).
    if (name != "da-protos" && name != "da-llm-client" && name != "new-ui" && name != "da-discovery") {
        tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
            archiveFileName.set("${project.name}.jar")
        }
    }
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.14.6"
    distributionType = Wrapper.DistributionType.ALL
} 

// The report plugin reads other projects' models while it runs, which the build cache cannot
// replay (the plugin marks itself configuration-cache incompatible; run it with
// --no-configuration-cache to keep the log quiet). Always regenerate: it is an audit artefact,
// not a build input.
tasks.named("generateLicenseReport") {
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("The licence inventory must always reflect the current dependency graph") { true }
}

// -------------------------------------------------------------------------------------------------
// DEPS-01 (critic item 13): dependency convergence of the boot jars.
//
// protobuf-java, gRPC, Netty, Jackson and Guava are the libraries that clash when the Spring Boot
// stack and the cloud SDKs share one jar. For every module whose bootJar is enabled, this check walks
// the resolved runtimeClasspath and fails when a library of these families resolves to another
// version than the declared alignment below. Every boot jar is held to the same alignment, so two
// modules that disagree, or two members of one family that disagree inside one jar, fail as well.
//
// The declared alignment:
//   - protobuf (com.google.protobuf:protobuf-*), gRPC (io.grpc:grpc-*), Netty (io.netty:netty-*, with
//     netty-tcnative on its own line), Jackson 2 (com.fasterxml.jackson*, with jackson-annotations on
//     its major.minor line) and Jackson 3 (tools.jackson*): the versions the Spring Boot BOM of the
//     plugins block manages. They are read by resolving one anchor library of each family against
//     that BOM, so a Spring Boot upgrade moves the alignment with it.
//   - Guava (com.google.guava:guava): the Spring Boot BOM does not manage it, so its line is declared
//     here: the patched release of SecurityPatches (VULN-01), which sits above the 33.6.0 the Google
//     Cloud libraries BOM of da-logingest brings. Guava publishes each release
//     as -jre and -android; on a JVM, Gradle's variant selection ships the -jre jar for both (the
//     33.6.0-android module metadata selects guava-33.6.0-jre.jar), so the suffix is not compared.
//
// CONVERGENCE_EXCEPTIONS lists the known divergences. A row allows exactly one version of one family
// in one module, and a row that no longer matches anything fails the check, so the list only shrinks.
//
//   ./gradlew verifyDependencyConvergence     the check; part of the root check and build tasks
//   ./gradlew dependencyConvergenceSelfTest   the policy against injected conflicts, no resolution;
//                                             verifyDependencyConvergence runs it first
//   bash scripts/ci-guards/pre/dependency-convergence.sh [--prove]
//                                             the CI hook; --prove injects a real conflicting
//                                             dependency into a boot jar and shows the check fails
// The report of the last run is build/reports/dependency-convergence/report.tsv.
// -------------------------------------------------------------------------------------------------

/** The convergence policy: pure functions over resolved coordinates, so the self-test can drive it. */
object DependencyConvergence {
    const val BOOT_BOM = "the Spring Boot BOM"

    /** Guava's declared line: the patched release of SecurityPatches (the Google Cloud libraries BOM brings 33.6.0). */
    const val GUAVA_ALIGNMENT = SecurityPatches.GUAVA_VERSION

    /** One watched family: the coordinates it covers and the anchor or declaration that sets its version. */
    class Family(val name: String, val anchor: String?, val declared: String?, val test: (String, String) -> Boolean) {
        /** Where the aligned version comes from, for the failure message. */
        val source: String get() = if (declared != null) "declared in the root build" else "$BOOT_BOM via $anchor"
    }

    /** First match wins, so the narrow families come before the wide ones. */
    val FAMILIES = listOf(
        Family("jackson-annotations", "com.fasterxml.jackson.core:jackson-annotations", null) { g, n ->
            g == "com.fasterxml.jackson.core" && n == "jackson-annotations"
        },
        Family("jackson2", "com.fasterxml.jackson.core:jackson-databind", null) { g, n ->
            (g == "com.fasterxml.jackson" || g.startsWith("com.fasterxml.jackson.")) && n.startsWith("jackson-")
        },
        Family("jackson3", "tools.jackson.core:jackson-databind", null) { g, n ->
            (g == "tools.jackson" || g.startsWith("tools.jackson.")) && n.startsWith("jackson-")
        },
        Family("netty-tcnative", "io.netty:netty-tcnative-classes", null) { g, n ->
            g == "io.netty" && n.startsWith("netty-tcnative")
        },
        Family("netty", "io.netty:netty-common", null) { g, n -> g == "io.netty" && n.startsWith("netty-") },
        Family("grpc", "io.grpc:grpc-api", null) { g, n -> g == "io.grpc" && n.startsWith("grpc-") },
        Family("protobuf", "com.google.protobuf:protobuf-java", null) { g, n ->
            g == "com.google.protobuf" && n.startsWith("protobuf-")
        },
        Family("guava", null, GUAVA_ALIGNMENT) { g, n -> g == "com.google.guava" && n == "guava" },
    )

    /** The anchors resolved against the Spring Boot BOM to read the aligned versions. */
    val ANCHORS: List<String> get() = FAMILIES.mapNotNull { it.anchor }

    /** The watched family of a group:name coordinate, or null when it is not watched. */
    fun familyOf(coordinate: String): Family? {
        val parts = coordinate.split(':')
        if (parts.size < 2) return null
        return FAMILIES.firstOrNull { it.test(parts[0], parts[1]) }
    }

    /** Guava's -jre and -android builds of one release ship the same -jre jar on a JVM. */
    fun normalise(family: String, version: String): String =
        if (family == "guava") version.removeSuffix("-jre").removeSuffix("-android") else version

    /** A known divergence: [module] (a project path) may resolve [family] to [version] only. */
    data class Exception(val module: String, val family: String, val version: String, val reason: String)

    /** One resolved library of one boot jar. */
    data class Observation(val module: String, val coordinate: String, val version: String)

    /** The verdict of one run: the violations, and every watched library with its status. */
    data class Result(val violations: List<String>, val rows: List<List<String>>)

    /**
     * Applies the policy.
     *
     * @param observed every resolved library of every boot jar; unwatched coordinates are ignored
     * @param anchors the version of each anchor coordinate under the Spring Boot BOM
     * @param exceptions the known divergences
     * @return the violations (empty when the boot jars converge) and the report rows
     */
    fun evaluate(observed: List<Observation>, anchors: Map<String, String>, exceptions: List<Exception>): Result {
        val violations = mutableListOf<String>()
        if (observed.isEmpty()) violations += "no boot jar runtime classpath was resolved; the check would be vacuous"
        val aligned = mutableMapOf<String, String>()
        for (family in FAMILIES) {
            val version = family.declared ?: anchors[family.anchor]
            if (version == null) {
                violations += "family ${family.name}: anchor ${family.anchor} does not resolve under $BOOT_BOM"
            } else {
                aligned[family.name] = normalise(family.name, version)
            }
        }
        val watched = observed.mapNotNull { o -> familyOf(o.coordinate)?.let { f -> f to o } }
        val rows = mutableListOf<List<String>>()
        val usedExceptions = mutableSetOf<Exception>()
        for ((family, o) in watched.sortedWith(compareBy({ it.second.module }, { it.second.coordinate }))) {
            val want = aligned[family.name] ?: continue
            val got = normalise(family.name, o.version)
            val exception = exceptions.firstOrNull {
                it.module == o.module && it.family == family.name && normalise(family.name, it.version) == got
            }
            val status = when {
                got == want -> "aligned"
                exception != null -> {
                    usedExceptions += exception
                    "excepted"
                }
                else -> "VIOLATION"
            }
            rows += listOf(o.module, o.coordinate, o.version, family.name, want, status)
            if (status == "VIOLATION") {
                val others = watched.filter { (_, other) -> other.coordinate == o.coordinate && other.module != o.module }
                    .groupBy({ normalise(family.name, it.second.version) }, { it.second.module })
                    .entries.joinToString("; ") { (v, mods) -> "$v in ${mods.sorted().joinToString(", ")}" }
                violations += "${o.module}: ${o.coordinate} resolves ${o.version}; the declared alignment of family " +
                    "${family.name} is $want (${family.source})" + (if (others.isEmpty()) "" else "; other boot jars: $others")
            }
        }
        for (e in exceptions) {
            // A family without an alignment is reported above; its rows were not compared.
            if (e !in usedExceptions && e.family in aligned) {
                violations += "stale exception: ${e.module} no longer resolves family ${e.family} to ${e.version}; " +
                    "remove its CONVERGENCE_EXCEPTIONS row (${e.reason})"
            }
        }
        return Result(violations, rows)
    }

    /**
     * Drives [evaluate] with injected conflicts and returns the failed cases; empty means the policy
     * catches every case. Each case names its input and the answer the policy must give.
     */
    fun selfTest(): List<String> {
        val anchors = mapOf(
            "com.fasterxml.jackson.core:jackson-annotations" to "2.21",
            "com.fasterxml.jackson.core:jackson-databind" to "2.21.5",
            "tools.jackson.core:jackson-databind" to "3.1.5",
            "io.netty:netty-tcnative-classes" to "2.0.81.Final",
            "io.netty:netty-common" to "4.2.17.Final",
            "io.grpc:grpc-api" to "1.83.1",
            "com.google.protobuf:protobuf-java" to "4.35.1",
        )
        val clean = listOf(
            Observation(":a", "com.google.protobuf:protobuf-java", "4.35.1"),
            Observation(":a", "com.google.protobuf:protobuf-java-util", "4.35.1"),
            Observation(":a", "io.grpc:grpc-api", "1.83.1"),
            Observation(":a", "io.grpc:grpc-xds", "1.83.1"),
            Observation(":a", "io.netty:netty-handler", "4.2.17.Final"),
            Observation(":a", "io.netty:netty-tcnative-boringssl-static", "2.0.81.Final"),
            Observation(":a", "com.fasterxml.jackson.core:jackson-annotations", "2.21"),
            Observation(":a", "com.fasterxml.jackson.core:jackson-databind", "2.21.5"),
            Observation(":a", "com.fasterxml.jackson.datatype:jackson-datatype-jsr310", "2.21.5"),
            Observation(":a", "com.fasterxml.jackson:jackson-bom", "2.21.5"),
            Observation(":a", "tools.jackson.core:jackson-databind", "3.1.5"),
            Observation(":a", "com.google.guava:guava", "33.7.2-jre"),
            Observation(":a", "com.google.cloud:google-cloud-storage", "2.99.0"),
            Observation(":b", "com.google.protobuf:protobuf-java", "4.35.1"),
            Observation(":b", "com.google.guava:guava", "33.7.2-android"),
        )
        val failures = mutableListOf<String>()
        fun expect(name: String, observed: List<Observation>, exceptions: List<Exception>, vararg needles: String) {
            val v = evaluate(observed, anchors, exceptions).violations
            val ok = if (needles.isEmpty()) v.isEmpty() else v.isNotEmpty() && needles.all { n -> v.any { it.contains(n) } }
            if (!ok) failures += "$name: wanted ${if (needles.isEmpty()) "no violation" else needles.toList()}, got $v"
        }
        fun replace(module: String, coordinate: String, version: String) =
            clean.filterNot { it.module == module && it.coordinate == coordinate } + Observation(module, coordinate, version)

        expect("aligned jars, guava -jre and -android, an unwatched library", clean, emptyList())
        expect("an injected protobuf conflict", replace(":a", "com.google.protobuf:protobuf-java", "3.25.5"), emptyList(),
            ":a: com.google.protobuf:protobuf-java resolves 3.25.5", "family protobuf is 4.35.1", "other boot jars: 4.35.1 in :b")
        expect("one gRPC member split from its family", replace(":a", "io.grpc:grpc-xds", "1.70.0"), emptyList(),
            ":a: io.grpc:grpc-xds resolves 1.70.0", "family grpc is 1.83.1")
        expect("Guava differs between modules", replace(":b", "com.google.guava:guava", "32.1.3-jre"), emptyList(),
            ":b: com.google.guava:guava resolves 32.1.3-jre", "other boot jars: 33.7.2 in :a")
        expect("a Netty conflict", replace(":a", "io.netty:netty-handler", "4.1.118.Final"), emptyList(),
            ":a: io.netty:netty-handler resolves 4.1.118.Final")
        expect("netty-tcnative stays on its own line", replace(":a", "io.netty:netty-tcnative-boringssl-static", "4.2.17.Final"),
            emptyList(), "family netty-tcnative is 2.0.81.Final")
        expect("a Jackson 2 module conflict", replace(":a", "com.fasterxml.jackson.datatype:jackson-datatype-jsr310", "2.19.2"),
            emptyList(), "jackson-datatype-jsr310 resolves 2.19.2")
        expect("a Jackson 3 conflict", replace(":a", "tools.jackson.core:jackson-databind", "3.0.4"), emptyList(),
            "tools.jackson.core:jackson-databind resolves 3.0.4")
        expect("jackson-annotations keeps its major.minor line",
            replace(":a", "com.fasterxml.jackson.core:jackson-annotations", "2.21.5"), emptyList(), "jackson-annotations resolves 2.21.5")
        val protobufException = Exception(":a", "protobuf", "4.29.4", "self-test")
        val excepted = replace(":a", "com.google.protobuf:protobuf-java", "4.29.4")
            .filterNot { it.module == ":a" && it.coordinate == "com.google.protobuf:protobuf-java-util" }
        expect("an exception allows exactly its version", excepted, listOf(protobufException))
        expect("an exception does not allow another version", replace(":a", "com.google.protobuf:protobuf-java", "4.28.0"),
            listOf(protobufException), "resolves 4.28.0", "stale exception: :a")
        expect("an exception is per module", replace(":b", "com.google.protobuf:protobuf-java", "4.29.4"),
            listOf(protobufException), ":b: com.google.protobuf:protobuf-java resolves 4.29.4")
        expect("a stale exception fails", clean, listOf(protobufException), "stale exception: :a no longer resolves family protobuf")
        expect("no boot jar fails instead of passing", emptyList(), emptyList(), "vacuous")
        val missingAnchor = evaluate(clean, anchors - "io.grpc:grpc-api", emptyList()).violations
        if (missingAnchor.none { it.contains("anchor io.grpc:grpc-api does not resolve") }) {
            failures += "a missing anchor: wanted a violation, got $missingAnchor"
        }
        return failures
    }
}

/**
 * The known divergences. Each row names the module, the family, the one version it may resolve and
 * the reason. Remove the row in the change that aligns the module.
 */
val CONVERGENCE_EXCEPTIONS = listOf<DependencyConvergence.Exception>(
    // Empty since VULN-01: da-discovery's protobuf 4.29.4, gRPC 1.70.0 and Guava 33.4.0-jre rows went
    // when the root build moved it onto the Google Cloud libraries BOM 26.90.0 followed by the Spring
    // Boot BOM (SecurityPatches.GOOGLE_CLOUD_LIBRARIES_BOM, Q-292).
)

/** Runs [DependencyConvergence.selfTest] and fails when the policy misses an injected conflict. */
abstract class DependencyConvergenceSelfTest : DefaultTask() {
    /** Runs the self-test cases. */
    @TaskAction
    fun run() {
        val failures = DependencyConvergence.selfTest()
        if (failures.isNotEmpty()) {
            throw GradleException("dependency convergence self-test failed:\n  " + failures.joinToString("\n  "))
        }
        logger.lifecycle("dependency convergence self-test OK")
    }
}

/** The convergence check over the resolved runtime classpath of every boot jar. */
abstract class VerifyDependencyConvergence : DefaultTask() {
    /** Project path to the root of its resolved runtimeClasspath, for each module with an enabled bootJar. */
    @get:Internal
    abstract val bootJars: MapProperty<String, ResolvedComponentResult>

    /** The anchors resolved against the Spring Boot BOM. */
    @get:Internal
    abstract val alignment: Property<ResolvedComponentResult>

    /** The known divergences, each as module|family|version|reason. */
    @get:Input
    abstract val exceptions: ListProperty<String>

    /** Every watched library of every boot jar with its status. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    /** Walks the graphs, applies the policy, writes the report and fails on a violation. */
    @TaskAction
    fun verify() {
        val anchors = components(alignment.get()).mapNotNull { it.moduleVersion }
            .associate { "${it.group}:${it.name}" to it.version }
        val jars = bootJars.get()
        val observed = jars.flatMap { (module, root) ->
            components(root).mapNotNull { it.moduleVersion }
                .map { DependencyConvergence.Observation(module, "${it.group}:${it.name}", it.version) }
        }
        val known = exceptions.get().map {
            val (module, family, version, reason) = it.split('|')
            DependencyConvergence.Exception(module, family, version, reason)
        }
        val result = DependencyConvergence.evaluate(observed, anchors, known)
        val file = report.get().asFile
        file.parentFile.mkdirs()
        val header = listOf("module", "coordinate", "resolved", "family", "aligned", "status")
        file.writeText((listOf(header) + result.rows).joinToString("\n", postfix = "\n") { it.joinToString("\t") })
        if (result.violations.isNotEmpty()) {
            throw GradleException("dependency convergence failed (${result.violations.size}):\n  " +
                result.violations.joinToString("\n  ") + "\nReport: $file")
        }
        val families = DependencyConvergence.FAMILIES.joinToString(", ") { f -> "${f.name} ${f.declared ?: anchors[f.anchor]}" }
        logger.lifecycle("dependency convergence OK: ${jars.size} boot jars (${jars.keys.sorted().joinToString(" ")}), " +
            "${result.rows.size} watched libraries, ${result.rows.count { it[5] == "excepted" }} excepted; $families")
    }

    /** Every component reachable from [root] through real (non-constraint) edges. */
    private fun components(root: ResolvedComponentResult): Set<ResolvedComponentResult> {
        val seen = linkedSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            for (dependency in queue.removeFirst().dependencies) {
                if (dependency is ResolvedDependencyResult && !dependency.isConstraint && seen.add(dependency.selected)) {
                    queue.add(dependency.selected)
                }
            }
        }
        return seen
    }
}

// The root project gets the lifecycle tasks (check, build), so "./gradlew build" runs the check, and the
// JVM ecosystem rules, without which Gradle does not read a Maven BOM as a platform.
apply(plugin = "base")
apply(plugin = "jvm-ecosystem")

val dependencyConvergenceSelfTest = tasks.register<DependencyConvergenceSelfTest>("dependencyConvergenceSelfTest") {
    group = "verification"
    description = "Proves the dependency convergence policy fails on injected conflicts (DEPS-01)."
}

val verifyDependencyConvergence = tasks.register<VerifyDependencyConvergence>("verifyDependencyConvergence") {
    group = "verification"
    description = "Fails when protobuf, gRPC, Netty, Jackson or Guava leave the declared alignment in a boot jar (DEPS-01)."
    dependsOn(dependencyConvergenceSelfTest)
    exceptions.set(CONVERGENCE_EXCEPTIONS.map { "${it.module}|${it.family}|${it.version}|${it.reason}" })
    report.set(layout.buildDirectory.file("reports/dependency-convergence/report.tsv"))
    outputs.upToDateWhen { false }
    val anchorGraph = configurations.detachedConfiguration(
        *(listOf(dependencies.enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)) +
            DependencyConvergence.ANCHORS.map { dependencies.create(it) }).toTypedArray()
    )
    anchorGraph.attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
    // VULN-01: a Spring Boot BOM property raised in SecurityPatches moves its anchor with it.
    anchorGraph.resolutionStrategy.eachDependency {
        SecurityPatches.anchorVersion("${requested.group}:${requested.name}")?.let { useVersion(it) }
    }
    alignment.set(anchorGraph.incoming.resolutionResult.rootComponent)
}

tasks.named("check") { dependsOn(verifyDependencyConvergence) }

subprojects {
    val modulePath = path
    pluginManager.withPlugin("org.springframework.boot") {
        pluginManager.withPlugin("java") {
            val runtime = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
            val bootJarEnabled = tasks.named("bootJar").map { it.enabled }
            verifyDependencyConvergence.configure {
                bootJars.putAll(bootJarEnabled.zip(runtime) { enabled, graph ->
                    if (enabled) mapOf(modulePath to graph) else emptyMap()
                })
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// QA-02: JaCoCo line coverage of every Java module.
//
// Each module that applies the java plugin gets the JaCoCo agent on its `test` task and a
// `jacocoTestReport` that runs after the tests, even when a test fails. The XML report
// (build/reports/jacoco/test/jacocoTestReport.xml) is what CI uploads and what the post-test guard
// scripts/ci-guards/post-test/coverage.sh reads: it requires at least 70% line coverage of the Java
// lines a change adds or modifies. There is deliberately no whole-project percentage: the gate
// measures the change, so legacy code with low coverage does not block unrelated work and new code
// cannot hide behind it.
//
// JaCoCo is EPL-2.0 and is a build and test tool only. Its agent runs in the test JVM and it never
// reaches a shipped runtime classpath, so the licence gate (scripts/ci-guards/pre/dependency-licenses.sh)
// and the DEPS-01 convergence check above do not see it. 0.8.14 reads Java 21 class files.
// da-discovery applied the plugin itself and pinned 0.8.11; the toolVersion below is set after the
// module's own build file has been evaluated, so one version applies everywhere.
// -------------------------------------------------------------------------------------------------
val jacocoToolVersion = "0.8.14"

subprojects {
    pluginManager.withPlugin("java") {
        apply(plugin = "jacoco")
        afterEvaluate {
            extensions.configure<JacocoPluginExtension> { toolVersion = jacocoToolVersion }
        }
        tasks.named("test") { finalizedBy(tasks.named("jacocoTestReport")) }
        tasks.withType<JacocoReport>().configureEach {
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }
    }
}
