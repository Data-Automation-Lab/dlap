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

package com.dlap.discovery.job.executable;

/**
 * Defines possible actions that can be performed with NiFi components.
 */
public enum NiFiAction {
    /**
     * Start a processor.
     */
    START_PROCESSOR,

    /**
     * Stop a processor.
     */
    STOP_PROCESSOR,

    /**
     * Enable a controller service.
     */
    ENABLE_CONTROLLER_SERVICE,

    /**
     * Disable a controller service.
     */
    DISABLE_CONTROLLER_SERVICE,

    /**
     * Start a process group.
     */
    START_PROCESS_GROUP,

    /**
     * Stop a process group.
     */
    STOP_PROCESS_GROUP,

    /**
     * Send data to an input port.
     */
    SEND_TO_INPUT_PORT,

    /**
     * Receive data from an output port.
     */
    RECEIVE_FROM_OUTPUT_PORT
}