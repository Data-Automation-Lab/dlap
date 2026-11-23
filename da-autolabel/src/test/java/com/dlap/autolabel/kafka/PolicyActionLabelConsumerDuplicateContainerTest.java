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

package com.dlap.autolabel.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.autolabel.client.dto.AssetLabelsPostFeignRequest;
import com.dlap.autolabel.client.feign.AssetCatalogClient;
import com.dlap.autolabel.repository.LabelingJobRepository;
import com.dlap.autolabel.service.IAutoLabelOrchestrator;
import com.dlap.autolabel.service.impl.LabelingRunServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * IDEM-01 (Q-856): {@link PolicyActionLabelConsumer} applies a policy {@code addLabel} fire once. Delivery on
 * {@code dlap.policies.actions} is at least once and the policy engine republishes a fire with the same
 * {@code fireId} when its source event is redelivered. The consumer, the service and the repository are the
 * module's own on PostgreSQL with the module's Flyway schema (V2 adds the unique {@code fire_id}); only the
 * catalog client and the orchestrator are doubles. Without the fix every copy posted the label again and wrote
 * another job row, which the dashboard counted as another labeled asset. Requires Docker.
 */
@DataJpaTest
@org.springframework.test.context.ContextConfiguration(
        classes = PolicyActionLabelConsumerDuplicateContainerTest.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PolicyActionLabelConsumerDuplicateContainerTest {

    /** JPA, the repository, the service and the consumer; the catalog client and the orchestrator are doubles. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.dlap.autolabel.entity")
    @EnableJpaRepositories(basePackageClasses = LabelingJobRepository.class)
    @Import({LabelingRunServiceImpl.class, PolicyActionLabelConsumer.class})
    static class Config {

        @Bean
        AssetCatalogClient catalogClient() {
            return mock(AssetCatalogClient.class);
        }

        @Bean
        IAutoLabelOrchestrator orchestrator() {
            return mock(IAutoLabelOrchestrator.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final UUID ASSET = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Autowired
    private PolicyActionLabelConsumer consumer;

    @Autowired
    private AssetCatalogClient catalog;

    @Autowired
    private com.dlap.autolabel.service.ILabelingRunService labelingRunService;

    @Autowired
    private LabelingJobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    private final Acknowledgment ack = mock(Acknowledgment.class);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_autolabel.dlap_autolabel_job_errors");
        jdbc.update("DELETE FROM dlap_autolabel.dlap_autolabel_job");
        org.mockito.Mockito.reset(catalog, ack);
    }

    private static String addLabel(String fireId) {
        String fire = fireId == null ? "" : "\"fireId\":\"" + fireId + "\",";
        return "{" + fire + "\"policyId\":\"pii-label\",\"policyName\":\"PII label\",\"assetId\":\"" + ASSET
                + "\",\"actionType\":\"addLabel\",\"actionParameters\":{\"labelName\":\"PII\"}}";
    }

    private long jobRows() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM dlap_autolabel.dlap_autolabel_job", Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void aRepeatedFireIdAppliesTheLabelAndRecordsTheJobOnce() {
        String fire = UUID.randomUUID().toString();

        consumer.onPolicyAction(addLabel(fire), ack);
        consumer.onPolicyAction(addLabel(fire), ack);
        consumer.onPolicyAction(addLabel(fire), ack);

        verify(catalog, times(1)).addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));
        assertThat(jobRows()).as("job rows").isEqualTo(1);
        assertThat(jobs.countTotalAssetsSuccessfullyLabeled()).as("dashboard KPI: assets labeled").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT fire_id FROM dlap_autolabel.dlap_autolabel_job", String.class))
                .isEqualTo(fire);
        verify(ack, times(3)).acknowledge();
    }

    @Test
    void twoDifferentFiresAreTwoJobs() {
        consumer.onPolicyAction(addLabel(UUID.randomUUID().toString()), ack);
        consumer.onPolicyAction(addLabel(UUID.randomUUID().toString()), ack);

        verify(catalog, times(2)).addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));
        assertThat(jobRows()).isEqualTo(2);
    }

    @Test
    void anActionWithoutAFireIdIsStillAppliedEachTime() {
        consumer.onPolicyAction(addLabel(null), ack);
        consumer.onPolicyAction(addLabel(null), ack);

        verify(catalog, times(2)).addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));
        assertThat(jobRows()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autolabel.dlap_autolabel_job WHERE fire_id IS NULL",
                Long.class)).isEqualTo(2L);
    }

    @Test
    void aFireWhoseCatalogCallFailedLeavesNoClaimSoTheRedeliveryApplies() {
        String fire = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("catalog down")).when(catalog)
                .addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));

        assertThatThrownBy(() -> consumer.onPolicyAction(addLabel(fire), ack)).isInstanceOf(RuntimeException.class);
        verify(ack, never()).acknowledge();
        assertThat(jobRows()).as("the failed attempt is rolled back, claim included").isZero();

        doNothing().when(catalog).addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));
        consumer.onPolicyAction(addLabel(fire), ack);
        consumer.onPolicyAction(addLabel(fire), ack);

        assertThat(jobRows()).isEqualTo(1);
        verify(ack, times(2)).acknowledge();
    }

    private static String addLabelRaw(String fireIdJson) {
        return "{\"fireId\":" + fireIdJson + ",\"policyId\":\"pii-label\",\"policyName\":\"PII label\",\"assetId\":\""
                + ASSET + "\",\"actionType\":\"addLabel\",\"actionParameters\":{\"labelName\":\"PII\"}}";
    }

    /** IDEM-02 (Q-890): blank fireIds were real keys, so all of them collapsed into one job. */
    @Test
    void blankFireIdsAreNoKeySoEachActionIsAppliedAndNoneCollapses() {
        consumer.onPolicyAction(addLabelRaw("\"\""), ack);
        consumer.onPolicyAction(addLabelRaw("\"   \""), ack);
        consumer.onPolicyAction(addLabelRaw("\" \""), ack);

        verify(catalog, times(3)).addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));
        assertThat(jobRows()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autolabel.dlap_autolabel_job WHERE fire_id IS NULL",
                Long.class)).as("no blank value was stored as a key").isEqualTo(3L);
        verify(ack, times(3)).acknowledge();
    }

    /** IDEM-02 (Q-890): the padded and the bare spelling of one fire are the same fire. */
    @Test
    void aPaddedFireIdIsTheSameFireAsTheBareOne() {
        String fire = UUID.randomUUID().toString();

        consumer.onPolicyAction(addLabelRaw("\"" + fire + "\""), ack);
        consumer.onPolicyAction(addLabelRaw("\"  " + fire + " \""), ack);

        verify(catalog, times(1)).addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));
        assertThat(jobRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT fire_id FROM dlap_autolabel.dlap_autolabel_job", String.class))
                .isEqualTo(fire);
    }

    /** IDEM-02 (Q-890): a fireId that does not fit the column used to fail the insert on every retry. */
    @Test
    void anOverlongFireIdIsAcknowledgedWithoutACatalogCallOrAJobRow() {
        consumer.onPolicyAction(addLabelRaw("\"" + "f".repeat(256) + "\""), ack);

        verify(catalog, never()).addLabelsToAsset(any(), any(AssetLabelsPostFeignRequest.class));
        assertThat(jobRows()).isZero();
        verify(ack, times(1)).acknowledge();
    }

    /** IDEM-02: a fireId of exactly the column width is stored and deduplicated. */
    @Test
    void aFireIdOf255CharactersIsStoredAndDeduplicated() {
        String max = "f".repeat(255);

        consumer.onPolicyAction(addLabelRaw("\"" + max + "\""), ack);
        consumer.onPolicyAction(addLabelRaw("\"" + max + "\""), ack);

        verify(catalog, times(1)).addLabelsToAsset(eq(ASSET), any(AssetLabelsPostFeignRequest.class));
        assertThat(jobRows()).isEqualTo(1);
    }

    /** IDEM-02 (Q-890): the service applies the same rule to a caller other than the consumer. */
    @Test
    void theServiceTreatsABlankFireIdAsAbsentAndTrimsAPaddedOne() {
        labelingRunService.applyPolicyLabel(ASSET, "PII", "p", "P", "  ");
        labelingRunService.applyPolicyLabel(ASSET, "PII", "p", "P", "");
        labelingRunService.applyPolicyLabel(ASSET, "PII", "p", "P", "  fire-1 ");
        labelingRunService.applyPolicyLabel(ASSET, "PII", "p", "P", "fire-1");

        assertThat(jobRows()).as("two keyless jobs and one job for fire-1").isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autolabel.dlap_autolabel_job WHERE fire_id = 'fire-1'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autolabel.dlap_autolabel_job WHERE fire_id IS NULL",
                Long.class)).isEqualTo(2L);
    }
}
