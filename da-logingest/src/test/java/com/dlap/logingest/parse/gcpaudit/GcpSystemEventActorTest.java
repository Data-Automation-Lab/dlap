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

package com.dlap.logingest.parse.gcpaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.proto.log.v1.PrincipalType;
import org.junit.jupiter.api.Test;

/**
 * PARSE-02 (Q-596): an entry of the System Event log names no caller, because Google's systems took
 * the action; its actor is {@code SYSTEM}, which is how BigQuery table expiration reads as a native
 * rule. Entries of the other logs, and System Event entries that do name a caller, keep their actor.
 */
class GcpSystemEventActorTest {

    private static final String SYSTEM_EVENT = "projects/proj-0d1a0001/logs/cloudaudit.googleapis.com%2Fsystem_event";
    private static final String ACTIVITY = "projects/proj-0d1a0001/logs/cloudaudit.googleapis.com%2Factivity";

    @Test
    void aSystemEventEntryWithoutACallerIsTheSystem() {
        assertThat(GcpPrincipals.of("", SYSTEM_EVENT))
                .isEqualTo(new CanonicalEvent.Actor(PrincipalType.SYSTEM, "", "", ""));
    }

    @Test
    void anAdminActivityEntryWithoutACallerStaysUnknown() {
        assertThat(GcpPrincipals.of("", ACTIVITY)).isEqualTo(CanonicalEvent.Actor.UNKNOWN);
        assertThat(GcpPrincipals.of("", null)).isEqualTo(CanonicalEvent.Actor.UNKNOWN);
    }

    @Test
    void aSystemEventEntryThatNamesSomeoneKeepsThatActor() {
        assertThat(GcpPrincipals.of("analyst@example.com", SYSTEM_EVENT).type()).isEqualTo(PrincipalType.USER);
        CanonicalEvent.Actor masked = GcpPrincipals.of("***@example.com", SYSTEM_EVENT);
        assertThat(masked.type()).as("a masked caller is somebody, not the system")
                .isEqualTo(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
        assertThat(masked.displayName()).isEqualTo("***@example.com");
    }
}
