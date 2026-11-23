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

package com.dlap.adminservice.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogReader;

/** The scheduled job never throws, and a signer without a key or sink refuses to run. */
class CheckpointSchedulerTest {

    @Test
    void aFailedOrSkippedRunIsLoggedAndTheNextRunStillHappens() {
        CheckpointService service = mock(CheckpointService.class);
        given(service.run()).willThrow(new AuditException("dlap_common is down", null))
                .willReturn(new CheckpointService.CheckpointRun(Instant.EPOCH, "another instance", List.of(), 0, 0, 0, null))
                .willReturn(new CheckpointService.CheckpointRun(Instant.EPOCH, null, List.of(
                        new CheckpointService.StreamResult("a.b", CheckpointService.StreamStatus.SIGNED, 1L, null),
                        new CheckpointService.StreamResult("a.c", CheckpointService.StreamStatus.BROKEN, 2L, "x")),
                        1, 0, 0, null));
        CheckpointScheduler job = new CheckpointScheduler(service);
        assertThatCode(job::runScheduled).doesNotThrowAnyException();
        assertThatCode(job::runScheduled).doesNotThrowAnyException();
        assertThatCode(job::runScheduled).doesNotThrowAnyException();
        verify(service, times(3)).run();
    }

    @Test
    void aSignerWithoutKeyOrSinkRefusesToRun() {
        CheckpointService noKey = new CheckpointService(mock(AuditLogReader.class), mock(DataSource.class),
                mock(CheckpointStore.class), new CheckpointKeys(null, Map.of()), null, new AuditChainVerifier(),
                Clock.systemUTC());
        assertThatThrownBy(noKey::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("signing key");
        var pair = AuditTestKeys.newPair();
        CheckpointKeys keys = new CheckpointKeys(new CheckpointKeys.SigningKey(pair.getPrivate(), pair.getPublic(),
                com.dlap.common.audit.CheckpointSignatures.keyId(pair.getPublic())), Map.of());
        CheckpointService noSink = new CheckpointService(mock(AuditLogReader.class), mock(DataSource.class),
                mock(CheckpointStore.class), keys, null, new AuditChainVerifier(), Clock.systemUTC());
        assertThatThrownBy(noSink::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("export sink");
    }
}
