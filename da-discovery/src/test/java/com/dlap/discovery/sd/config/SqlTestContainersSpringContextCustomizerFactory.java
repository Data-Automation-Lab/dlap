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

package com.dlap.discovery.sd.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

public class SqlTestContainersSpringContextCustomizerFactory implements ContextCustomizerFactory {

    private static final Logger logger = LoggerFactory.getLogger(SqlTestContainersSpringContextCustomizerFactory.class);

    // Using a static instance to share across context reloads
    private static ISqlTestContainer prodTestContainer;
    private static boolean initialized = false;

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        EmbeddedSQL sqlAnnotation = AnnotatedElementUtils.findMergedAnnotation(testClass, EmbeddedSQL.class);
        if (sqlAnnotation != null) {
            logger.debug("detected the EmbeddedSQL annotation on class {}", testClass.getName());
            return new SqlTestContainersContextCustomizer();
        }
        return null;
    }

    private static class SqlTestContainersContextCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            TestPropertyValues testValues = TestPropertyValues.empty();

            synchronized (SqlTestContainersSpringContextCustomizerFactory.class) {
                if (!initialized) {
                    logger.info("Initializing SQL test container");

                    try {
                        String containerClassName = this.getClass().getPackageName()
                                .replace("$SqlTestContainersContextCustomizer", "") + ".MysqlTestContainer";
                        Class<? extends ISqlTestContainer> containerClass = (Class<? extends ISqlTestContainer>) Class
                                .forName(containerClassName);

                        prodTestContainer = beanFactory.createBean(containerClass);
                        prodTestContainer.afterPropertiesSet(); // Initialize and start container
                        beanFactory.registerSingleton(containerClass.getName(), prodTestContainer);

                        initialized = true;
                        logger.info("SQL test container initialized successfully");
                    } catch (ClassNotFoundException e) {
                        logger.error("Could not find test container class", e);
                        throw new RuntimeException("Failed to create test container", e);
                    } catch (Exception e) {
                        logger.error("Failed to start test container", e);
                        throw new RuntimeException("Failed to start test container", e);
                    }
                } else {
                    logger.info("Reusing existing SQL test container");
                }
            }

            if (prodTestContainer != null && prodTestContainer.getTestContainer() != null) {
                // Configure data source properties
                testValues = testValues.and(
                        "spring.datasource.url=" +
                                prodTestContainer.getTestContainer().getJdbcUrl() +
                                "?useUnicode=true&characterEncoding=utf8&useSSL=false&useLegacyDatetimeCode=false&createDatabaseIfNotExist=true");
                testValues = testValues
                        .and("spring.datasource.username=" + prodTestContainer.getTestContainer().getUsername());
                testValues = testValues
                        .and("spring.datasource.password=" + prodTestContainer.getTestContainer().getPassword());
                testValues = testValues
                        .and("spring.datasource.driver-class-name=" + prodTestContainer.getDriverClassName());

                // Disable Testcontainers auto-configuration since we're handling it manually
                testValues = testValues.and("spring.test.database.replace=none");

                testValues.applyTo(context);
            } else {
                logger.error("Test container is null or not properly initialized");
                throw new IllegalStateException("Test container is null or not properly initialized");
            }
        }
    }
}
