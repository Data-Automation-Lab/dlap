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

package com.dlap.logingest.parse.archsample;

import com.google.cloud.logging.LogEntry;
import software.amazon.awssdk.services.cloudtrail.model.Event;

/**
 * A deliberate violation of the {@code parse/**} boundary, kept in test sources only. The
 * architecture test imports this class on its own to prove its rules fail on SDK imports; the
 * real check imports main classes only, so this sample never counts against production code.
 */
final class ProviderSdkImportSample {

    private ProviderSdkImportSample() {
    }

    /**
     * Touches an AWS SDK type.
     *
     * @param event a CloudTrail SDK event
     * @return its name
     */
    static String awsName(Event event) {
        return event.eventName();
    }

    /**
     * Touches a GCP SDK type.
     *
     * @param entry a Cloud Logging entry
     * @return its log name
     */
    static String gcpLogName(LogEntry entry) {
        return entry.getLogName();
    }
}
