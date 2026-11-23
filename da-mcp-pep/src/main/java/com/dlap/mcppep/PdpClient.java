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

package com.dlap.mcppep;

/**
 * Client seam to the DLAP AuthZEN PDP (da-policyengine).
 *
 * <p>Implementations translate an {@link McpAccessRequest} into the AuthZEN
 * evaluation request (§2), POST it to {@code /access/v1/evaluation}, and parse
 * the §3 response into an {@link McpDecision}. Because the contract is the only
 * dependency, any AuthZEN-conformant PDP is swappable behind this interface.
 */
public interface PdpClient {

    /**
     * Evaluate a single access request against the PDP.
     *
     * @param req the access request
     * @return the PDP verdict
     * @throws PdpUnavailableException on any transport or parse failure - the
     *         caller ({@link McpPolicyEnforcer}) applies the fail-closed /
     *         fail-open policy rather than propagating the exception
     */
    McpDecision evaluate(McpAccessRequest req);
}
