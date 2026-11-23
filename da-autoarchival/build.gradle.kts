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

// da-autoarchival inherits common configuration from parent build.gradle.kts
// This build file adds autoarchival-specific dependencies

dependencies {
    // da-protos common entities and utilities
    implementation(project(":da-protos"))
    
    // Cloud Storage SDKs
    implementation("software.amazon.awssdk:s3:2.59.5")
    implementation("software.amazon.awssdk:glacier:2.25.1")
    // EXEC-01 (Q-722): STS for an optional Actor role (credentials-ref, ADR-0110); Apache-2.0, same version as the rest.
    implementation("software.amazon.awssdk:sts:2.25.1")
    implementation("com.azure:azure-storage-blob:12.23.0")
    // EXEC-01 (Q-724): DefaultAzureCredential for the blob data plane; MIT, the version da-autodelete uses.
    implementation("com.azure:azure-identity:1.11.1")
    implementation("com.google.cloud:google-cloud-storage:2.28.0")
    
    // Additional dependencies specific to da-autoarchival
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

    // RET-05 (Q-607): ShedLock (Apache-2.0) keeps the archive rule reconciliation a cluster-wide singleton
    // (plan rule R7); the lock table is in this service's own schema (Flyway V3). Same version as
    // da-catalog, da-discovery, da-logingest, da-policyengine, da-autocompliance and da-autodelete.
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
}

// Configure main application class
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.autoarchival.DaAutoarchivalApplication")
} 