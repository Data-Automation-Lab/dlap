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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autocompliance.signal.ComplianceSignal.ComplianceStatus;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ComplianceLabelMapper} (plain JUnit, no Mockito). */
class ComplianceLabelMapperTest {

    private final ComplianceLabelMapper mapper = new ComplianceLabelMapper();

    @Test
    void violation_mapsToCanonicalLabel() {
        ComplianceSignal s = new ComplianceSignal("gcs://b/pii", "GDPR", "art-17", ComplianceStatus.VIOLATION, "retention exceeded");
        assertThat(mapper.toLabelName(s)).isEqualTo("compliance:gdpr:violation");
    }

    @Test
    void compliant_mapsToCanonicalLabel() {
        ComplianceSignal s = new ComplianceSignal("s3://b/k", "PCI-DSS", null, ComplianceStatus.COMPLIANT, null);
        assertThat(mapper.toLabelName(s)).isEqualTo("compliance:pci-dss:compliant");
    }

    @Test
    void framework_isLowercasedAndSanitised() {
        ComplianceSignal s = new ComplianceSignal("uri", "SOC 2", "cc6.1", ComplianceStatus.VIOLATION, null);
        assertThat(mapper.toLabelName(s)).isEqualTo("compliance:soc-2:violation");
    }

    @Test
    void unknownStatus_mapsToNoLabel() {
        ComplianceSignal s = new ComplianceSignal("uri", "HIPAA", "164.312", ComplianceStatus.UNKNOWN, null);
        assertThat(mapper.toLabelName(s)).isNull();
    }

    @Test
    void nullOrBlankFramework_mapsToNoLabel() {
        assertThat(mapper.toLabelName(new ComplianceSignal("uri", null, "x", ComplianceStatus.VIOLATION, null))).isNull();
        assertThat(mapper.toLabelName(new ComplianceSignal("uri", "  ", "x", ComplianceStatus.VIOLATION, null))).isNull();
    }
}
