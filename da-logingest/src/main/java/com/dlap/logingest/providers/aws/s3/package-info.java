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

/**
 * The AWS S3 log transports (AWS-01, Q-640, Q-641): {@code S3_TRAIL_FILES} (CloudTrail trail files,
 * envelope {@code s3-file}) and {@code S3_SERVER_ACCESS_LOGS} (S3 server access log text objects,
 * split into the CloudWatch Logs field names and parsed as envelope {@code cwl-json}, decision D36).
 * Both feed {@code RegistryIngestService} through the stream runner with object checkpoints
 * ({@code processed_object}); they never parse, store or publish. Only {@link
 * com.dlap.logingest.providers.aws.s3.SdkS3LogStore} touches the AWS SDK, and only this package (with the legacy {@code source/aws}) may (CLAUDE.md section 5). Off by default
 * ({@code dlap.logingest.transports.aws.enabled}). Transports pull; there is no S3 event notification
 * transport (ADR-0023).
 */
package com.dlap.logingest.providers.aws.s3;
