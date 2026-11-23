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

package com.dlap.common.constants;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.proto.log.v1.SourceProvider;
import org.junit.jupiter.api.Test;

/**
 * Pins the wave-1 frozen kind list: the constants other packages compile
 * against must keep their spelling, and every catalog provider constant must
 * exist as a log-event SourceProvider so a log event can name it.
 */
class FrozenKindConstantsTest {

    @Test
    void frozenServiceTypesKeepTheirSpelling() {
        assertThat(new String[] {
            DlapConstants.ServiceType.S3_OBJECT, DlapConstants.ServiceType.GCS_OBJECT,
            DlapConstants.ServiceType.BLOB_CONTAINER, DlapConstants.ServiceType.BLOB_OBJECT,
            DlapConstants.ServiceType.GLUE_DATABASE, DlapConstants.ServiceType.GLUE_TABLE,
            DlapConstants.ServiceType.UNITY_VOLUME, DlapConstants.ServiceType.SNOWFLAKE_TABLE
        }).containsExactly("S3_OBJECT", "GCS_OBJECT", "BLOB_CONTAINER", "BLOB_OBJECT",
                "GLUE_DATABASE", "GLUE_TABLE", "UNITY_VOLUME", "SNOWFLAKE_TABLE");
    }

    @Test
    void everyCatalogProviderIsALogEventSourceProvider() {
        for (String provider : new String[] {
            DlapConstants.CloudProvider.AWS, DlapConstants.CloudProvider.GCP,
            DlapConstants.CloudProvider.AZURE, DlapConstants.CloudProvider.OCI,
            DlapConstants.CloudProvider.UNITY, DlapConstants.CloudProvider.DATABRICKS,
            DlapConstants.CloudProvider.SNOWFLAKE
        }) {
            assertThat(SourceProvider.valueOf(provider).name()).isEqualTo(provider);
        }
    }
}
