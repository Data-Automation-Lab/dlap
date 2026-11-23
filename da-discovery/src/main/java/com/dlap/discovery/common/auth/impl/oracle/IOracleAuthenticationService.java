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

package com.dlap.discovery.common.auth.impl.oracle;

import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.google.auth.Credentials;
import java.io.IOException;
import java.util.Optional;

import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;

/**
 * Oracle-specific authentication service interface.
 */
public interface IOracleAuthenticationService extends CloudAuthenticationService {
    
    /**
     * Gets authentication details for Oracle Cloud API operations.
     *
     * @return Authentication details for Oracle Cloud
     * @throws IOException If there's an error loading credentials
     */
    Object getAuthenticationDetailsProvider() throws IOException;
    
    /**
     * Validates whether the current credentials have access to Oracle Cloud.
     *
     * @return true if the credentials are valid and have access, false otherwise
     */
    boolean validateAccess();
    
    /**
     * Gets the current authenticated Oracle Cloud identity.
     *
     * @return The currently authenticated identity, if available
     */
    @Override
    Optional<String> getCurrentIdentity();
} 