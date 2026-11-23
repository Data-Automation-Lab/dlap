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

package com.dlap.discovery.catalog.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Base configuration for persistence with Hibernate and Spring Data JPA.
 */
@Configuration
@Profile("!dev")
@EnableTransactionManagement
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
public abstract class PersistenceConfig {

	@Autowired
	private Environment env;

	/**
	 * Creates the entity manager factory.
	 *
	 * @param dataSource The data source
	 * @return The entity manager factory bean
	 */
	@Bean
	public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
		LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
		em.setDataSource(dataSource);
		em.setPackagesToScan(
				"com.dlap.discovery.catalog.model",
				"com.dlap.discovery.common.model",
				"com.dlap.discovery.config.model",
				"com.dlap.discovery.crawler.model",
				"com.dlap.discovery.log.service.gcp.persistence.entity");

		HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
		vendorAdapter.setGenerateDdl(false);
		em.setJpaVendorAdapter(vendorAdapter);

		em.setJpaProperties(additionalJpaProperties());

		return em;
	}

	/**
	 * Creates the transaction manager.
	 *
	 * @param entityManagerFactory The entity manager factory
	 * @return The transaction manager
	 */
	@Bean
	public PlatformTransactionManager transactionManager(
			LocalContainerEntityManagerFactoryBean entityManagerFactory) {
		JpaTransactionManager transactionManager = new JpaTransactionManager();
		transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
		return transactionManager;
	}

	/**
	 * Configures additional JPA properties.
	 *
	 * @return The JPA properties
	 */
	protected Properties additionalJpaProperties() {
		Properties properties = new Properties();

		// 1. DDL Auto Strategy
		// Try the specific key from application-dev.yml, then standard Spring Boot key,
		// then default to "update" (for tests).
		String ddlAuto = env.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto");
		if (ddlAuto == null) {
			ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto", "update");
		}
		properties.setProperty("hibernate.hbm2ddl.auto", ddlAuto);

		// 2. Hibernate Dialect
		// Try specific Hibernate dialect property, then standard Spring Boot
		// database-platform, then default to H2 (for tests).
		String dialect = env.getProperty("spring.jpa.properties.hibernate.dialect");
		if (dialect == null) {
			dialect = env.getProperty("spring.jpa.database-platform");
			if (dialect == null) {
				dialect = "org.hibernate.dialect.H2Dialect"; // Default for tests if no platform specified
			}
		}
		properties.setProperty("hibernate.dialect", dialect);

		// 3. Show SQL
		// Read from standard Spring Boot key, default to "false".
		properties.setProperty("hibernate.show_sql",
				env.getProperty("spring.jpa.show-sql", "false"));

		// 4. Format SQL
		// Read from specific Hibernate property key, default to "false".
		properties.setProperty("hibernate.format_sql",
				env.getProperty("spring.jpa.properties.hibernate.format_sql", "false"));

		// 5. Second Level Cache
		properties.setProperty("hibernate.cache.use_second_level_cache",
				env.getProperty("spring.jpa.properties.hibernate.cache.use_second_level_cache", "false"));

		// 6. Query Cache
		properties.setProperty("hibernate.cache.use_query_cache",
				env.getProperty("spring.jpa.properties.hibernate.cache.use_query_cache", "false"));

		// 7. JDBC Timezone
		properties.setProperty("hibernate.jdbc.time_zone",
				env.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC"));

		// 8. Schema and naming. Spring Boot's own entity-manager factory applies these; this hand-built
		// one (every profile except dev) used to drop them, so under prod Hibernate looked in the wrong
		// schema and expected camelCase column names (validate: "missing column [jobId]").
		copyIfPresent(properties, "hibernate.default_schema",
				env.getProperty("spring.jpa.properties.hibernate.default_schema"));
		copyIfPresent(properties, "hibernate.physical_naming_strategy",
				env.getProperty("spring.jpa.hibernate.naming.physical-strategy"));
		copyIfPresent(properties, "hibernate.implicit_naming_strategy",
				env.getProperty("spring.jpa.hibernate.naming.implicit-strategy"));

		return properties;
	}

	private static void copyIfPresent(Properties properties, String key, String value) {
		if (value != null && !value.isBlank()) {
			properties.setProperty(key, value);
		}
	}
}