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

package com.dlap.logingest.parse.cloudtrail;

/**
 * Names of the AWS CloudTrail family (PARSE-02).
 */
public final class CloudTrailFormat {

    /** Directory of the family mapping and fixtures. */
    public static final String FAMILY = "cloudtrail";

    /** Source kind of every record of the family. */
    public static final String SOURCE_KIND = "AWS_CLOUDTRAIL";

    /** A trail file in S3, {@code {"Records":[...]}}. */
    public static final String S3_FILE = "s3-file";

    /** Parser id in {@code parser_version}. */
    public static final String PARSER_ID = "cloudtrail-record";

    /** Parser semantic version. */
    public static final String PARSER_SEMVER = "1.1.0";

    /** Event source of the actions S3 lifecycle rules perform (filtering-data-events, example 4). */
    public static final String S3_LIFECYCLE_SERVICE = "s3lifecycle.amazonaws.com";

    /** Event source of S3 API calls. */
    public static final String S3_SERVICE = "s3.amazonaws.com";

    /** Provider resource kind of an object target. */
    public static final String S3_OBJECT_KIND = "AWS::S3::Object";

    /** Provider resource kind of a bucket target. */
    public static final String S3_BUCKET_KIND = "AWS::S3::Bucket";

    /**
     * {@code responseElements} member of an S3 {@code DeleteObject} response that says the call added
     * (or removed) a delete marker: the {@code x-amz-delete-marker} response header of the S3 API.
     * Whether CloudTrail carries it there for a data event is [unverified] until a live capture.
     */
    public static final String DELETE_MARKER_HEADER = "x-amz-delete-marker";

    /** {@code responseElements} member with the version id S3 returned ({@code x-amz-version-id}). */
    public static final String VERSION_ID_HEADER = "x-amz-version-id";

    private CloudTrailFormat() {
    }
}
