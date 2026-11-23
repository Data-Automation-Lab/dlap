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

package com.dlap.logingest.parse.core;

import com.dlap.common.assetid.AssetKind;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One row of an explicit operation classification table (design B1 "Declarative data"): a service
 * and an operation, or an operation prefix, give the change type, the event category and,
 * optionally, the asset kind of the target and the name of the family's resource extractor.
 *
 * <p>Mapping YAMLs hold these rows under {@code classification.rules}; bindings add their own under
 * {@code operation-overrides}, which win.
 *
 * @param service         provider service, exact, for example {@code s3.amazonaws.com}
 * @param operation       exact operation name, or {@code null} when {@code operationPrefix} is set
 * @param operationPrefix operation prefix, or {@code null} when {@code operation} is set
 * @param changeType      routing change type; never unspecified
 * @param category        event category; never unspecified
 * @param assetKind       the kind of the target, or {@code null}; with a data category a target
 *                        that does not normalize is quarantined as {@code UNRESOLVABLE_ASSET_ID}
 * @param extractor       the family's resource extractor name, or {@code null}
 */
public record ClassificationRule(
        String service,
        String operation,
        String operationPrefix,
        ChangeType changeType,
        EventCategory category,
        AssetKind assetKind,
        String extractor) {

    /** Allowed shape of an extractor name, for example {@code s3.request-parameters}. */
    public static final Pattern EXTRACTOR = Pattern.compile("[a-z0-9]+([.-][a-z0-9]+)*");

    /**
     * Validates the row.
     *
     * @param service         see the record description
     * @param operation       see the record description
     * @param operationPrefix see the record description
     * @param changeType      see the record description
     * @param category        see the record description
     * @param assetKind       see the record description
     * @param extractor       see the record description
     */
    public ClassificationRule {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("a classification rule needs a service");
        }
        boolean exact = operation != null && !operation.isBlank();
        boolean prefix = operationPrefix != null && !operationPrefix.isBlank();
        if (exact == prefix) {
            throw new IllegalArgumentException("a classification rule needs exactly one of operation and operation-prefix");
        }
        requireKnown(changeType);
        requireKnown(category);
        if (extractor != null && !EXTRACTOR.matcher(extractor).matches()) {
            throw new IllegalArgumentException("extractor must match " + EXTRACTOR.pattern());
        }
        operation = exact ? operation : null;
        operationPrefix = prefix ? operationPrefix : null;
    }

    /**
     * An exact rule without asset kind or extractor.
     *
     * @param service    the service
     * @param operation  the operation
     * @param changeType the change type
     * @param category   the category
     * @return the rule
     */
    public static ClassificationRule exact(String service, String operation, ChangeType changeType,
                                           EventCategory category) {
        return new ClassificationRule(service, operation, null, changeType, category, null, null);
    }

    /**
     * Whether the row matches an event.
     *
     * @param eventService   the event's service
     * @param eventOperation the event's operation
     * @return true on a match
     */
    public boolean matches(String eventService, String eventOperation) {
        if (!service.equals(eventService) || eventOperation == null) {
            return false;
        }
        return operation != null ? operation.equals(eventOperation) : eventOperation.startsWith(operationPrefix);
    }

    /**
     * How specific the row is: an exact operation beats every prefix, a longer prefix beats a
     * shorter one.
     *
     * @return a larger number for a more specific row
     */
    public int specificity() {
        return operation != null ? Integer.MAX_VALUE : operationPrefix.length();
    }

    /**
     * The asset kind, if any.
     *
     * @return the kind
     */
    public Optional<AssetKind> assetKindOptional() {
        return Optional.ofNullable(assetKind);
    }

    private static void requireKnown(Enum<?> value) {
        Objects.requireNonNull(value, "classification value");
        String name = value.name();
        if (name.endsWith("_UNSPECIFIED") || "UNRECOGNIZED".equals(name)) {
            throw new IllegalArgumentException("a classification rule never maps to an unspecified value");
        }
    }
}
