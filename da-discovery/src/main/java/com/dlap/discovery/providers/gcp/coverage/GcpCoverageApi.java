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

package com.dlap.discovery.providers.gcp.coverage;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The GCP reads of the coverage collector. Each method returns the API response as JSON in the
 * shape of the public API reference and throws
 * {@link com.dlap.discovery.coverage.MissingPermissionException} naming the IAM permission when the
 * call is refused.
 */
public interface GcpCoverageApi {

    /**
     * Resource Manager v3 {@code projects.get}.
     *
     * @param projectId the project id
     * @return the project, with {@code parent}
     */
    JsonNode getProject(String projectId);

    /**
     * Resource Manager v3 {@code folders.get}.
     *
     * @param folder {@code folders/<n>}
     * @return the folder, with {@code parent}
     */
    JsonNode getFolder(String folder);

    /**
     * Resource Manager v3 {@code getIamPolicy} of a project, folder or organization, policy version 3.
     *
     * @param resource {@code projects/<id>}, {@code folders/<n>} or {@code organizations/<n>}
     * @return the policy, with {@code auditConfigs}
     */
    JsonNode getIamPolicy(String resource);

    /**
     * Cloud Storage JSON API {@code buckets.list} of a project, every page.
     *
     * @param projectId the project id
     * @return the bucket resources
     */
    List<JsonNode> listBuckets(String projectId);

    /**
     * Cloud Storage JSON API {@code buckets.getIamPolicy}, policy version 3.
     *
     * @param bucket the bucket name
     * @return the policy, with {@code bindings}
     * @throws com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException when the call does
     *         not succeed for a reason other than a refusal (a 404 for a bucket deleted during the
     *         run, a 429, a 5xx); the collector marks that bucket unknown and goes on
     */
    JsonNode getBucketIamPolicy(String bucket);
}
