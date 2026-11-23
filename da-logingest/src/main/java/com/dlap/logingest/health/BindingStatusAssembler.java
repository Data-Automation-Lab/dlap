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

package com.dlap.logingest.health;

import com.dlap.logingest.health.HealthSnapshot.BindingSnapshot;
import com.dlap.logingest.health.HealthSnapshot.ObservedVersion;
import com.dlap.logingest.metrics.ParseTelemetry.WindowCounts;
import com.dlap.proto.log.v1.BindingScope;
import com.dlap.proto.log.v1.BindingState;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.ObservedFormat;
import com.dlap.proto.log.v1.RecordCounts;
import com.dlap.proto.log.v1.SourceProvider;
import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a {@link BindingSnapshot} and the counts of its status window into the wire message
 * {@code dlap.log.v1.LogBindingStatus} (log-event spec section 10). Pure: the same inputs give the
 * same message, so every branch is a unit test.
 *
 * <h3>State</h3>
 * <table>
 *   <caption>Binding state</caption>
 *   <tr><th>Condition</th><th>State</th></tr>
 *   <tr><td>configuration failed validation</td><td>FAILED</td></tr>
 *   <tr><td>disabled, or the legacy pipeline does not run bindings</td><td>DISABLED</td></tr>
 *   <tr><td>the startup self-test failed</td><td>DEGRADED</td></tr>
 *   <tr><td>the oldest unacknowledged event time is older than {@code degraded-lag}</td><td>DEGRADED</td></tr>
 *   <tr><td>more than {@code degraded-quarantine-ratio} of at least {@code degraded-min-records}
 *       records of the window were quarantined</td><td>DEGRADED</td></tr>
 *   <tr><td>otherwise</td><td>ACTIVE</td></tr>
 * </table>
 * {@code state_detail} names the rule in words and never carries a secret or a record value.
 *
 * <p>Every timestamp is a time the service observed (processing time or a provider event time); the
 * assembler never invents a business time.
 */
public final class BindingStatusAssembler {

    private final LogHealthProperties props;

    /**
     * Creates the assembler.
     *
     * @param props the health settings
     */
    public BindingStatusAssembler(LogHealthProperties props) {
        this.props = props;
    }

    /**
     * Builds the status of a binding.
     *
     * @param b            the binding
     * @param counts       the outcome counts of the status window
     * @param coverage     the read-coverage verdict
     * @param selfTestOk   whether the startup self-test passed
     * @param now          the current time
     * @return the status message
     */
    public LogBindingStatus assemble(BindingSnapshot b, WindowCounts counts, ReadCoverageEvaluator.Evaluation coverage,
                                     boolean selfTestOk, Instant now) {
        LogBindingStatus.Builder out = LogBindingStatus.newBuilder()
                .setBindingId(b.bindingId())
                .setSourceProvider(b.provider().orElse(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED))
                .setSourceKind(b.sourceKind())
                .setScope(scope(b))
                .setConfigHash(b.configHash())
                .setReportedTime(ts(now))
                .setCounts(RecordCounts.newBuilder()
                        .setParsed(counts.parsed())
                        .setParsedWithWarnings(counts.parsedWithWarnings())
                        .setSkipped(counts.skipped())
                        .setQuarantined(counts.quarantined())
                        .setPublished(counts.published())
                        .setDuplicates(counts.duplicates()))
                .setWindowStart(ts(counts.windowStart()))
                .setWindowEnd(ts(counts.windowEnd()))
                .setReadCoverage(coverage.coverage())
                .setSchemaVersion("v1");
        b.lowWatermark().ifPresent(t -> out.setLowWatermark(ts(t)));
        b.highWatermark().ifPresent(t -> out.setHighWatermark(ts(t)));
        b.lastPoll().ifPresent(t -> out.setLastPollTime(ts(t)));
        coverage.since().ifPresent(t -> out.setReadCoverageSince(ts(t)));
        for (ObservedVersion o : b.observed()) {
            ObservedFormat.Builder f = ObservedFormat.newBuilder()
                    .setEnvelope(o.envelope())
                    .setSchemaVersion(o.version())
                    .setFingerprint(o.fingerprint())
                    .setCount(o.count());
            if (o.firstSeen() != null) {
                f.setFirstSeen(ts(o.firstSeen()));
            }
            if (o.lastSeen() != null) {
                f.setLastSeen(ts(o.lastSeen()));
            }
            out.addObservedFormats(f);
        }
        State s = state(b, counts, selfTestOk, now);
        return out.setState(s.state()).setStateDetail(s.detail()).build();
    }

    /**
     * The state and its detail.
     *
     * @param state  the state
     * @param detail a short reason, empty for ACTIVE
     */
    public record State(BindingState state, String detail) {
    }

    /**
     * Decides the state of a binding.
     *
     * @param b          the binding
     * @param counts     the outcome counts of the status window
     * @param selfTestOk whether the startup self-test passed
     * @param now        the current time
     * @return the state and why
     */
    public State state(BindingSnapshot b, WindowCounts counts, boolean selfTestOk, Instant now) {
        switch (b.state()) {
            case INVALID:
                return new State(BindingState.BINDING_STATE_FAILED,
                        "the binding configuration failed validation; see /actuator/info");
            case DISABLED:
            case INACTIVE:
                return new State(BindingState.BINDING_STATE_DISABLED, b.state() == com.dlap.logingest.model.BindingState.INACTIVE
                        ? "the legacy pipeline is selected, bindings do not run" : "the binding is disabled");
            default:
                break;
        }
        if (!selfTestOk) {
            return new State(BindingState.BINDING_STATE_DEGRADED, "the startup parser self-test failed");
        }
        Optional<Instant> low = b.lowWatermark();
        if (low.isPresent() && Duration.between(low.get(), now).compareTo(props.degradedLag()) > 0) {
            return new State(BindingState.BINDING_STATE_DEGRADED, "the oldest unacknowledged event is older than "
                    + props.degradedLag());
        }
        long records = counts.records();
        if (records >= props.degradedMinRecords() && (double) counts.quarantined() / records
                > props.degradedQuarantineRatio()) {
            return new State(BindingState.BINDING_STATE_DEGRADED, String.format(Locale.ROOT,
                    "%d of %d records were quarantined in the window", counts.quarantined(), records));
        }
        return new State(BindingState.BINDING_STATE_ACTIVE, "");
    }

    /**
     * The scope of a binding, from its configuration scope map.
     *
     * @param b the binding
     * @return the scope message
     */
    static BindingScope scope(BindingSnapshot b) {
        Map<String, Object> m = b.scope();
        String kind = b.sourceKind().toUpperCase(Locale.ROOT);
        String type;
        String id;
        if (m.get("project-id") != null) {
            type = "GCP_PROJECT";
            id = String.valueOf(m.get("project-id"));
        } else if (m.get("subscription-id") != null) {
            type = "AZURE_SUBSCRIPTION";
            id = String.valueOf(m.get("subscription-id"));
        } else if (m.get("workspace-id") != null) {
            type = "DATABRICKS_WORKSPACE";
            id = String.valueOf(m.get("workspace-id"));
        } else if (m.get("metastore-id") != null) {
            type = "UNITY_METASTORE";
            id = String.valueOf(m.get("metastore-id"));
        } else if (m.get("account-id") != null) {
            type = kind.startsWith("SNOWFLAKE") ? "SNOWFLAKE_ACCOUNT"
                    : kind.startsWith("DATABRICKS") ? "DATABRICKS_ACCOUNT" : "AWS_ACCOUNT";
            id = String.valueOf(m.get("account-id"));
        } else {
            type = "";
            id = "";
        }
        BindingScope.Builder out = BindingScope.newBuilder().setScopeType(type).setScopeId(id);
        if (m.get("regions") instanceof List<?> regions) {
            regions.forEach(r -> out.addRegions(String.valueOf(r)));
        }
        return out.build();
    }

    private static Timestamp ts(Instant t) {
        return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
    }
}
