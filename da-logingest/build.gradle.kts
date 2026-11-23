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

// da-logingest inherits common configuration from parent build.gradle.kts
// (Java 21, Spring Boot 4.1, Postgres, Flyway, Kafka, Keycloak,
// Testcontainers). Adds GCP audit-log subscriber dependencies for M2;
// AWS CloudTrail (v0.2.0) and Azure Activity (v0.3.0) lands in
// per-provider subprojects under providers/<cloud>/ later (ADR-0004).

dependencies {
    // da-protos: DlapConstants + generated DlapLogEvent protobuf classes
    implementation(project(":da-protos"))

    // DEPS-01: the GCP provider set for providers/gcp/** (GCP-01, GCP-02), versioned by the Google
    // Cloud libraries BOM imported below. All four are Apache-2.0 (each POM; the licence gate
    // scripts/ci-guards/pre/dependency-licenses.sh checks what they bring). ADR-0023 and decision D6
    // keep cloud-native pub-sub out: no google-cloud-pubsub, events are pulled.
    // Cloud Logging read (entries.list): GCP-01's LoggingApiTransport; the legacy source/gcp poller.
    implementation("com.google.cloud:google-cloud-logging")
    // BigQuery (jobs.query, tabledata): GCP-01's BqSinkTransport and GCP-02's INFORMATION_SCHEMA.JOBS reader.
    implementation("com.google.cloud:google-cloud-bigquery")
    // BigQuery Storage Read API: bulk reads of BigQuery log sinks and JOBS results without the
    // tabledata.list page limit (GCP-01 BqSinkTransport, GCP-02).
    implementation("com.google.cloud:google-cloud-bigquerystorage")
    // Cloud Storage (list and read objects): GCP-01's GcsSinkTransport for the _Sn/_An sink shards.
    implementation("com.google.cloud:google-cloud-storage")

    // AWS CloudTrail audit-log source (v0.2.0, ADR-0023). Pulls
    // management events via the CloudTrail LookupEvents API into the
    // platform's own Kafka - no cloud-native pub-sub transport.
    implementation("software.amazon.awssdk:cloudtrail:2.25.1")
    // AWS-01: the S3 client of the CloudTrail trail-file and S3 server access log transports
    // (providers/aws/s3 only). Apache-2.0, the same SDK release as the cloudtrail client above.
    implementation("software.amazon.awssdk:s3:2.59.5")
    // LOG-05: STS for credentials-ref role ARNs (StsAssumeRoleCredentialsProvider) and for the
    // web-identity provider of the default chain (EKS IRSA), which the SDK loads from this module at
    // runtime. Apache-2.0, the same SDK release as the s3 and cloudtrail clients above.
    implementation("software.amazon.awssdk:sts:2.25.1")

    // Feign client for cross-service callbacks (used by replay endpoint
    // to notify catalog of late-arriving events).
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

    // LOG-02bc: ShedLock (Apache-2.0) keeps the log-event retention sweeper a cluster-wide
    // singleton (plan rule R7): with several replicas only one drops expired log_events
    // partitions per cron tick. JDBC lock table in this service's Flyway V3. 7.10.1 is the line
    // ShedLock tests with Spring Framework 7 and Spring Boot 4 (its README compatibility matrix;
    // BOOT-01, Q-111), the same version da-discovery uses. No other runtime dependency is added
    // here; the provider SDK set belongs to DEPS-01.
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")

    // Parser contract tests (LOG-02a, ADR-0054). Test scope only; nothing here ships.
    // ArchUnit (Apache-2.0): parse/** imports no provider SDK. Same version as da-discovery.
    testImplementation("com.tngtech.archunit:archunit:1.2.1")
    // Golden fixture corpus (FIX-01): the fixture-driven parser contract test base reads it.
    testImplementation(testFixtures(project(":da-log-fixtures")))
    // JSON Schema validator (Apache-2.0; brings com.ethlo.time:itu, Apache-2.0): proves the
    // mapping-YAML schema accepts the sample family and rejects malformed files. 1.4.0 is built
    // on Jackson 2.15.3, the line the Spring Boot 3.2 BOM pins (2.15.4); 1.4.3 and later need 2.17.
    testImplementation("com.networknt:json-schema-validator:1.4.0")
}

// DEPS-01: the Google Cloud libraries BOM versions the GCP provider set above as one tested line.
// The Spring Boot BOM is imported again after it, so the Spring Boot 4.1 versions of protobuf, gRPC,
// Netty and Jackson stay the alignment of every boot jar (the later import wins in the
// dependency-management plugin). The root build's verifyDependencyConvergence task enforces that.
configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
    imports {
        mavenBom("com.google.cloud:libraries-bom:26.90.0")
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

// Configure main application class
configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass.set("com.dlap.logingest.DaLogingestApplication")
}