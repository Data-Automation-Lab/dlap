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

package com.dlap.logingest.parse;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The {@code parse/**} boundary (CLAUDE.md sections 5 and 9, plan rule R10, ADR-0054): parsers are
 * cloud-neutral JSON code. No class under {@code com.dlap.logingest.parse} may depend on a cloud
 * provider SDK, on the legacy sources, on transports, or on Kafka, JPA or web types. Provider SDKs
 * live only in {@code providers/<cloud>/} transports, which depend on {@code parse}, never the
 * reverse.
 */
class ParseLayerArchitectureTest {

    /** Root packages of cloud provider and data-platform SDKs. */
    static final String[] PROVIDER_SDKS = {
        // AWS
        "software.amazon..", "com.amazonaws..",
        // GCP (com.google.cloud also covers com.google.cloud.audit.AuditLog in common protos)
        "com.google.cloud..", "com.google.api..", "com.google.auth..", "com.google.logging..",
        "com.google.rpc..", "com.google.iam..", "com.google.type..", "com.google.longrunning..", "io.grpc..",
        // Azure
        "com.azure..", "com.microsoft.azure..", "com.microsoft.aad..",
        // Databricks, Unity Catalog client, Snowflake, OCI
        "com.databricks..", "io.unitycatalog..", "net.snowflake..", "com.oracle.bmc..",
    };

    /** DLAP packages a parser must not reach: the legacy sources and the SDK-backed transports. */
    static final String[] DLAP_OUTSIDE_PARSE = {
        "com.dlap.logingest.source..", "com.dlap.logingest.providers..", "com.dlap.logingest.transport..",
    };

    /** Infrastructure a parser must not touch: it is pure and does no I/O. */
    static final String[] INFRASTRUCTURE = {
        "org.apache.kafka..", "org.springframework.kafka..", "jakarta.persistence..", "org.springframework.data..",
        "org.springframework.web..", "org.springframework.jdbc..", "java.sql..", "java.net.http..",
    };

    private static final String PARSE = "com.dlap.logingest.parse..";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.dlap.logingest");

    static ArchRule noProviderSdk() {
        return noClasses().that().resideInAPackage(PARSE)
                .should().dependOnClassesThat().resideInAnyPackage(PROVIDER_SDKS)
                .because("parse/** is JSON-only and cloud neutral; SDKs belong to providers/<cloud>/ transports");
    }

    static ArchRule noLegacyOrTransportPackages() {
        return noClasses().that().resideInAPackage(PARSE)
                .should().dependOnClassesThat().resideInAnyPackage(DLAP_OUTSIDE_PARSE)
                .because("transports depend on parse, never the reverse");
    }

    static ArchRule noInfrastructure() {
        return noClasses().that().resideInAPackage(PARSE)
                .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE)
                .because("parsers are pure: no messaging, persistence, web or network I/O");
    }

    @Test
    void theParseLayerHasClassesToCheck() {
        assertThat(MAIN.stream().filter(c -> c.getPackageName().startsWith("com.dlap.logingest.parse")).count())
                .as("main classes under com.dlap.logingest.parse")
                .isGreaterThan(20);
    }

    @Test
    void parseImportsNoProviderSdk() {
        noProviderSdk().check(MAIN);
    }

    @Test
    void parseDoesNotReachLegacySourcesOrTransports() {
        noLegacyOrTransportPackages().check(MAIN);
    }

    @Test
    void parseUsesNoMessagingPersistenceOrWebTypes() {
        noInfrastructure().check(MAIN);
    }

    @Test
    void theRuleFailsOnAnSdkImport() {
        JavaClasses sample = new ClassFileImporter()
                .importPackages("com.dlap.logingest.parse.archsample");
        assertThat(sample.size()).as("the violation sample is on the test classpath").isPositive();
        assertThatThrownBy(() -> noProviderSdk().check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("software.amazon.awssdk")
                .hasMessageContaining("com.google.cloud.logging");
    }

    @Test
    void theLegacySourcesReallyUseTheSdks() {
        // Guards against a vacuous rule: the same rule applied to the legacy sources must fail.
        ArchRule sourcesUseSdks = noClasses().that().resideInAPackage("com.dlap.logingest.source..")
                .should().dependOnClassesThat().resideInAnyPackage(PROVIDER_SDKS);
        assertThatThrownBy(() -> sourcesUseSdks.check(MAIN)).isInstanceOf(AssertionError.class);
    }
}
