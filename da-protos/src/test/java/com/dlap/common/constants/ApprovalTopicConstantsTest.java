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

package com.dlap.common.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * APPR-01: the approval topics named by docs/specs/approvals.md keep their values, because executors in
 * other services (and in other languages) subscribe by the string.
 */
class ApprovalTopicConstantsTest {

    @Test
    void approvalTopicsKeepTheirNames() {
        assertThat(DlapConstants.KafkaTopic.APPROVAL_REQUESTED).isEqualTo("dlap.approvals.requested");
        assertThat(DlapConstants.KafkaTopic.APPROVAL_DECIDED).isEqualTo("dlap.approvals.decided");
        assertThat(DlapConstants.KafkaTopic.APPROVAL_BREAK_GLASS).isEqualTo("dlap.approvals.break-glass");
    }
}
