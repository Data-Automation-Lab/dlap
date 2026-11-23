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

package com.dlap.policyengine.model;

/**
 * Enumeration representing different types of events that can occur in the DLAP platform.
 */
public enum EventType {
    // Policy-related events
    /**
     * Policy violation detected
     */
    POLICY_VIOLATION,
    
    /**
     * Policy evaluation completed
     */
    POLICY_EVALUATION_COMPLETED,
    
    /**
     * Policy applied to assets
     */
    POLICY_APPLIED,
    
    /**
     * Policy rule triggered
     */
    POLICY_RULE_TRIGGERED,
    
    // Asset-related events
    /**
     * New asset discovered
     */
    ASSET_DISCOVERED,
    
    /**
     * Asset metadata changed
     */
    ASSET_METADATA_CHANGED,
    
    /**
     * Asset classification changed
     */
    ASSET_CLASSIFICATION_CHANGED,
    
    /**
     * Asset access pattern detected
     */
    ASSET_ACCESS_DETECTED,
    
    // Compliance-related events
    /**
     * Compliance violation detected
     */
    COMPLIANCE_VIOLATION,
    
    /**
     * Compliance scan completed
     */
    COMPLIANCE_SCAN_COMPLETED,
    
    /**
     * Regulatory requirement changed
     */
    REGULATORY_CHANGE,
    
    // Data lifecycle events
    /**
     * Asset scheduled for archival
     */
    ASSET_ARCHIVAL_SCHEDULED,
    
    /**
     * Asset archival completed
     */
    ASSET_ARCHIVAL_COMPLETED,
    
    /**
     * Asset deletion scheduled
     */
    ASSET_DELETION_SCHEDULED,
    
    /**
     * Asset deletion completed
     */
    ASSET_DELETION_COMPLETED,
    
    // System events
    /**
     * System alert generated
     */
    SYSTEM_ALERT,
    
    /**
     * Service health check failed
     */
    SERVICE_HEALTH_ISSUE,
    
    /**
     * Data quality issue detected
     */
    DATA_QUALITY_ISSUE,
    
    /**
     * Security incident detected
     */
    SECURITY_INCIDENT,
    
    // User activity events
    /**
     * User access pattern anomaly
     */
    USER_ACCESS_ANOMALY,
    
    /**
     * Unauthorized access attempt
     */
    UNAUTHORIZED_ACCESS_ATTEMPT,
    
    // Generic event type for custom events
    /**
     * Custom event type
     */
    CUSTOM_EVENT
} 