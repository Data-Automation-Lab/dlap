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

package com.dlap.policyengine.access;

/**
 * In-JVM signal that the persisted policy set changed (create / update /
 * status-change / delete / draft publish). {@code PolicyService} publishes it;
 * {@link AccessPolicyRefreshListener} consumes it after commit to refresh the
 * PDP's AccessPolicy cache immediately, closing the up-to-30s staleness window
 * the ADR-0040 seam described. A no-op for lifecycle-kind policies (the PDP
 * refresh only loads {@code kind: AccessPolicy}).
 *
 * @param reason short cause, for logging only (e.g. {@code "create"})
 */
public record PolicyChangedEvent(String reason) {
}
