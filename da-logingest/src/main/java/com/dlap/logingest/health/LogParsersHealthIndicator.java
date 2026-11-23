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

package com.dlap.logingest.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The {@code logParsers} component of {@code /actuator/health}: UP when the startup self-test of
 * the parser registry passed, DOWN with the failing case ids when it did not (test strategy L8).
 * The aggregate health therefore goes DOWN for a build whose parsers are broken, which the compose
 * health check and a rollout both see before the build ingests data.
 *
 * <p>Details are shown only to an authorized caller
 * ({@code management.endpoint.health.show-details=when-authorized}); they name case ids and fields,
 * never record values.
 */
@Component
public class LogParsersHealthIndicator implements HealthIndicator {

    private final ParserSelfTest selfTest;

    /**
     * Creates the indicator.
     *
     * @param selfTest the startup self-test
     */
    public LogParsersHealthIndicator(ParserSelfTest selfTest) {
        this.selfTest = selfTest;
    }

    @Override
    public Health health() {
        ParserSelfTest.Report report = selfTest.report();
        if (report == null) {
            return Health.unknown().withDetail("selfTest", "has not run").build();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cases", report.cases());
        details.put("ranAt", report.ranAt().toString());
        if (report.ok()) {
            return Health.up().withDetails(details).build();
        }
        Map<String, String> failed = new LinkedHashMap<>();
        report.failures().forEach(f -> failed.put(f.id(), f.detail()));
        details.put("failed", failed);
        return Health.down().withDetails(details).build();
    }
}
