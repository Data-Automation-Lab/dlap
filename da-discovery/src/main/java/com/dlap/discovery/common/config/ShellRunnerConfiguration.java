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

package com.dlap.discovery.common.config;

import com.dlap.discovery.application.DADiscoveryAgent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.core.ShellRunner;

/**
 * Starts the Spring Shell runner only when the command line names a shell command.
 *
 * <p>Spring Shell 4 auto-configures an {@code ApplicationRunner} named
 * {@code springShellApplicationRunner} that always hands the command line to the shell. This
 * service is a server first: a regular start (no arguments, or Spring Boot options such as
 * {@code --spring.profiles.active=prod}) must not reach the shell, as with Spring Shell 3, where
 * both shell runners were switched off for a regular start. This bean takes that name, so the
 * shell auto-configuration backs off, and runs the shell only for a shell command (BOOT-01).
 */
@Configuration
public class ShellRunnerConfiguration {

    /**
     * The gated shell application runner.
     *
     * @param shellRunner the shell runner Spring Shell configures (resolved only when needed)
     * @return the application runner
     */
    @Bean(name = "springShellApplicationRunner")
    public ApplicationRunner springShellApplicationRunner(ObjectProvider<ShellRunner> shellRunner) {
        return args -> {
            String[] commandLine = args.getSourceArgs();
            if (DADiscoveryAgent.isShellCommand(commandLine)) {
                shellRunner.getObject().run(commandLine);
            }
        };
    }
}
