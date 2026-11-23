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

package com.dlap.autocompliance.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlap.autocompliance.client.dto.CatalogAssetDTO;
import com.dlap.autocompliance.model.entity.ComplianceControlEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;

/**
 * Verifies the catalog-backed evaluators derive findings from real asset
 * attributes: public exposure (IAM-DRIFT-001), sensitivity+encryption
 * (PII-ENCRYPTION-001), and residency (GDPR-RESIDENCY-001).
 */
class AssetComplianceEvaluatorTest {

    private final AssetComplianceEvaluator evaluator = new AssetComplianceEvaluator();

    private ComplianceControlEntity control(String id, String severity, List<String> assetTypes,
                                            Map<String, String> params) {
        return ComplianceControlEntity.builder()
                .controlId(id)
                .name(id)
                .severity(severity)
                .targetAssetTypes(assetTypes)
                .evaluationParametersDefinition(params)
                .enabled(true)
                .build();
    }

    private CatalogAssetDTO asset(String id, String type) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(id);
        a.setAssetName(id);
        a.setAssetType(type);
        return a;
    }

    @Test
    void iamDrift_publicAsset_isNonCompliant() {
        ComplianceControlEntity c = control("IAM-DRIFT-001", "HIGH", List.of("GCS_BUCKET"), null);
        CatalogAssetDTO a = asset("gcs-1", "GCS_BUCKET");
        a.setTechnicalMetadata(Map.of("publicAccess", true));

        ReportFindingData f = evaluator.evaluate(c, a);

        assertThat(f).isNotNull();
        assertThat(f.getStatus()).isEqualTo("NON_COMPLIANT");
        assertThat(f.getSeverity()).isEqualTo("HIGH");
        assertThat(f.getCheckId()).isEqualTo("IAM-DRIFT-001");
        assertThat(f.getAssetId()).isEqualTo("gcs-1");
    }

    @Test
    void iamDrift_privateAsset_isCompliant() {
        ComplianceControlEntity c = control("IAM-DRIFT-001", "HIGH", List.of("GCS_BUCKET"), null);
        CatalogAssetDTO a = asset("gcs-2", "GCS_BUCKET");
        a.setTechnicalMetadata(Map.of("publicAccess", false));

        ReportFindingData f = evaluator.evaluate(c, a);

        assertThat(f).isNotNull();
        assertThat(f.getStatus()).isEqualTo("COMPLIANT");
    }

    @Test
    void iamDrift_outOfScopeAssetType_returnsNull() {
        ComplianceControlEntity c = control("IAM-DRIFT-001", "HIGH", List.of("GCS_BUCKET"), null);
        CatalogAssetDTO a = asset("s3-1", "S3_BUCKET");
        a.setTechnicalMetadata(Map.of("publicAccess", true));

        assertThat(evaluator.evaluate(c, a)).isNull();
    }

    @Test
    void piiEncryption_sensitiveUnencrypted_isNonCompliant() {
        ComplianceControlEntity c = control("PII-ENCRYPTION-001", "CRITICAL", null, null);
        CatalogAssetDTO a = asset("bq-1", "BIGQUERY_TABLE");
        a.setLabels(List.of("PII"));

        ReportFindingData f = evaluator.evaluate(c, a);

        assertThat(f).isNotNull();
        assertThat(f.getStatus()).isEqualTo("NON_COMPLIANT");
        assertThat(f.getSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    void piiEncryption_sensitiveEncrypted_isCompliant() {
        ComplianceControlEntity c = control("PII-ENCRYPTION-001", "CRITICAL", null, null);
        CatalogAssetDTO a = asset("bq-2", "BIGQUERY_TABLE");
        a.setLabels(List.of("CONFIDENTIAL"));
        a.setTechnicalMetadata(Map.of("kmsKey", "projects/p/keyRings/r/cryptoKeys/k"));

        ReportFindingData f = evaluator.evaluate(c, a);

        assertThat(f).isNotNull();
        assertThat(f.getStatus()).isEqualTo("COMPLIANT");
    }

    @Test
    void piiEncryption_nonSensitiveAsset_returnsNull() {
        ComplianceControlEntity c = control("PII-ENCRYPTION-001", "CRITICAL", null, null);
        CatalogAssetDTO a = asset("bq-3", "BIGQUERY_TABLE");
        a.setLabels(List.of("PUBLIC_DATA"));

        assertThat(evaluator.evaluate(c, a)).isNull();
    }

    @Test
    void gdprResidency_outOfRegion_isNonCompliant() {
        ComplianceControlEntity c = control("GDPR-RESIDENCY-001", "HIGH", null,
                Map.of("allowedRegions", "EU-WEST-1,EUROPE-WEST1"));
        CatalogAssetDTO a = asset("gcs-3", "GCS_BUCKET");
        a.setRegion("us-east-1");

        ReportFindingData f = evaluator.evaluate(c, a);

        assertThat(f).isNotNull();
        assertThat(f.getStatus()).isEqualTo("NON_COMPLIANT");
    }

    @Test
    void gdprResidency_inRegion_isCompliant() {
        ComplianceControlEntity c = control("GDPR-RESIDENCY-001", "HIGH", null,
                Map.of("allowedRegions", "EU-WEST-1,EUROPE-WEST1"));
        CatalogAssetDTO a = asset("gcs-4", "GCS_BUCKET");
        a.setRegion("europe-west1");

        ReportFindingData f = evaluator.evaluate(c, a);

        assertThat(f).isNotNull();
        assertThat(f.getStatus()).isEqualTo("COMPLIANT");
    }

    @Test
    void gdprResidency_noRegion_returnsNull() {
        ComplianceControlEntity c = control("GDPR-RESIDENCY-001", "HIGH", null, null);
        CatalogAssetDTO a = asset("gcs-5", "GCS_BUCKET");

        assertThat(evaluator.evaluate(c, a)).isNull();
    }

    @Test
    void unknownControl_returnsNull() {
        ComplianceControlEntity c = control("SOME-OTHER-CONTROL", "LOW", null, null);
        CatalogAssetDTO a = asset("gcs-6", "GCS_BUCKET");

        assertThat(evaluator.evaluate(c, a)).isNull();
    }
}
