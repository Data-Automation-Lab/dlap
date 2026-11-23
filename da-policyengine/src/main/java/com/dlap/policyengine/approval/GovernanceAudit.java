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

package com.dlap.policyengine.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.stereotype.Component;

import com.dlap.common.audit.AuditChain;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The policy engine's access to the tamper-evident audit log for its two streams,
 * {@code govern.approval} and {@code govern.policy} (docs/specs/audit-log.md; both written by
 * {@code da_policyengine_user}).
 *
 * <p>Every governance change follows one pattern, so that a change and its audit entry either both
 * exist or the change does not:
 * <ol>
 *   <li>{@link #prior} reads the entry the stream already holds under the change's idempotency key.
 *       There is one only when an earlier attempt appended it and then failed to commit.</li>
 *   <li>The change is built at the prior entry's {@code occurredAt} when there is one, else at the
 *       clock's time, so a retry commits with the time the chain already states (Q-692).</li>
 *   <li>Last thing in the change's transaction, {@link #record} appends the entry, or replays the
 *       prior one when it records the same change. Any failure throws
 *       {@link AuditUnavailableException} and the transaction rolls back (fail closed, Q-687).</li>
 * </ol>
 *
 * <p>The writer and the lookup are resolved on first use, so the service starts without the audit
 * database; the first change then fails closed.
 */
@Component
public class GovernanceAudit {

    /** The members every replay compares, as JSON pointers into the record. */
    static final List<String> ALWAYS_COMPARED =
            List.of("/eventType", "/occurredAt", "/actor", "/target", "/correlationId", "/idempotencyKey");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<AuditLogWriter> writer;
    private final ObjectProvider<AuditKeyLookup> lookup;

    /**
     * Creates the audit access.
     *
     * @param writer the audit writer, resolved on first use
     * @param lookup the reader of stored entries by idempotency key, resolved on first use; without one
     *               a retry is not replayed, and only an exact repeat passes the writer's own check
     */
    @Autowired
    public GovernanceAudit(ObjectProvider<AuditLogWriter> writer, ObjectProvider<AuditKeyLookup> lookup) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * Creates the audit access with a writer and no lookup.
     *
     * @param writer the audit writer, resolved on first use
     */
    public GovernanceAudit(ObjectProvider<AuditLogWriter> writer) {
        this(writer, new StaticListableBeanFactory().getBeanProvider(AuditKeyLookup.class));
    }

    /**
     * The entry a stream already holds under a key: what an earlier, uncommitted attempt wrote.
     *
     * @param streamId the stream
     * @param key      the idempotency key of the change
     * @return the stored entry, or empty when there is none or no lookup is configured
     * @throws AuditUnavailableException when the audit database cannot be read
     */
    public Optional<AuditEntry> prior(String streamId, String key) {
        AuditKeyLookup l = lookup.getIfAvailable();
        if (l == null) {
            return Optional.empty();
        }
        try {
            return l.find(streamId, key);
        } catch (AuditException | IllegalArgumentException e) {
            throw new AuditUnavailableException("cannot read audit stream " + streamId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Appends an event, or replays the prior entry when it records the same change.
     *
     * @param streamId the stream
     * @param event    the event, built at {@link #occurredAt} of {@code prior} when there is one
     * @param prior    the result of {@link #prior} for the event's key
     * @param compared further members, as JSON pointers (for example {@code /details/reasonSha256}),
     *                 that must be equal for a replay; the members of {@link #ALWAYS_COMPARED} always are
     * @return the stored entry
     * @throws AuditUnavailableException when nothing is configured, the append fails, or the prior entry
     *                                   records another change
     */
    public AuditEntry record(String streamId, AuditEvent event, Optional<AuditEntry> prior, List<String> compared) {
        Objects.requireNonNull(event, "event");
        if (prior != null && prior.isPresent()) {
            AuditEntry stored = prior.get();
            List<String> differ = differences(stored, event, compared);
            if (!differ.isEmpty()) {
                throw new AuditUnavailableException("audit stream " + streamId + " already holds entry "
                        + stored.seq() + " under key " + event.idempotencyKey() + " for another change (" + differ
                        + "); retry with the original change", null);
            }
            return stored;
        }
        AuditLogWriter w = writer.getIfAvailable();
        if (w == null) {
            throw new AuditUnavailableException("no audit writer is configured for stream " + streamId, null);
        }
        try {
            return w.append(streamId, event);
        } catch (AuditException | IllegalArgumentException e) {
            throw new AuditUnavailableException("append to audit stream " + streamId + " failed: " + e.getMessage(),
                    e);
        }
    }

    /**
     * The {@code occurredAt} of a stored entry.
     *
     * @param entry the entry
     * @return the instant
     * @throws AuditUnavailableException when the stored record has none
     */
    public static Instant occurredAt(AuditEntry entry) {
        try {
            return Instant.parse(JSON.readTree(entry.record()).path("occurredAt").asText());
        } catch (JsonProcessingException | RuntimeException e) {
            throw new AuditUnavailableException("audit entry " + entry.seq() + " of " + entry.streamId()
                    + " has no readable occurredAt", e);
        }
    }

    /**
     * The instant a change commits with: the prior entry's, or {@code now}.
     *
     * @param prior the prior entry, if any
     * @param now   the clock's time
     * @return the business time of the change
     */
    public static Instant timeOf(Optional<AuditEntry> prior, Instant now) {
        return prior.map(GovernanceAudit::occurredAt).orElse(now);
    }

    /**
     * Hex SHA-256 of a text, UTF-8.
     *
     * @param text the text
     * @return 64 lower-case hex characters
     */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }

    private static List<String> differences(AuditEntry stored, AuditEvent event, List<String> compared) {
        JsonNode have;
        JsonNode want;
        try {
            have = JSON.readTree(stored.record());
            want = JSON.readTree(AuditChain.record(event, event.occurredAt()));
        } catch (JsonProcessingException e) {
            throw new AuditUnavailableException("audit entry " + stored.seq() + " of " + stored.streamId()
                    + " is not readable JSON", e);
        }
        List<String> pointers = new ArrayList<>(ALWAYS_COMPARED);
        if (compared != null) {
            pointers.addAll(compared);
        }
        List<String> differ = new ArrayList<>();
        for (String pointer : pointers) {
            if (!have.at(pointer).equals(want.at(pointer))) {
                differ.add(pointer);
            }
        }
        return differ;
    }
}
