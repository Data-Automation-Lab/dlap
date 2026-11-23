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

package com.dlap.catalog.lifecycle;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.dlap.catalog.coverage.ReadCoverageObservation;
import com.dlap.catalog.kafka.CatalogEvent;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.ResultStatus;

/**
 * Keeps the Log Event ids behind the Lifecycle State fields (docs/specs/lifecycle-state.md section
 * 3.8). It listens to the events the catalog's appliers publish inside their transactions, so a row
 * and its evidence commit together.
 *
 * <p>The rule is the same for every field: after the catalog's event-time rules ran, an event is
 * evidence of a field when the value it offers equals the value the asset row now holds. The
 * recorder never computes a value itself; it only names the events behind the value the catalog
 * stored. An event that offers nothing, or a value the row does not hold, records nothing.
 *
 * <table>
 *   <caption>What an event offers</caption>
 *   <tr><th>Field</th><th>Value the event offers</th></tr>
 *   <tr><td>{@code createdAt}</td><td>a direct, proven event's reported {@code created_time}, and the
 *       event time of a direct, proven management CREATE</td></tr>
 *   <tr><td>{@code lastReadAt}</td><td>the event time of a successful {@code DATA_READ} or
 *       {@code DATA_READ_OBSERVED} event (after roll-up)</td></tr>
 *   <tr><td>{@code lastWrittenAt}</td><td>the event time of a successful {@code DATA_WRITE} event
 *       (after roll-up)</td></tr>
 *   <tr><td>{@code sizeBytes}</td><td>a direct, proven event's {@code size_bytes} at its event time</td></tr>
 *   <tr><td>{@code disposition}</td><td>a direct, proven management DELETE or ARCHIVE at its event
 *       time (proven as the applier's transition rule reads it: a success, or a crawler observation)</td></tr>
 *   <tr><td>{@code retention:*}</td><td>a direct crawler event's provider retention setting
 *       ({@code expirationTime}, {@code retentionPeriodDays}) as text</td></tr>
 * </table>
 *
 * <p>Every time is compared and keyed as {@link #asStored(Instant)} gives it: the value the
 * {@code dlap_assets} {@code TIMESTAMPTZ} column holds after a write. A Log Event time may carry up
 * to nine fraction digits (Azure activity logs carry seven, the GCP Logging API nine) while the
 * column keeps six, so the raw event time would never match the stored value read back.
 */
@Component
public class LifecycleEvidenceRecorder {

    /** Field key of {@code createdAt}. */
    public static final String CREATED_AT = "createdAt";
    /** Field key of {@code lastReadAt}. */
    public static final String LAST_READ_AT = "lastReadAt";
    /** Field key of {@code lastWrittenAt}. */
    public static final String LAST_WRITTEN_AT = "lastWrittenAt";
    /** Field key of {@code sizeBytes}. */
    public static final String SIZE_BYTES = "sizeBytes";
    /** Field key of {@code disposition}. */
    public static final String DISPOSITION = "disposition";
    /** {@code technical_metadata} key of a provider expiry (a {@code FIXED_DATE} rule). */
    public static final String EXPIRATION_TIME = "expirationTime";
    /** {@code technical_metadata} key of a provider retention period (a {@code CREATED} rule). */
    public static final String RETENTION_PERIOD_DAYS = "retentionPeriodDays";
    /** Field-key prefix of a provider retention setting. */
    public static final String RETENTION_PREFIX = "retention:";

    /** {@code executedBy} of a disposition the provider performed on its own rule. */
    public static final String NATIVE_RULE = "native-rule";
    /** {@code executedBy} of a disposition this engine's executor requested. */
    public static final String ENGINE = "engine";
    /** Any other actor: no {@code executedBy} value in 0.1. */
    public static final String OTHER = "other";

    private final LifecycleEvidenceStore store;
    private final LifecycleStateProperties properties;

    /**
     * Creates the recorder.
     *
     * @param store      the evidence tables
     * @param properties the engine principals and the cap per field
     */
    public LifecycleEvidenceRecorder(LifecycleEvidenceStore store, LifecycleStateProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Records the evidence one applied lifecycle event gives one asset row.
     *
     * @param applied the event and the row after the rules ran
     */
    @EventListener
    public void onApplied(AppliedLogEvent applied) {
        CatalogEvent event = applied.event();
        String eventId = event.eventKey();
        if (!LifecycleEvidenceStore.EVENT_ID.matcher(eventId).matches()) {
            return;
        }
        Asset asset = applied.asset();
        Instant time = asStored(applied.eventTime());
        boolean success = event.event().getResultStatus() == ResultStatus.SUCCESS;
        String channel = event.channel();
        String binding = event.event().getBindingId();
        int cap = properties.getMaxEventIdsPerField();

        if (applied.direct() && event.isProven()) {
            List<Instant> created = new ArrayList<>(2);
            event.createdTime().map(LifecycleEvidenceRecorder::asStored).ifPresent(created::add);
            if (isManagement(event, ChangeType.CREATE)) {
                created.add(time);
            }
            Instant storedCreated = asStored(asset.getCloudCreatedAt());
            if (storedCreated != null && created.contains(storedCreated)) {
                record(asset, CREATED_AT, timeKey(storedCreated), eventId, time, channel, binding,
                        null, LifecycleEvidenceStore.Keep.ALL_WITH_VALUE, cap);
            }
            Optional<Long> size = event.sizeBytes();
            if (size.isPresent() && size.get().equals(asset.getSizeBytes())
                    && time.equals(asStored(asset.getSizeObservedAt()))) {
                record(asset, SIZE_BYTES, sizeKey(asset.getSizeBytes(), asset.getSizeObservedAt()), eventId, time,
                        channel, binding, null, LifecycleEvidenceStore.Keep.ALL_WITH_VALUE, cap);
            }
            dispositionAction(event).filter(action -> holds(asset, action, time))
                    .ifPresent(action -> record(asset, DISPOSITION, dispositionKey(action, time), eventId, time,
                            channel, binding, executedBy(event.event()),
                            LifecycleEvidenceStore.Keep.ALL_WITH_VALUE, cap));
        }
        if (success) {
            EventCategory category = applied.category();
            if ((category == EventCategory.EVENT_CATEGORY_DATA_READ
                    || category == EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED)
                    && time.equals(asStored(asset.getLastAccessedAt()))) {
                record(asset, LAST_READ_AT, timeKey(time), eventId, time, channel, binding, null,
                        LifecycleEvidenceStore.Keep.ALL_WITH_VALUE, cap);
            }
            if (category == EventCategory.EVENT_CATEGORY_DATA_WRITE
                    && time.equals(asStored(asset.getLastModifiedAt()))) {
                record(asset, LAST_WRITTEN_AT, timeKey(time), eventId, time, channel, binding, null,
                        LifecycleEvidenceStore.Keep.ALL_WITH_VALUE, cap);
            }
        }
        if (applied.direct() && event.isCrawler()) {
            providerSetting(asset, event, EXPIRATION_TIME, eventId, time, channel, binding, cap);
            providerSetting(asset, event, RETENTION_PERIOD_DAYS, eventId, time, channel, binding, cap);
        }
    }

    /**
     * Records an applied read-coverage observation.
     *
     * @param applied the observation
     */
    @EventListener
    public void onCoverage(AppliedCoverageObservation applied) {
        ReadCoverageObservation o = applied.observation();
        if (o.eventId() == null || !LifecycleEvidenceStore.EVENT_ID.matcher(o.eventId()).matches()) {
            return;
        }
        List<String> kinds = new ArrayList<>(o.appliesTo());
        kinds.sort(null);
        store.recordCoverage(new LifecycleEvidenceStore.CoverageEvidence(o.eventId(), o.provider(), o.rootUri(),
                kinds, asStored(o.observedAt())));
    }

    /**
     * The value a {@code dlap_assets} {@code TIMESTAMPTZ} column holds after this instant is written:
     * whole microseconds, a sub-microsecond part above 499 ns rounded up. This is the rounding the
     * PostgreSQL JDBC driver applies before it sends a timestamp ({@code TimestampUtils}), so the
     * column never sees more than six fraction digits. Applying it twice gives the same value.
     *
     * @param instant an event or stored time; may be {@code null}
     * @return the stored form, or {@code null} for {@code null}
     */
    public static Instant asStored(Instant instant) {
        if (instant == null) {
            return null;
        }
        Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
        return instant.getNano() % 1_000 > 499 ? micros.plus(1, ChronoUnit.MICROS) : micros;
    }

    /**
     * The value key of a {@code createdAt}, {@code lastReadAt} or {@code lastWrittenAt} time.
     *
     * @param time the time
     * @return the stored form as ISO-8601 text
     */
    public static String timeKey(Instant time) {
        return asStored(time).toString();
    }

    /**
     * The value key of a size observation.
     *
     * @param sizeBytes  the size
     * @param observedAt when it was true
     * @return {@code <size>@<instant>}, the instant in its stored form
     */
    public static String sizeKey(long sizeBytes, Instant observedAt) {
        return sizeBytes + "@" + asStored(observedAt);
    }

    /**
     * The value key of a disposition.
     *
     * @param action     {@code archive} or {@code delete}
     * @param executedAt the event time
     * @return {@code <action>@<instant>}, the instant in its stored form
     */
    public static String dispositionKey(String action, Instant executedAt) {
        return action + "@" + asStored(executedAt);
    }

    /**
     * Who executed a disposition: an engine principal first; then the provider's own lifecycle rule,
     * which needs a principal of type {@code SYSTEM} and the provider's lifecycle signature
     * ({@link LifecycleSignature}, D36, Q-654); anything else, including another provider service
     * acting as {@code SYSTEM} (S3 replication, for example), is {@link #OTHER}.
     *
     * @param event the event
     * @return {@link #ENGINE}, {@link #NATIVE_RULE} or {@link #OTHER}
     */
    String executedBy(DlapLogEvent event) {
        if (event == null || !event.hasPrincipal()) {
            return OTHER;
        }
        Principal principal = event.getPrincipal();
        if (!principal.getId().isBlank() && properties.getEnginePrincipals().contains(principal.getId())) {
            return ENGINE;
        }
        return LifecycleSignature.isNativeRule(event) ? NATIVE_RULE : OTHER;
    }

    private void providerSetting(Asset asset, CatalogEvent event, String key, String eventId, Instant time,
                                 String channel, String binding, int cap) {
        String offered = event.technicalMetadata().get(key);
        Map<String, Object> stored = asset.getTechnicalMetadata();
        Object held = stored == null ? null : stored.get(key);
        if (offered == null || offered.isBlank() || held == null || !offered.equals(String.valueOf(held))) {
            return;
        }
        record(asset, RETENTION_PREFIX + key, offered, eventId, time, channel, binding, null,
                LifecycleEvidenceStore.Keep.LATEST_WITH_VALUE, cap);
    }

    private void record(Asset asset, String field, String value, String eventId, Instant time, String channel,
                        String binding, String executedBy, LifecycleEvidenceStore.Keep keep, int cap) {
        store.record(new LifecycleEvidenceStore.Evidence(asset.getAssetId(), field, value, eventId, time, channel,
                binding == null || binding.isBlank() ? null : binding, executedBy), keep, cap);
    }

    private static boolean isManagement(CatalogEvent event, ChangeType type) {
        return event.category() == EventCategory.EVENT_CATEGORY_MANAGEMENT && event.changeType() == type;
    }

    private static Optional<String> dispositionAction(CatalogEvent event) {
        if (isManagement(event, ChangeType.DELETE)) {
            return Optional.of("delete");
        }
        if (isManagement(event, ChangeType.ARCHIVE)) {
            return Optional.of("archive");
        }
        return Optional.empty();
    }

    /** Whether the row's status and lifecycle time are the ones this action at this time set. */
    private static boolean holds(Asset asset, String action, Instant time) {
        String status = "delete".equals(action) ? DlapConstants.AssetStatus.DELETED : DlapConstants.AssetStatus.ARCHIVED;
        return Objects.equals(asset.getStatus(), status) && time.equals(asStored(asset.getLifecycleEventTime()));
    }
}
