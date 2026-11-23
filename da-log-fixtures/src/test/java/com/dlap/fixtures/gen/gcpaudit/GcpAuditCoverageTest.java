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

package com.dlap.fixtures.gen.gcpaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.fixtures.CoverageReport;
import com.dlap.fixtures.FixtureCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The coverage the GCP-01 corpus reaches against the independent gcpaudit spec, cell by cell: the
 * nine claimed cells score 1.0, and the three BigQuery v2 cells miss exactly the two VPC Service
 * Controls variants that no v2 entry can show. When the spec narrows those variants, this test fails
 * and the v2 cells should be claimed in catalog.yaml.
 */
class GcpAuditCoverageTest {

    private static final List<String> VPC_SC = List.of("status.vpc-sc-denial", "status.vpc-sc-ingress-egress");

    @Test
    void claimedCellsScoreOneAndTheV2CellsMissOnlyTheVpcScVariants() {
        CoverageReport report = CoverageReport.compute(FixtureCatalog.fromClasspath());
        List<CoverageReport.Row> rows = report.rows().stream().filter(r -> r.family().equals("gcpaudit")).toList();
        assertThat(rows).hasSize(12);
        for (CoverageReport.Row r : rows) {
            assertThat(r.denominator()).as(r.key()).isEqualTo(CoverageReport.Denominator.SPEC);
            assertThat(r.missingOperations()).as(r.key() + " operations").isEmpty();
            if (r.version().equals("bq-auditmetadata")) {
                assertThat(r.supported()).as(r.key() + " claimed").isFalse();
                assertThat(r.missingVariants()).as(r.key() + " variants").containsExactlyElementsOf(VPC_SC);
            } else {
                assertThat(r.supported()).as(r.key() + " claimed").isTrue();
                assertThat(r.score()).as(r.key() + " score").isEqualTo(1.0);
            }
        }
    }
}
