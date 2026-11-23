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

package com.dlap.common.assetid;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link AssetIdNormalizer#parse(String, AssetIdHints)}: either a canonical ID or
 * a rejection with a machine-readable reason. Rejections never guess; a destructive caller (archive,
 * delete) fails closed on any rejection.
 */
public sealed interface AssetIdParseResult
        permits AssetIdParseResult.Normalized, AssetIdParseResult.Rejected {

    /** Why an identifier did not normalize. */
    enum Reason {
        /** The input was null or blank. */
        BLANK,
        /** No scheme recognised the form. */
        UNRECOGNIZED,
        /** The form fits more than one reading; a provider or kind hint would decide. */
        AMBIGUOUS,
        /** The form is recognised but a hint it needs (account, project, metastore...) is absent. */
        MISSING_HINT,
        /** The form is recognised but a component is invalid (bad bucket name, bad account...). */
        MALFORMED,
        /** The form names something that is not a DLAP asset kind (an IAM user, a KMS key...). */
        UNSUPPORTED_KIND,
        /** The input or the canonical URI exceeds the length limit. */
        TOO_LONG
    }

    /**
     * The canonical ID, when normalization succeeded.
     *
     * @return the ID, or empty for a rejection
     */
    Optional<CanonicalAssetId> id();

    /**
     * The input exactly as the caller passed it.
     *
     * @return the raw input, may be {@code null}
     */
    String raw();

    /**
     * Whether normalization succeeded.
     *
     * @return true for {@link Normalized}
     */
    default boolean isNormalized() {
        return this instanceof Normalized;
    }

    /**
     * A successful normalization.
     *
     * @param canonical the canonical ID
     * @param raw       the input as passed, kept for {@code dlap_assets.native_asset_id}
     */
    record Normalized(CanonicalAssetId canonical, String raw) implements AssetIdParseResult {
        /**
         * Validates the components.
         *
         * @param canonical the canonical ID, never null
         * @param raw       the raw input
         */
        public Normalized {
            Objects.requireNonNull(canonical, "canonical");
        }

        /**
         * The canonical ID.
         *
         * @return the ID, never empty
         */
        @Override
        public Optional<CanonicalAssetId> id() {
            return Optional.of(canonical);
        }
    }

    /**
     * A rejection.
     *
     * @param reason the reason
     * @param detail a human-readable explanation; never contains secrets (query strings are dropped
     *               before parsing)
     * @param raw    the input as passed
     */
    record Rejected(Reason reason, String detail, String raw) implements AssetIdParseResult {
        /**
         * Validates the components.
         *
         * @param reason the reason, never null
         * @param detail the explanation
         * @param raw    the raw input
         */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }

        /**
         * Always empty.
         *
         * @return empty
         */
        @Override
        public Optional<CanonicalAssetId> id() {
            return Optional.empty();
        }
    }
}
