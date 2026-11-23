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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import com.dlap.common.security.DevSecretGuard.Finding;
import com.dlap.common.security.DevSecretGuard.Kind;
import com.dlap.common.security.DevSecretGuard.RefusedStartupException;

/**
 * SEC-05 and SEC-12: the dev-default boot guard, driven through a real application context. Since SEC-12
 * (Q-176, Q-449) a database password that is a published default and JWT validation switched off are fatal
 * under {@code prod}; the dev profile keeps them advisory.
 *
 * <p>Each test builds a context from {@link DevSecretGuard} alone with a hermetic environment: the
 * runner's system properties and OS environment are removed, so a variable on the machine that runs the
 * build can neither trip nor hide a finding. The profile is set through {@code spring.profiles.active},
 * the way a deployment sets it.
 */
class DevSecretGuardTest {

    /** The strong placeholder used for every setting a test does not exercise. */
    private static final String STRONG = "s3cure-Vw9xQ2pL7mKd4TgR8nHb";

    /** The sample AES key of .env.example: 32 hex digits counting 0 to f twice, computed so no line repeats it. */
    private static final String SAMPLE_KEY = IntStream.range(0, 32)
            .mapToObj(i -> Integer.toHexString(i % 16)).collect(Collectors.joining());

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DevSecretGuard.class))
                .withInitializer(ctx -> {
                    ctx.getEnvironment().getPropertySources().remove("systemProperties");
                    ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
                });
    }

    /**
     * A prod context with issuer and audience validation on, so a test sees only the finding it provokes.
     * A property given by the test comes later and wins, so a test can switch a flag off again.
     */
    private static ApplicationContextRunner prod(String... properties) {
        return bareProd().withPropertyValues(strongJwt()).withPropertyValues(properties);
    }

    /** A prod context with no JWT switch at all, the way the shipped property files leave it. */
    private static ApplicationContextRunner bareProd(String... properties) {
        return runner().withPropertyValues("spring.profiles.active=prod").withPropertyValues(properties);
    }

    private static RefusedStartupException refusal(AssertableApplicationContext ctx) {
        assertThat(ctx).hasFailed();
        Throwable cause = ctx.getStartupFailure();
        while (cause != null && !(cause instanceof RefusedStartupException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("the start-up failure is the guard's refusal").isInstanceOf(RefusedStartupException.class);
        return (RefusedStartupException) cause;
    }

    // ---------------------------------------------------------------------------------------------
    // The four defaults named in the acceptance list, plus the empty secret
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> publishedDefaults() {
        return Stream.of(
                Arguments.of("dlap.service-auth.client-secret", "change-me-dev-only"),
                Arguments.of("DLAP_BACKEND_CLIENT_SECRET", "change-me-dev-only"),
                Arguments.of("keycloak.admin.password", "admin"),
                Arguments.of("keycloak.admin.password", "Admin"),
                Arguments.of("keycloak.admin.password", "changeme"),
                Arguments.of("realm.seed.user-password", "Password123!"),
                Arguments.of("app.encryption.key", SAMPLE_KEY),
                Arguments.of("DLAP_ENCRYPTION_KEY", SAMPLE_KEY.toUpperCase()));
    }

    /** Acceptance: under prod, startup fails on a known dev default. Each value is one of the published ones. */
    @ParameterizedTest(name = "prod refuses {0}")
    @MethodSource("publishedDefaults")
    void prodRefusesAPublishedDefault(String name, String value) {
        prod(name + "=" + value, "dlap.service-auth.enabled=true").run(ctx -> {
            RefusedStartupException refused = refusal(ctx);
            assertThat(refused.findings()).extracting(Finding::name).contains(name);
            assertThat(refused.findings()).filteredOn(f -> f.name().equals(name))
                    .allMatch(f -> f.kind() == Kind.PUBLISHED_DEFAULT);
        });
    }

    /** The failure names the setting and its source and never prints the value. */
    @ParameterizedTest(name = "message hides the value of {0}")
    @MethodSource("publishedDefaults")
    void theMessageNamesTheSettingButNeverTheValue(String name, String value) {
        prod(name + "=" + value, "dlap.service-auth.enabled=true").run(ctx -> {
            String message = refusal(ctx).getMessage();
            assertThat(message).contains(name).contains("deployment/PRODUCTION.md");
            assertThat(message.replace(name, "").toLowerCase()).doesNotContain(value.toLowerCase());
        });
    }

    static Stream<Arguments> emptySecrets() {
        return Stream.of(
                Arguments.of("dlap.service-auth.client-secret"),
                Arguments.of("app.encryption.key"),
                Arguments.of("keycloak.admin.password"),
                Arguments.of("dlap.pdp.token"));
    }

    /** Acceptance: under prod, startup fails on an empty secret. */
    @ParameterizedTest(name = "prod refuses an empty {0}")
    @MethodSource("emptySecrets")
    void prodRefusesAnEmptySecret(String name) {
        prod(name + "=", "dlap.service-auth.enabled=true").run(ctx -> {
            RefusedStartupException refused = refusal(ctx);
            assertThat(refused.findings()).anySatisfy(f -> {
                assertThat(f.name()).isEqualTo(name);
                assertThat(f.kind()).isEqualTo(Kind.EMPTY);
            });
        });
    }

    /** A whitespace-only secret is as empty as an empty one. */
    @Test
    void prodRefusesABlankSecret() {
        prod("app.encryption.key=   ").run(ctx -> assertThat(refusal(ctx).findings())
                .extracting(Finding::name).containsExactly("app.encryption.key"));
    }

    /** A user name of admin is not a secret: only the password of the admin/admin login is refused. */
    @Test
    void anAdminUserNameAloneStartsButAdminAdminDoesNot() {
        prod("keycloak.admin.username=admin", "keycloak.admin.password=" + STRONG)
                .withPropertyValues(strongJwt())
                .run(ctx -> assertThat(ctx).hasNotFailed());
        prod("keycloak.admin.username=admin", "keycloak.admin.password=admin").run(ctx -> {
            assertThat(refusal(ctx).findings()).extracting(Finding::name)
                    .containsExactly("keycloak.admin.password");
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Scope: profile, service auth, strong values
    // ---------------------------------------------------------------------------------------------

    /** Outside prod the quickstart defaults are the point: the guard does nothing. */
    @ParameterizedTest(name = "profile {0} is left alone")
    @ValueSource(strings = {"dev", "test", "pilot-lab", ""})
    void theGuardDoesNothingOutsideProd(String profile) {
        runner().withPropertyValues(profile.isEmpty() ? new String[0] : new String[] {"spring.profiles.active=" + profile})
                .withPropertyValues("dlap.service-auth.enabled=true", "dlap.service-auth.client-secret=change-me-dev-only",
                        "keycloak.admin.password=admin", "app.encryption.key=")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** The profile list is configurable, for a deployment that names its production profile differently. */
    @Test
    void theGuardedProfilesAreConfigurable() {
        runner().withPropertyValues("spring.profiles.active=staging", "dlap.security.dev-secret-guard.profiles=prod, staging",
                "keycloak.admin.password=admin").withPropertyValues(strongJwt())
                .run(ctx -> assertThat(refusal(ctx).findings()).extracting(Finding::name)
                        .containsExactly("keycloak.admin.password"));
        runner().withPropertyValues("spring.profiles.active=staging", "keycloak.admin.password=admin")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** An empty profile list (an unset variable passed through as empty) falls back to prod, never to "off". */
    @ParameterizedTest(name = "profile list [{0}] still guards prod")
    @ValueSource(strings = {"", "   ", ","})
    void anEmptyProfileListDoesNotSwitchTheGuardOff(String list) {
        runner().withPropertyValues("spring.profiles.active=prod", "dlap.security.dev-secret-guard.profiles=" + list,
                "keycloak.admin.password=admin").withPropertyValues(strongJwt())
                .run(ctx -> assertThat(refusal(ctx).findings()).extracting(Finding::name)
                        .containsExactly("keycloak.admin.password"));
    }

    /** Real values for every guarded setting start cleanly. */
    @Test
    void strongValuesStart() {
        prod("dlap.service-auth.enabled=true", "dlap.service-auth.client-secret=" + STRONG,
                "app.encryption.key=" + "ab12".repeat(16), "keycloak.admin.password=" + STRONG,
                "dlap.pdp.token=" + STRONG, "spring.datasource.password=" + STRONG)
                .withPropertyValues(strongJwt())
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** The client secret is unused while service auth is off, so neither its default nor its absence matters. */
    @Test
    void theClientSecretOnlyMattersWhenServiceAuthIsOn() {
        prod("dlap.service-auth.enabled=false", "dlap.service-auth.client-secret=change-me-dev-only")
                .withPropertyValues(strongJwt()).run(ctx -> assertThat(ctx).hasNotFailed());
        prod("dlap.service-auth.client-secret=").withPropertyValues(strongJwt())
                .run(ctx -> assertThat(ctx).hasNotFailed());
        prod("dlap.service-auth.enabled=true", "dlap.service-auth.client-secret=change-me-dev-only")
                .run(ctx -> assertThat(refusal(ctx).findings()).extracting(Finding::name)
                        .containsExactly("dlap.service-auth.client-secret"));
    }

    // ---------------------------------------------------------------------------------------------
    // How a default is found
    // ---------------------------------------------------------------------------------------------

    /**
     * The shipped property file is {@code ${DLAP_SERVICE_CLIENT_SECRET:change-me-dev-only}}. The guard
     * resolves it like the service does, so the default is seen while the variable is unset and gone once
     * an operator sets it.
     */
    @Test
    void aPlaceholderDefaultIsResolvedBeforeItIsJudged() {
        String shipped = "dlap.service-auth.client-secret=${DLAP_SERVICE_CLIENT_SECRET:change-me-dev-only}";
        prod(shipped, "dlap.service-auth.enabled=true").run(ctx -> assertThat(refusal(ctx).findings())
                .extracting(Finding::name).containsExactly("dlap.service-auth.client-secret"));
        prod(shipped, "dlap.service-auth.enabled=true", "DLAP_SERVICE_CLIENT_SECRET=" + STRONG)
                .withPropertyValues(strongJwt()).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** A secret inside {@code SPRING_APPLICATION_JSON} (how the compose files used to pass it) is found. */
    @Test
    void aSecretInsideSpringApplicationJsonIsFound() {
        prod()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "spring.application.json", Map.of("dlap.service-auth.enabled", "true",
                                "dlap.service-auth.client-secret", "change-me-dev-only"))))
                .run(ctx -> {
                    RefusedStartupException refused = refusal(ctx);
                    assertThat(refused.findings()).singleElement().satisfies(f -> {
                        assertThat(f.name()).isEqualTo("dlap.service-auth.client-secret");
                        assertThat(f.source()).isEqualTo("spring.application.json");
                    });
                });
    }

    /** An OS environment variable (DLAP_SERVICE_AUTH_CLIENT_SECRET) is found under both of its names. */
    @Test
    void anEnvironmentVariableIsFoundAndReportedOnce() {
        prod()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "DLAP_SERVICE_AUTH_ENABLED", "true",
                                "DLAP_SERVICE_AUTH_CLIENT_SECRET", "change-me-dev-only"))))
                .withPropertyValues("dlap.service-auth.client-secret=${DLAP_SERVICE_CLIENT_SECRET:change-me-dev-only}")
                .run(ctx -> assertThat(refusal(ctx).findings()).as("one finding for one setting").hasSize(1));
    }

    /** A secret whose placeholder cannot be resolved is left to Spring's own, clearer error. */
    @Test
    void anUnresolvablePlaceholderIsNotTheGuardsBusiness() {
        prod("dlap.common-db.password=${NO_SUCH_VARIABLE_ANYWHERE}").withPropertyValues(strongJwt())
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** The guard runs before any other bean is created, so a refused start never opens a connection. */
    @Test
    void theGuardRunsBeforeAnyOtherBeanIsCreated() {
        AtomicBoolean created = new AtomicBoolean(false);
        prod("keycloak.admin.password=admin").withBean("probe", Object.class, () -> {
            created.set(true);
            return new Object();
        }).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(created).as("a bean was created before the refusal").isFalse();
        });
    }

    /** Guarded settings are checked by name and exact value: a similar strong value does not trip the guard. */
    @Test
    void onlyTheExactPublishedValuesAreRefused() {
        prod("keycloak.admin.password=administrator-" + STRONG, "dlap.service-auth.enabled=true",
                "dlap.service-auth.client-secret=prefix-change-me-dev-only-suffix-" + STRONG)
                .withPropertyValues(strongJwt()).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** {@link DevSecretGuard#isPublishedDefault} agrees with what the guard refuses. */
    @Test
    void isPublishedDefaultMatchesTheRefusalRules() {
        assertThat(DevSecretGuard.isPublishedDefault("DLAP_BACKEND_CLIENT_SECRET", "change-me-dev-only")).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("KEYCLOAK_ADMIN_PASSWORD", "admin")).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("KEYCLOAK_ADMIN", "admin")).isFalse();
        assertThat(DevSecretGuard.isPublishedDefault("DLAP_ENCRYPTION_KEY", SAMPLE_KEY)).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("DLAP_ENCRYPTION_KEY", "ab12".repeat(16))).isFalse();
        assertThat(DevSecretGuard.isPublishedDefault("SPRING_DATASOURCE_PASSWORD", "da_catalog_pass")).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("POSTGRES_PASSWORD", "dlap")).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("DA_CATALOG_DB_PASSWORD", "da_catalog_pass")).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("DLAP_MONITOR_DB_PASSWORD", "dlap_monitor_pass")).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("DLAP_BACKUP_DB_PASSWORD", "dlap_backup_pass")).isTrue();
        assertThat(DevSecretGuard.isPublishedDefault("DA_CATALOG_DB_PASSWORD", "operator-chosen")).isFalse();
        assertThat(DevSecretGuard.isPublishedDefault("DLAP_BACKEND_CLIENT_SECRET", null)).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Advisory findings (Q-186): warned about, refused in strict mode
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> databaseDefaults() {
        return Stream.of(
                Arguments.of("spring.datasource.password", "da_catalog_pass"),
                Arguments.of("spring.datasource.password", "da_admin_pass"),
                Arguments.of("dlap.common-db.password", "dlap_common_pass"),
                Arguments.of("spring.datasource.password", "dlap"),
                Arguments.of("SPRING_DATASOURCE_PASSWORD", "da_discovery_pass"),
                Arguments.of("DA_CATALOG_DB_PASSWORD", "da_catalog_pass"),
                Arguments.of("DLAP_MONITOR_DB_PASSWORD", "dlap_monitor_pass"),
                Arguments.of("DLAP_BACKUP_DB_PASSWORD", "dlap_backup_pass"),
                Arguments.of("POSTGRES_PASSWORD", "dlap"));
    }

    /**
     * Q-449 acceptance: under prod, startup fails on a published development database password. Every
     * value is one the init script or a property file falls back to when no password is set. The failure
     * names the setting and never the value.
     */
    @ParameterizedTest(name = "prod refuses the dev database password {0}={1}")
    @MethodSource("databaseDefaults")
    void prodRefusesADevDatabasePassword(String name, String value) {
        prod(name + "=" + value).run(ctx -> {
            RefusedStartupException refused = refusal(ctx);
            assertThat(refused.findings()).singleElement().satisfies(f -> {
                assertThat(f.name()).isEqualTo(name);
                assertThat(f.kind()).isEqualTo(Kind.DATABASE_DEFAULT);
            });
            assertThat(refused.getMessage().replace(name, "")).doesNotContain(value);
        });
    }

    /** Under the dev profile the same finding is a warning: the quickstart keeps its defaults. */
    @ParameterizedTest(name = "dev profile warns and starts: {0}={1}")
    @MethodSource("databaseDefaults")
    void theDevProfileKeepsADatabaseDefaultAdvisory(String name, String value) {
        runner().withPropertyValues("spring.profiles.active=dev", "dlap.security.dev-secret-guard.profiles=dev",
                name + "=" + value).withPropertyValues(strongJwt()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(DevSecretGuard.inspect(ctx.getEnvironment())).singleElement().satisfies(f -> {
                assertThat(f.name()).isEqualTo(name);
                assertThat(f.kind()).isEqualTo(Kind.DATABASE_DEFAULT);
            });
        });
    }

    /** An explicit strict=true refuses it even under the dev profile. */
    @ParameterizedTest(name = "dev profile with strict=true refuses {0}={1}")
    @MethodSource("databaseDefaults")
    void strictModeRefusesAPublishedDatabasePasswordUnderDev(String name, String value) {
        runner().withPropertyValues("spring.profiles.active=dev", "dlap.security.dev-secret-guard.profiles=dev",
                "dlap.security.dev-secret-guard.strict=true", name + "=" + value).withPropertyValues(strongJwt())
                .run(ctx -> assertThat(refusal(ctx).findings()).singleElement()
                        .satisfies(f -> assertThat(f.kind()).isEqualTo(Kind.DATABASE_DEFAULT)));
    }

    /** The default follows the profile: strict under prod and any guarded profile, advisory under dev alone. */
    @Test
    void strictIsTheDefaultUnderProdAndAdvisoryUnderDev() {
        assertThat(DevSecretGuard.strict(env("prod"))).isTrue();
        assertThat(DevSecretGuard.strict(env("prod", "dev"))).as("prod wins when both are active").isTrue();
        assertThat(DevSecretGuard.strict(env("staging"))).isTrue();
        assertThat(DevSecretGuard.strict(env("dev"))).isFalse();
        assertThat(DevSecretGuard.strict(env("dev").withProperty("dlap.security.dev-secret-guard.strict", "true")))
                .isTrue();
        assertThat(DevSecretGuard.strict(env("prod").withProperty("dlap.security.dev-secret-guard.strict", "false")))
                .isFalse();
        assertThat(DevSecretGuard.strict(env("prod").withProperty("dlap.security.dev-secret-guard.strict", "")))
                .as("an empty variable passed through from a compose file means unset").isTrue();
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    /** An explicit strict=false is the lab escape hatch: warnings only, but a published secret still stops. */
    @Test
    void anExplicitStrictFalseUnderProdOnlyWarns() {
        bareProd("spring.application.name=da-catalog", "spring.datasource.password=da_catalog_pass",
                "dlap.security.dev-secret-guard.strict=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(DevSecretGuard.inspect(ctx.getEnvironment())).extracting(Finding::kind)
                    .contains(Kind.DATABASE_DEFAULT, Kind.JWT_VALIDATION_OFF);
        });
        bareProd("keycloak.admin.password=admin", "dlap.security.dev-secret-guard.strict=false")
                .run(ctx -> assertThat(refusal(ctx).findings()).extracting(Finding::name)
                        .containsExactly("keycloak.admin.password"));
    }

    /** Operator-chosen database passwords are not findings. */
    @Test
    void aDatabasePasswordTheOperatorChoseIsClean() {
        prod("spring.datasource.password=" + STRONG, "dlap.common-db.password=" + STRONG,
                "dlap.security.dev-secret-guard.strict=true").withPropertyValues(strongJwt())
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** Issuer and audience validation off stops a prod start (B3-S1, Q-186, Q-449). */
    @Test
    void prodRefusesJwtValidationThatIsOff() {
        bareProd("spring.application.name=da-catalog").run(ctx -> assertThat(refusal(ctx).findings())
                .extracting(Finding::name)
                .containsExactly("dlap.security.jwt.validate-issuer", "dlap.security.jwt.validate-audience"));
        prod("spring.application.name=da-catalog", "dlap.security.jwt.validate-issuer=true",
                "dlap.security.jwt.validate-audience=false")
                .run(ctx -> assertThat(refusal(ctx).findings()).extracting(Finding::name)
                        .containsExactly("dlap.security.jwt.validate-audience"));
        prod("spring.application.name=da-catalog").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /**
     * A deployment with a reason can accept one JWT flag: the demo realm has no audience claim. The finding is
     * logged, the other flag still counts, and a database default or a published secret is never accepted.
     */
    @Test
    void anAcceptedJwtFlagIsLoggedAndNotFatal() {
        String accepted = "dlap.security.dev-secret-guard.accepted=dlap.security.jwt.validate-audience";
        bareProd("spring.application.name=da-catalog", "dlap.security.jwt.validate-issuer=true", accepted)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(DevSecretGuard.inspect(ctx.getEnvironment())).extracting(Finding::name)
                            .containsExactly("dlap.security.jwt.validate-audience");
                });
        bareProd("spring.application.name=da-catalog", accepted).run(ctx -> assertThat(refusal(ctx).findings())
                .extracting(Finding::name).containsExactly("dlap.security.jwt.validate-issuer"));
        prod("spring.datasource.password=da_catalog_pass", accepted)
                .run(ctx -> assertThat(refusal(ctx).findings()).extracting(Finding::name)
                        .containsExactly("spring.datasource.password"));
        prod("keycloak.admin.password=admin", accepted)
                .run(ctx -> assertThat(refusal(ctx).findings()).extracting(Finding::name)
                        .containsExactly("keycloak.admin.password"));
    }

    /** da-mcp-pep always validates issuer and audience and has no flags, so it is never reported for them. */
    @Test
    void theMcpPepIsNotReportedForJwtValidation() {
        prod("spring.application.name=da-mcp-pep", "dlap.security.dev-secret-guard.strict=true",
                "dlap.pdp.token=" + STRONG).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** A fatal and a strict finding together: with strict mode off the refusal lists only the fatal one. */
    @Test
    void withoutStrictModeTheRefusalListsOnlyTheFatalFindings() {
        prod("keycloak.admin.password=admin", "spring.datasource.password=da_catalog_pass",
                "dlap.security.dev-secret-guard.strict=false").run(ctx -> {
            assertThat(refusal(ctx).findings()).extracting(Finding::name).containsExactly("keycloak.admin.password");
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------------------------

    /** The guard reaches every service through da-protos' AutoConfiguration.imports, next to the service-auth one. */
    @Test
    void theGuardIsListedInAutoConfigurationImports() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertThat(candidates).contains(DevSecretGuard.class.getName(), "com.dlap.common.auth.ServiceAuthAutoConfiguration");
    }

    private static String[] strongJwt() {
        return new String[] {"dlap.security.jwt.validate-issuer=true", "dlap.security.jwt.validate-audience=true"};
    }
}
