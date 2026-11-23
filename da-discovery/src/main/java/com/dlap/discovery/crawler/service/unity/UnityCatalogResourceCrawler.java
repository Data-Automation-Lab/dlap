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

package com.dlap.discovery.crawler.service.unity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.dlap.common.assetid.AssetKind;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.config.UnityCatalogDiscoveryProperties;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.unity.UnityCatalogResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;

import io.unitycatalog.client.ApiClient;
import io.unitycatalog.client.ApiException;
import io.unitycatalog.client.api.CatalogsApi;
import io.unitycatalog.client.api.SchemasApi;
import io.unitycatalog.client.api.TablesApi;
import io.unitycatalog.client.model.CatalogInfo;
import io.unitycatalog.client.model.ListCatalogsResponse;
import io.unitycatalog.client.model.ListSchemasResponse;
import io.unitycatalog.client.model.ListTablesResponse;
import io.unitycatalog.client.model.SchemaInfo;
import io.unitycatalog.client.model.TableInfo;

import jakarta.annotation.PostConstruct;

/**
 * Discovers Unity Catalog tables asynchronously and publishes them as
 * {@link ResourceEvent}s onto the discovery&#8594;catalog spine (UC-5, ADR-0029).
 *
 * <p>Walks the Unity Catalog hierarchy: catalogs &#8594; schemas &#8594; tables,
 * building one {@link UnityCatalogResource} per table (keyed on the
 * {@code catalog.schema.table} three-level name) and emitting a {@code CREATE}
 * event. da-catalog ingests these into {@code Asset}, making Unity Catalog
 * tables first-class DLAP assets.
 *
 * <p>The {@code io.unitycatalog:unitycatalog-client:0.5.0} client exposes list
 * methods ({@code CatalogsApi.listCatalogs}, {@code SchemasApi.listSchemas},
 * {@code TablesApi.listTables}), each with {@code nextPageToken} pagination, so
 * no raw REST fallback is needed. This crawler consumes those directly and
 * pages fully through each level.
 *
 * <p>Active only when {@code cloud.provider.unity.enabled=true}. Off by default.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.unity.enabled", havingValue = "true", matchIfMissing = false)
public class UnityCatalogResourceCrawler extends AbstractResourceCrawler<UnityCatalogResource> {

    private static final Logger log = LoggerFactory.getLogger(UnityCatalogResourceCrawler.class);

    private final UnityCatalogDiscoveryProperties props;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public UnityCatalogResourceCrawler(UnityCatalogDiscoveryProperties props,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate) {
        super(new ArrayList<>());
        this.props = Objects.requireNonNull(props, "UnityCatalogDiscoveryProperties cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
    }

    /**
     * Registers the Unity Catalog table resource type from the hierarchy registry.
     * Without a supported type the crawler is skipped by {@code CrawlerRegistryConfig}.
     */
    @PostConstruct
    public void initSupportedTypes() {
        ResourceType tableType = hierarchyRegistry.getResourceType(UnityCatalogResource.TABLE_TYPE_ID);
        if (tableType != null && !getSupportedResourceTypes().contains(tableType)) {
            addSupportedType(tableType);
            log.info("UnityCatalogResourceCrawler initialized for type: {}", UnityCatalogResource.TABLE_TYPE_ID);
        } else if (tableType == null) {
            log.error("ResourceType record not found for ID: {}. UnityCatalogResourceCrawler will not register.",
                    UnityCatalogResource.TABLE_TYPE_ID);
        }
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.UNITY;
    }

    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing Unity Catalog discovery for job: {}", job != null ? job.getJobId() : null);
        // Unity Catalog auth is a bearer token from configuration; no per-job
        // credential preparation is required.
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous Unity Catalog table discovery (metastore: {})", props.getUri());
        performActualDiscovery(accountId, context);
    }

    /**
     * Lists Unity Catalog catalogs, schemas and tables and publishes a resource
     * event per table. Runs inline on the caller's discovery-worker thread.
     *
     * <p>Deliberately NOT {@code @Async}: an @Async method forces a CGLIB proxy on
     * this bean, and {@code CrawlerRegistryConfig} then reads the proxy's empty
     * supported-types field (the {@code final getSupportedResourceTypes()} can't
     * delegate to the target where @PostConstruct populated it) and skips
     * registration. The discovery job framework already invokes crawlers on a
     * worker thread, so inline execution is correct.
     *
     * @param accountId The account/metastore identifier used as the event account id.
     * @param context   The discovery context (unused; filtering is not yet supported).
     */
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        log.info("Starting Unity Catalog table discovery (metastore: {})", props.getUri());
        int discoveredCount = 0;

        try {
            ResourceType tableType = findSupportedType(UnityCatalogResource.TABLE_TYPE_ID);
            if (tableType == null) {
                log.error("ResourceType {} not available; cannot discover Unity Catalog tables.",
                        UnityCatalogResource.TABLE_TYPE_ID);
                return;
            }

            ApiClient apiClient = buildApiClient();
            CatalogsApi catalogsApi = new CatalogsApi(apiClient);
            SchemasApi schemasApi = new SchemasApi(apiClient);
            TablesApi tablesApi = new TablesApi(apiClient);

            for (CatalogInfo catalog : listCatalogs(catalogsApi)) {
                String catalogName = catalog.getName();
                if (catalogName == null) {
                    continue;
                }
                for (SchemaInfo schema : listSchemas(schemasApi, catalogName)) {
                    String schemaName = schema.getName();
                    if (schemaName == null) {
                        continue;
                    }
                    for (TableInfo table : listTables(tablesApi, catalogName, schemaName)) {
                        UnityCatalogResource resource = createResourceFromTable(table, catalogName, schemaName,
                                tableType, accountId);
                        if (resource != null) {
                            publishResourceEvent(accountId, resource, ChangeType.CREATE);
                            discoveredCount++;
                            log.debug("Published event for Unity Catalog table: {}", resource.getUri());
                        }
                    }
                }
            }

            log.info("Completed async Unity Catalog table discovery. Published {} resources.", discoveredCount);
        } catch (ApiException e) {
            log.error("Unity Catalog API error during table discovery (metastore {}): {} (code {})",
                    props.getUri(), e.getMessage(), e.getCode(), e);
        } catch (Exception e) {
            log.error("Error during async Unity Catalog table discovery (metastore {}): {}",
                    props.getUri(), e.getMessage(), e);
        }
    }

    /**
     * Builds a Unity Catalog {@link ApiClient} from configuration, mirroring the
     * da-catalog sink: base URI plus an optional bearer-token interceptor.
     *
     * @return a configured ApiClient
     */
    protected ApiClient buildApiClient() {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(props.getUri());
        String token = props.getToken();
        if (token != null && !token.isBlank()) {
            String bearer = "Bearer " + token;
            apiClient.setRequestInterceptor(b -> b.header("Authorization", bearer));
        }
        return apiClient;
    }

    /** Pages fully through all catalogs. */
    private List<CatalogInfo> listCatalogs(CatalogsApi catalogsApi) throws ApiException {
        List<CatalogInfo> all = new ArrayList<>();
        String pageToken = null;
        do {
            ListCatalogsResponse response = catalogsApi.listCatalogs(pageToken, props.getPageSize());
            if (response.getCatalogs() != null) {
                all.addAll(response.getCatalogs());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return all;
    }

    /** Pages fully through all schemas in a catalog. */
    private List<SchemaInfo> listSchemas(SchemasApi schemasApi, String catalogName) throws ApiException {
        List<SchemaInfo> all = new ArrayList<>();
        String pageToken = null;
        do {
            ListSchemasResponse response = schemasApi.listSchemas(catalogName, props.getPageSize(), pageToken);
            if (response.getSchemas() != null) {
                all.addAll(response.getSchemas());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return all;
    }

    /** Pages fully through all tables in a schema. */
    private List<TableInfo> listTables(TablesApi tablesApi, String catalogName, String schemaName)
            throws ApiException {
        List<TableInfo> all = new ArrayList<>();
        String pageToken = null;
        do {
            ListTablesResponse response = tablesApi.listTables(catalogName, schemaName, props.getPageSize(),
                    pageToken);
            if (response.getTables() != null) {
                all.addAll(response.getTables());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return all;
    }

    private ResourceType findSupportedType(String typeId) {
        for (ResourceType type : getSupportedResourceTypes()) {
            if (typeId.equals(type.id())) {
                return type;
            }
        }
        log.warn("ResourceType not found in supported types: {}", typeId);
        return null;
    }

    /**
     * Maps a Unity Catalog {@link TableInfo} onto a {@link UnityCatalogResource}.
     *
     * @param table       the Unity Catalog table metadata
     * @param catalogName the parent catalog name
     * @param schemaName  the parent schema name
     * @param rt          the resolved resource type
     * @param accountId   the account/metastore id
     * @return the mapped resource, or {@code null} if the table name is missing
     */
    protected UnityCatalogResource createResourceFromTable(TableInfo table, String catalogName, String schemaName,
            ResourceType rt, String accountId) {
        String tableName = table.getName();
        if (tableName == null) {
            log.warn("Skipping Unity Catalog table with null name in {}.{}", catalogName, schemaName);
            return null;
        }

        UnityCatalogResource resource = new UnityCatalogResource(rt, catalogName, schemaName, tableName);
        resource.setAccountId(accountId);
        // Canonical id (ADR-0047): uc://{metastore}/{catalog}/{schema}/{table}. A three-part name is
        // unique only inside a metastore, and the connection's account id is the metastore. Without
        // one the model's three-level name stays, and the table reaches the spine unresolved.
        CrawlerAssetIds.assignBuilt(resource, AssetKind.UNITY_TABLE, accountId, catalogName, schemaName, tableName);
        resource.setDescription(table.getComment());
        resource.setLastDiscoveredAt(Instant.now());

        if (table.getCreatedAt() != null) {
            resource.setCreatedAt(Instant.ofEpochMilli(table.getCreatedAt()));
        }
        if (table.getUpdatedAt() != null) {
            resource.setUpdatedAt(Instant.ofEpochMilli(table.getUpdatedAt()));
        } else {
            resource.setUpdatedAt(Instant.now());
        }

        if (table.getProperties() != null) {
            resource.setTags(table.getProperties());
        }

        // Capture the raw Unity Catalog table facts as technical metadata.
        addMeta(resource, "catalogName", catalogName);
        addMeta(resource, "schemaName", schemaName);
        addMeta(resource, "tableName", tableName);
        addMeta(resource, "tableId", table.getTableId());
        addMeta(resource, "tableType", table.getTableType() != null ? table.getTableType().getValue() : null);
        addMeta(resource, "dataSourceFormat",
                table.getDataSourceFormat() != null ? table.getDataSourceFormat().getValue() : null);
        addMeta(resource, "storageLocation", table.getStorageLocation());
        addMeta(resource, "owner", table.getOwner());

        return resource;
    }

    private void addMeta(UnityCatalogResource resource, String key, String value) {
        if (value != null && !value.isBlank()) {
            resource.getTechnicalMetadata().put(key, value);
        }
    }

    /**
     * Constructs and publishes a {@link ResourceEvent} for a discovered table.
     * Keyed on the resource id (the {@code catalog.schema.table} name) so events
     * for the same table land on the same partition.
     *
     * @param accountId  the account/metastore id
     * @param resource   the discovered Unity Catalog table
     * @param changeType the change type (typically CREATE)
     */
    protected void publishResourceEvent(String accountId, UnityCatalogResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(CloudProvider.UNITY.toString(), accountId, changeType, resource);
            log.debug("Publishing resource event: {}", event);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for Unity Catalog table {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }
}
