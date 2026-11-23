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

package com.dlap.discovery.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to DG Crawler.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {

    private final Kafka kafka = new Kafka();
    private final Metrics metrics = new Metrics();
    private final Scheduler scheduler = new Scheduler();

    public Kafka getKafka() {
        return kafka;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public static class Kafka {

        private boolean enabled;
        private String bootstrapServers;
        private final Consumer consumer = new Consumer();
        private final Producer producer = new Producer();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public Consumer getConsumer() {
            return consumer;
        }

        public Producer getProducer() {
            return producer;
        }

        public static class Consumer {

            private String autoOffsetReset;
            private String groupId;
            private String keyDeserializer;
            private String valueDeserializer;

            public String getAutoOffsetReset() {
                return autoOffsetReset;
            }

            public void setAutoOffsetReset(String autoOffsetReset) {
                this.autoOffsetReset = autoOffsetReset;
            }

            public String getGroupId() {
                return groupId;
            }

            public void setGroupId(String groupId) {
                this.groupId = groupId;
            }

            public String getKeyDeserializer() {
                return keyDeserializer;
            }

            public void setKeyDeserializer(String keyDeserializer) {
                this.keyDeserializer = keyDeserializer;
            }

            public String getValueDeserializer() {
                return valueDeserializer;
            }

            public void setValueDeserializer(String valueDeserializer) {
                this.valueDeserializer = valueDeserializer;
            }
        }

        public static class Producer {

            private String keySerializer;
            private String valueSerializer;

            public String getKeySerializer() {
                return keySerializer;
            }

            public void setKeySerializer(String keySerializer) {
                this.keySerializer = keySerializer;
            }

            public String getValueSerializer() {
                return valueSerializer;
            }

            public void setValueSerializer(String valueSerializer) {
                this.valueSerializer = valueSerializer;
            }
        }
    }

    public static class Metrics {

        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Scheduler {

        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
    // jhipster-needle-application-properties-property
    // jhipster-needle-application-properties-property-getter
    // jhipster-needle-application-properties-property-class
}
