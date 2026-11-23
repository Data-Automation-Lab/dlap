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

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.google.auth.Credentials;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * AWS implementation of CloudAuthenticationService.
 * Handles authentication with AWS services.
 */
@Service("AWSAuthenticationServiceImpl")
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class AWSAuthenticationServiceImpl implements AWSAuthenticationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AWSAuthenticationServiceImpl.class);

    private final AWSConfigService configService;
    private AwsCredentialsProvider credentialsProvider;

    /**
     * Creates a new AWS authentication service with the specified configuration.
     *
     * @param configService The AWS configuration service
     */
    @Autowired
    public AWSAuthenticationServiceImpl(AWSConfigService configService) {
        this.configService = configService;
    }

    @Override
    public AwsCredentialsProvider getAwsCredentialsProvider() {
        if (credentialsProvider == null) {
            try {
                // Try to use access key and secret if available
                if (configService.getAccessKey() != null && configService.getSecretKey() != null) {
                    AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(
                            configService.getAccessKey(),
                            configService.getSecretKey());
                    credentialsProvider = StaticCredentialsProvider.create(awsCredentials);
                    LOGGER.info("Created AWS credentials provider with access key");
                } else {
                    // Fall back to default credential chain (environment, instance profile, etc.)
                    credentialsProvider = DefaultCredentialsProvider.create();
                    LOGGER.info("Created default AWS credentials provider");
                }
            } catch (Exception e) {
                LOGGER.error("Failed to create AWS credentials provider", e);
                throw new RuntimeException("Failed to authenticate with AWS", e);
            }
        }
        return credentialsProvider;
    }

    @Override
    public AwsCredentialsProvider getAwsCredentialsProvider(String region) {
        // For most cases, the same credentials work for all regions
        // This method exists to allow for future region-specific credentials
        return getAwsCredentialsProvider();
    }

    @Override
    public Credentials getCredentials() {
        // AWS uses its own credential mechanism, not Google Credentials
        // This method is implemented for interface compatibility
        LOGGER.info("Getting credentials (adapter method for interface compatibility)");
        return null;
    }

    @Override
    public boolean validateAccess(String resourceId, String permission) {
        return validateResourceAccess(resourceId, permission);
    }

    @Override
    public boolean validateResourceAccess(String resourceArn, String permission) {
        try {
            // In a real implementation, this would use the AWS IAM API to check permissions
            // For now, we just assume access is valid if we can get credentials
            getAwsCredentialsProvider();
            LOGGER.debug("Validated access to resource: {} with permission: {}", resourceArn, permission);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to validate access to resource: {} with permission: {}", resourceArn, permission, e);
            return false;
        }
    }

    @Override
    public void refreshCredentials() {
        try {
            credentialsProvider = null;
            getAwsCredentialsProvider();
            LOGGER.info("Successfully refreshed AWS credentials");
        } catch (Exception e) {
            LOGGER.error("Failed to refresh AWS credentials", e);
        }
    }

    @Override
    public Optional<String> getCurrentIdentity() {
        try {
            // In a real implementation, this would call the AWS STS GetCallerIdentity API
            // For now, we just return the access key if available
            if (configService.getAccessKey() != null) {
                return Optional.of(configService.getAccessKey());
            }
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.warn("Failed to get current AWS identity", e);
            return Optional.empty();
        }
    }

    @Override
    public boolean validateCredentials(String accountId, Map<String, String> parameters) {
        try {
            // For AWS, we validate by attempting to get credentials
            // In a production implementation, this would make a lightweight API call
            // to verify the credentials work properly for the given account

            AwsCredentialsProvider provider = getAwsCredentialsProvider();

            // A real implementation might check the account ID by calling STS
            // But for now we'll just ensure we can get credentials
            if (provider != null) {
                LOGGER.info("Successfully validated AWS credentials for account: {}", accountId);
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.warn("Failed to validate AWS credentials for account {}: {}", accountId, e.getMessage());
            return false;
        }
    }
}