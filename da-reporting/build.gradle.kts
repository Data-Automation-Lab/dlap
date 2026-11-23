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

// da-reporting inherits common configuration from parent build.gradle.kts
// This build file adds reporting-specific dependencies

dependencies {
    // DA-Protos common entities and utilities
    implementation(project(":da-protos"))
    
    // Email and notification dependencies
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("com.slack.api:slack-api-client:1.38.0")
    
    // Cross-service communication for dashboard aggregation
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // OpenAPI documentation for dashboard endpoints: springdoc comes from the root build (BOOT-01).
}

// Configure main application class
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.reporting.DaReportingApplication")
} 