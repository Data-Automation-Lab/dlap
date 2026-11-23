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
 * Status enumeration for policy drafts supporting complete workflow lifecycle.
 * Tracks the draft from creation through approval and publication.
 */
public enum PolicyDraftStatus {
    /**
     * Draft has been created but not yet submitted for review.
     */
    CREATED,
    
    /**
     * Draft has been submitted and is awaiting review.
     */
    SUBMITTED,
    
    /**
     * Draft is currently under review by approvers.
     */
    UNDER_REVIEW,
    
    /**
     * Draft requires changes based on reviewer feedback.
     */
    REQUIRES_CHANGES,
    
    /**
     * Draft has been approved and is ready for publication.
     */
    APPROVED,
    
    /**
     * Draft has been rejected and will not be published.
     */
    REJECTED,
    
    /**
     * Draft has been published as an active policy.
     */
    PUBLISHED,
    
    /**
     * Draft has been archived (no longer active).
     */
    ARCHIVED
} 