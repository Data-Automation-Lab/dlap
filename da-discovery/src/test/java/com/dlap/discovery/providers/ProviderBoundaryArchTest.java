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

package com.dlap.discovery.providers;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * N2 / ADR-0004 cloud-neutrality boundary: provider SDKs may only be
 * touched from {@code providers/<cloud>/} packages (or per-cloud
 * subtrees like {@code crawler/service/<cloud>}, {@code log/service/<cloud>}).
 * The shared trees - {@code common/}, {@code config/} (Spring config root),
 * and the {@code com.dlap.common} entities from da-protos - must stay
 * SDK-free so a provider can be dropped without touching shared code.
 *
 * <p><b>Oracle exception (ADR-0026):</b> the OCI path
 * ({@code common/auth/impl/oracle}, {@code common/config/cloud/impl/oracle})
 * predates the providers/ layout and is property-gated
 * ({@code cloud.provider.oci.enabled}), not on the roadmap, and explicitly
 * left in place by ADR-0026. It is excluded from the rule rather than
 * relocated; promoting Oracle to a real roadmap cloud would supersede
 * ADR-0026 and move it under {@code providers/oracle/}.
 */
class ProviderBoundaryArchTest {

    private static final String[] PROVIDER_SDK_PACKAGES = {
            "com.amazonaws..",
            "software.amazon..",
            "com.azure..",
            "com.microsoft.azure..",
            "com.google.cloud..",
            "com.oracle.bmc..",
    };

    private static final JavaClasses SHARED_TREES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.dlap.discovery.common", "com.dlap.common");

    @Test
    void sharedTreesMustNotTouchProviderSdks() {
        noClasses()
                .that().resideOutsideOfPackages(
                        // ADR-0026: gated Oracle path stays under common/ for now.
                        "com.dlap.discovery.common.auth.impl.oracle..",
                        "com.dlap.discovery.common.config.cloud.impl.oracle..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(PROVIDER_SDK_PACKAGES)
                .because("ADR-0004: provider SDKs live only under providers/<cloud>/ "
                        + "(or per-cloud crawler/log subtrees); shared code stays cloud-neutral")
                .check(SHARED_TREES);
    }
}
