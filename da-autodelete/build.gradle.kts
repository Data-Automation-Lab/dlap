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

// da-autodelete inherits common configuration from parent build.gradle.kts
// This build file adds autodelete-specific dependencies

dependencies {
    // Q-653: the ProviderLifecycle*Test cases read the fixture corpus by path; as a test dependency the
    // corpus is an input of the test task, so a fixture-only change re-runs them. Internal module, Apache-2.0.
    testImplementation(testFixtures(project(":da-log-fixtures")))
    // da-protos common entities and utilities
    implementation(project(":da-protos"))
    
    // Cloud Storage SDKs for deletion operations
    implementation("software.amazon.awssdk:s3:2.25.1")
    implementation("software.amazon.awssdk:rds:2.25.1")
    implementation("software.amazon.awssdk:dynamodb:2.25.1")
    // EXEC-01 (Q-722): STS for an optional Actor role (credentials-ref, ADR-0110); Apache-2.0, same version as the rest.
    implementation("software.amazon.awssdk:sts:2.25.1")
    implementation("com.azure:azure-storage-blob:12.23.0")
    implementation("com.azure:azure-identity:1.11.1")
    implementation("com.azure:azure-cosmos:4.53.1")
    implementation("com.azure.resourcemanager:azure-resourcemanager:2.36.0")
    implementation("com.azure.resourcemanager:azure-resourcemanager-sql:2.36.0")
    implementation("com.google.cloud:google-cloud-storage:2.28.0")
    implementation("com.google.cloud:google-cloud-bigquery:2.38.1")
    
    // Additional dependencies specific to da-autodelete
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

    // RET-02: ShedLock (Apache-2.0) keeps the retire sweeper a cluster-wide singleton (plan rule R7,
    // Q-548); the lock table is in this service's own schema (Flyway V5). Same version as
    // da-catalog, da-discovery, da-logingest, da-policyengine and da-autocompliance (HA-01).
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
}

// Q-638: google-cloud-bigquery pulls com.google.auto.value:auto-value, an annotation processor, onto the runtime
// classpath. Its jar shades guava 32.1.2-jre (CVE-2026-102554), which Trivy flags in the image. Nothing here runs
// the processor, and the annotations jar (auto-value-annotations) stays, so the processor is kept out of the runtime
// classpath and with it the boot jar.
configurations.runtimeClasspath {
    exclude(group = "com.google.auto.value", module = "auto-value")
}

// Configure main application class
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.autodelete.DaAutodeleteApplication")
} 