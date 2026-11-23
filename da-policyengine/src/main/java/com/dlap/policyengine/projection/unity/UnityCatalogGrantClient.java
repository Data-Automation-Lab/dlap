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

package com.dlap.policyengine.projection.unity;

/**
 * Minimal client seam for applying Unity Catalog privilege changes. The Unity
 * Catalog OSS Java client ({@code io.unitycatalog:unitycatalog-client}, used by
 * da-catalog's metastore sink, ADR-0029) has no mature GRANT/REVOKE surface, so
 * push-down grants are expressed as UC SQL statements executed against a UC SQL
 * endpoint. This interface abstracts that execution so the sink stays testable
 * and DLAP does not hard-depend on a live UC.
 *
 * <p>Offline (no UC credentials), the {@link StubUnityCatalogGrantClient}
 * records the statements instead of sending them - mirroring how the existing UC
 * integration and the offline RDS/Glue stubs behave when live infrastructure is
 * absent. A deployment supplies a real client bean (JDBC to a UC SQL endpoint,
 * or the UC REST permissions API) to actually enforce.
 */
public interface UnityCatalogGrantClient {

    /**
     * Execute one Unity Catalog {@code GRANT}/{@code REVOKE} statement.
     *
     * @param statement a complete UC SQL grant statement
     */
    void execute(String statement);

    /** @return true when a live UC endpoint is wired. */
    default boolean isLive() {
        return false;
    }
}
