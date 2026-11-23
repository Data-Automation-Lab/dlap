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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import com.dlap.catalog.rekey.RekeyOutcome.Status;

/** The command line: command parsing, listener stop, report output and exit codes. */
class RekeyCommandRunnerTest {

    private final AssetUriRekeyService service = mock(AssetUriRekeyService.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final RekeyProperties properties = new RekeyProperties();
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final List<Integer> exits = new ArrayList<>();
    private RekeyCommandRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        runner = new RekeyCommandRunner(service, properties, provider, context);
        runner.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        runner.setExiter(exits::add);
    }

    @Test
    void aBlankCommandDoesNothingAndDoesNotExit() {
        properties.setCommand("  ");
        runner.run(new DefaultApplicationArguments());
        verifyNoInteractions(service, registry);
        assertThat(exits).isEmpty();
    }

    @Test
    void anUnknownCommandExitsTwoWithoutTouchingTheDatabase() {
        properties.setCommand("delete-everything");
        runner.run(new DefaultApplicationArguments());
        verifyNoInteractions(service);
        assertThat(exits).containsExactly(2);
    }

    @Test
    void theDryRunStopsTheListenersPrintsTheReportBetweenMarkersAndExitsZero() {
        properties.setCommand("DRY-RUN");
        when(registry.isRunning()).thenReturn(true);
        when(service.dryRun()).thenReturn(new RekeyOutcome(Status.DONE, null, "ok", Map.of("command", "dry-run")));

        runner.run(new DefaultApplicationArguments());

        verify(registry).stop();
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertThat(out).contains(RekeyCommandRunner.BEGIN_MARKER).contains(RekeyCommandRunner.END_MARKER)
                .contains("\"command\" : \"dry-run\"");
        assertThat(out.indexOf(RekeyCommandRunner.BEGIN_MARKER)).isLessThan(out.indexOf("dry-run"));
        assertThat(exits).containsExactly(0);
    }

    @Test
    void aRefusedApplyExitsTwoAndWritesTheReportFile(@TempDir Path dir) throws Exception {
        properties.setCommand("apply");
        Path file = dir.resolve("report.json");
        properties.setReportFile(file);
        when(service.apply()).thenReturn(new RekeyOutcome(Status.REFUSED, null, "blocked",
                Map.of("command", "apply", "blockers", List.of(Map.of("kind", "X")))));

        runner.run(new DefaultApplicationArguments());

        verify(registry, never()).stop();
        assertThat(Files.readString(file)).contains("\"blockers\"").contains("\"X\"");
        assertThat(exits).containsExactly(2);
    }

    @Test
    void revertPassesTheConfiguredRunAndNothingToDoExitsZero() {
        UUID run = UUID.randomUUID();
        properties.setCommand("revert");
        properties.setRunId(run);
        when(service.revert(run)).thenReturn(new RekeyOutcome(Status.DONE, run, "ok", Map.of()));
        assertThat(runner.execute()).isZero();

        properties.setCommand("apply");
        when(service.apply()).thenReturn(new RekeyOutcome(Status.NOTHING_TO_DO, null, "none", Map.of()));
        assertThat(runner.execute()).isZero();
    }

    @Test
    void aDatabaseFailureExitsOne() {
        properties.setCommand("apply");
        when(service.apply()).thenThrow(new QueryTimeoutException("lock timeout"));
        assertThat(runner.execute()).isEqualTo(1);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).doesNotContain(RekeyCommandRunner.BEGIN_MARKER);
    }

    @Test
    void anUnwritableReportFileExitsOne(@TempDir Path dir) {
        properties.setCommand("dry-run");
        properties.setReportFile(dir.resolve("missing-folder").resolve("report.json"));
        when(service.dryRun()).thenReturn(new RekeyOutcome(Status.DONE, null, "ok", Map.of()));
        assertThat(runner.execute()).isEqualTo(1);
    }

    @Test
    void exitCanBeSwitchedOff() {
        properties.setCommand("dry-run");
        properties.setExit(false);
        when(service.dryRun()).thenReturn(new RekeyOutcome(Status.DONE, null, "ok", Map.of()));
        runner.run(new DefaultApplicationArguments());
        assertThat(exits).isEmpty();
    }

    @Test
    void commandsParseAndPropertiesValidate() {
        assertThat(RekeyCommand.parse(" Apply ")).contains(RekeyCommand.APPLY);
        assertThat(RekeyCommand.parse(null)).isEmpty();
        assertThat(RekeyCommand.REVERT.text()).isEqualTo("revert");
        RekeyProperties p = new RekeyProperties();
        p.setCommand(null);
        assertThat(p.getCommand()).isEmpty();
        p.setLockTimeout(Duration.ofSeconds(5));
        assertThat(p.getLockTimeout()).hasSeconds(5);
        p.setOperator("ops");
        assertThat(p.getOperator()).isEqualTo("ops");
        assertThat(p.isExit()).isTrue();
        assertThatThrownBy(() -> p.setLockTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RekeyOutcome(Status.REFUSED, null, "x", Map.of()).exitCode()).isEqualTo(2);
        assertThatThrownBy(() -> new AssetUriRekeyExecutor(null, null).setBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void undoKeysCompareOnThePrimaryKeyColumnsOnly() {
        assertThat(AssetUriRekeyReverter.sameKey(RekeyTable.USAGE,
                "{\"asset_id\":\"a\",\"day\":\"2026-10-05\",\"reads\":1}", "{\"asset_id\":\"a\",\"day\":\"2026-10-05\"}"))
                .isTrue();
        assertThat(AssetUriRekeyReverter.sameKey(RekeyTable.USAGE,
                "{\"asset_id\":\"a\",\"day\":\"2026-10-05\"}", "{\"asset_id\":\"b\",\"day\":\"2026-10-05\"}")).isFalse();
        assertThatThrownBy(() -> AssetUriRekeyReverter.sameKey(RekeyTable.ASSETS, "{", "{}"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(RekeyTable.fromUndoName("asset_usage_daily")).contains(RekeyTable.USAGE);
        assertThat(RekeyTable.fromUndoName("nope")).isEmpty();
        assertThat(RekeyTable.LABELS.keyColumns()).containsExactly("asset_id", "label_id");
        assertThat(RekeyTable.USAGE.qualified()).isEqualTo("dlap_catalog.asset_usage_daily");
        assertThat(AssetUriRekeyReverter.unknownAssetColumns(java.util.Set.of("asset_id", "asset_uri", "new_col")))
                .containsExactly("new_col");
    }
}
