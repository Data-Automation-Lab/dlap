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

plugins {
    // Versions come from the root build (BOOT-01, ADR-0065).
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

group = "com.dlap"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        // The Spring Cloud BOM is imported by the root build (BOOT-01).
    }
}

val mapstructVersion = "1.5.5.Final"

dependencies {
    // Common Spring Boot dependencies
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    
    // Shared proto dependency
    implementation(project(":da-protos"))
    
    // AutoCompliance-specific dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Cross-service communication: fetch catalog assets for real, catalog-backed
    // compliance evaluation. Bearer token is attached by the shared service-account
    // Feign interceptor (da-protos ServiceAuthAutoConfiguration, dlap.service-auth.*).
    // Version from the Spring Cloud BOM the root build imports (BOOT-01).
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    // BOOT-01: the auto-configured RestTemplateBuilder (HttpComplianceSignalProvider) moved into its
    // own Spring Boot 4 module. Apache-2.0, Spring Boot BOM version.
    implementation("org.springframework.boot:spring-boot-starter-restclient")

    // HA-01: ShedLock (Apache-2.0) keeps the compliance signal sync a cluster-wide singleton (plan
    // rule R7). The lock table is in this service's own schema (Flyway V5). 7.x is the line
    // ShedLock tests with Spring Framework 7 and Spring Boot 4 (its README compatibility matrix);
    // the version matches da-catalog, da-discovery, da-logingest and da-policyengine.
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")

    // MapStruct
    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    
    // Common development and runtime dependencies
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    
    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    
    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // ArchUnit guards the ComplianceServiceImpl pure-facade contract (N1 acceptance)
    testImplementation("com.tngtech.archunit:archunit-junit5-api:1.2.1")
    testRuntimeOnly("com.tngtech.archunit:archunit-junit5-engine:1.2.1")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
} 