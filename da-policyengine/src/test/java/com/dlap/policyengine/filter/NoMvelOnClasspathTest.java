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

package com.dlap.policyengine.filter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SEC-04 (ADR-0057): no expression-language engine is on the test runtime classpath,
 * which contains the runtime classpath. {@code scripts/ci-guards/pre/no-mvel.sh} checks the
 * declarations of every module; the build file refuses to resolve the banned groups.
 */
class NoMvelOnClasspathTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "org.mvel2.MVEL",
        "org.mvel2.compiler.ExpressionCompiler",
        "org.jeasy.rules.api.Rules",
        "org.jeasy.rules.core.DefaultRulesEngine",
        "org.jeasy.rules.mvel.MVELRule"
    })
    void bannedEngineClassIsAbsent(String className) {
        assertThatThrownBy(() -> Class.forName(className, false, getClass().getClassLoader()))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.google.re2j.Pattern"})
    void boundedRegexEngineIsPresent(String className) {
        assertThatCode(() -> Class.forName(className, false, getClass().getClassLoader()))
                .doesNotThrowAnyException();
    }
}
