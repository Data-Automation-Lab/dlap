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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code approval} block of a released action on {@code dlap.policies.actions}
 * (docs/specs/approvals.md section 7).
 *
 * @param approvalId        the request id
 * @param status            {@code APPROVED} or {@code APPROVED_BREAK_GLASS}
 * @param actionClass       the class
 * @param requiredApprovals approvals the request needed
 * @param approvers         JWT subjects, in decision order
 * @param breakGlass        released by an administrator without the quorum
 */
public record ApprovalEvidence(UUID approvalId, String status, String actionClass, int requiredApprovals,
        List<String> approvers, boolean breakGlass) {

    /** Copies the approvers. */
    public ApprovalEvidence {
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
    }

    /**
     * Reads the block from a decoded action. A block that is not a map, or has no parseable
     * {@code approvalId}, is absent.
     *
     * @param approval the {@code approval} value of the action, or null
     * @return the evidence, or null
     */
    public static ApprovalEvidence from(Object approval) {
        if (!(approval instanceof Map<?, ?> map)) {
            return null;
        }
        UUID id = uuid(map.get("approvalId"));
        if (id == null) {
            return null;
        }
        List<String> approvers = new ArrayList<>();
        if (map.get("approvers") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    approvers.add(String.valueOf(o));
                }
            }
        }
        int required = map.get("requiredApprovals") instanceof Number n ? n.intValue() : 0;
        return new ApprovalEvidence(id, str(map.get("status")), str(map.get("actionClass")), required, approvers,
                Boolean.TRUE.equals(map.get("breakGlass")));
    }

    static UUID uuid(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(o).trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : Objects.toString(o);
    }
}
