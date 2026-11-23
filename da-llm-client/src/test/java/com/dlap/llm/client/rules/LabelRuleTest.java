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

package com.dlap.llm.client.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.dlap.llm.client.rules.LabelRule.Field;

/** Validation and matching rules of {@link LabelRule} and {@link TextNormalizer}. */
class LabelRuleTest {

    // ---- TextNormalizer -------------------------------------------------

    @Test
    void normalize_splitsCaseSeparatorsAndAcronyms() {
        assertThat(TextNormalizer.normalize("Customer_PII-export.csv")).isEqualTo(" customer pii export csv ");
        assertThat(TextNormalizer.normalize("customerPII")).isEqualTo(" customer pii ");
        assertThat(TextNormalizer.normalize("PIIData")).isEqualTo(" pii data ");
        assertThat(TextNormalizer.normalize("e-mail")).isEqualTo(" e mail ");
        assertThat(TextNormalizer.normalize("icd10 codes")).isEqualTo(" icd10 codes ");
    }

    @Test
    void normalize_emptyInput_isASingleSpace() {
        assertThat(TextNormalizer.normalize(null)).isEqualTo(" ");
        assertThat(TextNormalizer.normalize("")).isEqualTo(" ");
        assertThat(TextNormalizer.normalize("___")).isEqualTo(" ");
    }

    // ---- keyword rules --------------------------------------------------

    @Test
    void keywordRule_normalizesItsKeywords() {
        LabelRule rule = LabelRule.keywords("t.k", "PII", "Sensitivity", 0.9, LabelRule.ANY_FIELD, "d",
                "Social Security", "E-Mail");

        assertThat(rule.keywords()).containsExactly("social security", "e mail");
        assertThat(rule.match(TextNormalizer.normalize("my_social_security_file"), "")).isEqualTo("keyword 'social security'");
        assertThat(rule.match(TextNormalizer.normalize("work-email"), "")).isNull();
        assertThat(rule.match(TextNormalizer.normalize("work e-mails"), "")).isEqualTo("keyword 'e mail'");
    }

    @Test
    void keywordRule_matchesPluralButNotSubstring() {
        LabelRule rule = LabelRule.keywords("t.k", "PII", "Sensitivity", 0.9, LabelRule.ANY_FIELD, "d", "invoice");

        assertThat(rule.match(TextNormalizer.normalize("invoices"), "")).isNotNull();
        assertThat(rule.match(TextNormalizer.normalize("invoiced"), "")).isNull();
        assertThat(rule.match(TextNormalizer.normalize("preinvoice"), "")).isNull();
    }

    // ---- regex rules ----------------------------------------------------

    @Test
    void regexRule_searchesRawTextAndReportsNoContent() {
        LabelRule rule = LabelRule.regex("t.r", "PII", "Sensitivity", 0.9, LabelRule.ANY_FIELD, "d", "\\d{3}-\\d{2}-\\d{4}");

        assertThat(rule.match(" ", "value 123-45-6789 here")).isEqualTo("pattern");
        assertThat(rule.match(" ", "no match")).isNull();
        assertThat(rule.pattern()).isNotNull();
        assertThat(rule.keywords()).isNull();
    }

    // ---- validation -----------------------------------------------------

    @Test
    void constructor_rejectsInvalidDefinitions() {
        Set<Field> any = LabelRule.ANY_FIELD;
        List<String> kw = List.of("x");
        Pattern p = Pattern.compile("x");

        assertThatThrownBy(() -> new LabelRule(" ", "d", "PII", "c", 0.5, any, kw, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "", "c", 0.5, any, kw, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", null, 0.5, any, kw, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", "c", 1.01, any, kw, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", "c", -0.01, any, kw, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", "c", Double.NaN, any, kw, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", "c", 0.5, EnumSet.noneOf(Field.class), kw, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", "c", 0.5, any, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", "c", 0.5, any, kw, p))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelRule("id", "d", "PII", "c", 0.5, any, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factories_rejectBlankKeywordsAndBadRegex() {
        assertThatThrownBy(() -> LabelRule.keywords("id", "PII", "c", 0.5, LabelRule.ANY_FIELD, "d", "___"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank keyword");
        assertThatThrownBy(() -> LabelRule.regex("id", "PII", "c", 0.5, LabelRule.ANY_FIELD, "d", "("))
                .isInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    @Test
    void nullDescription_becomesEmpty() {
        LabelRule rule = new LabelRule("id", null, "PII", "c", 0.5, LabelRule.ANY_FIELD, List.of("x"), null);

        assertThat(rule.description()).isEmpty();
    }

    @Test
    void fieldSets_areImmutableAndNamed() {
        assertThat(LabelRule.NAME_AND_PATH).containsExactlyInAnyOrder(Field.NAME, Field.PATH);
        assertThat(LabelRule.METADATA_ONLY).containsExactly(Field.METADATA);
        assertThat(LabelRule.ANY_FIELD).containsExactlyInAnyOrder(Field.values());
        assertThatThrownBy(() -> LabelRule.ANY_FIELD.add(Field.NAME)).isInstanceOf(UnsupportedOperationException.class);
    }
}
