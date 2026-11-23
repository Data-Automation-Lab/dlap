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

package com.dlap.logingest.parse.core.mapping;

import java.util.List;

/**
 * A mapping YAML that cannot be used. The service refuses to start with one (fail fast), and the
 * message lists every problem found, one per line.
 */
public final class MappingSpecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The problems, one line each. */
    private final transient List<String> problems;

    /**
     * Creates the exception.
     *
     * @param problems every problem found; at least one
     */
    public MappingSpecException(List<String> problems) {
        super("invalid log-format mapping:\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the problems.
     *
     * @return one line per problem
     */
    public List<String> problems() {
        return problems;
    }
}
