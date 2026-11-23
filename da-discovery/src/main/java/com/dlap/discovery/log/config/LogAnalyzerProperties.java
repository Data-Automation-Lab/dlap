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

package com.dlap.discovery.log.config;

import com.dlap.discovery.common.model.ResourceChange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for log analyzers.
 * Maps operations to change types from configuration.
 *
 * <p>Prefix renamed from {@code gcp.log-analyzer} to
 * {@code dlap.log-analyzer} per R-P1-7 (finding N18) - the mapping
 * is cloud-neutral and cannot live behind a GCP-specific prefix.
 */
@Configuration
@ConfigurationProperties(prefix = "dlap.log-analyzer")
public class LogAnalyzerProperties {

    private List<OperationMapping> operationMappings = new ArrayList<>();

    /**
     * Inner class representing an operation to change type mapping.
     */
    public static class OperationMapping {

        private String operation;
        private String changeType;

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public String getChangeType() {
            return changeType;
        }

        public void setChangeType(String changeType) {
            this.changeType = changeType;
        }
    }

    public List<OperationMapping> getOperationMappings() {
        return operationMappings;
    }

    public void setOperationMappings(List<OperationMapping> operationMappings) {
        this.operationMappings = operationMappings;
    }

    /**
     * Builds a map of operation strings to ResourceChange.ChangeType enum values.
     *
     * @return Unmodifiable map of operation strings to change types
     */
    public Map<String, ResourceChange.ChangeType> buildOperationToChangeTypeMap() {
        Map<String, ResourceChange.ChangeType> map = new HashMap<>();

        for (OperationMapping mapping : operationMappings) {
            ResourceChange.ChangeType changeType = ResourceChange.ChangeType.valueOf(mapping.getChangeType());
            map.put(mapping.getOperation(), changeType);
        }

        return Map.copyOf(map);
    }
}
