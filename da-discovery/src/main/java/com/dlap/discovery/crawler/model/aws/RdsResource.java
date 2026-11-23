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
 * Amazon RDS database instance (JOINED-inheritance subtype of {@link AwsResource}).
 */
@Entity
@Table(name = "rds_resource")
@Getter
@Setter
@NoArgsConstructor
public class RdsResource extends AwsResource {

    /** Database engine (e.g. {@code postgres}, {@code mysql}, {@code aurora-mysql}). */
    @Column(name = "rds_engine")
    private String engine;

    /** Engine version. */
    @Column(name = "rds_engine_version")
    private String engineVersion;

    /** Instance class (e.g. {@code db.t3.micro}). */
    @Column(name = "rds_instance_class")
    private String instanceClass;

    /** Instance status (e.g. {@code available}, {@code creating}). */
    @Column(name = "rds_status")
    private String status;

    /** Connection endpoint address. */
    @Column(name = "rds_endpoint")
    private String endpoint;

    /** Whether storage is encrypted at rest. */
    @Column(name = "rds_storage_encrypted")
    private Boolean storageEncrypted;

    public RdsResource(ResourceType resourceTypeRecord, String dbInstanceIdentifier, String name) {
        super(resourceTypeRecord, dbInstanceIdentifier, name);
        String typeId = getTypeId();
        if (!AWSConstants.RESOURCE_TYPE_RDS_INSTANCE.equals(typeId)) {
            throw new IllegalArgumentException(
                    "RdsResource requires ResourceType " + AWSConstants.RESOURCE_TYPE_RDS_INSTANCE
                            + " but got: " + typeId);
        }
    }
}
