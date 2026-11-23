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

package com.dlap.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link CsvWriter}: formula neutralisation (CSV injection) and
 * RFC 4180 quoting.
 */
class CsvWriterTest {

    @ParameterizedTest
    @ValueSource(strings = {"=", "+", "-", "@", "\t", "\r"})
    void textCellStartingWithFormulaTrigger_isPrefixedWithSingleQuote(String trigger) {
        String cell = CsvWriter.cell(trigger + "SUM(A1:A9)");

        // A CR in the cell also forces RFC 4180 quoting; the guard quote stays inside it.
        String expected = trigger.equals("\r")
                ? "\"'" + trigger + "SUM(A1:A9)\""
                : "'" + trigger + "SUM(A1:A9)";
        assertThat(cell).isEqualTo(expected);
    }

    @Test
    void classicInjectionPayloads_areNeutralised() {
        assertThat(CsvWriter.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvWriter.cell("+1+1")).isEqualTo("'+1+1");
        assertThat(CsvWriter.cell("-2+3")).isEqualTo("'-2+3");
        assertThat(CsvWriter.cell("@SUM(1+1)")).isEqualTo("'@SUM(1+1)");
        assertThat(CsvWriter.cell("=cmd|' /C calc'!A0")).isEqualTo("'=cmd|' /C calc'!A0");
    }

    @Test
    void formulaWithCommaAndQuotes_isNeutralisedThenQuoted() {
        String cell = CsvWriter.cell("=HYPERLINK(\"http://x\",\"y\")");

        assertThat(cell).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
    }

    @Test
    void triggerCharactersInsideACell_areLeftAlone() {
        assertThat(CsvWriter.cell("a=b")).isEqualTo("a=b");
        assertThat(CsvWriter.cell("a+b-c@d")).isEqualTo("a+b-c@d");
        assertThat(CsvWriter.cell(" =1")).isEqualTo(" =1");
        assertThat(CsvWriter.cell("'=1")).isEqualTo("'=1");
    }

    @Test
    void numbersAndBooleans_stayRawSoNegativeNumbersStayNumeric() {
        assertThat(CsvWriter.cell(-5)).isEqualTo("-5");
        assertThat(CsvWriter.cell(-1.5d)).isEqualTo("-1.5");
        assertThat(CsvWriter.cell(new BigDecimal("-0.25"))).isEqualTo("-0.25");
        assertThat(CsvWriter.cell(7L)).isEqualTo("7");
        assertThat(CsvWriter.cell(true)).isEqualTo("true");
    }

    @Test
    void numericLookingText_isStillTreatedAsText() {
        assertThat(CsvWriter.cell("-5")).isEqualTo("'-5");
    }

    @Test
    void nullAndEmpty_areEmptyFields() {
        assertThat(CsvWriter.cell(null)).isEmpty();
        assertThat(CsvWriter.cell("")).isEmpty();
    }

    @Test
    void rfc4180Quoting_isApplied() {
        assertThat(CsvWriter.cell("plain")).isEqualTo("plain");
        assertThat(CsvWriter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvWriter.cell("line1\r\nline2")).isEqualTo("\"line1\r\nline2\"");
    }

    @Test
    void row_joinsCellsAndEndsWithLineFeed() {
        List<Object> cells = Arrays.asList("a", null, "=x", 3, "b,c");

        assertThat(CsvWriter.row(cells)).isEqualTo("a,,'=x,3,\"b,c\"\n");
    }

    @Test
    void row_ofNothing_isJustTheLineFeed() {
        assertThat(CsvWriter.row(List.of())).isEqualTo("\n");
    }

    @Test
    void startsWithFormulaTrigger_coversExactlyTheSixCharacters() {
        for (String s : new String[] {"=a", "+a", "-a", "@a", "\ta", "\ra"}) {
            assertThat(CsvWriter.startsWithFormulaTrigger(s)).as(s).isTrue();
        }
        for (String s : new String[] {"a", "0", " =a", "'=a", "\"=a", "#a", ""}) {
            assertThat(CsvWriter.startsWithFormulaTrigger(s)).as(s).isFalse();
        }
        assertThat(CsvWriter.startsWithFormulaTrigger(null)).isFalse();
    }
}
