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

package com.dlap.common.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;

/**
 * Dev-default boot guard (SEC-05). Under the {@code prod} profile a service refuses to start when a
 * secret is empty or still carries a development default that this repository publishes.
 *
 * <p>The repository ships working defaults so that the quickstart boots with no setup: the
 * {@code dlap-backend} client secret, the sample encryption key, the Keycloak admin login and the
 * per-service database passwords. Anyone can read them. Production must never keep one, so this guard
 * turns "the operator forgot to set it" from a silent weakness into a start-up failure.
 *
 * <p><b>Refused under {@code prod}</b> (the service does not start):
 *
 * <ul>
 *   <li>a secret-named setting whose value is {@code change-me-dev-only}, {@code Password123!} or the
 *       sample encryption key;</li>
 *   <li>a password-named setting whose value is {@code admin} (the {@code admin}/{@code admin} login),
 *       {@code changeme} or {@code change-me};</li>
 *   <li>an empty service-to-service client secret (when {@code dlap.service-auth.enabled=true}), an empty
 *       credential-store encryption key, an empty Keycloak admin password or an empty PDP token.</li>
 * </ul>
 *
 * <p><b>Strict findings</b>, refused under {@code prod} by default since SEC-12 (Q-176, Q-449): a database
 * password that is a published development default (the {@code <database>_pass} of the init script, the
 * {@code dlap_monitor_pass} and {@code dlap_backup_pass} of the platform roles, or the superuser's
 * {@code dlap}), and JWT issuer or audience validation switched off. They became fatal when
 * {@code 00-create-multiple-databases.sh} started taking one password per role from the environment
 * ({@code <DATABASE>_DB_PASSWORD}), so a deployment can now satisfy them. Under a guarded profile that is
 * not {@code prod} and where {@code dev} is active they stay advisory (a warning); the switches below
 * change that either way.
 *
 * <p>How it looks: every enumerable property source of the {@link Environment} (application files,
 * {@code SPRING_APPLICATION_JSON}, system properties, the OS environment) is scanned for settings whose
 * name looks like a secret; the value is resolved the way the service resolves it, so a shipped default
 * such as {@code ${DLAP_SERVICE_CLIENT_SECRET:change-me-dev-only}} is seen when the variable is unset. A
 * client secret is only checked when {@code dlap.service-auth.enabled=true}, because it is unused
 * otherwise. The check runs as a {@link BeanFactoryPostProcessor}, before any other bean (the data
 * source, the Kafka factories, the JWT decoder) is created.
 *
 * <p>Failure messages name the setting and the property source, never the value.
 *
 * <p>Switches (all optional): {@value #PROFILES_PROPERTY} (comma list, default {@code prod}) names the
 * profiles the guard applies to; {@value #STRICT_PROPERTY} turns the strict findings into refusals
 * ({@code true}) or warnings ({@code false}); unset, they are refusals unless {@code dev} is active
 * without {@code prod}; {@value #ACCEPTED_PROPERTY} lists JWT validation flags whose "off" finding is
 * accepted and only logged, for a deployment that has a reason (the demo's realm has no audience claim, so
 * it accepts {@code dlap.security.jwt.validate-audience}). An empty secret, a published secret and a
 * database default are never accepted.
 *
 * <p>This class has no mutable state and is safe to call from any thread.
 */
@AutoConfiguration
public class DevSecretGuard {

    /** Comma separated profiles under which the guard applies. Default {@value #DEFAULT_PROFILE}. */
    public static final String PROFILES_PROPERTY = "dlap.security.dev-secret-guard.profiles";

    /**
     * Whether the strict findings (database defaults, JWT validation off) stop the start. Unset, they do
     * unless the {@code dev} profile is active without {@code prod}.
     */
    public static final String STRICT_PROPERTY = "dlap.security.dev-secret-guard.strict";

    /**
     * Comma separated names of JWT validation flags whose "off" finding is accepted: logged, never fatal.
     * For example {@code dlap.security.jwt.validate-audience}. A database default is never accepted.
     */
    public static final String ACCEPTED_PROPERTY = "dlap.security.dev-secret-guard.accepted";

    /** The profile the guard applies to when {@value #PROFILES_PROPERTY} is not set. */
    public static final String DEFAULT_PROFILE = "prod";

    private static final Logger log = LoggerFactory.getLogger(DevSecretGuard.class);

    /** Settings whose name contains one of these words are treated as secrets. */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i)(pass(word|wd)?|secret|token|credential|api[-_.]?key|encryption[-_.]?key|access[-_.]?key"
                    + "|private[-_.]?key)");

    /** Settings whose name contains {@code pass}, {@code password} or {@code passwd}. */
    private static final Pattern PASSWORD_NAME = Pattern.compile("(?i)pass(word|wd)?");

    /**
     * {@code <database>_pass}: the password the init script gives a service user when no variable is set,
     * and the property default; and the same for the two platform roles of SEC-12.
     */
    private static final Pattern DATABASE_DEFAULT =
            Pattern.compile("^(da_[a-z]+|dlap_common|dlap_monitor|dlap_backup)_pass$");

    /** The sample AES key of {@code .env.example} and the dev compose file: 32 hex digits counting 0 to f twice. */
    private static final String SAMPLE_ENCRYPTION_KEY = IntStream.range(0, 32)
            .mapToObj(i -> Integer.toHexString(i % 16)).collect(Collectors.joining());

    /** Values refused for every secret-named setting. Compared ignoring case. */
    private static final Set<String> PUBLISHED_SECRETS = Set.of(
            "change-me-dev-only", "password123!", SAMPLE_ENCRYPTION_KEY);

    /** Values refused for password-named settings only ({@code admin} is a fine value for a user name). */
    private static final Set<String> PUBLISHED_PASSWORDS = Set.of("admin", "changeme", "change-me");

    /** Secrets that must not be empty when the setting exists. */
    private static final List<String> REQUIRED_WHEN_PRESENT = List.of(
            "dlap.service-auth.client-secret", "app.encryption.key", "keycloak.admin.password", "dlap.pdp.token");

    /** Normalized names of the service-to-service client secret, which only matters when service auth is on. */
    private static final String SERVICE_AUTH_SECRET = normalize("dlap.service-auth.client-secret");

    /** Normalized name suffix of the {@code <DATABASE>_DB_PASSWORD} variables of the init script and compose files. */
    private static final String DB_PASSWORD_SUFFIX = "dbpassword";

    /** Settings that name a database password. */
    private static final Set<String> DATABASE_PASSWORDS = Set.of(
            normalize("spring.datasource.password"),
            normalize("dlap.common-db.password"),
            normalize("dlap.common.datasource.password"),
            normalize("postgres.password"));

    /** Applications whose JWT decoder always validates issuer and audience and has no off switch. */
    private static final Set<String> ALWAYS_VALIDATING_APPLICATIONS = Set.of("da-mcp-pep");

    /**
     * Registers the guard. The method is static so that Spring creates the post processor before the
     * configuration class itself is instantiated, which keeps it ahead of every other bean.
     *
     * @param environment the application environment
     * @return the post processor that runs {@link #verify(Environment)} once, before bean creation
     */
    @Bean
    static BeanFactoryPostProcessor devSecretGuardPostProcessor(Environment environment) {
        return beanFactory -> verify(environment);
    }

    /**
     * Stops the start when the guard applies to the active profiles and a finding is fatal.
     *
     * @param environment the environment to inspect
     * @throws RefusedStartupException when an empty or published-default secret is configured or, in strict
     *         mode (the default under {@code prod}), a database default or switched-off JWT validation is
     *         found and not accepted
     */
    public static void verify(Environment environment) {
        if (!applies(environment)) {
            return;
        }
        boolean strict = strict(environment);
        Set<String> accepted = accepted(environment);
        List<Finding> findings = inspect(environment);
        List<Finding> fatal = findings.stream().filter(f -> fatal(f, strict, accepted)).toList();
        List<Finding> advice = findings.stream().filter(f -> !fatal.contains(f)).toList();
        if (!advice.isEmpty()) {
            log.warn("Dev-default boot guard: {} finding(s) do not stop the start (strict mode is {}; accepted: {}; "
                            + "set {}=true to refuse them): {}",
                    advice.size(), strict ? "on" : "off", accepted.isEmpty() ? "none" : accepted, STRICT_PROPERTY,
                    describe(advice));
        }
        if (!fatal.isEmpty()) {
            throw new RefusedStartupException(fatal);
        }
        log.info("Dev-default boot guard: no empty or published-default secret under profile(s) {}",
                String.join(",", guardedProfiles(environment)));
    }

    /**
     * Tells whether the strict findings are refused. An explicit {@value #STRICT_PROPERTY} wins; unset, they
     * are refused under {@code prod} and under any guarded profile unless {@code dev} is active without
     * {@code prod}.
     *
     * @param environment the environment to test
     * @return {@code true} when a database default or switched-off JWT validation stops the start
     */
    public static boolean strict(Environment environment) {
        Boolean configured = environment.getProperty(STRICT_PROPERTY, Boolean.class);
        if (configured != null) {
            return configured;
        }
        return environment.acceptsProfiles(Profiles.of(DEFAULT_PROFILE))
                || !environment.acceptsProfiles(Profiles.of("dev"));
    }

    private static boolean fatal(Finding finding, boolean strict, Set<String> accepted) {
        if (!finding.kind().advisory()) {
            return true;
        }
        boolean acceptedFinding = finding.kind() == Kind.JWT_VALIDATION_OFF
                && accepted.contains(normalize(finding.name()));
        return strict && !acceptedFinding;
    }

    private static Set<String> accepted(Environment environment) {
        String configured = environment.getProperty(ACCEPTED_PROPERTY, "");
        Set<String> names = new LinkedHashSet<>();
        for (String name : configured.split(",")) {
            if (!name.isBlank()) {
                names.add(normalize(name.trim()));
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Tells whether the guard applies to the environment's active profiles.
     *
     * @param environment the environment to test
     * @return {@code true} when one of the guarded profiles (default {@code prod}) is active
     */
    public static boolean applies(Environment environment) {
        List<String> profiles = guardedProfiles(environment);
        return !profiles.isEmpty() && environment.acceptsProfiles(Profiles.of(profiles.toArray(String[]::new)));
    }

    /**
     * Lists the empty and published-default secrets of an environment, whatever its profile.
     *
     * @param environment the environment to inspect
     * @return the findings in property-source order, one per setting; empty when the environment is clean
     */
    public static List<Finding> inspect(Environment environment) {
        Map<String, Finding> byName = new LinkedHashMap<>();
        boolean serviceAuthOn = environment.getProperty("dlap.service-auth.enabled", Boolean.class, false);
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    if (SECRET_NAME.matcher(name).find()) {
                        classify(environment, name, source.getName(), serviceAuthOn)
                                .ifPresent(f -> byName.putIfAbsent(normalize(f.name()), f));
                    }
                }
            }
        }
        for (String required : REQUIRED_WHEN_PRESENT) {
            emptyRequired(environment, required, serviceAuthOn)
                    .ifPresent(f -> byName.putIfAbsent(normalize(f.name()), f));
        }
        jwtValidationOff(environment).forEach(f -> byName.putIfAbsent(normalize(f.name()), f));
        return List.copyOf(byName.values());
    }

    /**
     * Tells whether a value is one of the defaults this repository publishes for a setting of that name.
     * Used by the compose contract test, so the compose files and the guard cannot drift apart.
     *
     * @param settingName the property or variable name
     * @param value       the value to test, may be {@code null}
     * @return {@code true} when the guard would refuse the value under {@code prod}
     */
    public static boolean isPublishedDefault(String settingName, String value) {
        return publishedDefault(settingName, value) != null;
    }

    private static List<String> guardedProfiles(Environment environment) {
        String configured = environment.getProperty(PROFILES_PROPERTY, DEFAULT_PROFILE);
        List<String> profiles = new ArrayList<>();
        for (String profile : configured.split(",")) {
            if (!profile.isBlank()) {
                profiles.add(profile.trim());
            }
        }
        if (profiles.isEmpty()) {
            // An empty variable (for example ${DLAP_SECURITY_DEV_SECRET_GUARD_PROFILES:-} in a compose file)
            // must not switch the guard off.
            profiles.add(DEFAULT_PROFILE);
        }
        return profiles;
    }

    private static Optional<Finding> classify(Environment environment, String name, String source,
            boolean serviceAuthOn) {
        String value = resolve(environment, name);
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        if (normalize(name).equals(SERVICE_AUTH_SECRET) && !serviceAuthOn) {
            return Optional.empty();
        }
        Kind kind = publishedDefault(name, value);
        return kind == null ? Optional.empty() : Optional.of(new Finding(name, source, kind));
    }

    private static Kind publishedDefault(String name, String value) {
        if (value == null || !SECRET_NAME.matcher(name).find()) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (PUBLISHED_SECRETS.contains(lower)) {
            return Kind.PUBLISHED_DEFAULT;
        }
        boolean password = PASSWORD_NAME.matcher(name).find();
        if (password && PUBLISHED_PASSWORDS.contains(lower)) {
            return Kind.PUBLISHED_DEFAULT;
        }
        if (password && (DATABASE_DEFAULT.matcher(lower).matches() || lower.equals("dlap"))
                && isDatabasePasswordSetting(name)) {
            return Kind.DATABASE_DEFAULT;
        }
        return null;
    }

    private static boolean isDatabasePasswordSetting(String name) {
        String normalized = normalize(name);
        return DATABASE_PASSWORDS.contains(normalized) || normalized.endsWith(DB_PASSWORD_SUFFIX);
    }

    private static Optional<Finding> emptyRequired(Environment environment, String name,
            boolean serviceAuthOn) {
        if (normalize(name).equals(SERVICE_AUTH_SECRET) && !serviceAuthOn) {
            return Optional.empty();
        }
        if (!environment.containsProperty(name)) {
            return Optional.empty();
        }
        String value = resolve(environment, name);
        if (value != null && value.isBlank()) {
            return Optional.of(new Finding(name, "environment", Kind.EMPTY));
        }
        return Optional.empty();
    }

    private static List<Finding> jwtValidationOff(Environment environment) {
        String application = environment.getProperty("spring.application.name", "");
        if (ALWAYS_VALIDATING_APPLICATIONS.contains(application)) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (String flag : List.of("dlap.security.jwt.validate-issuer", "dlap.security.jwt.validate-audience")) {
            if (!environment.getProperty(flag, Boolean.class, false)) {
                findings.add(new Finding(flag, "environment", Kind.JWT_VALIDATION_OFF));
            }
        }
        return findings;
    }

    private static String resolve(Environment environment, String name) {
        try {
            return environment.getProperty(name);
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            // Spring reports an unresolvable placeholder with its own, clearer message when the
            // owning bean is created; the guard has nothing to say about a value it cannot read.
            return null;
        }
    }

    /** Lower case with every dot, dash and underscore removed, so {@code A_B-C.D} equals {@code a.b-c.d}. */
    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[._-]", "");
    }

    private static String describe(List<Finding> findings) {
        return findings.stream().map(Finding::describe).collect(Collectors.joining("; "));
    }

    /** Why a setting was reported. */
    public enum Kind {
        /** The value is a default this repository publishes. Fatal under the guarded profiles. */
        PUBLISHED_DEFAULT("carries a published development default", false),
        /** A secret that must be set is empty. Fatal under the guarded profiles. */
        EMPTY("is empty and must be set", false),
        /**
         * A database password is the published {@code <database>_pass} or {@code dlap}. Strict: fatal under
         * {@code prod} unless strict mode is off. It cannot be accepted.
         */
        DATABASE_DEFAULT("is a published development database password; set the <DATABASE>_DB_PASSWORD variable "
                + "of the init script and hand the value to the service (Q-176)", true),
        /** JWT issuer or audience validation is off. Strict, like {@link #DATABASE_DEFAULT}. */
        JWT_VALIDATION_OFF("is not true, so tokens of other issuers or audiences are accepted", true);

        private final String text;
        private final boolean advisory;

        Kind(String text, boolean advisory) {
            this.text = text;
            this.advisory = advisory;
        }

        /**
         * Returns whether the finding is only a warning unless strict mode is on (the default under
         * {@code prod}) and the setting is not accepted.
         *
         * @return {@code true} for the strict kinds, {@code false} for the kinds that always stop the start
         */
        public boolean advisory() {
            return advisory;
        }

        /**
         * Returns the sentence fragment that follows the setting name in a message.
         *
         * @return the reason text, without the value
         */
        public String text() {
            return text;
        }
    }

    /**
     * One reported setting. The value is deliberately not part of the record.
     *
     * @param name   the property or variable name as it appears in its source
     * @param source the name of the property source, or {@code environment} for a derived check
     * @param kind   why the setting was reported
     */
    public record Finding(String name, String source, Kind kind) {

        /**
         * Returns a one-line description without the value.
         *
         * @return the setting, its source and the reason
         */
        public String describe() {
            return name + " (" + source + ") " + kind.text();
        }
    }

    /** Thrown at start-up when the guard finds a fatal setting. The message never contains a value. */
    public static class RefusedStartupException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final transient List<Finding> findings;

        RefusedStartupException(List<Finding> findings) {
            super("Refusing to start: " + findings.size() + " secret setting(s) are empty or still carry a "
                    + "published development default. Set real values (deployment/PRODUCTION.md, section 1): "
                    + describe(findings));
            this.findings = List.copyOf(findings);
        }

        /**
         * Returns the settings that stopped the start.
         *
         * @return the findings, never empty
         */
        public List<Finding> findings() {
            return findings;
        }
    }
}
