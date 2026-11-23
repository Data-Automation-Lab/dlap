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

package com.dlap.discovery.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
// ADDED import for EntityScan
import org.springframework.boot.persistence.autoconfigure.EntityScan;
// ADDED imports for AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
// ADDED import for TestPropertySource
import org.springframework.test.context.TestPropertySource;

import com.dlap.discovery.application.DADiscoveryAgent;
import com.dlap.discovery.crawler.config.CrawlerCacheConfiguration;
import com.dlap.discovery.sd.config.AsyncSyncConfiguration;
import com.dlap.discovery.sd.config.TestDatabaseConfiguration;
import com.dlap.discovery.sd.config.TestDiscoveryConfiguration;
import com.dlap.discovery.sd.config.TestSecurityConfiguration;
import com.dlap.discovery.sd.web.rest.errors.TestSecurityConfig;

/**
 * Base composite annotation for integration tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = {
		DADiscoveryAgent.class,
		AsyncSyncConfiguration.class,
		TestSecurityConfiguration.class,
		TestDatabaseConfiguration.class,
		TestSecurityConfig.class,
		TestDiscoveryConfiguration.class,
		CrawlerCacheConfiguration.class,

		// ADDED Explicit AutoConfigurations
		DataSourceAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		DataJpaRepositoriesAutoConfiguration.class
})
// Modified EntityScan to scan base package
@EntityScan("com.dlap.discovery.sd")
// ADDED property source to disable liquibase
@TestPropertySource(properties = {
		"spring.liquibase.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// // REMOVED
public @interface IntegrationTest {
}
//
