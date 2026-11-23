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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offline {@link UnityCatalogGrantClient}: logs and records each grant statement
 * instead of executing it against a live Unity Catalog. This is the default when
 * no UC endpoint/credentials are configured - the same honest offline behavior
 * as da-catalog's UC integration and the RDS/Glue stubs (ADR-0029/N5): the
 * translation and wiring are exercised end-to-end without inventing live
 * infrastructure.
 *
 * <p>{@link #executed()} exposes the recorded statements for tests and
 * observability.
 */
public class StubUnityCatalogGrantClient implements UnityCatalogGrantClient {

    private static final Logger log = LoggerFactory.getLogger(StubUnityCatalogGrantClient.class);

    private final List<String> executed = new ArrayList<>();

    @Override
    public void execute(String statement) {
        executed.add(statement);
        log.info("[uc-projection][stub] would execute: {}", statement);
    }

    @Override
    public boolean isLive() {
        return false;
    }

    /** @return an immutable copy of the statements recorded so far. */
    public List<String> executed() {
        return List.copyOf(executed);
    }

    /** Clear the recorded statements (test convenience). */
    public void clear() {
        executed.clear();
    }
}
