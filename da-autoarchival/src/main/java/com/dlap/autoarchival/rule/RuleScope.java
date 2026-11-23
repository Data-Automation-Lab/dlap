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
import java.util.Locale;

import com.dlap.autoarchival.service.LocationSafety;

/**
 * What a native archive rule covers: a bucket (or container), optionally narrowed by a key prefix,
 * or one object.
 *
 * <p>A bucket asset ({@code s3://b}, {@code gs://b}) becomes a rule on the bucket, whole or under
 * {@code prefix}. A key that ends in {@code /} is a folder and becomes a prefix rule. A key that
 * does not is one object: a prefix rule on it would also catch {@code key2}, so the executor
 * archives it with a direct storage class change instead ({@code singleObject}).
 *
 * @param provider     {@code AWS}, {@code GCP} or {@code AZURE}
 * @param account      the storage account (Azure only), empty otherwise
 * @param bucket       the bucket, or the Azure container
 * @param prefix       the key prefix, empty for the whole bucket; the key itself for one object
 * @param singleObject whether the scope is exactly one object
 */
public record RuleScope(String provider, String account, String bucket, String prefix, boolean singleObject) {

    /**
     * Whether the rule covers the whole bucket.
     *
     * @return true for no prefix and not one object
     */
    public boolean wholeBucket() {
        return prefix.isEmpty() && !singleObject;
    }

    /**
     * The scope as a storage URI, for logs and the audit log.
     *
     * @return {@code s3://b/p}, {@code gs://b/p} or {@code az://account/container/p}
     */
    public String uri() {
        String base = switch (provider) {
            case "AWS" -> "s3://" + bucket;
            case "GCP" -> "gs://" + bucket;
            default -> "az://" + account + "/" + bucket;
        };
        return prefix.isEmpty() ? base : base + "/" + prefix;
    }

    /**
     * Parses a resolved location.
     *
     * @param provider       the provider of the asset
     * @param location       the canonical storage URI of the asset
     * @param extraPrefix    a prefix the action adds below a bucket or folder; may be {@code null}
     * @param maxPrefixBytes the longest prefix allowed
     * @return the scope
     * @throws RuleException {@code UNSAFE_LOCATION}, {@code UNSUPPORTED_ASSET} or {@code PREFIX_TOO_LONG}
     */
    public static RuleScope parse(String provider, String location, String extraPrefix, int maxPrefixBytes) {
        String p = provider == null ? "" : provider.toUpperCase(Locale.ROOT);
        if (location == null || location.isBlank()) {
            throw new RuleException(RuleException.Reason.UNSUPPORTED_ASSET, "the asset has no storage location");
        }
        if (LocationSafety.carriesUrlSelector(location)) {
            throw new RuleException(RuleException.Reason.UNSAFE_LOCATION,
                    "a location with a query or fragment can select a version or snapshot: "
                            + LocationSafety.redact(location));
        }
        String lower = location.toLowerCase(Locale.ROOT);
        String rest;
        switch (p) {
            case "AWS" -> rest = after(lower, location, "s3://");
            case "GCP" -> rest = after(lower, location, "gs://");
            case "AZURE" -> rest = lower.startsWith("az://") ? location.substring("az://".length())
                    : after(lower, location, "azure://");
            default -> throw new RuleException(RuleException.Reason.UNSUPPORTED_PROVIDER,
                    "no native archive rule for provider '" + provider + "'");
        }
        String account = "";
        if ("AZURE".equals(p)) {
            int sep = rest.indexOf('/');
            if (sep <= 0) {
                throw new RuleException(RuleException.Reason.UNSUPPORTED_ASSET,
                        "an Azure storage account is not archived as a whole; name a container");
            }
            account = rest.substring(0, sep);
            rest = rest.substring(sep + 1);
        }
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String key = slash < 0 ? "" : rest.substring(slash + 1);
        if (bucket.isEmpty() || !bucket.equals(bucket.strip())) {
            throw new RuleException(RuleException.Reason.UNSUPPORTED_ASSET, "the location names no bucket");
        }
        if (LocationSafety.hasEdgeWhitespace(key)) {
            throw new RuleException(RuleException.Reason.UNSAFE_LOCATION,
                    "an object key with leading or trailing white space is ambiguous");
        }
        String extra = extraPrefix == null ? "" : extraPrefix;
        checkPrefixText(extra);
        String prefix;
        boolean single = false;
        if (key.isEmpty()) {
            prefix = extra;
        } else if (key.endsWith("/")) {
            prefix = key + extra;
        } else {
            if (!extra.isEmpty()) {
                throw new RuleException(RuleException.Reason.UNSAFE_LOCATION,
                        "a prefix cannot narrow a single object");
            }
            prefix = key;
            single = true;
        }
        if (prefix.getBytes(StandardCharsets.UTF_8).length > maxPrefixBytes) {
            throw new RuleException(RuleException.Reason.PREFIX_TOO_LONG,
                    "the prefix is longer than " + maxPrefixBytes + " bytes");
        }
        return new RuleScope(p, account, bucket, prefix, single);
    }

    private static String after(String lower, String original, String scheme) {
        if (!lower.startsWith(scheme)) {
            throw new RuleException(RuleException.Reason.UNSUPPORTED_ASSET,
                    "not a storage location of this provider: " + LocationSafety.redact(original));
        }
        return original.substring(scheme.length());
    }

    private static void checkPrefixText(String extra) {
        if (extra.startsWith("/")) {
            throw new RuleException(RuleException.Reason.UNSAFE_LOCATION, "a prefix must not start with '/'");
        }
        for (int i = 0; i < extra.length(); i++) {
            if (Character.isISOControl(extra.charAt(i))) {
                throw new RuleException(RuleException.Reason.UNSAFE_LOCATION,
                        "a prefix must not contain control characters");
            }
        }
    }
}
