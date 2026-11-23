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

package com.dlap.catalog.lifecycle;

import java.util.Objects;

import com.dlap.catalog.coverage.ReadCoverageObservation;

/**
 * Published by the read-coverage applier, inside its transaction, after an observation was written
 * to at least one asset row. {@link LifecycleEvidenceRecorder} keeps its event id so that the
 * {@code readCoverage} provenance of those assets can name it.
 *
 * @param observation the applied observation
 */
public record AppliedCoverageObservation(ReadCoverageObservation observation) {

    /**
     * Checks the observation.
     *
     * @throws NullPointerException when {@code observation} is {@code null}
     */
    public AppliedCoverageObservation {
        Objects.requireNonNull(observation, "observation");
    }
}
