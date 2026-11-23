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

package com.dlap.logingest.parse.core;

import java.util.Locale;
import org.springframework.core.env.PropertyResolver;

/**
 * Which ingest pipeline runs: the legacy per-cloud sources, or the parser registry
 * ({@value #PROPERTY}, ADR-0045 decision 8). The default is {@link #LEGACY}, and stays so until
 * the registry passes the family contract tests and the flip package changes it.
 *
 * <p>Beans of either pipeline are gated with {@link ConditionalOnLegacyPipeline} or
 * {@link ConditionalOnRegistryPipeline}. An unknown value fails startup.
 */
public enum PipelineMode {

    /** The pre-registry sources under {@code source/**}. The default. */
    LEGACY,

    /** Transports feed the parser registry. */
    REGISTRY;

    /** The property that selects the pipeline. */
    public static final String PROPERTY = "dlap.logingest.pipeline";

    /** The default pipeline. */
    public static final PipelineMode DEFAULT = LEGACY;

    /**
     * Parses a property value.
     *
     * @param value {@code legacy} or {@code registry} in any case, as {@code @ConditionalOnProperty}
     *              compares them; {@code null} gives the default
     * @return the mode
     * @throws IllegalArgumentException for any other value, including padded or empty text, which
     *                                  the pipeline conditions would not match either
     */
    public static PipelineMode parse(String value) {
        if (value == null) {
            return DEFAULT;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "legacy" -> LEGACY;
            case "registry" -> REGISTRY;
            default -> throw new IllegalArgumentException(PROPERTY + " must be legacy or registry");
        };
    }

    /**
     * Reads the mode from the environment.
     *
     * @param env the environment
     * @return the mode
     * @throws IllegalArgumentException for an unknown value
     */
    public static PipelineMode resolve(PropertyResolver env) {
        return parse(env.getProperty(PROPERTY));
    }
}
