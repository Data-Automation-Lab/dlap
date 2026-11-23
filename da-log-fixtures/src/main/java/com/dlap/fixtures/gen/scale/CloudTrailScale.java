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

package com.dlap.fixtures.gen.scale;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Synthetic CloudTrail S3 data events (E2E-02). Each record has the fields the {@code cloudtrail}
 * mapping reads: an assumed-role principal with its session issuer, the event source and name, the
 * region, the account, the request parameters that name the bucket and key, and the {@code eventID}
 * that becomes the native event key. About one in two hundred calls fails with {@code AccessDenied}.
 *
 * <p>The mix is read-heavy like a real data lake: mostly {@code GetObject}, then {@code PutObject},
 * {@code HeadObject}, {@code CopyObject} and a thin stream of {@code DeleteObject}. Buckets, roles and
 * callers come from small invented pools, so a million records touch a few dozen assets, the way object
 * events roll up to their bucket.
 */
public final class CloudTrailScale {

    private static final int LANE_ID = 0;
    private static final int LANE_OP = 2;
    private static final int LANE_BUCKET = 3;
    private static final int LANE_KEY = 4;
    private static final int LANE_ROLE = 5;
    private static final int LANE_IP = 6;
    private static final int LANE_DENY = 7;
    private static final int LANE_REQ = 8;
    private static final int LANE_AGENT = 9;
    private static final int LANE_SESSION = 10;
    private static final int LANE_KEY_PART = 24;

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final List<String> OPERATIONS =
            List.of("GetObject", "PutObject", "HeadObject", "CopyObject", "DeleteObject");
    /** Per mille weights of {@link #OPERATIONS}. */
    private static final int[] OPERATION_WEIGHTS = {620, 230, 70, 60, 20};
    private static final List<String> ROLES = List.of("dlap-scale-etl", "dlap-scale-analyst", "dlap-scale-ml",
            "dlap-scale-ingest", "dlap-scale-report", "dlap-scale-backup", "dlap-scale-audit", "dlap-scale-batch");
    private static final List<String> AGENTS = List.of("aws-sdk-java/2.25.30 Linux/6.1 OpenJDK_64-Bit_Server_VM/21",
            "Boto3/1.34.84 md/Botocore#1.34.84 ua/2.0 os/linux#6.1 lang/python#3.11",
            "aws-cli/2.15.30 Python/3.11.8 Linux/6.1 exe/x86_64", "aws-sdk-go-v2/1.26.0 os/linux lang/go#1.22");
    private static final int BUCKETS = 24;

    private final long seed;
    private final ScaleWindow window;
    private final String accountId;
    private final String region;

    /**
     * Creates a generator.
     *
     * @param window    the seed, count and event-time window
     * @param accountId the 12 digit account the events belong to
     * @param region    the region the events are in, for example {@code us-east-1}
     */
    public CloudTrailScale(ScaleWindow window, String accountId, String region) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new IllegalArgumentException("accountId must be 12 digits");
        }
        if (region == null || !region.matches("[a-z]{2}(-[a-z]+)+-\\d{1,2}")) {
            throw new IllegalArgumentException("region must be an AWS region name");
        }
        this.seed = window.seed();
        this.window = window;
        this.accountId = accountId;
        this.region = region;
    }

    /**
     * The window of this stream.
     *
     * @return the window
     */
    public ScaleWindow window() {
        return window;
    }

    /**
     * The account of this stream.
     *
     * @return the account id
     */
    public String accountId() {
        return accountId;
    }

    /**
     * The region of this stream.
     *
     * @return the region
     */
    public String region() {
        return region;
    }

    /**
     * The {@code eventID} of a record; the registry stores it as the native event id.
     *
     * @param index the record index
     * @return a version 4 UUID text
     */
    public String eventId(long index) {
        return ScaleRandom.uuid(seed, index, LANE_ID);
    }

    /**
     * The bucket a record touches.
     *
     * @param index the record index
     * @return the bucket name
     */
    public String bucket(long index) {
        return String.format("dlap-scale-%s-b%02d", accountId.substring(8),
                ScaleRandom.below(seed, index, LANE_BUCKET, BUCKETS));
    }

    /**
     * One CloudTrail record as JSON text.
     *
     * @param index the record index, 0 to {@code count - 1}
     * @return the record
     */
    public String record(long index) {
        Instant time = window.eventTime(index);
        int op = ScaleRandom.weighted(seed, index, LANE_OP, OPERATION_WEIGHTS);
        String name = OPERATIONS.get(op);
        boolean readOnly = op == 0 || op == 2;
        String bucket = bucket(index);
        String key = "raw/dt=" + String.format("%02d", 1 + ScaleRandom.below(seed, index, LANE_KEY, 28))
                + "/part-" + ScaleRandom.hex(seed, index, LANE_KEY_PART, 6).toLowerCase(Locale.ROOT) + ".parquet";
        int roleIndex = ScaleRandom.below(seed, index, LANE_ROLE, ROLES.size());
        String role = ROLES.get(roleIndex);
        String session = "sess-" + ScaleRandom.hex(seed, index, LANE_SESSION, 8).toLowerCase(Locale.ROOT);
        String ip = "203.0.113." + (1 + ScaleRandom.below(seed, index, LANE_IP, 250));
        boolean denied = ScaleRandom.below(seed, index, LANE_DENY, 200) == 0;
        String issuer = "arn:aws:iam::" + accountId + ":role/" + role;
        String roleId = "AROA" + ScaleRandom.hex(seed, 0, 30 + roleIndex, 16);

        StringBuilder sb = new StringBuilder(1200);
        sb.append("{\"eventVersion\":\"1.09\",\"userIdentity\":{\"type\":\"AssumedRole\",\"principalId\":\"")
                .append(roleId).append(':').append(session).append("\",\"arn\":\"arn:aws:sts::").append(accountId)
                .append(":assumed-role/").append(role).append('/').append(session).append("\",\"accountId\":\"")
                .append(accountId).append("\",\"accessKeyId\":\"ASIA").append(ScaleRandom.hex(seed, index, 11, 16))
                .append("\",\"sessionContext\":{\"sessionIssuer\":{\"type\":\"Role\",\"principalId\":\"")
                .append(roleId).append("\",\"arn\":\"").append(issuer).append("\",\"accountId\":\"").append(accountId)
                .append("\",\"userName\":\"").append(role).append("\"},\"attributes\":{\"creationDate\":\"")
                .append(SECONDS.format(time.minusSeconds(300))).append("\",\"mfaAuthenticated\":\"false\"}}},");
        sb.append("\"eventTime\":\"").append(SECONDS.format(time)).append("\",");
        sb.append("\"eventSource\":\"s3.amazonaws.com\",\"eventName\":\"").append(name).append("\",");
        sb.append("\"awsRegion\":\"").append(region).append("\",\"sourceIPAddress\":\"").append(ip).append("\",");
        sb.append("\"userAgent\":");
        JsonText.append(sb, AGENTS.get(ScaleRandom.below(seed, index, LANE_AGENT, AGENTS.size())));
        sb.append(',');
        if (denied) {
            sb.append("\"errorCode\":\"AccessDenied\",\"errorMessage\":\"Access Denied\",");
        }
        sb.append("\"requestParameters\":{\"bucketName\":\"").append(bucket).append("\",\"Host\":\"").append(bucket)
                .append(".s3.").append(region).append(".amazonaws.com\",\"key\":");
        JsonText.append(sb, key);
        sb.append("},\"responseElements\":null,\"additionalEventData\":{\"SignatureVersion\":\"SigV4\","
                + "\"CipherSuite\":\"TLS_AES_128_GCM_SHA256\",\"bytesTransferredIn\":0,\"AuthenticationMethod\":"
                + "\"AuthHeader\",\"x-amz-id-2\":\"").append(ScaleRandom.hex(seed, index, 12, 16)).append("\"},");
        sb.append("\"requestID\":\"").append(ScaleRandom.hex(seed, index, LANE_REQ, 16)).append("\",\"eventID\":\"")
                .append(eventId(index)).append("\",\"readOnly\":").append(readOnly)
                .append(",\"resources\":[{\"type\":\"AWS::S3::Object\",\"ARN\":\"arn:aws:s3:::").append(bucket)
                .append('/').append(key).append("\"},{\"accountId\":\"").append(accountId)
                .append("\",\"type\":\"AWS::S3::Bucket\",\"ARN\":\"arn:aws:s3:::").append(bucket).append("\"}],");
        sb.append("\"eventType\":\"AwsApiCall\",\"managementEvent\":false,\"recipientAccountId\":\"").append(accountId)
                .append("\",\"eventCategory\":\"Data\",\"tlsDetails\":{\"tlsVersion\":\"TLSv1.3\","
                        + "\"cipherSuite\":\"TLS_AES_128_GCM_SHA256\",\"clientProvidedHostHeader\":\"").append(bucket)
                .append(".s3.").append(region).append(".amazonaws.com\"}}");
        return sb.toString();
    }
}
