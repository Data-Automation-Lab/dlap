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

package com.dlap.discovery.crawler.model.aws;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.annotation.JsonTypeName;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Represents a generic Amazon Web Services (AWS) resource.
 * Serves as a base for more specific AWS resource types (e.g., EC2, S3).
 */
@Entity
@Table(name = "aws_resource")
@JsonTypeName("AWS") // Keep for Jackson if used
public class AwsResource extends CloudResource {

    /**
     * Constructor for AWSResource.
     *
     * @param resourceTypeRecord The ResourceType record for this AWS resource.
     * @param awsResourceId      The unique AWS resource ID (e.g., instance ID,
     *                           ARN).
     * @param name               The name tag or other identifier.
     */
    public AwsResource(ResourceType resourceTypeRecord, String awsResourceId, String name) {
        super(resourceTypeRecord, awsResourceId, name);
        // Validate provider
        if (getCloudProviderEnum() != CloudProvider.AWS) {
            throw new IllegalArgumentException(
                    "ResourceType must be for AWS provider. Found: " + getCloudProviderEnum());
        }
    }

    /**
     * No-arg constructor required by JPA.
     */
    protected AwsResource() {
        super();
    }

    // AWS-specific common fields could be added here and mapped with @Column
    // For example:
    // @Column(name = "aws_arn")
    // private String arn;

    // Getters/Setters for any AWS-specific fields...

    /**
     * Returns the provider-specific type identifier.
     * Marked transient as it uses getTypeId() which is already mapped.
     *
     * @return A string like "AWS::EC2::Instance" or "AWS::aws_s3_bucket"
     */
    @Override
    @Transient // Mark as transient, derived from persisted typeId
    public String getProviderSpecificType() {
        String typeId = getTypeId(); // Use mapped field
        if (typeId != null) {
            // Example specific formatting (could be adjusted)
            if (EC2Resource.isEc2TypeId(typeId)) {
                return "AWS::EC2::Instance";
            } else if ("aws_s3_bucket".equals(typeId)) { // TODO: Use constants
                return "AWS::S3::Bucket";
            }
            // Default format if no specific case matches
            return "AWS::" + typeId;
        }
        return "AWS::Unknown"; // Fallback if typeId is null
    }
}