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

package com.dlap.logingest.config.sources;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every finding of one validation run.
 *
 * @param problems the findings, in the order they were found
 */
public record ValidationReport(List<ConfigProblem> problems) {

    /** No findings. */
    public static final ValidationReport CLEAN = new ValidationReport(List.of());

    /**
     * Freezes the list.
     *
     * @param problems see the record description
     */
    public ValidationReport {
        problems = List.copyOf(problems);
    }

    /**
     * The errors.
     *
     * @return the findings of severity error
     */
    public List<ConfigProblem> errors() {
        return problems.stream().filter(ConfigProblem::isError).toList();
    }

    /**
     * The warnings.
     *
     * @return the findings of severity warning
     */
    public List<ConfigProblem> warnings() {
        return problems.stream().filter(p -> !p.isError()).toList();
    }

    /**
     * Whether any finding is an error.
     *
     * @return true when there is an error
     */
    public boolean hasErrors() {
        return problems.stream().anyMatch(ConfigProblem::isError);
    }

    /**
     * The rules that produced findings, for tests.
     *
     * @return the rules
     */
    public Set<ConfigProblem.Rule> rules() {
        return problems.stream().map(ConfigProblem::rule).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The bindings an error makes invalid.
     *
     * @return binding ids
     */
    public Set<String> invalidBindings() {
        return problems.stream().filter(ConfigProblem::isError)
                .flatMap(p -> p.binding().stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
