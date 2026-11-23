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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.policyengine.projection.CompiledAccessPolicy;
import com.dlap.policyengine.projection.GrantIntent;
import com.dlap.policyengine.projection.PolicyProjectionSink;

/**
 * Unity Catalog {@link PolicyProjectionSink} (ADR-0040 push-down mode, Q-022).
 * Translates a compiled AccessPolicy into Unity Catalog {@code GRANT}/{@code REVOKE}
 * statements and applies them through a {@link UnityCatalogGrantClient}.
 *
 * <p>Registered only when {@code dlap.policy.projection.unity.enabled=true}
 * (wired by {@code UnityCatalogProjectionConfiguration}); it then takes the place
 * of the default {@code LoggingPolicyProjectionSink}. Offline, the injected
 * {@link StubUnityCatalogGrantClient} records the statements rather than sending
 * them, so the SPI + translation + wiring are exercised without a live UC.
 *
 * <p><b>Statement shape.</b> Each {@link GrantIntent} becomes:
 * <pre>{@code GRANT <PRIVILEGE> ON <SECURABLE> TO `<principal>`}</pre>
 * (or {@code REVOKE ... FROM ...}). An AccessPolicy {@code effect: deny} arrives
 * as revoke-intents, so a deny projects as a UC {@code REVOKE}. Securables are
 * either concrete asset URIs or {@code label:<LABEL>} tokens, which map to a
 * catalog/schema-scoped grant (see {@link #securableClause}). Never throws onto
 * the caller - a UC failure must not break policy authoring.
 */
public class UnityCatalogPolicyProjectionSink implements PolicyProjectionSink {

    private static final Logger log = LoggerFactory.getLogger(UnityCatalogPolicyProjectionSink.class);

    private final UnityCatalogGrantClient client;
    private final UnityCatalogGrantProperties props;

    public UnityCatalogPolicyProjectionSink(UnityCatalogGrantClient client,
                                            UnityCatalogGrantProperties props) {
        this.client = client;
        this.props = props;
        log.info("UnityCatalogPolicyProjectionSink enabled - target {}.{}, live={}",
                props.getCatalog(), props.getSchema(), client.isLive());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void project(CompiledAccessPolicy compiled) {
        apply(compiled, false);
    }

    @Override
    public void revoke(CompiledAccessPolicy compiled) {
        // Reverse the intents: what was granted is revoked and vice-versa.
        apply(compiled, true);
    }

    private void apply(CompiledAccessPolicy compiled, boolean reverse) {
        if (compiled.isEmpty()) {
            log.debug("UC projection: policy {} has no securables; nothing to apply",
                    compiled.policyId());
            return;
        }
        for (GrantIntent intent : compiled.intents()) {
            boolean grant = reverse ? !intent.grant() : intent.grant();
            try {
                client.execute(toStatement(intent, grant));
            } catch (Exception e) {
                log.warn("UC projection: failed to apply intent {} for policy {}: {}",
                        intent, compiled.policyId(), e.getMessage());
            }
        }
    }

    /**
     * Render one intent to a UC grant statement.
     *
     * @param intent the tuple to render
     * @param grant  true → {@code GRANT ... TO}; false → {@code REVOKE ... FROM}
     */
    String toStatement(GrantIntent intent, boolean grant) {
        String verb = grant ? "GRANT" : "REVOKE";
        String direction = grant ? "TO" : "FROM";
        return String.format("%s %s ON %s %s `%s`",
                verb, intent.privilege(), securableClause(intent.securable()),
                direction, intent.principal());
    }

    /**
     * Map a DLAP securable to a UC securable clause. A concrete asset URI becomes
     * a {@code TABLE <catalog>.<schema>.<name>}; a {@code label:<LABEL>} token
     * (which names a class of assets DLAP has not resolved to a single table)
     * becomes a {@code SCHEMA <catalog>.<schema>} scope, the narrowest UC
     * securable that covers "all matching tables" without inventing per-table
     * identity. The originating label is preserved in a trailing comment.
     */
    String securableClause(String securable) {
        if (securable.startsWith("label:")) {
            String label = securable.substring("label:".length());
            return String.format("SCHEMA %s.%s /* dlap-label: %s */",
                    props.getCatalog(), props.getSchema(), label);
        }
        return "TABLE " + props.getCatalog() + "." + props.getSchema() + "."
                + sanitize(securable);
    }

    /** UC identifiers allow letters, digits and underscore. */
    private String sanitize(String raw) {
        String tail = raw;
        int slash = raw.lastIndexOf('/');
        if (slash >= 0 && slash < raw.length() - 1) {
            tail = raw.substring(slash + 1);
        }
        String s = tail.replaceAll("[^a-zA-Z0-9_]", "_");
        return s.isBlank() ? "asset" : s;
    }
}
