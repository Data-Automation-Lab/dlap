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

package com.dlap.discovery.crawler.service;

import java.util.Map;
import java.util.Set;

/**
 * Interface for managing resource permissions.
 */
public interface IResourcePermissionService {
    /**
     * Grants permissions to a resource.
     * @param resourceId Resource identifier
     * @param permissions Permissions to grant (principal to permission)
     */
    void grantPermissions(String resourceId, Map<String, String> permissions);
    
    /**
     * Revokes permissions from a resource.
     * @param resourceId Resource identifier
     * @param principals Principals to revoke permissions from
     */
    void revokePermissions(String resourceId, Set<String> principals);
    
    /**
     * Gets all permissions for a resource.
     * @param resourceId Resource identifier
     * @return Resource permissions (principal to permission)
     */
    Map<String, String> getPermissions(String resourceId);
    
    /**
     * Checks if a principal has a specific permission on a resource.
     * @param resourceId Resource identifier
     * @param principal Principal to check
     * @param permission Permission to check
     * @return true if the principal has the permission
     */
    boolean hasPermission(String resourceId, String principal, String permission);
} 