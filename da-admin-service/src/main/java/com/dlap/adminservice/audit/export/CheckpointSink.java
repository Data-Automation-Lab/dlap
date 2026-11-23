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

package com.dlap.adminservice.audit.export;

/**
 * Where signed checkpoints are copied so that a database administrator cannot change them
 * (docs/specs/audit-log.md, section "Export"). The cloud implementations live under
 * {@code audit/providers/<cloud>} and talk to the provider's REST API.
 */
public interface CheckpointSink {

    /** @return the sink name stored with each exported checkpoint, for example {@code s3} */
    String name();

    /**
     * Checks that the target is write-once storage that is ready: for example that the S3 bucket
     * has Object Lock enabled. Called before each export run.
     *
     * @throws CheckpointExportException when the target is missing or not write-once
     */
    void preflight();

    /**
     * Writes the signature object and then the payload object. Never overwrites: when an object
     * already exists with the same bytes the call succeeds (a retry after a lost answer), when it
     * exists with other bytes the call fails.
     *
     * @param checkpoint the signed checkpoint
     * @return the location of the payload object, for example {@code s3://bucket/key#versionId}
     * @throws CheckpointExportException when the export fails
     */
    String export(SignedCheckpoint checkpoint);
}
