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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.discovery.application.DADiscoveryAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.shell.core.ShellRunner;

/**
 * BOOT-01: Spring Shell 4 hands every command line to the shell; the gated runner keeps a regular
 * server start away from it, as Spring Shell 3 did with both shell runners switched off.
 */
class ShellRunnerConfigurationTest {

    @Test
    void regularStartWithoutArgumentsDoesNotReachTheShell() throws Exception {
        ShellRunner shell = mock(ShellRunner.class);

        runner(shell).run(new DefaultApplicationArguments());

        verify(shell, never()).run(any());
    }

    @Test
    void regularStartWithSpringBootOptionsDoesNotReachTheShell() throws Exception {
        ShellRunner shell = mock(ShellRunner.class);

        runner(shell).run(new DefaultApplicationArguments("--spring.profiles.active=prod", "--server.port=8091"));

        verify(shell, never()).run(any());
    }

    @Test
    void shellCommandRunsInTheShell() throws Exception {
        ShellRunner shell = mock(ShellRunner.class);
        String[] commandLine = {"list-jobs", "--provider", "gcp"};

        runner(shell).run(new DefaultApplicationArguments(commandLine));

        verify(shell).run(commandLine);
    }

    @Test
    void shellCommandDetection() {
        assertThat(DADiscoveryAgent.isShellCommand(new String[0])).isFalse();
        assertThat(DADiscoveryAgent.isShellCommand(null)).isFalse();
        assertThat(DADiscoveryAgent.isShellCommand(new String[] {"--spring.profiles.active=dev"})).isFalse();
        assertThat(DADiscoveryAgent.isShellCommand(new String[] {"app-status"})).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static ApplicationRunner runner(ShellRunner shell) {
        ObjectProvider<ShellRunner> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(shell);
        return new ShellRunnerConfiguration().springShellApplicationRunner(provider);
    }
}
