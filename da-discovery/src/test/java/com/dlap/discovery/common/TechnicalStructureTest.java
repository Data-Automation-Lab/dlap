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

package com.dlap.discovery.common;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class TechnicalStructureTest {

    private static final String BASE_PACKAGE = "com.dlap.discovery";

    /** A DLAP class whose simple name ends with Impl. Framework classes such as Spring's PageImpl do not count. */
    private static final DescribedPredicate<JavaClass> DLAP_IMPLEMENTATION_CLASS = DescribedPredicate.describe(
        "DLAP implementation classes (in " + BASE_PACKAGE + " and named *Impl)",
        javaClass -> javaClass.getPackageName().startsWith(BASE_PACKAGE) && javaClass.getSimpleName().endsWith("Impl")
    );

    @Test
    @Timeout(60)
    public void respectsTechnicalArchitectureLayers() {
        JavaClasses importedClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages(BASE_PACKAGE);

        // The rule below is vacuous when no controller is imported (for example after a package
        // move), so assert that there are some.
        long controllers = importedClasses.stream()
            .filter(javaClass -> javaClass.getSimpleName().endsWith("Controller"))
            .count();
        Assertions.assertTrue(controllers > 0, "no *Controller class found under " + BASE_PACKAGE);

        // Web controllers depend on service interfaces, never on a DLAP implementation class.
        // The earlier form of this rule (only depend on classes not named *Impl) also matched
        // Spring's PageImpl, which a controller legitimately builds for a paged response, and
        // was disabled for that reason.
        ArchRule controllerRule = ArchRuleDefinition
            .noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat(DLAP_IMPLEMENTATION_CLASS);

        controllerRule.check(importedClasses);
    }
}
