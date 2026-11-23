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

package com.dlap.logingest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Package boundaries of the configuration-driven log layer (ADR-0060, CLAUDE.md section 5, plan
 * rule R10), next to the {@code parse/**} rules of {@code ParseLayerArchitectureTest}:
 * <ul>
 *   <li>cloud SDKs only under {@code providers/<cloud>/}, each cloud's SDK only in its own
 *       package, and the legacy {@code source/**} as the one shrinking exception;</li>
 *   <li>bindings ({@code config.sources}) know parsers and storage, never transports, the ingest
 *       service, the web layer or Kafka;</li>
 *   <li>the registry ingest path ({@code service.registry}) never reaches transports, the web
 *       layer or the legacy sources; transports never reach the web layer or the legacy sources;</li>
 *   <li>parsers know none of the new layers;</li>
 *   <li>nothing new depends on the legacy sources, so they can be deleted (design B8 step 4.3).</li>
 * </ul>
 */
class LogIngestBoundaryArchitectureTest {

    private static final String[] AWS_SDK = {"software.amazon..", "com.amazonaws.."};
    private static final String[] GCP_SDK = {"com.google.cloud..", "com.google.api..", "com.google.auth..",
        "com.google.logging..", "com.google.rpc..", "com.google.iam..", "com.google.type..",
        "com.google.longrunning..", "io.grpc.."};
    private static final String[] AZURE_SDK = {"com.azure..", "com.microsoft.azure..", "com.microsoft.aad.."};
    private static final String[] OTHER_SDK = {"com.databricks..", "io.unitycatalog..", "net.snowflake..",
        "com.oracle.bmc.."};

    private static final String ROOT = "com.dlap.logingest";
    private static final String CONFIG_SOURCES = ROOT + ".config.sources..";
    private static final String TRANSPORT = ROOT + ".transport..";
    private static final String SERVICE_REGISTRY = ROOT + ".service.registry..";
    private static final String PARSE = ROOT + ".parse..";
    private static final String LEGACY = ROOT + ".source..";
    private static final String PROVIDERS = ROOT + ".providers..";
    private static final String WEB = ROOT + ".web..";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    private static String[] allSdks() {
        return java.util.stream.Stream.of(AWS_SDK, GCP_SDK, AZURE_SDK, OTHER_SDK).flatMap(java.util.Arrays::stream)
                .toArray(String[]::new);
    }

    static ArchRule sdksOnlyInProvidersOrLegacy() {
        return noClasses().that().resideOutsideOfPackages(PROVIDERS, LEGACY)
                .should().dependOnClassesThat().resideInAnyPackage(allSdks())
                .because("cloud neutrality: provider SDKs live only in providers/<cloud>/ (CLAUDE.md section 5)");
    }

    static ArchRule eachProviderUsesOnlyItsOwnSdk(String cloud, String[] foreign) {
        return noClasses().that().resideInAPackage(ROOT + ".providers." + cloud + "..")
                .should().dependOnClassesThat().resideInAnyPackage(foreign)
                .allowEmptyShould(true)
                .because("providers/" + cloud + " uses its own cloud's SDK only");
    }

    @Test
    void theCheckedPackagesHaveClasses() {
        for (String pkg : new String[] {"config.sources", "transport", "service.registry", "parse.dlapnormalized"}) {
            assertThat(MAIN.stream().filter(c -> c.getPackageName().startsWith(ROOT + "." + pkg)).count())
                    .as("main classes under %s", pkg).isGreaterThan(2);
        }
    }

    @Test
    void providerSdksLiveOnlyInProviderPackages() {
        sdksOnlyInProvidersOrLegacy().check(MAIN);
    }

    @Test
    void eachProviderPackageUsesOnlyItsOwnSdk() {
        String[] notAws = java.util.stream.Stream.of(GCP_SDK, AZURE_SDK, OTHER_SDK).flatMap(java.util.Arrays::stream)
                .toArray(String[]::new);
        String[] notGcp = java.util.stream.Stream.of(AWS_SDK, AZURE_SDK, OTHER_SDK).flatMap(java.util.Arrays::stream)
                .toArray(String[]::new);
        String[] notAzure = java.util.stream.Stream.of(AWS_SDK, GCP_SDK, OTHER_SDK).flatMap(java.util.Arrays::stream)
                .toArray(String[]::new);
        eachProviderUsesOnlyItsOwnSdk("aws", notAws).check(MAIN);
        eachProviderUsesOnlyItsOwnSdk("gcp", notGcp).check(MAIN);
        eachProviderUsesOnlyItsOwnSdk("azure", notAzure).check(MAIN);
    }

    @Test
    void bindingsDoNotReachTransportsTheIngestServiceTheWebOrKafka() {
        noClasses().that().resideInAPackage(CONFIG_SOURCES)
                .should().dependOnClassesThat().resideInAnyPackage(TRANSPORT, ROOT + ".service..", WEB, LEGACY,
                        PROVIDERS, ROOT + ".kafka..", "org.apache.kafka..", "org.springframework.kafka..")
                .because("bindings are configuration; transports and ingest depend on them, not the reverse")
                .check(MAIN);
    }

    @Test
    void theRegistryPathDoesNotReachTransportsTheWebOrTheLegacySources() {
        noClasses().that().resideInAPackage(SERVICE_REGISTRY)
                .should().dependOnClassesThat().resideInAnyPackage(TRANSPORT, WEB, LEGACY, PROVIDERS)
                .check(MAIN);
    }

    @Test
    void transportsDoNotReachTheWebOrTheLegacySources() {
        noClasses().that().resideInAPackage(TRANSPORT)
                .should().dependOnClassesThat().resideInAnyPackage(WEB, LEGACY, ROOT + ".kafka..", ROOT + ".demo..")
                .because("a transport hands records to the registry path and never publishes itself")
                .check(MAIN);
    }

    @Test
    void parsersKnowNoneOfTheNewLayers() {
        noClasses().that().resideInAPackage(PARSE)
                .should().dependOnClassesThat().resideInAnyPackage(CONFIG_SOURCES, ROOT + ".service..",
                        ROOT + ".repository..", ROOT + ".model..", WEB, ROOT + ".retention..")
                .check(MAIN);
    }

    @Test
    void nothingNewDependsOnTheLegacySources() {
        noClasses().that().resideOutsideOfPackage(LEGACY)
                .and().doNotHaveFullyQualifiedName(ROOT + ".repository.SourceWatermarkRepository")
                .should().dependOnClassesThat().resideInAPackage(LEGACY)
                .because("the legacy sources are deleted once the registry pipeline is the default")
                .check(MAIN);
    }

    @Test
    void theSdkRuleIsNotVacuous() {
        // The legacy sources do import SDKs: the same rule without their exemption must fail.
        ArchRule withoutExemption = noClasses().that().resideOutsideOfPackages(PROVIDERS)
                .should().dependOnClassesThat().resideInAnyPackage(allSdks());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withoutExemption.check(MAIN))
                .isInstanceOf(AssertionError.class);
    }
}
