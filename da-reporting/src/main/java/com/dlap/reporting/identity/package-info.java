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

/**
 * Principal identity map for the Consumed-stage drift join (RPT-03, enforcement-contract.md
 * section 6.1).
 *
 * <p>A cloud audit log names the reader in the provider's own form: an AWS role ARN, a GCP
 * principal email, an Entra object id. The PDP names the same actor by its DLAP subject, the
 * AuthZEN {@code subject.id}. The map in table {@code principal_identity_map} links the two, so a
 * read and the decision that authorized it join on one subject.
 */
package com.dlap.reporting.identity;
