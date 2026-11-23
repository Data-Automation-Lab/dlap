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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * Writes checkpoints to a local directory. For development, tests and a directory that a
 * write-once volume backs; a plain directory is not write-once storage, so production pilots use
 * a cloud sink (docs/specs/audit-log.md, section "Export"). Files are created, never replaced, and
 * made read-only.
 */
public final class FileSystemCheckpointSink implements CheckpointSink {

    private final Path directory;

    /**
     * Creates the sink.
     *
     * @param directory the target directory
     */
    public FileSystemCheckpointSink(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "filesystem";
    }

    @Override
    public void preflight() {
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new CheckpointExportException("checkpoint directory " + directory + " is not a writable directory");
        }
    }

    @Override
    public String export(SignedCheckpoint checkpoint) {
        Path sig = resolve(checkpoint.signatureName(""));
        Path payload = resolve(checkpoint.payloadName(""));
        writeOnce(sig, checkpoint.signatureBytes());
        writeOnce(payload, checkpoint.payloadBytes());
        return payload.toUri().toString();
    }

    private Path resolve(String name) {
        Path p = directory.resolve(name).normalize();
        if (!p.startsWith(directory)) {
            throw new CheckpointExportException("checkpoint name " + name + " leaves the directory");
        }
        return p;
    }

    private static void writeOnce(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            try {
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                if (!Arrays.equals(Files.readAllBytes(file), bytes)) {
                    throw new CheckpointExportException(file + " already exists with other content", e);
                }
                return;
            }
            if (!file.toFile().setReadOnly()) {
                throw new CheckpointExportException("could not make " + file + " read-only");
            }
        } catch (IOException e) {
            throw new CheckpointExportException("writing " + file + " failed: " + e.getMessage(), e);
        }
    }
}
