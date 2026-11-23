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

package com.dlap.autodelete.dto;

/**
 * Body of {@code POST /approvals/{id}/withdraw} (APPR-03, RET-06, Q-676).
 *
 * @param reason     why, at least 10 characters
 * @param onBehalfOf the JWT subject of the person the service account acts for (the restorer)
 */
public record ApprovalWithdrawRequest(String reason, String onBehalfOf) {
}
