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

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// Import the new ResourceType record
import com.dlap.discovery.common.model.ResourceType;

/**
 * Service interface for providing access to AWS configuration properties.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public interface AWSConfigService {

    /**
     * Gets the AWS access key.
     *
     * @return The AWS access key
     */
    String getAccessKey();

    /**
     * Gets the AWS secret key.
     *
     * @return The AWS secret key
     */
    String getSecretKey();

    /**
     * Gets the default AWS region.
     *
     * @return The default AWS region
     */
    String getRegion();

    /**
     * Gets the S3 bucket name for discovery data.
     *
     * @return The S3 bucket name
     */
    String getS3BucketName();

    /**
     * Gets the DynamoDB table name for resources.
     *
     * @return The DynamoDB table name
     */
    String getDynamoDBTableName();

    /**
     * Checks if AWS SSM Parameter Store is enabled.
     *
     * @return true if SSM Parameter Store is enabled, false otherwise
     */
    boolean isSsmEnabled();

    /**
     * Gets the SSM parameter prefix.
     *
     * @return The SSM parameter prefix
     */
    String getSsmPrefix();

    String getAccountId();

    /**
     * Gets the tags for the given resource type.
     *
     * @param resourceType The resource type record
     * @return The tags
     */
    Map<String, String> getTags(ResourceType resourceType);
}