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

import static com.dlap.logingest.parse.core.Stubs.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.ResourceRelation;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The producer rules a {@link CanonicalEvent} enforces at construction. */
class CanonicalEventTest {

    @Test
    void theEventIdIsTheFrozenFormulaAndMatchesTheLegacyPipeline() throws Exception {
        CanonicalEvent e = event("8f6a3b2c-0000-4000-8000-000000000001");
        // LogIngestServiceImpl.computeEventId with a non-blank requestId: SHA-256("<provider>|<requestId>").
        String legacy = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("AWS|8f6a3b2c-0000-4000-8000-000000000001".getBytes(StandardCharsets.UTF_8)));
        assertThat(e.eventId()).isEqualTo(legacy).isEqualTo(LogEventIds.eventId(SourceProvider.AWS, e.nativeKey()));
    }

    @Test
    void aNamespacedKeyKeepsSourcesOfOneProviderApart() {
        CanonicalEvent jobs = event(LogEventIds.namespacedNativeKey("GCP_BQ_JOBS_VIEW", "p", "US", "job_1"));
        CanonicalEvent audit = event("job_1");
        assertThat(jobs.eventId()).isNotEqualTo(audit.eventId());
        assertThat(jobs.nativeKey()).isEqualTo("GCP_BQ_JOBS_VIEW|p|US|job_1");
    }

    @Test
    void supersedingNamesTheReplacedEventId() {
        CanonicalEvent e = event("new").toBuilder().supersedesNativeKey("old").build();
        assertThat(e.supersedesEventId()).contains(LogEventIds.eventId(SourceProvider.AWS, "old"));
        assertThat(event("x").supersedesEventId()).isEmpty();
    }

    @Test
    void anEventNeedsANativeKeyAProviderAnOperationAndAnEventTime() {
        CanonicalEvent ok = event("k");
        assertThatThrownBy(() -> ok.toBuilder().nativeKey(" ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ok.toBuilder().nativeKey("x".repeat(CanonicalEvent.MAX_NATIVE_KEY_LENGTH + 1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ok.toBuilder().provider(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ok.toBuilder().operation("").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ok.toBuilder().eventTime(null).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("never stamps business time");
        assertThatThrownBy(() -> ok.toBuilder().eventTime(Instant.parse("+10000-01-01T00:00:00Z")).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aParsedEventNeedsKnownClassificationAndResult() {
        CanonicalEvent ok = event("k");
        assertThatThrownBy(() -> ok.toBuilder().changeType(ChangeType.CHANGE_TYPE_UNSPECIFIED).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ok.toBuilder().category(EventCategory.EVENT_CATEGORY_UNSPECIFIED).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ok.toBuilder().result(ResultStatus.RESULT_STATUS_UNSPECIFIED).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ok.toBuilder().changeType(ChangeType.UNRECOGNIZED).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPassThroughNeverRoutes() {
        CanonicalEvent p = CanonicalEvent.builder().nativeKey("k").provider(SourceProvider.AWS).operation("Op")
                .eventTime(Stubs.T0).passThrough().build();
        assertThat(p.passThrough()).isTrue();
        assertThat(p.changeType()).isEqualTo(ChangeType.CHANGE_TYPE_UNSPECIFIED);
        assertThatThrownBy(() -> p.toBuilder().changeType(ChangeType.DELETE).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalUrisComeOnlyFromTheNormalizer() {
        assertThatThrownBy(() -> CanonicalEvent.Resource.builder().canonicalUri("arn:aws:s3:::bucket-b/k").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AssetIdNormalizer");
        CanonicalEvent.Resource r = CanonicalEvent.Resource.builder().id("arn:aws:s3:::bucket-b/k")
                .canonicalFrom("arn:aws:s3:::bucket-b/k", AssetIdHints.of(AssetKind.S3_OBJECT)).build();
        assertThat(r.canonicalUri()).isEqualTo("s3://bucket-b/k");
        CanonicalEvent.Resource unresolved = CanonicalEvent.Resource.builder().id("demo://x")
                .canonicalFrom("demo://x", AssetIdHints.none()).build();
        assertThat(unresolved.canonicalUri()).isEmpty();
        assertThat(unresolved.id()).isEqualTo("demo://x");
    }

    @Test
    void relationsBelongToRelatedResourcesOnly() {
        CanonicalEvent.Resource parent = CanonicalEvent.Resource.builder().id("b")
                .relation(ResourceRelation.RESOURCE_RELATION_PARENT).build();
        assertThatThrownBy(() -> event("k").toBuilder().target(parent).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("k").toBuilder().addRelated(CanonicalEvent.Resource.builder().id("x").build()).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event("k").toBuilder().addRelated(parent).build().related()).containsExactly(parent);
    }

    @Test
    void sizesAreNeverNegativeAndUnknownIsNull() {
        assertThatThrownBy(() -> CanonicalEvent.Resource.builder().sizeBytes(-1L).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CanonicalEvent.Resource.builder().sizeBytes(0L).build().sizeBytes()).isZero();
        assertThat(CanonicalEvent.Resource.builder().build().sizeBytes()).isNull();
    }

    @Test
    void missingStringsAreEmptyNeverInvented() {
        CanonicalEvent e = event("k");
        assertThat(e.principal()).isEqualTo(CanonicalEvent.Actor.UNKNOWN);
        assertThat(e.target()).isEqualTo(CanonicalEvent.Resource.NONE);
        assertThat(e.request()).isEqualTo(CanonicalEvent.Request.NONE);
        assertThat(e.sharedEventId()).isEmpty();
    }

    @Test
    void warningsAreKnownValuesAndAccumulate() {
        CanonicalEvent e = event("k").withWarnings(Set.of(ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED));
        assertThat(e.withWarnings(Set.of(ParseWarning.PARSE_WARNING_NEWER_MINOR)).warnings())
                .containsExactlyInAnyOrder(ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED, ParseWarning.PARSE_WARNING_NEWER_MINOR);
        assertThatThrownBy(() -> event("k").toBuilder().warning(ParseWarning.PARSE_WARNING_UNSPECIFIED).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHeuristicClassificationCarriesItsWarning() {
        CanonicalEvent e = event("k").toBuilder().classification(VerbHeuristics.classify("ListBuckets")).build();
        assertThat(e.warnings()).contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
    }
}
