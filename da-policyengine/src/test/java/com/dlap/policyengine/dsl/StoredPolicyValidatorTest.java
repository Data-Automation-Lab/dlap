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

package com.dlap.policyengine.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-02 start-up checks. Decision D42 on Q-441: every stored AccessPolicy is re-validated, each
 * invalid one is logged with its id and counted in a gauge. Decision D42 on Q-436: every stored
 * policy whose schedule cron does not parse is logged with its id and counted; nothing is
 * rewritten.
 */
class StoredPolicyValidatorTest {

    private static final String VALID_DENY = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: deny-ok}\n"
            + "spec:\n  subjects: {roles: [X]}\n  actions: [read]\n  match: {labels: {anyOf: [PII]}}\n"
            + "  effect: deny\n";
    private static final String MALFORMED_DENY = "apiVersion: dlap/v1\nkind: AccessPolicy\n"
            + "metadata: {id: deny-typo}\nspec:\n  subjects: {roles: [X]}\n  actions: [read]\n"
            + "  match: {lastAccessedAt: {olderThan: 90 days}}\n  effect: deny\n";
    private static final String UNPARSEABLE_ACCESS = "apiVersion: dlap/v1\nkind: AccessPolicy\n"
            + "metadata: {id: no-effect}\nspec:\n  subjects: {roles: [X]}\n  actions: [read]\n";
    private static final String QUARTZ_CRON = "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: quartz}\n"
            + "spec:\n  triggers: [{type: schedule, cron: '0 0 2 * * ? 2027'}]\n  match: {cloudProvider: GCP}\n"
            + "  action: {type: alert.webhook}\n";
    private static final String VALID_CRON = QUARTZ_CRON.replace("quartz", "fine").replace(" 2027", "");
    private static final String NOT_YAML = "apiVersion: [dlap/v1\n";

    private final PolicyRepository repository = mock(PolicyRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<Policy> stored = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(StoredPolicyValidator.class);
    private StoredPolicyValidator validator;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        when(repository.findAll()).thenReturn(stored);
        validator = new StoredPolicyValidator(repository, new PolicyDslParser(), registry);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private Policy store(String yaml, PolicyStatus status) {
        Policy p = new Policy();
        p.setId(UUID.randomUUID());
        p.setName("p-" + stored.size());
        p.setStatus(status);
        p.setPolicyDocument(yaml);
        stored.add(p);
        return p;
    }

    private double gauge(String check) {
        return registry.get(StoredPolicyValidator.GAUGE).tag("check", check).gauge().value();
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void everyInvalidStoredAccessPolicyIsLoggedWithItsIdAndCounted() {
        store(VALID_DENY, PolicyStatus.ENABLED);
        Policy typo = store(MALFORMED_DENY, PolicyStatus.ENABLED);
        Policy disabledBroken = store(UNPARSEABLE_ACCESS, PolicyStatus.DISABLED);

        StoredPolicyValidator.Report report = validator.validate();

        assertThat(report.invalidAccessPolicies()).containsExactly(typo.getId(), disabledBroken.getId());
        assertThat(gauge(StoredPolicyValidator.CHECK_ACCESS_POLICY)).isEqualTo(2.0);
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(typo.getId().toString()).contains("deny-typo")
                .contains("lastAccessedAt: olderThan is not an ISO-8601 duration").contains("fails closed"));
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(disabledBroken.getId().toString())
                .contains("does not parse"));
    }

    @Test
    void everyStoredPolicyWithAnInvalidScheduleCronIsLoggedWithItsIdAndCounted() {
        Policy quartz = store(QUARTZ_CRON, PolicyStatus.ENABLED);
        store(VALID_CRON, PolicyStatus.ENABLED);
        Policy disabledQuartz = store(QUARTZ_CRON.replace("quartz", "quartz-off"), PolicyStatus.DISABLED);

        StoredPolicyValidator.Report report = validator.validate();

        assertThat(report.invalidCrons()).containsExactly(quartz.getId(), disabledQuartz.getId());
        assertThat(report.invalidAccessPolicies()).isEmpty();
        assertThat(gauge(StoredPolicyValidator.CHECK_SCHEDULE_CRON)).isEqualTo(2.0);
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(quartz.getId().toString())
                .contains("0 0 2 * * ? 2027").contains("inert"));
    }

    @Test
    void nothingIsRewritten() {
        Policy quartz = store(QUARTZ_CRON, PolicyStatus.ENABLED);
        Policy typo = store(MALFORMED_DENY, PolicyStatus.ENABLED);

        validator.validate();

        assertThat(quartz.getPolicyDocument()).isEqualTo(QUARTZ_CRON);
        assertThat(quartz.getStatus()).isEqualTo(PolicyStatus.ENABLED);
        assertThat(typo.getPolicyDocument()).isEqualTo(MALFORMED_DENY);
        org.mockito.Mockito.verify(repository).findAll();
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
    }

    @Test
    void aDocumentThatIsNotPolicyYamlIsCountedApart() {
        Policy broken = store(NOT_YAML, PolicyStatus.ENABLED);
        store("  ", PolicyStatus.ENABLED);

        StoredPolicyValidator.Report report = validator.validate();

        assertThat(report.unreadable()).containsExactly(broken.getId());
        assertThat(gauge(StoredPolicyValidator.CHECK_UNREADABLE)).isEqualTo(1.0);
        assertThat(gauge(StoredPolicyValidator.CHECK_ACCESS_POLICY)).isZero();
    }

    @Test
    void theGaugesFollowTheLatestCheck() {
        Policy typo = store(MALFORMED_DENY, PolicyStatus.ENABLED);
        validator.validate();
        assertThat(gauge(StoredPolicyValidator.CHECK_ACCESS_POLICY)).isEqualTo(1.0);

        typo.setPolicyDocument(VALID_DENY);
        validator.validate();

        assertThat(gauge(StoredPolicyValidator.CHECK_ACCESS_POLICY)).isZero();
    }

    @Test
    void aStoreOutageAtStartUpIsLoggedAndDoesNotStopTheService() {
        when(repository.findAll()).thenThrow(new DataAccessResourceFailureException("db down"));

        validator.onReady();

        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("db down"));
    }

    @Test
    void eachUnparseableDocumentNamesWhatThePdpDoesWithIt() {
        // POL-03 (Q-842): the start-up line says what the PDP now does instead of "does not load it".
        Policy noEffect = store(UNPARSEABLE_ACCESS, PolicyStatus.ENABLED);
        Policy notYaml = store(NOT_YAML, PolicyStatus.ENABLED);
        Policy brokenAllow = store(UNPARSEABLE_ACCESS.replace("actions: [read]\n", "effect: allow\n"),
                PolicyStatus.ENABLED);

        validator.validate();

        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(noEffect.getId().toString())
                .contains("denies every request in its readable scope (subjects) as policy-invalid no-effect"));
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(notYaml.getId().toString())
                .contains("denies every request as policy-store-invalid " + notYaml.getId() + " until it is fixed"));
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(brokenAllow.getId().toString())
                .contains("skips it"));
    }

    @Test
    void aDocumentOfAnUnknownKindIsCountedAsUnreadableBecauseThePdpTreatsItAsAPossibleDeny() {
        Policy typo = store(VALID_DENY.replace("kind: AccessPolicy", "kind: AccesPolicy"), PolicyStatus.ENABLED);
        store(VALID_CRON.replace("kind: Policy", "kind: policy"), PolicyStatus.ENABLED);

        StoredPolicyValidator.Report report = validator.validate();

        assertThat(report.unreadable()).containsExactly(typo.getId());
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(typo.getId().toString())
                .contains("kind 'AccesPolicy'").contains("policy-invalid deny-ok"));
    }

    @Test
    void aLifecycleDocumentIsNamedAsIgnoredByThePdp() {
        Policy p = new Policy();
        p.setId(UUID.randomUUID());

        assertThat(StoredPolicyValidator.pdpConsequence(p, QUARTZ_CRON)).startsWith("ignores it");
    }

    @Test
    void theStartUpRunLogsTheSummary() {
        store(MALFORMED_DENY, PolicyStatus.ENABLED);
        store(QUARTZ_CRON, PolicyStatus.ENABLED);

        validator.onReady();

        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("1 invalid AccessPolicies, 1 policies with an invalid schedule cron"));
    }
}
