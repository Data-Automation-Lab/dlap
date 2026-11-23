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

package com.dlap.autolabel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing rule activity and performance metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleActivity {
    
    /**
     * Name of the rule
     */
    private String ruleName;
    
    /**
     * Category of the rule (e.g., PII, Financial, Customer Data)
     */
    private String category;
    
    /**
     * Number of times the rule was triggered
     */
    private Integer hitCount;
    
    /**
     * Success rate percentage
     */
    private Double successRate;
    
    /**
     * When the rule was last triggered
     */
    private String lastTriggered;
    
    /**
     * Number of assets affected by this rule
     */
    private Integer assetsAffected;
} 