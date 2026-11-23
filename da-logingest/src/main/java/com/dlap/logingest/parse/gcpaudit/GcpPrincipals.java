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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.proto.log.v1.PrincipalType;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The actor of a GCP audit entry (GCP report section 7, actor order): {@code principalEmail},
 * then {@code principalSubject}, then the first {@code serviceAccountDelegationInfo} entry; the
 * mapping's {@code principal.id} fallback list encodes the order and a binding's
 * {@code field-overrides} come first.
 *
 * <p>Actor classes, none of them a parse error:
 * <ul>
 *   <li>a service account ({@code ...gserviceaccount.com}, {@code serviceAccount:...}) is
 *       {@code SERVICE_ACCOUNT};</li>
 *   <li>{@code google-internal}, which some google.com callers become, is {@code SYSTEM};</li>
 *   <li>a masked or redacted email (no {@code @}, or a local part of mask characters such as
 *       {@code ...} or {@code ***}) keeps its text as the display name, with no id and no type,
 *       because it identifies nobody;</li>
 *   <li>any other email or {@code principal://} subject is {@code USER}; {@code group:} is
 *       {@code GROUP}.</li>
 * </ul>
 *
 * <p>An entry of the System Event log ({@code cloudaudit.googleapis.com%2Fsystem_event}) is written
 * for an action Google systems took, not a caller (GCP report section 7): its actor is
 * {@code SYSTEM} when the entry names no caller at all. BigQuery table expiration
 * ({@code InternalTableExpired}) is such an entry, and the Lifecycle State emitter reads a
 * {@code SYSTEM} actor as the provider's own native rule (PARSE-02, Q-596).
 */
final class GcpPrincipals {

    /** The value Google writes for some of its own callers. */
    static final String GOOGLE_INTERNAL = "google-internal";

    /** {@code logName} suffix of the System Event audit log. */
    static final String SYSTEM_EVENT_LOG_SUFFIX = "cloudaudit.googleapis.com%2Fsystem_event";

    private static final Pattern MASK = Pattern.compile("[.*#?]+");

    private GcpPrincipals() {
    }

    /**
     * Classifies an actor.
     *
     * @param raw the actor text from the entry, or {@code ""}
     * @return the actor; {@link CanonicalEvent.Actor#UNKNOWN} when there is none
     */
    static CanonicalEvent.Actor of(String raw) {
        String id = raw == null ? "" : raw.strip();
        if (id.isEmpty()) {
            return CanonicalEvent.Actor.UNKNOWN;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (GOOGLE_INTERNAL.equals(lower)) {
            return new CanonicalEvent.Actor(PrincipalType.SYSTEM, id, "", "");
        }
        if (masked(id)) {
            return new CanonicalEvent.Actor(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED, "", id, "");
        }
        if (lower.startsWith("serviceaccount:") || lower.endsWith(".gserviceaccount.com")
                || lower.contains("gserviceaccount.com/")) {
            return new CanonicalEvent.Actor(PrincipalType.SERVICE_ACCOUNT, id, "", "");
        }
        if (lower.startsWith("group:")) {
            return new CanonicalEvent.Actor(PrincipalType.GROUP, id, "", "");
        }
        return new CanonicalEvent.Actor(PrincipalType.USER, id, "", "");
    }

    /**
     * Classifies the actor of an entry, with the System Event rule.
     *
     * @param raw     the actor text from the entry, or {@code ""}
     * @param logName the entry's {@code logName}, or {@code ""}
     * @return the actor; {@code SYSTEM} for a System Event entry that names no caller
     */
    static CanonicalEvent.Actor of(String raw, String logName) {
        CanonicalEvent.Actor actor = of(raw);
        if (CanonicalEvent.Actor.UNKNOWN.equals(actor) && logName != null && logName.endsWith(SYSTEM_EVENT_LOG_SUFFIX)) {
            return new CanonicalEvent.Actor(PrincipalType.SYSTEM, "", "", "");
        }
        return actor;
    }

    /**
     * Whether an actor text is masked or redacted.
     *
     * @param id the text
     * @return true when it names nobody
     */
    static boolean masked(String id) {
        if (id.startsWith("principal://") || id.startsWith("principalSet://") || id.contains(":")) {
            return false;
        }
        int at = id.indexOf('@');
        if (at < 0) {
            return true;
        }
        String local = id.substring(0, at);
        return local.isEmpty() || MASK.matcher(local).matches();
    }
}
