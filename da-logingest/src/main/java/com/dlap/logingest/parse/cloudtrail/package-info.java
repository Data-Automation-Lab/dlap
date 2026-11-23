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
 * The {@code AWS_CLOUDTRAIL} family (PARSE-02): CloudTrail records from trail files.
 *
 * <p>The mapping ({@code dlap/log-formats/cloudtrail/mapping.yaml}) carries the fields, the
 * classification table and the principal types; {@link com.dlap.logingest.parse.cloudtrail.CloudTrailParser}
 * adds the S3 target and the principal type of a record that names its invoking service but no
 * identity type. An S3 lifecycle expiration ({@code eventSource s3lifecycle.amazonaws.com}) is a
 * {@code DELETE} of the object with a {@code SYSTEM} principal, so the router puts it on
 * {@code dlap.discovery.asset.deleted}, where the delete proof of da-autodelete matches it.
 *
 * <p>This package is JSON only (plan rule R10).
 */
package com.dlap.logingest.parse.cloudtrail;
