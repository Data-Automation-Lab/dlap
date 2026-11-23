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

package com.dlap.autolabel.service;

import com.dlap.autolabel.dto.LabelAgentKpis;
import com.dlap.autolabel.dto.RecentLabelActivity;
import com.dlap.autolabel.dto.RuleActivity;
import com.dlap.autolabel.dto.TopAppliedLabel;

import java.util.List;
import java.util.Map;

/**
 * Service interface for SmartLabel Agent dashboard operations.
 */
public interface ILabelAgentDashboardService {
    
    /**
     * Get Key Performance Indicators for the SmartLabel Agent.
     *
     * @return LabelAgentKpis containing dashboard metrics
     */
    LabelAgentKpis getLabelAgentKpis();
    
    /**
     * Get top applied labels with their statistics.
     *
     * @param limit maximum number of labels to return
     * @return List of top applied labels
     */
    List<TopAppliedLabel> getTopAppliedLabels(int limit);
    
    /**
     * Get rule activities and performance metrics.
     *
     * @param limit maximum number of rules to return
     * @return List of rule activities
     */
    List<RuleActivity> getRuleActivities(int limit);
    
    /**
     * Get recent labeling activities.
     *
     * @param limit maximum number of activities to return
     * @return List of recent label activities
     */
    List<RecentLabelActivity> getRecentLabelActivities(int limit);
    
    /**
     * Get recent activities with detailed information for the UI table.
     *
     * @param limit maximum number of activities to return
     * @return List of recent activities with UI-specific fields
     */
    List<Map<String, Object>> getRecentActivitiesDetailed(int limit);
    
    /**
     * Get top applied labels with detailed information for the UI cards.
     *
     * @param limit maximum number of labels to return
     * @return List of top labels with UI-specific fields
     */
    List<Map<String, Object>> getTopLabelsDetailed(int limit);
    
    /**
     * Get rule performance with detailed information for the UI table.
     *
     * @param limit maximum number of rules to return
     * @return List of rule performance with UI-specific fields
     */
    List<Map<String, Object>> getRulePerformance(int limit);
    
    /**
     * Get asset events and labeling activities.
     *
     * @param limit maximum number of events to return
     * @return List of asset events
     */
    List<Map<String, Object>> getAssetEvents(int limit);
    
    /**
     * Get labeling methods and their statistics.
     *
     * @return List of labeling methods statistics
     */
    List<Map<String, Object>> getLabelingMethods();
    
    /**
     * Get labeling status statistics.
     *
     * @return List of labeling status statistics
     */
    List<Map<String, Object>> getLabelingStatus();
    
    /**
     * Trigger a manual labeling run.
     *
     * @return true if the labeling run was triggered successfully
     */
    boolean triggerLabelingRun();
} 