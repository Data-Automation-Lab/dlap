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

package com.dlap.autodelete.providers.aws;

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
 * Generates an Amazon S3 lifecycle expiration rule for a whole bucket ({@code S3_BUCKET}).
 *
 * <p>The rule is one member of {@code LifecycleConfiguration.Rules} of
 * {@code PutBucketLifecycleConfiguration}, with the API's member names:
 * <pre>
 * {"ID": "dlap-retire-KEY", "Filter": {"Prefix": ""}, "Status": "Enabled",
 *  "Expiration": {"Days": N},
 *  "NoncurrentVersionExpiration": {"NoncurrentDays": 1},
 *  "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}}
 * </pre>
 * The rule is age-based (RET-06, Q-677): S3 expires an object {@code N} days
 * ({@code dlap.autodelete.native-rule.container-expiration-days}, at least 1) after it was written, at
 * the next UTC midnight after that, so an object written after the delete is not removed the same day.
 * A date-based rule ({@code Expiration.Date}) would delete every object, new ones included, from that
 * date on, and is refused ({@code DATE_RULE_UNSUPPORTED}). The rule stays in force until a restore or
 * {@code dlap.autodelete.rule-in-force.max-age} removes it. A versioned bucket is refused before this
 * rule is applied (Q-656), because its expirations only add delete markers, which prove nothing.
 * A single object ({@code S3_OBJECT}) has no exact rule, because a prefix filter would also match its
 * siblings; it goes to the engine.
 */
@Component
public class S3LifecycleRuleGenerator implements NativeRuleGenerator {

    private final int expirationDays;

    /** A generator with the default age of one day (tests). */
    public S3LifecycleRuleGenerator() {
        this(RetireProperties.defaults());
    }

    /**
     * Creates the generator.
     *
     * @param properties the executor settings, for the container rule's age
     */
    @Autowired
    public S3LifecycleRuleGenerator(RetireProperties properties) {
        this.expirationDays = properties.nativeRule().containerExpirationDays();
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.S3_LIFECYCLE;
    }

    @Override
    public boolean supports(CanonicalAssetId asset) {
        return asset != null && asset.kind() == AssetKind.S3_BUCKET;
    }

    @Override
    public NativeRule generate(CanonicalAssetId asset, UUID ruleKey, Instant now) {
        if (!supports(asset)) {
            throw new IllegalArgumentException("no S3 lifecycle rule for " + (asset == null ? null : asset.uri()));
        }
        String bucket = asset.parts().get(0);
        String ruleId = RuleDocuments.ruleName(ruleKey);
        // S3 runs lifecycle at UTC midnight: the first objects can expire then.
        Instant effective = RuleDocuments.nextUtcMidnight(now);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ID", ruleId);
        rule.put("Filter", Map.of("Prefix", ""));
        rule.put("Status", "Enabled");
        rule.put("Expiration", Map.of("Days", expirationDays));
        rule.put("NoncurrentVersionExpiration", Map.of("NoncurrentDays", 1));
        rule.put("AbortIncompleteMultipartUpload", Map.of("DaysAfterInitiation", 1));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("api", "s3:PutBucketLifecycleConfiguration");
        doc.put("bucket", bucket);
        doc.put("merge", "keep every existing rule; replace only a rule with the same ID");
        doc.put("rules", List.of(rule));
        return new NativeRule(platform(), asset.uri(), ruleId, effective, RuleDocuments.write(doc));
    }
}
