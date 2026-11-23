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

package com.dlap.policyengine.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * POL-01: a schedule trigger must carry a cron the sweeper can fire, so a schedule-only policy
 * is refused at save time instead of never evaluating; every published example uses only
 * clauses the evaluator knows.
 */
class PolicyDslParserScheduleTest {

    private final PolicyDslParser parser = new PolicyDslParser();

    private static String withTrigger(String trigger) {
        return "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: p}\nspec:\n"
                + "  triggers: [" + trigger + "]\n  match: {cloudProvider: GCP}\n  action: {type: alert}\n";
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 0 2 * * ?", "0 30 4 * * MON-FRI", "0 0 0 1 1 ?", "0 0 3 L * ?"})
    void sixFieldCronsParse(String cron) {
        PolicyDsl dsl = parser.parse(withTrigger("{type: schedule, cron: '" + cron + "'}"));
        assertThat(dsl.getSpec().getTriggers().get(0).getCron()).isEqualTo(cron);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 2 * * *", "0 0 2 * * ? 2027", "every day", "61 0 2 * * ?"})
    void aCronTheSweeperCannotFireIsRefused(String cron) {
        assertThatThrownBy(() -> parser.parse(withTrigger("{type: schedule, cron: '" + cron + "'}")))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("invalid schedule cron");
    }

    @Test
    void aScheduleTriggerWithoutACronIsRefused() {
        assertThatThrownBy(() -> parser.parse(withTrigger("{type: schedule}")))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("requires a cron");
    }

    @Test
    void anEventTriggerNeedsNoCron() {
        assertThat(parser.parse(withTrigger("{type: event, lifecycle: created}")).getSpec().getTriggers()).hasSize(1);
    }

    @Test
    void everyExampleUsesOnlyKnownClauses() throws IOException {
        Path examples = locate("docs/examples");
        Pattern separator = Pattern.compile("(?m)^---[ \\t]*$");
        try (Stream<Path> walk = Files.walk(examples)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                for (String doc : separator.split(Files.readString(file, StandardCharsets.UTF_8))) {
                    if (doc.isBlank()) {
                        continue;
                    }
                    Map<String, Object> match = parser.parse(doc).getSpec().getMatch();
                    if (match != null) {
                        assertThat(PolicyDslEvaluator.CLAUSES).as("clauses of %s", file.getFileName())
                                .containsAll(match.keySet());
                    }
                }
            }
        }
    }

    private static Path locate(String relative) {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(relative))) {
                return dir.resolve(relative);
            }
        }
        throw new AssertionError(relative + " not found from " + start);
    }
}
