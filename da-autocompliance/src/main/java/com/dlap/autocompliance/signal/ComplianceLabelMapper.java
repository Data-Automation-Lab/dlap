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

package com.dlap.autocompliance.signal;

import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Projects a {@link ComplianceSignal} onto a canonical DLAP label name per the
 * compliance label taxonomy ({@code docs/specs/compliance-labels.md}):
 *
 * <pre>{@code compliance:<framework>:<status>}</pre>
 *
 * e.g. {@code compliance:gdpr:violation}. Framework/status segments are lower-cased
 * and sanitised to {@code [a-z0-9-]} so the label is a stable, policy-matchable key
 * ({@code spec.match.labels: { anyOf: [compliance:gdpr:violation] }}).
 *
 * <p>{@code UNKNOWN}-status signals map to no label (returns null) - DLAP only
 * stamps a definite compliant/violation posture, never "we don't know".
 */
@Component
public class ComplianceLabelMapper {

    public static final String PREFIX = "compliance";

    /** @return the canonical label name, or {@code null} if the signal is not label-worthy. */
    public String toLabelName(ComplianceSignal signal) {
        if (signal == null
                || signal.status() == null
                || signal.status() == ComplianceSignal.ComplianceStatus.UNKNOWN) {
            return null;
        }
        String framework = sanitise(signal.framework());
        String status = sanitise(signal.status().name());
        if (framework.isEmpty() || status.isEmpty()) {
            return null;
        }
        return PREFIX + ":" + framework + ":" + status;
    }

    private static String sanitise(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
