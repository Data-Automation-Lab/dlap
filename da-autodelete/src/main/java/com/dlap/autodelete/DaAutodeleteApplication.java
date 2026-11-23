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

package com.dlap.autodelete;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EnableMethodSecurity // For @PreAuthorize annotations
@EnableAsync // If async operations are needed
// M5: hard-delete sweeper runs on cron in DeletionServiceImpl.processDueHardDeletes.
@EnableScheduling
@EnableFeignClients(basePackages = "com.dlap.autodelete.client")
public class DaAutodeleteApplication {

    public static void main(String[] args) {
        SpringApplication.run(DaAutodeleteApplication.class, args);
    }

    @Bean
    public OpenAPI customOpenAPI(@Value("${spring.application.name:DLAP AutoDelete Service}") String appName,
                               @Value("${spring.application.description:API for AutoDelete Service}") String appDescription,
                               @Value("${spring.application.version:0.0.1-SNAPSHOT}") String appVersion) {
        return new OpenAPI()
                .info(new Info()
                        .title(appName)
                        .version(appVersion)
                        .description(appDescription)
                        .termsOfService("http://swagger.io/terms/") // Placeholder
                        .license(new License().name("Apache 2.0").url("http://springdoc.org"))); // Placeholder
    }
} 