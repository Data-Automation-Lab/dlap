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

package com.dlap.mcppep;

import java.util.List;

/**
 * An obligation the PEP must honor when returning data for an allowed
 * decision (docs/specs/enforcement-contract.md §3).
 *
 * <p>v1 types: {@code mask} (redact values in fields carrying the named
 * labels), {@code redact_rows} (drop rows matching the label predicate),
 * {@code log_only} (advisory / observe mode). Per the contract, a PEP that
 * cannot honor a returned obligation MUST treat the decision as denied
 * (fail-closed on obligations) - enforcing that transform is the MCP
 * transport layer's job, out of scope for this reference core.
 *
 * @param type   the obligation type
 * @param labels the labels the obligation applies to (may be empty)
 */
public record Obligation(String type, List<String> labels) {
}
