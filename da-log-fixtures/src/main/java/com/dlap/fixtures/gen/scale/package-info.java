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

/**
 * Synthetic scale generators (E2E-02, decision D39). They produce CloudTrail trail files, GCP Cloud
 * Audit log entries and Azure activity log records at a configurable count, deterministic from a seed:
 * the same seed, count and end time give the same bytes on every machine and every run. Nothing here
 * reads a vendor sample, an Elastic file or a real capture; every account, project, subscription,
 * address and name is invented (the addresses are the RFC 5737 documentation ranges).
 *
 * <p>The package has no dependency beyond the JDK, so a harness can run it from the compiled classes
 * with {@code java -cp da-log-fixtures/build/classes/java/main com.dlap.fixtures.gen.scale.ScaleMain}.
 */
package com.dlap.fixtures.gen.scale;
