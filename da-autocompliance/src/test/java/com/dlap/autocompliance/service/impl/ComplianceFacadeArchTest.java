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

package com.dlap.autocompliance.service.impl;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * N1 acceptance guard (R-P1-12 / ADR-0024 phase-2): the
 * {@code ComplianceServiceImpl} facade must stay logic-free. If business
 * logic creeps back in - repository, mapper, or entity dependencies, or
 * private helper methods - these rules fail the build.
 */
class ComplianceFacadeArchTest {

    private static final JavaClasses FACADE = new ClassFileImporter()
            .importClasses(ComplianceServiceImpl.class);

    @Test
    void facadeMustNotTouchRepositoriesMappersOrEntities() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.dlap.autocompliance.model.repository..",
                        "com.dlap.autocompliance.model.mapper..",
                        "com.dlap.autocompliance.model.entity..")
                .because("ComplianceServiceImpl is a pure facade; data access lives in the focused slice impls")
                .check(FACADE);
    }

    @Test
    void facadeMustNotDeclareHelperMethods() {
        // Delegation-only contract: every declared method is public (an
        // interface delegation or the @PostConstruct seed hook). A private
        // or package-private method means logic moved back into the facade.
        List<Method> nonPublic = List.of(ComplianceServiceImpl.class.getDeclaredMethods()).stream()
                .filter(m -> !m.isSynthetic())
                .filter(m -> !java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .toList();
        assertThat(nonPublic)
                .withFailMessage("ComplianceServiceImpl declares non-public methods (helpers): %s - move logic to a focused slice impl", nonPublic)
                .isEmpty();
    }
}
