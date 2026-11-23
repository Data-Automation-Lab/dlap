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

package com.dlap.reporting.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The normal form of principal ids (enforcement-contract.md section 6.1). */
class PrincipalKeysTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // GCP: IAM member prefixes removed, emails lower-cased.
            "serviceAccount:Agent@acme-prod.iam.gserviceaccount.com|agent@acme-prod.iam.gserviceaccount.com",
            "  agent@acme-prod.iam.gserviceaccount.com  |agent@acme-prod.iam.gserviceaccount.com",
            "user:Alice@Example.COM|alice@example.com",
            "GROUP:data-team@example.com|data-team@example.com",
            // Entra object ids are GUIDs: lower-cased.
            "6F1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D|6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
            // ARNs and principal:// subjects keep their case.
            "arn:aws:iam::123456789012:role/Analytics-Reader|arn:aws:iam::123456789012:role/Analytics-Reader",
            "principal://iam.googleapis.com/projects/1/locations/global/workloadIdentityPools/p/subject/S1"
                    + "|principal://iam.googleapis.com/projects/1/locations/global/workloadIdentityPools/p/subject/S1",
            // A DLAP subject is kept as written.
            "svc-marketing-agent|svc-marketing-agent"
    })
    void key_normalizes(String raw, String expected) {
        assertThat(PrincipalKeys.key(raw)).isEqualTo(expected);
    }

    @Test
    void key_ofBlankOrNull_isEmpty() {
        assertThat(PrincipalKeys.key(null)).isEmpty();
        assertThat(PrincipalKeys.key("   ")).isEmpty();
        assertThat(PrincipalKeys.candidates(" ")).isEmpty();
    }

    @Test
    void key_isIdempotent() {
        String once = PrincipalKeys.key("serviceAccount:Agent@P.iam.gserviceaccount.com");
        assertThat(PrincipalKeys.key(once)).isEqualTo(once);
    }

    @Test
    void candidates_ofAssumedRoleSession_addTheRoleArn() {
        assertThat(PrincipalKeys.candidates("arn:aws:sts::123456789012:assumed-role/analytics-reader/alice@example.com"))
                .containsExactly(
                        "arn:aws:sts::123456789012:assumed-role/analytics-reader/alice@example.com",
                        "arn:aws:iam::123456789012:role/analytics-reader");
    }

    @Test
    void candidates_ofGovCloudAssumedRole_keepThePartition() {
        assertThat(PrincipalKeys.candidates("arn:aws-us-gov:sts::123456789012:assumed-role/r/s"))
                .containsExactly("arn:aws-us-gov:sts::123456789012:assumed-role/r/s",
                        "arn:aws-us-gov:iam::123456789012:role/r");
    }

    @Test
    void key_ofAnIdWithInnerWhitespace_isNotAnEmail_andKeepsItsCase() {
        assertThat(PrincipalKeys.key("Ops\tTeam@Example.com")).isEqualTo("Ops\tTeam@Example.com");
        assertThat(PrincipalKeys.key("Ops Team@Example.com")).isEqualTo("Ops Team@Example.com");
    }

    @Test
    void candidates_ofAnythingElse_areTheKeyAlone() {
        assertThat(PrincipalKeys.candidates("user:Bob@Example.com")).containsExactly("bob@example.com");
        assertThat(PrincipalKeys.candidates("arn:aws:iam::123456789012:role/r"))
                .containsExactly("arn:aws:iam::123456789012:role/r");
    }
}
