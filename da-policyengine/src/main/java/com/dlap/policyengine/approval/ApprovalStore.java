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

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JDBC access to the approval tables of Flyway V7 and V8 ({@code approval_requests},
 * {@code approval_decisions}, {@code approval_outbox}), in UTC.
 *
 * <p>Every value is a bind parameter. The only text spliced into a statement is the schema name,
 * checked once in the constructor against {@link #SQL_IDENTIFIER}; every statement is built there,
 * once, and the methods pass the finished constant to the template. The callers own the
 * transactions ({@link ApprovalService}).
 */
@Repository
public class ApprovalStore {

    /** A schema name: a lower-case SQL identifier. */
    static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private static final String REQUEST_COLUMNS = "id, request_key, origin, action_class, action_type, "
            + "action_parameters::text AS action_parameters, asset_id, asset_uri, provider, service_type, policy_db_id, "
            + "policy_id, policy_name, fire_id, requested_by, requester_kind, submitted_by, excluded_subjects, reason, "
            + "required_approvals, approvals, status, created_at, expires_at, resolved_at, "
            + "action_payload::text AS action_payload, dispatch_status, dispatched_at, withdrawn_by, on_behalf_of, "
            + "withdraw_reason, withdrawn_at";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<ApprovalRequest> requestMapper = this::request;
    private final String insertRequestSql;
    private final String findSql;
    private final String findByKeySql;
    private final String lockSql;
    private final String countApprovalSql;
    private final String resolveSql;
    private final String withdrawSql;
    private final String markDispatchedSql;
    private final String expireSql;
    private final String listSql;
    private final String countSql;
    private final String inboxSql;
    private final String inboxCountSql;
    private final String insertDecisionSql;
    private final String decisionsSql;
    private final String hasDecidedSql;
    private final String insertOutboxSql;
    private final String unsentSql;
    private final String markSentSql;
    private final String markFailedSql;

    /**
     * Creates the store.
     *
     * @param dataSource the service data source
     * @param json       the JSON mapper for the jsonb columns
     * @param schema     the service schema
     */
    public ApprovalStore(DataSource dataSource, ObjectMapper json,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_policyengine}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the approval tables: " + schema);
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.json = json;
        String requests = schema + ".approval_requests";
        String decisions = schema + ".approval_decisions";
        String outbox = schema + ".approval_outbox";
        this.insertRequestSql = "INSERT INTO " + requests + " (id, request_key, origin, action_class, action_type, "
                + "action_parameters, asset_id, asset_uri, provider, service_type, policy_db_id, policy_id, policy_name, "
                + "fire_id, requested_by, requester_kind, submitted_by, excluded_subjects, reason, required_approvals, "
                + "approvals, status, created_at, expires_at, action_payload, dispatch_status) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING', ?, ?, ?::jsonb, "
                + "'NONE') ON CONFLICT DO NOTHING";
        this.findSql = "SELECT " + REQUEST_COLUMNS + " FROM " + requests + " WHERE id = ?";
        this.findByKeySql = "SELECT " + REQUEST_COLUMNS + " FROM " + requests + " WHERE request_key = ?";
        this.lockSql = "SELECT " + REQUEST_COLUMNS + " FROM " + requests + " WHERE id = ? FOR UPDATE";
        this.countApprovalSql = "UPDATE " + requests + " SET approvals = ? WHERE id = ? AND status = 'PENDING'";
        this.resolveSql = "UPDATE " + requests + " SET status = ?, approvals = ?, resolved_at = ?, dispatch_status = ? "
                + "WHERE id = ? AND status = 'PENDING'";
        this.withdrawSql = "UPDATE " + requests + " SET status = 'WITHDRAWN', resolved_at = ?, withdrawn_at = ?, "
                + "withdrawn_by = ?, on_behalf_of = ?, withdraw_reason = ? WHERE id = ? AND status = 'PENDING'";
        this.markDispatchedSql = "UPDATE " + requests + " SET dispatch_status = 'SENT', dispatched_at = ? "
                + "WHERE id = ? AND dispatch_status = 'QUEUED'";
        this.expireSql = "UPDATE " + requests + " SET status = 'EXPIRED', resolved_at = ? WHERE id IN (SELECT id FROM "
                + requests + " WHERE status = 'PENDING' AND expires_at <= ? ORDER BY expires_at, id LIMIT ? "
                + "FOR UPDATE SKIP LOCKED) RETURNING " + REQUEST_COLUMNS;
        String filter = " WHERE (CAST(? AS VARCHAR) IS NULL OR status = ?) "
                + "AND (CAST(? AS VARCHAR) IS NULL OR action_class = ?) AND (CAST(? AS UUID) IS NULL OR asset_id = ?)";
        this.listSql = "SELECT " + REQUEST_COLUMNS + " FROM " + requests + filter
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?";
        this.countSql = "SELECT count(*) FROM " + requests + filter;
        String inbox = " WHERE status = 'PENDING' AND expires_at > ? AND NOT (CAST(? AS TEXT) = ANY (excluded_subjects)) "
                + "AND NOT EXISTS (SELECT 1 FROM " + decisions + " d WHERE d.request_id = r.id AND d.approver_sub = ?)";
        this.inboxSql = "SELECT " + REQUEST_COLUMNS + " FROM " + requests + " r" + inbox
                + " ORDER BY expires_at, id LIMIT ? OFFSET ?";
        this.inboxCountSql = "SELECT count(*) FROM " + requests + " r" + inbox;
        this.insertDecisionSql = "INSERT INTO " + decisions + " (id, request_id, approver_sub, approver_name, "
                + "approver_roles, decision, comment, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        this.decisionsSql = "SELECT id, request_id, approver_sub, approver_name, approver_roles, decision, comment, "
                + "decided_at FROM " + decisions + " WHERE request_id = ? ORDER BY seq";
        this.hasDecidedSql = "SELECT count(*) FROM " + decisions + " WHERE request_id = ? AND approver_sub = ?";
        this.insertOutboxSql = "INSERT INTO " + outbox + " (id, request_id, kind, topic, msg_key, payload, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        this.unsentSql = "SELECT id, request_id, kind, topic, msg_key, payload, created_at FROM " + outbox
                + " WHERE sent_at IS NULL ORDER BY seq LIMIT ?";
        this.markSentSql = "UPDATE " + outbox + " SET sent_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ?";
        this.markFailedSql = "UPDATE " + outbox + " SET attempts = attempts + 1, last_error = ? WHERE id = ?";
    }

    /**
     * One message waiting in, or sent from, the outbox.
     *
     * @param id        the message id
     * @param requestId the request it belongs to
     * @param kind      {@code REQUESTED}, {@code DECIDED}, {@code BREAK_GLASS} or {@code ACTION}
     * @param topic     the Kafka topic
     * @param key       the record key
     * @param payload   the JSON value
     * @param createdAt when it was written
     */
    public record OutboxMessage(UUID id, UUID requestId, String kind, String topic, String key, String payload,
            Instant createdAt) {
    }

    /**
     * A page of requests.
     *
     * @param rows  the rows of the page
     * @param total all rows that match
     */
    public record Slice(List<ApprovalRequest> rows, long total) {

        /**
         * Copies the rows.
         */
        public Slice {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Inserts a pending request unless one with the same key, or an open request for the same policy,
     * asset and action, exists.
     *
     * @param r the request; {@code status} and {@code approvals} are ignored (PENDING, 0)
     * @return true when the row was inserted
     */
    public boolean insertRequest(ApprovalRequest r) {
        int rows = jdbc.update(con -> {
            var ps = con.prepareStatement(insertRequestSql);
            int i = 1;
            ps.setObject(i++, r.id());
            ps.setString(i++, r.requestKey());
            ps.setString(i++, r.origin());
            ps.setString(i++, r.actionClass().name());
            ps.setString(i++, r.actionType());
            ps.setString(i++, write(r.actionParameters()));
            ps.setObject(i++, r.assetId());
            ps.setString(i++, r.assetUri());
            ps.setString(i++, r.provider());
            ps.setString(i++, r.serviceType());
            ps.setObject(i++, r.policyDbId());
            ps.setString(i++, r.policyId());
            ps.setString(i++, r.policyName());
            ps.setString(i++, r.fireId());
            ps.setString(i++, r.requestedBy());
            ps.setString(i++, r.requesterKind());
            ps.setString(i++, r.submittedBy());
            ps.setArray(i++, con.createArrayOf("text", r.excludedSubjects().toArray()));
            ps.setString(i++, r.reason());
            ps.setInt(i++, r.requiredApprovals());
            ps.setObject(i++, utc(r.createdAt()));
            ps.setObject(i++, utc(r.expiresAt()));
            ps.setString(i, r.actionPayload());
            return ps;
        });
        return rows == 1;
    }

    /**
     * Reads a request.
     *
     * @param id the request id
     * @return the request, or empty
     */
    public Optional<ApprovalRequest> find(UUID id) {
        return jdbc.query(findSql, requestMapper, id).stream().findFirst();
    }

    /**
     * Reads a request by its idempotency key.
     *
     * @param requestKey the key
     * @return the request, or empty
     */
    public Optional<ApprovalRequest> findByKey(String requestKey) {
        return jdbc.query(findByKeySql, requestMapper, requestKey).stream().findFirst();
    }

    /**
     * Reads a request and locks its row until the transaction ends, so two deciders queue.
     *
     * @param id the request id
     * @return the request, or empty
     */
    public Optional<ApprovalRequest> lock(UUID id) {
        return jdbc.query(lockSql, requestMapper, id).stream().findFirst();
    }

    /**
     * Records the approval count of a request that stays pending.
     *
     * @param id        the request id
     * @param approvals the new count
     */
    public void countApproval(UUID id, int approvals) {
        jdbc.update(countApprovalSql, approvals, id);
    }

    /**
     * Moves a pending request to a terminal state.
     *
     * @param id             the request id
     * @param status         the terminal state
     * @param approvals      the final approval count
     * @param resolvedAt     when
     * @param dispatchStatus the dispatch state that follows
     * @return true when the row was pending and moved
     */
    public boolean resolve(UUID id, ApprovalStatus status, int approvals, Instant resolvedAt, String dispatchStatus) {
        return jdbc.update(resolveSql, status.name(), approvals, utc(resolvedAt), dispatchStatus, id) == 1;
    }

    /**
     * Moves a pending request to {@link ApprovalStatus#WITHDRAWN} and records who withdrew it, for
     * whom and why. {@code resolved_at} and {@code withdrawn_at} carry the same instant.
     *
     * @param id          the request id
     * @param withdrawnAt when, by the business clock
     * @param withdrawnBy the service principal
     * @param onBehalfOf  the person it acted for
     * @param reason      why
     * @return true when the row was pending and moved
     */
    public boolean withdraw(UUID id, Instant withdrawnAt, String withdrawnBy, String onBehalfOf, String reason) {
        return jdbc.update(withdrawSql, utc(withdrawnAt), utc(withdrawnAt), withdrawnBy, onBehalfOf, reason, id) == 1;
    }

    /**
     * Records that the broker acknowledged a request's released action.
     *
     * @param requestId the request id
     * @param at        when
     */
    public void markDispatched(UUID requestId, Instant at) {
        jdbc.update(markDispatchedSql, utc(at), requestId);
    }

    /**
     * Expires pending requests whose time is up, oldest first.
     *
     * @param now   the business instant
     * @param limit most rows to expire
     * @return the expired requests, as they are now
     */
    public List<ApprovalRequest> expireOverdue(Instant now, int limit) {
        return jdbc.query(expireSql, requestMapper, utc(now), utc(now), limit);
    }

    /**
     * Pages the requests, newest first.
     *
     * @param status      only this state; null for every state
     * @param actionClass only this class; null for every class
     * @param assetId     only this asset; null for every asset
     * @param limit       page size
     * @param offset      rows to skip
     * @return the page
     */
    public Slice list(ApprovalStatus status, ActionClass actionClass, UUID assetId, int limit, long offset) {
        String s = status == null ? null : status.name();
        String c = actionClass == null ? null : actionClass.name();
        List<ApprovalRequest> rows = jdbc.query(listSql, requestMapper, s, s, c, c, assetId, assetId, limit, offset);
        Long total = jdbc.queryForObject(countSql, Long.class, s, s, c, c, assetId, assetId);
        return new Slice(rows, total == null ? 0 : total);
    }

    /**
     * Pages the pending requests one subject may still decide, soonest expiry first: not expired,
     * not excluded for the subject, and not decided by it yet.
     *
     * @param subject the caller's JWT {@code sub}
     * @param now     the business instant
     * @param limit   page size
     * @param offset  rows to skip
     * @return the page
     */
    public Slice inbox(String subject, Instant now, int limit, long offset) {
        List<ApprovalRequest> rows = jdbc.query(inboxSql, requestMapper, utc(now), subject, subject, limit, offset);
        Long total = jdbc.queryForObject(inboxCountSql, Long.class, utc(now), subject, subject);
        return new Slice(rows, total == null ? 0 : total);
    }

    /**
     * Inserts a decision. A second decision by the same subject on the same request violates
     * {@code uq_approval_decisions_person}.
     *
     * @param d the decision
     */
    public void insertDecision(ApprovalDecision d) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(insertDecisionSql);
            ps.setObject(1, d.id());
            ps.setObject(2, d.requestId());
            ps.setString(3, d.approverSub());
            ps.setString(4, d.approverName());
            ps.setArray(5, con.createArrayOf("text", d.approverRoles().toArray()));
            ps.setString(6, d.decision().name());
            ps.setString(7, d.comment());
            ps.setObject(8, utc(d.decidedAt()));
            return ps;
        });
    }

    /**
     * The decisions of a request, in the order they were recorded.
     *
     * @param requestId the request id
     * @return the decisions
     */
    public List<ApprovalDecision> decisions(UUID requestId) {
        return jdbc.query(decisionsSql, (rs, i) -> new ApprovalDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("approver_sub"),
                rs.getString("approver_name"),
                texts(rs.getArray("approver_roles")),
                DecisionKind.valueOf(rs.getString("decision")),
                rs.getString("comment"),
                instant(rs, "decided_at")), requestId);
    }

    /**
     * Whether a subject has decided a request.
     *
     * @param requestId the request id
     * @param subject   the JWT {@code sub}
     * @return true when a decision row exists
     */
    public boolean hasDecided(UUID requestId, String subject) {
        Long n = jdbc.queryForObject(hasDecidedSql, Long.class, requestId, subject);
        return n != null && n > 0;
    }

    /**
     * Writes an outbox message.
     *
     * @param m the message
     */
    public void insertOutbox(OutboxMessage m) {
        jdbc.update(insertOutboxSql, m.id(), m.requestId(), m.kind(), m.topic(), m.key(), m.payload(),
                utc(m.createdAt()));
    }

    /**
     * The oldest messages not yet acknowledged by the broker, in the order they were written.
     *
     * @param limit most messages
     * @return the messages, oldest first
     */
    public List<OutboxMessage> unsent(int limit) {
        return jdbc.query(unsentSql, (rs, i) -> new OutboxMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("kind"),
                rs.getString("topic"),
                rs.getString("msg_key"),
                rs.getString("payload"),
                instant(rs, "created_at")), limit);
    }

    /**
     * Records that the broker acknowledged a message.
     *
     * @param id the message id
     * @param at when
     */
    public void markSent(UUID id, Instant at) {
        jdbc.update(markSentSql, utc(at), id);
    }

    /**
     * Records a failed send; the message stays in the outbox.
     *
     * @param id    the message id
     * @param error a short reason, without message content
     */
    public void markFailed(UUID id, String error) {
        String e = error == null ? "unknown" : error;
        jdbc.update(markFailedSql, e.length() > 255 ? e.substring(0, 255) : e, id);
    }

    private ApprovalRequest request(ResultSet rs, int row) throws SQLException {
        return new ApprovalRequest(
                rs.getObject("id", UUID.class),
                rs.getString("request_key"),
                rs.getString("origin"),
                ActionClass.valueOf(rs.getString("action_class")),
                rs.getString("action_type"),
                read(rs.getString("action_parameters")),
                rs.getObject("asset_id", UUID.class),
                rs.getString("asset_uri"),
                rs.getString("provider"),
                rs.getString("service_type"),
                rs.getObject("policy_db_id", UUID.class),
                rs.getString("policy_id"),
                rs.getString("policy_name"),
                rs.getString("fire_id"),
                rs.getString("requested_by"),
                rs.getString("requester_kind"),
                rs.getString("submitted_by"),
                texts(rs.getArray("excluded_subjects")),
                rs.getString("reason"),
                rs.getInt("required_approvals"),
                rs.getInt("approvals"),
                ApprovalStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "resolved_at"),
                rs.getString("action_payload"),
                rs.getString("dispatch_status"),
                instant(rs, "dispatched_at"),
                rs.getString("withdrawn_by"),
                rs.getString("on_behalf_of"),
                rs.getString("withdraw_reason"),
                instant(rs, "withdrawn_at"));
    }

    private static List<String> texts(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("action parameters are not JSON-serialisable", e);
        }
    }

    private Map<String, Object> read(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("approval_requests.action_parameters is not a JSON object", e);
        }
    }
}
