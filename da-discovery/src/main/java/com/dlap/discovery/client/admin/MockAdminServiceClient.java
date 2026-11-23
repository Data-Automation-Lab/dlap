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

import com.dlap.discovery.common.model.enums.CloudProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-only stand-in for the real admin-service connection lookup.
 *
 * <p>R-P0-2 / finding N8: replaces the previously-nested
 * {@code DiscoveryJobController.MockAdminServiceClient} that was instantiated
 * unconditionally in production paths. This bean only exists when the
 * {@code dev} or {@code test} Spring profile is active and
 * {@value AdminClientMode#PROPERTY} is not {@code http}; on every other profile
 * {@link HttpAdminServiceClient} takes its place (DISC-01), so a production
 * deployment can never scan a stand-in account.
 *
 * <p>Recognised connection ids are the well-known dev shortcuts
 * ({@code mock-gcp-conn-id}, {@code mock-aws-conn-id}, etc.). Unknown ids
 * fall through to a GCP placeholder so the demo flow never null-pointers,
 * with a {@code WARN} log so the fallthrough is visible in test output. To scan
 * the connections an administrator registered, run the dev stack with
 * {@code dlap.discovery.admin-client.mode=http}.
 */
@Component
@Profile({"dev", "test"})
@Conditional(AdminClientMode.MockSelected.class)
public class MockAdminServiceClient implements IAdminServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MockAdminServiceClient.class);

    /**
     * Lists the one GCP mock connection. The dev stack enables only the GCP provider, so the
     * AWS, Azure and OCI shortcuts are resolvable by id but are not offered for scanning.
     */
    @Override
    public List<AdminConnectionSummary> listConnections() {
        return List.of(new AdminConnectionSummary("mock-gcp-conn-id", "Mock GCP Connection", "GCP", true, true));
    }

    @Override
    public AdminConnectionDetails getConnectionDetails(String connectionId) {
        log.warn("MOCK admin-service lookup for cloudConnectionId={} (dev profile only); "
                + "the real Feign-backed client lands in milestone M1.", connectionId);
        if ("mock-gcp-conn-id".equals(connectionId)) {
            return new AdminConnectionDetails("mock-gcp-project-dlap",
                    CloudProvider.GCP, "Mock GCP Connection");
        }
        if ("mock-aws-conn-id".equals(connectionId)) {
            return new AdminConnectionDetails("123456789012",
                    CloudProvider.AWS, "Mock AWS Connection");
        }
        if ("mock-azure-conn-id".equals(connectionId)) {
            return new AdminConnectionDetails("mock-azure-sub-guid",
                    CloudProvider.AZURE, "Mock Azure Connection");
        }
        if ("mock-oci-conn-id".equals(connectionId)) {
            return new AdminConnectionDetails("ocid1.tenancy.oc1..mockuniqueid",
                    CloudProvider.OCI, "Mock OCI Connection");
        }
        log.warn("Falling back to mock GCP connection for unrecognised id: {}", connectionId);
        return new AdminConnectionDetails("fallback-gcp-account-id",
                CloudProvider.GCP, "Fallback Mock GCP Connection");
    }
}
