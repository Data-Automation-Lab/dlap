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

package com.dlap.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Strongly-typed payload for label-assignment {@link AssetChangeEvent}
 * variants ({@code LABELS_ASSIGNED}, {@code LABEL_REMOVED}).
 *
 * <p>Introduced by R-P2-3 (v0.2.0 N0). Replaces the
 * {@code Map<String, Object>} blob that {@code da-catalog} previously
 * stuffed with a mix of asset identity + label-specific keys. The
 * snapshot fields move to {@link AssetSnapshot}; the label-change-specific
 * details (kept as a {@code Map<String, String>} for now because the
 * key set varies: {@code addedLabels}, {@code removedLabels},
 * {@code labelSource}, etc.) live here.
 *
 * <p>Future work: enumerate the known detail keys and promote them
 * to named record fields. Tracked under R-P2-3 phase-2.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LabelAssignmentDetails(
        Map<String, String> details
) {
    public static LabelAssignmentDetails of(Map<String, String> details) {
        return new LabelAssignmentDetails(details);
    }
}
