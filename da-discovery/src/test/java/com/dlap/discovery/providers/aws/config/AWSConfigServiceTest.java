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

package com.dlap.discovery.providers.aws.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.discovery.common.model.ResourceType;

class AWSConfigServiceTest {

    private AWSConfigService awsConfigService;

    @BeforeEach
    void setUp() {
        // Create a simple mock implementation for testing
        awsConfigService = new AWSConfigService() {
            @Override
            public String getAccessKey() {
                return "test-access-key";
            }

            @Override
            public String getSecretKey() {
                return "test-secret-key";
            }

            @Override
            public String getRegion() {
                return "us-west-2";
            }

            @Override
            public String getS3BucketName() {
                return "test-bucket";
            }

            @Override
            public String getDynamoDBTableName() {
                return "test-table";
            }

            @Override
            public boolean isSsmEnabled() {
                return false;
            }

            @Override
            public String getSsmPrefix() {
                return "/test-prefix/";
            }

            @Override
            public Map<String, String> getTags(ResourceType resourceType) {
                return Collections.emptyMap();
            }

            @Override
            public String getAccountId() {
                return "test-account-id";
            }
        };
    }

    @Test
    void getAccessKey_shouldReturnConfiguredValue() {
        assertEquals("test-access-key", awsConfigService.getAccessKey());
    }

    @Test
    void getSecretKey_shouldReturnConfiguredValue() {
        assertEquals("test-secret-key", awsConfigService.getSecretKey());
    }

    @Test
    void getRegion_shouldReturnConfiguredValue() {
        assertEquals("us-west-2", awsConfigService.getRegion());
    }

    @Test
    void getS3BucketName_shouldReturnConfiguredValue() {
        assertEquals("test-bucket", awsConfigService.getS3BucketName());
    }

    @Test
    void getDynamoDBTableName_shouldReturnConfiguredValue() {
        assertEquals("test-table", awsConfigService.getDynamoDBTableName());
    }

    @Test
    void isSsmEnabled_shouldReturnConfiguredValue() {
        assertFalse(awsConfigService.isSsmEnabled());
    }

    @Test
    void getSsmPrefix_shouldReturnConfiguredValue() {
        assertEquals("/test-prefix/", awsConfigService.getSsmPrefix());
    }
}