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

package com.dlap.autodelete.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO representing total cost savings achieved through deletion operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotalCostSavingsDTO {
    private BigDecimal totalSavingsToDate;
    private BigDecimal estimatedMonthlySavings;
    private BigDecimal savingsThisMonth;
    private BigDecimal savingsThisYear;
    private long totalAssetsDeleted;
    private LocalDateTime lastUpdated;
    private String currency;
    private double averageSavingsPerAsset;
    private double savingsPercentage; // Percentage of original costs saved
} 