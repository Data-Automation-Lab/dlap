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
import org.springframework.stereotype.Component;

/**
 * Default {@link PdpTokenProvider}: returns the static token configured at
 * {@code dlap.pdp.token} (via {@link McpPepProperties}), or empty when unset.
 *
 * <p>This is a reference/dev implementation. In production, replace this bean
 * with a client-credentials provider that mints and refreshes a
 * ROLE_SERVICE_ACCOUNT token from Keycloak (ADR-0034) - the PEP would then
 * authenticate to the PDP the same way every other DLAP service does.
 */
@Component
public class StaticPdpTokenProvider implements PdpTokenProvider {

    private final String token;

    public StaticPdpTokenProvider(McpPepProperties properties) {
        this.token = properties.getPdp().getToken();
    }

    @Override
    public Optional<String> getToken() {
        return (token == null || token.isBlank()) ? Optional.empty() : Optional.of(token);
    }
}
