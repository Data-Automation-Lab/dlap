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

import java.util.Optional;

/**
 * Supplies the bearer token the PEP presents to the DLAP PDP.
 *
 * <p>The PDP endpoints are Keycloak-authenticated M2M surfaces
 * (docs/specs/enforcement-contract.md §1; ROLE_SERVICE_ACCOUNT, ADR-0034).
 * In production a client-credentials implementation mints and refreshes a
 * ROLE_SERVICE_ACCOUNT token. The reference default
 * ({@link StaticPdpTokenProvider}) returns a configured static token or empty.
 */
public interface PdpTokenProvider {

    /**
     * @return the bearer token to send, or empty to send no Authorization
     *         header (local/dev against a permissive PDP)
     */
    Optional<String> getToken();
}
