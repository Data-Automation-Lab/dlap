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

import static com.dlap.llm.client.rules.LabelRule.Field.METADATA;
import static com.dlap.llm.client.rules.LabelRule.Field.NAME;
import static com.dlap.llm.client.rules.LabelRule.Field.PATH;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Parsing of the {@code Key: value} asset descriptions the auto-label pipeline sends. */
class AssetTextTest {

    @Test
    void orchestratorFormat_splitsNameMetadataAndIgnoresTheRest() {
        AssetText text = AssetText.parse("Asset name: customer_profiles\nCloud provider: GCP\n"
                + "Asset type: BIGQUERY_TABLE\nRegion: us-central1\nExisting labels: ANALYTICS, PII\n"
                + "Business metadata: {owner=data-team, steward=jo}");

        assertThat(text.raw(NAME)).isEqualTo("customer_profiles");
        assertThat(text.raw(PATH)).isEmpty();
        assertThat(text.raw(METADATA)).isEqualTo("{owner=data-team, steward=jo}");
    }

    @Test
    void jobServiceFormat_isParsedTheSame() {
        AssetText text = AssetText.parse("Asset name: customer_profiles\nType: BIGQUERY_TABLE\n"
                + "Provider: GCP\nRegion: us\nLabels: PII\nBusiness metadata: {a=b}\n");

        assertThat(text.raw(NAME)).isEqualTo("customer_profiles");
        assertThat(text.raw(METADATA)).isEqualTo("{a=b}");
    }

    @Test
    void nameThatLooksLikeAPath_isAlsoThePath() {
        for (String name : new String[] {"s3://bucket/key.csv", "proj.dataset.table", "a/b", "C:\\data\\x"}) {
            AssetText text = AssetText.parse("Asset name: " + name);

            assertThat(text.raw(NAME)).isEqualTo(name);
            assertThat(text.raw(PATH)).as(name).isEqualTo(name);
        }
        assertThat(AssetText.parse("Asset name: plain_table").raw(PATH)).isEmpty();
    }

    @Test
    void explicitPath_winsOverTheNameFallback() {
        AssetText text = AssetText.parse("Asset name: a.b\nPath: gs://bucket/hr/");

        assertThat(text.raw(PATH)).isEqualTo("gs://bucket/hr/");
    }

    @Test
    void metadataBlock_swallowsLaterLinesEvenWithNameLikeKeys() {
        AssetText text = AssetText.parse("Asset name: quarterly\nBusiness metadata: {x=1}\nname: patient\nregion: eu");

        assertThat(text.raw(NAME)).isEqualTo("quarterly");
        assertThat(text.raw(METADATA)).isEqualTo("{x=1}\nname: patient\nregion: eu");
    }

    @Test
    void unknownKeyLines_goToMetadata() {
        AssetText text = AssetText.parse("Asset name: quarterly\nOwner: finance-team");

        assertThat(text.raw(METADATA)).isEqualTo("Owner: finance-team");
    }

    @Test
    void bareText_fillsEveryField() {
        for (String bare : new String[] {"customer_pii.csv", "gs://bucket/customer_pii.csv", "s3://b/k"}) {
            AssetText text = AssetText.parse(bare);

            assertThat(text.raw(NAME)).isEqualTo(bare);
            assertThat(text.raw(PATH)).isEqualTo(bare);
            assertThat(text.raw(METADATA)).isEqualTo(bare);
        }
    }

    @Test
    void nullAndBlank_giveEmptyFields() {
        for (String empty : new String[] {null, "", "  \n "}) {
            AssetText text = AssetText.parse(empty);

            assertThat(text.normalized(NAME).strip()).isEmpty();
            assertThat(text.normalized(METADATA).strip()).isEmpty();
        }
    }

    @Test
    void windowsLineEndings_areHandled() {
        AssetText text = AssetText.parse("Asset name: a_table\r\nBusiness metadata: {k=v}\r\n");

        assertThat(text.raw(NAME)).isEqualTo("a_table");
        assertThat(text.raw(METADATA)).isEqualTo("{k=v}");
    }

    @Test
    void oversizedFields_areTruncated() {
        String huge = "x".repeat(AssetText.MAX_FIELD_CHARS * 3);

        AssetText text = AssetText.parse("Asset name: n\nBusiness metadata: " + huge);

        assertThat(text.raw(METADATA)).hasSize(AssetText.MAX_FIELD_CHARS);
    }
}
