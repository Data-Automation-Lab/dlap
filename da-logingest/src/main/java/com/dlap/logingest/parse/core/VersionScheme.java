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

/**
 * How the versions of one envelope relate to each other. One envelope uses one scheme.
 */
public enum VersionScheme {

    /**
     * Numbered {@code major.minor}. The same major with a minor at or above the floor is
     * compatible; a minor above the highest known is parsed as the highest known with
     * {@code PARSE_WARNING_NEWER_MINOR}; any other major is unknown and quarantined.
     */
    MAJOR_MINOR,

    /**
     * Named versions with no order, for example the GCP payload types {@code auditlog},
     * {@code bq-auditmetadata} and {@code bq-auditdata-legacy}. Only listed names are supported.
     */
    NAMED
}
