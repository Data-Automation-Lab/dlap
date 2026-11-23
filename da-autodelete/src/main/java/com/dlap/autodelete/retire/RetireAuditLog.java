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

package com.dlap.autodelete.retire;

import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.AuditTarget;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes the delete executor's records to the {@code retire.delete} stream of the tamper-evident
 * audit log through AUD-01's {@link AuditLogWriter} (docs/specs/audit-log.md).
 *
 * <p>Every executed or refused action leaves a chained record. An action with an external effect
 * (applying a native rule, an engine delete) writes {@link AuditOutcome#REQUESTED} first and its
 * outcome after, under one correlation id (the soft-delete record id); when the intent cannot be
 * written the action does not run. Every append commits on its own connection before it returns,
 * and a failed append throws {@code AuditException}, which callers let propagate so the work is
 * retried.
 *
 * <p>Records carry no personal data: actors are JWT subjects or the service name, targets are
 * canonical asset URIs (passed through {@code AssetIdNormalizer} by {@link AuditTarget#asset}).
 */
public class RetireAuditLog {

    /** Actor of the executor's own work. */
    public static final AuditActor SERVICE = AuditActor.service("da-autodelete");

    /** A delete entered its soft-delete window. */
    public static final String SOFT_DELETED = "retire.delete.soft_deleted";
    /** A person asked for deletes through the task API. */
    public static final String TASK_REQUESTED = "retire.delete.task_requested";
    /** The window ended and the executor asked for a HARD_DELETE approval. */
    public static final String HARD_DELETE_REQUESTED = "retire.delete.hard_delete_requested";
    /** A rule refused an action; nothing was touched. */
    public static final String REFUSED = "retire.delete.refused";
    /** Every rule passed but dry run is on; the plan is recorded, nothing applied. */
    public static final String DRY_RUN = "retire.delete.dry_run";
    /** The native rule or engine delete: REQUESTED before, SUCCEEDED or FAILED after. */
    public static final String APPLY = "retire.delete.apply";
    /** The provider's own log event proved the deletion. */
    public static final String EXECUTED = "retire.delete.executed";
    /** No proof arrived in the expected time. */
    public static final String PROOF_OVERDUE = "retire.delete.proof_overdue";
    /** An operator restored a soft-deleted asset. */
    public static final String RESTORED = "retire.delete.restored";
    /**
     * The executor removed its own bucket, prefix or container rule because the rule's maximum age passed
     * (RET-06, Q-677): REQUESTED before the removal, SUCCEEDED or FAILED after. Not destructive, so no
     * approval; audited like every other effect.
     */
    public static final String RULE_ENDED = "retire.delete.rule_ended";
    /**
     * The executor removed a bucket, prefix or container rule it applied that no provider event ever proved,
     * because the rule's maximum age passed (RET-08, Q-695, decision D38): REQUESTED before the removal,
     * SUCCEEDED or FAILED after. Not destructive, so no approval.
     */
    public static final String RULE_ENDED_UNPROVEN = "retire.delete.rule_ended_unproven";

    private static final int MAX_TEXT = AuditEvent.MAX_DETAIL_STRING_LENGTH;

    private final AuditLogWriter writer;

    /**
     * Creates the log.
     *
     * @param writer the AUD-01 writer, connected to dlap_common as {@code da_autodelete_user}
     */
    public RetireAuditLog(AuditLogWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Appends one record.
     *
     * @param eventType     one of the constants of this class
     * @param occurredAt    the business time of the event
     * @param actor         who acted
     * @param target        the canonical asset URI, or the asset id when there is no URI
     * @param outcome       the outcome
     * @param correlationId the soft-delete record id or the task id
     * @param details       further facts; null values are dropped
     */
    public void record(String eventType, Instant occurredAt, AuditActor actor, String target, AuditOutcome outcome,
            String correlationId, Map<String, ?> details) {
        AuditEvent.Builder b = AuditEvent.builder(eventType)
                .occurredAt(occurredAt)
                .actor(actor)
                .outcome(outcome);
        if (target != null && !target.isBlank()) {
            b.target(AuditTarget.asset(target));
        }
        if (correlationId != null && !correlationId.isBlank()) {
            b.correlationId(correlationId);
        }
        for (Map.Entry<String, Object> e : clean(details).entrySet()) {
            b.detail(e.getKey(), e.getValue());
        }
        writer.append(AuditStreams.RETIRE_DELETE, b.build());
    }

    /**
     * A refusal: {@link AuditOutcome#DENIED} with the machine reason.
     *
     * @param occurredAt    the business time
     * @param actor         who acted
     * @param target        the asset URI or id
     * @param correlationId the record or task id
     * @param stage         {@code soft_delete}, {@code hard_delete} or {@code task}
     * @param reason        the reason
     * @param detail        a sentence, or null
     * @param details       further facts
     */
    public void refused(Instant occurredAt, AuditActor actor, String target, String correlationId, String stage,
            RefusalReason reason, String detail, Map<String, ?> details) {
        Map<String, Object> all = new LinkedHashMap<>(clean(details));
        all.put("stage", stage);
        all.put("reason", reason.code());
        if (detail != null) {
            all.put("detail", detail);
        }
        record(REFUSED, occurredAt, actor, target, AuditOutcome.DENIED, correlationId, all);
    }

    /** Drops nulls, turns UUIDs and enums into text, lists of anything into lists of text, and caps strings. */
    static Map<String, Object> clean(Map<String, ?> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (details == null) {
            return out;
        }
        for (Map.Entry<String, ?> e : details.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
                out.put(e.getKey(), v instanceof Integer i ? Long.valueOf(i) : v);
            } else if (v instanceof Collection<?> c) {
                out.put(e.getKey(), c.stream().filter(Objects::nonNull).map(o -> cap(String.valueOf(o))).toList());
            } else if (v instanceof UUID || v instanceof Enum<?> || v instanceof Instant || v instanceof CharSequence) {
                out.put(e.getKey(), cap(String.valueOf(v)));
            } else {
                out.put(e.getKey(), cap(String.valueOf(v)));
            }
        }
        return out;
    }

    private static String cap(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT);
    }

    /** Longest caller-supplied identifier written as an audit target. */
    private static final int MAX_RAW_TARGET = 512;

    /**
     * A caller-supplied identifier as an audit target: trimmed and capped, so a long or blank value in a
     * refused request can neither fail the append nor flood the log (the audit target accepts 2048
     * characters; the raw text of a refused request is cut well below that).
     *
     * @param raw the identifier as the caller gave it
     * @return the text to pass as the target; {@code unknown} for a blank value
     */
    public static String safeTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String t = raw.trim();
        return t.length() <= MAX_RAW_TARGET ? t : t.substring(0, MAX_RAW_TARGET);
    }

    /**
     * Comma-joined approvers for storage in a row; the audit record keeps them as a list.
     *
     * @param approvers the subjects
     * @return the joined text, or null for none
     */
    public static String join(List<String> approvers) {
        return approvers == null || approvers.isEmpty() ? null : String.join(",", approvers);
    }
}
