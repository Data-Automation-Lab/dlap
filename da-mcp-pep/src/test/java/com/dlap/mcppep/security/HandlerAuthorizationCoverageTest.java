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

package com.dlap.mcppep.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import com.dlap.common.constants.DlapConstants;

/**
 * Handler coverage guard for {@code da-mcp-pep} (RBAC-01, CLAUDE.md section 9,
 * docs/specs/access-model.md "Enforcement").
 *
 * <p>Scans every concrete {@code @Controller} / {@code @RestController} on the
 * classpath and fails when a request handler has no {@code @PreAuthorize}, or one
 * that any authenticated caller satisfies ({@code isAuthenticated()},
 * {@code permitAll()}, a bare SpEL condition). The method-level annotation wins
 * over the class-level one, as in Spring Security. It also fails when an
 * expression names a role outside {@link DlapConstants.UserRole}, because the
 * Access Model recognises exactly that set (conformance rule (e)).
 *
 * <p>Exempt without an entry: health and Swagger paths (CLAUDE.md section 9
 * leaves them open). Every other exception is an {@link #ALLOWLIST} entry with a
 * reason. An entry that no longer matches a violating handler fails the build, so
 * the list cannot rot: fix the handler, then delete the entry.
 *
 * <p>This test is deliberately identical across the eleven services except for
 * the allowlist, so a service cannot opt out by editing shared helpers.
 */
class HandlerAuthorizationCoverageTest {

    /** Scan root: this service plus any controller shipped in da-protos or da-llm-client. */
    private static final String SCAN_ROOT = "com.dlap";

    /**
     * Explicit exceptions. Key is a simple class name (every handler of the class) or
     * {@code Class#method}. Value is the reason, shown when the entry goes stale.
     *
     * <p>Empty since SEC-07: {@code McpPepController#authorize} used to be listed here as open by design
     * (Q-071). It now carries a named-role {@code @PreAuthorize} and sits behind a JWT resource server.
     */
    private static final Map<String, String> ALLOWLIST = Map.of();

    private static final Pattern NAMED_TERM =
            Pattern.compile("has(?:Any)?(?:Role|Authority)\\s*\\(\\s*[^)\\s]");
    private static final Pattern NAMED_CALL =
            Pattern.compile("has(?:Any)?(?:Role|Authority)\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    /** One request handler method and the authorization expression that guards it. */
    private record Handler(String className, String id, String path, String expression) {

        String describe() {
            return id + " " + path + " : " + (expression == null ? "no @PreAuthorize" : expression);
        }
    }

    @Test
    void scanFindsRequestHandlers() throws Exception {
        assertThat(handlers())
                .as("the scan must see this service's controllers, otherwise the guard is vacuous")
                .isNotEmpty();
    }

    @Test
    void everyHandlerHasRoleBasedAuthorization() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Handler h : handlers()) {
            if (isViolation(h) && !isExempt(h)) {
                violations.add(h.describe());
            }
        }
        assertThat(violations)
                .as("handlers without a named-role @PreAuthorize (CLAUDE.md section 9; "
                        + "docs/specs/access-model.md). Add the roles the Access Model grants, or "
                        + "add an ALLOWLIST entry with a reason")
                .isEmpty();
    }

    @Test
    void rolesNamedInExpressionsAreCanonical() throws Exception {
        Set<String> canonical = canonicalRoles();
        List<String> violations = new ArrayList<>();
        for (Handler h : handlers()) {
            for (String name : namedAuthorities(h.expression())) {
                String role = name.startsWith("ROLE_") ? name.substring("ROLE_".length()) : name;
                if (!canonical.contains(role)) {
                    violations.add(h.id() + " names '" + name + "', not in DlapConstants.UserRole " + canonical);
                }
            }
        }
        assertThat(violations)
                .as("expressions may name only the canonical roles (access-model.md conformance (e))")
                .isEmpty();
    }

    @Test
    void allowlistHasNoStaleEntries() throws Exception {
        List<Handler> handlers = handlers();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> entry : ALLOWLIST.entrySet()) {
            boolean stillNeeded = handlers.stream()
                    .filter(h -> entry.getKey().equals(h.className()) || entry.getKey().equals(h.id()))
                    .anyMatch(h -> isViolation(h) && !isInfrastructurePath(h.path()));
            if (!stillNeeded) {
                stale.add(entry.getKey() + " (" + entry.getValue() + ")");
            }
        }
        assertThat(stale)
                .as("allowlist entries that no longer match a violating handler: delete them")
                .isEmpty();
    }

    @Test
    void authenticatedOnlyExpressionsAreNotRoleBased() {
        assertThat(isRoleBased("isAuthenticated()")).isFalse();
        assertThat(isRoleBased("permitAll()")).isFalse();
        assertThat(isRoleBased("")).isFalse();
        assertThat(isRoleBased("#id == authentication.name")).isFalse();
        assertThat(isRoleBased("hasAuthority('ROLE_ADMIN') or isAuthenticated()")).isFalse();
        assertThat(isRoleBased("hasRole('ADMIN') || #dto.owner == authentication.name")).isFalse();
        assertThat(isRoleBased("(hasRole('ADMIN') or isAuthenticated())")).isFalse();
        assertThat(isRoleBased("!hasRole('ADMIN')")).isFalse();
    }

    @Test
    void namedRoleExpressionsAreRoleBased() {
        assertThat(isRoleBased("hasRole('ADMIN')")).isTrue();
        assertThat(isRoleBased("hasAnyRole('ADMIN', 'CDO')")).isTrue();
        assertThat(isRoleBased("hasAuthority('ROLE_ADMIN')")).isTrue();
        assertThat(isRoleBased("hasAnyAuthority('ROLE_SERVICE_ACCOUNT', 'ROLE_ADMIN')")).isTrue();
        assertThat(isRoleBased("hasAuthority('ROLE_ADMIN') or @systemSecurityService.isSystemUser(authentication)"))
                .isTrue();
        assertThat(isRoleBased("isAuthenticated() and hasRole('ADMIN')")).isTrue();
        assertThat(isRoleBased("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')"
                + " and (hasAuthority('ROLE_ADMIN') or #dto.rules == null or #dto.rules.isEmpty())")).isTrue();
        assertThat(isRoleBased("denyAll()")).isTrue();
    }

    @Test
    void healthAndSwaggerPathsAreInfrastructure() {
        assertThat(isInfrastructurePath("/api/v1/autolabel/health")).isTrue();
        assertThat(isInfrastructurePath("/actuator/health")).isTrue();
        assertThat(isInfrastructurePath("/v3/api-docs/swagger-config")).isTrue();
        assertThat(isInfrastructurePath("/swagger-ui/index.html")).isTrue();
        assertThat(isInfrastructurePath("/api/v1/catalog/assets")).isFalse();
    }

    // ---------------------------------------------------------------- scanning

    private static List<Handler> handlers() throws Exception {
        // Matches @Controller and, through meta-annotation, @RestController. Skips
        // interfaces and abstract classes, so Feign clients are not picked up.
        AnnotationTypeFilter controllers = new AnnotationTypeFilter(Controller.class);
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                        // No @Conditional / @Profile evaluation: a controller that is switched
                        // off in this test environment is still a handler in some deployment.
                        return controllers.match(reader, getMetadataReaderFactory());
                    }
                };
        ClassLoader loader = HandlerAuthorizationCoverageTest.class.getClassLoader();
        List<Handler> handlers = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(SCAN_ROOT)) {
            Class<?> type = ClassUtils.forName(bd.getBeanClassName(), loader);
            if (isTestFixture(type)) {
                continue;
            }
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            PreAuthorize classAuth = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            Map<Method, RequestMapping> methods = MethodIntrospector.selectMethods(type,
                    (MethodIntrospector.MetadataLookup<RequestMapping>) m ->
                            AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class));
            for (Map.Entry<Method, RequestMapping> e : methods.entrySet()) {
                PreAuthorize auth = AnnotatedElementUtils.findMergedAnnotation(e.getKey(), PreAuthorize.class);
                if (auth == null) {
                    auth = classAuth;
                }
                handlers.add(new Handler(
                        type.getSimpleName(),
                        type.getSimpleName() + "#" + e.getKey().getName(),
                        join(firstPath(classMapping), firstPath(e.getValue())),
                        auth == null ? null : auth.value()));
            }
        }
        handlers.sort((a, b) -> a.id().compareTo(b.id()));
        return handlers;
    }

    /** Controllers declared under src/test (security smoke endpoints and similar) are not handlers. */
    private static boolean isTestFixture(Class<?> type) {
        CodeSource own = HandlerAuthorizationCoverageTest.class.getProtectionDomain().getCodeSource();
        CodeSource theirs = type.getProtectionDomain().getCodeSource();
        return own != null && theirs != null
                && own.getLocation().toString().equals(theirs.getLocation().toString());
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return "";
        }
        return mapping.path()[0];
    }

    private static String join(String prefix, String suffix) {
        String joined = (prefix + "/" + suffix).replaceAll("/+", "/");
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private static Set<String> canonicalRoles() throws IllegalAccessException {
        Set<String> roles = new TreeSet<>();
        for (Field f : DlapConstants.UserRole.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                roles.add((String) f.get(null));
            }
        }
        return roles;
    }

    // ---------------------------------------------------------------- classification

    private static boolean isViolation(Handler h) {
        return h.expression() == null || !isRoleBased(h.expression());
    }

    private static boolean isExempt(Handler h) {
        return isInfrastructurePath(h.path())
                || ALLOWLIST.containsKey(h.className())
                || ALLOWLIST.containsKey(h.id());
    }

    /** Health and Swagger endpoints are open by policy (CLAUDE.md section 9). */
    private static boolean isInfrastructurePath(String path) {
        return path.equals("/health") || path.endsWith("/health")
                || path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui");
    }

    /**
     * True when satisfying the expression requires a named role, a named authority or a
     * custom authorization bean. Walks the boolean structure: an {@code or} is only as
     * strong as its weakest branch, an {@code and} as its strongest conjunct.
     */
    static boolean isRoleBased(String expression) {
        String e = expression == null ? "" : expression.trim();
        if (e.isEmpty()) {
            return false;
        }
        List<String> disjuncts = splitTopLevel(e, "or", "||");
        if (disjuncts.size() > 1) {
            return disjuncts.stream().allMatch(HandlerAuthorizationCoverageTest::isRoleBased);
        }
        List<String> conjuncts = splitTopLevel(e, "and", "&&");
        if (conjuncts.size() > 1) {
            return conjuncts.stream().anyMatch(HandlerAuthorizationCoverageTest::isRoleBased);
        }
        if (e.startsWith("(") && closesAtEnd(e)) {
            return isRoleBased(e.substring(1, e.length() - 1));
        }
        return NAMED_TERM.matcher(e).lookingAt() || e.startsWith("@") || e.equals("denyAll()");
    }

    /** Role and authority names written as quoted literals inside has*Role / has*Authority calls. */
    private static List<String> namedAuthorities(String expression) {
        List<String> names = new ArrayList<>();
        if (expression == null) {
            return names;
        }
        Matcher call = NAMED_CALL.matcher(expression);
        while (call.find()) {
            Matcher literal = QUOTED.matcher(call.group(1));
            while (literal.find()) {
                names.add(literal.group(1));
            }
        }
        return names;
    }

    private static List<String> splitTopLevel(String expr, String word, String symbol) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                } else if (depth == 0 && expr.startsWith(symbol, i)) {
                    parts.add(expr.substring(start, i));
                    i += symbol.length() - 1;
                    start = i + 1;
                } else if (depth == 0 && isWordAt(expr, i, word)) {
                    parts.add(expr.substring(start, i));
                    i += word.length() - 1;
                    start = i + 1;
                }
            }
        }
        parts.add(expr.substring(start));
        return parts;
    }

    private static boolean isWordAt(String expr, int i, String word) {
        if (!expr.regionMatches(true, i, word, 0, word.length())) {
            return false;
        }
        boolean before = i > 0 && (Character.isWhitespace(expr.charAt(i - 1)) || expr.charAt(i - 1) == ')');
        int end = i + word.length();
        boolean after = end < expr.length() && (Character.isWhitespace(expr.charAt(end)) || expr.charAt(end) == '(');
        return before && after;
    }

    private static boolean closesAtEnd(String e) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i == e.length() - 1;
                    }
                }
            }
        }
        return false;
    }
}
