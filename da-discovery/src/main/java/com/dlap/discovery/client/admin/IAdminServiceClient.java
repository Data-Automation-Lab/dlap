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
 * Contract for resolving a {@code cloudConnectionId} (issued by
 * {@code da-admin-service}) into the cloud-account credentials and metadata
 * needed to drive a discovery scan.
 *
 * <p>R-P0-2 / finding N8: extracted from the previously-nested
 * {@code MockAdminServiceClient} inside {@code DiscoveryJobController}.
 * DISC-01: exactly one implementation is wired for any configuration (see
 * {@link AdminClientMode}): {@link HttpAdminServiceClient}, which reads
 * {@code /api/v1/admin/cloud-connections} from {@code da-admin-service} over a
 * service-account token, on every profile except {@code dev} and {@code test}; and
 * the in-process {@link MockAdminServiceClient} on those two.
 */
public interface IAdminServiceClient {

    /**
     * Resolve a cloud-connection id into the account + provider + display-name
     * triple used by the discovery pipeline.
     *
     * @param connectionId opaque id issued by {@code da-admin-service}; not null.
     * @return connection details; never null.
     * @throws AdminServiceException when the connection is unknown, disabled, not
     *         scannable, or da-admin-service cannot be reached. The HTTP implementation
     *         never returns a stand-in for a missing connection.
     */
    AdminConnectionDetails getConnectionDetails(String connectionId);

    /**
     * List the cloud connections a scan can be started for.
     *
     * <p>Defaults to unsupported so that a single-method lambda implementing
     * {@link #getConnectionDetails(String)} stays valid; both shipped implementations
     * override it.
     *
     * @return non-secret summaries of every connection known to da-admin-service,
     *         including disabled ones (flagged, not hidden); never null.
     * @throws AdminServiceException when da-admin-service cannot be reached or refuses the call.
     */
    default java.util.List<AdminConnectionSummary> listConnections() {
        throw new UnsupportedOperationException(
                "listConnections is not supported by " + getClass().getSimpleName());
    }
}
