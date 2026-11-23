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

package com.dlap.autodelete.providers.gcp;

import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleGenerator;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.CanonicalAssetId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Generates Google Cloud Storage lifecycle Delete rules for a whole bucket ({@code GCS_BUCKET}).
 *
 * <p>The rules are members of {@code lifecycle.rule} of the JSON API bucket resource
 * ({@code storage.buckets.patch}), with the API's member names:
 * <pre>
 * {"action": {"type": "Delete"}, "condition": {"age": N}}
 * {"action": {"type": "Delete"}, "condition": {"isLive": false, "daysSinceNoncurrentTime": 1}}
 * </pre>
 * The first is age-based (RET-06, Q-677): GCS deletes an object {@code N} days
 * ({@code dlap.autodelete.native-rule.container-expiration-days}, at least 1) after it was created, so an
 * object written after the delete is not removed the same day. A date condition ({@code createdBefore})
 * is refused ({@code DATE_RULE_UNSUPPORTED}). The second removes noncurrent versions a day after they
 * became noncurrent; a bucket with versioning on is refused before the rules are applied (Q-656). GCS
 * lifecycle rules have no name; the applier recognises DLAP's rules by their normalized content (Q-682).
 * The rules stay in force until a restore or {@code dlap.autodelete.rule-in-force.max-age} removes them.
 * A single object ({@code GCS_OBJECT}) has no exact rule and goes to the engine.
 */
@Component
public class GcsLifecycleRuleGenerator implements NativeRuleGenerator {

    private final int ageDays;

    /** A generator with the default age of one day (tests). */
    public GcsLifecycleRuleGenerator() {
        this(RetireProperties.defaults());
    }

    /**
     * Creates the generator.
     *
     * @param properties the executor settings, for the container rule's age
     */
    @Autowired
    public GcsLifecycleRuleGenerator(RetireProperties properties) {
        this.ageDays = properties.nativeRule().containerExpirationDays();
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.GCS_LIFECYCLE;
    }

    @Override
    public boolean supports(CanonicalAssetId asset) {
        return asset != null && asset.kind() == AssetKind.GCS_BUCKET;
    }

    @Override
    public NativeRule generate(CanonicalAssetId asset, UUID ruleKey, Instant now) {
        if (!supports(asset)) {
            throw new IllegalArgumentException("no GCS lifecycle rule for " + (asset == null ? null : asset.uri()));
        }
        String bucket = asset.parts().get(0);
        // GCS evaluates lifecycle conditions about once a day; the first objects can go at the next run.
        Instant effective = RuleDocuments.nextUtcMidnight(now);

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("action", Map.of("type", "Delete"));
        live.put("condition", Map.of("age", ageDays));

        Map<String, Object> noncurrentCondition = new LinkedHashMap<>();
        noncurrentCondition.put("isLive", false);
        noncurrentCondition.put("daysSinceNoncurrentTime", 1);
        Map<String, Object> noncurrent = new LinkedHashMap<>();
        noncurrent.put("action", Map.of("type", "Delete"));
        noncurrent.put("condition", noncurrentCondition);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("api", "storage.buckets.patch");
        doc.put("bucket", bucket);
        doc.put("label", RuleDocuments.ruleName(ruleKey));
        doc.put("merge", "keep every existing rule; add these rules unless an equal rule exists");
        doc.put("lifecycle", Map.of("rule", List.of(live, noncurrent)));
        return new NativeRule(platform(), asset.uri(), RuleDocuments.ruleName(ruleKey), effective,
                RuleDocuments.write(doc));
    }
}
