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

package com.dlap.logingest.providers.aws.s3;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * AWS-01 cloud neutrality: the AWS SDK is used only under {@code providers/aws/**} (and the legacy
 * LookupEvents source, the shrinking exception of {@code LogIngestBoundaryArchitectureTest}), and in
 * the S3 transports only by the one class that builds the client. The transports, the text-line
 * splitter and the configuration are SDK free, so they can be read and tested without AWS.
 */
class AwsSdkConfinementTest {

    private static final String ROOT = "com.dlap.logingest";
    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void noClassOutsideProvidersAwsOrTheLegacySourceUsesTheAwsSdk() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".providers.aws..", ROOT + ".source.aws..")
                .should().dependOnClassesThat().resideInAnyPackage("software.amazon..", "com.amazonaws..")
                .because("CLAUDE.md section 5: provider SDKs live only in providers/<cloud>/")
                .check(MAIN);
    }

    @Test
    void inTheS3TransportsOnlyTheClientStoreUsesTheSdk() {
        classes().that().resideInAPackage(ROOT + ".providers.aws.s3..")
                .and().doNotHaveSimpleName("SdkS3LogStore")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages("software.amazon..", "com.amazonaws..")
                .check(MAIN);
        assertThat(MAIN.stream().filter(c -> c.getPackageName().equals(ROOT + ".providers.aws.s3")).count())
                .isGreaterThanOrEqualTo(7);
    }
}
