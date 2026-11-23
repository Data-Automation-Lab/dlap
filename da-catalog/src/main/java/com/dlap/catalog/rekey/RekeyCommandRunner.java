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

package com.dlap.catalog.rekey;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.function.IntConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The command line of the re-key: when the catalog starts with {@code dlap.catalog.rekey.command}
 * set, this runner stops the catalog's Kafka listeners, runs the command, prints the JSON report
 * between two marker lines on standard output (and to {@code dlap.catalog.rekey.report-file} when
 * set), and exits: 0 done or nothing to do, 2 refused, 1 failed. Runners run before the
 * application-ready event, so the startup index build never starts in this mode.
 */
@Component
@ConditionalOnProperty(prefix = "dlap.catalog.rekey", name = "command")
public class RekeyCommandRunner implements ApplicationRunner {

    /** First line of the report on standard output. */
    public static final String BEGIN_MARKER = "----- BEGIN DLAP ASSET URI REKEY REPORT -----";

    /** Last line of the report on standard output. */
    public static final String END_MARKER = "----- END DLAP ASSET URI REKEY REPORT -----";

    private static final Logger log = LoggerFactory.getLogger(RekeyCommandRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final AssetUriRekeyService service;
    private final RekeyProperties properties;
    private final ObjectProvider<KafkaListenerEndpointRegistry> listeners;
    private final ApplicationContext context;
    private PrintStream out = System.out;
    private IntConsumer exiter = System::exit;

    /**
     * Creates the runner.
     *
     * @param service    the re-key
     * @param properties the command and its options
     * @param listeners  the Kafka listener registry, when Kafka is configured
     * @param context    the application context, closed before the process exits
     */
    public RekeyCommandRunner(AssetUriRekeyService service, RekeyProperties properties,
                              ObjectProvider<KafkaListenerEndpointRegistry> listeners, ApplicationContext context) {
        this.service = service;
        this.properties = properties;
        this.listeners = listeners;
        this.context = context;
    }

    /**
     * Runs the configured command.
     *
     * @param args the application arguments (unused; the command comes from the properties)
     */
    @Override
    public void run(ApplicationArguments args) {
        if (properties.getCommand().isBlank()) {
            return;
        }
        int code = execute();
        if (properties.isExit()) {
            int exitCode = SpringApplication.exit(context, () -> code);
            exiter.accept(exitCode);
        }
    }

    /**
     * Runs the command and returns its exit code, without exiting.
     *
     * @return 0 done or nothing to do, 2 refused or an unknown command, 1 failed
     */
    int execute() {
        Optional<RekeyCommand> command = RekeyCommand.parse(properties.getCommand());
        if (command.isEmpty()) {
            log.error("Unknown dlap.catalog.rekey.command '{}': use dry-run, apply or revert",
                    properties.getCommand().length() > 32 ? properties.getCommand().substring(0, 32) : properties.getCommand());
            return 2;
        }
        KafkaListenerEndpointRegistry registry = listeners.getIfAvailable();
        if (registry != null && registry.isRunning()) {
            log.info("Re-key mode: stopping the catalog's Kafka listeners before {}", command.get().text());
            registry.stop();
        }
        try {
            RekeyOutcome outcome = switch (command.get()) {
                case DRY_RUN -> service.dryRun();
                case APPLY -> service.apply();
                case REVERT -> service.revert(properties.getRunId());
            };
            String json = JSON.writeValueAsString(outcome.report());
            out.println(BEGIN_MARKER);
            out.println(json);
            out.println(END_MARKER);
            out.flush();
            if (properties.getReportFile() != null) {
                Files.writeString(properties.getReportFile(), json + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            if (outcome.status() == RekeyOutcome.Status.REFUSED) {
                log.warn("Re-key {}: {}", command.get().text(), outcome.message());
            } else {
                log.info("Re-key {}: {}", command.get().text(), outcome.message());
            }
            return outcome.exitCode();
        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Re-key {} failed; nothing was committed: {}", command.get().text(), e.getMessage(), e);
            return 1;
        } catch (IOException e) {
            log.error("Re-key {} finished but the report file could not be written: {}", command.get().text(),
                    e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Test seam: where the report goes.
     *
     * @param out the stream
     */
    void setOut(PrintStream out) {
        this.out = out;
    }

    /**
     * Test seam: what exits the process.
     *
     * @param exiter the exit function
     */
    void setExiter(IntConsumer exiter) {
        this.exiter = exiter;
    }
}
