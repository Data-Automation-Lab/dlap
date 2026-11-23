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

import com.dlap.logingest.health.LogHealthViews.BindingHealth;
import com.dlap.logingest.health.LogHealthViews.DriftReport;
import com.dlap.logingest.health.LogHealthViews.HealthPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The parser health and drift APIs (design B3, test strategy L8). Every handler is {@code ROLE_ADMIN}
 * only: the view lists quarantine samples (masked, bounded excerpts of provider data) and unknown
 * field paths (plan rules R3 and R9). The unauthenticated signal is the {@code logParsers} component
 * of {@code /actuator/health} and the Prometheus series.
 */
@RestController
@RequestMapping("/api/v1/logingest")
@Tag(name = "Log Health", description = "Parser health, quarantine summary and format drift per binding")
public class LogHealthController {

    /** Largest list the drift report returns. */
    public static final int MAX_DRIFT_LIMIT = 200;

    private final BindingHealthService health;
    private final DriftReportService drift;

    /**
     * Creates the controller.
     *
     * @param health the health view
     * @param drift  the drift report
     */
    public LogHealthController(BindingHealthService health, DriftReportService drift) {
        this.health = health;
        this.drift = drift;
    }

    /**
     * The parser health of every binding, plus the startup self-test.
     *
     * @return the page
     */
    @GetMapping("/health")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Parser health per binding: status, versions, quarantine reasons, fill ratios, read coverage")
    public HealthPage page() {
        return health.page();
    }

    /**
     * The parser health of one binding.
     *
     * @param bindingId the binding id
     * @return the row, or 404 when the binding is not configured
     */
    @GetMapping("/health/{bindingId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Parser health of one binding")
    public ResponseEntity<BindingHealth> binding(@PathVariable String bindingId) {
        return health.binding(bindingId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The drift report: new versions, unknown field paths and fields that stopped being filled.
     *
     * @param since start of the window for new versions (ISO-8601); defaults to 7 days ago
     * @param limit the most entries per list, at most {@value #MAX_DRIFT_LIMIT}
     * @return the report
     */
    @GetMapping("/drift")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Weekly drift report: new format versions, unknown paths, fill-ratio drops")
    public DriftReport drift(@RequestParam(required = false) Instant since,
                             @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_DRIFT_LIMIT) {
            throw new IllegalArgumentException("limit is between 1 and " + MAX_DRIFT_LIMIT);
        }
        return drift.report(since, limit);
    }
}
