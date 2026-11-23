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

package com.dlap.discovery.application;

import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.config.ApplicationProperties;
import com.dlap.discovery.common.config.CRLFLogConverter;
import com.dlap.discovery.common.config.DynamicConfig;
import com.dlap.discovery.crawler.service.IDiscoveryService;
import com.dlap.discovery.log.service.ILogAnalyzer;
import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import tech.jhipster.config.DefaultProfileUtil;
import tech.jhipster.config.JHipsterConstants;

@SpringBootApplication
@EnableConfigurationProperties({ ApplicationProperties.class, DynamicConfig.class })
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.dlap.discovery.client.feign")
@EnableScheduling
@EnableAspectJAutoProxy(proxyTargetClass = true)
@ComponentScan(basePackages = { "com.dlap.discovery" })
@EntityScan(basePackages = { "com.dlap.discovery" })
@EnableJpaRepositories(basePackages = { "com.dlap.discovery" })
public class DADiscoveryAgent {

    private static final Logger log = LoggerFactory.getLogger(DADiscoveryAgent.class);

    private final Environment env;
    private final DynamicConfig dynamicConfig;
    private final ICatalogService catalogService;
    private final Collection<IDiscoveryService<?>> discoveryServices;
    private Collection<ILogAnalyzer> logAnalyzerServices;

    public DADiscoveryAgent(
        Environment env,
        DynamicConfig dynamicConfig,
        ICatalogService catalogService,
        Collection<IDiscoveryService<?>> discoveryServices,
        Collection<ILogAnalyzer> logAnalyzerServices
    ) {
        this.env = env;
        this.dynamicConfig = dynamicConfig;
        this.catalogService = catalogService;
        this.discoveryServices = discoveryServices;
        this.logAnalyzerServices = logAnalyzerServices;
    }

    /**
     * Initializes DGCrawler.
     * <p>
     * Spring profiles can be configured with a program argument
     * --spring.profiles.active=your-active-profile
     * <p>
     * You can find more information on how profiles work with JHipster on <a href=
     * "https://www.jhipster.tech/profiles/">https://www.jhipster.tech/profiles/</a>.
     */
    @PostConstruct
    public void initApplication() {
        Collection<String> activeProfiles = Arrays.asList(env.getActiveProfiles());
        if (
            activeProfiles.contains(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT) &&
            activeProfiles.contains(JHipsterConstants.SPRING_PROFILE_PRODUCTION)
        ) {
            log.error(
                "You have misconfigured your application! It should not run " + "with both the 'dev' and 'prod' profiles at the same time."
            );
        }
        if (
            activeProfiles.contains(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT) &&
            activeProfiles.contains(JHipsterConstants.SPRING_PROFILE_CLOUD)
        ) {
            log.error(
                "You have misconfigured your application! It should not " + "run with both the 'dev' and 'cloud' profiles at the same time."
            );
        }

        bootupDiscoveryServices(discoveryServices);
        bootupLogAnalyzerServices(logAnalyzerServices);
    }

    /**
     * Main method, used to run the application.
     *
     * @param args the command line arguments.
     */
    public static void main(String[] args) {
        // Determine if we're running a shell command or regular application. Spring Shell 4
        // (BOOT-01) has a single runner: non-interactive runs the command given in the arguments,
        // which is what Spring Shell 3 did whenever arguments were present. ShellRunnerConfiguration
        // starts it only for a shell command, never for a regular start.
        boolean isShellCommand = isShellCommand(args);
        System.setProperty("spring.shell.interactive.enabled", "false");
        log.info("Starting application in {} mode", isShellCommand ? "shell" : "regular");
        // Initialize Spring application
        SpringApplication app = new SpringApplication(DADiscoveryAgent.class);

        // Add default profile if needed
        DefaultProfileUtil.addDefaultProfile(app);

        // Run the application
        Environment env = app.run(args).getEnvironment();

        // Log application startup
        logApplicationStartup(env);
    }

    /**
     * Whether the command line asks for a shell command rather than a regular start: the first
     * argument is a command name, not a Spring Boot option.
     *
     * @param args the command line arguments
     * @return true for a shell command
     */
    public static boolean isShellCommand(String[] args) {
        return args != null && args.length > 0 && args[0] != null && !args[0].startsWith("-");
    }

    /**
     * Bootup and configure the discovery services.
     *
     * @param discoveryServices
     */
    private void bootupDiscoveryServices(Collection<IDiscoveryService<?>> discoveryServices) {}

    /**
     * Bootup and configure the log analyzer services.
     *
     * @param logAnalyzerServices
     */
    private void bootupLogAnalyzerServices(Collection<ILogAnalyzer> logAnalyzerServices) {}

    private static void logApplicationStartup(Environment env) {
        String protocol = Optional.ofNullable(env.getProperty("server.ssl.key-store")).map(key -> "https").orElse("http");
        String applicationName = env.getProperty("spring.application.name");
        String serverPort = env.getProperty("server.port");
        String contextPath = Optional
            .ofNullable(env.getProperty("server.servlet.context-path"))
            .filter(StringUtils::isNotBlank)
            .orElse("/");
        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("The host name could not be determined, using `localhost` as fallback");
        }
        log.info(
            CRLFLogConverter.CRLF_SAFE_MARKER,
            """

            ----------------------------------------------------------
            \tApplication '{}' is running! Access URLs:
            \tLocal: \t\t{}://localhost:{}{}
            \tExternal: \t{}://{}:{}{}
            \tProfile(s): \t{}
            ----------------------------------------------------------""",
            applicationName,
            protocol,
            serverPort,
            contextPath,
            protocol,
            hostAddress,
            serverPort,
            contextPath,
            env.getActiveProfiles().length == 0 ? env.getDefaultProfiles() : env.getActiveProfiles()
        );

        String configServerStatus = env.getProperty("configserver.status");
        if (configServerStatus == null) {
            configServerStatus = "Not found or not setup for this application";
        }
        log.info(
            CRLFLogConverter.CRLF_SAFE_MARKER,
            "\n----------------------------------------------------------\n\t" +
            "Config Server: \t{}\n----------------------------------------------------------",
            configServerStatus
        );
    }
}
