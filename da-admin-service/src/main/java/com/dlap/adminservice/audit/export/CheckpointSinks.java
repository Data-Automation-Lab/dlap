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

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;

import com.dlap.adminservice.audit.AuditProperties;
import com.dlap.adminservice.audit.providers.aws.AwsCredentialsSource;
import com.dlap.adminservice.audit.providers.aws.S3ObjectLockCheckpointSink;
import com.dlap.adminservice.audit.providers.azure.AzureImmutableBlobCheckpointSink;
import com.dlap.adminservice.audit.providers.gcp.GcsBucketLockCheckpointSink;

/** Builds the configured {@link CheckpointSink} from {@code dlap.audit.export.*}. */
public final class CheckpointSinks {

    private CheckpointSinks() {
    }

    /**
     * The configured sink.
     *
     * @param export the export settings
     * @param env    reads environment variables (AWS credentials)
     * @param clock  the clock
     * @return the sink, or {@code null} for {@code NONE}
     * @throws IllegalArgumentException when the chosen sink's settings are incomplete
     */
    public static CheckpointSink create(AuditProperties.Export export, Function<String, String> env, Clock clock) {
        HttpCalls http = new HttpCalls(export.getTimeout());
        return switch (export.getSink()) {
            case NONE -> null;
            case FILESYSTEM -> {
                if (export.getFilesystem().getDirectory() == null || export.getFilesystem().getDirectory().isBlank()) {
                    throw new IllegalArgumentException("dlap.audit.export.filesystem.directory is required");
                }
                yield new FileSystemCheckpointSink(Path.of(export.getFilesystem().getDirectory()));
            }
            case S3 -> new S3ObjectLockCheckpointSink(export.getS3(), export.getPrefix(), http,
                    new AwsCredentialsSource(env, http, URI.create(export.getS3().getImdsEndpoint()), clock), clock);
            case GCS -> new GcsBucketLockCheckpointSink(export.getGcs(), export.getPrefix(), http, clock);
            case AZURE -> new AzureImmutableBlobCheckpointSink(export.getAzure(), export.getPrefix(), http, clock);
        };
    }
}
