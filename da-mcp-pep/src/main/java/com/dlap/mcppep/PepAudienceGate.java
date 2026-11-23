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

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The gate of {@code POST /mcp/v1/authorize} (Q-459, D29): any authenticated caller whose token
 * carries the PEP audience gets a decision, whatever its roles.
 *
 * <p>Until Q-459 the endpoint admitted only {@code ROLE_SERVICE_ACCOUNT} and {@code ROLE_ADMIN}, so a
 * user or agent token without one of them was answered 403 before the subject binding ran. The
 * binding makes the wider gate safe ({@link SubjectBinder}, ADR-0084): a caller that is not a service
 * account is asked about as itself, with the roles and tier of its own token, and a body that names
 * another principal is refused. What a caller may do with the verdict is the PDP's decision, not this
 * gate's.
 *
 * <p>The audience is the one the decoder requires ({@code dlap.security.jwt.expected-audience},
 * {@link JwtTokenValidators#create}), so a token for another audience never reaches the controller:
 * it is answered 401. This gate repeats the check next to the handler, so the endpoint stays closed to
 * a token without the audience even if the decoder is replaced or misconfigured. It admits only a
 * verified JWT; any other authentication, and an unset audience, are refused.
 */
@Component("pepAudienceGate")
public class PepAudienceGate {

    private final String expectedAudience;

    /**
     * Creates the gate.
     *
     * @param expectedAudience the audience a caller's token must list, the same setting the decoder checks
     */
    public PepAudienceGate(@Value("${dlap.security.jwt.expected-audience:}") String expectedAudience) {
        this.expectedAudience = expectedAudience == null ? "" : expectedAudience.trim();
    }

    /**
     * Whether the caller presented a verified token that lists the PEP audience.
     *
     * @param authentication the caller, as Spring Security resolved it (may be null)
     * @return true for a verified JWT whose {@code aud} contains the configured audience
     */
    public boolean carriesPepAudience(Authentication authentication) {
        if (expectedAudience.isEmpty() || !(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            return false;
        }
        Jwt jwt = jwtAuthentication.getToken();
        List<String> audience = jwt.getAudience();
        return audience != null && audience.contains(expectedAudience);
    }
}
