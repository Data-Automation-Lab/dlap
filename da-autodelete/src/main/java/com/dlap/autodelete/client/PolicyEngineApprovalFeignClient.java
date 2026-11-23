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

package com.dlap.autodelete.client;

import com.dlap.autodelete.dto.ApprovalCreateRequest;
import com.dlap.autodelete.dto.ApprovalViewDTO;
import com.dlap.autodelete.dto.ApprovalWithdrawRequest;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The approval API of da-policyengine (docs/specs/approvals.md section 8), called as the service
 * account: the shared Feign interceptor of da-protos ({@code dlap.service-auth.*}) attaches the
 * client-credentials token, so the policy engine sees {@code ROLE_SERVICE_ACCOUNT}.
 *
 * <p>Errors surface as {@code FeignException}; {@link ApprovalClient} turns them into a not-found
 * answer or a retryable failure. There is no circuit-breaker fallback: an executor must never
 * read "no answer" as "approved".
 */
@FeignClient(
        name = "da-policyengine-approvals",
        url = "${dlap.policyengine-service.url:http://da-policyengine:8080}",
        path = "/api/v1/policyengine/approvals")
public interface PolicyEngineApprovalFeignClient {

    /**
     * {@code GET /approvals/{id}}: the request with its decisions.
     *
     * @param id the request id
     * @return the request
     */
    @GetMapping("/{id}")
    ApprovalViewDTO get(@PathVariable("id") UUID id);

    /**
     * {@code POST /approvals}: asks for an approval. A repeated {@code idempotencyKey} from the same
     * service account returns the first request.
     *
     * @param body the request
     * @return the new or earlier request
     */
    @PostMapping
    ApprovalViewDTO create(@RequestBody ApprovalCreateRequest body);

    /**
     * {@code POST /approvals/{id}/withdraw} (APPR-03, RET-06): withdraws a {@code PENDING} request.
     * {@code ROLE_SERVICE_ACCOUNT} only; the body names the person the service acts for. 200 answers
     * the request with status {@code WITHDRAWN}; 409 answers {@code {"status": current}} when the
     * request is no longer {@code PENDING}. A policy engine without the operation answers 404 or 405.
     *
     * @param id   the request id
     * @param body the reason and the person
     * @return the request after the withdrawal
     */
    @PostMapping("/{id}/withdraw")
    ApprovalViewDTO withdraw(@PathVariable("id") UUID id, @RequestBody ApprovalWithdrawRequest body);
}
