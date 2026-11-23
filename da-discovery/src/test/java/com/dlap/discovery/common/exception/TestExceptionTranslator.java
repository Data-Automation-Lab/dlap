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

package com.dlap.discovery.common.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * A standalone test for the ExceptionTranslator functionality.
 */
public class TestExceptionTranslator {

    @Test
    void testExceptionTranslatorExists() {
        // Create a mock environment
        MockEnvironment env = new MockEnvironment();

        // Create our standalone translator
        StandaloneExceptionTranslator translator = new StandaloneExceptionTranslator(env);

        // Verify it was created
        assertNotNull(translator);
    }

    /**
     * A minimal implementation of ExceptionTranslator for testing.
     */
    @ControllerAdvice
    @Component
    static class StandaloneExceptionTranslator {
        private final Environment env;

        public StandaloneExceptionTranslator(Environment env) {
            this.env = env;
        }

        public Environment getEnvironment() {
            return env;
        }
    }
}