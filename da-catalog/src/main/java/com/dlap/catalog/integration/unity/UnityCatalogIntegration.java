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

package com.dlap.catalog.integration.unity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.dlap.catalog.integration.ExternalCatalogIntegration;
import com.dlap.common.model.entity.Asset;

import io.unitycatalog.client.ApiClient;
import io.unitycatalog.client.ApiException;
import io.unitycatalog.client.api.CatalogsApi;
import io.unitycatalog.client.api.SchemasApi;
import io.unitycatalog.client.api.TablesApi;
import io.unitycatalog.client.model.ColumnInfo;
import io.unitycatalog.client.model.ColumnTypeName;
import io.unitycatalog.client.model.CreateCatalog;
import io.unitycatalog.client.model.CreateSchema;
import io.unitycatalog.client.model.CreateTable;
import io.unitycatalog.client.model.DataSourceFormat;
import io.unitycatalog.client.model.TableType;

/**
 * Unity Catalog sink (ADR-0029): mirrors DLAP <em>tabular</em> assets into a
 * Unity Catalog metastore as {@code catalog.schema.table} entries, with DLAP
 * labels attached as table properties. da-catalog stays the system of record;
 * this is an additive interoperability layer.
 *
 * <p>Active only when {@code dlap.catalog.unity.enabled=true}. Non-tabular
 * assets (buckets, blobs) are skipped. Failures are swallowed (logged) so
 * mirroring never breaks catalog ingestion.
 */
@Component("unityCatalogIntegration")
@ConditionalOnProperty(name = "dlap.catalog.unity.enabled", havingValue = "true")
@EnableConfigurationProperties(UnityCatalogProperties.class)
public class UnityCatalogIntegration implements ExternalCatalogIntegration {

    private static final Logger log = LoggerFactory.getLogger(UnityCatalogIntegration.class);

    private final UnityCatalogProperties props;
    private final CatalogsApi catalogsApi;
    private final SchemasApi schemasApi;
    private final TablesApi tablesApi;

    public UnityCatalogIntegration(UnityCatalogProperties props) {
        this.props = props;
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(props.getUri());
        if (props.getToken() != null && !props.getToken().isBlank()) {
            String bearer = "Bearer " + props.getToken();
            apiClient.setRequestInterceptor(b -> b.header("Authorization", bearer));
        }
        this.catalogsApi = new CatalogsApi(apiClient);
        this.schemasApi = new SchemasApi(apiClient);
        this.tablesApi = new TablesApi(apiClient);
        log.info("UnityCatalogIntegration enabled - target {}.{} at {}",
                props.getCatalog(), props.getSchema(), props.getUri());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void registerAsset(Asset asset, Collection<String> labels) {
        if (asset == null || asset.getAssetUri() == null) {
            return;
        }
        if (!isTabular(asset)) {
            log.debug("UC: skipping non-tabular asset {} (serviceType={})",
                    asset.getAssetUri(), asset.getServiceType());
            return;
        }
        try {
            ensureCatalog();
            ensureSchema();
            upsertTable(asset, labels);
        } catch (Exception e) {
            // Never propagate onto the catalog-ingestion path.
            log.warn("UC: failed to publish asset {}: {}", asset.getAssetUri(), e.getMessage());
        }
    }

    private void ensureCatalog() throws ApiException {
        try {
            catalogsApi.getCatalog(props.getCatalog());
        } catch (ApiException notFound) {
            catalogsApi.createCatalog(new CreateCatalog()
                    .name(props.getCatalog())
                    .comment("DLAP-managed catalog (da-catalog)"));
            log.info("UC: created catalog {}", props.getCatalog());
        }
    }

    private void ensureSchema() throws ApiException {
        String fullName = props.getCatalog() + "." + props.getSchema();
        try {
            schemasApi.getSchema(fullName);
        } catch (ApiException notFound) {
            schemasApi.createSchema(new CreateSchema()
                    .name(props.getSchema())
                    .catalogName(props.getCatalog())
                    .comment("DLAP-managed schema"));
            log.info("UC: created schema {}", fullName);
        }
    }

    private void upsertTable(Asset asset, Collection<String> labels) throws ApiException {
        String tableName = sanitize(tableNameFor(asset));
        String fullName = props.getCatalog() + "." + props.getSchema() + "." + tableName;
        try {
            tablesApi.getTable(fullName, Boolean.FALSE, Boolean.FALSE);
            // Already present. Unity Catalog OSS has no in-place property update
            // on the table API yet; recreating would be destructive, so we leave
            // the existing entry. Labels are captured at first registration.
            log.debug("UC: table {} already present; leaving as-is", fullName);
            return;
        } catch (ApiException notFound) {
            // fall through to create
        }
        CreateTable create = new CreateTable()
                .name(tableName)
                .catalogName(props.getCatalog())
                .schemaName(props.getSchema())
                .tableType(TableType.EXTERNAL)
                .dataSourceFormat(formatFor(asset))
                .storageLocation(storageLocationFor(asset, tableName))
                .columns(columnsFor(asset))
                .comment(asset.getDescription())
                .properties(propertiesFor(asset, labels));
        tablesApi.createTable(create);
        log.info("UC: registered table {} -> {}", fullName, asset.getAssetUri());
    }

    /** Only warehouse/table-shaped assets become UC tables. */
    private boolean isTabular(Asset asset) {
        String svc = asset.getServiceType() == null ? "" : asset.getServiceType().toUpperCase();
        String type = asset.getAssetType() == null ? "" : asset.getAssetType().toUpperCase();
        return svc.endsWith("_TABLE") || svc.contains("BIGQUERY") || svc.contains("REDSHIFT")
                || svc.contains("SQL") || type.equals("TABLE");
    }

    private String tableNameFor(Asset asset) {
        if (asset.getAssetName() != null && !asset.getAssetName().isBlank()) {
            return asset.getAssetName();
        }
        String uri = asset.getAssetUri();
        int slash = uri.lastIndexOf('/');
        return slash >= 0 && slash < uri.length() - 1 ? uri.substring(slash + 1) : uri;
    }

    /** UC identifiers allow letters, digits, underscore. */
    private String sanitize(String raw) {
        String s = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        return s.isBlank() ? "asset" : s;
    }

    /**
     * Unity Catalog validates the {@code storage_location} URI scheme and only
     * accepts object-storage / file schemes. DLAP warehouse assets (e.g.
     * BigQuery {@code bq://}, Azure {@code https://...blob...}) have no such
     * URI, so for unsupported schemes we register a synthetic {@code file://}
     * location and keep the real asset URI in the {@code dlap.assetUri}
     * property. Object-storage assets (s3/gs/abfs) pass their URI straight
     * through.
     */
    private String storageLocationFor(Asset asset, String tableName) {
        String uri = asset.getAssetUri();
        String lower = uri.toLowerCase();
        if (lower.startsWith("s3://") || lower.startsWith("gs://")
                || lower.startsWith("file://") || lower.startsWith("abfs://")
                || lower.startsWith("abfss://")) {
            return uri;
        }
        return "file:///dlap/" + props.getCatalog() + "/" + props.getSchema() + "/" + tableName;
    }

    private DataSourceFormat formatFor(Asset asset) {
        Map<String, Object> tech = asset.getTechnicalMetadata();
        Object cls = tech != null ? tech.get("classification") : null;
        if (cls != null) {
            switch (cls.toString().toLowerCase()) {
                case "parquet": return DataSourceFormat.PARQUET;
                case "csv":     return DataSourceFormat.CSV;
                case "json":    return DataSourceFormat.JSON;
                case "avro":    return DataSourceFormat.AVRO;
                case "orc":     return DataSourceFormat.ORC;
                default: break;
            }
        }
        return DataSourceFormat.DELTA;
    }

    /**
     * Column schema for the UC table. DLAP discovery rarely captures full
     * column-level schema, and Unity Catalog requires at least one column, so
     * we register a single opaque {@code data} column as a placeholder. Real
     * column mapping can be layered on when discovery surfaces it.
     */
    private List<ColumnInfo> columnsFor(Asset asset) {
        return List.of(new ColumnInfo()
                .name("data")
                .typeName(ColumnTypeName.STRING)
                .typeText("string")
                .typeJson("{\"name\":\"data\",\"type\":\"string\",\"nullable\":true}")
                .position(0)
                .nullable(true)
                .comment("DLAP placeholder column; see properties for asset metadata"));
    }

    private Map<String, String> propertiesFor(Asset asset, Collection<String> labels) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dlap.assetId", asset.getAssetId() != null ? asset.getAssetId().toString() : "");
        p.put("dlap.assetUri", asset.getAssetUri());
        p.put("dlap.cloudProvider", asset.getCloudProvider() != null ? asset.getCloudProvider() : "");
        p.put("dlap.serviceType", asset.getServiceType() != null ? asset.getServiceType() : "");
        if (labels != null && !labels.isEmpty()) {
            p.put("dlap.labels", String.join(",", labels));
        }
        return p;
    }
}
