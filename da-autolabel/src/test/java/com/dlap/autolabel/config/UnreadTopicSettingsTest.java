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
package com.dlap.autolabel.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.dlap.common.constants.DlapConstants;

/**
 * Q-417 follow-up: da-autolabel carried {@code app.kafka.topic.asset-change-event=dlap.assets.changes}, a
 * setting no code reads and a topic name nobody produces (catalog publishes asset changes to
 * {@link DlapConstants.KafkaTopic#ASSET_CHANGE_EVENT}). The line is gone; this keeps it from returning.
 */
class UnreadTopicSettingsTest {

    private static Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void theUnreadAssetChangeTopicSettingIsGone() throws IOException {
        Properties properties = applicationProperties();

        assertThat(properties).doesNotContainKey("app.kafka.topic.asset-change-event");
        assertThat(properties.values()).as("no setting names the retired topic").doesNotContain("dlap.assets.changes");
    }

    @Test
    void theNameTheCatalogPublishesToIsTheConstant() {
        assertThat(DlapConstants.KafkaTopic.ASSET_CHANGE_EVENT).isEqualTo("dlap.asset.change.event");
    }
}
