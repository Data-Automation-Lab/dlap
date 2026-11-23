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

package com.dlap.logingest.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.lock.PgAdvisoryLocks;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Stream poll locks on a real PostgreSQL (Testcontainers; requires Docker): while one replica holds
 * the lock of a (binding, stream), another cannot take it; other streams are independent; closing
 * the lease releases it.
 */
class PgAdvisoryLocksTest {

    @Test
    void oneHolderPerStreamUntilReleased() {
        DataSource ds = LogIngestPostgres.newDatabase();
        PgAdvisoryLocks replicaA = new PgAdvisoryLocks(ds);
        PgAdvisoryLocks replicaB = new PgAdvisoryLocks(ds);

        Optional<AdvisoryLocks.Lease> a = replicaA.tryLockStream("aws-111111111111", "s3-file");
        assertThat(a).isPresent();
        assertThat(replicaB.tryLockStream("aws-111111111111", "s3-file")).as("held by replica A").isEmpty();
        Optional<AdvisoryLocks.Lease> other = replicaB.tryLockStream("aws-111111111111", "lookup-events");
        assertThat(other).as("another stream of the same binding").isPresent();

        a.get().close();
        a.get().close();
        Optional<AdvisoryLocks.Lease> again = replicaB.tryLockStream("aws-111111111111", "s3-file");
        assertThat(again).as("released by close").isPresent();
        again.get().close();
        other.get().close();
    }
}
