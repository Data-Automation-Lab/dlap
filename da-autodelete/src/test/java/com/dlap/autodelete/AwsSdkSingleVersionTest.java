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

package com.dlap.autodelete;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * EXEC-01 (Q-722): the repository uses one AWS SDK version. The retire executors moved from 2.20.162 to the version
 * da-logingest and da-discovery use, so a mix of SDK lines cannot come back unnoticed. This reads the module build
 * files only; it needs no network.
 */
class AwsSdkSingleVersionTest {

    private static final Pattern ARTIFACT = Pattern.compile("software\\.amazon\\.awssdk:[a-z0-9-]+:(\\d+\\.\\d+\\.\\d+)");
    private static final Pattern VARIABLE = Pattern.compile("val awsSdkVersion = \"(\\d+\\.\\d+\\.\\d+)\"");

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("the repository root").isNotNull();
        return dir;
    }

    /** Q-722: every AWS SDK dependency of every module is on the same version. */
    @Test
    void everyModuleUsesOneAwsSdkVersion() throws IOException {
        Set<String> versions = new TreeSet<>();
        int seen = 0;
        try (Stream<Path> modules = Files.list(root())) {
            for (Path module : (Iterable<Path>) modules.filter(Files::isDirectory)::iterator) {
                Path build = module.resolve("build.gradle.kts");
                if (!Files.isRegularFile(build)) {
                    continue;
                }
                String text = Files.readString(build);
                for (Pattern p : new Pattern[] {ARTIFACT, VARIABLE}) {
                    Matcher m = p.matcher(text);
                    while (m.find()) {
                        versions.add(m.group(1));
                        seen++;
                    }
                }
            }
        }
        assertThat(seen).as("AWS SDK version declarations found").isGreaterThan(10);
        assertThat(versions).as("AWS SDK versions in the module build files").hasSize(1);
    }

    /** Q-722: the executors carry the STS module next to S3, so a role can be assumed. */
    @Test
    void theExecutorsDeclareStsOnTheSharedVersion() throws IOException {
        String version = Set.copyOf(versionsOf("da-logingest")).iterator().next();
        for (String module : new String[] {"da-autodelete", "da-autoarchival"}) {
            assertThat(Files.readString(root().resolve(module).resolve("build.gradle.kts")))
                    .as(module).contains("software.amazon.awssdk:sts:" + version)
                    .contains("software.amazon.awssdk:s3:" + version);
        }
    }

    private static Set<String> versionsOf(String module) throws IOException {
        Set<String> versions = new TreeSet<>();
        Matcher m = ARTIFACT.matcher(Files.readString(root().resolve(module).resolve("build.gradle.kts")));
        while (m.find()) {
            versions.add(m.group(1));
        }
        return versions;
    }
}
