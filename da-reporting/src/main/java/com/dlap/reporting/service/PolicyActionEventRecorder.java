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

package com.dlap.reporting.service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.reporting.model.PolicyActionEventRecord;
import com.dlap.reporting.repository.PolicyActionEventRecordRepository;

import lombok.RequiredArgsConstructor;

/**
 * Records each policy fire on {@code dlap.policies.actions} once (Q-855, log-event.md section 5.5).
 *
 * <p>Delivery is at least once and the policy engine republishes a fire with the same {@code fireId}
 * when its source event is redelivered, so {@code fireId} is the idempotency key: the column
 * {@code fire_id} carries a unique index, and a repeated fire writes no second row and runs no second
 * dispatch. A message without a {@code fireId} (a producer that predates it, or one that has none) cannot be
 * recognised and is recorded every time, as before.
 */
@Service
@RequiredArgsConstructor
public class PolicyActionEventRecorder {

    /** Longest fire id the column holds; a longer id is not used as a key. */
    static final int FIRE_ID_MAX_LENGTH = 255;

    private final PolicyActionEventRecordRepository repository;

    /**
     * The key of a fire.
     *
     * @param event the decoded action
     * @return its {@code fireId} as text, or null when it has none or it does not fit the column
     */
    public static String fireIdOf(Map<String, Object> event) {
        Object raw = event.get("fireId");
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() || text.length() > FIRE_ID_MAX_LENGTH ? null : text;
    }

    /**
     * Records the fire and runs {@code dispatch} once, in one transaction.
     *
     * <p>The row is inserted first, so a concurrent copy of the same fire waits on the unique index until
     * this transaction ends. When {@code dispatch} throws, the row is rolled back and the redelivery
     * dispatches again.
     *
     * @param event    the decoded action
     * @param dispatch sends the notification; returns true when one was sent
     * @return true when this call recorded the fire, false when the fire was already recorded (nothing was
     *         dispatched or stored)
     */
    @Transactional
    public boolean recordOnce(Map<String, Object> event, BooleanSupplier dispatch) {
        String fireId = fireIdOf(event);
        if (fireId != null && repository.existsByFireId(fireId)) {
            return false;
        }
        PolicyActionEventRecord row = new PolicyActionEventRecord();
        row.setFireId(fireId);
        row.setAssetId(stringVal(event.get("assetId")));
        row.setPolicyId(stringVal(event.get("policyId")));
        row.setActionType(stringVal(event.get("actionType")));
        row.setPayload(new HashMap<>(event));
        row.setNotificationDispatched(false);
        repository.saveAndFlush(row);
        row.setNotificationDispatched(dispatch.getAsBoolean());
        return true;
    }

    /**
     * Whether a fire is already recorded.
     *
     * @param fireId the fire id
     * @return true when a row carries it
     */
    @Transactional(readOnly = true)
    public boolean isRecorded(String fireId) {
        return repository.existsByFireId(fireId);
    }

    private static String stringVal(Object o) {
        return o == null ? null : o.toString();
    }
}
