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

package com.dlap.policyengine.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dlap.policyengine.model.PolicyVersion;

/**
 * The immutable versions of policies (AUD-02a, Flyway V9). Rows are only ever inserted.
 */
@Repository
public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {

    /**
     * Every version of a policy, oldest first.
     *
     * @param policyId the policy
     * @return the versions
     */
    List<PolicyVersion> findByPolicyIdOrderByVersionAsc(UUID policyId);

    /**
     * One version of a policy.
     *
     * @param policyId the policy
     * @param version  the version number
     * @return the version
     */
    Optional<PolicyVersion> findByPolicyIdAndVersion(UUID policyId, int version);

    /**
     * The newest version of a policy.
     *
     * @param policyId the policy
     * @return the version, or empty when the policy has none
     */
    Optional<PolicyVersion> findFirstByPolicyIdOrderByVersionDesc(UUID policyId);
}
