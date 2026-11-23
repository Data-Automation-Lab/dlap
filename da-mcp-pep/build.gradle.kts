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

// da-mcp-pep - reference Policy Enforcement Point (ADR-0040, V4-C6).
// A thin, separate deployable that fronts an MCP server, maps each
// tool-call / resource-read to an AuthZEN evaluation, calls the DLAP
// PDP (POST /access/v1/evaluation, docs/specs/enforcement-contract.md
// §2/§3) and enforces the verdict. It depends ONLY on the published
// AuthZEN contract, so it is swappable for any AuthZEN PDP.
//
// This module inherits ALL common configuration from the root
// build.gradle.kts subproject block: Java 21, Spring Boot 4.1,
// spring-webmvc / -security / -security-oauth2-resource-server / -actuator /
// -validation, and the bootJar naming convention. It adds nothing heavy.
//
// A PEP is a stateless client, not a data-owning service. The inherited
// JPA / Flyway / Kafka starters are therefore unused here and are
// excluded at autoconfiguration time in McpPepApplication (@SpringBootApplication
// exclude=…). They stay on the classpath only because the root block adds
// them to every microservice module; the PEP never opens a datasource,
// runs a migration, or starts a Kafka consumer.

dependencies {
    // da-protos: DlapConstants (shared string constants). The root block
    // already adds this to every microservice module; declared explicitly
    // here for intent. RestClient (used by HttpPdpClient) comes from the
    // inherited spring-web.
    implementation(project(":da-protos"))
    // BOOT-01: Spring Boot 4 moved the auto-configured RestClient.Builder that HttpPdpClient takes
    // into its own module (Apache-2.0, Spring Boot BOM version); without it the PEP does not start.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
}

// Configure main application class for the bootable PEP jar.
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.mcppep.McpPepApplication")
}

// RealmImportTest (PEP-02) reads the shipped Keycloak realm import. Declare it as a test input so a
// change to the realm re-runs the test instead of being served from the Gradle up-to-date check.
tasks.named<Test>("test") {
    inputs.file(rootProject.file("deployment/platform/keycloak/keycloak-import/dlap-realm.json"))
        .withPropertyName("keycloakRealmImport")
        .withPathSensitivity(PathSensitivity.NONE)
}
