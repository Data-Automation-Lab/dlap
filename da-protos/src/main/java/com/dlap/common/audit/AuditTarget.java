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

package com.dlap.common.audit;

import java.util.Objects;
import java.util.regex.Pattern;

import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.CanonicalAssetId;

/**
 * What an audited event acted on (docs/specs/audit-log.md, section "Record").
 *
 * @param kind the kind of object: upper case, for example {@code ASSET}, {@code POLICY},
 *             {@code APPROVAL}, {@code ROLE}, {@code EXPORT}
 * @param id   the object's identifier, 1 to 2048 characters; for {@code ASSET} the canonical
 *             asset URI (ADR-0047)
 */
public record AuditTarget(String kind, String id) {

    /** The kind of a data asset. */
    public static final String ASSET = "ASSET";

    /** Maximum length of {@link #id()}. */
    public static final int MAX_ID_LENGTH = AssetIdNormalizer.MAX_URI_LENGTH;

    private static final Pattern KIND = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

    /**
     * Validates the target.
     *
     * @param kind the kind
     * @param id   the identifier
     */
    public AuditTarget {
        Objects.requireNonNull(kind, "kind");
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("target.kind must match " + KIND.pattern());
        }
        AuditEvent.requireText("target.id", id, MAX_ID_LENGTH);
    }

    /**
     * A data asset. The identifier passes through {@link AssetIdNormalizer} (work-plan rule R8):
     * an identifier that normalizes is stored as its canonical URI, one that does not is stored
     * as given, so the audit still records what the caller acted on.
     *
     * @param assetId the asset identifier in any form the normalizer accepts
     * @return the target
     */
    public static AuditTarget asset(String assetId) {
        String id = AssetIdNormalizer.normalize(assetId).map(CanonicalAssetId::uri).orElse(assetId);
        return new AuditTarget(ASSET, id);
    }

    /**
     * Any other object.
     *
     * @param kind the kind
     * @param id   the identifier
     * @return the target
     */
    public static AuditTarget of(String kind, String id) {
        return new AuditTarget(kind, id);
    }
}
