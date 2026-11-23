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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Parsing of the identity map seed file. */
class IdentityMapSeedFileTest {

    @Test
    void parse_readsEntries_skippingCommentsAndBlankLines_andNormalizingKeys() {
        String text = "\uFEFF# DLAP identity map\r\n"
                + "\r\n"
                + "Provider, Principal, Subject\r\n"
                + "GCP,serviceAccount:Agent@acme-prod.iam.gserviceaccount.com,svc-marketing-agent\r\n"
                + "aws, arn:aws:iam::123456789012:role/analytics-reader , svc-analytics\n"
                + "# trailing comment\n"
                + "ANY,Alice@Example.com,alice\n";

        List<IdentityMapSeedFile.Entry> entries = IdentityMapSeedFile.parse(text);

        assertThat(entries).containsExactly(
                new IdentityMapSeedFile.Entry(IdentityProviderScope.GCP,
                        "agent@acme-prod.iam.gserviceaccount.com", "svc-marketing-agent"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.AWS,
                        "arn:aws:iam::123456789012:role/analytics-reader", "svc-analytics"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.ANY, "alice@example.com", "alice"));
    }

    @Test
    void parse_dropsAnExactDuplicate() {
        List<IdentityMapSeedFile.Entry> entries = IdentityMapSeedFile.parse(
                "provider,principal,subject\nGCP,a@x.com,s\nGCP,A@X.COM,s\n");

        assertThat(entries).hasSize(1);
    }

    @Test
    void parse_keepsTheSameKeyInTwoScopes() {
        List<IdentityMapSeedFile.Entry> entries = IdentityMapSeedFile.parse(
                "provider,principal,subject\nGCP,a@x.com,s1\nAZURE,a@x.com,s2\n");

        assertThat(entries).extracting(IdentityMapSeedFile.Entry::subjectId).containsExactly("s1", "s2");
    }

    @Test
    void parse_rejectsAKeyMappedToTwoSubjects() {
        assertThatThrownBy(() -> IdentityMapSeedFile.parse(
                "provider,principal,subject\nGCP,a@x.com,s1\nGCP,user:a@x.com,s2\n"))
                .isInstanceOf(IdentityMapSeedFile.InvalidSeedFileException.class)
                .hasMessageContaining("line 3");
    }

    @Test
    void parse_rejectsAMissingHeader() {
        assertThatThrownBy(() -> IdentityMapSeedFile.parse("GCP,a@x.com,s1\n"))
                .isInstanceOf(IdentityMapSeedFile.InvalidSeedFileException.class)
                .hasMessageContaining("line 1");
        assertThatThrownBy(() -> IdentityMapSeedFile.parse("# only a comment\n"))
                .isInstanceOf(IdentityMapSeedFile.InvalidSeedFileException.class)
                .hasMessageContaining("no header");
    }

    @Test
    void parse_rejectsMalformedLines_namingTheLine() {
        String header = "provider,principal,subject\n";
        assertThatThrownBy(() -> IdentityMapSeedFile.parse(header + "GCP,a@x.com\n"))
                .hasMessageContaining("line 2: expected 3 columns");
        assertThatThrownBy(() -> IdentityMapSeedFile.parse(header + "MARS,a@x.com,s\n"))
                .hasMessageContaining("line 2: unknown provider 'MARS'");
        assertThatThrownBy(() -> IdentityMapSeedFile.parse(header + "GCP, ,s\n"))
                .hasMessageContaining("line 2: the principal");
        assertThatThrownBy(() -> IdentityMapSeedFile.parse(header + "GCP,a@x.com, \n"))
                .hasMessageContaining("line 2: the subject");
        assertThatThrownBy(() -> IdentityMapSeedFile.parse(header + "GCP,a@x.com," + "s".repeat(513) + "\n"))
                .hasMessageContaining("line 2: the subject");
        assertThatThrownBy(() -> IdentityMapSeedFile.parse(header + "GCP," + "p".repeat(513) + ",s\n"))
                .hasMessageContaining("line 2: the principal");
    }

    @Test
    void parse_ofHeaderOnly_isEmpty() {
        assertThat(IdentityMapSeedFile.parse("provider,principal,subject")).isEmpty();
    }
}
