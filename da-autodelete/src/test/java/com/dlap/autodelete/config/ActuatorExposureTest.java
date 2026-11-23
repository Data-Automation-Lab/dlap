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

package com.dlap.autodelete.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.actuate.endpoint.web.WebMvcHealthEndpointExtensionAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.jvm.JvmMetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsEndpointAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.system.SystemMetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.actuate.web.ServletManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.actuate.web.server.TomcatServletManagementContextAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OBS-01 acceptance for da-autodelete: where the actuator endpoints are served and who may call them.
 *
 * <p>Scope: the real security configuration of the service on a real servlet container, with the
 * actuator, Micrometer and Prometheus auto-configuration of Spring Boot and nothing else (no
 * database, no Kafka). Each test starts its own application, with a separate management port
 * (the {@code MANAGEMENT_SERVER_PORT} case) or without one, and calls the ports over HTTP with no
 * token. The wiring of the whole service, including its own {@code application.properties}, is
 * covered by {@code ManagementPortContextTest}.
 *
 * <p>The rules under test: liveness and readiness are public (Q-086) and show no component detail;
 * Prometheus is open only on the management port and carries the series the platform alerts read;
 * every other actuator endpoint and every API path stays closed to a caller without a token.
 */
class ActuatorExposureTest {

    private static final String PROBE_PATH = "/api/v1/obs01/probe";

    /** The status of a path that needs a token and has none. */
    private static final int CLOSED = 401;

    /** The status of an actuator path on the application port while the actuator has its own port. */
    private static final int OFF_PORT = 401;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Liveness and readiness answer without a token on the management port, and show no details. */
    @Test
    void healthProbesArePublicOnTheManagementPort() throws Exception {
        try (ConfigurableApplicationContext app = start(true)) {
            int management = port(app, "local.management.port");

            for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                    "/actuator/health/readiness"}) {
                HttpResponse<String> response = get(management, path);

                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(response.body()).as(path).contains("\"status\":\"UP\"")
                        .doesNotContain("\"components\"").doesNotContain("\"details\"");
            }
        }
    }

    /** The scrape is open on the management port and carries the JVM, process and HTTP series the alerts read. */
    @Test
    void prometheusIsOpenOnTheManagementPortAndCarriesTheSeriesTheAlertsRead() throws Exception {
        try (ConfigurableApplicationContext app = start(true)) {
            get(port(app, "local.server.port"), PROBE_PATH);
            HttpResponse<String> response = get(port(app, "local.management.port"), "/actuator/prometheus");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("jvm_memory_used_bytes{")
                    .contains("jvm_memory_max_bytes{")
                    .contains("process_start_time_seconds")
                    .contains("process_uptime_seconds")
                    .contains("http_server_requests_seconds_count{");
        }
    }

    /** With a separate management port the application port never serves the scrape, and never for free. */
    @Test
    void prometheusIsNotOpenOnTheApplicationPortWhenThereIsAManagementPort() throws Exception {
        try (ConfigurableApplicationContext app = start(true)) {
            HttpResponse<String> response = get(port(app, "local.server.port"), "/actuator/prometheus");

            assertThat(response.statusCode()).isEqualTo(OFF_PORT);
            assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
        }
    }

    /** Everything but health and the scrape stays behind a token, on the management port and the application port. */
    @Test
    void everythingElseNeedsATokenOnBothPortsWithAManagementPort() throws Exception {
        try (ConfigurableApplicationContext app = start(true)) {
            int management = port(app, "local.management.port");
            int server = port(app, "local.server.port");

            assertThat(get(management, "/actuator/metrics").statusCode()).as("management metrics").isEqualTo(CLOSED);
            assertThat(get(management, "/actuator/info").statusCode()).as("management info").isEqualTo(CLOSED);
            assertThat(get(server, "/actuator/metrics").statusCode()).as("application metrics").isEqualTo(OFF_PORT);
            assertThat(get(server, "/actuator/info").statusCode()).as("application info").isEqualTo(OFF_PORT);
            assertThat(get(management, PROBE_PATH).statusCode()).as("management api").isEqualTo(CLOSED);
            assertThat(get(server, PROBE_PATH).statusCode()).as("application api").isEqualTo(CLOSED);
        }
    }

    /** Without a management port the actuator shares the application port: health public, the rest closed. */
    @Test
    void withoutAManagementPortOnlyHealthIsPublic() throws Exception {
        try (ConfigurableApplicationContext app = start(false)) {
            int server = port(app, "local.server.port");

            for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                    "/actuator/health/readiness"}) {
                HttpResponse<String> response = get(server, path);

                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(response.body()).as(path).doesNotContain("\"components\"").doesNotContain("\"details\"");
            }
            assertThat(get(server, "/actuator/prometheus").statusCode()).as("prometheus").isEqualTo(CLOSED);
            assertThat(get(server, "/actuator/metrics").statusCode()).as("metrics").isEqualTo(CLOSED);
            assertThat(get(server, "/actuator/info").statusCode()).as("info").isEqualTo(CLOSED);
            assertThat(get(server, PROBE_PATH).statusCode()).as("api").isEqualTo(CLOSED);
        }
    }

    private static ConfigurableApplicationContext start(boolean separateManagementPort) {
        // Command-line arguments outrank the module's own application.properties; builder properties would not.
        return new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        // An empty value counts as unset: the module may ship its own management.server.port.
                        "--management.server.port=" + (separateManagementPort ? "0" : ""),
                        "--management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                        "--management.endpoint.health.probes.enabled=true",
                        "--management.endpoint.health.show-details=when-authorized",
                        // The module may group health contributors this slice does not have (a database, for one).
                        "--management.endpoint.health.validate-group-membership=false",
                        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs",
                        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/dlap",
                        "--spring.main.banner-mode=off");
    }

    private static int port(ConfigurableApplicationContext app, String property) {
        return Integer.parseInt(app.getEnvironment().getRequiredProperty(property));
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Web, security and actuator auto-configuration of Spring Boot plus the real security chain. Nested in the
     * test class so the component scan of the application leaves it out.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        TomcatServletManagementContextAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        Jackson2AutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        // Spring Boot 4 enables web security (the HttpSecurity bean) here, no longer in SecurityAutoConfiguration.
        ServletWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class,
        ManagementContextAutoConfiguration.class,
        ServletManagementContextAutoConfiguration.class,
        EndpointAutoConfiguration.class,
        WebEndpointAutoConfiguration.class,
        ApplicationAvailabilityAutoConfiguration.class,
        AvailabilityHealthContributorAutoConfiguration.class,
        AvailabilityProbesAutoConfiguration.class,
        HealthContributorAutoConfiguration.class,
        HealthEndpointAutoConfiguration.class,
        HealthContributorRegistryAutoConfiguration.class,
        WebMvcHealthEndpointExtensionAutoConfiguration.class,
        InfoContributorAutoConfiguration.class,
        InfoEndpointAutoConfiguration.class,
        MetricsAutoConfiguration.class,
        MetricsEndpointAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class,
        JvmMetricsAutoConfiguration.class,
        SystemMetricsAutoConfiguration.class,
        PrometheusMetricsExportAutoConfiguration.class,
        ObservationAutoConfiguration.class,
        WebMvcObservationAutoConfiguration.class
    })
    @Import({SecurityConfiguration.class, ApiProbe.class})
    static class TestApp {
    }

    /** A path under the API, to show what the chain does to the application itself. */
    @RestController
    static class ApiProbe {

        @GetMapping(PROBE_PATH)
        String probe() {
            return "ok";
        }
    }
}
