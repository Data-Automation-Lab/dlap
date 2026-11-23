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

import org.testcontainers.utility.DockerImageName;

/**
 * The LocalStack image of the AWS transport tests, pinned by digest (Q-667). The tag
 * {@code localstack/localstack:3.4} is a multi-architecture image; the digest below is the digest of its
 * manifest list, so a pull gets the same bytes on amd64 and arm64 and a retagged or removed tag cannot
 * change what the tests run against.
 *
 * <p>To pre-pull it on a machine or a CI runner (once, before the tests; Testcontainers then finds it
 * locally and does not contact the registry):
 * <pre>
 * docker pull localstack/localstack@sha256:54fcf172f6ff70909e1e26652c3bb4587282890aff0d02c20aa7695469476ac0
 * </pre>
 * To move to another release, pull the new tag, read its digest with
 * {@code docker buildx imagetools inspect localstack/localstack:TAG}, and change {@link #DIGEST} and the
 * tag named above in the same commit. If the registry cannot be reached anonymously, the S3 and STS
 * calls these tests make work unchanged against {@code motoserver/moto} (pin that by digest the same way).
 */
final class LocalStackImage {

    /** The release the digest was read from; documentation only, the digest decides. */
    static final String TAG = "3.4";

    /** Digest of the manifest list of {@code localstack/localstack:3.4}. */
    static final String DIGEST = "sha256:54fcf172f6ff70909e1e26652c3bb4587282890aff0d02c20aa7695469476ac0";

    /** The image reference the containers start. */
    static final String REFERENCE = "localstack/localstack@" + DIGEST;

    private LocalStackImage() {
    }

    /**
     * The image to start.
     *
     * @return the digest-pinned image name
     */
    static DockerImageName name() {
        return DockerImageName.parse(REFERENCE).asCompatibleSubstituteFor("localstack/localstack");
    }
}
