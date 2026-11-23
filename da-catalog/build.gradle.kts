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

// da-catalog inherits common configuration from parent build.gradle.kts
// This build file adds catalog-specific dependencies

dependencies {
    // Q-653: the ProviderLifecycle*Test cases read the fixture corpus by path; as a test dependency the
    // corpus is an input of the test task, so a fixture-only change re-runs them. Internal module, Apache-2.0.
    testImplementation(testFixtures(project(":da-log-fixtures")))
    // Additional dependencies specific to da-catalog
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

    // CAT-03: ShedLock (Apache-2.0) makes the retention sweep and the retention-due publisher
    // cluster-wide singletons (plan rule R7). The lock table is in this service's own schema
    // (Flyway V4). 7.x is the line ShedLock tests with Spring Framework 7 and Spring Boot 4
    // (its README compatibility matrix); the version matches da-discovery and da-logingest.
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")

    // Unity Catalog OSS client (ADR-0029): optional external-catalog
    // interoperability. Apache 2.0; provider-neutral. Gated at runtime on
    // dlap.catalog.unity.enabled (default false) - no effect unless enabled.
    // Exclude its bundled logging bindings (log4j2/reload4j) so they don't
    // collide with the platform's logback - slf4j-api alone is kept.
    implementation("io.unitycatalog:unitycatalog-client:0.5.0") {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "log4j", module = "log4j")
    }

    // LSE-01: validates the emitted Lifecycle State objects against
    // docs/specs/schemas/lifecycle-state-0.1.json. Apache-2.0; test scope only; the same version
    // da-protos and da-logingest already use, so no new artifact enters the build.
    testImplementation("com.networknt:json-schema-validator:1.4.0")
}

// Configure main application class
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.catalog.DaCatalogApplication")
} 
// LSE-01: offline OKF v0.1 export of Lifecycle State objects saved from the catalog API
// (docs/specs/lifecycle-state.md section 8.2). No database or network:
//   ./gradlew :da-catalog:exportLifecycleStateOkf -Pinput=states.json -Poutput=lifecycle-state-okf.zip
tasks.register<JavaExec>("exportLifecycleStateOkf") {
    group = "lifecycle state"
    description = "Writes the OKF v0.1 zip of saved Lifecycle State objects (-Pinput=<json> -Poutput=<zip>)"
    classpath = project.the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.dlap.catalog.lifecycle.okf.OkfExportCli")
    val input = providers.gradleProperty("input")
    val output = providers.gradleProperty("output")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(input.getOrElse(""), output.getOrElse(""))
    })
    workingDir = rootProject.projectDir
}
