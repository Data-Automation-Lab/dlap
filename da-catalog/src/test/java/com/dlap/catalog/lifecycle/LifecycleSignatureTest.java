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

package com.dlap.catalog.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.RequestMetadata;
import com.dlap.proto.log.v1.SourceProvider;

/**
 * Pins each row of the {@link LifecycleSignature} table (D36, Q-654): a native rule needs a
 * {@code SYSTEM} principal and the provider's lifecycle signature.
 */
class LifecycleSignatureTest {

    private static DlapLogEvent.Builder system(SourceProvider provider, String service, String operation) {
        return DlapLogEvent.newBuilder().setSourceProvider(provider).setSourceService(service).setOperation(operation)
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM));
    }

    @Test
    void s3LifecycleIsRecognizedByItsServiceItsPrincipalItsCallerOrItsAccessLogOperation() {
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3lifecycle.amazonaws.com", "DeleteObject")
                .build())).isTrue();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3.amazonaws.com", "DeleteObject")
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM).setId("lifecycle.s3.amazonaws.com"))
                .build())).isTrue();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3.amazonaws.com", "DeleteObject")
                .setRequestMetadata(RequestMetadata.newBuilder().setCallerIp("lifecycle.s3.amazonaws.com")).build()))
                .isTrue();
        for (String op : new String[] {"S3.EXPIRE.OBJECT", "S3.CREATE.DELETEMARKER", "S3.TRANSITION_SIA.OBJECT",
                "S3.TRANSITION.OBJECT", "s3.expire.object"}) {
            assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3.amazonaws.com", op).build()))
                    .as(op).isTrue();
        }
    }

    @Test
    void anotherAwsServiceIsNotTheLifecycle() {
        for (String op : new String[] {"DeleteObject", "S3.DELETE.UPLOAD", "S3.COMPUTE.OBJECT.CHECKSUM", "REST.DELETE.OBJECT",
                "S3.EXPIRE.OBJECTS"}) {
            assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3.amazonaws.com", op)
                    .setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM).setId("s3.amazonaws.com")).build()))
                    .as(op).isFalse();
        }
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3.amazonaws.com", "DeleteObject")
                .clearOperation().build())).isFalse();
    }

    @Test
    void gcpNeedsTheBigQueryExpiryOrTheGcsLifecycleAgent() {
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.GCP, "bigquery.googleapis.com",
                "InternalTableExpired").build())).isTrue();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.GCP, "other.googleapis.com",
                "internaltableexpired").build())).isTrue();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.GCP, "bigquery.googleapis.com", "TableDeleted")
                .build())).as("the matcher's SYSTEM-on-BigQuery row").isTrue();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.GCP, "storage.googleapis.com",
                "storage.objects.delete").setRequestMetadata(RequestMetadata.newBuilder()
                .setUserAgent("GCS Lifecycle Management")).build())).isTrue();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.GCP, "storage.googleapis.com",
                "storage.objects.delete").build())).isFalse();
    }

    @Test
    void azureTakesAnySystemPrincipalAndOtherProvidersNone() {
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AZURE, "Microsoft.Storage", "DeleteBlob").build()))
                .isTrue();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.DATABRICKS, "unityCatalog", "deleteTable").build()))
                .isFalse();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED,
                "s3lifecycle.amazonaws.com", "DeleteObject").build())).isFalse();
    }

    @Test
    void withoutASystemPrincipalNothingIsTheNativeRule() {
        assertThat(LifecycleSignature.isNativeRule(null)).isFalse();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3lifecycle.amazonaws.com", "DeleteObject")
                .clearPrincipal().build())).isFalse();
        assertThat(LifecycleSignature.isNativeRule(system(SourceProvider.AWS, "s3lifecycle.amazonaws.com", "DeleteObject")
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.USER).setId("lifecycle.s3.amazonaws.com"))
                .build())).isFalse();
    }
}
