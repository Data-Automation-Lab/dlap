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

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Explicit DataSource configuration for the 'dev' profile only.
 * This ensures that the dev environment uses a clearly defined DataSource,
 * overriding any other potential DataSource bean definitions or
 * auto-configurations
 * that might be causing conflicts.
 * Also enables JPA repositories and transaction management for the dev profile.
 */
@Configuration
@Profile("dev")
@EnableJpaRepositories(basePackages = {
		"com.dlap.discovery.catalog.model.repository",
		"com.dlap.discovery.common.model.repository",
		"com.dlap.discovery.crawler.model.repository",
		"com.dlap.discovery.log.service.gcp.persistence.repository"
})
@EntityScan(basePackages = {
		"com.dlap.discovery.catalog.model",
		"com.dlap.discovery.common.model",
		"com.dlap.discovery.crawler.model",
		"com.dlap.discovery.log.service.gcp.persistence.entity"
})
@EnableTransactionManagement
public class DevOnlyDataSourceConfig {

	/**
	 * Creates the primary DataSource bean using HikariCP for the 'dev' profile.
	 * Properties are bound from 'spring.datasource' in application-dev.yml.
	 *
	 * @param dataSourceProperties The auto-configured DataSourceProperties.
	 * @return The configured HikariDataSource.
	 */
	@Bean
	@Primary
	@ConfigurationProperties(prefix = "spring.datasource.hikari")
	public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
		HikariDataSource hikariDataSource = dataSourceProperties.initializeDataSourceBuilder()
				.type(HikariDataSource.class).build();
		// additional Hikari-specific configurations can be set here if needed,
		// but most are covered by @ConfigurationProperties("spring.datasource.hikari")
		// e.g., hikariDataSource.setPoolName("MyCustomDevPoolName");

		// Explicitly disable auto-commit to ensure transaction management works
		// correctly
		hikariDataSource.setAutoCommit(false);

		return hikariDataSource;
	}

	// Removed the custom SpringLiquibase bean as CrawlerLiquibaseConfiguration now
	// handles sync/async based on profile.
	// /**
	// * Configures a synchronous SpringLiquibase bean for the 'dev' profile.
	// * This overrides JHipster's default AsyncSpringLiquibase to simplify
	// debugging
	// * potential DataSource lifecycle issues with Liquibase.
	// *
	// * @param dataSource The primary DataSource.
	// * @param liquibaseProperties Auto-configured LiquibaseProperties.
	// * @return Configured SpringLiquibase instance.
	// */
	// @Bean
	// public SpringLiquibase liquibase(DataSource dataSource, LiquibaseProperties
	// liquibaseProperties) {
	// SpringLiquibase liquibase = new SpringLiquibase();
	// liquibase.setDataSource(dataSource);
	// liquibase.setChangeLog(liquibaseProperties.getChangeLog());
	// liquibase.setContexts(liquibaseProperties.getContexts());
	// liquibase.setDefaultSchema(liquibaseProperties.getDefaultSchema());
	// liquibase.setDropFirst(liquibaseProperties.isDropFirst());
	// liquibase.setShouldRun(liquibaseProperties.isEnabled());
	// // liquibase.setLabelFilter(liquibaseProperties.getLabelFilter());
	// // liquibase.setChangeLogParameters(liquibaseProperties.getParameters());
	// // liquibase.setRollbackFile(liquibaseProperties.getRollbackFile());
	// return liquibase;
	// }
}