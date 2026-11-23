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

package com.dlap.catalog.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dlap.catalog.config.DiscoveryKafkaConfiguration;
import com.dlap.catalog.kafka.AssetMatcher;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.model.RetentionBasis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Records the {@code setRetention} actions of the policy engine as {@link RuleSource#POLICY}
 * rules (docs/specs/policy-dsl.md "setRetention parameters", asset-descriptor spec "Retention >
 * Rule"). It reads {@code dlap.policies.actions}, the topic the lifecycle consumers share, under
 * its own consumer group, and acts only on {@code actionType = setRetention}; every other action
 * is acknowledged and ignored.
 *
 * <p>The action names the asset by {@code assetId} (or the legacy {@code targetAssetId}) and, when
 * the policy engine adds it, by {@code assetUri}. Parameters: {@code retentionDays} (required, 0
 * to {@value RetentionProperties#MAX_DAYS}), {@code basis} (optional: {@code CREATED},
 * {@code LAST_MODIFIED} or {@code LAST_ACCESSED}, default {@code LAST_ACCESSED} as the spec says
 * "from lastAccessedAt") and {@code mode} (optional, {@code soft} or {@code hard}). The policy is
 * {@code policyDbId}, else {@code policyId} when that is a UUID. {@code firedAt} orders the actions
 * of one policy: a later action replaces the rule, an earlier one is ignored, a repeat is a no-op.
 *
 * <p>An action with {@code dryRun: true} or {@code requireApproval: true} records nothing (the
 * approval bridge republishes after approval). An action for an asset the catalog does not know
 * changes nothing and is counted. An action that cannot be understood (no days, a bad basis, no
 * policy id) is not retryable: it goes through the error handler to the dead-letter topic.
 * After the rule is stored the asset is evaluated at once, in the same transaction.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.retention.set-retention-listener-enabled", havingValue = "true",
        matchIfMissing = true)
public class SetRetentionActionListener {

    /** Consumer group of the listener. */
    public static final String GROUP = "da-catalog-retention-actions";

    /** The action type, compared without case, spaces, dots and underscores. */
    static final String ACTION_TYPE = "setretention";

    /** What the listener did with a message. */
    public enum Outcome {
        /** The rule was stored (inserted or replaced). */
        RECORDED,
        /** A rule issued later is already stored, or this is a repeat. */
        STALE,
        /** Not a setRetention action. */
        IGNORED,
        /** The action is a dry run. */
        DRY_RUN,
        /** The action waits for an approval. */
        APPROVAL_PENDING,
        /** The catalog has no such asset. */
        UNMATCHED
    }

    private static final Logger log = LoggerFactory.getLogger(SetRetentionActionListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RetentionRuleStore rules;
    private final RetentionAssetStore assets;
    private final RetentionEvaluator evaluator;
    private final ReadCoverageSource coverage;
    private final AssetMatcher matcher;
    private final RetentionMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /**
     * Creates the listener.
     *
     * @param rules              the rule store
     * @param assets             access to the asset columns
     * @param evaluator          the evaluator
     * @param coverage           the read signal
     * @param matcher            finds assets by URI
     * @param metrics            the retention metrics
     * @param transactionManager the transaction manager
     * @param clock              the time source for an action without {@code firedAt}
     */
    public SetRetentionActionListener(RetentionRuleStore rules, RetentionAssetStore assets,
                                      RetentionEvaluator evaluator, ReadCoverageSource coverage,
                                      AssetMatcher matcher, RetentionMetrics metrics,
                                      PlatformTransactionManager transactionManager, Clock clock) {
        this.rules = rules;
        this.assets = assets;
        this.evaluator = evaluator;
        this.coverage = coverage;
        this.matcher = matcher;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Handles one policy action message.
     *
     * @param payload the record value, a JSON object (or a string holding one)
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(topics = DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, groupId = GROUP,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY)
    public void onPolicyAction(String payload, Acknowledgment ack) {
        handle(payload);
        ack.acknowledge();
    }

    /**
     * Handles a message without acknowledging it.
     *
     * @param payload the record value
     * @return what was done
     * @throws IllegalArgumentException when a setRetention action cannot be understood
     */
    Outcome handle(String payload) {
        JsonNode event = parse(payload);
        if (event == null || !isSetRetention(event)) {
            metrics.action("ignored");
            return Outcome.IGNORED;
        }
        if (event.path("dryRun").asBoolean(false)) {
            metrics.action("dry_run");
            return Outcome.DRY_RUN;
        }
        if (event.path("requireApproval").asBoolean(false)) {
            metrics.action("approval_pending");
            return Outcome.APPROVAL_PENDING;
        }
        JsonNode params = event.path("actionParameters");
        int days = days(params);
        RetentionBasis basis = basis(params);
        String mode = mode(params);
        UUID policyId = policyId(event);
        Instant issuedAt = issuedAt(event);
        matcher.awaitIndex();
        Optional<UUID> asset = resolveAsset(event);
        if (asset.isEmpty()) {
            metrics.action("unmatched");
            return Outcome.UNMATCHED;
        }
        UUID assetId = asset.get();
        Outcome outcome = transaction.execute(status -> {
            boolean stored = rules.upsertPolicyRule(assetId, policyId, basis, days, mode, issuedAt);
            if (stored) {
                evaluator.reevaluate(assetId, clock.instant(), coverage.snapshot(), null);
            }
            return stored ? Outcome.RECORDED : Outcome.STALE;
        });
        metrics.action(outcome == Outcome.RECORDED ? "recorded" : "stale");
        log.info("setRetention action of policy {} on asset {}: {}", policyId, assetId, outcome);
        return outcome;
    }

    private static JsonNode parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(payload);
            if (node != null && node.isTextual()) {
                node = JSON.readTree(node.asText());
            }
            return node != null && node.isObject() ? node : null;
        } catch (java.io.IOException e) {
            // Not JSON: another producer's message on a shared topic is not ours to reject.
            return null;
        }
    }

    private static boolean isSetRetention(JsonNode event) {
        String type = event.path("actionType").asText("");
        return type.toLowerCase(Locale.ROOT).replaceAll("[._\\- ]", "").equals(ACTION_TYPE);
    }

    private static int days(JsonNode params) {
        JsonNode node = params.path("retentionDays");
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("setRetention action without actionParameters.retentionDays");
        }
        long value;
        if (node.isIntegralNumber()) {
            value = node.asLong();
        } else if (node.isTextual()) {
            try {
                value = Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("setRetention retentionDays is not a whole number");
            }
        } else {
            throw new IllegalArgumentException("setRetention retentionDays is not a whole number");
        }
        if (value < 0 || value > RetentionProperties.MAX_DAYS) {
            throw new IllegalArgumentException("setRetention retentionDays is outside 0 to "
                    + RetentionProperties.MAX_DAYS);
        }
        return (int) value;
    }

    private static RetentionBasis basis(JsonNode params) {
        JsonNode node = params.path("basis");
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return RetentionBasis.LAST_ACCESSED;
        }
        try {
            RetentionBasis basis = RetentionBasis.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
            if (!basis.requiresDays()) {
                throw new IllegalArgumentException("setRetention basis must be CREATED, LAST_MODIFIED or LAST_ACCESSED");
            }
            return basis;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("setRetention basis must be CREATED, LAST_MODIFIED or LAST_ACCESSED");
        }
    }

    private static String mode(JsonNode params) {
        JsonNode node = params.path("mode");
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        String mode = node.asText().trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("soft") && !mode.equals("hard")) {
            throw new IllegalArgumentException("setRetention mode must be soft or hard");
        }
        return mode;
    }

    private static UUID policyId(JsonNode event) {
        for (String field : new String[] {"policyDbId", "policyId"}) {
            String text = event.path(field).asText("");
            if (!text.isBlank()) {
                try {
                    return UUID.fromString(text.trim());
                } catch (IllegalArgumentException e) {
                    // try the next field
                }
            }
        }
        throw new IllegalArgumentException("setRetention action without a policy id (policyDbId or a UUID policyId)");
    }

    private Instant issuedAt(JsonNode event) {
        String text = event.path("firedAt").asText("");
        if (!text.isBlank()) {
            try {
                return Instant.parse(text.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("setRetention firedAt is not an ISO-8601 instant");
            }
        }
        return clock.instant();
    }

    private Optional<UUID> resolveAsset(JsonNode event) {
        for (String field : new String[] {"assetId", "targetAssetId"}) {
            String text = event.path(field).asText("");
            if (!text.isBlank()) {
                try {
                    UUID id = UUID.fromString(text.trim());
                    return assets.findById(id).map(row -> row.assetId());
                } catch (IllegalArgumentException e) {
                    // not a UUID: fall through to the URI
                }
            }
        }
        String uri = event.path("assetUri").asText("");
        if (!uri.isBlank()) {
            Optional<CanonicalAssetId> canonical = AssetIdNormalizer.normalize(uri);
            if (canonical.isPresent()) {
                return matcher.find(canonical.get()).map(AssetMatcher.Match::assetId);
            }
        }
        return Optional.empty();
    }
}
