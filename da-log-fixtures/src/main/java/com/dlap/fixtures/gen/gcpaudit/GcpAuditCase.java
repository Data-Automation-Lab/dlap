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

package com.dlap.fixtures.gen.gcpaudit;

import java.util.List;

/**
 * One generated case of the {@code gcpaudit} family: its records, the events a correct parser
 * emits for them, and the provenance written to {@code meta.yaml}.
 *
 * @param name      the case directory name
 * @param version   the version cell ({@code auditlog-2021}, {@code auditlog-2025},
 *                  {@code bq-auditmetadata}, {@code bq-auditdata-legacy})
 * @param envelopes the envelopes the case is written to ({@code json}, {@code api-proto})
 * @param negative  true for a case under {@code negative/}
 * @param files     the input files: each a list of entries, written as one JSON array
 *                  ({@code input.json}, {@code input.late.json}) or as JSON lines in the
 *                  {@code api-proto} envelope
 * @param expected  the events, in record order
 * @param outcome   the expected outcome, for example {@code PARSED}
 * @param covers    {@code service:operation} pairs of the coverage spec the case exercises
 * @param variants  coverage-spec variant ids the case exercises in every envelope
 * @param jsonOnly  variant ids the case exercises only in the {@code json} envelope
 * @param notes     reviewer notes for {@code meta.yaml}
 */
record GcpAuditCase(
        String name,
        String version,
        List<String> envelopes,
        boolean negative,
        List<List<Entry>> files,
        List<Expected> expected,
        String outcome,
        List<String> covers,
        List<String> variants,
        List<String> jsonOnly,
        String notes) {
}
