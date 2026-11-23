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

package com.dlap.autodelete.nativerule;

import java.time.Instant;
import java.util.Objects;

/**
 * A generated native lifecycle rule. {@code document} is a JSON object naming the provider API
 * call and holding the rule in that API's own shape (member names exactly as the provider's API
 * spells them), so the rule a reviewer reads in the would-delete list is the rule that is applied.
 *
 * @param platform    the primitive
 * @param targetUri   the canonical URI of the asset the rule deletes
 * @param ruleId      the rule's name on the platform (S3 rule ID, Azure rule name), or a label
 *                    where the platform has none (GCS, BigQuery)
 * @param effectiveAt when the rule makes the data eligible for deletion
 * @param document    the JSON document
 */
public record NativeRule(NativeRulePlatform platform, String targetUri, String ruleId, Instant effectiveAt,
        String document) {

    /** Requires every field. */
    public NativeRule {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(targetUri, "targetUri");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(document, "document");
    }
}
