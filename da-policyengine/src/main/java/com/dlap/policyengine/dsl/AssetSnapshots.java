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

package com.dlap.policyengine.dsl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;

/**
 * The asset snapshot the {@link PolicyDslEvaluator} reads: its key names and the builders that
 * turn a catalog asset or an activity row into one (POL-01).
 *
 * <p>One vocabulary serves the event consumer, the scheduled sweep, the on-demand REST evaluation
 * and the PDP. A key is present only when its value is known; an absent key is an unknown value
 * and makes the clause that reads it {@link MatchOutcome#UNKNOWN}. Times are ISO-8601 strings, so
 * a snapshot copied into an action event serialises the same way whatever mapper writes it.
 *
 * <p>{@link #CLOUD_CREATED_AT} is the provider's creation time (ADR-0046). The catalog row's own
 * {@code createdAt} (when DLAP inserted the row) and {@code discoveredAt} are never copied into
 * it: the {@code createdAt} match clause reads the provider time only.
 */
public final class AssetSnapshots {

    /** Catalog asset id. */
    public static final String ASSET_ID = "assetId";
    /** Canonical asset URI (ADR-0047). */
    public static final String ASSET_URI = "assetUri";
    /** Display name. */
    public static final String ASSET_NAME = "assetName";
    /** Legacy alias of {@link #ASSET_NAME}. */
    public static final String NAME = "name";
    /** Cloud provider. */
    public static final String CLOUD_PROVIDER = "cloudProvider";
    /** Asset kind. */
    public static final String SERVICE_TYPE = "serviceType";
    /** Asset type. */
    public static final String ASSET_TYPE = "assetType";
    /** Region or location. */
    public static final String REGION = "region";
    /** Lifecycle status. */
    public static final String STATUS = "status";
    /** Label names. */
    public static final String LABELS = "labels";
    /** Cloud-native tags. */
    public static final String TAGS = "tags";
    /** Latest recorded read, event time. */
    public static final String LAST_ACCESSED_AT = "lastAccessedAt";
    /** Provider creation time; read by the {@code createdAt} clause. */
    public static final String CLOUD_CREATED_AT = "cloudCreatedAt";
    /** Latest recorded content write, event time. */
    public static final String LAST_MODIFIED_AT = "lastModifiedAt";
    /** Latest observed size in bytes. */
    public static final String SIZE_BYTES = "sizeBytes";
    /** Read-logging coverage: FULL, PARTIAL or NONE. */
    public static final String READ_COVERAGE = "readCoverage";
    /** When the catalog last observed {@link #READ_COVERAGE} (ISO-8601 instant; Q-475). */
    public static final String READ_COVERAGE_OBSERVED_AT = "readCoverageObservedAt";
    /**
     * Start of the current unbroken run of {@code FULL} coverage (ISO-8601 instant; COV-02). Every read
     * since then is recorded, so with no recorded read since it the asset was not read since it
     * (POL-03, Q-434).
     */
    public static final String READ_COVERAGE_SINCE = "readCoverageSince";
    /** DLAP legal hold. */
    public static final String LEGAL_HOLD = "legalHold";
    /** Stored retention status. */
    public static final String RETENTION_STATUS = "retentionStatus";
    /** Expiry found by the last retention evaluation. */
    public static final String RETENTION_EXPIRES_AT = "retentionExpiresAt";
    /** Provider-native id. */
    public static final String NATIVE_ASSET_ID = "nativeAssetId";

    /** The only read coverage under which a recorded last read proves an asset unread since. */
    public static final String COVERAGE_FULL = "FULL";

    /**
     * The lifecycle keys: event times, size, read coverage, hold and retention. Only the catalog's
     * own fields (or the event itself) supply them, never a free-form details map.
     */
    public static final Set<String> LIFECYCLE_KEYS = Set.of(LAST_ACCESSED_AT, CLOUD_CREATED_AT, LAST_MODIFIED_AT,
            SIZE_BYTES, READ_COVERAGE, READ_COVERAGE_OBSERVED_AT, READ_COVERAGE_SINCE, LEGAL_HOLD, RETENTION_STATUS, RETENTION_EXPIRES_AT);

    /** Key in the catalog's {@code technicalMetadata} that holds read coverage until it is a column. */
    static final String TECHNICAL_READ_COVERAGE = "readCoverage";

    private AssetSnapshots() {
    }

    /**
     * Snapshot of an asset as {@code GET /assets/{id}} returns it. The catalog answered, so a
     * missing label list means no labels. The coverage observation time and the coverage start are
     * read the way the activity row reads them (POL-03, Q-837), so the event path and the PDP apply
     * the same freshness rule as the scheduled sweep.
     *
     * @param dto the catalog asset, never {@code null}
     * @return a mutable snapshot holding the known values only
     */
    public static Map<String, Object> fromCatalogAsset(CatalogAssetDTO dto) {
        Map<String, Object> s = new LinkedHashMap<>();
        put(s, ASSET_ID, dto.getAssetId() != null ? dto.getAssetId().toString() : null);
        put(s, ASSET_URI, dto.getAssetUri());
        put(s, ASSET_NAME, dto.getAssetName());
        put(s, NAME, dto.getAssetName());
        put(s, CLOUD_PROVIDER, dto.getCloudProvider());
        put(s, SERVICE_TYPE, dto.getServiceType());
        put(s, ASSET_TYPE, dto.getAssetType());
        put(s, REGION, dto.getRegion());
        put(s, STATUS, dto.getStatus());
        put(s, NATIVE_ASSET_ID, dto.getNativeAssetId());
        s.put(LABELS, dto.getLabels() != null ? List.copyOf(dto.getLabels()) : List.of());
        put(s, TAGS, tagsOf(dto.getDetailsJson(), dto.getBusinessMetadataJson()));
        put(s, LAST_ACCESSED_AT, iso(dto.getLastAccessedAt()));
        put(s, CLOUD_CREATED_AT, iso(dto.getCloudCreatedAt()));
        put(s, LAST_MODIFIED_AT, iso(dto.getLastModifiedAt()));
        put(s, SIZE_BYTES, dto.getSizeBytes());
        Object coverage = dto.getReadCoverage();
        if (coverage == null && dto.getTechnicalMetadata() != null) {
            coverage = dto.getTechnicalMetadata().get(TECHNICAL_READ_COVERAGE);
        }
        put(s, READ_COVERAGE, normaliseCoverage(coverage));
        put(s, READ_COVERAGE_OBSERVED_AT, observedAt(dto.getReadCoverageObservedAt()));
        put(s, READ_COVERAGE_SINCE, observedAt(dto.getReadCoverageSince()));
        put(s, LEGAL_HOLD, dto.getLegalHold());
        put(s, RETENTION_STATUS, dto.getRetentionStatus());
        put(s, RETENTION_EXPIRES_AT, iso(dto.getRetentionExpiresAt()));
        return s;
    }

    /**
     * Snapshot of one activity-report row (the scheduled sweep). The row carries no tags, so a
     * {@code tagMatches} clause is unknown on this path.
     *
     * @param row the row, never {@code null}
     * @return a mutable snapshot holding the known values only
     */
    public static Map<String, Object> fromActivityRow(CatalogActivityRowDTO row) {
        Map<String, Object> s = new LinkedHashMap<>();
        put(s, ASSET_ID, row.assetId() != null ? row.assetId().toString() : null);
        put(s, ASSET_URI, row.assetUri());
        put(s, ASSET_NAME, row.assetName());
        put(s, NAME, row.assetName());
        put(s, CLOUD_PROVIDER, row.cloudProvider());
        put(s, SERVICE_TYPE, row.serviceType());
        put(s, ASSET_TYPE, row.assetType());
        put(s, REGION, row.region());
        put(s, STATUS, row.status());
        s.put(LABELS, row.labels() != null ? List.copyOf(row.labels()) : List.of());
        put(s, LAST_ACCESSED_AT, iso(row.lastAccessedAt()));
        put(s, CLOUD_CREATED_AT, iso(row.cloudCreatedAt()));
        put(s, LAST_MODIFIED_AT, iso(row.lastModifiedAt()));
        put(s, SIZE_BYTES, row.sizeBytes());
        put(s, READ_COVERAGE, normaliseCoverage(row.readCoverage()));
        put(s, READ_COVERAGE_OBSERVED_AT, observedAt(row.readCoverageObservedAt()));
        put(s, READ_COVERAGE_SINCE, observedAt(row.readCoverageSince()));
        put(s, LEGAL_HOLD, row.legalHold());
        put(s, RETENTION_STATUS, row.retentionStatus());
        put(s, RETENTION_EXPIRES_AT, iso(row.retentionExpiresAt()));
        return s;
    }

    /**
     * Copies into {@code target} every known value of {@code source} whose key {@code target} does
     * not hold yet. The target's own values win.
     *
     * @param target the snapshot to complete
     * @param source the values to fill gaps from; may be {@code null}
     */
    public static void fillGaps(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        source.forEach((k, v) -> {
            if (v != null && target.get(k) == null) {
                target.put(k, v);
            }
        });
    }

    /**
     * The snapshot a policy that requires full read coverage is judged on, when the event and the
     * catalog both describe the asset (Q-471): the catalog's read coverage, its observation time and
     * its coverage start replace the event's (an event cannot claim coverage the catalog does not record; a catalog
     * that did not answer leaves coverage unknown), and {@code lastAccessedAt} is the later of the
     * two recorded reads. Every other key keeps the event's value.
     *
     * @param eventSnapshot the event's snapshot, already completed from the catalog
     * @param catalogView   the catalog's snapshot of the asset; {@code null} when it did not answer
     * @return a new snapshot; the inputs are not changed
     */
    public static Map<String, Object> withCatalogCoverage(Map<String, Object> eventSnapshot,
            Map<String, Object> catalogView) {
        Map<String, Object> s = new LinkedHashMap<>(eventSnapshot);
        s.remove(READ_COVERAGE);
        s.remove(READ_COVERAGE_OBSERVED_AT);
        s.remove(READ_COVERAGE_SINCE);
        if (catalogView == null) {
            return s;
        }
        put(s, READ_COVERAGE, normaliseCoverage(catalogView.get(READ_COVERAGE)));
        put(s, READ_COVERAGE_OBSERVED_AT, catalogView.get(READ_COVERAGE_OBSERVED_AT));
        put(s, READ_COVERAGE_SINCE, catalogView.get(READ_COVERAGE_SINCE));
        Instant eventRead = instant(eventSnapshot.get(LAST_ACCESSED_AT));
        Instant catalogRead = instant(catalogView.get(LAST_ACCESSED_AT));
        if (catalogRead != null && (eventRead == null || catalogRead.isAfter(eventRead))) {
            s.put(LAST_ACCESSED_AT, iso(catalogRead));
        }
        return s;
    }

    /**
     * Upper-cases a read-coverage value; blank means unknown.
     *
     * @param coverage the raw value; may be {@code null}
     * @return {@code FULL}, {@code PARTIAL}, {@code NONE} or another stored value, or {@code null}
     */
    public static String normaliseCoverage(Object coverage) {
        if (coverage == null) {
            return null;
        }
        String text = coverage.toString().strip();
        return text.isEmpty() ? null : text.toUpperCase(Locale.ROOT);
    }

    /**
     * The first non-blank string among the values of {@code keys} in {@code map}.
     *
     * @param map  the map; may be {@code null}
     * @param keys the keys in order of preference
     * @return the value, or {@code null}
     */
    public static String firstText(Map<String, ?> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return null;
    }

    private static Map<String, Object> tagsOf(Map<String, Object> details, Map<String, Object> business) {
        if (details != null && details.get(TAGS) instanceof Map<?, ?> m) {
            return stringKeys(m);
        }
        if (business != null && business.get(TAGS) instanceof Map<?, ?> m) {
            return stringKeys(m);
        }
        return null;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k.toString(), v);
            }
        });
        return out;
    }

    /**
     * A coverage time of an activity row or a catalog asset (observation time or coverage start),
     * read defensively: the fields are optional (COV-02 adds them) and arrive as text, so a blank
     * value is absent and an unparseable one is kept as text for the evaluator, which counts an
     * unparseable observation time as stale and an unparseable start as absent. Never an error.
     */
    private static String observedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Instant t = instant(raw);
        return t == null ? raw.strip() : t.toString();
    }

    private static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        String text = v.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String iso(Instant t) {
        return t == null ? null : t.toString();
    }

    private static void put(Map<String, Object> s, String key, Object value) {
        if (value != null) {
            s.put(key, value);
        }
    }
}
