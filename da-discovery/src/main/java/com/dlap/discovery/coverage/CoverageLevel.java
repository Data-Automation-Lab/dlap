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

/**
 * What a provider configuration proves about the recording of data reads (COV-01). Unknown is
 * not a level: a {@link CoverageVerdict} with a {@code null} level is unknown.
 */
public enum CoverageLevel {

    /** The configuration proves that every data read of the asset is recorded. */
    FULL,
    /** Some data reads are recorded, others are not; the reasons name the gap. */
    PARTIAL,
    /** No data read of the asset is recorded. */
    NONE;

    /** Wire spelling of an unknown level. */
    public static final String UNKNOWN = "UNKNOWN";
}
