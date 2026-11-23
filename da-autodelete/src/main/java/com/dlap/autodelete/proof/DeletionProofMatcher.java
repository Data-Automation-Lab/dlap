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

package com.dlap.autodelete.proof;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether a normalized log event (docs/specs/log-event.md) is the provider's own proof
 * that a disposition was carried out. Only then does a disposition become {@code EXECUTED}.
 *
 * <p>Every proof is a successful {@code DELETE} event from the provider's own logs
 * ({@code ORIGIN_CLOUD_LOG}, {@code ORIGIN_BACKFILL}, or the legacy pipeline's unspecified
 * origin; never a crawler observation or a replayed fixture), of the disposition's provider, on
 * the disposition's target or below it, stamped no earlier than the apply time minus the clock
 * skew. A native rule additionally needs the platform's lifecycle signature, so a person's manual
 * delete is not mistaken for the rule firing:
 *
 * <table>
 *   <caption>Lifecycle signatures</caption>
 *   <tr><th>Platform</th><th>Signature</th><th>Source</th></tr>
 *   <tr><td>S3</td><td>{@code source_service} {@code s3lifecycle.amazonaws.com}; or the principal or
 *       caller {@code lifecycle.s3.amazonaws.com}; or a server access log operation
 *       {@code S3.EXPIRE.OBJECT}. {@code S3.CREATE.DELETEMARKER} is not a proof (RET-06, Q-657): a delete
 *       marker is an UPDATE (ADR-0104), and a native rule on a versioned bucket is refused (Q-656)</td>
 *       <td>CloudTrail lifecycle events and S3 server access logs (research/hardening 01, sections 4.2
 *       and 6)</td></tr>
 *   <tr><td>GCS</td><td>user agent containing {@code GCS Lifecycle Management}</td>
 *       <td>[unverified]: Cloud Audit Logs do not record lifecycle actions (research/hardening 02); the
 *       usage-log user agent needs a live capture (open question)</td></tr>
 *   <tr><td>Azure Blob</td><td>user agent containing {@code ObjectLifecycleManagement}, or a
 *       {@code SYSTEM} principal</td><td>[unverified]: needs a live StorageBlobLogs capture (open
 *       question)</td></tr>
 *   <tr><td>BigQuery</td><td>operation {@code InternalTableExpired}, or a {@code SYSTEM} principal on
 *       {@code bigquery.googleapis.com}</td><td>the system event of Cloud Audit Logs
 *       (research/hardening 02, section 3)</td></tr>
 * </table>
 *
 * A direct engine delete needs no signature: the provider's own delete event on the target is the
 * proof.
 */
public final class DeletionProofMatcher {

    private DeletionProofMatcher() {
    }

    /**
     * Matches one event against one disposition.
     *
     * @param event       the decoded event
     * @param disposition an applied disposition
     * @param clockSkew   how much earlier than the apply time the event may be stamped
     * @return the proof source ({@code source_format}, else {@code source_service}) when the event
     *         proves the disposition, else empty
     */
    public static Optional<String> match(DlapLogEvent event, RetireDispositionEntity disposition, Duration clockSkew) {
        if (event == null || disposition == null || disposition.getAppliedAt() == null) {
            return Optional.empty();
        }
        if (event.getResultStatus() != ResultStatus.SUCCESS || event.getChangeType() != ChangeType.DELETE) {
            return Optional.empty();
        }
        Origin origin = event.getOrigin();
        if (origin == Origin.ORIGIN_CRAWLER || origin == Origin.ORIGIN_REPLAY) {
            return Optional.empty();
        }
        if (!event.getSourceProvider().name().equalsIgnoreCase(String.valueOf(disposition.getCloudProvider()))) {
            return Optional.empty();
        }
        String uri = event.hasTargetResource() ? event.getTargetResource().getCanonicalUri() : "";
        String target = disposition.getTargetUri();
        if (uri.isEmpty() || target == null || !(uri.equals(target) || uri.startsWith(target + "/"))) {
            return Optional.empty();
        }
        Instant eventTime = eventTime(event);
        if (eventTime == null || eventTime.isBefore(disposition.getAppliedAt().minus(clockSkew))) {
            return Optional.empty();
        }
        if (RetireDispositionEntity.MODE_NATIVE_RULE.equals(disposition.getMode())
                && !hasLifecycleSignature(event, NativeRulePlatform.valueOf(disposition.getPlatform()))) {
            return Optional.empty();
        }
        String source = event.getSourceFormat().isEmpty() ? event.getSourceService() : event.getSourceFormat();
        return Optional.of(source.isEmpty() ? "unknown" : source);
    }

    /**
     * Whether the event carries the platform's lifecycle signature (see the class table).
     *
     * @param event    the event
     * @param platform the platform
     * @return true when it does
     */
    static boolean hasLifecycleSignature(DlapLogEvent event, NativeRulePlatform platform) {
        String service = lower(event.getSourceService());
        String operation = event.getOperation();
        String agent = event.hasRequestMetadata() ? lower(event.getRequestMetadata().getUserAgent()) : "";
        String caller = event.hasRequestMetadata() ? lower(event.getRequestMetadata().getCallerIp()) : "";
        String principalId = event.hasPrincipal() ? lower(event.getPrincipal().getId()) : "";
        boolean systemPrincipal = event.hasPrincipal() && event.getPrincipal().getType() == PrincipalType.SYSTEM;
        return switch (platform) {
            case S3_LIFECYCLE -> "s3lifecycle.amazonaws.com".equals(service)
                    || principalId.contains("lifecycle.s3.amazonaws.com")
                    || caller.contains("lifecycle.s3.amazonaws.com")
                    || "S3.EXPIRE.OBJECT".equalsIgnoreCase(operation);
            case GCS_LIFECYCLE -> agent.contains("gcs lifecycle management");
            case AZURE_BLOB_LIFECYCLE -> agent.contains("objectlifecyclemanagement") || systemPrincipal;
            case BIGQUERY_TABLE_EXPIRATION -> "InternalTableExpired".equalsIgnoreCase(operation)
                    || (systemPrincipal && "bigquery.googleapis.com".equals(service));
        };
    }

    private static Instant eventTime(DlapLogEvent event) {
        if (!event.hasEventTime()) {
            return null;
        }
        return Instant.ofEpochSecond(event.getEventTime().getSeconds(), event.getEventTime().getNanos());
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
