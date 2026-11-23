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

// da-autolabel inherits common configuration from parent build.gradle.kts
// This build file adds autolabel-specific dependencies

dependencies {
    // DA-Protos common entities and utilities
    implementation(project(":da-protos"))

    // Shared LLM provider abstraction (ADR-0044). ILLMClient / LLMClientFactory
    // and the OpenAI + Ollama provider clients moved here out of this service;
    // the provider SDK dependencies moved with them.
    // Gemini/Vertex AI dep removed per ADR-0009 / R-P1-1: v0.1.0 ships OpenAI + Ollama only
    // behind the ILLMClient abstraction. Re-add google-cloud-aiplatform in 0.2.0 if needed.
    implementation(project(":da-llm-client"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    
    // Feign Client Dependencies
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
}

// Configure main application class
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.autolabel.DaAutolabelApplication")
}