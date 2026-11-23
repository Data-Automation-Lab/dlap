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

package com.dlap.policyengine.projection.unity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.projection.CompiledAccessPolicy;
import com.dlap.policyengine.projection.GrantIntent;

/**
 * The {@link UnityCatalogPolicyProjectionSink} renders intents to UC
 * {@code GRANT}/{@code REVOKE} statements against a mocked (stub) client
 * (ADR-0040 push-down mode). Verifies statement shape, allow→GRANT, deny→REVOKE,
 * label-securable → SCHEMA scope, and concrete-URI → TABLE scope.
 */
class UnityCatalogPolicyProjectionSinkTest {

    private UnityCatalogPolicyProjectionSink sink(StubUnityCatalogGrantClient client) {
        UnityCatalogGrantProperties props = new UnityCatalogGrantProperties();
        props.setCatalog("dlap");
        props.setSchema("gov");
        return new UnityCatalogPolicyProjectionSink(client, props);
    }

    @Test
    void allowIntent_emitsGrantToPrincipal_onTableSecurable() {
        StubUnityCatalogGrantClient client = new StubUnityCatalogGrantClient();
        CompiledAccessPolicy compiled = new CompiledAccessPolicy(
                "p1", "n1", "allow",
                List.of(GrantIntent.grant("DATA_ANALYST", "SELECT", "gs://eu/customers")),
                "labels:{anyOf=[EU_RESIDENT]}");

        sink(client).project(compiled);

        assertThat(client.executed()).hasSize(1);
        assertThat(client.executed().get(0))
                .isEqualTo("GRANT SELECT ON TABLE dlap.gov.customers TO `DATA_ANALYST`");
    }

    @Test
    void denyIntent_emitsRevokeFromPrincipal() {
        StubUnityCatalogGrantClient client = new StubUnityCatalogGrantClient();
        CompiledAccessPolicy compiled = new CompiledAccessPolicy(
                "p2", "n2", "deny",
                List.of(GrantIntent.revoke("DATA_SCIENTIST", "SELECT", "s3://bucket/pii")),
                "labels:{allOf=[EU_RESIDENT, PII]}");

        sink(client).project(compiled);

        assertThat(client.executed()).hasSize(1);
        assertThat(client.executed().get(0))
                .isEqualTo("REVOKE SELECT ON TABLE dlap.gov.pii FROM `DATA_SCIENTIST`");
    }

    @Test
    void labelSecurable_mapsToSchemaScope_withLabelComment() {
        StubUnityCatalogGrantClient client = new StubUnityCatalogGrantClient();
        CompiledAccessPolicy compiled = new CompiledAccessPolicy(
                "p3", "n3", "allow",
                List.of(GrantIntent.grant("tier:PRACTITIONER", "SELECT", "label:EU_RESIDENT")),
                "labels:{anyOf=[EU_RESIDENT]}");

        sink(client).project(compiled);

        assertThat(client.executed().get(0))
                .isEqualTo("GRANT SELECT ON SCHEMA dlap.gov /* dlap-label: EU_RESIDENT */ "
                        + "TO `tier:PRACTITIONER`");
    }

    @Test
    void revokeCall_reversesEachIntent() {
        StubUnityCatalogGrantClient client = new StubUnityCatalogGrantClient();
        CompiledAccessPolicy compiled = new CompiledAccessPolicy(
                "p4", "n4", "allow",
                List.of(GrantIntent.grant("AUDITOR", "SELECT", "s3://a/tbl")),
                "labels:{anyOf=[PII]}");

        sink(client).revoke(compiled);

        // The projected grant is undone -> REVOKE.
        assertThat(client.executed().get(0))
                .isEqualTo("REVOKE SELECT ON TABLE dlap.gov.tbl FROM `AUDITOR`");
    }

    @Test
    void emptyCompilation_emitsNoStatements() {
        StubUnityCatalogGrantClient client = new StubUnityCatalogGrantClient();
        sink(client).project(new CompiledAccessPolicy("p5", "n5", "allow", List.of(), "match:*"));

        assertThat(client.executed()).isEmpty();
    }

    @Test
    void sinkReportsEnabled() {
        assertThat(sink(new StubUnityCatalogGrantClient()).isEnabled()).isTrue();
    }
}
