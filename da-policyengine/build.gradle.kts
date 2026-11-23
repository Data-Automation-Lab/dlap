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

// da-policyengine inherits common configuration from parent build.gradle.kts
// This build file adds policy-engine-specific dependencies

dependencies {
    // Shared LLM provider abstraction (ADR-0044) - used only by the draft-only
    // natural-language policy authoring path. No LLM call ever persists a policy.
    implementation(project(":da-llm-client"))

    // SEC-04 (ADR-0057): MVEL and Easy Rules are removed. Policies evaluate only through the
    // dlap/v1 DSL; subscriptions use typed filters. User-supplied regular expressions (the DSL
    // nameMatches clause) run on RE2/J, which matches in linear time, so a crafted pattern
    // cannot backtrack catastrophically. Licence: Go License (BSD-3-Clause), ASF Category A.
    implementation("com.google.re2j:re2j:1.8")

    // Additional dependencies specific to da-policyengine
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

    // POL-01: the scheduled policy sweep is a cluster-wide singleton job (plan rule R7). ShedLock
    // JDBC on the database clock, the version da-catalog, da-discovery and da-logingest already
    // ship (BOOT-01). Licence: Apache-2.0.
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
}

// SEC-04 (ADR-0057): no expression-language engine may come back on any classpath of this
// module, directly or transitively. scripts/ci-guards/pre/no-mvel.sh checks the whole tree.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.mvel" || requested.group == "org.jeasy") {
            throw GradleException(
                "${requested.group}:${requested.name} is banned in da-policyengine (ADR-0057, SEC-04)")
        }
    }
}

// Configure main application class
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.policyengine.DaPolicyEngineApplication")
}

// DocsExamplesParseTest parses every YAML under the repo's docs/examples. Declare
// that directory as a test input so an examples-only edit re-runs the test
// instead of being skipped as up-to-date.
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/examples"))
        .withPropertyName("docsExamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // PolicyActionRequestSpecTest holds the spec's PolicyActionRequest wire example to the code (POL-01).
    inputs.file(rootProject.layout.projectDirectory.file("docs/specs/policy-dsl.md"))
        .withPropertyName("policyDslSpec")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
