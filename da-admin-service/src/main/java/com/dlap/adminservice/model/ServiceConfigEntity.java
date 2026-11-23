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

package com.dlap.adminservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity for service configuration management.
 */
@Entity
@Table(name = "service_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceConfigEntity {
    
    @Id
    @Column(nullable = false, unique = true, length = 100)
    private String serviceId;
    
    @Column(length = 255)
    private String displayName;
    
    @Column(length = 1000)
    private String description;
    
    @Column(length = 50)
    private String version;
    
    @Column(length = 500)
    private String endpoint;
    
    @Builder.Default
    private boolean enabled = true;
    
} 