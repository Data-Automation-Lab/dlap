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

package com.dlap.policyengine.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * SEC-01: a copy-pasted authority in a {@code @PreAuthorize} (for example
 * {@code 'ROLE_POLICY_MANAGER', 'ROLE_POLICY_MANAGER'}) usually means a different role
 * was meant. Spring Security 7 also refuses such a list at call time (duplicate element),
 * so every caller gets an error (Q-756). No controller in the module may repeat an authority.
 */
class PreAuthorizeAuthoritiesTest {

    private static final Pattern AUTHORITY = Pattern.compile("'(ROLE_[A-Z_]+)'");

    @Test
    void policyController_hasNoRepeatedAuthorityInAnyPreAuthorize() {
        assertThat(repeatedAuthorities(PolicyController.class)).isEmpty();
    }

    @Test
    void eventCenterController_hasNoRepeatedAuthorityInAnyPreAuthorize() {
        assertThat(repeatedAuthorities(EventCenterController.class)).isEmpty();
    }

    @Test
    void policyDraftController_hasNoRepeatedAuthorityInAnyPreAuthorize() {
        assertThat(repeatedAuthorities(PolicyDraftController.class)).isEmpty();
    }

    @Test
    void everyRestControllerInTheModule_hasNoRepeatedAuthorityInAnyPreAuthorize() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> repeated = new ArrayList<>();
        int controllers = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.dlap.policyengine")) {
            repeated.addAll(repeatedAuthorities(Class.forName(candidate.getBeanClassName())));
            controllers++;
        }
        assertThat(controllers).isGreaterThan(2);
        assertThat(repeated).isEmpty();
    }

    @Test
    void analyzePolicyImpact_listsEachAuthorityOnce() throws Exception {
        Method m = PolicyController.class.getMethod("analyzePolicyImpact",
                com.dlap.policyengine.dto.PolicyImpactRequestDTO.class);
        List<String> authorities = authoritiesOf(m.getAnnotation(PreAuthorize.class).value());

        assertThat(authorities).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DIRECTOR");
    }

    private static List<String> repeatedAuthorities(Class<?> controller) {
        List<String> repeated = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
            if (pre == null) {
                continue;
            }
            List<String> seen = new ArrayList<>();
            for (String authority : authoritiesOf(pre.value())) {
                if (seen.contains(authority)) {
                    repeated.add(controller.getSimpleName() + "." + m.getName() + " repeats " + authority);
                }
                seen.add(authority);
            }
        }
        return repeated;
    }

    /** Authorities in a {@code hasAnyAuthority(...)} list; the boolean tail of a guard is ignored. */
    private static List<String> authoritiesOf(String expression) {
        // Only the first hasAnyAuthority(...) list is a role list. A guard may append
        // 'hasAuthority('ROLE_ADMIN') or ...', which legitimately names ROLE_ADMIN again.
        int start = expression.indexOf("hasAnyAuthority(");
        int end = start < 0 ? -1 : expression.indexOf(')', start);
        String roleList = start < 0 ? expression : expression.substring(start, end + 1);
        List<String> out = new ArrayList<>();
        Matcher matcher = AUTHORITY.matcher(roleList);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }
}
