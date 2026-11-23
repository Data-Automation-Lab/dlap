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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The normalized form of one generated GCP or Azure record: the fields of da-logingest's
 * {@code IngestRequestDTO}, which the replay seeder and the REST channel accept. The log layer has no
 * offline raw transport for GCP audit logs or Azure activity logs (the GCS sink cannot be pointed at
 * an emulator and no Azure transport exists), so these two clouds enter the scale run at the
 * normalized seam. The raw record is carried as {@code rawEvent}, as the fixtures in
 * {@code e2e-tests/fixtures/replay} do.
 *
 * @param provider     {@code GCP} or {@code AZURE}
 * @param service      the source service
 * @param operation    the cloud's operation name
 * @param changeType   CREATE, UPDATE, DELETE, ACCESS or PERMISSION
 * @param result       SUCCESS or FAILURE
 * @param time         the event time
 * @param principalId  the caller's stable id
 * @param principalType USER, SERVICE_ACCOUNT or similar
 * @param principalName the caller's display name
 * @param account      the project or subscription
 * @param kind         the target resource kind
 * @param targetId     the target resource id (a canonical URI)
 * @param targetName   the target resource name
 * @param project      the target project, resource group or account
 * @param region       the target region
 * @param callerIp     the caller address
 * @param userAgent    the caller agent
 * @param requestId    the request id, which is also the dedup key of the DTO path
 * @param rawJson      the raw provider record, as JSON text
 */
public record NormalizedEvent(String provider, String service, String operation, String changeType, String result,
                              Instant time, String principalId, String principalType, String principalName,
                              String account, String kind, String targetId, String targetName, String project,
                              String region, String callerIp, String userAgent, String requestId, String rawJson) {

    /**
     * The DTO as one JSON object.
     *
     * @return JSON text with the {@code IngestRequestDTO} field names
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(rawJson.length() + 700);
        sb.append('{');
        JsonText.member(sb, "sourceProvider", provider);
        sb.append(',');
        JsonText.member(sb, "sourceService", service);
        sb.append(',');
        JsonText.member(sb, "operation", operation);
        sb.append(',');
        JsonText.member(sb, "changeType", changeType);
        sb.append(',');
        JsonText.member(sb, "resultStatus", result);
        sb.append(',');
        JsonText.member(sb, "eventTime", time.toString());
        sb.append(',');
        JsonText.member(sb, "principalId", principalId);
        sb.append(',');
        JsonText.member(sb, "principalType", principalType);
        sb.append(',');
        JsonText.member(sb, "principalDisplayName", principalName);
        sb.append(',');
        JsonText.member(sb, "principalAccountId", account);
        sb.append(',');
        JsonText.member(sb, "targetResourceKind", kind);
        sb.append(',');
        JsonText.member(sb, "targetResourceId", targetId);
        sb.append(',');
        JsonText.member(sb, "targetResourceName", targetName);
        sb.append(',');
        JsonText.member(sb, "targetProject", project);
        sb.append(',');
        JsonText.member(sb, "targetRegion", region);
        sb.append(',');
        JsonText.member(sb, "callerIp", callerIp);
        sb.append(',');
        JsonText.member(sb, "userAgent", userAgent);
        sb.append(',');
        JsonText.member(sb, "requestId", requestId);
        sb.append(",\"rawEvent\":").append(rawJson).append('}');
        return sb.toString();
    }

    /**
     * The {@code event_id} da-logingest stores for this record: SHA-256 of
     * {@code <provider>|<requestId>}, the id the DTO path computes when a request id is present.
     *
     * @return 64 lower case hex digits
     */
    public String eventId() {
        return eventId(provider, requestId);
    }

    /**
     * The stored id of a provider and request id.
     *
     * @param provider  the provider text
     * @param requestId the request id
     * @return 64 lower case hex digits
     */
    public static String eventId(String provider, String requestId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((provider + "|" + requestId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandated by the JDK", e);
        }
    }
}
