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

import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Version-qualified deletes (D36, Q-651). A delete that names an object version, or one that only
 * adds a delete marker, removes a version of an object while the object itself (its current
 * version, or its noncurrent versions behind the marker) still exists. Such an event is never an
 * asset {@code DELETE}: it becomes an {@code UPDATE} of the same canonical target in category
 * {@code MANAGEMENT}, so it neither marks the asset deleted nor moves its last write, and the target's
 * {@code technical_metadata} carries what happened:
 *
 * <table>
 *   <caption>Target technical metadata of a version-qualified delete</caption>
 *   <tr><th>Key</th><th>Value</th></tr>
 *   <tr><td>{@value #ACTION_KEY}</td><td>{@value #DELETE_VERSION} (a version was removed) or
 *       {@value #CREATE_DELETE_MARKER} (a delete marker was added)</td></tr>
 *   <tr><td>{@value #VERSION_ID_KEY}</td><td>the version id the record names (the removed version,
 *       or the new delete marker's id); absent when the record names none</td></tr>
 * </table>
 *
 * Only a delete of the current object that names no version stays {@code DELETE}. Provider neutral:
 * the families decide from their own fields which records are version-qualified.
 */
public final class VersionedDeletes {

    /** {@code technical_metadata} key of the version action. */
    public static final String ACTION_KEY = "versionAction.kind";

    /** {@code technical_metadata} key of the version id the record names. */
    public static final String VERSION_ID_KEY = "versionAction.versionId";

    /** A delete that named one version and removed it. */
    public static final String DELETE_VERSION = "DELETE_VERSION";

    /** A delete that added a delete marker in front of the current version. */
    public static final String CREATE_DELETE_MARKER = "CREATE_DELETE_MARKER";

    private VersionedDeletes() {
    }

    /**
     * Rewrites a version-qualified delete into an update of the same target. Any other event is
     * returned unchanged.
     *
     * @param event     the parsed event
     * @param action    {@link #DELETE_VERSION} or {@link #CREATE_DELETE_MARKER}
     * @param versionId the version id the record names, if any
     * @return the event as an {@code UPDATE} carrying the version action, or {@code event} when it is
     *         not a {@code DELETE}
     */
    public static CanonicalEvent asVersionUpdate(CanonicalEvent event, String action, Optional<String> versionId) {
        if (event.changeType() != ChangeType.DELETE) {
            return event;
        }
        return event.toBuilder()
                .changeType(ChangeType.UPDATE)
                .category(EventCategory.EVENT_CATEGORY_MANAGEMENT)
                .target(withAction(event.target(), action, versionId))
                .build();
    }

    /**
     * Adds the version action to a target's technical metadata.
     *
     * @param target    the target
     * @param action    {@link #DELETE_VERSION} or {@link #CREATE_DELETE_MARKER}
     * @param versionId the version id the record names, if any
     * @return the target with the two keys set
     */
    public static CanonicalEvent.Resource withAction(CanonicalEvent.Resource target, String action,
                                                     Optional<String> versionId) {
        Map<String, String> metadata = new LinkedHashMap<>(target.technicalMetadata());
        metadata.put(ACTION_KEY, action);
        versionId.ifPresent(v -> metadata.put(VERSION_ID_KEY, v));
        return new CanonicalEvent.Resource(target.kind(), target.id(), target.name(), target.project(), target.region(),
                target.resourcePath(), target.labels(), target.canonicalUri(), target.createdTime(), target.sizeBytes(),
                metadata, target.relation());
    }
}
