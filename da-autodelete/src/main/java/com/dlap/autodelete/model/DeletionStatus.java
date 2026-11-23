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

package com.dlap.autodelete.model;

public enum DeletionStatus {
    PENDING_APPROVAL, // Task is awaiting approval before execution
    APPROVED,         // Task has been approved and is ready for processing
    REJECTED,         // Task has been rejected and will not be processed
    SUBMITTED,        // Task submitted directly (no approval needed) or post-approval, ready for queue
    QUEUED,           // Task is in the processing queue
    IN_PROGRESS,      // Task execution is actively in progress
    COMPLETED,        // Task completed successfully (all assets processed)
    PARTIALLY_COMPLETED, // Task completed, but some assets failed to delete
    FAILED,           // Task failed entirely or due to a critical error
    CANCELLED         // Task was cancelled before completion
} 