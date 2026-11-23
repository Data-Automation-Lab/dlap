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

package com.dlap.discovery.client.rest;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import com.dlap.discovery.client.dto.ConfigDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ConfigUpdate {

    private static final Logger log = LoggerFactory.getLogger(ConfigUpdate.class);

    private static final String CONFIG_FILE_PATH = "src/main/resources/application.properties"; // Change to writable path

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/update-config")
    public synchronized ResponseEntity<String> updateConfig(@Valid @RequestBody ConfigDTO configDTO) {
        log.info("Received config update: {}", configDTO);

        Path propertiesFilePath = Paths.get(CONFIG_FILE_PATH);

        // Ensure the config file exists
        if (!Files.exists(propertiesFilePath)) {
            log.error("Configuration file not found at {}", CONFIG_FILE_PATH);
            return ResponseEntity.status(500).body("Configuration file not found");
        }

        try /*(FileOutputStream output = new FileOutputStream(propertiesFilePath.toFile()))*/ {
            Properties properties = new Properties();

            // Load existing properties
            try (var inputStream = Files.newInputStream(propertiesFilePath)) {
                properties.load(inputStream);
            }

            // Validate ConfigDTO fields
            if (configDTO.getProjectId() == null || configDTO.getProjectId().isEmpty()) {
                return ResponseEntity.badRequest().body("Project ID cannot be null or empty");
            }

            // Update properties with the incoming config
            // properties.setProperty("google.cloud.projectId", configDTO.getProjectId());
            // properties.setProperty("google.cloud.folderId", configDTO.getFolderId());
            // properties.setProperty("google.cloud.searchText", configDTO.getSearchText());
            // properties.setProperty("dg.spider.folderName", configDTO.getFolderName());
            log.info("application.properties set properties. {}",configDTO);

            // Save updated properties
           // properties.store(output, "Updated by ConfigUpdate API");
        } catch (IOException e) {
            log.error("Failed to update configuration file", e);
            return ResponseEntity.status(500).body("Failed to update configuration");
        }

        log.info("Configuration updated successfully");
        return ResponseEntity.ok("Configuration updated successfully");
    }
}
