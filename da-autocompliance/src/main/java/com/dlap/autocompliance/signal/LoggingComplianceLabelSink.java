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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ComplianceLabelSink}: logs the label it would apply instead of
 * writing to the catalog. Lets a deployment turn on signal ingestion and observe
 * the projected labels before committing to a write path. Replaced by supplying a
 * catalog-writing {@code ComplianceLabelSink} bean.
 */
public class LoggingComplianceLabelSink implements ComplianceLabelSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingComplianceLabelSink.class);

    @Override
    public void applyLabel(String assetUri, String labelName, String detail) {
        log.info("[compliance-label] would apply '{}' to {}{}", labelName, assetUri,
                detail == null || detail.isBlank() ? "" : " (" + detail + ")");
    }
}
