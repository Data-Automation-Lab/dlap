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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The shipped gcpaudit mapping and a registry built the way GcpAuditConfiguration builds it. Test support. */
final class GcpAuditTestSupport {

    private GcpAuditTestSupport() {
    }

    /** The shipped mapping, {@code dlap/log-formats/gcpaudit/mapping.yaml}. */
    static MappingSpec mapping() {
        return AbstractParserContractTest.classpathMapping(GcpAuditFormat.FAMILY);
    }

    /** The three parsers of the family. */
    static List<GcpAuditParser> parsers(MappingSpec spec) {
        GcpAuditConfiguration c = new GcpAuditConfiguration();
        MappingSpecs specs = new MappingSpecs(List.of(spec));
        return List.of(c.gcpAuditApiProtoParser(specs), c.gcpAuditJsonParser(specs), c.gcpAuditBqSinkRowParser(specs));
    }

    private static FixtureCatalog allCells;

    /**
     * The corpus with every {@code gcpaudit} cell marked supported, so the contract test runs the cases of
     * cells the shipped catalog does not claim yet (the BigQuery v2 cells, held back only by two coverage-spec
     * variants no v2 entry can show). The copy lives in a temporary directory; the shipped files are unchanged.
     */
    static synchronized FixtureCatalog allCellsCorpus() {
        if (allCells != null) {
            return allCells;
        }
        Path source = FixtureCatalog.fromClasspath().root();
        try {
            Path target = Files.createTempDirectory("gcpaudit-all-cells").resolve(FixtureCatalog.RESOURCE_ROOT);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : walk.toList()) {
                    Path dest = target.resolve(source.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(p, dest);
                    }
                }
            }
            Path catalog = target.resolve(GcpAuditFormat.FAMILY).resolve(FixtureCatalog.CATALOG_FILE);
            String text = Files.readString(catalog, StandardCharsets.UTF_8).replace("supported: false", "supported: true");
            Files.writeString(catalog, text, StandardCharsets.UTF_8);
            allCells = FixtureCatalog.load(target);
            return allCells;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The registry: the three parsers, the mapping's declarative detectors and the sink-row detector. */
    static DefaultParserRegistry registry() {
        MappingSpec spec = mapping();
        List<VersionDetector> detectors = new ArrayList<>(new MappingSpecs(List.of(spec)).detectors());
        detectors.add(new GcpAuditConfiguration().gcpAuditBqSinkRowDetector());
        return new DefaultParserRegistry(parsers(spec), detectors);
    }
}
