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

package com.dlap.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// The shared da-protos entities (Asset, Label, …) and their repositories live
// under com.dlap.common; da-catalog's own model/repositories live under
// com.dlap.catalog. Both must be scanned or the EntityManagerFactory fails to
// resolve associations like AssetLineage.downstreamAsset -> Asset.
@SpringBootApplication
@ConfigurationPropertiesScan // Scans for @ConfigurationProperties beans
@EntityScan(basePackages = {"com.dlap.catalog", "com.dlap.common.model.entity"})
@EnableJpaRepositories(basePackages = {"com.dlap.catalog", "com.dlap.common.repository"})
public class DaCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(DaCatalogApplication.class, args);
    }

} 