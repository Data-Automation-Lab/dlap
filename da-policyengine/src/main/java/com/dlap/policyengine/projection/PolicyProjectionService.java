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

package com.dlap.policyengine.projection;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dlap.policyengine.access.PolicyChangedEvent;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Drives the push-down projection of {@code kind: AccessPolicy} documents onto a
 * platform-native control system (ADR-0040 push-down mode). Additive supplement
 * to the AuthZEN PDP - it does <b>not</b> change PDP behavior; it only mirrors
 * ENABLED AccessPolicies into the {@link PolicyProjectionSink} so a platform can
 * self-enforce without calling the PDP.
 *
 * <p><b>Off by default.</b> Created only when
 * {@code dlap.policy.projection.enabled=true}. When enabled it (re)projects on
 * startup and on every committed {@link PolicyChangedEvent} - the same in-JVM
 * signal {@code AccessPolicyRefreshListener} uses to keep the PDP cache fresh,
 * so an authored AccessPolicy is pushed down as soon as it commits.
 *
 * <p>Projection is best-effort: the sink swallows platform failures and this
 * service never lets a projection error escape onto the policy-authoring path.
 */
@Service
@ConditionalOnProperty(name = "dlap.policy.projection.enabled", havingValue = "true")
@Slf4j
public class PolicyProjectionService {

    private final PolicyRepository policyRepository;
    private final PolicyDslParser parser;
    private final AccessPolicyToGrantTranslator translator;
    private final ProjectionSecurableResolver securableResolver;
    private final PolicyProjectionSink sink;

    public PolicyProjectionService(PolicyRepository policyRepository,
                                   PolicyDslParser parser,
                                   AccessPolicyToGrantTranslator translator,
                                   ProjectionSecurableResolver securableResolver,
                                   PolicyProjectionSink sink) {
        this.policyRepository = policyRepository;
        this.parser = parser;
        this.translator = translator;
        this.securableResolver = securableResolver;
        this.sink = sink;
        log.info("PolicyProjectionService enabled (push-down mode) - sink={}, real-integration={}",
                sink.getClass().getSimpleName(), sink.isEnabled());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Projecting ENABLED AccessPolicies on startup");
        projectAll();
    }

    /**
     * Re-project after any policy change commits. {@code AFTER_COMMIT} so the
     * projection reflects the committed row; {@code fallbackExecution=true} so it
     * still runs if the event was published outside a transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPolicyChanged(PolicyChangedEvent event) {
        log.debug("Policy change ({}) - re-projecting AccessPolicies", event.reason());
        projectAll();
    }

    /**
     * Project every ENABLED {@code kind: AccessPolicy}. Idempotent: a sink applies
     * the same grant set each time. Non-AccessPolicy and unparseable rows are
     * skipped.
     *
     * @return the number of AccessPolicies projected.
     */
    public int projectAll() {
        int projected = 0;
        for (Policy p : policyRepository.findByStatus(PolicyStatus.ENABLED)) {
            String yaml = p.getPolicyDocument();
            if (yaml == null || yaml.isBlank()) {
                continue;
            }
            PolicyDsl dsl;
            try {
                dsl = parser.parse(yaml);
            } catch (PolicyDslParser.PolicyDslParseException e) {
                log.warn("Skipping projection of policy {} ({}): invalid DSL: {}",
                        p.getId(), p.getName(), e.getMessage());
                continue;
            }
            if (!PolicyDsl.KIND_ACCESS_POLICY.equals(dsl.getKind())) {
                continue;
            }
            if (projectOne(dsl)) {
                projected++;
            }
        }
        log.info("Push-down projection: projected {} AccessPolicy document(s)", projected);
        return projected;
    }

    /**
     * Compile and project a single AccessPolicy DSL. Never throws.
     *
     * @return true if the policy was handed to the sink.
     */
    public boolean projectOne(PolicyDsl dsl) {
        try {
            List<String> securables = securableResolver.resolveSecurables(dsl);
            CompiledAccessPolicy compiled = translator.translate(dsl, securables);
            sink.project(compiled);
            return true;
        } catch (Exception e) {
            log.warn("Failed to project AccessPolicy {}: {}",
                    dsl.getMetadata() != null ? dsl.getMetadata().getId() : "?", e.getMessage());
            return false;
        }
    }
}
