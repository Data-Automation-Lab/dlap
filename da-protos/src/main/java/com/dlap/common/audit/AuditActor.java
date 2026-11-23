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

/**
 * Who caused an audited event (docs/specs/audit-log.md, section "Record").
 *
 * <p>The id is a stable, pseudonymous identifier: the JWT {@code sub} of a person, the client id
 * of a service account, or the name of a DLAP component. It is never an e-mail address or a
 * display name. Audit rows cannot be erased, so personal data must not be written into them.
 *
 * @param kind the kind of principal
 * @param id   the principal's stable identifier, 1 to 256 printable characters
 */
public record AuditActor(Kind kind, String id) {

    /** Maximum length of {@link #id()}. */
    public static final int MAX_ID_LENGTH = 256;

    /** The kinds of principal. */
    public enum Kind {
        /** A person, identified by the JWT {@code sub}. */
        USER,
        /** A machine principal, identified by its OAuth client id. */
        SERVICE,
        /** A DLAP component acting on its own schedule (a sweeper, a scheduler). */
        SYSTEM
    }

    /**
     * Validates the actor.
     *
     * @param kind the kind of principal
     * @param id   the identifier
     */
    public AuditActor {
        Objects.requireNonNull(kind, "kind");
        AuditEvent.requireText("actor.id", id, MAX_ID_LENGTH);
    }

    /**
     * A person.
     *
     * @param subject the JWT {@code sub}
     * @return the actor
     */
    public static AuditActor user(String subject) {
        return new AuditActor(Kind.USER, subject);
    }

    /**
     * A service account.
     *
     * @param clientId the OAuth client id
     * @return the actor
     */
    public static AuditActor service(String clientId) {
        return new AuditActor(Kind.SERVICE, clientId);
    }

    /**
     * A DLAP component.
     *
     * @param component the component name, for example {@code da-autodelete.sweeper}
     * @return the actor
     */
    public static AuditActor system(String component) {
        return new AuditActor(Kind.SYSTEM, component);
    }
}
