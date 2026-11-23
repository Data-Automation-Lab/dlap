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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Cloud neutrality (CLAUDE.md section 5, plan rule R10) for da-admin-service: no provider SDK is
 * imported anywhere outside a {@code providers/<cloud>/} package, and the self-check's neutral core
 * ({@code permissioncheck/} outside {@code providers/}) names no provider endpoint and no provider
 * class.
 */
class ProviderCodePlacementTest {

    private static final Pattern SDK_IMPORT = Pattern.compile("^import (static )?(software\\.amazon|com\\.amazonaws"
            + "|com\\.google\\.cloud|com\\.google\\.api\\.services|com\\.google\\.api\\.gax|com\\.google\\.auth"
            + "|com\\.azure|com\\.microsoft\\.azure)\\.", Pattern.MULTILINE);

    private static final Pattern PROVIDER_PACKAGE = Pattern.compile(".*/providers/(aws|gcp|azure)/[^/]+\\.java$");

    private static final Pattern PROVIDER_NAMES = Pattern.compile(
            "amazonaws\\.com|googleapis\\.com|management\\.azure\\.com|metadata\\.google\\.internal|169\\.254\\.169\\.254"
                    + "|import com\\.dlap\\.adminservice\\.(permissioncheck|audit)\\.providers\\.");

    private static final Path MAIN = ActorRoleTraceabilityTest.repositoryRoot().resolve("da-admin-service/src/main/java");

    @Test
    void noProviderSdkIsImportedOutsideAProvidersPackage() {
        List<String> outside = new ArrayList<>();
        for (Path file : javaSources(MAIN)) {
            String path = file.toString().replace('\\', '/');
            if (SDK_IMPORT.matcher(read(file)).find() && !PROVIDER_PACKAGE.matcher(path).matches()) {
                outside.add(MAIN.relativize(file).toString());
            }
        }
        assertThat(outside).as("files outside providers/<cloud>/ that import a cloud SDK").isEmpty();
    }

    @Test
    void theNeutralCoreOfTheSelfCheckNamesNoProvider() {
        Path core = MAIN.resolve("com/dlap/adminservice/permissioncheck");
        List<String> named = new ArrayList<>();
        int files = 0;
        for (Path file : javaSources(core)) {
            if (file.toString().replace('\\', '/').contains("/providers/")) {
                continue;
            }
            files++;
            if (PROVIDER_NAMES.matcher(read(file)).find()) {
                named.add(core.relativize(file).toString());
            }
        }
        assertThat(files).as("core files checked").isGreaterThanOrEqualTo(7);
        assertThat(named).as("core files that name a provider endpoint or import provider code").isEmpty();
        assertThat(PROVIDER_NAMES.matcher("URI.create(\"https://iam.amazonaws.com/\")").find()).isTrue();
        assertThat(SDK_IMPORT.matcher("import software.amazon.awssdk.services.iam.IamClient;").find()).isTrue();
        assertThat(PROVIDER_PACKAGE.matcher("x/permissioncheck/providers/aws/P.java").matches()).isTrue();
        assertThat(PROVIDER_PACKAGE.matcher("x/permissioncheck/P.java").matches()).isFalse();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaSources(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
