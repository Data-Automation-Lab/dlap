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

package com.dlap.discovery.client.admin;

/**
 * Non-secret view of one cloud connection, as listed to the people who may run scans.
 *
 * <p>Returned by {@code GET /api/v1/discovery/connections}. It deliberately carries no
 * connection parameters and no credential material: the Access Model keeps cloud
 * connections an administrator concern, and a scan runner only needs enough to pick a
 * connection and show its name.
 *
 * @param id           connection id issued by da-admin-service
 * @param name         display name chosen by the administrator
 * @param providerType provider type as stored by da-admin-service (AWS, AZURE, GCP, OTHER)
 * @param enabled      false when an administrator disabled the connection
 * @param scannable    true when the connection is enabled and its provider can be scanned
 */
public record AdminConnectionSummary(String id, String name, String providerType, boolean enabled, boolean scannable) {
}
