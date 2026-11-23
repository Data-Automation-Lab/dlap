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

package com.dlap.common.audit;

import java.util.regex.Pattern;

/**
 * The audit streams seeded by {@code 02-create-common-schema.sql} (docs/specs/audit-log.md,
 * section "Streams"). Each stream has one writer role; a new stream needs a new row in
 * {@code dlap_common.audit_streams}, added by a {@code dlap_common} delta.
 */
public final class AuditStreams {

    /** Delete path: requests, executions, restores. Writer {@code da_autodelete_user} (RET-02). */
    public static final String RETIRE_DELETE = "retire.delete";

    /** Archive path: requests, executions, restores. Writer {@code da_autoarchival_user} (RET-03). */
    public static final String RETIRE_ARCHIVE = "retire.archive";

    /** Approval requests and decisions. Writer {@code da_policyengine_user} (APPR-01). */
    public static final String GOVERN_APPROVAL = "govern.approval";

    /** Policy versions and changes. Writer {@code da_policyengine_user} (AUD-02a). */
    public static final String GOVERN_POLICY = "govern.policy";

    /** Role changes and identity-provider events. Writer {@code da_admin_user} (AUD-02b). */
    public static final String ADMIN_ACCESS = "admin.access";

    /** Report and CSV exports. Writer {@code da_reporting_user} (AUD-02b). */
    public static final String REPORT_EXPORT = "report.export";

    /** The stream id rule, also a CHECK constraint on {@code audit_streams.stream_id}. */
    public static final Pattern STREAM_ID = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    /** Maximum length of a stream id. */
    public static final int MAX_STREAM_ID_LENGTH = 64;

    private AuditStreams() {
    }

    /**
     * Checks a stream id.
     *
     * @param streamId the candidate
     * @return {@code streamId}
     * @throws IllegalArgumentException when it does not follow {@link #STREAM_ID}
     */
    public static String requireValid(String streamId) {
        if (streamId == null || streamId.length() > MAX_STREAM_ID_LENGTH
                || !STREAM_ID.matcher(streamId).matches()) {
            throw new IllegalArgumentException("stream id must match " + STREAM_ID.pattern()
                    + " and have at most " + MAX_STREAM_ID_LENGTH + " characters");
        }
        return streamId;
    }
}
