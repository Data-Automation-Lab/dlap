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

package com.dlap.autocompliance.signal;

import java.util.List;

/**
 * Default (zero-integration) {@link ComplianceSignalProvider}: reports no signals
 * and is not enabled. Registered as the fallback bean (see
 * {@code ComplianceSignalConfiguration}) so shipping DLAP with no external
 * compliance tool is fully valid - the built-in findings roll-up remains the
 * informational default.
 */
public class NoopComplianceSignalProvider implements ComplianceSignalProvider {

    @Override
    public List<ComplianceSignal> fetchSignals() {
        return List.of();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
