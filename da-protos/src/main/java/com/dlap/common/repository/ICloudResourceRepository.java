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

package com.dlap.common.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.dlap.common.model.entity.CloudResource;

/**
 * Repository for the shared {@link CloudResource} model (ADR-0028).
 *
 * <p>Optional adoption: services that need provider-native detail beyond
 * {@link com.dlap.common.model.entity.Asset} use this repository; the
 * auto-flow path continues to use {@code IAssetRepository}.
 */
@Repository
public interface ICloudResourceRepository
        extends JpaRepository<CloudResource, UUID>, JpaSpecificationExecutor<CloudResource> {

    /** Look up by provider-native identifier (ARN, self-link, OCID, …). */
    Optional<CloudResource> findByNativeResourceId(String nativeResourceId);

    /** All cloud resources linked to a canonical {@link CloudResource#getAssetId() asset}. */
    List<CloudResource> findByAssetId(UUID assetId);

    /** Direct children of a resource (asset-of-assets hierarchy). */
    List<CloudResource> findByParentResourceId(UUID parentResourceId);

    /** All resources for a cloud account. */
    List<CloudResource> findByAccountId(String accountId);
}
