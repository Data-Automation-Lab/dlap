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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dlap.adminservice.audit.AuditProperties;
import com.dlap.adminservice.audit.AuditTestKeys;

/** The filesystem sink, the sink factory and the object names. */
class FileSystemCheckpointSinkTest {

    @TempDir
    Path dir;

    private final KeyPair pair = AuditTestKeys.newPair();

    @Test
    void writesSignatureAndPayloadOnceAndReadOnly() throws Exception {
        FileSystemCheckpointSink sink = new FileSystemCheckpointSink(dir);
        sink.preflight();
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "retire.delete", 9);
        String location = sink.export(cp);
        Path payload = dir.resolve("retire.delete/00000000000000000009.json");
        Path sig = dir.resolve("retire.delete/00000000000000000009.sig");
        assertThat(location).isEqualTo(payload.toUri().toString());
        assertThat(Files.readAllBytes(payload)).isEqualTo(cp.payloadBytes());
        assertThat(Files.readAllBytes(sig)).isEqualTo(cp.signatureBytes());
        assertThat(Files.isWritable(payload)).isFalse();

        assertThat(sink.export(cp)).as("a retry with the same bytes succeeds").isEqualTo(location);
        SignedCheckpoint other = AuditTestKeys.signed(AuditTestKeys.newPair(), "retire.delete", 9);
        assertThatThrownBy(() -> sink.export(other)).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("already exists with other content");
        // Let the temporary directory be removed on every platform.
        assertThat(payload.toFile().setWritable(true) && sig.toFile().setWritable(true)).isTrue();
    }

    @Test
    void preflightNeedsAWritableDirectory() {
        assertThatThrownBy(() -> new FileSystemCheckpointSink(dir.resolve("missing")).preflight())
                .isInstanceOf(CheckpointExportException.class);
        assertThat(new FileSystemCheckpointSink(dir).name()).isEqualTo("filesystem");
    }

    @Test
    void namesCarryThePrefixWithoutDuplicateSlashes() {
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "admin.access", 1);
        assertThat(cp.payloadName("/a/b/")).isEqualTo("a/b/admin.access/00000000000000000001.json");
        assertThat(cp.signatureName("a")).isEqualTo("a/admin.access/00000000000000000001.sig");
        assertThat(cp.payloadName("")).isEqualTo("admin.access/00000000000000000001.json");
        assertThat(cp.payloadName(null)).isEqualTo("admin.access/00000000000000000001.json");
    }

    @Test
    void theFactoryBuildsTheConfiguredSink() {
        AuditProperties.Export export = new AuditProperties().getExport();
        assertThat(CheckpointSinks.create(export, n -> null, java.time.Clock.systemUTC())).isNull();
        export.setSink(AuditProperties.SinkType.FILESYSTEM);
        assertThatThrownBy(() -> CheckpointSinks.create(export, n -> null, java.time.Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        export.getFilesystem().setDirectory(dir.toString());
        assertThat(CheckpointSinks.create(export, n -> null, java.time.Clock.systemUTC())).isInstanceOf(FileSystemCheckpointSink.class);
        export.setSink(AuditProperties.SinkType.S3);
        export.getS3().setBucket("b-audit");
        export.getS3().setRegion("us-east-1");
        export.getS3().setRetentionDays(1);
        assertThat(CheckpointSinks.create(export, Map.<String, String>of()::get, java.time.Clock.systemUTC()).name()).isEqualTo("s3");
        export.setSink(AuditProperties.SinkType.GCS);
        export.getGcs().setBucket("g-audit");
        assertThat(CheckpointSinks.create(export, n -> null, java.time.Clock.systemUTC()).name()).isEqualTo("gcs");
        export.setSink(AuditProperties.SinkType.AZURE);
        export.getAzure().setAccountUrl("https://acct.blob.core.windows.net");
        export.getAzure().setContainer("audit");
        export.getAzure().setRetentionDays(1);
        assertThat(CheckpointSinks.create(export, n -> null, java.time.Clock.systemUTC()).name()).isEqualTo("azure");
        assertThat(export.getTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void httpExcerptsAreShortAndFlat() {
        assertThat(HttpCalls.excerpt(null)).isEmpty();
        assertThat(HttpCalls.excerpt("a\n  b".getBytes())).isEqualTo("a b");
        assertThat(HttpCalls.excerpt("x".repeat(400).getBytes())).hasSize(303).endsWith("...");
    }
}
