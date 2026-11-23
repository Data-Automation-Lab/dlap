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

package com.dlap.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.fixtures.CoverageSpec.Cell;
import com.dlap.fixtures.CoverageSpec.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks the coverage specs this module ships (SPEC-01): every owned family has one, every entry
 * cites an https page, and the entries the parser packages' acceptance criteria rely on exist.
 */
class ShippedCoverageSpecsTest {

    /** The five families SPEC-01 owns a spec for. */
    private static final Set<String> SPEC_FAMILIES = Set.of("gcpaudit", "bqjobs", "cloudtrail", "azstoragelog", "dbxaudit");

    @Test
    void classpathCorpusCarriesTheIndependentSpecs() {
        FixtureCatalog catalog = FixtureCatalog.fromClasspath();
        assertThat(catalog.familyNames()).containsAll(SPEC_FAMILIES);
        assertThat(catalog.spec("gcpaudit")).get().extracting(CoverageSpec::family).isEqualTo("gcpaudit");
        assertThat(catalog.spec("selftest")).isEmpty();
    }

    private static CoverageSpec shipped(String family) {
        return FixtureCatalog.fromClasspath().spec(family).orElseThrow(() -> new AssertionError("no spec for " + family));
    }

    private static List<String> ids(List<Entry> entries) {
        return entries.stream().map(Entry::id).toList();
    }

    private static Set<String> variantIds(CoverageSpec spec) {
        return spec.variants().stream().map(Entry::id).collect(Collectors.toSet());
    }

    private static Set<String> scoredOperationIds(CoverageSpec spec, String envelope, String version) {
        return spec.operations(envelope, version).stream().filter(Entry::scored).map(Entry::id).collect(Collectors.toSet());
    }

    @Test
    void everyOwnedFamilyShipsAWellFormedSpecWrittenBySpec01() {
        FixtureCatalog corpus = FixtureCatalog.fromClasspath();
        assertThat(corpus.problems()).isEmpty();
        assertThat(corpus.specs()).extracting(CoverageSpec::family).containsAll(SPEC_FAMILIES);
        for (String family : SPEC_FAMILIES) {
            CoverageSpec spec = shipped(family);
            assertThat(spec.writtenBy()).as(family).isEqualTo("SPEC-01");
            assertThat(spec.cells()).as(family).isNotEmpty();
            List<Entry> all = new ArrayList<>(spec.operations());
            all.addAll(spec.variants());
            assertThat(all).as(family + ": every entry cites an https page").allMatch(e -> e.docUrl().startsWith("https://"));
            assertThat(all).as(family + ": every not-scored entry says why").allMatch(e -> e.scored() || e.reason() != null);
        }
    }

    @Test
    void operationIdsFollowEachFamilysRecordSpelling() {
        Function<String, List<String>> ops = f -> shipped(f).operations().stream().map(Entry::id).toList();
        assertThat(ops.apply("gcpaudit")).allMatch(id -> id.startsWith("storage.googleapis.com:") || id.startsWith("bigquery.googleapis.com:"));
        assertThat(ops.apply("cloudtrail")).allMatch(id -> id.substring(0, id.indexOf(':')).endsWith(".amazonaws.com"));
        assertThat(ops.apply("azstoragelog")).allMatch(id -> id.startsWith("StorageBlobLogs:"));
        assertThat(ops.apply("bqjobs")).allMatch(id -> id.matches("(QUERY|LOAD|COPY|EXTRACT|NULL):[A-Z_]+"));
        assertThat(ops.apply("dbxaudit")).allMatch(id -> id.matches("[a-zA-Z]+:[a-zA-Z]+"));
    }

    @Test
    void gcpauditCoversTheGcp01Contract() {
        CoverageSpec spec = shipped("gcpaudit");
        assertThat(spec.versions()).extracting(CoverageSpec.Version::id)
                .containsExactly("auditlog-2021", "auditlog-2025", "bq-auditmetadata", "bq-auditdata-legacy");
        assertThat(spec.envelopes()).extracting(CoverageSpec.Envelope::id).containsExactly("api-proto", "json", "bq-sink-row");
        assertThat(spec.cells()).hasSize(12);
        assertThat(variantIds(spec)).contains(
                "status.org-policy-denial", "status.vpc-sc-denial", "status.granted-false", "actor.masked-email",
                "bq2.read.job", "bq2.read.referenced-tables", "bq2.read.cache-hit-no-read", "bq2.script.child-jobs",
                "bq1.referenced-tables-objects", "bq1.binding-pinned-v1", "envelope.gcs-shard-array",
                "envelope.gcs-shard-ndjson", "envelope.gcs-addendum-shard", "time.receive-timestamp-fallback",
                "dedup.same-insert-id");
        assertThat(scoredOperationIds(spec, "json", "auditlog-2025")).contains(
                "storage.googleapis.com:storage.objects.get", "storage.googleapis.com:storage.objects.create",
                "storage.googleapis.com:storage.objects.delete", "storage.googleapis.com:storage.buckets.create",
                "storage.googleapis.com:storage.setIamPermissions");
        assertThat(scoredOperationIds(spec, "json", "bq-auditmetadata"))
                .contains("bigquery.googleapis.com:google.cloud.bigquery.v2.JobService.InsertJob",
                        "bigquery.googleapis.com:google.cloud.bigquery.v2.TableDataService.List")
                .noneMatch(id -> id.startsWith("storage."));
        assertThat(scoredOperationIds(spec, "json", "bq-auditdata-legacy")).contains("bigquery.googleapis.com:jobservice.jobcompleted");
        assertThat(spec.variants().stream().filter(Entry::negative).map(Entry::id))
                .contains("entry.missing-method-name", "entry.unknown-payload-type", "time.no-timestamp");
    }

    @Test
    void bqjobsCoversTheGcp02Contract() {
        CoverageSpec spec = shipped("bqjobs");
        assertThat(spec.cells()).containsExactly(new Cell("jobs-view-row", "jobs-2026-10"));
        assertThat(spec.versions().get(0).requiredFields())
                .containsExactly("job_id", "project_id", "creation_time", "end_time", "user_email", "job_type", "state", "referenced_tables");
        assertThat(variantIds(spec)).contains(
                "read.referenced-tables-fanout", "read.cache-hit-no-read", "read.script-parent-excluded",
                "row.query-text-never-stored", "row.repeated-poll-dedup", "row.end-time-is-event-time",
                "binding.backfill-180-days", "actor.principal-subject", "write.destination-table");
        assertThat(spec.variants().stream().filter(Entry::negative).map(Entry::id)).contains("row.missing-required-column");
        assertThat(scoredOperationIds(spec, "jobs-view-row", "jobs-2026-10"))
                .contains("QUERY:SELECT", "QUERY:INSERT", "QUERY:MERGE", "QUERY:SCRIPT", "LOAD:NULL");
    }

    @Test
    void cloudtrailKeepsDataEventsOutOfLookupEvents() {
        CoverageSpec spec = shipped("cloudtrail");
        for (String v : List.of("1.08", "1.09", "1.10", "1.11")) {
            assertThat(spec.hasCell("s3-file", v)).isTrue();
            assertThat(spec.hasCell("lookup-events", v)).isTrue();
        }
        assertThat(spec.hasCell("cloudwatch-line", "1.11")).as("unverified envelope").isFalse();
        assertThat(scoredOperationIds(spec, "s3-file", "1.11")).contains("s3.amazonaws.com:GetObject", "s3.amazonaws.com:CreateBucket");
        assertThat(scoredOperationIds(spec, "lookup-events", "1.11"))
                .contains("s3.amazonaws.com:CreateBucket")
                .doesNotContain("s3.amazonaws.com:GetObject", "s3.amazonaws.com:PutObject");
        // Every userIdentity type of the AWS report section 3.2 has a variant.
        assertThat(variantIds(spec)).contains("uid.iam-user", "uid.assumed-role", "uid.root", "uid.federated-user",
                "uid.role", "uid.directory", "uid.aws-account", "uid.aws-service", "uid.identity-center-user",
                "uid.builder-id-user", "uid.saml-user", "uid.web-identity-user", "uid.unknown");
        assertThat(ids(spec.variants("s3-file", "1.02"))).doesNotContain("addendum.updated-data", "s3.cross-account-shared-event");
        assertThat(ids(spec.variants("s3-file", "1.08"))).contains("addendum.updated-data", "s3.cross-account-shared-event");
    }

    @Test
    void azstoragelogAndDbxauditCarryTheirContractVariants() {
        assertThat(variantIds(shipped("azstoragelog"))).contains(
                "actor.sas-unattributed", "actor.account-key-unattributed", "status.non-2xx-failure",
                "envelope.torn-last-line", "envelope.pre-2018-records-blob", "object.arm-id-lowercased");
        CoverageSpec dbx = shipped("dbxaudit");
        assertThat(variantIds(dbx)).contains(
                "scope.account-level-workspace-zero", "credential.read-operation", "params.sql-definitions-masked",
                "asset.uc-full-name", "binding.system-schema-not-enabled");
        assertThat(dbx.operations().stream().filter(o -> o.id().equals("unityCatalog:getTable")).findFirst().orElseThrow()
                .expectedCategory()).isEqualTo("MANAGEMENT");
    }
}
