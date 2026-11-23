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

package com.dlap.autoarchival.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArchiveAuditConfigurationTest {

    @Test
    void theAuditUrlIsTheCommonDatabaseOnTheServersOwnHost() {
        assertThat(ArchiveAuditConfiguration.commonUrl("", "jdbc:postgresql://postgresql:5432/da_autoarchival"))
                .isEqualTo("jdbc:postgresql://postgresql:5432/dlap_common");
        assertThat(ArchiveAuditConfiguration.commonUrl(null,
                "jdbc:postgresql://db.internal:5432/da_autoarchival?sslmode=verify-full&sslrootcert=/ca.pem"))
                .isEqualTo("jdbc:postgresql://db.internal:5432/dlap_common?sslmode=verify-full&sslrootcert=/ca.pem");
    }

    @Test
    void anExplicitUrlWinsAndAMissingOneFallsBackToLocalhost() {
        assertThat(ArchiveAuditConfiguration.commonUrl("jdbc:postgresql://audit:5432/dlap_common",
                "jdbc:postgresql://x:5432/da_autoarchival")).isEqualTo("jdbc:postgresql://audit:5432/dlap_common");
        assertThat(ArchiveAuditConfiguration.commonUrl("", "")).isEqualTo("jdbc:postgresql://localhost:5432/dlap_common");
    }
}
