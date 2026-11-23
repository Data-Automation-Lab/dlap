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

package com.dlap.catalog.kafka;

import java.time.Instant;
import java.util.Objects;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.model.RetentionStatus;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.EventCategory;

/**
 * The event-time rules of ADR-0046 and docs/specs/asset-descriptor.md ("Time, size and
 * activity"), as pure functions over an {@link Asset}. Every time argument is a business
 * time from provider evidence; nothing here reads a clock.
 *
 * <ul>
 *   <li>Monotonic: {@code last*} fields take the maximum, {@code cloudCreatedAt} the minimum.</li>
 *   <li>Lifecycle ordering: a status transition applies only when
 *       {@code eventTime >= lifecycleEventTime} (null counts as never).</li>
 *   <li>A CREATE is a transition only on a {@code DELETED} row (a new incarnation). On any
 *       other row it is existence evidence: status and {@code lifecycleEventTime} stay, so a
 *       crawl never reactivates an ARCHIVED or PENDING_DELETION row and never makes a
 *       delayed ARCHIVE or DELETE stale.</li>
 *   <li>Incarnation boundary: evidence older than {@code recreatedAt} never changes the row.</li>
 * </ul>
 */
final class AssetStateRules {

    /** Width of the evidence-channel columns ({@code *_source}, VARCHAR(64)). */
    static final int MAX_CHANNEL_LENGTH = 64;

    /** A status transition carried by a management event. */
    enum Transition {
        /**
         * The resource was created or observed to exist: on a DELETED row a new incarnation
         * (status ACTIVE); on any other row no status change.
         */
        CREATE,
        /** The resource was deleted: status DELETED. */
        DELETE,
        /** The resource moved to an archive tier: status ARCHIVED. */
        ARCHIVE
    }

    /** What a lifecycle event did. */
    enum LifecycleOutcome {
        /** The transition applied (the status may be unchanged, the lifecycle time moved). */
        APPLIED,
        /** A CREATE on a DELETED row started a new incarnation. */
        NEW_INCARNATION,
        /** The event is older than the last transition or the incarnation; nothing changed. */
        STALE,
        /**
         * A CREATE on a row that is not DELETED: the resource is known to exist, which is not
         * a transition; status and lifecycle time are unchanged.
         */
        UNCHANGED
    }

    private AssetStateRules() {
    }

    /**
     * Whether evidence at {@code time} belongs to the asset's current incarnation.
     *
     * @param asset the asset
     * @param time  the evidence time
     * @return {@code false} when the time is before {@code recreatedAt}
     */
    static boolean withinIncarnation(Asset asset, Instant time) {
        return asset.getRecreatedAt() == null || !time.isBefore(asset.getRecreatedAt());
    }

    /**
     * Moves the activity maximum the category controls ({@code DATA_READ} and
     * {@code DATA_READ_OBSERVED}: last access; {@code DATA_WRITE}: last modified;
     * {@code PERMISSION}: last permission change). The caller has checked the event is a
     * successful one.
     *
     * @param asset     the asset
     * @param category  the effective category, after roll-up
     * @param eventTime the event time
     * @param channel   the evidence channel, stored with the last access
     * @return {@code true} when a field moved
     */
    static boolean applyActivity(Asset asset, EventCategory category, Instant eventTime, String channel) {
        Objects.requireNonNull(eventTime, "eventTime");
        if (category == null || !withinIncarnation(asset, eventTime)) {
            return false;
        }
        switch (category) {
            case EVENT_CATEGORY_DATA_READ, EVENT_CATEGORY_DATA_READ_OBSERVED -> {
                if (isLater(eventTime, asset.getLastAccessedAt())) {
                    asset.setLastAccessedAt(eventTime);
                    asset.setLastAccessSource(truncate(channel));
                    return true;
                }
                return false;
            }
            case EVENT_CATEGORY_DATA_WRITE -> {
                if (isLater(eventTime, asset.getLastModifiedAt())) {
                    asset.setLastModifiedAt(eventTime);
                    return true;
                }
                return false;
            }
            case EVENT_CATEGORY_PERMISSION -> {
                if (isLater(eventTime, asset.getLastPermissionChangeAt())) {
                    asset.setLastPermissionChangeAt(eventTime);
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Keeps the least known creation time of the current incarnation.
     *
     * @param asset   the asset
     * @param created a provider creation time (crawler {@code created_time} or a CREATE's
     *                event time)
     * @param channel the evidence channel
     * @return {@code true} when {@code cloudCreatedAt} moved
     */
    static boolean applyCreatedTime(Asset asset, Instant created, String channel) {
        Objects.requireNonNull(created, "created");
        if (!withinIncarnation(asset, created)) {
            return false;
        }
        if (asset.getCloudCreatedAt() == null || created.isBefore(asset.getCloudCreatedAt())) {
            asset.setCloudCreatedAt(created);
            asset.setCloudCreatedSource(truncate(channel));
            return true;
        }
        return false;
    }

    /**
     * Records a size observation; a newer observation replaces an older one, an older one is
     * ignored. Size never rolls up from objects: callers pass container-level observations only.
     *
     * @param asset      the asset
     * @param sizeBytes  the observed size, {@code >= 0}
     * @param observedAt the business time at which the size was true
     * @return {@code true} when the size changed
     */
    static boolean applySize(Asset asset, long sizeBytes, Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (sizeBytes < 0 || !withinIncarnation(asset, observedAt)) {
            return false;
        }
        if (asset.getSizeObservedAt() == null || !observedAt.isBefore(asset.getSizeObservedAt())) {
            boolean changed = !Long.valueOf(sizeBytes).equals(asset.getSizeBytes())
                    || !observedAt.equals(asset.getSizeObservedAt());
            asset.setSizeBytes(sizeBytes);
            asset.setSizeObservedAt(observedAt);
            return changed;
        }
        return false;
    }

    /**
     * Applies a status transition under the lifecycle ordering rule. DELETE and ARCHIVE set
     * their status and the lifecycle time. A CREATE changes the row only when it is
     * {@code DELETED}, by starting a new incarnation at the CREATE's event time (ADR-0046
     * section 4); on an ACTIVE, ARCHIVED or PENDING_DELETION row it changes neither status
     * nor lifecycle time, because crawlers report every resource they see as a CREATE and an
     * archive or deletion request must survive the next crawl.
     *
     * @param asset      the asset
     * @param transition the transition
     * @param eventTime  the event time
     * @param channel    the evidence channel
     * @return the outcome
     */
    static LifecycleOutcome applyLifecycle(Asset asset, Transition transition, Instant eventTime,
                                           String channel) {
        Objects.requireNonNull(eventTime, "eventTime");
        Instant last = asset.getLifecycleEventTime();
        if ((last != null && eventTime.isBefore(last)) || !withinIncarnation(asset, eventTime)) {
            return LifecycleOutcome.STALE;
        }
        switch (transition) {
            case CREATE -> {
                if (!DlapConstants.AssetStatus.DELETED.equals(asset.getStatus())) {
                    return LifecycleOutcome.UNCHANGED;
                }
                startIncarnation(asset, eventTime, channel);
                return LifecycleOutcome.NEW_INCARNATION;
            }
            case DELETE -> asset.setStatus(DlapConstants.AssetStatus.DELETED);
            case ARCHIVE -> asset.setStatus(DlapConstants.AssetStatus.ARCHIVED);
            default -> throw new IllegalArgumentException("unknown transition " + transition);
        }
        asset.setLifecycleEventTime(eventTime);
        return LifecycleOutcome.APPLIED;
    }

    /**
     * Starts a new incarnation (ADR-0046 section 4): status ACTIVE, {@code recreatedAt},
     * {@code cloudCreatedAt} and {@code lifecycleEventTime} set to the CREATE's event time, and
     * the activity maxima, the size observation and the retention evaluation cleared. The
     * clearing is unconditional, as the contract states: evidence of the new incarnation that
     * was delivered before its CREATE is cleared with the rest (ADR-0055 item 7 records the
     * open proposal to keep it). A legal hold is kept: it is an input, not evaluation output,
     * and keeping it is the safe direction.
     *
     * @param asset     the asset
     * @param eventTime the CREATE's event time, the incarnation start
     * @param channel   the evidence channel of the CREATE
     */
    static void startIncarnation(Asset asset, Instant eventTime, String channel) {
        asset.setStatus(DlapConstants.AssetStatus.ACTIVE);
        asset.setLifecycleEventTime(eventTime);
        asset.setRecreatedAt(eventTime);
        asset.setCloudCreatedAt(eventTime);
        asset.setCloudCreatedSource(truncate(channel));
        asset.setLastAccessedAt(null);
        asset.setLastAccessSource(null);
        asset.setLastModifiedAt(null);
        asset.setLastPermissionChangeAt(null);
        asset.setSizeBytes(null);
        asset.setSizeObservedAt(null);
        asset.setRetentionDays(null);
        asset.setRetentionBasis(null);
        asset.setRetentionSource(null);
        asset.setRetentionPolicyId(null);
        asset.setRetentionExpiresAt(null);
        asset.setRetentionEvaluatedAt(null);
        asset.setRetentionStatus(asset.isLegalHold() ? RetentionStatus.ON_HOLD : RetentionStatus.UNKNOWN);
    }

    /**
     * Cuts an evidence channel to the column width.
     *
     * @param channel the channel, may be {@code null}
     * @return at most {@value #MAX_CHANNEL_LENGTH} characters
     */
    static String truncate(String channel) {
        if (channel == null || channel.length() <= MAX_CHANNEL_LENGTH) {
            return channel;
        }
        return channel.substring(0, MAX_CHANNEL_LENGTH);
    }

    private static boolean isLater(Instant candidate, Instant current) {
        return current == null || candidate.isAfter(current);
    }
}
