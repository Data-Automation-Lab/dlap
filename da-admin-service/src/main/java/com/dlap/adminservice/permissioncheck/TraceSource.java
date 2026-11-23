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

package com.dlap.adminservice.permissioncheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Where the committed traces are read from. The build copies
 * {@code deployment/iam/<cloud>/{actor,reader}/permissions.yaml} into the jar under {@code iam/}
 * (da-admin-service {@code processResources}); {@code dlap.permission-check.trace-dir} points at a
 * checkout of {@code deployment/iam} instead.
 */
public final class TraceSource {

    private static final Pattern NAME = Pattern.compile("[a-z]{2,16}");

    private final Path directory;

    /**
     * Creates the source.
     *
     * @param directory a directory laid out like {@code deployment/iam}, or {@code null} for the jar copy
     */
    public TraceSource(Path directory) {
        this.directory = directory;
    }

    /**
     * Loads one cloud's Actor trace with its Reader trace.
     *
     * @param cloud the cloud, for example {@code aws}
     * @return the trace
     * @throws IllegalArgumentException when the cloud has no Actor trace
     */
    public ActorTrace load(String cloud) {
        if (cloud == null || !NAME.matcher(cloud).matches()) {
            throw new IllegalArgumentException("not a cloud name: " + cloud);
        }
        String actor = read(cloud + "/actor/permissions.yaml");
        if (actor == null) {
            throw new IllegalArgumentException("no Actor trace for " + cloud);
        }
        return ActorTrace.parse(actor, read(cloud + "/reader/permissions.yaml"));
    }

    private String read(String relative) {
        try {
            if (directory != null) {
                Path file = directory.resolve(relative);
                return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            }
            try (InputStream in = TraceSource.class.getClassLoader().getResourceAsStream("iam/" + relative)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the trace " + relative, e);
        }
    }
}
