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

package com.dlap.logingest.parse.s3accesslog;

import com.dlap.logingest.parse.core.VersionRange;

/**
 * Names of the Amazon S3 server access log family (PARSE-02).
 */
public final class S3AccessLogFormat {

    /** Directory of the family mapping and fixtures. */
    public static final String FAMILY = "s3accesslog";

    /** Source kind of every record of the family. */
    public static final String SOURCE_KIND = "AWS_S3_SERVER_ACCESS_LOG";

    /** One record as structured JSON, as CloudWatch Logs delivery stores it. */
    public static final String CWL_JSON = "cwl-json";

    /** The record schema of {@code schema_version_id} {@code V_1_0}. */
    public static final String V_1_0 = "V_1_0";

    /** Range the parser reads. */
    public static final VersionRange RANGE = VersionRange.named(V_1_0);

    /** Parser id in {@code parser_version}. */
    public static final String PARSER_ID = "s3-access-log";

    /** Parser semantic version. */
    public static final String PARSER_SEMVER = "1.1.0";

    /**
     * Prefix of the operations S3 performs itself: lifecycle actions and checksum jobs
     * ({@code S3.action.resource_type}, LogFormat page).
     */
    public static final String SYSTEM_OPERATION_PREFIX = "S3.";

    /**
     * The lifecycle operation that adds a delete marker in front of the current version
     * (lifecycle-and-other-bucket-config, "S3 Lifecycle and logging").
     */
    public static final String CREATE_DELETE_MARKER_OPERATION = "S3.CREATE.DELETEMARKER";

    /** Provider resource kind of an object target. */
    public static final String S3_OBJECT_KIND = "AWS::S3::Object";

    /** Provider resource kind of a bucket target. */
    public static final String S3_BUCKET_KIND = "AWS::S3::Bucket";

    private S3AccessLogFormat() {
    }
}
