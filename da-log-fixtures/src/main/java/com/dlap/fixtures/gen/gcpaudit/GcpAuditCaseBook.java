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

package com.dlap.fixtures.gen.gcpaudit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The generated cases of the {@code gcpaudit} family (GCP-01), for the {@code json} and
 * {@code api-proto} envelopes and the four version cells.
 *
 * <p>Each case is written here by hand from the field lists of the vendored googleapis protos
 * ({@code log_entry.proto}, {@code audit_log.proto}, {@code bigquery_audit_metadata.proto},
 * {@code logging/v1/audit_data.proto}) and the GCP log sources report, together with the events
 * the GCP-01 parser contract says a correct parser emits. {@link GcpAuditGenerator} writes them to
 * the corpus; {@code GcpAuditProtoRoundTripTest} parses every input strictly against the vendored
 * descriptors, which is the independent confirmation their {@code meta.yaml} names; and
 * {@code GcpAuditGeneratedCasesTest} fails when the committed files differ from this book.
 *
 * <p>A few cases combine fields that the documentation never shows together, so that a rule that
 * does not depend on the payload version is exercised in every version cell (for example a BigQuery
 * entry in the Policy Denied log); their notes say so. Nothing here is a copy of a vendor sample;
 * all identifiers are invented ({@code proj-0d1a0001}, {@code example.com}, 192.0.2.0/24).
 */
public final class GcpAuditCaseBook {

    static final String PROJECT = "proj-0d1a0001";
    static final String P = "projects/" + PROJECT;
    static final String FOLDER = "folders/999900000002";
    static final String ORG = "organizations/999900000001";
    static final String BUCKET = "dlap-fixture-bucket";
    static final String OBJECT = "exports/2026/09/orders.csv";
    static final String OBJECT_RN = "projects/_/buckets/" + BUCKET + "/objects/" + OBJECT;
    static final String BUCKET_RN = "projects/_/buckets/" + BUCKET;
    static final String GS_OBJECT = "gs://" + BUCKET + "/" + OBJECT;
    static final String GS_BUCKET = "gs://" + BUCKET;
    static final String USER = "analyst@example.com";
    static final String SA = "sa-0d1a00e1@proj-0d1a0001.iam.gserviceaccount.com";
    static final String IP = "192.0.2.10";
    static final String UA = "dlap-fixture-client/1.0";
    static final String STORAGE = "storage.googleapis.com";
    static final String BQ = "bigquery.googleapis.com";
    static final String V2 = "google.cloud.bigquery.v2.";
    static final String BQ_META = "type.googleapis.com/google.cloud.audit.BigQueryAuditMetadata";
    static final String BQ_DATA = "type.googleapis.com/google.cloud.bigquery.logging.v1.AuditData";
    static final String IAM_DATA = "type.googleapis.com/google.iam.v1.logging.AuditData";
    static final String VPC_META = "type.googleapis.com/google.cloud.audit.VpcServiceControlAuditMetadata";
    static final String T_ORDERS = P + "/datasets/sales/tables/orders";
    static final String T_CUSTOMERS = P + "/datasets/sales/tables/customers";
    static final String T_VIEW = P + "/datasets/sales/tables/orders_v";
    static final String T_STAGING = P + "/datasets/sales/tables/orders_staging";
    static final String T_BACKUP = P + "/datasets/sales/tables/orders_backup";
    static final String T_ANON = P + "/datasets/_8f3c1d2e/tables/anon6b1f";
    static final String BQ_ORDERS = "bq://" + PROJECT + "/sales/orders";
    static final String BQ_CUSTOMERS = "bq://" + PROJECT + "/sales/customers";
    static final String BQ_VIEW = "bq://" + PROJECT + "/sales/orders_v";
    static final String BQ_STAGING = "bq://" + PROJECT + "/sales/orders_staging";
    static final String BQ_BACKUP = "bq://" + PROJECT + "/sales/orders_backup";

    private static final List<String> BOTH = List.of("json", "api-proto");
    private static final List<String> JSON_ONLY = List.of("json");

    /** The version cells. */
    enum Cell {
        A2021("auditlog-2021", "a21", 1),
        A2025("auditlog-2025", "a25", 2),
        V2("bq-auditmetadata", "bq2", 3),
        V1("bq-auditdata-legacy", "bq1", 4);

        final String id;
        final String tag;
        final int day;

        Cell(String id, String tag, int day) {
            this.id = id;
            this.tag = tag;
            this.day = day;
        }

        /** The {@code source_schema_version} a correct parser reports. */
        String schema() {
            return this == A2021 || this == A2025 ? "auditlog" : id;
        }
    }

    private GcpAuditCaseBook() {
    }

    /**
     * Every generated case.
     *
     * @return the cases, in a stable order
     */
    public static List<GcpAuditCase> cases() {
        List<GcpAuditCase> out = new ArrayList<>();
        for (Cell c : List.of(Cell.A2021, Cell.A2025)) {
            out.addAll(new Book(c).auditlog());
        }
        out.addAll(new Book(Cell.V2).bigQueryV2());
        out.addAll(new Book(Cell.V1).bigQueryV1());
        return out;
    }

    // ------------------------------------------------------------------
    // One cell's cases
    // ------------------------------------------------------------------

    /** Builds the cases of one cell; owns its clock so times never repeat. */
    static final class Book {
        private final Cell cell;
        private int second;
        private final List<GcpAuditCase> cases = new ArrayList<>();

        Book(Cell cell) {
            this.cell = cell;
        }

        /** The next distinct entry time of this cell, with nanosecond digits. */
        String t() {
            int s = second++;
            return String.format("2026-09-%02dT10:%02d:%02d.%09dZ", cell.day, s / 60, s % 60, 123_456_789 + s);
        }

        String id(String name, int n) {
            return cell.tag + "-" + name + "-" + n;
        }

        /** The 2025 profile's permissionType, or none for every other cell. */
        String pt(String type) {
            return cell == Cell.A2025 ? type : null;
        }

        Expected exp(Entry e) {
            return Expected.of(e, cell.schema());
        }

        static String plus(String time, long millis) {
            return Instant.parse(time).plusMillis(millis).toString();
        }

        void add(String name, List<String> envelopes, List<List<Entry>> files, List<Expected> expected, String outcome,
                 List<String> covers, List<String> variants, List<String> jsonOnly, String notes) {
            cases.add(new GcpAuditCase(name, cell.id, envelopes, outcome.startsWith("QUARANTINED"), files, expected,
                    outcome, covers, variants, jsonOnly, notes));
        }

        void add(String name, List<Entry> records, List<Expected> expected, String outcome, List<String> covers,
                 List<String> variants, String notes) {
            add(name, BOTH, List.of(records), expected, outcome, covers, variants, List.of(), notes);
        }

        // ---------------------------------------------------------------- entry templates

        Entry gcs(String log, String name, int n, String method, String resourceName, String permission,
                  String permissionType) {
            String time = t();
            Entry e = Entry.of(P, log, id(name, n), time)
                    .resource("gcs_bucket", "bucket_name", resourceName.startsWith(BUCKET_RN) ? BUCKET : "dlap-fixture-new-bucket",
                            "project_id", PROJECT, "location", "us-central1")
                    .payload(STORAGE, method, resourceName)
                    .principal(USER)
                    .caller(IP, UA)
                    .auth(resourceName, permission, true, pt(permissionType));
            return e.received(plus(time, 700), "INFO");
        }

        Entry get(String name, int n) {
            return gcs("data_access", name, n, "storage.objects.get", OBJECT_RN, "storage.objects.get", "DATA_READ");
        }

        Expected expGet(Entry e) {
            return exp(e).is("ACCESS", "DATA_READ").target(GS_OBJECT).principal("USER", USER);
        }

        Entry bq2(String log, String name, int n, String method, String resourceName, String resourceType,
                  ObjectNode metadata) {
            String time = t();
            Entry e = Entry.of(P, log, id(name, n), time);
            if ("bigquery_dataset".equals(resourceType)) {
                e.resource(resourceType, "project_id", PROJECT, "dataset_id", "sales");
            } else {
                e.resource(resourceType, "project_id", PROJECT, "location", "US");
            }
            e.payload(BQ, method, resourceName).principal(USER).caller(IP, UA)
                    .auth(resourceName, "bigquery.jobs.create", true, null)
                    .pp("metadata", metadata);
            return e.received(plus(time, 900), "INFO");
        }

        Entry read2(String name, int n, String table, String reason, String jobName) {
            ObjectNode tdr = Entry.F.objectNode();
            tdr.putArray("fields").add("order_id").add("amount");
            tdr.put("reason", reason);
            if (jobName != null) {
                tdr.put("jobName", jobName);
            }
            return bq2("data_access", name, n, V2 + "JobService.InsertJob", table, "bigquery_dataset",
                    meta2("tableDataRead", tdr));
        }

        Entry bq1(String log, String name, int n, String method, String resourceName, ObjectNode serviceData) {
            String time = t();
            Entry e = Entry.of(P, log, id(name, n), time)
                    .resource("bigquery_resource", "project_id", PROJECT)
                    .payload(BQ, method, resourceName).principal(USER).caller(IP, UA)
                    .auth(resourceName, "bigquery.jobs.create", true, null);
            if (serviceData != null) {
                e.pp("serviceData", serviceData);
            }
            return e.received(plus(time, 900), "INFO");
        }

        Entry list1(String name, int n) {
            ObjectNode sd = data1();
            sd.putObject("tableDataListRequest").put("maxResults", 100);
            return bq1("data_access", name, n, "tabledataservice.list", T_ORDERS, sd);
        }

        Expected expList1(Entry e) {
            return exp(e).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER);
        }

        /** A representative entry of the cell, for the cross-cutting cases. */
        Entry base(String name, int n) {
            return switch (cell) {
                case A2021, A2025 -> get(name, n);
                case V2 -> read2(name, n, T_ORDERS, "JOB", P + "/jobs/bquxjob_base_" + n);
                case V1 -> list1(name, n);
            };
        }

        /** The events a correct parser emits for {@link #base}. */
        Expected expBase(Entry e) {
            return switch (cell) {
                case A2021, A2025 -> expGet(e);
                case V2 -> exp(e).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER)
                        .shared(e.pp().path("metadata").path("tableDataRead").path("jobName").asText());
                case V1 -> expList1(e);
            };
        }

        String baseOperation() {
            return switch (cell) {
                case A2021, A2025 -> STORAGE + ":storage.objects.get";
                case V2 -> BQ + ":" + V2 + "JobService.InsertJob";
                case V1 -> BQ + ":tabledataservice.list";
            };
        }

        // ---------------------------------------------------------------- cross-cutting cases

        void crossCutting() {
            String op = baseOperation();
            // Log names: folder and organization parents (aggregated sinks).
            {
                Entry folder = base("log-names", 1);
                Entry org = base("log-names", 2);
                Entry f = retarget(folder, FOLDER);
                Entry o = retarget(org, ORG);
                add("log-folder-and-organization", List.of(f, o), List.of(expBase(f), expBase(o)), "PARSED",
                        List.of(op), List.of("log.folder-or-organization", "log.data-access"),
                        "Data Access entries of a folder-level and an organization-level aggregated sink; the native key keeps the "
                                + "whole logName.");
            }
            // Result status.
            {
                Entry denied = base("status", 1).status(7, "PERMISSION_DENIED");
                ((ObjectNode) denied.pp().withArrayProperty("authorizationInfo").get(0)).put("granted", false);
                Entry notGranted = base("status", 2);
                ((ObjectNode) notGranted.pp().withArrayProperty("authorizationInfo").get(0)).put("granted", false);
                boolean storage = cell == Cell.A2021 || cell == Cell.A2025;
                Entry policy = storage
                        ? gcs("policy", "status", 3, "storage.buckets.create", "projects/_/buckets/dlap-fixture-new-bucket",
                                "storage.buckets.create", "ADMIN_WRITE").status(9, "FAILED_PRECONDITION")
                        : retargetLog(base("status", 3), "policy").status(9, "FAILED_PRECONDITION");
                ObjectNode vi = Entry.F.objectNode();
                vi.put("constraint", "constraints/gcp.resourceLocations");
                vi.put("errorMessage", "Location us-central1 violates constraint constraints/gcp.resourceLocations");
                vi.put("checkedValue", "us-central1");
                vi.put("policyType", "LIST_CONSTRAINT");
                ObjectNode pvi = Entry.F.objectNode();
                ObjectNode org = pvi.putObject("orgPolicyViolationInfo");
                org.put("resourceType", storage ? "storage.googleapis.com/Bucket" : "bigquery.googleapis.com/Table");
                org.putArray("violationInfo").add(vi);
                policy.pp("policyViolationInfo", pvi);
                List<Entry> rs = new ArrayList<>(List.of(denied, notGranted, policy));
                Expected policyExp = storage
                        ? exp(policy).is("CREATE", "MANAGEMENT").target("gs://dlap-fixture-new-bucket")
                                .principal("USER", USER).failed()
                        : expBase(policy).failed();
                List<Expected> ex = new ArrayList<>(List.of(expBase(denied).failed(), expBase(notGranted).failed(),
                        policyExp));
                List<String> vs = new ArrayList<>(List.of("status.nonzero-code", "status.granted-false",
                        "log.policy-denied", "status.org-policy-denial"));
                String note = "Denied and failed entries: a non-zero status, a denied permission check, and a Policy Denied "
                        + "entry with an organization policy violation.";
                if (cell != Cell.V2) {
                    Entry vpc = retargetLog(base("status", 4), "policy").status(7, "PERMISSION_DENIED");
                    vpc.pp("metadata", vpcMetadata());
                    rs.add(vpc);
                    ex.add(expBase(vpc).failed());
                    vs.add("status.vpc-sc-denial");
                    vs.add("status.vpc-sc-ingress-egress");
                    note += " And a VPC Service Controls denial with ingress and egress violations.";
                }
                if (cell == Cell.V2 || cell == Cell.V1) {
                    note += " Synthetic combination: the documentation shows Policy Denied and VPC Service Controls entries"
                            + " without BigQuery audit payloads; the failure rule does not depend on the payload version, and"
                            + " these entries exercise it in this cell.";
                }
                List<String> ops = storage ? List.of(op, STORAGE + ":storage.buckets.create") : List.of(op);
                add("result-status", rs, ex, "PARSED", ops, vs, note);
            }
            // Actors.
            {
                Entry subject = base("actors", 1);
                ObjectNode ai = subject.pp().putObject("authenticationInfo");
                ai.put("principalSubject", "principal://iam.googleapis.com/locations/global/workforcePools/dlap-pool/subject/user-7");
                Entry delegated = base("actors", 2);
                delegated.pp().putObject("authenticationInfo");
                delegated.delegation("sa-0d1a00d2@proj-0d1a0002.iam.gserviceaccount.com");
                Entry sa = base("actors", 3).principal(SA);
                Entry masked = base("actors", 4).principal("...");
                Entry internal = base("actors", 5).principal("google-internal");
                Entry noIp = base("actors", 6);
                ((ObjectNode) noIp.pp().get("requestMetadata")).remove("callerIp");
                add("actors", List.of(subject, delegated, sa, masked, internal, noIp), List.of(
                        expBase(subject).principal("USER",
                                "principal://iam.googleapis.com/locations/global/workforcePools/dlap-pool/subject/user-7"),
                        expBase(delegated).principal("SERVICE_ACCOUNT", "sa-0d1a00d2@proj-0d1a0002.iam.gserviceaccount.com"),
                        expBase(sa).principal("SERVICE_ACCOUNT", SA),
                        expBase(masked).maskedPrincipal("..."),
                        expBase(internal).principal("SYSTEM", "google-internal"),
                        expBase(noIp).principal("USER", USER)),
                        "PARSED", List.of(op),
                        List.of("actor.principal-subject", "actor.delegation-chain", "actor.service-account",
                                "actor.masked-email", "actor.google-internal", "actor.caller-ip-redacted",
                                "actor.principal-email"),
                        "Actor order and classes: a third-party subject, a delegation chain, a service account, a masked email"
                                + " (a valid actor that names nobody), google-internal, and an entry without a caller IP.");
            }
            // Entry shapes: no @type, and the native key triple.
            {
                Entry untyped = base("shapes", 1).withoutPayloadType();
                String time = t();
                Entry first = retargetTime(base("shapes", 2), time, "shapes-dup");
                Entry second = retargetTime(base("shapes", 3), plus(time, 1000), "shapes-dup");
                add("entry-shapes", List.of(untyped, first, second),
                        List.of(expBase(untyped), expBase(first), expBase(second)), "PARSED", List.of(op),
                        List.of("entry.protopayload-without-type", "dedup.same-insert-id"),
                        "A payload without @type, recognised from serviceName; and two entries that share insertId but not "
                                + "timestamp, which are two events because the native key is logName|timestamp|insertId. That the"
                                + " same triple twice is one event is GcpAuditDedupTest in da-logingest (a case cannot hold one"
                                + " event id twice).");
            }
            // Warnings: time fallback and the first part of a split entry; in the storage cells also another
            // service's System Event entry and long-running operation, classified by the verb heuristics. Unknown
            // fields cannot pass the proto round trip, so their cases are hand-written (entry-unknown-fields).
            {
                Entry fallback = base("warnings", 2);
                String receive = fallback.json().path("receiveTimestamp").asText();
                fallback.without("timestamp");
                Entry fb = Entry.of(P, "data_access", id("warnings", 2), null);
                Entry fbFinal = copyInto(fb, fallback.json(), receive);
                Entry split = base("warnings", 3).split("split-" + cell.tag + "-1", 0, 2);
                List<Entry> rs = new ArrayList<>(List.of(fbFinal, split));
                List<Expected> ex = new ArrayList<>(List.of(
                        expBase(fbFinal).warnings("EVENT_TIME_FALLBACK"),
                        expBase(split).warnings("PROVIDER_TRUNCATED")));
                List<String> vs = new ArrayList<>(List.of("time.receive-timestamp-fallback", "entry.split"));
                String note = "An entry without timestamp takes receiveTimestamp with a warning; the first part of a split "
                        + "entry carries the event with PROVIDER_TRUNCATED.";
                if (cell == Cell.A2021 || cell == Cell.A2025) {
                    String time = t();
                    Entry system = Entry.of(P, "system_event", id("other-services", 1), time)
                            .resource("gce_instance", "instance_id", "4471823305193002810", "project_id", PROJECT, "zone",
                                    "us-central1-a")
                            .payload("compute.googleapis.com", "compute.instances.preempted",
                                    P + "/zones/us-central1-a/instances/batch-vm-1")
                            .auth(P + "/zones/us-central1-a/instances/batch-vm-1", "compute.instances.get", true,
                                    pt("ADMIN_READ"))
                            .received(plus(time, 500), "NOTICE");
                    Entry lroFirst = compute(1, true);
                    Entry lroLast = compute(2, false);
                    rs.addAll(List.of(system, lroFirst, lroLast));
                    ex.add(exp(system).is("UPDATE", "MANAGEMENT").warnings("HEURISTIC_CLASSIFICATION"));
                    ex.add(exp(lroFirst).is("CREATE", "MANAGEMENT").principal("USER", USER)
                            .requestId("operation-1725184800-lro").warnings("HEURISTIC_CLASSIFICATION"));
                    ex.add(exp(lroLast).is("CREATE", "MANAGEMENT").principal("USER", USER)
                            .requestId("operation-1725184800-lro").warnings("HEURISTIC_CLASSIFICATION"));
                    vs.addAll(List.of("log.system-event", "entry.operation-first-last"));
                    note += " A System Event entry of another service (an instance preemption) and the first and last "
                            + "entries of one long-running operation, which share operation.id as request id; both services "
                            + "are outside the family's classification table, so the verb heuristics decide and say so.";
                }
                add("entry-warnings", rs, ex, "PARSED_WITH_WARNINGS", List.of(op), vs, note);
            }
            // Later parts of a split entry.
            {
                Entry p1 = base("split", 1).split("split-" + cell.tag + "-2", 1, 3);
                Entry p2 = base("split", 2).split("split-" + cell.tag + "-2", 2, 3);
                add("split-later-parts", List.of(p1, p2), List.of(), "SKIPPED:NOT_AN_EVENT", List.of(op),
                        List.of("entry.split"),
                        "Parts after the first of a split entry are skipped and counted, so the entry is never counted twice.");
            }
            // Negative: no methodName, and neither timestamp nor receiveTimestamp.
            {
                Entry noMethod = base("missing", 1);
                noMethod.pp().remove("methodName");
                Entry noTime = base("missing", 2);
                ObjectNode j = noTime.json();
                j.remove("timestamp");
                j.remove("receiveTimestamp");
                Entry nt = Entry.of(P, "data_access", id("missing", 2), null);
                Entry ntFinal = copyInto(nt, j, null);
                add("missing-method-or-time", BOTH, List.of(List.of(noMethod, ntFinal)), List.of(),
                        "QUARANTINED:MISSING_REQUIRED_FIELD", List.of(),
                        List.of("entry.missing-method-name", "time.no-timestamp"),
                        List.of(), "A payload without methodName, and an entry with neither timestamp nor receiveTimestamp (a "
                                + "stored LogEntry always has both), are quarantined as missing required fields.");
            }
        }

        // ---------------------------------------------------------------- Cloud Storage

        List<GcpAuditCase> auditlog() {
            Entry get = get("gcs-reads", 1);
            Entry list = gcs("data_access", "gcs-reads", 2, "storage.objects.list", BUCKET_RN, "storage.objects.list",
                    "DATA_READ");
            list.pp("numResponseItems", Entry.F.textNode("3"));
            ObjectNode loc = Entry.F.objectNode();
            loc.putArray("currentLocations").add("us-central1");
            list.pp("resourceLocation", loc);
            add("gcs-object-reads", List.of(get, list), List.of(
                    expGet(get),
                    exp(list).is("ACCESS", "MANAGEMENT").target(GS_BUCKET).principal("USER", USER)),
                    "PARSED", List.of(STORAGE + ":storage.objects.get", STORAGE + ":storage.objects.list"),
                    List.of("log.data-access", "actor.principal-email", "gcs.object-resource-name",
                            "gcs.list-bucket-resource-name", "gcs.resource-labels", "entry.num-response-items"),
                    "An object read and a bucket-level listing (a metadata read, MANAGEMENT); the listing carries "
                            + "numResponseItems and resourceLocation.");

            String newObject = "projects/_/buckets/" + BUCKET + "/objects/exports/2026/09/orders-2.csv";
            Entry create = gcs("data_access", "gcs-writes", 1, "storage.objects.create", newObject,
                    "storage.objects.create", "DATA_WRITE");
            create.auth(newObject, "storage.objects.delete", true, pt("DATA_WRITE"));
            ObjectNode req = Entry.F.objectNode();
            req.put("@type", "type.googleapis.com/storage.objects.insert");
            req.put("bucket", BUCKET);
            req.put("name", "exports/2026/09/orders-2.csv");
            ObjectNode resp = Entry.F.objectNode();
            resp.put("@type", "type.googleapis.com/storage.objects.insert");
            resp.put("bucket", BUCKET);
            resp.put("name", "exports/2026/09/orders-2.csv");
            resp.put("size", "2048");
            resp.put("generation", "1725184800123456");
            resp.put("timeCreated", "2026-09-01T10:00:02.100Z");
            create.pp("request", req).pp("response", resp);
            Entry delete = gcs("data_access", "gcs-writes", 2, "storage.objects.delete", OBJECT_RN,
                    "storage.objects.delete", "DATA_WRITE");
            add("gcs-object-writes", List.of(create, delete), List.of(
                    exp(create).is("CREATE", "DATA_WRITE").target("gs://" + BUCKET + "/exports/2026/09/orders-2.csv")
                            .sizeAndCreated(2048, "2026-09-01T10:00:02.100Z").principal("USER", USER),
                    exp(delete).is("DELETE", "DATA_WRITE").target(GS_OBJECT).principal("USER", USER)),
                    "PARSED", List.of(STORAGE + ":storage.objects.create", STORAGE + ":storage.objects.delete"),
                    List.of("log.data-access", "gcs.create-request-response", "gcs.object-resource-name"),
                    "An object create with request and response (size, generation, timeCreated) and both the create and "
                            + "delete permissions checked; an object delete.");

            String copyTarget = "projects/_/buckets/" + BUCKET + "/objects/archive/orders.csv";
            Entry copyRead = get("gcs-copy", 1);
            Entry copyWrite = gcs("data_access", "gcs-copy", 2, "storage.objects.create", copyTarget,
                    "storage.objects.create", "DATA_WRITE");
            add("gcs-copy-two-entries", List.of(copyRead, copyWrite), List.of(
                    expGet(copyRead),
                    exp(copyWrite).is("CREATE", "DATA_WRITE").target("gs://" + BUCKET + "/archive/orders.csv")
                            .principal("USER", USER)),
                    "PARSED", List.of(STORAGE + ":storage.objects.get", STORAGE + ":storage.objects.create"),
                    List.of("gcs.copy-compose-two-entries", "gcs.object-resource-name"),
                    "A copy is two entries: a read of the source and a write of the destination.");

            String newBucket = "projects/_/buckets/dlap-fixture-new-bucket";
            Entry bcreate = gcs("activity", "gcs-admin", 1, "storage.buckets.create", newBucket,
                    "storage.buckets.create", "ADMIN_WRITE");
            bcreate.pp("serviceData", policyDelta("roles/storage.legacyBucketOwner", "projectOwner:" + PROJECT));
            ObjectNode breq = Entry.F.objectNode();
            breq.put("@type", "type.googleapis.com/storage.buckets.insert");
            breq.put("name", "dlap-fixture-new-bucket");
            bcreate.pp("request", breq);
            Entry bupdate = gcs("activity", "gcs-admin", 2, "storage.buckets.update", BUCKET_RN,
                    "storage.buckets.update", "ADMIN_WRITE");
            ObjectNode ureq = Entry.F.objectNode();
            ureq.put("@type", "type.googleapis.com/storage.buckets.update");
            ureq.putObject("versioning").put("enabled", true);
            bupdate.pp("request", ureq);
            Entry setIam = gcs("activity", "gcs-admin", 3, "storage.setIamPermissions", BUCKET_RN,
                    "storage.buckets.setIamPolicy", "ADMIN_WRITE");
            setIam.pp("serviceData", policyDelta("roles/storage.objectViewer", "user:" + USER));
            add("gcs-bucket-admin", List.of(bcreate, bupdate, setIam), List.of(
                    exp(bcreate).is("CREATE", "MANAGEMENT").target("gs://dlap-fixture-new-bucket").principal("USER", USER),
                    exp(bupdate).is("UPDATE", "MANAGEMENT").target(GS_BUCKET).principal("USER", USER),
                    exp(setIam).is("PERMISSION", "PERMISSION").target(GS_BUCKET).principal("USER", USER)),
                    "PARSED", List.of(STORAGE + ":storage.buckets.create", STORAGE + ":storage.buckets.update",
                            STORAGE + ":storage.setIamPermissions"),
                    List.of("log.admin-activity", "gcs.admin-activity-servicedata", "gcs.setiam-servicedata-policy-delta",
                            "gcs.resource-labels"),
                    "Admin Activity bucket entries: a create that carries serviceData next to request, a versioning update, "
                            + "and an IAM change whose serviceData holds the policy delta.");

            crossCutting();
            shards();
            return cases;
        }

        Entry compute(int n, boolean first) {
            String time = t();
            String rn = P + "/zones/us-central1-a/instances/batch-vm-2";
            Entry e = Entry.of(P, "activity", id("lro", n), time)
                    .resource("gce_instance", "instance_id", "4471823305193002811", "project_id", PROJECT, "zone",
                            "us-central1-a")
                    .payload("compute.googleapis.com", "v1.compute.instances.insert", rn)
                    .principal(USER).caller(IP, UA)
                    .auth(rn, "compute.instances.create", true, pt("ADMIN_WRITE"));
            e.operation("operation-1725184800-lro", first, !first);
            return e.received(plus(time, 400), "NOTICE");
        }

        // ---------------------------------------------------------------- Cloud Storage sink shards (json)

        void shards() {
            String op = baseOperation();
            Entry a = base("shard-array", 1);
            Entry b = base("shard-array", 2);
            Entry c = base("shard-array", 3);
            // Written out of time order: the shard is unsorted.
            add("gcs-shard-array", JSON_ONLY, List.of(List.of(c, a, b)), List.of(expBase(c), expBase(a), expBase(b)),
                    "PARSED", List.of(op), List.of(),
                    List.of("envelope.gcs-shard-array", "envelope.gcs-unsorted-duplicates", "envelope.gcs-nested-log-id-path"),
                    "A Cloud Storage sink shard (BUCKET/cloudaudit.googleapis.com/data_access/YYYY/MM/DD/..._S0.json) as a JSON"
                            + " array, entries out of time order. Duplicates across overlapping sinks are absorbed by event-id"
                            + " dedup (GcpAuditDedupTest; GcsSinkTransportTest lists the nested log-id path).");
            Entry d = base("shard-ndjson", 1);
            Entry e = base("shard-ndjson", 2);
            add("gcs-shard-ndjson", JSON_ONLY, List.of(List.of(d, e)), List.of(expBase(d), expBase(e)), "PARSED",
                    List.of(op), List.of(), List.of("envelope.gcs-shard-ndjson"),
                    "The same shard shape as newline-delimited LogEntry objects.");
            Entry s0 = base("shard-addendum", 1);
            Entry a0 = base("shard-addendum", 2);
            add("gcs-addendum-shard", JSON_ONLY, List.of(List.of(s0), List.of(a0)), List.of(expBase(s0), expBase(a0)),
                    "PARSED", List.of(op), List.of(), List.of("envelope.gcs-addendum-shard"),
                    "A regular shard (input.json, _S0) and its addendum shard (input.late.json, _A0:<unix>) with a late entry;"
                            + " the addendum parses like the shard and holds other entries, so nothing is counted twice.");
        }

        // ---------------------------------------------------------------- BigQuery v2

        List<GcpAuditCase> bigQueryV2() {
            String j1 = P + "/jobs/bquxjob_5f2a1c_0001";
            String insertJob = BQ + ":" + V2 + "JobService.InsertJob";

            Entry inserted = bq2("data_access", "job-lifecycle", 1, V2 + "JobService.InsertJob", j1, "bigquery_project",
                    meta2("jobInsertion", jobInsertion(job2(j1, "RUNNING", queryConfig("SELECT"), null), "JOB_INSERT_REQUEST")));
            inserted.operation(j1, true, false);
            ObjectNode stats = jobStats2(List.of(T_ORDERS, T_CUSTOMERS), List.of(T_VIEW), false);
            ArrayNode usage = stats.putArray("reservationUsage");
            usage.addObject().put("name", "unreserved").put("slotMs", "4210");
            stats.put("reservation", "unreserved");
            Entry done = bq2("data_access", "job-lifecycle", 2, V2 + "JobService.InsertJob", j1, "bigquery_project",
                    meta2("jobChange", jobChange("RUNNING", "DONE", job2(j1, "DONE", queryConfig("SELECT"), stats))));
            done.operation(j1, false, true);
            Expected doneJob = exp(done).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_5f2a1c_0001")
                    .principal("USER", USER).requestId(j1);
            add("job-lifecycle", List.of(inserted, done), List.of(
                    exp(inserted).is("CREATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_5f2a1c_0001")
                            .principal("USER", USER).requestId(j1),
                    doneJob,
                    doneJob.fan(BQ_ORDERS).is("ACCESS", "DATA_READ").shared(j1),
                    doneJob.fan(BQ_CUSTOMERS).is("ACCESS", "DATA_READ").shared(j1),
                    doneJob.fan(BQ_VIEW).is("ACCESS", "DATA_READ").shared(j1)),
                    "PARSED", List.of(insertJob),
                    List.of("bq2.job.two-entries", "bq2.event.job-insertion", "bq2.event.job-change-done",
                            "bq2.event.job-change-query", "bq2.read.referenced-tables", "bq2.read.referenced-views",
                            "bq2.reservation-usage-deprecated", "bq2.resource-type", "entry.operation-first-last",
                            "log.data-access", "actor.principal-email"),
                    "The two entries of one InsertJob (jobInsertion, then jobChange to DONE): one job, and one read per "
                            + "referenced table and view from the jobChange; the deprecated reservationUsage sits next to "
                            + "jobStats.reservation.");

            String j2 = P + "/jobs/bquxjob_5f2a1c_0002";
            String j3 = P + "/jobs/bquxjob_5f2a1c_0003";
            String j4 = P + "/jobs/bquxjob_5f2a1c_0004";
            Entry query = bq2("data_access", "job-calls", 1, V2 + "JobService.Query", j2, "bigquery_project",
                    meta2("jobInsertion", jobInsertion(job2(j2, "RUNNING", queryConfig("SELECT"), null), "QUERY_REQUEST")));
            ObjectNode cancelledJob = job2(j3, "DONE", queryConfig("SELECT"), null);
            ObjectNode err = ((ObjectNode) cancelledJob.get("jobStatus")).putObject("errorResult");
            err.put("code", 1);
            err.put("message", "Job execution was cancelled: User requested cancellation");
            Entry cancel = bq2("data_access", "job-calls", 2, V2 + "JobService.CancelJob", j3, "bigquery_project",
                    meta2("jobChange", jobChange("RUNNING", "DONE", cancelledJob)));
            ObjectNode deletion = Entry.F.objectNode();
            deletion.put("jobName", j4);
            deletion.put("reason", "JOB_DELETE_REQUEST");
            Entry delete = bq2("data_access", "job-calls", 3, V2 + "JobService.DeleteJob", j4, "bigquery_project",
                    meta2("jobDeletion", deletion));
            ObjectNode results = Entry.F.objectNode();
            results.put("reason", "GET_QUERY_RESULTS_REQUEST");
            results.put("jobName", j1);
            Entry getResults = bq2("data_access", "job-calls", 4, V2 + "JobService.GetQueryResults", T_ANON,
                    "bigquery_dataset", meta2("tableDataRead", results));
            add("job-calls", List.of(query, cancel, delete, getResults), List.of(
                    exp(query).is("CREATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_5f2a1c_0002")
                            .principal("USER", USER),
                    exp(cancel).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_5f2a1c_0003")
                            .principal("USER", USER),
                    exp(delete).is("DELETE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_5f2a1c_0004")
                            .principal("USER", USER),
                    exp(getResults).is("ACCESS", "MANAGEMENT").target("bq://" + PROJECT + "/_8f3c1d2e/anon6b1f")
                            .principal("USER", USER).shared(j1)),
                    "PARSED", List.of(BQ + ":" + V2 + "JobService.Query", BQ + ":" + V2 + "JobService.CancelJob",
                            BQ + ":" + V2 + "JobService.DeleteJob", BQ + ":" + V2 + "JobService.GetQueryResults"),
                    List.of("bq2.event.job-insertion", "bq2.event.job-deletion", "bq2.read.get-query-results",
                            "bq2.resource-type"),
                    "Job calls: a jobs.query insertion (reason QUERY_REQUEST), a cancel (a successful call on a job that ends "
                            + "cancelled), a job deletion, and a GetQueryResults read of the anonymous result table, which is "
                            + "no data asset.");

            Entry copy = bq2("data_access", "job-types", 1, V2 + "JobService.InsertJob", P + "/jobs/bquxjob_copy_1",
                    "bigquery_project", meta2("jobChange", jobChange("RUNNING", "DONE", job2(P + "/jobs/bquxjob_copy_1",
                            "DONE", copyConfig(), null))));
            Entry load = bq2("data_access", "job-types", 2, V2 + "JobService.InsertJob", P + "/jobs/bquxjob_load_1",
                    "bigquery_project", meta2("jobChange", jobChange("RUNNING", "DONE", job2(P + "/jobs/bquxjob_load_1",
                            "DONE", loadConfig(), null))));
            Entry export = bq2("data_access", "job-types", 3, V2 + "JobService.InsertJob", P + "/jobs/bquxjob_extract_1",
                    "bigquery_project", meta2("jobChange", jobChange("RUNNING", "DONE", job2(P + "/jobs/bquxjob_extract_1",
                            "DONE", extractConfig(), null))));
            add("job-types", List.of(copy, load, export), List.of(
                    exp(copy).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_copy_1").principal("USER", USER),
                    exp(load).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_load_1").principal("USER", USER),
                    exp(export).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_extract_1")
                            .principal("USER", USER)),
                    "PARSED", List.of(insertJob),
                    List.of("bq2.event.job-change-copy", "bq2.event.job-change-import", "bq2.event.job-change-export"),
                    "jobChange to DONE for a copy, a load (IMPORT) and an extract (EXPORT) job: the job events; their table "
                            + "reads and writes arrive as their own tableDataRead and tableDataChange entries.");

            String jr = P + "/jobs/bquxjob_reads_1";
            Entry rJob = read2("reads", 1, T_ORDERS, "JOB", jr);
            ObjectNode listRead = Entry.F.objectNode();
            listRead.put("reason", "TABLEDATA_LIST_REQUEST");
            Entry rList = bq2("data_access", "reads", 2, V2 + "TableDataService.List", T_CUSTOMERS, "bigquery_dataset",
                    meta2("tableDataRead", listRead));
            rList.pp("numResponseItems", Entry.F.textNode("100"));
            ObjectNode qr = Entry.F.objectNode();
            qr.putArray("fields").add("customer_id");
            qr.put("reason", "QUERY_REQUEST");
            qr.put("jobName", j2);
            Entry rQuery = bq2("data_access", "reads", 3, V2 + "JobService.Query", T_CUSTOMERS, "bigquery_dataset",
                    meta2("tableDataRead", qr));
            String session = P + "/locations/us/sessions/CAISDGxFakeSession1";
            ObjectNode sr = Entry.F.objectNode();
            sr.putArray("fields").add("order_id");
            sr.put("reason", "CREATE_READ_SESSION");
            sr.put("sessionName", session);
            String srTime = t();
            Entry rSession = Entry.of(P, "data_access", id("reads", 4), srTime)
                    .resource("bigquery_dataset", "project_id", PROJECT, "dataset_id", "sales")
                    .payload("bigquerystorage.googleapis.com", "google.cloud.bigquery.storage.v1.BigQueryRead.CreateReadSession",
                            T_ORDERS)
                    .principal(SA).caller(IP, "grpc-java/1.83.1")
                    .auth(T_ORDERS, "bigquery.readsessions.create", true, null)
                    .pp("metadata", meta2("tableDataRead", sr))
                    .received(plus(srTime, 800), "INFO");
            Entry rMv = read2("reads", 5, T_ORDERS, "MATERIALIZED_VIEW_REFRESH", null);
            String jw = P + "/jobs/bquxjob_wildcard_1";
            Entry rWild = read2("reads", 6, P + "/datasets/sales/tables/events_*", "JOB", jw);
            add("table-reads", List.of(rJob, rList, rQuery, rSession, rMv, rWild), List.of(
                    exp(rJob).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER).shared(jr),
                    exp(rList).is("ACCESS", "DATA_READ").target(BQ_CUSTOMERS).principal("USER", USER),
                    exp(rQuery).is("ACCESS", "DATA_READ").target(BQ_CUSTOMERS).principal("USER", USER).shared(j2),
                    exp(rSession).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("SERVICE_ACCOUNT", SA)
                            .shared(session),
                    exp(rMv).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER),
                    exp(rWild).is("ACCESS", "DATA_READ").target("bq://" + PROJECT + "/sales")
                            .technical("bigquery.wildcard_table", "events_*").principal("USER", USER).shared(jw)),
                    "PARSED", List.of(insertJob, BQ + ":" + V2 + "TableDataService.List", BQ + ":" + V2 + "JobService.Query"),
                    List.of("bq2.read.job", "bq2.read.tabledata-list", "bq2.read.query-request",
                            "bq2.read.storage-read-session", "bq2.read.materialized-view-refresh", "bq2.read.wildcard-table",
                            "entry.num-response-items"),
                    "tableDataRead in each reason; the Storage Read API session (service bigquerystorage.googleapis.com, a "
                            + "name from the API reference, unverified in a capture) is no job and is linked by its session "
                            + "name; a wildcard table read is recorded on its dataset.");

            String parent = P + "/jobs/script_parent_1";
            String child = P + "/jobs/script_job_9c1e_0";
            ObjectNode cacheStats = Entry.F.objectNode();
            cacheStats.put("createTime", "2026-09-03T11:00:00Z");
            cacheStats.put("startTime", "2026-09-03T11:00:00.100Z");
            cacheStats.put("endTime", "2026-09-03T11:00:00.200Z");
            cacheStats.putObject("queryStats").put("totalProcessedBytes", "0").put("cacheHit", true);
            Entry cached = bq2("data_access", "no-reads", 1, V2 + "JobService.InsertJob", P + "/jobs/bquxjob_cached_1",
                    "bigquery_project", meta2("jobChange", jobChange("RUNNING", "DONE",
                            job2(P + "/jobs/bquxjob_cached_1", "DONE", queryConfig("SELECT"), cacheStats))));
            ObjectNode failedJob = job2(P + "/jobs/bquxjob_failed_1", "DONE", queryConfig("SELECT"), null);
            ObjectNode fe = ((ObjectNode) failedJob.get("jobStatus")).putObject("errorResult");
            fe.put("code", 3);
            fe.put("message", "Syntax error: Unexpected keyword FROM at [1:8]");
            Entry failed = bq2("data_access", "no-reads", 2, V2 + "JobService.InsertJob", P + "/jobs/bquxjob_failed_1",
                    "bigquery_project", meta2("jobChange", jobChange("RUNNING", "DONE", failedJob)));
            Entry scriptParent = bq2("data_access", "no-reads", 3, V2 + "JobService.InsertJob", parent, "bigquery_project",
                    meta2("jobChange", jobChange("RUNNING", "DONE", job2(parent, "DONE", queryConfig("SCRIPT"),
                            jobStats2(List.of(T_ORDERS), List.of(), false)))));
            ObjectNode childStats = jobStats2(List.of(T_ORDERS), List.of(), false);
            childStats.put("parentJobName", parent);
            Entry scriptChild = bq2("data_access", "no-reads", 4, V2 + "JobService.InsertJob", child, "bigquery_project",
                    meta2("jobChange", jobChange("RUNNING", "DONE", job2(child, "DONE", queryConfig("SELECT"), childStats))));
            Expected childJob = exp(scriptChild).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/script_job_9c1e_0")
                    .principal("USER", USER);
            add("jobs-without-reads", List.of(cached, failed, scriptParent, scriptChild), List.of(
                    exp(cached).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_cached_1")
                            .principal("USER", USER),
                    exp(failed).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_failed_1")
                            .principal("USER", USER).failed(),
                    exp(scriptParent).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/script_parent_1")
                            .principal("USER", USER),
                    childJob,
                    childJob.fan(BQ_ORDERS).is("ACCESS", "DATA_READ").shared(child)),
                    "PARSED", List.of(insertJob),
                    List.of("bq2.read.cache-hit-no-read", "bq2.job.failed-no-read", "bq2.script.child-jobs"),
                    "No read for a cache hit, a failed job (FAILURE) or the parent of a multi-statement script; the script's "
                            + "child job (script_job_ id, parentJobName set) reports the read.");


            ObjectNode cJob = tableDataChange("JOB", P + "/jobs/bquxjob_load_1", null);
            ObjectNode cQuery = tableDataChange("QUERY", P + "/jobs/bquxjob_dml_1", null);
            ObjectNode cMv = tableDataChange("MATERIALIZED_VIEW_REFRESH", null, null);
            ObjectNode cWrite = tableDataChange("WRITE_API", null, T_ORDERS + "/streams/_default");
            Entry wJob = bq2("data_access", "writes", 1, V2 + "JobService.InsertJob", T_STAGING, "bigquery_dataset",
                    meta2("tableDataChange", cJob));
            Entry wQuery = bq2("data_access", "writes", 2, V2 + "JobService.Query", T_ORDERS, "bigquery_dataset",
                    meta2("tableDataChange", cQuery));
            Entry wMv = bq2("data_access", "writes", 3, V2 + "JobService.InsertJob", T_VIEW, "bigquery_dataset",
                    meta2("tableDataChange", cMv));
            String wt = t();
            Entry wApi = Entry.of(P, "data_access", id("writes", 4), wt)
                    .resource("bigquery_dataset", "project_id", PROJECT, "dataset_id", "sales")
                    .payload("bigquerystorage.googleapis.com", "google.cloud.bigquery.storage.v1.BigQueryWrite.AppendRows",
                            T_ORDERS)
                    .principal(SA).caller(IP, "grpc-java/1.83.1")
                    .auth(T_ORDERS, "bigquery.tables.updateData", true, null)
                    .pp("metadata", meta2("tableDataChange", cWrite))
                    .received(plus(wt, 800), "INFO");
            add("table-writes", List.of(wJob, wQuery, wMv, wApi), List.of(
                    exp(wJob).is("UPDATE", "DATA_WRITE").target(BQ_STAGING).principal("USER", USER),
                    exp(wQuery).is("UPDATE", "DATA_WRITE").target(BQ_ORDERS).principal("USER", USER),
                    exp(wMv).is("UPDATE", "DATA_WRITE").target(BQ_VIEW).principal("USER", USER),
                    exp(wApi).is("UPDATE", "DATA_WRITE").target(BQ_ORDERS).principal("SERVICE_ACCOUNT", SA)),
                    "PARSED", List.of(insertJob, BQ + ":" + V2 + "JobService.Query"),
                    List.of("bq2.write.table-data-change-job", "bq2.write.table-data-change-query",
                            "bq2.write.table-data-change-mv-refresh", "bq2.write.table-data-change-write-api"),
                    "tableDataChange in each reason (job, DML query, materialized view refresh, Storage Write API).");

            String tNew = P + "/datasets/sales/tables/returns";
            ObjectNode creation = Entry.F.objectNode();
            ObjectNode tbl = creation.putObject("table");
            tbl.put("tableName", tNew);
            tbl.put("createTime", "2026-09-03T12:00:00.250Z");
            creation.put("reason", "TABLE_INSERT_REQUEST");
            Entry tCreate = bq2("activity", "table-dataset", 1, V2 + "TableService.InsertTable", tNew, "bigquery_dataset",
                    meta2("tableCreation", creation));
            Entry tPatch = bq2("activity", "table-dataset", 2, V2 + "TableService.PatchTable", T_ORDERS, "bigquery_dataset",
                    meta2("tableChange", tableChange(T_ORDERS)));
            Entry tUpdate = bq2("activity", "table-dataset", 3, V2 + "TableService.UpdateTable", T_ORDERS,
                    "bigquery_dataset", meta2("tableChange", tableChange(T_ORDERS)));
            ObjectNode td = Entry.F.objectNode();
            td.put("reason", "TABLE_DELETE_REQUEST");
            Entry tDelete = bq2("activity", "table-dataset", 4, V2 + "TableService.DeleteTable", T_BACKUP,
                    "bigquery_dataset", meta2("tableDeletion", td));
            String ds = P + "/datasets/marketing";
            Entry dCreate = bq2("activity", "table-dataset", 5, V2 + "DatasetService.InsertDataset", ds, "bigquery_dataset",
                    meta2("datasetCreation", datasetEvent(ds, "CREATE")));
            Entry dPatch = bq2("activity", "table-dataset", 6, V2 + "DatasetService.PatchDataset", ds, "bigquery_dataset",
                    meta2("datasetChange", datasetEvent(ds, "UPDATE")));
            ObjectNode dd = Entry.F.objectNode();
            dd.put("reason", "DELETE");
            Entry dDelete = bq2("activity", "table-dataset", 7, V2 + "DatasetService.DeleteDataset", ds, "bigquery_dataset",
                    meta2("datasetDeletion", dd));
            Entry dIam = bq2("activity", "table-dataset", 8, "google.iam.v1.IAMPolicy.SetIamPolicy", P + "/datasets/sales",
                    "bigquery_dataset", meta2("datasetChange", datasetEvent(P + "/datasets/sales", "SET_IAM_POLICY")));
            add("table-and-dataset-admin", List.of(tCreate, tPatch, tUpdate, tDelete, dCreate, dPatch, dDelete, dIam),
                    List.of(
                            exp(tCreate).is("CREATE", "MANAGEMENT").target("bq://" + PROJECT + "/sales/returns")
                                    .created("2026-09-03T12:00:00.250Z").principal("USER", USER),
                            exp(tPatch).is("UPDATE", "MANAGEMENT").target(BQ_ORDERS).principal("USER", USER),
                            exp(tUpdate).is("UPDATE", "MANAGEMENT").target(BQ_ORDERS).principal("USER", USER),
                            exp(tDelete).is("DELETE", "MANAGEMENT").target(BQ_BACKUP).principal("USER", USER),
                            exp(dCreate).is("CREATE", "MANAGEMENT").target("bq://" + PROJECT + "/marketing")
                                    .principal("USER", USER),
                            exp(dPatch).is("UPDATE", "MANAGEMENT").target("bq://" + PROJECT + "/marketing")
                                    .principal("USER", USER),
                            exp(dDelete).is("DELETE", "MANAGEMENT").target("bq://" + PROJECT + "/marketing")
                                    .principal("USER", USER),
                            exp(dIam).is("PERMISSION", "PERMISSION").target("bq://" + PROJECT + "/sales")
                                    .principal("USER", USER)),
                    "PARSED", List.of(BQ + ":" + V2 + "TableService.InsertTable", BQ + ":" + V2 + "TableService.PatchTable",
                            BQ + ":" + V2 + "TableService.UpdateTable", BQ + ":" + V2 + "TableService.DeleteTable",
                            BQ + ":google.iam.v1.IAMPolicy.SetIamPolicy"),
                    List.of("bq2.table.creation", "bq2.table.change", "bq2.table.deletion", "bq2.dataset.lifecycle",
                            "bq2.dataset.set-iam-policy", "log.admin-activity", "bq2.resource-type"),
                    "Admin Activity table and dataset events; a table creation carries its createTime; an IAM policy change "
                            + "on a dataset is a permission change.");

            ObjectNode expired = Entry.F.objectNode();
            expired.put("reason", "EXPIRED");
            String et = t();
            Entry expiry = Entry.of(P, "system_event", id("expiry", 1), et)
                    .resource("bigquery_dataset", "project_id", PROJECT, "dataset_id", "sales")
                    .payload(BQ, "InternalTableExpired", P + "/datasets/sales/tables/tmp_orders_20260801")
                    .pp("metadata", meta2("tableDeletion", expired))
                    .received(plus(et, 900), "INFO");
            add("table-expired", List.of(expiry), List.of(
                    exp(expiry).is("DELETE", "MANAGEMENT").target("bq://" + PROJECT + "/sales/tmp_orders_20260801")
                            .principal("SYSTEM", null)),
                    "PARSED", List.of(BQ + ":InternalTableExpired"),
                    List.of("bq2.table.deletion-expired", "log.system-event"),
                    "A System Event entry: BigQuery deleting an expired table; no caller, so the actor is SYSTEM.");

            Entry model = bq2("data_access", "other-resources", 1, V2 + "JobService.InsertJob",
                    P + "/datasets/sales/models/churn", "bigquery_dataset", meta2("modelCreation", named("model", "modelName",
                            P + "/datasets/sales/models/churn", "QUERY")));
            Entry routine = bq2("activity", "other-resources", 2, V2 + "RoutineService.InsertRoutine",
                    P + "/datasets/sales/routines/net_amount", "bigquery_dataset", meta2("routineCreation",
                            named("routine", "routineName", P + "/datasets/sales/routines/net_amount", "ROUTINE_INSERT_REQUEST")));
            ObjectNode rap = Entry.F.objectNode();
            rap.putObject("rowAccessPolicy").put("rowAccessPolicyName", T_ORDERS + "/rowAccessPolicies/eu_only");
            Entry rowPolicy = bq2("activity", "other-resources", 3, V2 + "RowAccessPolicyService.CreateRowAccessPolicy",
                    T_ORDERS + "/rowAccessPolicies/eu_only", "bigquery_dataset", meta2("rowAccessPolicyCreation", rap));
            ObjectNode unlink = Entry.F.objectNode();
            unlink.put("linkedDataset", P + "/datasets/shared_link");
            unlink.put("sourceDataset", "projects/proj-0d1a0002/datasets/shared");
            unlink.put("reason", "UNLINK_API");
            Entry unlinked = bq2("activity", "other-resources", 4, V2 + "DatasetService.UnlinkDataset",
                    P + "/datasets/shared_link", "bigquery_dataset", meta2("unlinkDataset", unlink));
            add("other-resource-events", List.of(model, routine, rowPolicy, unlinked), List.of(
                    exp(model).is("CREATE", "MANAGEMENT").principal("USER", USER),
                    exp(routine).is("CREATE", "MANAGEMENT").principal("USER", USER),
                    exp(rowPolicy).is("CREATE", "MANAGEMENT").principal("USER", USER),
                    exp(unlinked).is("DELETE", "MANAGEMENT").target("bq://" + PROJECT + "/shared_link")
                            .principal("USER", USER)),
                    "PARSED", List.of(insertJob), List.of("bq2.other-resource-events"),
                    "Model, routine, row access policy and unlink events: management events on their own resources, never "
                            + "read as table events. Method names other than InsertJob are from the API reference, unverified in"
                            + " a capture.");

            String jt = P + "/jobs/bquxjob_truncated_1";
            ObjectNode tq = queryConfig("SELECT");
            ((ObjectNode) tq.get("queryConfig")).put("queryTruncated", true);
            Entry truncatedJob = bq2("data_access", "truncated", 1, V2 + "JobService.InsertJob", jt, "bigquery_project",
                    meta2("jobChange", jobChange("RUNNING", "DONE", job2(jt, "DONE", tq,
                            jobStats2(List.of(T_ORDERS), List.of(), false)))));
            ObjectNode tf = Entry.F.objectNode();
            tf.putArray("fields").add("order_id");
            tf.put("fieldsTruncated", true);
            tf.putArray("policyTags").add(P + "/locations/us/taxonomies/1/policyTags/2");
            tf.put("policyTagsTruncated", true);
            tf.put("reason", "JOB");
            tf.put("jobName", jt);
            Entry truncatedRead = bq2("data_access", "truncated", 2, V2 + "JobService.InsertJob", T_ORDERS,
                    "bigquery_dataset", meta2("tableDataRead", tf));
            Expected tj = exp(truncatedJob).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_truncated_1")
                    .principal("USER", USER).warnings("PROVIDER_TRUNCATED");
            add("metadata-truncated", List.of(truncatedJob, truncatedRead), List.of(
                    tj,
                    tj.fan(BQ_ORDERS).is("ACCESS", "DATA_READ").shared(jt),
                    exp(truncatedRead).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER).shared(jt)
                            .warnings("PROVIDER_TRUNCATED")),
                    "PARSED_WITH_WARNINGS", List.of(insertJob),
                    List.of("bq2.metadata-truncated", "bq2.read.fields-truncated"),
                    "Truncated metadata (queryTruncated, fieldsTruncated, policyTagsTruncated) is parsed and flagged "
                            + "PROVIDER_TRUNCATED for completion through jobs.get; the job's read and the tableDataRead entry "
                            + "share the job name, so usage counts them once.");

            crossCutting();
            shards();

            // Q-393: a job that finished fine on an entry that itself failed (denied) reads with FAILURE.
            String denJob = P + "/jobs/bquxjob_denied_1";
            Entry deniedStatus = bq2("data_access", "denied-entry", 1, V2 + "JobService.InsertJob", denJob,
                    "bigquery_project", meta2("jobChange", jobChange("RUNNING", "DONE", job2(denJob, "DONE",
                            queryConfig("SELECT"), jobStats2(List.of(T_ORDERS), List.of(), false)))))
                    .status(7, "PERMISSION_DENIED");
            String denJob2 = P + "/jobs/bquxjob_denied_2";
            Entry deniedCheck = bq2("data_access", "denied-entry", 2, V2 + "JobService.InsertJob", denJob2,
                    "bigquery_project", meta2("jobChange", jobChange("RUNNING", "DONE", job2(denJob2, "DONE",
                            queryConfig("SELECT"), jobStats2(List.of(T_ORDERS), List.of(), false)))));
            ((ObjectNode) deniedCheck.pp().withArrayProperty("authorizationInfo").get(0)).put("granted", false);
            Expected deniedJob1 = exp(deniedStatus).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_denied_1")
                    .principal("USER", USER).failed();
            Expected deniedJob2 = exp(deniedCheck).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bquxjob_denied_2")
                    .principal("USER", USER).failed();
            add("job-reads-on-failed-entry", List.of(deniedStatus, deniedCheck), List.of(
                    deniedJob1,
                    deniedJob1.fan(BQ_ORDERS).is("ACCESS", "DATA_READ").shared(denJob),
                    deniedJob2,
                    deniedJob2.fan(BQ_ORDERS).is("ACCESS", "DATA_READ").shared(denJob2)),
                    "PARSED", List.of(insertJob),
                    List.of("status.nonzero-code", "status.granted-false"),
                    "A finished query job on an entry with a non-zero status.code (PERMISSION_DENIED), and one whose "
                            + "authorization check was denied: the job event and the read it derives are both FAILURE, "
                            + "because the entry's failure is the entry's result (Q-393). Synthetic combination: the "
                            + "documentation shows no denied BigQuery job entry; the failure rule does not depend on the "
                            + "payload.");
            return cases;
        }

        // ---------------------------------------------------------------- BigQuery v1

        List<GcpAuditCase> bigQueryV1() {
            String completed = BQ + ":jobservice.jobcompleted";
            String jq = "bqjob_r3ac45_query_1";
            ObjectNode sdQuery = completedEvent(jq, query1("SELECT", null), stats1(List.of("orders", "customers"),
                    List.of("orders_v"), true), null);
            ArrayNode reads = sdQuery.putArray("tableDataReadEvents");
            ObjectNode tre = reads.addObject();
            tre.set("tableName", tableName1("orders"));
            tre.putArray("referencedFields").add("order_id").add("amount");
            Entry query = bq1("data_access", "job-completed", 1, "jobservice.jobcompleted", P + "/jobs/" + jq, sdQuery);
            Expected q = exp(query).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER)
                    .shared(P + "/jobs/" + jq);
            add("job-completed-query", List.of(query), List.of(
                    q,
                    q.fan(BQ_CUSTOMERS),
                    q.fan(BQ_VIEW)),
                    "PARSED", List.of(completed),
                    List.of("bq1.referenced-tables-objects", "bq1.table-data-read-events", "bq1.referenced-views",
                            "bq1.resource-type", "bq1.deprecated-fields", "log.data-access", "actor.principal-email"),
                    "jobservice.jobcompleted of a query: one read per table in jobStatistics.referencedTables, "
                            + "referencedViews and tableDataReadEvents (orders appears in both and is read once); the "
                            + "deprecated reservationUsage is present and ignored; the anonymous destination is no asset.");

            ObjectNode load = Entry.F.objectNode();
            ObjectNode lc = load.putObject("load");
            lc.putArray("sourceUris").add("gs://" + BUCKET + "/exports/2026/09/*.csv");
            lc.set("destinationTable", tableName1("orders_staging"));
            lc.put("createDisposition", "CREATE_IF_NEEDED");
            lc.put("writeDisposition", "WRITE_APPEND");
            Entry loadJob = bq1("data_access", "job-writes", 1, "jobservice.jobcompleted", P + "/jobs/bqjob_load_1",
                    completedEvent("bqjob_load_1", load, stats1(List.of(), List.of(), false), "load_job_completed"));
            ObjectNode copyCfg = Entry.F.objectNode();
            ObjectNode cc = copyCfg.putObject("tableCopy");
            cc.putArray("sourceTables").add(tableName1("orders"));
            cc.set("destinationTable", tableName1("orders_backup"));
            Entry copyJob = bq1("data_access", "job-writes", 2, "jobservice.jobcompleted", P + "/jobs/bqjob_copy_1",
                    completedEvent("bqjob_copy_1", copyCfg, stats1(List.of(), List.of(), false), "table_copy_job_completed"));
            Entry merge = bq1("data_access", "job-writes", 3, "jobservice.jobcompleted", P + "/jobs/bqjob_merge_1",
                    completedEvent("bqjob_merge_1", query1("MERGE", tableName1("orders")),
                            stats1(List.of("orders", "orders_staging"), List.of(), false), null));
            ObjectNode extractCfg = Entry.F.objectNode();
            ObjectNode ec = extractCfg.putObject("extract");
            ec.putArray("destinationUris").add("gs://" + BUCKET + "/extracts/orders-*.avro");
            ec.set("sourceTable", tableName1("orders"));
            Entry extract = bq1("data_access", "job-writes", 4, "jobservice.jobcompleted", P + "/jobs/bqjob_extract_1",
                    completedEvent("bqjob_extract_1", extractCfg, stats1(List.of(), List.of(), false), "extract_job_completed"));
            Expected m = exp(merge).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER)
                    .shared(P + "/jobs/bqjob_merge_1");
            Expected c = exp(copyJob).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER)
                    .shared(P + "/jobs/bqjob_copy_1");
            add("job-completed-writes", List.of(loadJob, copyJob, merge, extract), List.of(
                    exp(loadJob).is("UPDATE", "DATA_WRITE").target(BQ_STAGING).principal("USER", USER),
                    c,
                    c.fan(BQ_BACKUP).is("UPDATE", "DATA_WRITE").shared(null),
                    m,
                    m.fan(BQ_STAGING),
                    m.fan(BQ_ORDERS).is("UPDATE", "DATA_WRITE").shared(null),
                    exp(extract).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER)
                            .shared(P + "/jobs/bqjob_extract_1")),
                    "PARSED", List.of(completed),
                    List.of("bq1.referenced-tables-objects", "bq1.resource-type"),
                    "Completed load, copy, MERGE and extract jobs: the destination of a load, a copy or a DML statement is a "
                            + "write; the source of a copy or an extract and the tables a MERGE references are reads.");

            ObjectNode failedSd = completedEvent("bqjob_failed_1", query1("SELECT", null), stats1(List.of(), List.of(), false),
                    null);
            ObjectNode js = (ObjectNode) failedSd.path("jobCompletedEvent").path("job").path("jobStatus");
            ObjectNode err = js.putObject("error");
            err.put("code", 3);
            err.put("message", "Syntax error: Unexpected end of script");
            Entry failedJob = bq1("data_access", "job-no-reads", 1, "jobservice.jobcompleted", P + "/jobs/bqjob_failed_1",
                    failedSd);
            Entry noTables = bq1("data_access", "job-no-reads", 2, "jobservice.jobcompleted", P + "/jobs/bqjob_const_1",
                    completedEvent("bqjob_const_1", query1("SELECT", null), stats1(List.of(), List.of(), false), null));
            add("job-completed-without-reads", List.of(failedJob, noTables), List.of(
                    exp(failedJob).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bqjob_failed_1")
                            .principal("USER", USER).failed(),
                    exp(noTables).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bqjob_const_1")
                            .principal("USER", USER)),
                    "PARSED", List.of(completed), List.of("bq1.resource-type"),
                    "A failed job (jobStatus.error) and a query that references no table give one job event and no read.");


            ObjectNode insertSd = data1();
            insertSd.putObject("jobInsertRequest").putObject("resource").set("jobName", jobName1("bqjob_insert_1"));
            Entry insert = bq1("data_access", "api-calls", 1, "jobservice.insert", P + "/jobs/bqjob_insert_1", insertSd);
            ObjectNode querySd = data1();
            querySd.putObject("jobQueryRequest").put("query", "SELECT 1").put("projectId", PROJECT);
            querySd.putObject("jobQueryDoneResponse").putObject("job").set("jobName", jobName1("bqjob_query_2"));
            Entry jquery = bq1("data_access", "api-calls", 2, "jobservice.query", P + "/jobs/bqjob_query_2", querySd);
            ObjectNode gqrSd = data1();
            gqrSd.putObject("jobGetQueryResultsRequest").put("maxResults", 100);
            Entry gqr = bq1("data_access", "api-calls", 3, "jobservice.getqueryresults", P + "/jobs/bqjob_query_2", gqrSd);
            Entry cancel = bq1("data_access", "api-calls", 4, "jobservice.cancel", P + "/jobs/bqjob_query_2", data1());
            Entry tlist = list1("api-calls", 5);
            tlist.pp("numResponseItems", Entry.F.textNode("100"));
            ObjectNode tinsSd = data1();
            tinsSd.putObject("tableInsertRequest").putObject("resource").set("tableName", tableName1("returns"));
            Entry tinsert = bq1("activity", "api-calls", 6, "tableservice.insert", P + "/datasets/sales/tables/returns", tinsSd);
            ObjectNode tupdSd = data1();
            tupdSd.putObject("tableUpdateRequest").putObject("resource").set("tableName", tableName1("orders"));
            Entry tupdate = bq1("activity", "api-calls", 7, "tableservice.update", T_ORDERS, tupdSd);
            Entry tdelete = bq1("activity", "api-calls", 8, "tableservice.delete", T_BACKUP, data1());
            add("api-calls", List.of(insert, jquery, gqr, cancel, tlist, tinsert, tupdate, tdelete), List.of(
                    exp(insert).is("CREATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bqjob_insert_1")
                            .principal("USER", USER),
                    exp(jquery).is("CREATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bqjob_query_2")
                            .principal("USER", USER),
                    exp(gqr).is("ACCESS", "MANAGEMENT").target("bqjob://" + PROJECT + "/bqjob_query_2")
                            .principal("USER", USER),
                    exp(cancel).is("UPDATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/bqjob_query_2")
                            .principal("USER", USER),
                    expList1(tlist),
                    exp(tinsert).is("CREATE", "MANAGEMENT").target("bq://" + PROJECT + "/sales/returns")
                            .principal("USER", USER),
                    exp(tupdate).is("UPDATE", "MANAGEMENT").target(BQ_ORDERS).principal("USER", USER),
                    exp(tdelete).is("DELETE", "MANAGEMENT").target(BQ_BACKUP).principal("USER", USER)),
                    "PARSED", List.of(BQ + ":jobservice.insert", BQ + ":jobservice.query", BQ + ":jobservice.getqueryresults",
                            BQ + ":jobservice.cancel", BQ + ":tabledataservice.list", BQ + ":tableservice.insert",
                            BQ + ":tableservice.update", BQ + ":tableservice.delete"),
                    List.of("bq1.resource-type", "bq1.deprecated-fields", "log.admin-activity", "log.data-access",
                            "entry.num-response-items"),
                    "The v1 API invocations: job calls are management events, tabledata.list is a read, table calls are "
                            + "management events (Admin Activity); jobs.query carries the deprecated jobQueryDoneResponse.");

            // A job's insert and its completion as the first and last entries of one operation.
            String jo = "bqjob_operation_1";
            ObjectNode opInsertSd = data1();
            opInsertSd.putObject("jobInsertRequest").putObject("resource").set("jobName", jobName1(jo));
            Entry opFirst = bq1("data_access", "operation", 1, "jobservice.insert", P + "/jobs/" + jo, opInsertSd);
            opFirst.operation(P + "/jobs/" + jo, true, false);
            Entry opLast = bq1("data_access", "operation", 2, "jobservice.jobcompleted", P + "/jobs/" + jo,
                    completedEvent(jo, query1("SELECT", null), stats1(List.of("customers"), List.of(), false), null));
            opLast.operation(P + "/jobs/" + jo, false, true);
            add("job-operation-first-last", List.of(opFirst, opLast), List.of(
                    exp(opFirst).is("CREATE", "MANAGEMENT").target("bqjob://" + PROJECT + "/" + jo).principal("USER", USER)
                            .requestId(P + "/jobs/" + jo),
                    exp(opLast).is("ACCESS", "DATA_READ").target(BQ_CUSTOMERS).principal("USER", USER)
                            .shared(P + "/jobs/" + jo).requestId(P + "/jobs/" + jo)),
                    "PARSED", List.of(BQ + ":jobservice.insert", completed), List.of("entry.operation-first-last"),
                    "Synthetic combination: a v1 job insert and its completion marked as the first and last entries of one "
                            + "operation (the documentation shows operation.first and last on long-running v2 job entries); "
                            + "both carry the operation id as request id.");

            // A System Event entry in the v1 format.
            String st = t();
            ObjectNode expiredSd = data1();
            Entry expired = Entry.of(P, "system_event", id("expiry", 1), st)
                    .resource("bigquery_resource", "project_id", PROJECT)
                    .payload(BQ, "tableservice.delete", P + "/datasets/sales/tables/tmp_orders_20260801")
                    .pp("serviceData", expiredSd)
                    .received(plus(st, 900), "INFO");
            add("system-event", List.of(expired), List.of(
                    exp(expired).is("DELETE", "MANAGEMENT").target("bq://" + PROJECT + "/sales/tmp_orders_20260801")
                            .principal("SYSTEM", null)),
                    "PARSED", List.of(BQ + ":tableservice.delete"), List.of("log.system-event"),
                    "Synthetic combination: a table deletion by the system in the System Event log with v1 service data; "
                            + "the documentation shows BigQuery system events (InternalTableExpired) only in the v2 format.");

            // One job seen in both formats during the overlap.
            String jm = "bqjob_mixed_1";
            Entry v1 = bq1("data_access", "mixed", 1, "jobservice.jobcompleted", P + "/jobs/" + jm,
                    completedEvent(jm, query1("SELECT", null), stats1(List.of("orders"), List.of(), false), null));
            Entry v2 = bq2("data_access", "mixed", 2, V2 + "JobService.InsertJob", P + "/jobs/" + jm, "bigquery_project",
                    meta2("jobChange", jobChange("RUNNING", "DONE", job2(P + "/jobs/" + jm, "DONE", queryConfig("SELECT"),
                            jobStats2(List.of(T_ORDERS), List.of(), false)))));
            Expected v2job = Expected.of(v2, "bq-auditmetadata").is("UPDATE", "MANAGEMENT")
                    .target("bqjob://" + PROJECT + "/" + jm).principal("USER", USER);
            add("mixed-with-v2", List.of(v1, v2), List.of(
                    exp(v1).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER).shared(P + "/jobs/" + jm),
                    v2job,
                    v2job.fan(BQ_ORDERS).is("ACCESS", "DATA_READ").shared(P + "/jobs/" + jm)),
                    "PARSED", List.of(completed), List.of("bq1.mixed-with-v2"),
                    "During the migration overlap one project writes both formats: each entry parses under its own detected "
                            + "version, and the two reads of one job and table share the job name, so usage counts one.");

            // A binding pinned to v1 (the contract test binds case names starting with binding-pinned-v1).
            Entry pinnedV1 = bq1("data_access", "pinned", 1, "jobservice.jobcompleted", P + "/jobs/bqjob_pinned_1",
                    completedEvent("bqjob_pinned_1", query1("SELECT", null), stats1(List.of("orders"), List.of(), false),
                            null));
            add("binding-pinned-v1", List.of(pinnedV1), List.of(
                    exp(pinnedV1).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER)
                            .shared(P + "/jobs/bqjob_pinned_1")),
                    "PARSED", List.of(completed), List.of("bq1.binding-pinned-v1"),
                    "Parsed through a binding pinned to bq-auditdata-legacy with on-mismatch QUARANTINE: a v1 entry parses.");
            Entry pinnedV2 = bq2("data_access", "pinned", 2, V2 + "JobService.InsertJob", T_ORDERS, "bigquery_dataset",
                    meta2("tableDataRead", tableDataRead("JOB", P + "/jobs/bqjob_pinned_2")));
            add("binding-pinned-v1-rejects-v2", BOTH, List.of(List.of(pinnedV2)), List.of(),
                    "QUARANTINED:VERSION_PIN_MISMATCH", List.of(), List.of("bq1.binding-pinned-v1"), List.of(),
                    "Through the same pinned binding a BigQuery v2 entry is quarantined: the binding's mismatch policy "
                            + "applies.");

            crossCutting();
            shards();

            // Q-393: a job that completed fine on an entry that itself failed (denied) reads and writes with FAILURE.
            ObjectNode deniedQuerySd = completedEvent("bqjob_denied_1", query1("SELECT", null),
                    stats1(List.of("orders", "customers"), List.of(), false), null);
            Entry deniedQuery = bq1("data_access", "denied-entry", 1, "jobservice.jobcompleted",
                    P + "/jobs/bqjob_denied_1", deniedQuerySd).status(7, "PERMISSION_DENIED");
            ObjectNode deniedLoad = Entry.F.objectNode();
            ObjectNode dl = deniedLoad.putObject("load");
            dl.putArray("sourceUris").add("gs://" + BUCKET + "/exports/2026/09/*.csv");
            dl.set("destinationTable", tableName1("orders_staging"));
            Entry deniedLoadJob = bq1("data_access", "denied-entry", 2, "jobservice.jobcompleted",
                    P + "/jobs/bqjob_denied_2", completedEvent("bqjob_denied_2", deniedLoad,
                            stats1(List.of(), List.of(), false), "load_job_completed"));
            ((ObjectNode) deniedLoadJob.pp().withArrayProperty("authorizationInfo").get(0)).put("granted", false);
            Expected dq = exp(deniedQuery).is("ACCESS", "DATA_READ").target(BQ_ORDERS).principal("USER", USER)
                    .shared(P + "/jobs/bqjob_denied_1").failed();
            add("job-completed-on-failed-entry", List.of(deniedQuery, deniedLoadJob), List.of(
                    dq,
                    dq.fan(BQ_CUSTOMERS),
                    exp(deniedLoadJob).is("UPDATE", "DATA_WRITE").target(BQ_STAGING).principal("USER", USER).failed()),
                    "PARSED", List.of(completed),
                    List.of("status.nonzero-code", "status.granted-false"),
                    "jobservice.jobcompleted of a query on an entry with a non-zero status.code, and of a load whose "
                            + "authorization check was denied: every read and the write are FAILURE, because the entry's "
                            + "failure is the entry's result (Q-393). Synthetic combination: the documentation shows no "
                            + "denied BigQuery job entry; the failure rule does not depend on the payload.");
            return cases;
        }

        // ---------------------------------------------------------------- payload builders

        static ObjectNode meta2(String event, ObjectNode body) {
            ObjectNode m = Entry.F.objectNode();
            m.put("@type", BQ_META);
            m.set(event, body);
            return m;
        }

        static ObjectNode tableDataRead(String reason, String jobName) {
            ObjectNode t = Entry.F.objectNode();
            t.putArray("fields").add("order_id");
            t.put("reason", reason);
            if (jobName != null) {
                t.put("jobName", jobName);
            }
            return t;
        }

        static ObjectNode queryConfig(String statementType) {
            ObjectNode q = Entry.F.objectNode();
            q.put("query", "SELECT order_id, amount FROM sales.orders JOIN sales.customers USING (customer_id)");
            q.put("destinationTable", T_ANON);
            q.put("createDisposition", "CREATE_IF_NEEDED");
            q.put("writeDisposition", "WRITE_TRUNCATE");
            q.put("priority", "QUERY_INTERACTIVE");
            q.put("statementType", statementType);
            ObjectNode c = Entry.F.objectNode();
            c.put("type", "QUERY");
            c.set("queryConfig", q);
            return c;
        }

        static ObjectNode copyConfig() {
            ObjectNode t = Entry.F.objectNode();
            t.putArray("sourceTables").add(T_ORDERS);
            t.put("destinationTable", T_BACKUP);
            t.put("createDisposition", "CREATE_IF_NEEDED");
            t.put("writeDisposition", "WRITE_TRUNCATE");
            t.put("operationType", "COPY");
            ObjectNode c = Entry.F.objectNode();
            c.put("type", "COPY");
            c.set("tableCopyConfig", t);
            return c;
        }

        static ObjectNode loadConfig() {
            ObjectNode l = Entry.F.objectNode();
            l.putArray("sourceUris").add("gs://" + BUCKET + "/exports/2026/09/*.csv");
            l.put("destinationTable", T_STAGING);
            l.put("createDisposition", "CREATE_IF_NEEDED");
            l.put("writeDisposition", "WRITE_APPEND");
            ObjectNode c = Entry.F.objectNode();
            c.put("type", "IMPORT");
            c.set("loadConfig", l);
            return c;
        }

        static ObjectNode extractConfig() {
            ObjectNode e = Entry.F.objectNode();
            e.putArray("destinationUris").add("gs://" + BUCKET + "/extracts/orders-*.avro");
            e.put("sourceTable", T_ORDERS);
            ObjectNode c = Entry.F.objectNode();
            c.put("type", "EXPORT");
            c.set("extractConfig", e);
            return c;
        }

        static ObjectNode job2(String jobName, String state, ObjectNode config, ObjectNode stats) {
            ObjectNode j = Entry.F.objectNode();
            j.put("jobName", jobName);
            j.set("jobConfig", config);
            j.putObject("jobStatus").put("jobState", state);
            if (stats != null) {
                j.set("jobStats", stats);
            } else {
                ObjectNode s = j.putObject("jobStats");
                s.put("createTime", "2026-09-03T11:00:00Z");
                s.put("startTime", "2026-09-03T11:00:00.100Z");
                if ("DONE".equals(state)) {
                    s.put("endTime", "2026-09-03T11:00:03.400Z");
                }
            }
            return j;
        }

        static ObjectNode jobStats2(List<String> tables, List<String> views, boolean cacheHit) {
            ObjectNode s = Entry.F.objectNode();
            s.put("createTime", "2026-09-03T11:00:00Z");
            s.put("startTime", "2026-09-03T11:00:00.100Z");
            s.put("endTime", "2026-09-03T11:00:03.400Z");
            ObjectNode q = s.putObject("queryStats");
            q.put("totalProcessedBytes", "1048576");
            q.put("totalBilledBytes", "10485760");
            q.put("billingTier", 1);
            ArrayNode t = q.putArray("referencedTables");
            tables.forEach(t::add);
            if (!views.isEmpty()) {
                ArrayNode v = q.putArray("referencedViews");
                views.forEach(v::add);
            }
            q.put("outputRowCount", "42");
            if (cacheHit) {
                q.put("cacheHit", true);
            }
            s.put("totalSlotMs", "4210");
            return s;
        }

        static ObjectNode jobInsertion(ObjectNode job, String reason) {
            ObjectNode i = Entry.F.objectNode();
            i.set("job", job);
            i.put("reason", reason);
            return i;
        }

        static ObjectNode jobChange(String before, String after, ObjectNode job) {
            ObjectNode c = Entry.F.objectNode();
            c.put("before", before);
            c.put("after", after);
            c.set("job", job);
            return c;
        }

        static ObjectNode tableDataChange(String reason, String jobName, String streamName) {
            ObjectNode c = Entry.F.objectNode();
            c.put("insertedRowsCount", "120");
            if ("QUERY".equals(reason)) {
                c.put("deletedRowsCount", "3");
            }
            c.put("reason", reason);
            if (jobName != null) {
                c.put("jobName", jobName);
            }
            if (streamName != null) {
                c.put("streamName", streamName);
            }
            return c;
        }

        static ObjectNode tableChange(String table) {
            ObjectNode c = Entry.F.objectNode();
            ObjectNode t = c.putObject("table");
            t.put("tableName", table);
            t.put("updateTime", "2026-09-03T12:10:00Z");
            c.put("reason", "TABLE_UPDATE_REQUEST");
            return c;
        }

        static ObjectNode datasetEvent(String dataset, String reason) {
            ObjectNode c = Entry.F.objectNode();
            ObjectNode d = c.putObject("dataset");
            d.put("datasetName", dataset);
            d.put("createTime", "2026-09-03T12:20:00Z");
            c.put("reason", reason);
            return c;
        }

        static ObjectNode named(String key, String nameField, String name, String reason) {
            ObjectNode c = Entry.F.objectNode();
            c.putObject(key).put(nameField, name);
            c.put("reason", reason);
            return c;
        }

        static ObjectNode vpcMetadata() {
            ObjectNode m = Entry.F.objectNode();
            m.put("@type", VPC_META);
            m.put("violationReason", "NO_MATCHING_ACCESS_LEVEL");
            m.putArray("resourceNames").add(P);
            m.putObject("securityPolicyInfo").put("servicePerimeterName",
                    "accessPolicies/999900000003/servicePerimeters/dlap_fixture_perimeter").put("organizationId", "999900000001");
            ObjectNode in = m.putArray("ingressViolations").addObject();
            in.put("servicePerimeter", "accessPolicies/999900000003/servicePerimeters/dlap_fixture_perimeter");
            in.put("targetResource", P);
            in.putObject("source").put("accessLevel", "accessPolicies/999900000003/accessLevels/corp_network");
            ObjectNode out = m.putArray("egressViolations").addObject();
            out.put("servicePerimeter", "accessPolicies/999900000003/servicePerimeters/dlap_fixture_perimeter");
            out.put("targetResource", "projects/999900000004");
            out.put("sourceType", "Resource");
            m.put("vpcServiceControlsUniqueId", "dlapFixtureVpcScId0001");
            m.putObject("deviceState");
            return m;
        }

        static ObjectNode policyDelta(String role, String member) {
            ObjectNode sd = Entry.F.objectNode();
            sd.put("@type", IAM_DATA);
            ObjectNode b = sd.putObject("policyDelta").putArray("bindingDeltas").addObject();
            b.put("action", "ADD");
            b.put("role", role);
            b.put("member", member);
            return sd;
        }

        static ObjectNode data1() {
            ObjectNode sd = Entry.F.objectNode();
            sd.put("@type", BQ_DATA);
            return sd;
        }

        static ObjectNode tableName1(String table) {
            ObjectNode t = Entry.F.objectNode();
            t.put("projectId", PROJECT);
            t.put("datasetId", "sales");
            t.put("tableId", table);
            return t;
        }

        static ObjectNode jobName1(String jobId) {
            ObjectNode j = Entry.F.objectNode();
            j.put("projectId", PROJECT);
            j.put("jobId", jobId);
            j.put("location", "US");
            return j;
        }

        static ObjectNode query1(String statementType, ObjectNode destination) {
            ObjectNode c = Entry.F.objectNode();
            ObjectNode q = c.putObject("query");
            q.put("query", "SELECT order_id FROM sales.orders");
            ObjectNode dest = destination != null ? destination : Entry.F.objectNode()
                    .put("projectId", PROJECT).put("datasetId", "_8f3c1d2e").put("tableId", "anon6b1f");
            q.set("destinationTable", dest);
            q.put("createDisposition", "CREATE_IF_NEEDED");
            q.put("writeDisposition", destination != null ? "WRITE_APPEND" : "WRITE_TRUNCATE");
            q.put("queryPriority", "QUERY_INTERACTIVE");
            q.put("statementType", statementType);
            return c;
        }

        static ObjectNode stats1(List<String> tables, List<String> views, boolean reservationUsage) {
            ObjectNode s = Entry.F.objectNode();
            s.put("createTime", "2026-09-04T11:00:00Z");
            s.put("startTime", "2026-09-04T11:00:00.100Z");
            s.put("endTime", "2026-09-04T11:00:02.600Z");
            s.put("totalProcessedBytes", "1048576");
            s.put("totalBilledBytes", "10485760");
            s.put("billingTier", 1);
            s.put("totalSlotMs", "3100");
            if (reservationUsage) {
                s.putArray("reservationUsage").addObject().put("name", "unreserved").put("slotMs", "3100");
            }
            if (!tables.isEmpty()) {
                ArrayNode t = s.putArray("referencedTables");
                tables.forEach(x -> t.add(tableName1(x)));
                s.put("totalTablesProcessed", tables.size());
            }
            if (!views.isEmpty()) {
                ArrayNode v = s.putArray("referencedViews");
                views.forEach(x -> v.add(tableName1(x)));
                s.put("totalViewsProcessed", views.size());
            }
            s.put("queryOutputRowCount", "42");
            return s;
        }

        static ObjectNode completedEvent(String jobId, ObjectNode configuration, ObjectNode statistics, String eventName) {
            ObjectNode sd = data1();
            ObjectNode ev = sd.putObject("jobCompletedEvent");
            ev.put("eventName", eventName != null ? eventName : "query_job_completed");
            ObjectNode job = ev.putObject("job");
            job.set("jobName", jobName1(jobId));
            job.set("jobConfiguration", configuration);
            job.putObject("jobStatus").put("state", "DONE");
            job.set("jobStatistics", statistics);
            return sd;
        }

        // ---------------------------------------------------------------- entry rewrites

        /** The same entry under another parent (folder or organization). */
        Entry retarget(Entry e, String parent) {
            ObjectNode j = e.json();
            String logName = j.path("logName").asText();
            String logId = logName.substring(logName.indexOf("%2F") + 3);
            Entry r = Entry.of(parent, logId, j.path("insertId").asText(), j.path("timestamp").asText());
            return copyInto(r, j, null);
        }

        /** The same entry in another log of the same project. */
        Entry retargetLog(Entry e, String logId) {
            ObjectNode j = e.json();
            Entry r = Entry.of(P, logId, j.path("insertId").asText(), j.path("timestamp").asText());
            return copyInto(r, j, null);
        }

        /** The same entry with another time and insert id. */
        Entry retargetTime(Entry e, String time, String insertId) {
            ObjectNode j = e.json();
            String logName = j.path("logName").asText();
            String logId = logName.substring(logName.indexOf("%2F") + 3);
            Entry r = Entry.of(P, logId, cell.tag + "-" + insertId, time);
            j.put("receiveTimestamp", plus(time, 700));
            return copyInto(r, j, null);
        }

        /** Copies every member of {@code from} except the identity fields into {@code into}. */
        static Entry copyInto(Entry into, ObjectNode from, String receive) {
            from.fields().forEachRemaining(f -> {
                String k = f.getKey();
                if (!k.equals("logName") && !k.equals("insertId") && !k.equals("timestamp")) {
                    into.top(k, f.getValue().deepCopy());
                }
            });
            if (receive != null) {
                into.top("receiveTimestamp", receive);
            }
            return into;
        }
    }
}
