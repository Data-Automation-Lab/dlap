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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Lookup order and fallback of the principal identity map. */
class PrincipalIdentityResolverTest {

    private final PrincipalIdentityMapRepository repository = mock(PrincipalIdentityMapRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PrincipalIdentityResolver resolver = new PrincipalIdentityResolver(repository, meters);

    private static PrincipalIdentityMapping row(IdentityProviderScope provider, String key, String subject) {
        PrincipalIdentityMapping m = new PrincipalIdentityMapping();
        m.setProvider(provider);
        m.setPrincipalKey(key);
        m.setSubjectId(subject);
        m.setSource(PrincipalIdentityMapping.Source.MANUAL);
        return m;
    }

    private double count(String outcome) {
        return meters.counter(PrincipalIdentityResolver.RESOLUTIONS_COUNTER, "outcome", outcome).count();
    }

    @Test
    void mappedPrincipal_resolvesToItsSubject_searchingTheEventProviderAndAny() {
        when(repository.findByPrincipalKeyInAndProviderIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(row(IdentityProviderScope.GCP,
                        "agent@acme-prod.iam.gserviceaccount.com", "svc-marketing-agent")));

        ResolvedPrincipal p = resolver.resolve(IdentityProviderScope.GCP,
                "serviceAccount:Agent@acme-prod.iam.gserviceaccount.com").orElseThrow();

        assertThat(p).isEqualTo(new ResolvedPrincipal("svc-marketing-agent",
                "agent@acme-prod.iam.gserviceaccount.com", true));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<IdentityProviderScope>> scopes = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findByPrincipalKeyInAndProviderIn(any(), scopes.capture());
        assertThat(new ArrayList<>(scopes.getValue()))
                .containsExactlyInAnyOrderElementsOf(EnumSet.of(IdentityProviderScope.GCP, IdentityProviderScope.ANY));
        assertThat(count("mapped")).isEqualTo(1.0);
    }

    @Test
    void providerSpecificEntry_winsOverAnAnyEntry() {
        when(repository.findByPrincipalKeyInAndProviderIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        row(IdentityProviderScope.ANY, "alice@example.com", "alice-any"),
                        row(IdentityProviderScope.AZURE, "alice@example.com", "alice-azure")));

        assertThat(resolver.resolve(IdentityProviderScope.AZURE, "Alice@Example.com").orElseThrow().subjectId())
                .isEqualTo("alice-azure");
    }

    @Test
    void exactSessionEntry_winsOverTheRoleEntry_andTheRoleCoversOtherSessions() {
        String session = "arn:aws:sts::123456789012:assumed-role/analytics-reader/alice";
        String role = "arn:aws:iam::123456789012:role/analytics-reader";
        when(repository.findByPrincipalKeyInAndProviderIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(row(IdentityProviderScope.AWS, role, "svc-analytics"),
                        row(IdentityProviderScope.ANY, session, "alice")));

        assertThat(resolver.resolve(IdentityProviderScope.AWS, session).orElseThrow().subjectId()).isEqualTo("alice");

        when(repository.findByPrincipalKeyInAndProviderIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(row(IdentityProviderScope.AWS, role, "svc-analytics")));
        assertThat(resolver.resolve(IdentityProviderScope.AWS,
                "arn:aws:sts::123456789012:assumed-role/analytics-reader/bob").orElseThrow().subjectId())
                .isEqualTo("svc-analytics");
    }

    @Test
    void unmappedPrincipal_keepsItsKey_andIsCountedUnmapped() {
        when(repository.findByPrincipalKeyInAndProviderIn(anyCollection(), anyCollection())).thenReturn(List.of());

        ResolvedPrincipal p = resolver.resolve(null, "user:Bob@Example.com").orElseThrow();

        assertThat(p).isEqualTo(new ResolvedPrincipal("bob@example.com", "bob@example.com", false));
        assertThat(count("unmapped")).isEqualTo(1.0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<IdentityProviderScope>> scopes = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findByPrincipalKeyInAndProviderIn(any(), scopes.capture());
        assertThat(new ArrayList<>(scopes.getValue())).containsExactly(IdentityProviderScope.ANY);
    }

    @Test
    void rowForAnotherKey_isIgnored() {
        when(repository.findByPrincipalKeyInAndProviderIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(row(IdentityProviderScope.GCP, "someone-else@example.com", "x")));

        assertThat(resolver.resolve(IdentityProviderScope.GCP, "a@example.com").orElseThrow().mapped()).isFalse();
    }

    @Test
    void blankPrincipal_isEmpty_withoutALookup() {
        assertThat(resolver.resolve(IdentityProviderScope.GCP, "  ")).isEmpty();
        assertThat(resolver.resolve(IdentityProviderScope.GCP, null)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void scope_parse_ignoresCase_andRejectsUnknownNames() {
        assertThat(IdentityProviderScope.parse(" gcp ")).contains(IdentityProviderScope.GCP);
        assertThat(IdentityProviderScope.parse("Any")).contains(IdentityProviderScope.ANY);
        assertThat(IdentityProviderScope.parse("mars")).isEmpty();
        assertThat(IdentityProviderScope.parse("")).isEmpty();
        assertThat(IdentityProviderScope.parse(null)).isEmpty();
    }
}
