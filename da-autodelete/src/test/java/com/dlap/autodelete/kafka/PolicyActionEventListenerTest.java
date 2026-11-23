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

package com.dlap.autodelete.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.retire.RetireRemoteException;
import com.dlap.autodelete.service.IDeletionService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * RET-02: only deletes reach the executor; a policy dry run executes nothing; an action that says it
 * needs no approval still goes through the executor's approval check; an action the executor cannot
 * decide yet is redelivered.
 */
class PolicyActionEventListenerTest {

    private final IDeletionService service = mock(IDeletionService.class);
    private final PolicyActionEventListener listener = new PolicyActionEventListener(service);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    @Test
    void otherActionsAndPolicyDryRunsAreAcknowledgedWithoutWork() {
        listener.onPolicyAction(Map.of("actionType", "archive"), "t", 1, ack);
        listener.onPolicyAction(Map.of("actionType", "delete", "dryRun", true), "t", 2, ack);
        listener.onPolicyAction(null, "t", 3, ack);

        verify(service, never()).queueSoftDelete(any());
        verify(ack, org.mockito.Mockito.times(3)).acknowledge();
    }

    /** Q-632: a policy dry run executes nothing and still writes an audit record before it is acknowledged. */
    @Test
    void aPolicyDryRunIsAuditedBeforeItIsAcknowledged() {
        Map<String, Object> dry = Map.of("actionType", "delete", "dryRun", true, "assetId", "a1");

        listener.onPolicyAction(dry, "t", 1, ack);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service, ack);
        order.verify(service).recordPolicyDryRun(dry);
        order.verify(ack).acknowledge();
        verify(service, never()).queueSoftDelete(any());
    }

    /** Q-632: when the dry-run record cannot be written the event is not acknowledged and is retried. */
    @Test
    void aPolicyDryRunWhoseRecordCannotBeWrittenIsNotAcknowledged() {
        Map<String, Object> dry = Map.of("actionType", "delete", "dryRun", true);
        org.mockito.Mockito.doThrow(new com.dlap.common.audit.AuditException("dlap_common is down", null))
                .when(service).recordPolicyDryRun(dry);

        assertThatThrownBy(() -> listener.onPolicyAction(dry, "t", 1, ack))
                .isInstanceOf(com.dlap.common.audit.AuditException.class);
        verify(ack, never()).acknowledge();
    }

    @Test
    void everyDeleteGoesToTheExecutorWhateverItsRequireApprovalSays() {
        Map<String, Object> unapproved = Map.of("actionType", "delete", "requireApproval", true);
        Map<String, Object> noApprovalNeeded = Map.of("actionType", "AUTO_DELETE", "requireApproval", false);

        listener.onPolicyAction(unapproved, "t", 1, ack);
        listener.onPolicyAction(noApprovalNeeded, "t", 2, ack);

        verify(service).queueSoftDelete(unapproved);
        verify(service).queueSoftDelete(noApprovalNeeded);
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }

    @Test
    void anUndecidableDeleteIsNotAcknowledged() {
        Map<String, Object> event = Map.of("actionType", "delete");
        when(service.queueSoftDelete(event)).thenThrow(new RetireRemoteException("policy engine down", true, null));

        assertThatThrownBy(() -> listener.onPolicyAction(event, "t", 1, ack)).isInstanceOf(RetireRemoteException.class);
        verify(ack, never()).acknowledge();
    }
}
