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

// da-llm-client is a plain library (no bootJar), like da-protos. It carries the
// ILLMClient provider abstraction shared by da-autolabel and da-policyengine.
// Excluded from the root build's Spring Boot application conventions - see the
// exclusion list in the root build.gradle.kts subprojects block.

plugins {
    `java-library`
    // Versions come from the root build (BOOT-01, ADR-0065).
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Spring context + Boot auto-configuration/@ConfigurationProperties support.
    api("org.springframework.boot:spring-boot-starter")
    api("com.fasterxml.jackson.core:jackson-databind")

    // Provider SDKs - moved verbatim from da-autolabel; no new dependency added.
    implementation("com.theokanning.openai-gpt3-java:service:0.18.2")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.5")

    // SEC-03: the Bean Validation constraints on MLConfigRequest. The API (Apache-2.0, version from the
    // Spring Boot BOM) is compile-only: the services that validate the request carry the implementation.
    compileOnly("jakarta.validation:jakarta.validation-api")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JUnit 6 (Spring Boot 4 BOM) needs its launcher on the test runtime classpath; Gradle no longer
    // supplies a matching one. The Spring Boot plugin adds it to the service modules (BOOT-01).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
