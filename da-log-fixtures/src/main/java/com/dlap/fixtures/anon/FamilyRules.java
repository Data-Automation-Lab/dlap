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

package com.dlap.fixtures.anon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the importer knows about one log family: which envelope and version a record belongs to, what
 * a complete record is, how seed records are wrapped into an input document, and the facts a correct
 * parser must report for a record.
 *
 * <p>The facts are the fields that copy through from the provider record under the rules of
 * {@code docs/specs/log-event.md} (native key, event time, provider, service, operation, result,
 * schema version, stable principal) and, for the few operations the research classifies explicitly,
 * the event category. They are written from the specification, not from any parser, so a parser
 * package is graded against them rather than against its own output. Fields that need a decision a
 * parser package owns (change type, canonical URI, parse warnings) are left out.
 */
interface FamilyRules {

    /** The facts of one record. */
    record Facts(String operation, List<String> variants, ObjectNode event) {
    }

    /**
     * Returns the family directory name.
     *
     * @return for example {@code cloudtrail}
     */
    String family();

    /**
     * Returns the envelope id of the cases this family imports.
     *
     * @return for example {@code s3-file}
     */
    String envelope();

    /**
     * Returns the version cell of a record.
     *
     * @param record one provider record
     * @return the version id used in the case path
     * @throws IllegalArgumentException when the record lacks a field the family needs
     */
    String versionOf(JsonNode record);

    /**
     * Wraps seed records into the input document of a case, the way the provider delivers them.
     *
     * @param records the anonymised records
     * @return the document
     */
    JsonNode wrap(List<JsonNode> records);

    /**
     * Gives every record of a case its own native event id, in place, when the source sample reuses one
     * id for different records (manifest {@code distinct_event_ids: true}, Q-642). The new id is a
     * name-based UUID ({@link java.util.UUID#nameUUIDFromBytes}) of {@code <seed>|<record index>}, so a
     * re-import gives the same ids. Families without a per-record id refuse.
     *
     * @param records the anonymised records of one case, in order; changed in place
     * @param seed    the case's identity, for example {@code cloudtrail/<case name>}
     * @throws IllegalArgumentException when the family has no per-record event id
     */
    default void makeEventIdsDistinct(List<JsonNode> records, String seed) {
        throw new IllegalArgumentException(family() + " records have no event id that distinct_event_ids could rewrite");
    }

    /**
     * Reverses {@link #wrap(List)}: returns the records of an input document, in order.
     *
     * @param document an input document made by {@code wrap}
     * @return the records
     */
    List<JsonNode> unwrap(JsonNode document);

    /**
     * Returns the file extension of the input: {@code json} for a document, {@code jsonl} for lines.
     *
     * @return the extension without the dot
     */
    default String inputExtension() {
        return "json";
    }

    /**
     * Returns the variants that follow from how the input file is stored rather than from a record, for
     * example a gzip compressed trail file.
     *
     * @param gzip whether the input is stored gzip compressed
     * @return the variant ids; empty for most families
     */
    default List<String> fileVariants(boolean gzip) {
        return List.of();
    }

    /**
     * Computes the facts of one record.
     *
     * @param record one anonymised provider record
     * @return its facts
     * @throws IllegalArgumentException when the record lacks a field the family needs
     */
    Facts facts(JsonNode record);

    /**
     * Returns the rules of a family.
     *
     * @param family the family directory name
     * @return its rules
     * @throws IllegalArgumentException when the family is unknown
     */
    static FamilyRules of(String family) {
        return switch (family) {
            case "cloudtrail" -> new CloudTrailRules();
            case "gcpaudit" -> new GcpAuditRules();
            case "azactivity" -> new AzureActivityRules();
            case "azstoragelog" -> new AzureStorageRules();
            case "dbxaudit" -> new DatabricksRules();
            default -> throw new IllegalArgumentException("no import rules for family '" + family + "'");
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Reads a string at a path of field names; null when absent, null or not a string. */
    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            if (n == null) {
                return null;
            }
            n = n.get(p);
        }
        return n != null && n.isTextual() ? n.asText() : null;
    }

    /** Reads a node at a path of field names; null when absent. */
    static JsonNode at(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            if (n == null) {
                return null;
            }
            n = n.get(p);
        }
        return n;
    }

    /** Requires a non-blank string at a path. */
    static String need(JsonNode node, String... path) {
        String v = str(node, path);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("record lacks " + String.join(".", path));
        }
        return v;
    }

    /** Parses an ISO-8601 instant, naming the field when it is not one. */
    static Instant instant(String text, String field) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not an ISO-8601 instant: " + text, e);
        }
    }

    /** Compares dotted major.minor versions numerically: negative, zero or positive. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int major = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
        if (major != 0) {
            return major;
        }
        int minorA = x.length > 1 ? Integer.parseInt(x[1]) : 0;
        int minorB = y.length > 1 ? Integer.parseInt(y[1]) : 0;
        return Integer.compare(minorA, minorB);
    }

    /** An empty event object. */
    static ObjectNode newEvent() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Wraps records as {@code {"<key>": [records]}}. */
    static JsonNode wrapIn(String key, List<JsonNode> records) {
        ObjectNode doc = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = doc.putArray(key);
        records.forEach(arr::add);
        return doc;
    }

    /** Reverses {@link #wrapIn(String, List)}. */
    static List<JsonNode> unwrapIn(String key, JsonNode document) {
        JsonNode arr = document.get(key);
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("document has no '" + key + "' array");
        }
        List<JsonNode> out = new java.util.ArrayList<>();
        arr.forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------
    // AWS CloudTrail
    // ------------------------------------------------------------------

    /** CloudTrail records delivered as S3 trail files, {@code {"Records": [...]}}. */
    final class CloudTrailRules implements FamilyRules {

        private static final Map<String, String> CATEGORY = Map.ofEntries(
                Map.entry("s3.amazonaws.com:GetObject", "EVENT_CATEGORY_DATA_READ"),
                Map.entry("s3.amazonaws.com:SelectObjectContent", "EVENT_CATEGORY_DATA_READ"),
                Map.entry("s3.amazonaws.com:GetObjectAttributes", "EVENT_CATEGORY_DATA_READ"),
                Map.entry("s3.amazonaws.com:PutObject", "EVENT_CATEGORY_DATA_WRITE"),
                Map.entry("s3.amazonaws.com:CopyObject", "EVENT_CATEGORY_DATA_WRITE"),
                Map.entry("s3.amazonaws.com:CompleteMultipartUpload", "EVENT_CATEGORY_DATA_WRITE"),
                Map.entry("s3.amazonaws.com:DeleteObject", "EVENT_CATEGORY_DATA_WRITE"),
                Map.entry("s3.amazonaws.com:DeleteObjects", "EVENT_CATEGORY_DATA_WRITE"),
                Map.entry("s3.amazonaws.com:PutObjectAcl", "EVENT_CATEGORY_PERMISSION"),
                Map.entry("s3.amazonaws.com:PutBucketPolicy", "EVENT_CATEGORY_PERMISSION"),
                Map.entry("s3.amazonaws.com:PutBucketAcl", "EVENT_CATEGORY_PERMISSION"),
                Map.entry("s3.amazonaws.com:CreateBucket", "EVENT_CATEGORY_MANAGEMENT"),
                Map.entry("s3.amazonaws.com:DeleteBucket", "EVENT_CATEGORY_MANAGEMENT"),
                Map.entry("s3.amazonaws.com:GetBucketPolicy", "EVENT_CATEGORY_MANAGEMENT"),
                Map.entry("s3.amazonaws.com:ListBuckets", "EVENT_CATEGORY_MANAGEMENT"),
                Map.entry("s3.amazonaws.com:ListObjects", "EVENT_CATEGORY_MANAGEMENT"),
                Map.entry("s3.amazonaws.com:HeadBucket", "EVENT_CATEGORY_MANAGEMENT"));

        private static final Map<String, String> IDENTITY_VARIANT = Map.ofEntries(
                Map.entry("IAMUser", "uid.iam-user"),
                Map.entry("AssumedRole", "uid.assumed-role"),
                Map.entry("Root", "uid.root"),
                Map.entry("FederatedUser", "uid.federated-user"),
                Map.entry("Role", "uid.role"),
                Map.entry("Directory", "uid.directory"),
                Map.entry("AWSAccount", "uid.aws-account"),
                Map.entry("AWSService", "uid.aws-service"),
                Map.entry("IdentityCenterUser", "uid.identity-center-user"),
                Map.entry("BuilderIdUser", "uid.builder-id-user"),
                Map.entry("SAMLUser", "uid.saml-user"),
                Map.entry("WebIdentityUser", "uid.web-identity-user"),
                Map.entry("Unknown", "uid.unknown"));

        @Override
        public String family() {
            return "cloudtrail";
        }

        @Override
        public String envelope() {
            return "s3-file";
        }

        @Override
        public String versionOf(JsonNode r) {
            String v = need(r, "eventVersion");
            if (!v.matches("[0-9]+\\.[0-9]+")) {
                throw new IllegalArgumentException("eventVersion is not major.minor: " + v);
            }
            return v;
        }

        @Override
        public List<String> fileVariants(boolean gzip) {
            return gzip ? List.of("file.gzip-records") : List.of();
        }

        @Override
        public JsonNode wrap(List<JsonNode> records) {
            return wrapIn("Records", records);
        }

        @Override
        public List<JsonNode> unwrap(JsonNode document) {
            return unwrapIn("Records", document);
        }

        @Override
        public void makeEventIdsDistinct(List<JsonNode> records, String seed) {
            for (int i = 0; i < records.size(); i++) {
                if (!(records.get(i) instanceof com.fasterxml.jackson.databind.node.ObjectNode r)) {
                    throw new IllegalArgumentException("record " + i + " is not an object");
                }
                r.put("eventID", java.util.UUID.nameUUIDFromBytes((seed + "|" + i)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            }
        }

        @Override
        public Facts facts(JsonNode r) {
            String version = versionOf(r);
            String source = need(r, "eventSource");
            String name = need(r, "eventName");
            String id = need(r, "eventID");
            Instant time = instant(need(r, "eventTime"), "eventTime");
            JsonNode uid = r.get("userIdentity");
            if (uid == null || !uid.isObject() || uid.isEmpty()) {
                throw new IllegalArgumentException("record lacks userIdentity");
            }
            // AWS service events (eventType AwsServiceEvent) carry only accountId and invokedBy: no type.
            String type = str(uid, "type");
            if (type == null && str(uid, "invokedBy") == null) {
                throw new IllegalArgumentException("record lacks userIdentity.type");
            }
            String op = source + ":" + name;
            String error = str(r, "errorCode");
            boolean failed = error != null && !error.isEmpty();

            ObjectNode e = newEvent();
            e.put("nativeEventId", id);
            e.put("eventTime", time.toString());
            e.put("sourceProvider", "AWS");
            e.put("sourceService", source);
            e.put("operation", name);
            e.put("resultStatus", failed ? "FAILURE" : "SUCCESS");
            String principal = type == null ? null : switch (type) {
                case "IAMUser", "Root" -> str(uid, "arn");
                case "AssumedRole" -> str(uid, "sessionContext", "sessionIssuer", "arn");
                default -> null;
            };
            if (principal != null && !principal.isEmpty()) {
                e.putObject("principal").put("id", principal);
            }
            String category = CATEGORY.get(op);
            if (category != null) {
                e.put("eventCategory", category);
            }
            e.put("sourceFormat", "AWS_CLOUDTRAIL/s3-file");
            e.put("sourceSchemaVersion", version);
            String shared = str(r, "sharedEventID");
            if (shared != null && !shared.isEmpty()) {
                e.put("sharedEventId", shared);
            }

            Set<String> v = new LinkedHashSet<>();
            String idVariant = type == null ? null : IDENTITY_VARIANT.get(type);
            if (idVariant != null) {
                v.add(idVariant);
            }
            if (str(uid, "invokedBy") != null && !r.has("tlsDetails")) {
                v.add("uid.invoked-by");
            }
            if ("HIDDEN_DUE_TO_SECURITY_REASONS".equals(str(uid, "userName"))) {
                v.add("uid.hidden-user-name");
            }
            if (failed) {
                v.add("result.error-code-failure");
            }
            if (r.has("recipientAccountId") && compareVersions(version, "1.02") >= 0) {
                v.add("account.recipient-account-id");
            }
            if (r.has("tlsDetails")) {
                v.add("field.tls-details");
            }
            if (r.has("sessionCredentialFromConsole")) {
                v.add("field.session-credential-from-console");
            }
            if (r.has("vpcEndpointId")) {
                v.add("field.vpc-endpoint");
            }
            if (r.has("serviceEventDetails")) {
                v.add("field.service-event-details");
            }
            if (!r.has("readOnly") || !r.has("managementEvent")) {
                v.add("field.read-only-management-absent");
            }
            if (!r.has("eventCategory") && compareVersions(version, "1.06") <= 0) {
                v.add("field.event-category-absent");
            }
            if (source.equals("s3.amazonaws.com")) {
                s3Variants(r, version, v);
            }
            return new Facts(op, List.copyOf(v), e);
        }

        private static void s3Variants(JsonNode r, String version, Set<String> v) {
            JsonNode params = r.get("requestParameters");
            if (params != null && params.has("bucketName") && params.has("key")) {
                v.add("s3.request-parameters-bucket-key");
            }
            if (params != null && (params.has("host") || params.has("Host"))) {
                v.add("s3.host-header-forms");
            }
            boolean object = false;
            boolean bucket = false;
            boolean bucketAccount = false;
            JsonNode resources = r.get("resources");
            if (resources != null && resources.isArray()) {
                for (JsonNode res : resources) {
                    String t = str(res, "type");
                    object |= "AWS::S3::Object".equals(t);
                    if ("AWS::S3::Bucket".equals(t)) {
                        bucket = true;
                        bucketAccount |= res.has("accountId");
                    }
                }
            }
            if (object && bucket) {
                v.add("s3.object-and-bucket-resources");
            }
            if (bucketAccount) {
                v.add("account.bucket-owner-from-resources");
            }
            if (r.has("sharedEventID") && compareVersions(version, "1.03") >= 0) {
                v.add("s3.cross-account-shared-event");
            }
        }
    }

    // ------------------------------------------------------------------
    // GCP Cloud Audit Logs
    // ------------------------------------------------------------------

    /** GCP Cloud Audit LogEntry JSON in REST field naming, one entry per input document. */
    final class GcpAuditRules implements FamilyRules {

        private static final Map<String, String> CATEGORY = Map.of(
                "storage.googleapis.com:storage.objects.get", "EVENT_CATEGORY_DATA_READ",
                "storage.googleapis.com:storage.objects.list", "EVENT_CATEGORY_MANAGEMENT",
                "storage.googleapis.com:storage.objects.create", "EVENT_CATEGORY_DATA_WRITE",
                "storage.googleapis.com:storage.objects.delete", "EVENT_CATEGORY_DATA_WRITE",
                "storage.googleapis.com:storage.setIamPermissions", "EVENT_CATEGORY_PERMISSION",
                "storage.googleapis.com:storage.buckets.create", "EVENT_CATEGORY_MANAGEMENT",
                "storage.googleapis.com:storage.buckets.update", "EVENT_CATEGORY_MANAGEMENT");

        @Override
        public String family() {
            return "gcpaudit";
        }

        @Override
        public String envelope() {
            return "json";
        }

        @Override
        public String versionOf(JsonNode r) {
            String serviceData = str(r, "protoPayload", "serviceData", "@type");
            if (serviceData != null && serviceData.endsWith("google.cloud.bigquery.logging.v1.AuditData")) {
                return "bq-auditdata-legacy";
            }
            String metadata = str(r, "protoPayload", "metadata", "@type");
            if (metadata != null && metadata.endsWith("google.cloud.audit.BigQueryAuditMetadata")) {
                return "bq-auditmetadata";
            }
            JsonNode auth = at(r, "protoPayload", "authorizationInfo");
            if (auth != null && auth.isArray()) {
                for (JsonNode a : auth) {
                    if (a.has("permissionType")) {
                        return "auditlog-2025";
                    }
                }
            }
            return "auditlog-2021";
        }

        private static String schemaVersion(String cell) {
            return cell.startsWith("auditlog") ? "auditlog" : cell;
        }

        @Override
        public JsonNode wrap(List<JsonNode> records) {
            if (records.size() != 1) {
                throw new IllegalArgumentException("a gcpaudit case holds exactly one LogEntry");
            }
            return records.get(0);
        }

        @Override
        public List<JsonNode> unwrap(JsonNode document) {
            return List.of(document);
        }

        @Override
        public Facts facts(JsonNode r) {
            String logName = need(r, "logName");
            String insertId = need(r, "insertId");
            Instant time = instant(need(r, "timestamp"), "timestamp");
            String service = need(r, "protoPayload", "serviceName");
            String method = need(r, "protoPayload", "methodName");
            String cell = versionOf(r);
            String op = service + ":" + method;

            JsonNode code = at(r, "protoPayload", "status", "code");
            boolean failed = code != null && code.isNumber() && code.asInt() != 0;
            boolean denied = false;
            JsonNode auth = at(r, "protoPayload", "authorizationInfo");
            if (auth != null && auth.isArray()) {
                for (JsonNode a : auth) {
                    denied |= a.has("granted") && !a.get("granted").asBoolean(true);
                }
            }

            ObjectNode e = newEvent();
            e.put("nativeEventId", logName + "|" + time + "|" + insertId);
            e.put("eventTime", time.toString());
            String receive = str(r, "receiveTimestamp");
            if (receive != null) {
                e.put("observedTime", instant(receive, "receiveTimestamp").toString());
            }
            e.put("sourceProvider", "GCP");
            e.put("sourceService", service);
            e.put("operation", method);
            e.put("resultStatus", failed || denied ? "FAILURE" : "SUCCESS");
            String category = CATEGORY.get(op);
            if (category != null) {
                e.put("eventCategory", category);
            }
            e.put("sourceFormat", "GCP_CLOUD_AUDIT/json");
            e.put("sourceSchemaVersion", schemaVersion(cell));

            Set<String> v = new LinkedHashSet<>();
            if (logName.endsWith("%2Factivity")) {
                v.add("log.admin-activity");
            } else if (logName.endsWith("%2Fdata_access")) {
                v.add("log.data-access");
            } else if (logName.endsWith("%2Fsystem_event")) {
                v.add("log.system-event");
            } else if (logName.endsWith("%2Fpolicy")) {
                v.add("log.policy-denied");
            }
            if (failed) {
                v.add("status.nonzero-code");
            }
            if (denied) {
                v.add("status.granted-false");
            }
            String email = str(r, "protoPayload", "authenticationInfo", "principalEmail");
            if (email != null) {
                v.add("actor.principal-email");
                if (email.endsWith("gserviceaccount.com")) {
                    v.add("actor.service-account");
                }
            }
            if (str(r, "protoPayload", "@type") == null) {
                v.add("entry.protopayload-without-type");
            }
            if (cell.startsWith("auditlog") && service.equals("storage.googleapis.com")) {
                gcsVariants(r, method, v);
            }
            if (cell.equals("bq-auditdata-legacy")) {
                JsonNode refs = at(r, "protoPayload", "serviceData", "jobCompletedEvent", "job", "jobStatistics",
                        "referencedTables");
                if (refs != null && refs.isArray() && !refs.isEmpty() && refs.get(0).isObject()) {
                    v.add("bq1.referenced-tables-objects");
                }
                if ("bigquery_resource".equals(str(r, "resource", "type"))) {
                    v.add("bq1.resource-type");
                }
            }
            return new Facts(op, List.copyOf(v), e);
        }

        private static void gcsVariants(JsonNode r, String method, Set<String> v) {
            String resourceName = str(r, "protoPayload", "resourceName");
            if (resourceName != null && resourceName.contains("/objects/")) {
                v.add("gcs.object-resource-name");
            }
            if (method.equals("storage.objects.list") && resourceName != null && !resourceName.contains("/objects/")) {
                v.add("gcs.list-bucket-resource-name");
            }
            if (at(r, "resource", "labels", "bucket_name") != null) {
                v.add("gcs.resource-labels");
            }
            if (method.equals("storage.objects.create") && at(r, "protoPayload", "request") != null
                    && at(r, "protoPayload", "response") != null) {
                v.add("gcs.create-request-response");
            }
            if (method.equals("storage.setIamPermissions")
                    && at(r, "protoPayload", "serviceData", "policyDelta") != null) {
                v.add("gcs.setiam-servicedata-policy-delta");
            }
        }
    }

    // ------------------------------------------------------------------
    // Azure Activity Log
    // ------------------------------------------------------------------

    /** Azure Activity Log records of a diagnostic export, {@code {"records": [...]}}. */
    final class AzureActivityRules implements FamilyRules {

        @Override
        public String family() {
            return "azactivity";
        }

        @Override
        public String envelope() {
            return "resource-log";
        }

        @Override
        public String versionOf(JsonNode r) {
            need(r, "time");
            need(r, "resourceId");
            need(r, "operationName");
            need(r, "resultType");
            return "unversioned";
        }

        @Override
        public JsonNode wrap(List<JsonNode> records) {
            return wrapIn("records", records);
        }

        @Override
        public List<JsonNode> unwrap(JsonNode document) {
            return unwrapIn("records", document);
        }

        @Override
        public Facts facts(JsonNode r) {
            versionOf(r);
            String operation = need(r, "operationName");
            if (operation.chars().anyMatch(Character::isWhitespace)) {
                // Directory (Entra) audit events such as PIM activations are not ARM operations and have no id
                // that fits the service:operation shape of meta.yaml covers.
                throw new IllegalArgumentException("operationName contains whitespace: " + operation);
            }
            Instant time = instant(need(r, "time"), "time");
            int slash = operation.indexOf('/');
            String provider = (slash < 0 ? "azure" : operation.substring(0, slash)).toLowerCase(Locale.ROOT);
            String op = provider + ":" + operation.toLowerCase(Locale.ROOT);

            ObjectNode e = newEvent();
            e.put("eventTime", time.toString());
            e.put("sourceProvider", "AZURE");
            e.put("operation", operation);
            e.put("sourceFormat", "AZURE_ACTIVITY/resource-log");

            Set<String> v = new LinkedHashSet<>();
            v.add("envelope.records-object");
            String category = str(r, "category");
            if (category != null) {
                v.add("category." + category.toLowerCase(Locale.ROOT));
            }
            v.add("result." + need(r, "resultType").toLowerCase(Locale.ROOT));
            if (operation.equals(operation.toUpperCase(Locale.ROOT))) {
                v.add("case.operation-name-upper");
            } else if (operation.equals(operation.toLowerCase(Locale.ROOT))) {
                v.add("case.operation-name-lower");
            }
            if (r.has("RoleLocation") || r.has("Stamp") || r.has("ReleaseVersion")) {
                v.add("extra.top-level-keys");
            }
            return new Facts(op, List.copyOf(v), e);
        }
    }

    // ------------------------------------------------------------------
    // Azure Storage resource logs
    // ------------------------------------------------------------------

    /** Azure Storage blob resource logs of a diagnostic export, {@code {"records": [...]}}. */
    final class AzureStorageRules implements FamilyRules {

        @Override
        public String family() {
            return "azstoragelog";
        }

        @Override
        public String envelope() {
            return "resource-log";
        }

        @Override
        public String versionOf(JsonNode r) {
            return need(r, "schemaVersion");
        }

        @Override
        public JsonNode wrap(List<JsonNode> records) {
            return wrapIn("records", records);
        }

        @Override
        public List<JsonNode> unwrap(JsonNode document) {
            return unwrapIn("records", document);
        }

        @Override
        public Facts facts(JsonNode r) {
            String version = versionOf(r);
            need(r, "resourceId");
            need(r, "category");
            String operation = need(r, "operationName");
            Instant time = instant(need(r, "time"), "time");
            JsonNode code = r.get("statusCode");
            if (code == null || !code.isNumber()) {
                throw new IllegalArgumentException("record lacks a numeric statusCode");
            }
            boolean success = code.asInt() >= 200 && code.asInt() < 300;

            ObjectNode e = newEvent();
            e.put("eventTime", time.toString());
            e.put("sourceProvider", "AZURE");
            e.put("operation", operation);
            e.put("resultStatus", success ? "SUCCESS" : "FAILURE");
            e.put("sourceFormat", "AZURE_STORAGE_RESOURCE_LOG/resource-log");
            e.put("sourceSchemaVersion", version);

            Set<String> v = new LinkedHashSet<>();
            v.add("envelope.records-object");
            if (!success) {
                v.add("status.non-2xx-failure");
            }
            JsonNode authz = at(r, "identity", "authorization");
            if (authz != null && authz.isArray()) {
                for (JsonNode a : authz) {
                    if ("Denied".equals(str(a, "result")) && "NoPolicy".equals(str(a, "reason"))) {
                        v.add("status.denied-no-policy");
                    }
                }
            }
            String identityType = str(r, "identity", "type");
            if ("OAuth".equals(identityType) && at(r, "identity", "requester") != null) {
                v.add("actor.oauth-requester");
            }
            if ("SAS".equals(identityType) && at(r, "identity", "requester") == null) {
                v.add("actor.sas-unattributed");
            }
            String key = str(r, "properties", "objectKey");
            if (key != null && key.startsWith("/")) {
                v.add("object.key-leading-slash");
            }
            if (r.has("operationVersion")) {
                v.add("version.operation-version-values");
            }
            return new Facts("StorageBlobLogs:" + operation, List.copyOf(v), e);
        }
    }

    // ------------------------------------------------------------------
    // Databricks audit logs
    // ------------------------------------------------------------------

    /** Databricks audit logs as delivered: JSON Lines, one record per line, camelCase, epoch millisecond timestamp. */
    final class DatabricksRules implements FamilyRules {

        @Override
        public String family() {
            return "dbxaudit";
        }

        @Override
        public String envelope() {
            return "delivered-json";
        }

        @Override
        public String versionOf(JsonNode r) {
            return need(r, "version");
        }

        @Override
        public String inputExtension() {
            return "jsonl";
        }

        @Override
        public JsonNode wrap(List<JsonNode> records) {
            ArrayNode lines = JsonNodeFactory.instance.arrayNode();
            records.forEach(lines::add);
            return lines;
        }

        @Override
        public List<JsonNode> unwrap(JsonNode document) {
            List<JsonNode> out = new java.util.ArrayList<>();
            document.forEach(out::add);
            return out;
        }

        @Override
        public Facts facts(JsonNode r) {
            String version = versionOf(r);
            JsonNode ts = r.get("timestamp");
            if (ts == null || !ts.isIntegralNumber()) {
                throw new IllegalArgumentException("record lacks an integer timestamp");
            }
            String requestId = need(r, "requestId");
            String service = need(r, "serviceName");
            String action = need(r, "actionName");
            Instant time = Instant.ofEpochMilli(ts.asLong());
            JsonNode status = at(r, "response", "statusCode");

            ObjectNode e = newEvent();
            e.put("nativeEventId", "DATABRICKS_AUDIT|" + requestId + "|" + ts.asLong() + "|" + action);
            e.put("eventTime", time.toString());
            e.put("sourceProvider", "DATABRICKS");
            e.put("sourceService", service);
            e.put("operation", action);
            if (status != null && status.isNumber()) {
                e.put("resultStatus", status.asInt() >= 200 && status.asInt() < 300 ? "SUCCESS" : "FAILURE");
            }
            e.put("sourceFormat", "DATABRICKS_AUDIT/delivered-json");
            e.put("sourceSchemaVersion", version);

            Set<String> v = new LinkedHashSet<>();
            v.add("time.epoch-milliseconds");
            if (status != null && status.isNumber() && (status.asInt() < 200 || status.asInt() >= 300)) {
                v.add("status.error-response");
            }
            if (r.has("userIdentity") && str(r, "userIdentity", "email") != null) {
                v.add("actor.email");
            }
            String level = str(r, "auditLevel");
            if ("ACCOUNT_LEVEL".equals(level)) {
                v.add("scope.account-level-workspace-zero");
            } else if ("WORKSPACE_LEVEL".equals(level)) {
                v.add("scope.workspace-level");
            }
            return new Facts(service + ":" + action, List.copyOf(v), e);
        }
    }
}
