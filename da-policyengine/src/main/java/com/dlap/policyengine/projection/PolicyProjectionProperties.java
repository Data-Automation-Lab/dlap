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

package com.dlap.policyengine.projection;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional AccessPolicy push-down projection feature
 * (ADR-0040 push-down mode). Bound from {@code dlap.policy.projection.*}.
 * Off by default - the AuthZEN PDP (pull mode) is unaffected.
 */
@ConfigurationProperties(prefix = "dlap.policy.projection")
public class PolicyProjectionProperties {

    /**
     * Master switch. When false, no projection service is created and AccessPolicy
     * create/enable events do not drive any sink. The PDP is unaffected either way.
     */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
