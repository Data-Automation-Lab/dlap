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

package com.dlap.autoarchival.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;

/**
 * Plans a native archive rule: parses the location, resolves the tier, checks every limit and asks
 * the provider what the bucket holds, without writing anything.
 *
 * <p>The same plan serves the dry-run listing (an operator asks what a rule would do before anyone
 * approves it) and the executor (which plans again right before it applies, so a bucket that
 * filled up since the approval is caught).
 *
 * <h3>Limits that stop a plan</h3>
 * <ul>
 *   <li>tier: only archive classes the provider's rule accepts ({@link TierResolver});</li>
 *   <li>age: at least the class minimum (S3 STANDARD_IA needs 30 days) and at most
 *       {@code max-after-days};</li>
 *   <li>prefix: at most {@code max-prefix-bytes} bytes;</li>
 *   <li>rules per bucket: the provider's own maximum (S3 1000, GCS 100, Azure 100 per account
 *       policy) and DLAP's {@code max-dlap-rules-per-bucket};</li>
 *   <li>a whole-bucket rule only when {@code allow-bucket-wide} is true;</li>
 *   <li>the dry-run listing: at most {@code listing-max-keys} objects.</li>
 * </ul>
 */
@Service
public class RulePlanner {

    /** Id prefix of every rule DLAP writes. */
    public static final String RULE_ID_PREFIX = "dlap-archive-";

    private static final Set<String> COUNTED = Set.of(ArchiveRuleEntity.STATUS_EXECUTING,
            ArchiveRuleEntity.STATUS_APPLIED, ArchiveRuleEntity.STATUS_EXECUTED, ArchiveRuleEntity.STATUS_RENDERED);

    /** Rows that hold, or are about to hold, a provider rule (Q-673 duplicate-scope check). */
    private static final Set<String> LIVE = Set.of(ArchiveRuleEntity.STATUS_PLANNED, ArchiveRuleEntity.STATUS_EXECUTING,
            ArchiveRuleEntity.STATUS_APPLIED, ArchiveRuleEntity.STATUS_EXECUTED);

    private final Map<String, NativeRuleProvider> providers = new java.util.HashMap<>();
    private final ArchiveRuleProperties properties;
    private final ArchiveRuleRepository rules;

    /**
     * Creates the planner.
     *
     * @param providerBeans every provider implementation
     * @param properties    the limits
     * @param rules         the rule rows, to count DLAP's own rules on a bucket
     */
    public RulePlanner(List<NativeRuleProvider> providerBeans, ArchiveRuleProperties properties,
            ArchiveRuleRepository rules) {
        for (NativeRuleProvider p : providerBeans) {
            providers.put(p.provider(), p);
        }
        this.properties = properties;
        this.rules = rules;
        properties.validate();
    }

    /**
     * Returns the provider for a cloud.
     *
     * @param provider {@code AWS}, {@code GCP} or {@code AZURE}
     * @return the provider
     * @throws RuleException {@code UNSUPPORTED_PROVIDER} when none is registered
     */
    public NativeRuleProvider providerFor(String provider) {
        NativeRuleProvider p = providers.get(provider == null ? "" : provider.toUpperCase(Locale.ROOT));
        if (p == null) {
            throw new RuleException(RuleException.Reason.UNSUPPORTED_PROVIDER,
                    "no native archive rule for provider '" + provider + "'");
        }
        return p;
    }

    /**
     * Builds the plan.
     *
     * @param provider      the asset's provider
     * @param assetUri      the asset's canonical storage URI
     * @param parameters    the action parameters ({@code prefix}, {@code tier} or {@code targetStorageClass},
     *                      {@code afterDays}); may be {@code null}
     * @param defaultTier   the tier to use when the parameters name none; may be {@code null}
     * @param approvalId    the approval the rule runs under; {@code null} for a dry run
     * @param listingKeys   how many objects to list; {@code null} for the default, 0 for none
     * @return the plan
     * @throws RuleException when a limit or a check stops the rule
     */
    public RulePlan plan(String provider, String assetUri, Map<String, Object> parameters, String defaultTier,
            String approvalId, Integer listingKeys) {
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        RuleScope scope = RuleScope.parse(provider, assetUri, text(params.get("prefix")),
                properties.getMaxPrefixBytes());
        NativeRuleProvider impl = providerFor(scope.provider());
        TierResolver.NativeTier tier = TierResolver.resolve(scope.provider(),
                firstText(params, "tier", "targetStorageClass", "targetTier", defaultTier));
        int days = afterDays(params.get("afterDays"), tier);
        if (scope.wholeBucket() && !properties.isAllowBucketWide()) {
            throw new RuleException(RuleException.Reason.BUCKET_WIDE_NOT_ALLOWED,
                    "a rule on the whole bucket is switched off; name a prefix");
        }
        NativeRuleProvider.RuleSpec spec = new NativeRuleProvider.RuleSpec(
                ruleId(approvalId, scope, tier.name(), days), scope, tier.name(), days);
        // Also for a dry run and for the check made before an approval is asked, so nobody approves a rule that
        // planning would refuse afterwards.
        refuseDuplicateScope(spec);
        List<String> notes = new ArrayList<>();
        NativeRuleProvider.Inventory inventory;
        String document;
        String kind;
        if (scope.singleObject()) {
            inventory = new NativeRuleProvider.Inventory(-1, 0, false);
            document = directDocument(spec);
            kind = "OBJECT";
            notes.add("One object: archived with a direct storage class change, not a lifecycle rule, "
                    + "because a prefix rule would also match other keys that start with this key.");
        } else {
            inventory = impl.inventory(spec);
            checkCapacity(scope, inventory, impl.provider());
            document = impl.render(spec);
            kind = scope.wholeBucket() ? "BUCKET" : "PREFIX";
            if (scope.wholeBucket()) {
                notes.add("The rule covers every object in the bucket, including objects written later.");
            } else {
                notes.add("The rule covers every object under the prefix, including objects written later.");
            }
            if (!impl.canApply()) {
                notes.add("DLAP cannot write this provider's rule through an API; the document is the rule to apply.");
            }
            if ("AWS".equals(scope.provider())) {
                notes.add("S3 lifecycle leaves objects under 128 KiB where they are unless a size filter says otherwise; the default "
                        + "minimum object size applies.");
            }
        }
        NativeRuleProvider.Listing listing = listingKeys != null && listingKeys == 0
                ? new NativeRuleProvider.Listing(List.of(), false, 0, 0L, false, "not requested")
                : listing(impl, spec, listingKeys);
        if (listing.truncated()) {
            notes.add("The listing is a sample of " + listing.limit() + " objects; more objects match.");
        }
        return new RulePlan(spec, inventory, listing, document, impl.canApply() || scope.singleObject(), kind,
                List.copyOf(notes));
    }

    /**
     * Reads the scope of a location and prefix with the configured prefix limit, without asking the provider.
     * The executor uses it to read the scope an approval itself names.
     *
     * @param provider   the provider
     * @param assetUri   the canonical storage URI
     * @param parameters the parameters; only {@code prefix} is read; may be {@code null}
     * @return the scope
     * @throws RuleException when the location cannot be used
     */
    public RuleScope scopeOf(String provider, String assetUri, Map<String, Object> parameters) {
        return RuleScope.parse(provider, assetUri, text(parameters == null ? null : parameters.get("prefix")),
                properties.getMaxPrefixBytes());
    }

    /**
     * Checks the limits again right before the rule is applied, so a bucket that filled up since the
     * approval is caught. Reads the provider; writes nothing.
     *
     * @param spec the rule
     * @throws RuleException {@code RULE_LIMIT} or {@code PROVIDER_ERROR}
     */
    public void recheck(NativeRuleProvider.RuleSpec spec) {
        if (spec.scope().singleObject()) {
            return;
        }
        NativeRuleProvider impl = providerFor(spec.scope().provider());
        checkCapacity(spec.scope(), impl.inventory(spec), impl.provider());
    }

    private NativeRuleProvider.Listing listing(NativeRuleProvider impl, NativeRuleProvider.RuleSpec spec,
            Integer requested) {
        int limit = requested == null ? properties.getListingDefaultKeys()
                : Math.max(1, Math.min(requested, properties.getListingMaxKeys()));
        if (spec.scope().singleObject()) {
            NativeRuleProvider.Listing l = impl.list(spec.scope(), 1);
            List<NativeRuleProvider.ListingEntry> exact = l.entries().stream()
                    .filter(e -> e.key().equals(spec.scope().prefix())).toList();
            return new NativeRuleProvider.Listing(exact, false, 1,
                    exact.stream().mapToLong(NativeRuleProvider.ListingEntry::sizeBytes).sum(), l.available(),
                    exact.isEmpty() && l.available() ? "the object was not found" : l.note());
        }
        return impl.list(spec.scope(), limit);
    }

    /**
     * Q-673: a GCS lifecycle rule has no id, so two DLAP rows with the same bucket, prefix, class and age would
     * share one provider rule, and restoring one would remove the rule the other relies on. A second row for an
     * identical live scope is refused, naming the row that already holds it. S3 and Azure rules carry the row's
     * own id, so they do not collide.
     */
    private void refuseDuplicateScope(NativeRuleProvider.RuleSpec spec) {
        RuleScope scope = spec.scope();
        if (!"GCP".equals(scope.provider()) || scope.singleObject()) {
            return;
        }
        List<ArchiveRuleEntity> same = rules.findLiveScope(scope.provider(), scope.bucket(), scope.prefix(),
                spec.nativeClass(), spec.afterDays(), LIVE, spec.ruleId());
        if (!same.isEmpty()) {
            throw duplicate(same.get(0), scope, spec.nativeClass(), spec.afterDays());
        }
    }

    /**
     * Builds the duplicate-scope exception for a row that holds the scope.
     *
     * @param existing    the live row
     * @param scope       the scope
     * @param nativeClass the provider storage class
     * @param afterDays   the age in days
     * @return the exception, naming the row
     */
    public static DuplicateScopeException duplicate(ArchiveRuleEntity existing, RuleScope scope, String nativeClass,
            int afterDays) {
        return new DuplicateScopeException("archive rule " + existing.getRuleId() + " (" + existing.getStatus()
                + ", approval " + existing.getApprovalId() + ") already holds " + scope.uri() + " to " + nativeClass
                + " after " + afterDays + " days; a GCS rule has no id, so a second row would share it", existing);
    }

    /**
     * Returns the live statuses that hold or are about to hold a provider rule, for the duplicate-scope check.
     *
     * @return the statuses
     */
    public static Set<String> liveStatuses() {
        return LIVE;
    }

    private void checkCapacity(RuleScope scope, NativeRuleProvider.Inventory inventory, String provider) {
        if (inventory.specPresent()) {
            return;
        }
        if (inventory.totalRules() >= 0 && inventory.totalRules() + 1 > inventory.providerMax()) {
            throw new RuleException(RuleException.Reason.RULE_LIMIT, provider + " allows " + inventory.providerMax()
                    + " lifecycle rules per bucket and '" + scope.bucket() + "' holds " + inventory.totalRules());
        }
        long dlap = rules.countByProviderAndScopeBucketAndStatusIn(scope.provider(), scope.bucket(), COUNTED);
        if (dlap >= properties.getMaxDlapRulesPerBucket()) {
            throw new RuleException(RuleException.Reason.RULE_LIMIT, "DLAP already keeps " + dlap
                    + " archive rules on '" + scope.bucket() + "' (max-dlap-rules-per-bucket="
                    + properties.getMaxDlapRulesPerBucket() + ")");
        }
    }

    private int afterDays(Object raw, TierResolver.NativeTier tier) {
        if (raw == null || raw.toString().isBlank()) {
            return tier.minDays();
        }
        int days;
        try {
            days = Integer.parseInt(raw.toString().strip());
        } catch (NumberFormatException e) {
            throw new RuleException(RuleException.Reason.INVALID_AGE, "afterDays is not a whole number");
        }
        if (days < tier.minDays()) {
            throw new RuleException(RuleException.Reason.INVALID_AGE, "afterDays " + days + " is below the "
                    + tier.minDays() + " days the provider needs for " + tier.name());
        }
        if (days > properties.getMaxAfterDays()) {
            throw new RuleException(RuleException.Reason.INVALID_AGE,
                    "afterDays " + days + " is above max-after-days " + properties.getMaxAfterDays());
        }
        return days;
    }

    /**
     * Derives the rule id: the same approval, scope, class and age always give the same id, so a
     * repeated apply finds its own rule instead of adding a second.
     *
     * @param approvalId the approval id, or {@code null} for a dry run
     * @param scope      the scope
     * @param nativeClass the provider storage class
     * @param days       the age
     * @return {@code dlap-archive-} and 12 hex characters
     */
    public static String ruleId(String approvalId, RuleScope scope, String nativeClass, int days) {
        String material = (approvalId == null ? "dry-run" : approvalId) + "|" + scope.provider() + "|"
                + scope.account() + "|" + scope.bucket() + "|" + scope.prefix() + "|" + nativeClass + "|" + days;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return RULE_ID_PREFIX + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String directDocument(NativeRuleProvider.RuleSpec spec) {
        return "{\"action\":\"set-storage-class\",\"uri\":\"" + spec.scope().uri().replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"storageClass\":\"" + spec.nativeClass() + "\"}";
    }

    private static String text(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    private static String firstText(Map<String, Object> params, String a, String b, String c, String fallback) {
        for (String k : new String[] {a, b, c}) {
            String v = text(params.get(k));
            if (v != null) {
                return v;
            }
        }
        return fallback;
    }
}
