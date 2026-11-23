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

package com.dlap.common.constants;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the topic names reserved by ADR-0045 and keeps the topic catalog
 * collision-free: other packages and other languages hard-code these strings.
 */
class KafkaTopicConstantsTest {

    @Test
    void reservedLogLayerTopicsKeepTheirNames() {
        assertThat(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED).isEqualTo("dlap.log.event.normalized");
        assertThat(DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE).isEqualTo("dlap.log.event.quarantine");
        assertThat(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS).isEqualTo("dlap.logingest.binding.status");
        assertThat(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE)
                .isEqualTo("dlap.catalog.asset.retention-due");
    }

    @Test
    void everyTopicIsUniqueAndNamespaced() throws IllegalAccessException {
        List<String> topics = new ArrayList<>();
        for (Field f : DlapConstants.KafkaTopic.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                topics.add((String) f.get(null));
            }
        }

        assertThat(topics).hasSizeGreaterThanOrEqualTo(20).doesNotHaveDuplicates();
        assertThat(topics).allSatisfy(t -> assertThat(t).matches("dlap([.][a-z0-9-]+)+"));
    }
}
