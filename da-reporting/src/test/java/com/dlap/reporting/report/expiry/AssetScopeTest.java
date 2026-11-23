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

package com.dlap.reporting.report.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The account column is read from the canonical URI only where the URI carries it. */
class AssetScopeTest {

    @ParameterizedTest
    @CsvSource({
            "arn:aws:rds:eu-west-1:111122223333:db:prod-postgres,111122223333",
            "arn:aws:dynamodb:eu-west-1:111122223333:table/orders,111122223333",
            "arn:aws:redshift:eu-west-1:111122223333:cluster:wh,111122223333",
            "arn:aws:glue:eu-west-1:111122223333:database/sales,111122223333",
            "arn:aws:glue:eu-west-1:111122223333:table/sales/orders,111122223333",
            "arn:aws:glue:eu-west-1:111122223333:job/nightly-etl,111122223333",
            "bq://example-analytics/sales,example-analytics",
            "bq://example-analytics/sales/orders,example-analytics",
            "az://examplestore,examplestore",
            "az://examplestore/raw,examplestore",
            "az://examplestore/raw/2026/part-0001.csv,examplestore",
            "snowflake://acme-xy12345/DB/SCH/TBL,acme-xy12345",
    })
    void readsTheAccountFromTheUri(String uri, String account) {
        assertThat(AssetScope.accountOf(uri)).contains(account);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "s3://orphan-logs",
            "gs://finance-archive",
            "finance-archive",
            "not a uri at all",
            "arn:aws:rds:eu-west-1:null:db:prod-postgres",
    })
    void givesNoAccountWhenTheUriHasNone(String uri) {
        assertThat(AssetScope.accountOf(uri)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void givesNoAccountForABlankUri(String uri) {
        assertThat(AssetScope.accountOf(uri)).isEmpty();
    }

    @Test
    void readsTheAccountOfOtherProvidersCanonicalForms() {
        assertThat(AssetScope.accountOf("bqjob://example-analytics/job_abc123")).contains("example-analytics");
        assertThat(AssetScope.accountOf("//cloudsql.googleapis.com/projects/example-analytics/instances/db1"))
                .contains("example-analytics");
        assertThat(AssetScope.accountOf("athena://eu-west-1/111122223333/a1b2c3d4-0000-4000-8000-000000000001"))
                .contains("111122223333");
        assertThat(AssetScope.accountOf("/subscriptions/00000000-0000-4000-8000-000000000001/resourcegroups/rg1"
                + "/providers/microsoft.sql/servers/srv1/databases/db1"))
                .contains("00000000-0000-4000-8000-000000000001");
        assertThat(AssetScope.accountOf("dbx://1234567890123456/jobs/42")).contains("1234567890123456");
    }
}
