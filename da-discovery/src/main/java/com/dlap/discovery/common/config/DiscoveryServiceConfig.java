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

package com.dlap.discovery.common.config;

import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.catalog.service.impl.CatalogServiceImpl;
import com.dlap.discovery.crawler.model.aws.AwsResource;
import com.dlap.discovery.crawler.model.oracle.OracleResource;
import com.dlap.discovery.crawler.service.IDiscoveryService;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.crawler.service.aws.AWSDiscoveryServiceImpl;
import com.dlap.discovery.crawler.service.aws.AWSResourceCrawler;
import com.dlap.discovery.crawler.service.azure.AzureDiscoveryServiceImpl;
import com.dlap.discovery.crawler.service.gcp.GCPDiscoveryServiceImpl;
import com.dlap.discovery.crawler.service.oracle.OracleDiscoveryServiceImpl;
import com.dlap.discovery.crawler.service.oracle.OracleResourceCrawler;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.aws.AWSLogAnalyzer;
import com.dlap.discovery.log.service.azure.AzureLogAnalyzer;
import com.dlap.discovery.log.service.gcp.GcpLogAnalyzer;
import com.dlap.discovery.log.service.oracle.OracleLogAnalyzer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration for discovery services, Crawlers, log analyzers,
 * and catalog services for following public cloud providers:
 * - AWS
 * - Azure
 * - GCP
 * - Oracle Cloud Infrastructure (OCI)
 */
@Configuration
@ComponentScan(basePackages = { "com.dlap.discovery.sd.", "com.dlap.discovery.crawler.service", "com.dlap.discovery.sd.common" })
@EnableConfigurationProperties
public class DiscoveryServiceConfig {

    /**
     * Creates a map of discovery services keyed by provider name.
     *
     * @param gcpDiscoveryService    The GCP discovery service implementation
     *                               (optional)
     * @param awsDiscoveryService    The AWS discovery service implementation
     *                               (optional)
     * @param azureDiscoveryService  The Azure discovery service implementation
     *                               (optional)
     * @param oracleDiscoveryService The Oracle discovery service implementation
     *                               (optional)
     * @return A map of discovery services
     */
    @Bean
    @Primary
    public Map<String, IDiscoveryService<?>> discoveryServices(
        @Autowired GCPDiscoveryServiceImpl gcpDiscoveryService,
        @Autowired(required = false) AWSDiscoveryServiceImpl awsDiscoveryService,
        @Autowired(required = false) AzureDiscoveryServiceImpl azureDiscoveryService,
        @Autowired(required = false) OracleDiscoveryServiceImpl oracleDiscoveryService
    ) {
        Map<String, IDiscoveryService<?>> services = new HashMap<>();

        // Only add services that are available (non-null)
        if (gcpDiscoveryService != null) {
            services.put("gcp", gcpDiscoveryService);
        }

        if (awsDiscoveryService != null) {
            services.put("aws", awsDiscoveryService);
        }

        if (azureDiscoveryService != null) {
            services.put("azure", azureDiscoveryService);
        }

        if (oracleDiscoveryService != null) {
            services.put("oracle", oracleDiscoveryService);
        }

        if (services.isEmpty()) {
            throw new IllegalStateException("No discovery services available. Please ensure at least one cloud provider is enabled.");
        }

        return services;
    }

    /**
     * Creates a map of log analyzers keyed by provider name.
     *
     * @param gcpLogAnalyzer    The GCP log analyzer (optional)
     * @param awsLogAnalyzer    The AWS log analyzer (optional)
     * @param oracleLogAnalyzer The Oracle log analyzer (optional)
     * @param azureLogAnalyzer  The Azure log analyzer (optional)
     * @return Map of log analyzers
     */
    @Bean
    @Primary
    public Map<String, ILogAnalyzer> logAnalyzers(
        @Autowired(required = false) GcpLogAnalyzer gcpLogAnalyzer,
        @Autowired(required = false) @Qualifier("awsLogAnalyzer") AWSLogAnalyzer awsLogAnalyzer,
        @Autowired(required = false) OracleLogAnalyzer oracleLogAnalyzer,
        @Autowired(required = false) @Qualifier("azureLogAnalyzer") AzureLogAnalyzer azureLogAnalyzer
    ) {
        Map<String, ILogAnalyzer> analyzers = new HashMap<>();

        // Only add analyzers that are available (non-null)
        if (gcpLogAnalyzer != null) {
            analyzers.put("gcp", gcpLogAnalyzer);
        }

        if (awsLogAnalyzer != null) {
            analyzers.put("aws", awsLogAnalyzer);
        }

        if (oracleLogAnalyzer != null) {
            analyzers.put("oracle", oracleLogAnalyzer);
        }

        if (azureLogAnalyzer != null) {
            analyzers.put("azure", azureLogAnalyzer);
        }

        return analyzers;
    }

    /**
     * Creates a map of catalog services keyed by provider name.
     *
     * @param gcpCatalogService    The GCP catalog service
     * @param oracleCatalogService The Oracle catalog service
     * @return A map of catalog services
     */
    @Bean
    @Primary
    public Map<String, ICatalogService> catalogServices(CatalogServiceImpl gcpCatalogService) {
        Map<String, ICatalogService> services = new HashMap<>();
        services.put("gcp", (ICatalogService) gcpCatalogService);
        services.put("aws", (ICatalogService) gcpCatalogService);
        services.put("azure", (ICatalogService) gcpCatalogService);
        services.put("oracle", (ICatalogService) gcpCatalogService);
        return services;
    }

    /**
     * Defines a list of AWS resource Crawlers to be injected into the AWS discovery
     * service.
     *
     * @param awsResourceCrawlers The AWS Resource Crawlers
     * @return List of AWS resource Crawlers
     */
    @Bean(name = "awsResourceCrawlers")
    public List<IResourceCrawler<AwsResource>> awsResourceCrawlers(List<AWSResourceCrawler<AwsResource>> awsResourceCrawlers) {
        return new ArrayList<>(awsResourceCrawlers);
    }

    /**
     * Defines a list of Oracle resource Crawlers to be injected into the Oracle
     * discovery service.
     * Only created when cloud.provider.oci.enabled=true
     *
     * @param objectStorageBucketCrawler The Oracle Object Storage bucket Crawler
     * @return List of Oracle resource Crawlers
     */
    @Bean(name = "oracleResourceCrawlers")
    @ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
    public List<OracleResourceCrawler<OracleResource>> oracleResourceCrawlers(OracleResourceCrawler<OracleResource> oracleResourceCrawler) {
        List<OracleResourceCrawler<OracleResource>> oracleCrawlers = new ArrayList<>();
        oracleCrawlers.add(oracleResourceCrawler);
        // Additional Oracle Crawlers can be added here
        return oracleCrawlers;
    }

    /**
     * Creates a RestTemplate bean for making HTTP requests.
     * Used by job executors like NiFiJobExecutor to interact with external
     * services.
     *
     * @return A new RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        // Jackson 2 for JSON, as before Spring Framework 7 (BOOT-01).
        return Jackson2ClientConverters.restTemplate();
    }
}
