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

package com.dlap.logingest.providers.aws.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.s3accesslog.S3AccessLogFormat;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The S3 server access log text format, split into the CloudWatch Logs field names (decision D36).
 *
 * <p>The lines follow the documented example log and the Compute checksum example of the LogFormat
 * page (field order, a GET of bucket versioning, a 404 with an error code, a PUT with an access point
 * ARN, aclRequired and source region, an S3.* record with dashes), with invented values so no vendor
 * text is copied (plan rule R14). Edge cases: a quoted Request-URI with spaces, unescaped quotes in
 * the quoted fields, {@code -} quoted and not, fields AWS adds at the end, an older line without the
 * newer fields, and malformed lines.
 */
class S3AccessLogLineTest {

    private static final String OWNER = "5f1c0de0c0ffee0000000000000000000000000000000000000000000000beef";
    private static final String HOST_ID = "aGVsbG8gZnJvbSBkbGFwIGF3cy0wMSB0ZXN0IGhvc3QgaWQgdmFsdWUgMTIzNA==";

    /** The shape of the first documented example: a console GET of the bucket's versioning. */
    static final String GET_VERSIONING = OWNER + " dlap-sal-bucket [06/Feb/2026:00:00:38 +0000] 198.51.100.7 "
            + OWNER + " 7C0FFEE00000AA01 REST.GET.VERSIONING - \"GET /dlap-sal-bucket?versioning HTTP/1.1\" 200 - "
            + "113 - 7 - \"-\" \"S3Console/0.4\" - " + HOST_ID + " SigV4 ECDHE-RSA-AES128-GCM-SHA256 AuthHeader "
            + "dlap-sal-bucket.s3.us-west-1.amazonaws.com TLSv1.2 "
            + "arn:aws:s3:us-west-1:123456789012:accesspoint/dlap-ap Yes us-east-1";

    /** The shape of the documented 404 example: an error code and dashes. */
    static final String GET_POLICY_404 = OWNER + " dlap-sal-bucket [06/Feb/2026:00:00:38 +0000] 198.51.100.7 "
            + OWNER + " 7C0FFEE00000AA02 REST.GET.BUCKETPOLICY - \"GET /dlap-sal-bucket?policy HTTP/1.1\" 404 "
            + "NoSuchBucketPolicy 297 - 38 - \"-\" \"S3Console/0.4\" - " + HOST_ID + " SigV4 "
            + "ECDHE-RSA-AES128-GCM-SHA256 AuthHeader dlap-sal-bucket.s3.us-west-1.amazonaws.com TLSv1.2 - Yes "
            + "us-east-1";

    /** The shape of the documented PUT example. */
    static final String PUT_OBJECT = OWNER + " dlap-sal-bucket [06/Feb/2026:00:01:57 +0000] 198.51.100.7 "
            + "arn:aws:sts::123456789012:assumed-role/dlap-writer/session-1 7C0FFEE00000AA03 REST.PUT.OBJECT "
            + "reports/q3.pdf \"PUT /dlap-sal-bucket/reports/q3.pdf HTTP/1.1\" 200 - - 4406583 41754 28 \"-\" "
            + "\"S3Console/0.4\" - " + HOST_ID + " SigV4 ECDHE-RSA-AES128-SHA AuthHeader "
            + "dlap-sal-bucket.s3.us-west-1.amazonaws.com TLSv1.2 - Yes us-east-1";

    /** The shape of the Compute checksum example: an S3.* operation with dashes for the request fields. */
    static final String CHECKSUM = OWNER + " dlap-sal-bucket [06/Feb/2026:00:00:38 +0000] - " + OWNER
            + " 0d1a0000-aws0-4001-8000-000000000001 S3.COMPUTE.OBJECT.CHECKSUM reports/q3.pdf - - - - 1048576 - - - - "
            + "- " + HOST_ID + " - - - - - - - -";

    /** A lifecycle expiration, as S3 writes S3.EXPIRE.OBJECT records (the delete proof's signature). */
    static final String EXPIRE = OWNER + " dlap-aws-e2e [02/Nov/2026:03:20:41 +0000] - - AWS01SAL00000001 "
            + "S3.EXPIRE.OBJECT raw/2026/09/orders.parquet - - - - 1048576 - - - - - - - - - - - - - - -";

    private static ObjectNode parse(String line) {
        return S3AccessLogLine.parse(line).orElseThrow(() -> new AssertionError("line did not split: " + line));
    }

    private static ParseOutcome registry(ObjectNode body) {
        var ctx = AwsS3ForTests.accessLog(Map.of("bucket", "logs"), new AwsS3ForTests.DoneObjects());
        RawRecord r = RawRecord.builder().bindingId("aws-test").sourceKind(S3AccessLogFormat.SOURCE_KIND)
                .envelope(S3AccessLogFormat.CWL_JSON).ref(RawRef.of("TEST", "test:line", 0)).mode(IngestMode.STREAM)
                .fetchedAt(AwsS3ForTests.NOW).json(body).build();
        return AwsS3ForTests.parse(ctx, r);
    }

    private static CanonicalEvent event(ObjectNode body) {
        ParseOutcome o = registry(body);
        assertThat(o.result()).as(o.outcomeCode()).isInstanceOf(ParseResult.Parsed.class);
        return ((ParseResult.Parsed) o.result()).events().get(0);
    }

    @Test
    void theDocumentedExampleShapesSplitIntoTheCloudWatchFieldNames() {
        ObjectNode r = parse(GET_VERSIONING);

        assertThat(r.size()).isEqualTo(29);
        assertThat(r.path("schema_version_id").asText()).isEqualTo("V_1_0");
        assertThat(r.path("bucket_arn").isNull()).isTrue();
        assertThat(r.path("bucket_owner_id").asText()).isEqualTo(OWNER);
        assertThat(r.path("bucket_name").asText()).isEqualTo("dlap-sal-bucket");
        assertThat(r.path("request_time").asText()).isEqualTo("2026-02-06T00:00:38Z");
        assertThat(r.path("remote_ip").asText()).isEqualTo("198.51.100.7");
        assertThat(r.path("requester").asText()).isEqualTo(OWNER);
        assertThat(r.path("request_id").asText()).isEqualTo("7C0FFEE00000AA01");
        assertThat(r.path("operation").asText()).isEqualTo("REST.GET.VERSIONING");
        assertThat(r.path("key_name").isNull()).isTrue();
        assertThat(r.path("request_uri").asText()).isEqualTo("GET /dlap-sal-bucket?versioning HTTP/1.1");
        assertThat(r.path("http_status").isNumber()).isTrue();
        assertThat(r.path("http_status").asInt()).isEqualTo(200);
        assertThat(r.path("error_code").isNull()).isTrue();
        assertThat(r.path("bytes_sent_size").asLong()).isEqualTo(113);
        assertThat(r.path("object_size").isNull()).isTrue();
        assertThat(r.path("total_duration").asLong()).isEqualTo(7);
        assertThat(r.path("turn_around_duration").isNull()).isTrue();
        assertThat(r.path("referer").isNull()).as("a quoted dash is null too").isTrue();
        assertThat(r.path("user_agent").asText()).isEqualTo("S3Console/0.4");
        assertThat(r.path("version_id").isNull()).isTrue();
        assertThat(r.path("host_id").asText()).isEqualTo(HOST_ID);
        assertThat(r.path("signature_version").asText()).isEqualTo("SigV4");
        assertThat(r.path("cipher_suite").asText()).isEqualTo("ECDHE-RSA-AES128-GCM-SHA256");
        assertThat(r.path("authentication_type").asText()).isEqualTo("AuthHeader");
        assertThat(r.path("host_header").asText()).isEqualTo("dlap-sal-bucket.s3.us-west-1.amazonaws.com");
        assertThat(r.path("tls_version").asText()).isEqualTo("TLSv1.2");
        assertThat(r.path("access_point_arn").asText()).isEqualTo("arn:aws:s3:us-west-1:123456789012:accesspoint/dlap-ap");
        assertThat(r.path("acl_required").asText()).isEqualTo("Yes");
        assertThat(r.path("source_region").asText()).isEqualTo("us-east-1");
    }

    @Test
    void theDocumentedErrorPutAndChecksumShapesSplitAndParse() {
        ObjectNode notFound = parse(GET_POLICY_404);
        assertThat(notFound.path("http_status").asInt()).isEqualTo(404);
        assertThat(notFound.path("error_code").asText()).isEqualTo("NoSuchBucketPolicy");
        assertThat(notFound.path("access_point_arn").isNull()).isTrue();
        assertThat(event(notFound).result()).isEqualTo(ResultStatus.FAILURE);

        ObjectNode put = parse(PUT_OBJECT);
        assertThat(put.path("key_name").asText()).isEqualTo("reports/q3.pdf");
        assertThat(put.path("object_size").asLong()).isEqualTo(4406583);
        assertThat(put.path("bytes_sent_size").isNull()).isTrue();
        CanonicalEvent write = event(put);
        assertThat(write.changeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(write.principal().type()).isEqualTo(PrincipalType.ROLE);
        assertThat(write.target().canonicalUri()).isEqualTo("s3://dlap-sal-bucket/reports/q3.pdf");

        ObjectNode checksum = parse(CHECKSUM);
        assertThat(checksum.path("remote_ip").isNull()).isTrue();
        assertThat(checksum.path("request_uri").isNull()).isTrue();
        assertThat(checksum.path("object_size").asLong()).isEqualTo(1048576);
        assertThat(checksum.path("user_agent").isNull()).isTrue();
        assertThat(event(checksum).principal().type()).isEqualTo(PrincipalType.SYSTEM);
    }

    @Test
    void aLifecycleExpirationLineIsADeleteOfTheObjectByTheSystem() {
        ObjectNode r = parse(EXPIRE);
        assertThat(r.path("requester").isNull()).isTrue();

        CanonicalEvent e = event(r);

        assertThat(e.operation()).isEqualTo("S3.EXPIRE.OBJECT");
        assertThat(e.changeType()).isEqualTo(ChangeType.DELETE);
        assertThat(e.principal().type()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(e.target().canonicalUri()).isEqualTo("s3://dlap-aws-e2e/raw/2026/09/orders.parquet");
        assertThat(e.nativeKey()).isEqualTo("AWS_S3_SERVER_ACCESS_LOG|dlap-aws-e2e|2026-11-02T03:20:41Z|"
                + "S3.EXPIRE.OBJECT|AWS01SAL00000001|raw/2026/09/orders.parquet");
    }

    @Test
    void aQuotedRequestUriWithSpacesAndUnescapedQuotesStaysOneField() {
        String line = OWNER + " dlap-sal-bucket [06/Feb/2026:00:02:00 +0000] 198.51.100.7 " + OWNER
                + " 7C0FFEE00000AA04 REST.GET.OBJECT my%20file.csv "
                + "\"GET /dlap-sal-bucket/my file.csv?x-note=\"a b\" HTTP/1.1\" 200 - 10 10 3 2 "
                + "\"https://example.com/p?q=\"x\"\" \"agent \"quoted\" 1.0\" - " + HOST_ID
                + " SigV4 ECDHE-RSA-AES128-GCM-SHA256 AuthHeader s3.us-west-1.amazonaws.com TLSv1.3 - - -";

        ObjectNode r = parse(line);

        assertThat(r.path("request_uri").asText()).isEqualTo("GET /dlap-sal-bucket/my file.csv?x-note=\"a b\" HTTP/1.1");
        assertThat(r.path("http_status").asInt()).isEqualTo(200);
        assertThat(r.path("referer").asText()).isEqualTo("https://example.com/p?q=\"x\"");
        assertThat(r.path("user_agent").asText()).isEqualTo("agent \"quoted\" 1.0");
        assertThat(r.path("signature_version").asText()).isEqualTo("SigV4");
        assertThat(r.path("tls_version").asText()).isEqualTo("TLSv1.3");
        assertThat(r.path("source_region").isNull()).isTrue();
        assertThat(r.path("key_name").asText()).as("the key is kept as written").isEqualTo("my%20file.csv");
    }

    @Test
    void aQuoteInsideAnUnquotedFieldIsAnOrdinaryCharacter() {
        String line = OWNER + " dlap-sal-bucket [06/Feb/2026:00:02:00 +0000] 198.51.100.7 - 7C0FFEE00000AA05 "
                + "REST.GET.OBJECT \"odd\"key \"GET /dlap-sal-bucket/odd HTTP/1.1\" 403 AccessDenied 243 - 5 - \"-\" "
                + "\"curl/8.0\" - " + HOST_ID + " - - - s3.amazonaws.com - - - -";

        ObjectNode r = parse(line);

        assertThat(r.path("key_name").asText()).isEqualTo("\"odd\"key");
        assertThat(r.path("requester").isNull()).isTrue();
        assertThat(r.path("error_code").asText()).isEqualTo("AccessDenied");
    }

    @Test
    void trailingFieldsAwsAddsLaterAreIgnored() {
        ObjectNode r = parse(GET_VERSIONING + " future-field-1 \"future quoted\" 42");

        assertThat(r.size()).isEqualTo(29);
        assertThat(r.path("source_region").asText()).isEqualTo("us-east-1");
        assertThat(r.has("future-field-1")).isFalse();
    }

    @Test
    void anOlderLineWithoutTheNewerFieldsHasThemAsNull() {
        String old = OWNER + " dlap-sal-bucket [06/Feb/2018:00:00:38 +0000] 198.51.100.7 " + OWNER
                + " 7C0FFEE00000AA06 REST.DELETE.OBJECT old.csv \"DELETE /dlap-sal-bucket/old.csv HTTP/1.1\" 204 - - "
                + "12 9 - \"-\" \"aws-cli/1.0\" -";

        ObjectNode r = parse(old);

        assertThat(r.path("version_id").isNull()).isTrue();
        assertThat(r.path("host_id").isNull()).isTrue();
        assertThat(r.path("source_region").isNull()).isTrue();
        assertThat(event(r).changeType()).isEqualTo(ChangeType.DELETE);
    }

    @Test
    void numericFieldsThatAreNotNumbersStayText() {
        ObjectNode r = parse(GET_VERSIONING.replace(" 113 - 7 - ", " 113 - 7x - "));

        JsonNode total = r.path("total_duration");
        assertThat(total.isTextual()).isTrue();
        assertThat(total.asText()).isEqualTo("7x");
    }

    @Test
    void malformedLinesDoNotSplit() {
        assertThat(S3AccessLogLine.parse(null)).isEmpty();
        assertThat(S3AccessLogLine.parse("")).isEmpty();
        assertThat(S3AccessLogLine.parse(OWNER + " dlap-sal-bucket [06/Feb/2026:00:00:38 +0000] 1.2.3.4 - id")).isEmpty();
        assertThat(S3AccessLogLine.parse(OWNER + " dlap-sal-bucket [06/Feb/2026:00:00:38 +0000 1.2.3.4 - id op key"))
                .as("unclosed bracket").isEmpty();
        assertThat(S3AccessLogLine.parse(OWNER + " dlap-sal-bucket [not a time] 1.2.3.4 - id op key")).isEmpty();
        assertThat(S3AccessLogLine.parse(OWNER + " b [06/Feb/2026:00:00:38 +0000] 1.2.3.4 - id op key \"GET /b"))
                .as("unclosed quote").isEmpty();
    }

    @Test
    void aDashTimeIsNullNotMalformed() {
        ObjectNode r = parse(OWNER + " dlap-sal-bucket - 1.2.3.4 - id REST.GET.OBJECT k");

        assertThat(r.path("request_time").isNull()).isTrue();
        assertThat(r.path("request_uri").isNull()).isTrue();
    }

    @Test
    void timeZonesOtherThanUtcBecomeUtcInstants() {
        assertThat(S3AccessLogLine.instant("06/Feb/2026:01:00:38 +0100")).contains("2026-02-06T00:00:38Z");
        assertThat(S3AccessLogLine.instant("06/Xyz/2026:01:00:38 +0100")).isEmpty();
    }
}
