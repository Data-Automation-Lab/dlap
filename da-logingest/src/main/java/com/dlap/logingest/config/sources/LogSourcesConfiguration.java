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

package com.dlap.logingest.config.sources;

import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.proto.log.v1.SourceProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Wires the bindings file (design B2, ADR-0060): binds {@link LogSourcesProperties}, validates and
 * resolves it into {@link EffectiveLogSources}, and exposes the configuration hash under
 * {@code /actuator/info}.
 *
 * <p>Fail fast: with {@code dlap.logingest.config-validation=auto} (the default) an error fails
 * startup under the {@code prod} profile and is logged as a warning under every other profile,
 * where the affected bindings are marked invalid and do not run; {@code strict} always fails,
 * {@code warn} never does. Validation runs while this bean is created, before the web server
 * accepts requests. The file itself is optional:
 * {@code spring.config.import=optional:file:${DLAP_LOGINGEST_SOURCES_FILE:/etc/dlap/log-sources.yml}}
 * in {@code application.properties}.
 *
 * <p>The built-in REST and fixture-replay bindings ({@link BuiltinBindings}) join the effective
 * configuration only on the registry pipeline; on the legacy pipeline nothing reads bindings.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogSourcesProperties.class)
public class LogSourcesConfiguration {

    /** The environment variable that names the bindings file. */
    public static final String SOURCES_FILE_VARIABLE = "DLAP_LOGINGEST_SOURCES_FILE";

    /** Legacy source switches that must be off on the registry pipeline. */
    static final List<String> LEGACY_SOURCE_SWITCHES = List.of("dlap.logingest.aws.enabled",
            "dlap.logingest.gcp.enabled");

    private static final Logger log = LoggerFactory.getLogger(LogSourcesConfiguration.class);

    /**
     * Validates and resolves the bindings file.
     *
     * @param props      the bound file
     * @param registry   the parser registry
     * @param parsers    every parser bean, for the provider of each source kind
     * @param specs      the family mappings
     * @param transports the registered transports, when the transport layer is present
     * @param pipeline   the selected pipeline
     * @param env        the environment, for profiles and the legacy source switches
     * @return the effective configuration
     * @throws IllegalStateException when validation is strict and finds an error
     */
    @Bean
    public EffectiveLogSources effectiveLogSources(LogSourcesProperties props, DefaultParserRegistry registry,
                                                   ObjectProvider<LogRecordParser> parsers, MappingSpecs specs,
                                                   ObjectProvider<TransportCatalog> transports, PipelineMode pipeline,
                                                   Environment env) {
        Map<String, SourceProvider> providers = new TreeMap<>();
        for (MappingSpec spec : specs.all()) {
            providers.put(spec.sourceKind(), spec.sourceProvider());
        }
        parsers.orderedStream().forEach(p -> providers.putIfAbsent(p.envelopeKey().sourceKind(), p.sourceProvider()));
        List<String> legacy = LEGACY_SOURCE_SWITCHES.stream()
                .filter(k -> Boolean.TRUE.equals(env.getProperty(k, Boolean.class, false)))
                .toList();
        LogSourceConfigValidator.Context ctx = new LogSourceConfigValidator.Context(registry, providers, specs,
                transports.getIfAvailable(TransportCatalog::none), pipeline, legacy, System::getenv,
                registry.describe());
        LogSourceConfigValidator.Result result = new LogSourceConfigValidator(ctx).validate(props);

        boolean strict = switch (result.mode()) {
            case STRICT -> true;
            case WARN -> false;
            case AUTO -> env.acceptsProfiles(Profiles.of("prod"));
        };
        report(result.report(), strict);

        List<EffectiveBinding> all = new ArrayList<>(result.bindings());
        if (pipeline == PipelineMode.REGISTRY) {
            all.addAll(BuiltinBindings.all());
        }
        EffectiveLogSources sources = new EffectiveLogSources(pipeline, result.configMode(), all, result.report());
        log.info("Log sources: pipeline={} bindings={} (file {}, built-in {}) configHash={} file {}",
                pipeline, all.size(), result.bindings().size(), all.size() - result.bindings().size(),
                sources.configHash(), env.getProperty(SOURCES_FILE_VARIABLE) == null ? "not configured" : "configured");
        return sources;
    }

    /**
     * The {@code /actuator/info} contributor of the log-source configuration.
     *
     * @param sources the effective configuration
     * @return the contributor
     */
    @Bean
    public LogSourcesInfoContributor logSourcesInfoContributor(EffectiveLogSources sources) {
        return new LogSourcesInfoContributor(sources);
    }

    /**
     * Logs every finding and fails on an error when validation is strict.
     *
     * @param report the findings
     * @param strict whether an error fails startup
     */
    static void report(ValidationReport report, boolean strict) {
        for (ConfigProblem p : report.warnings()) {
            log.warn("Log sources configuration: {}", p);
        }
        for (ConfigProblem p : report.errors()) {
            if (strict) {
                log.error("Log sources configuration: {}", p);
            } else {
                log.warn("Log sources configuration (binding does not run): {}", p);
            }
        }
        if (strict && report.hasErrors()) {
            throw new IllegalStateException("Invalid log sources configuration (" + report.errors().size()
                    + " error(s)): " + report.errors().stream().map(ConfigProblem::toString)
                    .collect(Collectors.joining("; ")));
        }
    }
}
