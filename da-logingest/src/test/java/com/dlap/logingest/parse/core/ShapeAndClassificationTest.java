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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.assetid.AssetKind;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Shape fingerprints, field paths, classification tables and the shared verb heuristics. */
class ShapeAndClassificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Nested
    class Fingerprints {

        private final List<FieldPath> declared = List.of(FieldPath.of("/eventName"),
                FieldPath.of("/userIdentity/sessionContext/sessionIssuer/arn"), FieldPath.of("/requestParameters"),
                FieldPath.of("/resources/0/ARN"));

        @Test
        void knownUnknownAndSubtrees() throws Exception {
            ShapeFingerprint f = ShapeFingerprint.of(json("""
                    {"eventName":"GetObject",
                     "userIdentity":{"sessionContext":{"sessionIssuer":{"arn":"a"}},"newThing":1},
                     "requestParameters":{"bucketName":"b","key":{"deep":1}},
                     "resources":[{"ARN":"x","type":"t"}],
                     "brandNew":{"a":{"b":{"c":1}}}}"""), declared);
            assertThat(f.unknownPaths()).containsExactly("/brandNew", "/resources/*/type", "/userIdentity/newThing");
            assertThat(f.knownPaths()).contains("/eventName", "/requestParameters/bucketName",
                    "/userIdentity/sessionContext/sessionIssuer", "/resources/*/ARN");
            assertThat(f.hasUnknownFields()).isTrue();
            assertThat(f.shortHash()).hasSize(16);
        }

        @Test
        void theHashIgnoresValuesAndKeyOrderButNotShape() throws Exception {
            ShapeFingerprint a = ShapeFingerprint.of(json("{\"eventName\":\"A\",\"requestParameters\":{\"x\":1}}"), declared);
            ShapeFingerprint b = ShapeFingerprint.of(json("{\"requestParameters\":{\"x\":2},\"eventName\":\"B\"}"), declared);
            ShapeFingerprint c = ShapeFingerprint.of(json("{\"eventName\":\"A\"}"), declared);
            assertThat(a.hash()).isEqualTo(b.hash()).isNotEqualTo(c.hash());
        }

        @Test
        void depthIsBoundedAndHostileKeysAreSafe() throws Exception {
            ShapeFingerprint f = ShapeFingerprint.of(json("{\"" + "k".repeat(2000) + "\":{\"a/b~c\":1}}"), List.of(), 2);
            assertThat(f.unknownPaths()).hasSize(1);
            assertThatThrownBy(() -> ShapeFingerprint.of(json("{}"), List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Paths {

        @Test
        void pointersWildcardsAndEscapes() throws Exception {
            JsonNode n = json("{\"a\":{\"b/c\":1,\"t~x\":2},\"arr\":[{\"g\":false},{\"g\":true}],\"blank\":\"  \",\"nul\":null}");
            assertThat(FieldPath.of("/a/b~1c").first(n)).map(JsonNode::asInt).contains(1);
            assertThat(FieldPath.of("/a/t~0x").first(n)).map(JsonNode::asInt).contains(2);
            assertThat(FieldPath.of("/arr/*/g").select(n)).hasSize(2);
            assertThat(FieldPath.of("/arr/1/g").first(n)).map(JsonNode::asBoolean).contains(true);
            assertThat(FieldPath.of("/blank").first(n)).isEmpty();
            assertThat(FieldPath.of("/nul").first(n)).isEmpty();
            assertThat(FieldPath.of("/missing/deeper").select(n)).isEmpty();
            assertThat(FieldPath.of("/arr/0/g").fingerprintPath()).isEqualTo("/arr/*/g");
        }

        @Test
        void invalidPointersAreRefused() {
            assertThat(FieldPath.isValid("eventName")).isFalse();
            assertThat(FieldPath.isValid("/a~2")).isFalse();
            assertThat(FieldPath.isValid("/" + "a".repeat(FieldPath.MAX_LENGTH))).isFalse();
            assertThatThrownBy(() -> FieldPath.of("x")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Classifications {

        private final List<ClassificationRule> table = List.of(
                ClassificationRule.exact("s3.amazonaws.com", "GetObject", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ),
                new ClassificationRule("s3.amazonaws.com", null, "Put", ChangeType.UPDATE,
                        EventCategory.EVENT_CATEGORY_DATA_WRITE, AssetKind.S3_OBJECT, "s3.request-parameters"),
                new ClassificationRule("s3.amazonaws.com", null, "PutBucket", ChangeType.PERMISSION,
                        EventCategory.EVENT_CATEGORY_PERMISSION, AssetKind.S3_BUCKET, null));

        @Test
        void theMostSpecificRowWins() {
            assertThat(Classification.classify(List.of(), table, "s3.amazonaws.com", "PutObject").category())
                    .isEqualTo(EventCategory.EVENT_CATEGORY_DATA_WRITE);
            Classification bucket = Classification.classify(List.of(), table, "s3.amazonaws.com", "PutBucketPolicy");
            assertThat(bucket.changeType()).isEqualTo(ChangeType.PERMISSION);
            assertThat(bucket.assetKind()).contains(AssetKind.S3_BUCKET);
            assertThat(bucket.heuristic()).isFalse();
        }

        @Test
        void bindingOverridesWin() {
            List<ClassificationRule> overrides = List.of(ClassificationRule.exact("s3.amazonaws.com", "PutObject",
                    ChangeType.CREATE, EventCategory.EVENT_CATEGORY_DATA_WRITE));
            assertThat(Classification.classify(overrides, table, "s3.amazonaws.com", "PutObject").changeType())
                    .isEqualTo(ChangeType.CREATE);
        }

        @Test
        void unlistedOperationsUseTheHeuristicsAndSaySo() {
            Classification c = Classification.classify(List.of(), table, "s3.amazonaws.com", "DeleteBucket");
            assertThat(c.heuristic()).isTrue();
            assertThat(c.changeType()).isEqualTo(ChangeType.DELETE);
            assertThat(Classification.classify(List.of(), table, "other.service", "GetObject").heuristic()).isTrue();
        }

        @Test
        void rowsAreValidated() {
            assertThatThrownBy(() -> new ClassificationRule("s", "a", "b", ChangeType.ACCESS,
                    EventCategory.EVENT_CATEGORY_DATA_READ, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ClassificationRule.exact("s", "a", ChangeType.CHANGE_TYPE_UNSPECIFIED,
                    EventCategory.EVENT_CATEGORY_DATA_READ)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ClassificationRule("s", "a", null, ChangeType.ACCESS,
                    EventCategory.EVENT_CATEGORY_DATA_READ, null, "Bad_Name")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Heuristics {

        @ParameterizedTest(name = "{0} -> {1} {2}")
        @CsvSource({
            "GetObject,                                   ACCESS,     EVENT_CATEGORY_DATA_READ",
            "storage.objects.get,                         ACCESS,     EVENT_CATEGORY_DATA_READ",
            "BatchGetItem,                                ACCESS,     EVENT_CATEGORY_DATA_READ",
            "HeadObject,                                  ACCESS,     EVENT_CATEGORY_DATA_READ",
            "ListBuckets,                                 ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "DescribeTable,                               ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "tabledataservice.list,                       ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "LookupEntry,                                 ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "SearchCatalog,                               ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "ListIAMRoles,                                ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "GetBucketPolicy,                             ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "GetBucketAcl,                                ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "projects.getIamPolicy,                       ACCESS,     EVENT_CATEGORY_MANAGEMENT",
            "PutBucketPolicy,                             PERMISSION, EVENT_CATEGORY_PERMISSION",
            "storage.setIamPermissions,                   PERMISSION, EVENT_CATEGORY_PERMISSION",
            "PutBucketAcl,                                PERMISSION, EVENT_CATEGORY_PERMISSION",
            "RevokeRole,                                  PERMISSION, EVENT_CATEGORY_PERMISSION",
            "CreateDiamondTable,                          CREATE,     EVENT_CATEGORY_MANAGEMENT",
            "storage.buckets.create,                      CREATE,     EVENT_CATEGORY_MANAGEMENT",
            "google.cloud.bigquery.v2.TableService.DeleteTable, DELETE, EVENT_CATEGORY_MANAGEMENT",
            "TerminateInstances,                          DELETE,     EVENT_CATEGORY_MANAGEMENT",
            "jobservice.jobcompleted,                     UPDATE,     EVENT_CATEGORY_MANAGEMENT",
            "ModifyDBInstance,                            UPDATE,     EVENT_CATEGORY_MANAGEMENT",
        })
        void verbs(String operation, ChangeType type, EventCategory category) {
            Classification c = VerbHeuristics.classify(operation);
            assertThat(c.changeType()).isEqualTo(type);
            assertThat(c.category()).isEqualTo(category);
            assertThat(c.heuristic()).isTrue();
        }

        @Test
        void noOperationIsAnUpdate() {
            assertThat(VerbHeuristics.classify(null).changeType()).isEqualTo(ChangeType.UPDATE);
            assertThat(VerbHeuristics.classify(" ").changeType()).isEqualTo(ChangeType.UPDATE);
            assertThat(VerbHeuristics.words("ListIAMRoles")).containsExactly("list", "iam", "roles");
        }
    }
}
