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

package com.dlap.policyengine.approval;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.kafka.DlapKafkaFactoryBuilder;
import com.dlap.policyengine.config.KafkaTopicsConfiguration;

/**
 * Wiring of the approval service: its settings ({@link ApprovalProperties}) and the topics it
 * produces. The lock provider of {@link ApprovalMaintenance} is the service's one ShedLock provider
 * (POL-01, table {@code shedlock} of Flyway V6).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalConfiguration {

    /**
     * The writer of the {@code govern.approval} and {@code govern.policy} audit streams (AUD-01,
     * docs/specs/audit-log.md section 7). The audit log lives in the shared {@code dlap_common} database and only the
     * stream's writer role may append. That role is this service's own database user
     * {@code da_policyengine_user}, so the connection reuses the service's user and password against
     * the {@code dlap_common} database on the same server; set {@code dlap.audit.datasource.*} to
     * point elsewhere. The data source opens a connection per append and none at start-up, so the
     * service starts without the audit database; the first decision, withdrawal or policy change then fails closed.
     *
     * @param configuredUrl {@code dlap.audit.datasource.url}; empty derives it from the main data source
     * @param mainUrl       {@code spring.datasource.url}
     * @param username      the writer role; defaults to the service's database user
     * @param password      its password; defaults to the service's database password
     * @param clock         the clock for {@code recordedAt}
     * @return the writer
     */
    @Bean
    AuditLogWriter approvalAuditLogWriter(
            @Value("${dlap.audit.datasource.url:}") String configuredUrl,
            @Value("${spring.datasource.url:}") String mainUrl,
            @Value("${dlap.audit.datasource.username:${spring.datasource.username:}}") String username,
            @Value("${dlap.audit.datasource.password:${spring.datasource.password:}}") String password,
            Clock clock) {
        return new AuditLogWriter(new DriverManagerDataSource(ApprovalAudit.commonUrl(configuredUrl, mainUrl),
                username, password), clock);
    }

    /**
     * The reader of stored audit entries by idempotency key, on the same connection settings as
     * {@link #approvalAuditLogWriter}: a retried decision, withdrawal or policy change replays the entry
     * an uncommitted earlier attempt left in {@code govern.approval} or {@code govern.policy} (Q-692).
     * Opens no connection at start-up.
     *
     * @param configuredUrl {@code dlap.audit.datasource.url}; empty derives it from the main data source
     * @param mainUrl       {@code spring.datasource.url}
     * @param username      the writer role; defaults to the service's database user
     * @param password      its password; defaults to the service's database password
     * @return the lookup
     */
    @Bean
    AuditKeyLookup governanceAuditKeyLookup(
            @Value("${dlap.audit.datasource.url:}") String configuredUrl,
            @Value("${spring.datasource.url:}") String mainUrl,
            @Value("${dlap.audit.datasource.username:${spring.datasource.username:}}") String username,
            @Value("${dlap.audit.datasource.password:${spring.datasource.password:}}") String password) {
        return new AuditKeyLookup(new DriverManagerDataSource(ApprovalAudit.commonUrl(configuredUrl, mainUrl),
                username, password));
    }

    /**
     * The approval topics and the dead-letter topic of {@code dlap.approvals.decided}, the one topic
     * executors consume with a dead-letter handler. Declared with the same settings and the same
     * switch as {@link KafkaTopicsConfiguration} ({@code dlap.kafka.provision-topics}, Q-336).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dlap.kafka", name = "provision-topics", havingValue = "true",
            matchIfMissing = true)
    static class Topics {

        /**
         * {@code dlap.approvals.requested}.
         *
         * @param settings the topic settings
         * @return the topic
         */
        @Bean
        NewTopic approvalRequestedTopic(KafkaTopicsConfiguration.Settings settings) {
            return topic(DlapConstants.KafkaTopic.APPROVAL_REQUESTED, settings);
        }

        /**
         * {@code dlap.approvals.decided}.
         *
         * @param settings the topic settings
         * @return the topic
         */
        @Bean
        NewTopic approvalDecidedTopic(KafkaTopicsConfiguration.Settings settings) {
            return topic(DlapConstants.KafkaTopic.APPROVAL_DECIDED, settings);
        }

        /**
         * Dead-letter topic of {@code dlap.approvals.decided}.
         *
         * @param settings the topic settings
         * @return the topic
         */
        @Bean
        NewTopic approvalDecidedDltTopic(KafkaTopicsConfiguration.Settings settings) {
            return new NewTopic(DlapConstants.KafkaTopic.APPROVAL_DECIDED + DlapKafkaFactoryBuilder.DEAD_LETTER_SUFFIX,
                    Optional.of(settings.dltPartitions()), replicas(settings.topicReplicationFactor()))
                    .configs(Map.of(TopicConfig.RETENTION_MS_CONFIG, Long.toString(settings.dltRetention().toMillis())));
        }

        /**
         * {@code dlap.approvals.break-glass}.
         *
         * @param settings the topic settings
         * @return the topic
         */
        @Bean
        NewTopic approvalBreakGlassTopic(KafkaTopicsConfiguration.Settings settings) {
            return topic(DlapConstants.KafkaTopic.APPROVAL_BREAK_GLASS, settings);
        }

        private static NewTopic topic(String name, KafkaTopicsConfiguration.Settings settings) {
            Optional<Integer> partitions = settings.topicPartitions() > 0
                    ? Optional.of(settings.topicPartitions()) : Optional.empty();
            return new NewTopic(name, partitions, replicas(settings.topicReplicationFactor()));
        }

        private static Optional<Short> replicas(int replicationFactor) {
            return replicationFactor > 0 ? Optional.of((short) replicationFactor) : Optional.empty();
        }
    }
}
