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
import java.util.List;
import java.util.Locale;

/**
 * Synthetic Azure activity log records for blob containers and role assignments (E2E-02): the
 * administrative-category JSON an activity-log export carries, and its normalized projection for the
 * replay seam (there is no Azure transport in the log layer). Subscriptions, accounts, callers and
 * addresses are invented (the addresses are the RFC 5737 documentation ranges).
 */
public final class AzureActivityScale {

    private static final int LANE_OP = 2;
    private static final int LANE_CONTAINER = 3;
    private static final int LANE_CALLER = 5;
    private static final int LANE_IP = 6;
    private static final int LANE_FAIL = 7;
    private static final int LANE_CORR = 8;
    private static final int LANE_ACCOUNT = 9;
    private static final int CONTAINERS = 24;
    private static final int ACCOUNTS = 4;

    private record Op(String name, String change, String sub) {
    }

    private static final List<Op> OPS = List.of(
            new Op("Microsoft.Storage/storageAccounts/blobServices/containers/read", "ACCESS", "container"),
            new Op("Microsoft.Storage/storageAccounts/blobServices/containers/write", "UPDATE", "container"),
            new Op("Microsoft.Storage/storageAccounts/blobServices/containers/delete", "DELETE", "container"),
            new Op("Microsoft.Storage/storageAccounts/read", "ACCESS", "account"),
            new Op("Microsoft.Storage/storageAccounts/listKeys/action", "PERMISSION", "account"),
            new Op("Microsoft.Authorization/roleAssignments/write", "PERMISSION", "container"));
    /** Per mille weights of {@link #OPS}. */
    private static final int[] WEIGHTS = {400, 300, 30, 150, 70, 50};
    private static final List<String> CALLERS = List.of("etl", "analyst", "ml", "ingest", "report", "backup");

    private final long seed;
    private final ScaleWindow window;
    private final String subscriptionId;

    /**
     * Creates a generator.
     *
     * @param window         the seed, count and event-time window
     * @param subscriptionId the subscription GUID
     */
    public AzureActivityScale(ScaleWindow window, String subscriptionId) {
        if (subscriptionId == null
                || !subscriptionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("subscriptionId must be a lower case GUID");
        }
        this.seed = window.seed();
        this.window = window;
        this.subscriptionId = subscriptionId;
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
     * The correlation id of a record; it is the request id of the normalized projection.
     *
     * @param index the record index
     * @return a version 4 UUID text
     */
    public String correlationId(long index) {
        return ScaleRandom.uuid(seed, index, LANE_CORR);
    }

    /**
     * The raw activity record.
     *
     * @param index the record index
     * @return JSON text
     */
    public String record(long index) {
        return normalized(index).rawJson();
    }

    /**
     * The normalized projection of a record, which carries the raw record.
     *
     * @param index the record index, 0 to {@code count - 1}
     * @return the event
     */
    public NormalizedEvent normalized(long index) {
        Instant time = window.eventTime(index);
        Op op = OPS.get(ScaleRandom.weighted(seed, index, LANE_OP, WEIGHTS));
        String account = "dlapscale" + (1 + ScaleRandom.below(seed, index, LANE_ACCOUNT, ACCOUNTS));
        String container = String.format("container%02d", ScaleRandom.below(seed, index, LANE_CONTAINER, CONTAINERS));
        String caller = CALLERS.get(ScaleRandom.below(seed, index, LANE_CALLER, CALLERS.size()));
        String upn = caller + "@dlapscale.onmicrosoft.com";
        String objectId = ScaleRandom.uuid(seed, 0, 40 + CALLERS.indexOf(caller));
        String ip = "192.0.2." + (1 + ScaleRandom.below(seed, index, LANE_IP, 250));
        boolean failed = ScaleRandom.below(seed, index, LANE_FAIL, 100) == 0;
        String correlation = correlationId(index);
        String resourceGroup = "rg-dlap-scale";
        String accountId = "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                + "/providers/Microsoft.Storage/storageAccounts/" + account;
        boolean onContainer = "container".equals(op.sub());
        String resourceId = onContainer ? accountId + "/blobServices/default/containers/" + container : accountId;

        StringBuilder raw = new StringBuilder(900);
        raw.append("{\"time\":\"").append(time).append("\",\"resourceId\":");
        JsonText.append(raw, resourceId);
        raw.append(",\"operationName\":\"").append(op.name()).append("\",\"category\":\"Administrative\",");
        raw.append("\"resultType\":\"").append(failed ? "Failure" : "Success").append("\",\"resultSignature\":\"")
                .append(failed ? "Forbidden." : "Succeeded.").append("\",\"durationMs\":")
                .append(5 + ScaleRandom.below(seed, index, 11, 400)).append(",\"callerIpAddress\":\"").append(ip)
                .append("\",\"correlationId\":\"").append(correlation).append("\",");
        raw.append("\"identity\":{\"authorization\":{\"scope\":");
        JsonText.append(raw, resourceId);
        raw.append(",\"action\":\"").append(op.name()).append("\"},\"claims\":{\"upn\":\"").append(upn)
                .append("\",\"http://schemas.microsoft.com/identity/claims/objectidentifier\":\"").append(objectId)
                .append("\"}},\"level\":\"").append(failed ? "Error" : "Information").append("\",\"location\":\"eastus\",");
        raw.append("\"properties\":{\"statusCode\":\"").append(failed ? "Forbidden" : "OK")
                .append("\",\"serviceRequestId\":\"").append(ScaleRandom.uuid(seed, index, 12))
                .append("\",\"eventCategory\":\"Administrative\"}}");

        String host = "https://" + account + ".blob.core.windows.net";
        String targetId = onContainer ? host + "/" + container : host;
        String kind = onContainer ? "Microsoft.Storage/storageAccounts/blobServices/containers"
                : "Microsoft.Storage/storageAccounts";
        return new NormalizedEvent("AZURE", "Microsoft.Storage", op.name(), op.change(),
                failed ? "FAILURE" : "SUCCESS", time, objectId, "USER", upn, subscriptionId, kind, targetId,
                onContainer ? container : account, resourceGroup, "eastus", ip,
                "Azure-Storage/12.19.0 (scale " + caller.toUpperCase(Locale.ROOT) + ")", correlation, raw.toString());
    }
}
