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

package com.dlap.discovery.common.service;

import java.util.HashMap;
import java.util.Map;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.crawler.exception.ErrorCode;

/**
 * Exception thrown when a requested resource cannot be found.
 * This can be any type of resource such as a cloud resource, configuration item, etc.
 */
public class ResourceNotFoundException extends DiscoveryException {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Creates a new ResourceNotFoundException with the default message.
     */
    public ResourceNotFoundException() {
        super(ErrorCode.RESOURCE_NOT_FOUND);
    }
    
    /**
     * Creates a new ResourceNotFoundException with a custom message.
     * 
     * @param message A user-friendly error message
     */
    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
    
    /**
     * Creates a new ResourceNotFoundException for a specific resource type and ID.
     * 
     * @param resourceType The type of resource that wasn't found
     * @param resourceId The ID of the resource that wasn't found
     */
    public ResourceNotFoundException(String resourceType, String resourceId) {
        super(ErrorCode.RESOURCE_NOT_FOUND, 
              String.format("%s with ID %s not found", resourceType, resourceId),
              createDetails(resourceType, resourceId));
    }
    
    /**
     * Creates a new ResourceNotFoundException with a specific resource type, ID and cause.
     * 
     * @param resourceType The type of resource that wasn't found
     * @param resourceId The ID of the resource that wasn't found
     * @param cause The underlying cause of this exception
     */
    public ResourceNotFoundException(String resourceType, String resourceId, Throwable cause) {
        super(ErrorCode.RESOURCE_NOT_FOUND, 
              String.format("%s with ID %s not found", resourceType, resourceId),
              createDetails(resourceType, resourceId),
              cause);
    }
    
    private static Map<String, Object> createDetails(String resourceType, String resourceId) {
        Map<String, Object> details = new HashMap<>();
        details.put("resourceType", resourceType);
        details.put("resourceId", resourceId);
        return details;
    }
} 