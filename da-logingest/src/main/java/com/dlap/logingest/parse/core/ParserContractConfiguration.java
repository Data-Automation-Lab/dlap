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

package com.dlap.logingest.parse.core;

import com.dlap.logingest.parse.core.mapping.MappingSpecLoader;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Wires the frozen parser contract (ADR-0054): the pipeline mode, the family mappings found on
 * the classpath, the registry assembled from every parser and detector bean, and the mapper.
 *
 * <p>Self-registration: a family adds {@code LogRecordParser} beans (and, for envelopes without a
 * mapping YAML, {@code VersionDetector} beans) in its own package; nothing here names a family.
 * Every envelope of a loaded mapping YAML gets a declarative detector.
 *
 * <p>These beans are inert until a registry-pipeline component calls them: this package does not
 * wire the registry into the live ingest path, and the legacy pipeline stays the default
 * ({@link PipelineMode#DEFAULT}). Startup fails on an unknown pipeline value, an invalid mapping
 * YAML or an ambiguous set of parsers.
 */
@Configuration(proxyBeanMethods = false)
public class ParserContractConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ParserContractConfiguration.class);

    /**
     * The pipeline mode.
     *
     * @param env the environment
     * @return the mode
     * @throws IllegalArgumentException when {@value PipelineMode#PROPERTY} is not legacy or registry
     */
    @Bean
    public PipelineMode logIngestPipelineMode(Environment env) {
        PipelineMode mode = PipelineMode.resolve(env);
        log.info("Log ingest pipeline: {} ({}={})", mode, PipelineMode.PROPERTY,
                env.getProperty(PipelineMode.PROPERTY, "<unset>"));
        return mode;
    }

    /**
     * Every family mapping on the classpath.
     *
     * @param resolver the resource resolver; the application context when present
     * @return the mappings
     */
    @Bean
    public MappingSpecs logFormatMappingSpecs(ObjectProvider<ResourcePatternResolver> resolver) {
        ResourcePatternResolver r = resolver.getIfAvailable(PathMatchingResourcePatternResolver::new);
        MappingSpecs specs = new MappingSpecs(MappingSpecLoader.loadClasspath(r));
        log.info("Log-format mappings loaded: {}", specs.all().stream().map(s -> s.family()).toList());
        return specs;
    }

    /**
     * The registry.
     *
     * @param parsers   every parser bean
     * @param detectors every detector bean
     * @param specs     the family mappings, which contribute declarative detectors
     * @return the registry
     */
    @Bean
    public DefaultParserRegistry logParserRegistry(ObjectProvider<LogRecordParser> parsers,
                                                   ObjectProvider<VersionDetector> detectors,
                                                   MappingSpecs specs) {
        List<VersionDetector> all = new ArrayList<>(detectors.orderedStream().toList());
        all.addAll(specs.detectors());
        DefaultParserRegistry registry = new DefaultParserRegistry(parsers.orderedStream().toList(), all);
        log.info("Log parser registry: {}", registry.describe());
        return registry;
    }

    /**
     * The mapper from parse outcomes to the wire contract.
     *
     * @param clock the application's {@code Clock} bean when it has exactly one or a primary one;
     *              UTC system clock otherwise, so a second {@code Clock} bean cannot fail startup
     * @return the mapper
     */
    @Bean
    public CanonicalEventMapper canonicalEventMapper(ObjectProvider<Clock> clock) {
        return new CanonicalEventMapper(clock.getIfUnique(Clock::systemUTC));
    }
}
