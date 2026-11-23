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

import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.model.ResourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Amazon S3 bucket resource (JOINED-inheritance subtype of {@link AwsResource}).
 */
@Entity
@Table(name = "s3_resource")
@Getter
@Setter
@NoArgsConstructor
public class S3Resource extends AwsResource {

    /** Bucket versioning status (e.g. {@code Enabled}, {@code Suspended}, {@code Disabled}). */
    @Column(name = "s3_versioning_status")
    private String versioningStatus;

    /** Whether the bucket allows public access (best-effort from the public-access-block config). */
    @Column(name = "s3_is_public")
    private Boolean isPublic;

    /** Bucket encryption algorithm (e.g. {@code AES256}, {@code aws:kms}), or null if none. */
    @Column(name = "s3_encryption")
    private String encryption;

    public S3Resource(ResourceType resourceTypeRecord, String bucketName, String name) {
        super(resourceTypeRecord, bucketName, name);
        String typeId = getTypeId();
        if (!AWSConstants.RESOURCE_TYPE_S3_BUCKET.equals(typeId)) {
            throw new IllegalArgumentException(
                    "S3Resource requires ResourceType " + AWSConstants.RESOURCE_TYPE_S3_BUCKET
                            + " but got: " + typeId);
        }
    }
}
