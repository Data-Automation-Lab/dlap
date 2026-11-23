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

package com.dlap.autoarchival.proof;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.executor.ArchiveTaskSync;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RuleScope;
import com.dlap.common.audit.AuditException;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.google.protobuf.Timestamp;

/**
 * The log proof: marks an archive {@code EXECUTED} only from the provider's own event.
 *
 * <p>DLAP writing a lifecycle rule, or the provider answering "200 OK" to that write, says the
 * rule exists. It does not say any object moved. A row therefore stays {@code APPLIED} (or
 * {@code RENDERED}) until a normalized provider log event (topic {@code dlap.log.event.normalized})
 * shows a successful transition, and the event's id, operation and time are stored on the row and
 * in the audit log. Nothing else sets {@code EXECUTED}: not the executor, not an API call, not a
 * task status field.
 *
 * <h3>What counts as the provider event</h3>
 * An event proves a row when all of these hold:
 * <ol>
 *   <li>its result is {@code SUCCESS};</li>
 *   <li>it comes from the provider's log, not from a replay of stored events (fixtures, demo
 *       seeding) unless {@code dlap.autoarchival.rules.accept-replay-evidence=true}, which the tests
 *       use; a crawler observation counts only as state evidence (below);</li>
 *   <li>its target is an object (not the bucket) inside the row's scope: under the prefix for a
 *       rule, exactly the key for a direct change, in the same provider and bucket;</li>
 *   <li>the row was really applied (a mocked apply, used in development without credentials, wrote nothing
 *       at the provider, so no event can be about it);</li>
 *   <li>its time is not before the row's apply began (the provider's clock, never ingest time);</li>
 *   <li>its operation is one of the provider's transition operations for the row's mode
 *       (below), or a crawler observation of the object's storage class;</li>
 *   <li>when the event states the object's resulting class, the class equals the row's class.</li>
 * </ol>
 *
 * <table>
 *   <caption>Transition operations</caption>
 *   <tr><th>Provider</th><th>Rule (lifecycle)</th><th>Direct change</th></tr>
 *   <tr><td>AWS</td><td>{@code LifecycleTransition} (CloudTrail, invoked by
 *       {@code lifecycle.s3.amazonaws.com}), {@code S3.TRANSITION_*.OBJECT} (server access log)</td>
 *       <td>{@code CopyObject}</td></tr>
 *   <tr><td>GCP</td><td>none: Cloud Audit Logs do not record lifecycle actions, so only a crawler
 *       observation of the object's class proves it</td>
 *       <td>{@code storage.objects.update}, {@code storage.objects.rewrite},
 *       {@code storage.objects.copy}</td></tr>
 *   <tr><td>AZURE</td><td>{@code SetBlobTier}</td><td>{@code SetBlobTier}</td></tr>
 * </table>
 * The operation names are taken from provider documentation and are not yet observed against a
 * live account (open question Q-600); the fixture-based tests prove the matching logic,
 * not the providers' spelling.
 */
@Service
public class ArchiveProofService {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveProofService.class);

    /** Evidence kind: a provider log record of the transition operation. */
    public static final String KIND_PROVIDER_LOG = "PROVIDER_LOG";
    /** Evidence kind: a provider-state observation of the object's storage class. */
    public static final String KIND_STATE_OBSERVATION = "STATE_OBSERVATION";

    private static final Set<String> WAITING = Set.of(ArchiveRuleEntity.STATUS_APPLIED,
            ArchiveRuleEntity.STATUS_RENDERED);

    /** The CloudTrail operation of a lifecycle transition (the principal is the S3 lifecycle service). */
    private static final String AWS_CLOUDTRAIL_TRANSITION = "LifecycleTransition";
    private static final Set<String> AWS_DIRECT_OPS = Set.of("copyobject");
    private static final Set<String> GCP_DIRECT_OPS = Set.of("storage.objects.update", "storage.objects.rewrite",
            "storage.objects.copy");

    /**
     * S3 server access log lifecycle operations and the storage class each one means (Q-644).
     *
     * <p>Source: AWS documentation, "How S3 Lifecycle interacts with other bucket configurations",
     * section "S3 Lifecycle and logging",
     * https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html,
     * retrieved 2026-11-01. The page lists exactly these six transition operations; there is no
     * {@code S3.TRANSITION_DAA.OBJECT}, and Glacier Flexible Retrieval is the code-less
     * {@code S3.TRANSITION.OBJECT}. Keys are upper case; the lookup upper-cases the event's operation.
     */
    static final Map<String, String> AWS_ACCESS_LOG_TRANSITIONS = Map.of(
            "S3.TRANSITION_SIA.OBJECT", "STANDARD_IA",
            "S3.TRANSITION_ZIA.OBJECT", "ONEZONE_IA",
            "S3.TRANSITION_INT.OBJECT", "INTELLIGENT_TIERING",
            "S3.TRANSITION_GIR.OBJECT", "GLACIER_IR",
            "S3.TRANSITION.OBJECT", "GLACIER",
            "S3.TRANSITION_GDA.OBJECT", "DEEP_ARCHIVE");

    private static final List<String> CLASS_KEYS = List.of("storageClass", "storage_class", "accessTier",
            "access_tier");
    /** When the object's class last changed, as a state observation reports it. */
    private static final List<String> CHANGED_KEYS = List.of("timeStorageClassUpdated", "storageClassUpdated",
            "accessTierChangeTime");

    private final ArchiveRuleRepository rules;
    private final ArchiveTaskSync sync;
    private final ArchiveAudit audit;
    private final Clock clock;
    private final ArchiveRuleProperties properties;

    /**
     * Creates the service.
     *
     * @param rules      the rule rows
     * @param sync       recomputes tasks
     * @param audit      the audit log
     * @param clock      the injected clock
     * @param properties the evidence switches
     */
    public ArchiveProofService(ArchiveRuleRepository rules, ArchiveTaskSync sync, ArchiveAudit audit, Clock clock,
            ArchiveRuleProperties properties) {
        this.rules = rules;
        this.sync = sync;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Looks at one normalized log event and marks every row it proves.
     *
     * @param event the event
     * @return how many rows the event marked {@code EXECUTED}
     */
    public int onEvent(DlapLogEvent event) {
        if (event.getResultStatus() != ResultStatus.SUCCESS || !relevantOrigin(event.getOrigin())) {
            return 0;
        }
        String provider = providerName(event.getSourceProvider());
        if (provider == null || !event.hasEventTime()) {
            return 0;
        }
        String uri = event.getTargetResource().getCanonicalUri().isBlank()
                ? event.getTargetResource().getId() : event.getTargetResource().getCanonicalUri();
        RuleScope target;
        try {
            target = RuleScope.parse(provider, uri, null, 4096);
        } catch (RuleException e) {
            return 0;
        }
        if (target.prefix().isEmpty()) {
            return 0;
        }
        Instant eventTime = instant(event.getEventTime());
        int marked = 0;
        for (ArchiveRuleEntity row : rules.findByProviderAndScopeBucketAndStatusIn(provider, target.bucket(), WAITING)) {
            if (row.isMock()) {
                continue; // a mocked apply wrote nothing, so no provider event is about this rule
            }
            String kind = evidenceKind(row, event, target, eventTime);
            if (kind == null) {
                continue;
            }
            Instant now = clock.instant();
            // Q-674: a conditional update on the status loaded above. A restore that removed the row meanwhile
            // wins, and this proof then changes nothing (no state, no audit record, no task sync).
            if (rules.markExecutedIf(row.getRuleId(), row.getStatus(), event.getEventId(), event.getOperation(),
                    eventTime, kind, now) != 1) {
                LOG.info("archive {} changed while event {} was matched (it is no longer {}); the proof is not recorded",
                        row.getRuleId(), event.getEventId(), row.getStatus());
                continue;
            }
            row.markExecuted(event.getEventId(), event.getOperation(), eventTime, kind, now);
            try {
                audit.executed(row, null);
            } catch (AuditException | IllegalArgumentException e) {
                LOG.error("archive {} proven by event {} but the audit record could not be written: {}",
                        row.getRuleId(), event.getEventId(), e.getMessage());
            }
            sync.sync(row.getTaskId());
            marked++;
            LOG.info("archive {} executed: {} on {} (event {})", row.getRuleId(), event.getOperation(), uri,
                    event.getEventId());
        }
        return marked;
    }

    /**
     * Decides whether an event proves a row.
     *
     * @return the evidence kind, or {@code null} when the event proves nothing about the row
     */
    private String evidenceKind(ArchiveRuleEntity row, DlapLogEvent event, RuleScope target, Instant eventTime) {
        boolean inScope = ArchiveRuleEntity.MODE_DIRECT_OBJECT.equals(row.getMode())
                ? target.prefix().equals(row.getScopePrefix())
                : target.prefix().startsWith(row.getScopePrefix());
        if (!inScope || row.getAppliedAt() == null || eventTime.isBefore(row.getAppliedAt())) {
            return null;
        }
        String reported = reportedClass(event);
        if (event.getOrigin() == Origin.ORIGIN_CRAWLER) {
            // A state observation proves a transition only when it says WHEN the class changed and that
            // was after the rule began; an object that already sat in the class proves nothing.
            Instant changed = changedAt(event);
            return reported != null && reported.equalsIgnoreCase(row.getNativeClass()) && changed != null
                    && !changed.isBefore(row.getAppliedAt()) ? KIND_STATE_OBSERVATION : null;
        }
        if (!isTransitionOperation(row, event.getOperation())) {
            return null;
        }
        String codeClass = awsCodeClass(event.getOperation());
        if (codeClass != null && !codeClass.equalsIgnoreCase(row.getNativeClass())) {
            return null;
        }
        if (reported != null && !reported.equalsIgnoreCase(row.getNativeClass())) {
            return null;
        }
        return KIND_PROVIDER_LOG;
    }

    private boolean relevantOrigin(Origin origin) {
        return switch (origin) {
            case ORIGIN_CLOUD_LOG, ORIGIN_BACKFILL, ORIGIN_UNSPECIFIED, ORIGIN_CRAWLER -> true;
            case ORIGIN_REPLAY -> properties.isAcceptReplayEvidence();
            default -> false;
        };
    }

    private static boolean isTransitionOperation(ArchiveRuleEntity row, String operation) {
        if (operation == null || operation.isBlank()) {
            return false;
        }
        String op = operation.strip();
        boolean direct = ArchiveRuleEntity.MODE_DIRECT_OBJECT.equals(row.getMode());
        return switch (row.getProvider()) {
            case "AWS" -> direct ? AWS_DIRECT_OPS.contains(op.toLowerCase(Locale.ROOT))
                    : AWS_CLOUDTRAIL_TRANSITION.equalsIgnoreCase(op)
                    || AWS_ACCESS_LOG_TRANSITIONS.containsKey(op.toUpperCase(Locale.ROOT));
            case "GCP" -> direct && GCP_DIRECT_OPS.contains(op.toLowerCase(Locale.ROOT));
            case "AZURE" -> op.toLowerCase(Locale.ROOT).endsWith("setblobtier");
            default -> false;
        };
    }

    /** The class an S3 access log transition operation means, or {@code null} for any other operation. */
    private static String awsCodeClass(String operation) {
        return operation == null ? null : AWS_ACCESS_LOG_TRANSITIONS.get(operation.strip().toUpperCase(Locale.ROOT));
    }

    private static String reportedClass(DlapLogEvent event) {
        Map<String, String> md = event.getTargetResource().getTechnicalMetadataMap();
        for (String k : CLASS_KEYS) {
            String v = md.get(k);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static Instant changedAt(DlapLogEvent event) {
        Map<String, String> md = event.getTargetResource().getTechnicalMetadataMap();
        for (String k : CHANGED_KEYS) {
            String v = md.get(k);
            if (v != null && !v.isBlank()) {
                try {
                    return Instant.parse(v.strip());
                } catch (java.time.format.DateTimeParseException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String providerName(SourceProvider p) {
        return switch (p) {
            case AWS -> "AWS";
            case GCP -> "GCP";
            case AZURE -> "AZURE";
            default -> null;
        };
    }

    private static Instant instant(Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }
}
