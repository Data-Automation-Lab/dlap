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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generates a BigQuery table expiration for one table ({@code BIGQUERY_TABLE}).
 *
 * <p>The body is the {@code tables.patch} request body with the Table resource's member name:
 * {@code {"expirationTime": "MILLIS"}}, milliseconds since the epoch as a string (the API's int64
 * encoding). The expiration is {@code dlap.autodelete.native-rule.big-query-expiration-delay}
 * after planning (default one hour), so the time is never in the past when it is applied.
 * BigQuery deletes the table, with its storage, when the time passes; the deletion shows in Cloud
 * Audit Logs as a system event. A dataset ({@code BIGQUERY_DATASET}) has no exact rule: its
 * default table expiration applies only to tables created later.
 */
@Component
public class BigQueryTableExpirationGenerator implements NativeRuleGenerator {

    private final RetireProperties properties;

    /**
     * Creates the generator.
     *
     * @param properties the executor settings
     */
    public BigQueryTableExpirationGenerator(RetireProperties properties) {
        this.properties = properties;
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.BIGQUERY_TABLE_EXPIRATION;
    }

    @Override
    public boolean supports(CanonicalAssetId asset) {
        return asset != null && asset.kind() == AssetKind.BIGQUERY_TABLE;
    }

    @Override
    public NativeRule generate(CanonicalAssetId asset, UUID ruleKey, Instant now) {
        if (!supports(asset)) {
            throw new IllegalArgumentException("no BigQuery expiration for " + (asset == null ? null : asset.uri()));
        }
        Instant effective = now.plus(properties.nativeRule().bigQueryExpirationDelay()).truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("api", "bigquery.tables.patch");
        doc.put("projectId", asset.parts().get(0));
        doc.put("datasetId", asset.parts().get(1));
        doc.put("tableId", asset.parts().get(2));
        doc.put("label", RuleDocuments.ruleName(ruleKey));
        doc.put("merge", "keep an earlier expirationTime; otherwise set this one");
        doc.put("body", Map.of("expirationTime", Long.toString(effective.toEpochMilli())));
        return new NativeRule(platform(), asset.uri(), RuleDocuments.ruleName(ruleKey), effective,
                RuleDocuments.write(doc));
    }
}
