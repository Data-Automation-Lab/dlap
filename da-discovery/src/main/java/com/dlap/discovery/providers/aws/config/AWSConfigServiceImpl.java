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

import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.model.ResourceType;

/**
 * Implementation of the AWS Cloud configuration service.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class AWSConfigServiceImpl implements AWSConfigService {

    @Value("${aws.access-key:#{null}}")
    private String accessKey;

    @Value("${aws.secret-key:#{null}}")
    private String secretKey;

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${aws.s3.bucket-name:discovery-data}")
    private String s3BucketName;

    @Value("${aws.dynamodb.table-name:resources}")
    private String dynamoDBTableName;

    @Value("${aws.ssm.enabled:false}")
    private boolean ssmEnabled;

    @Value("${aws.ssm.prefix:/da-discovery/}")
    private String ssmPrefix;

    @Value("${aws.account-id:#{null}}")
    private String accountId;

    @Value("${aws.filter-map:#{null}}")
    private Map<String, Map<String, String>> filterMap;

    @Override
    public String getAccessKey() {
        return accessKey;
    }

    @Override
    public String getSecretKey() {
        return secretKey;
    }

    @Override
    public String getRegion() {
        return region;
    }

    @Override
    public String getS3BucketName() {
        return s3BucketName;
    }

    @Override
    public String getDynamoDBTableName() {
        return dynamoDBTableName;
    }

    @Override
    public boolean isSsmEnabled() {
        return ssmEnabled;
    }

    @Override
    public String getSsmPrefix() {
        return ssmPrefix;
    }

    @Override
    public String getAccountId() {
        return accountId;
    }

    @Override
    public Map<String, String> getTags(ResourceType resourceType) {
        if (this.filterMap == null || resourceType == null) {
            return Collections.emptyMap();
        }
        Map<String, String> tags = this.filterMap.get(resourceType.id());
        return tags != null ? Collections.unmodifiableMap(tags) : Collections.emptyMap();
    }

}