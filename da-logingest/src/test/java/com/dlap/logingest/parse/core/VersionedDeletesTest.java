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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetKind;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** PARSE-03 (D36, Q-651): a version-qualified delete is an update of the same target. */
class VersionedDeletesTest {

    private static CanonicalEvent delete() {
        CanonicalEvent.Resource target = CanonicalEvent.Resource.builder().kind("AWS::S3::Object").id("s3://bucket-b/k")
                .name("k").project("123456789012").region("us-east-1").technicalMetadata(Map.of("storageClass", "STANDARD"))
                .canonicalFrom("s3://bucket-b/k", AssetIdHints.of(AssetKind.S3_OBJECT)).build();
        return CanonicalEvent.builder().nativeKey("k").provider(SourceProvider.AWS).service("s3.amazonaws.com")
                .operation("DeleteObject").changeType(ChangeType.DELETE).category(EventCategory.EVENT_CATEGORY_DATA_WRITE)
                .result(ResultStatus.SUCCESS).eventTime(Stubs.T0).target(target).build();
    }

    @Test
    void aDeleteBecomesAManagementUpdateOfTheSameTargetWithTheVersionAction() {
        CanonicalEvent e = VersionedDeletes.asVersionUpdate(delete(), VersionedDeletes.DELETE_VERSION, Optional.of("v1"));

        assertThat(e.changeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(e.category()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(e.target().canonicalUri()).isEqualTo("s3://bucket-b/k");
        assertThat(e.target().id()).isEqualTo("s3://bucket-b/k");
        assertThat(e.target().project()).isEqualTo("123456789012");
        assertThat(e.target().technicalMetadata()).containsExactly(Map.entry("storageClass", "STANDARD"),
                Map.entry(VersionedDeletes.ACTION_KEY, VersionedDeletes.DELETE_VERSION),
                Map.entry(VersionedDeletes.VERSION_ID_KEY, "v1"));
        assertThat(e.nativeKey()).isEqualTo("k");
    }

    @Test
    void anEventThatIsNotADeleteIsReturnedUnchanged() {
        CanonicalEvent update = delete().toBuilder().changeType(ChangeType.UPDATE).build();

        assertThat(VersionedDeletes.asVersionUpdate(update, VersionedDeletes.DELETE_VERSION, Optional.of("v1")))
                .isSameAs(update);
    }

    @Test
    void aMarkerWithoutAVersionIdCarriesOnlyTheAction() {
        CanonicalEvent.Resource t = VersionedDeletes.withAction(delete().target(), VersionedDeletes.CREATE_DELETE_MARKER,
                Optional.empty());

        assertThat(t.technicalMetadata()).containsEntry(VersionedDeletes.ACTION_KEY, VersionedDeletes.CREATE_DELETE_MARKER)
                .doesNotContainKey(VersionedDeletes.VERSION_ID_KEY);
    }
}
