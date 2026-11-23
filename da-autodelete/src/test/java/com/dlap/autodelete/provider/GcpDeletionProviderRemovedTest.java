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

package com.dlap.autodelete.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Q-635: the uncalled GcpDeletionProvider (a provider SDK import outside providers/, CLAUDE.md section 9)
 * and its interface are gone, and nothing in the module refers to them.
 */
class GcpDeletionProviderRemovedTest {

    @Test
    void theLegacyGcpDeletionProviderAndItsInterfaceNoLongerExist() {
        assertThatThrownBy(() -> Class.forName("com.dlap.autodelete.provider.GcpDeletionProvider"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.dlap.autodelete.provider.IGcpDeletionProvider"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void nothingInTheModuleSourcesReferencesThem() throws IOException {
        Path moduleSources = Path.of("src");
        try (Stream<Path> files = Files.walk(moduleSources)) {
            assertThat(files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".properties"))
                    .filter(p -> !p.getFileName().toString().equals("GcpDeletionProviderRemovedTest.java"))
                    .filter(p -> {
                        try {
                            String text = Files.readString(p);
                            return text.contains("GcpDeletionProvider") || text.contains("autodelete.provider.");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).map(Path::toString).toList())
                    .as("files that still mention the removed provider").isEmpty();
        }
    }
}
