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

package com.dlap.reporting.identity;

/**
 * The subject a read is attributed to.
 *
 * @param subjectId    the DLAP subject the drift join uses: the mapped subject, or the
 *                     principal's {@link PrincipalKeys#key} when the map has no entry
 * @param principalKey the principal in {@link PrincipalKeys#key} form
 * @param mapped       whether an identity map entry supplied {@code subjectId}
 */
public record ResolvedPrincipal(String subjectId, String principalKey, boolean mapped) {
}
