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

package com.dlap.discovery.coverage;

/**
 * The provider refused a configuration read because the collector's identity lacks a permission.
 * The coverage it would have decided is unknown, and the reason names the permission so an
 * operator can grant it (the permission list is in {@code docs/specs/asset-descriptor.md}).
 */
public class MissingPermissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String permission;
    private final String resource;

    /**
     * Creates the exception.
     *
     * @param permission the provider permission, for example {@code cloudtrail:GetEventSelectors}
     * @param resource   the resource the read was for; a resource name, never a credential
     */
    public MissingPermissionException(String permission, String resource) {
        super("missing permission " + permission + " on " + resource);
        this.permission = permission;
        this.resource = resource;
    }

    /**
     * The permission.
     *
     * @return the provider permission name
     */
    public String permission() {
        return permission;
    }

    /**
     * The resource.
     *
     * @return the resource the read was for
     */
    public String resource() {
        return resource;
    }

    /**
     * The coverage reason.
     *
     * @return {@code missing permission <permission> on <resource>}
     */
    public String reason() {
        return getMessage();
    }
}
