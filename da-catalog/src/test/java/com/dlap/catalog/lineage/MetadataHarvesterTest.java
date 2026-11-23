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

package com.dlap.catalog.lineage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetadataHarvester}. Plain JUnit with hand-built maps:
 * no Mockito.
 */
class MetadataHarvesterTest {

    private final MetadataHarvester harvester = new MetadataHarvester();

    @Test
    void harvestsOwnerTeamRegionProviderAccount_fromPrincipalAndTarget() {
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("id", "user:alice@data-eng.co");
        principal.put("type", "USER");
        principal.put("displayName", "alice@data-eng.co");
        principal.put("accountId", "proj-123");

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("region", "us-central1");
        target.put("project", "proj-xyz");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sourceProvider", "GCP");
        event.put("principal", principal);
        event.put("targetResource", target);

        Map<String, Object> harvested = harvester.harvest(event);

        assertThat(harvested).containsEntry("harvestedOwner", "alice@data-eng.co");
        assertThat(harvested).containsEntry("harvestedOwnerType", "USER");
        assertThat(harvested).containsEntry("harvestedTeam", "data-eng");
        assertThat(harvested).containsEntry("harvestedRegion", "us-central1");
        assertThat(harvested).containsEntry("harvestedProvider", "GCP");
        // accountId on the principal wins over targetResource.project.
        assertThat(harvested).containsEntry("harvestedAccount", "proj-123");
        assertThat(harvested).containsKey("harvestedAt");
    }

    @Test
    void stripsPrincipalPrefixFromIdWhenDisplayNameMissing() {
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("id", "serviceAccount:etl-runner@proj.iam.gserviceaccount.com");
        principal.put("type", "SERVICE_ACCOUNT");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("principal", principal);

        Map<String, Object> harvested = harvester.harvest(event);

        assertThat(harvested).containsEntry("harvestedOwner",
                "etl-runner@proj.iam.gserviceaccount.com");
        assertThat(harvested).containsEntry("harvestedOwnerType", "SERVICE_ACCOUNT");
        // Domain first label is the org signal for a service account.
        assertThat(harvested).containsEntry("harvestedTeam", "proj");
    }

    @Test
    void fallsBackToTargetProjectForAccountWhenPrincipalHasNone() {
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("displayName", "bob@acme.io");

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("project", "acme-prod");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("principal", principal);
        event.put("targetResource", target);

        Map<String, Object> harvested = harvester.harvest(event);

        assertThat(harvested).containsEntry("harvestedAccount", "acme-prod");
        assertThat(harvested).containsEntry("harvestedTeam", "acme");
    }

    @Test
    void teamInference_usesLocalPartForGenericMailHosts() {
        // A personal gmail address carries no org signal in the domain; the
        // local-part is the more useful team hint.
        assertThat(MetadataHarvester.inferTeam("carol@gmail.com")).isEqualTo("carol");
        assertThat(MetadataHarvester.inferTeam("dave@yahoo.com")).isEqualTo("dave");
    }

    @Test
    void teamInference_isDefensive() {
        assertThat(MetadataHarvester.inferTeam(null)).isNull();
        assertThat(MetadataHarvester.inferTeam("")).isNull();
        assertThat(MetadataHarvester.inferTeam("no-at-sign")).isNull();
        assertThat(MetadataHarvester.inferTeam("@leading-at.co")).isEqualTo("leading-at");
    }

    @Test
    void emptyKeysAreOmitted_soMergeNeverClobbersWithNulls() {
        // da-logingest fills absent fields with empty strings; those must not
        // become harvested entries.
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("id", "");
        principal.put("type", "");
        principal.put("displayName", "");
        principal.put("accountId", "");

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("region", "");
        target.put("project", "");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sourceProvider", "");
        event.put("principal", principal);
        event.put("targetResource", target);

        Map<String, Object> harvested = harvester.harvest(event);

        // Only the always-present timestamp survives.
        assertThat(harvested).containsOnlyKeys("harvestedAt");
    }

    @Test
    void nullEvent_yieldsEmptyMap() {
        assertThat(harvester.harvest(null)).isEmpty();
    }
}
