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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits and switches of the native archive rule path ({@code dlap.autoarchival.rules.*}).
 *
 * <p>The provider limits (rules per bucket, key length) are facts of the clouds and live in the
 * provider classes; the values here only tighten them. A value outside its range stops the
 * start-up through {@link #validate()} so a mistyped limit never loosens a guard.
 */
@ConfigurationProperties(prefix = "dlap.autoarchival.rules")
public class ArchiveRuleProperties {

    /** Longest prefix in bytes (UTF-8); S3 and GCS allow keys of 1024 bytes. */
    private int maxPrefixBytes = 1024;
    /** Most rules DLAP keeps on one bucket, below the provider's own maximum. */
    private int maxDlapRulesPerBucket = 20;
    /** Largest age in days a rule may use. */
    private int maxAfterDays = 3650;
    /** Keys shown when a dry run does not ask for a number. */
    private int listingDefaultKeys = 50;
    /** Most keys a dry run lists, whatever the caller asks for. */
    private int listingMaxKeys = 1000;
    /** Whether a rule may cover a whole bucket (no prefix). */
    private boolean allowBucketWide = true;
    /** Whether a single object is archived with a direct storage class change (exact object). */
    private boolean directObjectEnabled = true;
    /** Tier used when neither the action nor the configuration names one. */
    private String defaultTier = TierResolver.DEFAULT_TIER;
    /** Whether replayed events (fixtures, demo seeding) may prove a transition. */
    private boolean acceptReplayEvidence = false;

    /**
     * Checks the ranges.
     *
     * @throws IllegalStateException when a limit is out of range
     */
    public void validate() {
        require(maxPrefixBytes >= 1 && maxPrefixBytes <= 1024, "max-prefix-bytes must be 1 to 1024");
        require(maxDlapRulesPerBucket >= 1 && maxDlapRulesPerBucket <= 90,
                "max-dlap-rules-per-bucket must be 1 to 90");
        require(maxAfterDays >= 0 && maxAfterDays <= 36500, "max-after-days must be 0 to 36500");
        require(listingMaxKeys >= 1 && listingMaxKeys <= 1000, "listing-max-keys must be 1 to 1000");
        require(listingDefaultKeys >= 1 && listingDefaultKeys <= listingMaxKeys,
                "listing-default-keys must be 1 to listing-max-keys");
        TierResolver.resolve("AWS", defaultTier);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("dlap.autoarchival.rules." + message);
        }
    }

    /** @return the longest prefix in bytes */
    public int getMaxPrefixBytes() {
        return maxPrefixBytes;
    }

    /** @param v the longest prefix in bytes */
    public void setMaxPrefixBytes(int v) {
        this.maxPrefixBytes = v;
    }

    /** @return most DLAP rules per bucket */
    public int getMaxDlapRulesPerBucket() {
        return maxDlapRulesPerBucket;
    }

    /** @param v most DLAP rules per bucket */
    public void setMaxDlapRulesPerBucket(int v) {
        this.maxDlapRulesPerBucket = v;
    }

    /** @return the largest age in days */
    public int getMaxAfterDays() {
        return maxAfterDays;
    }

    /** @param v the largest age in days */
    public void setMaxAfterDays(int v) {
        this.maxAfterDays = v;
    }

    /** @return the default number of listed keys */
    public int getListingDefaultKeys() {
        return listingDefaultKeys;
    }

    /** @param v the default number of listed keys */
    public void setListingDefaultKeys(int v) {
        this.listingDefaultKeys = v;
    }

    /** @return the largest number of listed keys */
    public int getListingMaxKeys() {
        return listingMaxKeys;
    }

    /** @param v the largest number of listed keys */
    public void setListingMaxKeys(int v) {
        this.listingMaxKeys = v;
    }

    /** @return whether a whole-bucket rule is allowed */
    public boolean isAllowBucketWide() {
        return allowBucketWide;
    }

    /** @param v whether a whole-bucket rule is allowed */
    public void setAllowBucketWide(boolean v) {
        this.allowBucketWide = v;
    }

    /** @return whether single objects are archived directly */
    public boolean isDirectObjectEnabled() {
        return directObjectEnabled;
    }

    /** @param v whether single objects are archived directly */
    public void setDirectObjectEnabled(boolean v) {
        this.directObjectEnabled = v;
    }

    /** @return the default tier */
    public String getDefaultTier() {
        return defaultTier;
    }

    /** @param v the default tier */
    public void setDefaultTier(String v) {
        this.defaultTier = v;
    }

    /** @return whether replayed events may prove a transition */
    public boolean isAcceptReplayEvidence() {
        return acceptReplayEvidence;
    }

    /** @param v whether replayed events may prove a transition */
    public void setAcceptReplayEvidence(boolean v) {
        this.acceptReplayEvidence = v;
    }
}
