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

package com.dlap.discovery.providers.aws.auth;

import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.google.auth.Credentials;
import java.util.Optional;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Service interface for AWS Cloud authentication operations.
 */
public interface AWSAuthenticationService extends CloudAuthenticationService {
    
    /**
     * Gets AWS credentials provider for authentication with AWS services.
     *
     * @return The AWS credentials provider
     */
    AwsCredentialsProvider getAwsCredentialsProvider();
    
    /**
     * Gets AWS credentials provider for a specific service or region.
     *
     * @param region The AWS region to get credentials for
     * @return The AWS credentials provider
     */
    AwsCredentialsProvider getAwsCredentialsProvider(String region);
    
    /**
     * Validates access to the specified AWS resource.
     *
     * @param resourceArn The AWS resource ARN to validate access for
     * @param permission The permission to validate (e.g., "s3:GetObject")
     * @return true if the authenticated identity has the specified permission on the resource
     */
    boolean validateResourceAccess(String resourceArn, String permission);
} 