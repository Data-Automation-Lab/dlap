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
    java
    // Versions come from the root build (BOOT-01, ADR-0065).
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.dlap"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // da-protos common entities and utilities
    implementation(project(":da-protos"))
    
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // Spring Cloud and springdoc versions come from the root build (BOOT-01, ADR-0065).
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign") // For Feign clients

    // MapStruct for DTO mapping
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")

    // Lombok for reduced boilerplate
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0") // If using Lombok with MapStruct

    // BOOT-01: hypersistence-utils-hibernate-62 does not run on Hibernate 7. Its one use (the
    // connection_parameters jsonb column of CloudConnectionEntity) now maps through Hibernate's own
    // @JdbcTypeCode(SqlTypes.JSON), like every other JSON column in the repo, so the dependency is gone.

    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Keycloak Admin Client (if direct Keycloak interaction is needed for users/roles)
    implementation("org.keycloak:keycloak-admin-client:24.0.4") 
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// IAM-04: the permission self-check compares the cloud's answer with the committed traces, so the
// jar carries deployment/iam/<cloud>/{actor,reader}/permissions.yaml under iam/ (TraceSource).
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("deployment/iam")) {
        include("*/actor/permissions.yaml", "*/reader/permissions.yaml")
        into("iam")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
} 