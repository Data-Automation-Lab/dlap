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

package com.dlap.autodelete.providers.aws;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds an S3 client for one bucket's region. The default implementation asks
 * {@code GetBucketLocation} with the default credentials chain; tests pass a stub.
 */
@FunctionalInterface
public interface S3ClientFactory {

    /**
     * A client for the bucket's region; the caller closes it.
     *
     * @param bucket the bucket
     * @return the client
     */
    S3Client forBucket(String bucket);
}
