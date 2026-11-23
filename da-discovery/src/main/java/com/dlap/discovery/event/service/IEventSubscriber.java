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

package com.dlap.discovery.event.service;

/**
 * Generic interface for event processors.
 * Any service interested in processing specific events should implement this
 * interface.
 * 
 * @param <T> The type of event this processor handles
 */
public interface IEventSubscriber<T> {

    /**
     * Returns true if this processor can process the given event.
     * 
     * @param event The event to check
     * @return true if this processor supports the event, false otherwise
     */
    boolean canProcess(T event);

    /**
     * Process an event.
     * 
     * @param event The event to process
     */
    void process(T event);

    /**
     * Gets the priority of this processor.
     * Lower numbers indicate higher priority.
     * 
     * @return The processor priority
     */
    default int getPriority() {
        return 100; // Default medium priority
    }

    /**
     * Gets a name for this processor (for logging purposes).
     * 
     * @return The processor name
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}