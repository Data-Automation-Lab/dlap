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

package com.dlap.discovery.coverage;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * COV-01 cloud-neutrality boundary (CLAUDE.md section 5, ADR-0004): the shared coverage package
 * (model, encoder, publisher, job, transport) touches no provider SDK, Google auth or Google API
 * client. Provider code lives only in {@code providers/<cloud>/coverage}.
 */
class ReadCoverageArchitectureTest {

    private static final JavaClasses COVERAGE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.dlap.discovery.coverage");

    @Test
    void theSharedCoveragePackageUsesNoProviderSdk() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "com.amazonaws..", "software.amazon..", "com.azure..", "com.microsoft.azure..",
                        "com.google.cloud..", "com.google.api..", "com.google.auth..", "com.oracle.bmc..",
                        "com.dlap.discovery.providers..")
                .because("ADR-0004: provider code lives only under providers/<cloud>/")
                .check(COVERAGE);
    }
}
