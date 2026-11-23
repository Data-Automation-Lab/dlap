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

/**
 * Seed corpus import and anonymiser (FIX-02).
 *
 * <p>The corpus holds real-shaped records from five open sources (invictus-ir/aws_dataset,
 * panther-labs/panther-analysis, GoogleCloudPlatform/professional-services, googleapis/google-cloudevents
 * and open-telemetry/opentelemetry-collector-contrib). Nothing from them is copied by hand. Each family
 * directory holds a {@code seeds.yaml} that pins repositories at commits and names the records; the
 * classes here turn that manifest into cases:
 * <ul>
 *   <li>{@link com.dlap.fixtures.anon.SeedImporter}: the command line tool ({@code import}, {@code check},
 *       {@code scan}, and {@code list}, which prints every seed with its repository, commit, licence and
 *       date);</li>
 *   <li>{@link com.dlap.fixtures.anon.Anonymiser}: replaces account numbers, addresses, mailboxes, project
 *       and tenant ids, access keys and names with deterministic pseudonyms (HMAC-SHA256, fixed public
 *       salt), and leaves the structure a parser sees; STS session tokens and secret access keys become
 *       placeholders of the same length that carry the marker {@code EXAMPLE} (Q-220);</li>
 *   <li>{@link com.dlap.fixtures.anon.LeakScanner}: finds what the anonymiser should have replaced, by
 *       shape, without needing the real values; any credential-shaped value that is not a placeholder is
 *       a finding;</li>
 *   <li>{@code FamilyRules}: computes the facts a correct parser reports (native key, event time, result,
 *       principal, variants) from the rules in {@code docs/specs/log-event.md}, never from a parser.</li>
 * </ul>
 *
 * <h2>Re-running the import</h2>
 * Fetch each repository, check out the commit in the manifest, and pass the checkouts by source id (the
 * ids are {@code invictus}, {@code panther}, {@code pservices}, {@code cloudevents} and {@code otel}):
 * <pre>
 * java -cp &lt;da-log-fixtures runtime classpath&gt; com.dlap.fixtures.anon.SeedImporter check \
 *     --corpus da-log-fixtures/src/main/resources/dlap-fixtures \
 *     --source invictus=/path/to/aws_dataset --source panther=/path/to/panther-analysis ...
 * </pre>
 * {@code check} makes every case in memory and fails if one committed byte differs, so the committed
 * files are provably the output of the import and nobody edited them. {@code import} writes them.
 * {@code SeedCorpusTest#committedSeedsMatchAFreshImport} runs {@code check} when the environment
 * variable {@code DLAP_SEED_SOURCES} names a directory holding the five checkouts as subdirectories.
 * The rest of {@code SeedCorpusTest} needs no checkout: it recomputes {@code expected.json} and
 * {@code meta.yaml} from the committed input, scans for identifiers, and checks that anonymising the
 * committed input changes nothing.
 *
 * <h2>Rules that keep the corpus clean</h2>
 * <ul>
 *   <li>A case is written only if {@link com.dlap.fixtures.anon.LeakScanner} finds nothing in it.</li>
 *   <li>Samples that went through another product's pipeline (Panther's {@code p_*} fields, its own time
 *       format) are not provider shaped and are not imported. The remaining Panther samples are marked
 *       DERIVED in their notes and are not independent proof of a provider's shape.</li>
 *   <li>Vendor documentation samples, Elastic content and flaws.cloud data are never imported
 *       ({@code ProvenanceVerifier} blocks them).</li>
 *   <li>Changing the salt or a rule changes every pseudonym; do it deliberately and regenerate the corpus.</li>
 * </ul>
 */
package com.dlap.fixtures.anon;
