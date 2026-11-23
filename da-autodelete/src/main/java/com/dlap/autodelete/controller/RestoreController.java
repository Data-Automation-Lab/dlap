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

package com.dlap.autodelete.controller;

import com.dlap.autodelete.service.IDeletionService;
import com.dlap.autodelete.service.RestoreOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restores a soft-deleted asset while nothing has been touched in the cloud, or removes the rule of a
 * rule in force. The restorer is the JWT subject of the caller, never a value from the body (RET-02: no
 * requester override). A pending hard-delete approval of the record is withdrawn by the executor's own
 * service account on behalf of the caller (Q-621, Q-676), never with the caller's token.
 */
@RestController
@RequestMapping("/api/v1/autodelete/restores")
@RequiredArgsConstructor
@Tag(name = "Restore", description = "Restore soft-deleted assets within the deletion window")
@Slf4j
public class RestoreController {

    private final IDeletionService deletionService;

    /**
     * Restores one record.
     *
     * @param softDeleteRecordId the record
     * @param body               optional notes
     * @param jwt                the caller's token
     * @return 200 when restored, 409 otherwise (the reason says why)
     */
    @PostMapping("/{softDeleteRecordId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(summary = "Restore a soft-deleted asset before anything is deleted in the cloud",
            description = "A record whose bucket, prefix or container rule was applied (RULE_IN_FORCE, or "
                    + "AWAITING_PROOF) is restored by removing the rule; objects it already expired are gone. When "
                    + "DLAP's rule is not found the record ends RESTORE_FAILED (409, ruleRemoved false). A pending "
                    + "HARD_DELETE approval of the record is withdrawn by the service account on behalf of the "
                    + "caller (approvalWithdrawal in the answer).")
    public ResponseEntity<Map<String, Object>> restore(
            @PathVariable("softDeleteRecordId") UUID softDeleteRecordId,
            @RequestBody(required = false) RestoreRequest body,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {

        String notes = body != null ? body.notes() : null;
        String subject = jwt != null ? jwt.getSubject() : null;
        if (subject == null || subject.isBlank()) {
            return ResponseEntity.status(403).body(Map.of(
                    "softDeleteRecordId", softDeleteRecordId,
                    "status", "NOT_RESTORABLE",
                    "reason", "no-subject"));
        }
        RestoreOutcome outcome = deletionService.restore(softDeleteRecordId, subject, notes);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("softDeleteRecordId", softDeleteRecordId);
        if (outcome.restored()) {
            answer.put("status", "RESTORED");
            answer.put("ruleRemoved", outcome.ruleRemoved());
            answer.put("approvalWithdrawal", outcome.approvalWithdrawal());
            if (outcome.message() != null) {
                answer.put("message", outcome.message());
            }
            return ResponseEntity.ok(answer);
        }
        answer.put("status", outcome.recordStatus() != null ? outcome.recordStatus() : "NOT_RESTORABLE");
        answer.put("ruleRemoved", outcome.ruleRemoved());
        answer.put("reason", outcome.reason() + (outcome.message() == null ? "" : ": " + outcome.message()));
        return ResponseEntity.status(409).body(answer);
    }

    /**
     * Optional body. {@code userId} is accepted for compatibility and ignored: the restorer is the
     * caller's JWT subject.
     *
     * @param userId ignored
     * @param notes  notes
     */
    public record RestoreRequest(UUID userId, String notes) {}
}
