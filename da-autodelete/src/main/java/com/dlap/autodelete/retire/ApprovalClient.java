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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.client.PolicyEngineApprovalFeignClient;
import com.dlap.autodelete.dto.ApprovalCreateRequest;
import com.dlap.autodelete.dto.ApprovalViewDTO;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads and asks for approvals through the policy engine (docs/specs/approvals.md sections 8 and 9).
 *
 * <p>A 404 is "no such request"; any other failure is a {@link RetireRemoteException}: retryable
 * for a network error, a 5xx, a 401 (the service token may be renewing) or a 429, permanent for
 * the other 4xx answers.
 */
@Component
public class ApprovalClient {

    private final PolicyEngineApprovalFeignClient feign;

    /**
     * Creates the client.
     *
     * @param feign the Feign client
     */
    public ApprovalClient(PolicyEngineApprovalFeignClient feign) {
        this.feign = feign;
    }

    /**
     * Re-reads a request (rule 3 of section 9).
     *
     * @param approvalId the request id
     * @return the request, or empty when the policy engine does not know it
     * @throws RetireRemoteException when the policy engine gave no usable answer
     */
    public Optional<ApprovalViewDTO> read(UUID approvalId) {
        try {
            return Optional.ofNullable(feign.get(approvalId));
        } catch (FeignException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw failure("read approval " + approvalId, e);
        } catch (RuntimeException e) {
            throw new RetireRemoteException("read approval " + approvalId + " failed: " + e.getClass().getSimpleName(),
                    true, e);
        }
    }

    /**
     * Asks for an approval (section 6.2). A repeated idempotency key returns the first request.
     *
     * @param request the request
     * @return the request as stored
     * @throws RetireRemoteException when the policy engine did not store it
     */
    public ApprovalViewDTO create(ApprovalCreateRequest request) {
        try {
            ApprovalViewDTO view = feign.create(request);
            if (view == null || view.id() == null) {
                throw new RetireRemoteException("the policy engine answered an approval request without an id", true,
                        null);
            }
            return view;
        } catch (FeignException e) {
            throw failure("ask for approval " + request.idempotencyKey(), e);
        } catch (RetireRemoteException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RetireRemoteException("ask for approval " + request.idempotencyKey() + " failed: "
                    + e.getClass().getSimpleName(), true, e);
        }
    }

    static RetireRemoteException failure(String what, FeignException e) {
        int status = e.status();
        boolean retryable = status < 400 || status >= 500 || status == 401 || status == 408 || status == 429;
        return new RetireRemoteException(what + " failed with HTTP " + status, retryable, e);
    }
}
