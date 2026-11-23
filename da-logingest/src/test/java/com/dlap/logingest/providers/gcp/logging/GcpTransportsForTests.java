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

package com.dlap.logingest.providers.gcp.logging;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.TransportCatalog;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportRegistry;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the three GCP transports and their stream contexts for tests, without any GCP client. */
public final class GcpTransportsForTests {

    /** The fixed test time. */
    public static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    private GcpTransportsForTests() {
    }

    /**
     * The transport catalog the bindings validator sees in production, built from the real transports.
     *
     * @return the catalog of LOGGING_API, GCS_SINK and BQ_SINK
     */
    public static TransportCatalog catalog() {
        return new TransportRegistry(List.of(
                new LoggingApiTransport((c, p, f, t, s) -> new LoggingApiTransport.Page(List.of(), ""),
                        new RequestRateLimiter(60, Duration.ofMinutes(1), Clock.systemUTC(), d -> { })),
                new GcsSinkTransport(new GcsSinkTransport.ShardStore() {
                    @Override
                    public List<GcsSinkTransport.ShardObject> list(StreamContext context, String bucket, String prefix) {
                        return List.of();
                    }

                    @Override
                    public byte[] read(StreamContext context, String bucket, GcsSinkTransport.ShardObject object) {
                        return new byte[0];
                    }
                }),
                new BqSinkTransport(new BqSinkTransport.SinkTables() {
                    @Override
                    public List<String> list(StreamContext context, String project, String dataset) {
                        return List.of();
                    }

                    @Override
                    public java.util.Iterator<BqSinkTransport.Page> query(StreamContext context, String project, String sql,
                                                                         Instant from, Instant to, int pageSize) {
                        return java.util.Collections.emptyIterator();
                    }
                })));
    }

    /**
     * The records of a fixture case, every input file in order: the elements of a JSON array file, or
     * each value of a file of one or more JSON objects (one per line or pretty printed).
     *
     * @param c the case
     * @return the records as JSON text
     */
    static List<String> records(com.dlap.fixtures.FixtureCase c) {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> out = new java.util.ArrayList<>();
        try {
            for (java.nio.file.Path f : c.inputFiles()) {
                String t = java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).strip();
                try (com.fasterxml.jackson.databind.MappingIterator<com.fasterxml.jackson.databind.JsonNode> it =
                             json.readerFor(com.fasterxml.jackson.databind.JsonNode.class).readValues(t)) {
                    while (it.hasNext()) {
                        com.fasterxml.jackson.databind.JsonNode n = it.next();
                        if (n.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode e : n) {
                                out.add(json.writeValueAsString(e));
                            }
                        } else {
                            out.add(json.writeValueAsString(n));
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    /**
     * A stream context of a gcpaudit binding.
     *
     * @param transport the transport id
     * @param envelope  the envelope
     * @param project   the binding's {@code scope.project-id}, or {@code null}
     * @param location  the location keys
     * @param done      object keys already processed, as {@code key@generation}
     * @return the context
     */
    static StreamContext context(String transport, String envelope, String project, Map<String, String> location,
                                 Set<String> done) {
        BindingConfig parse = BindingConfig.auto("gcp-test", "GCP_CLOUD_AUDIT");
        if (project != null) {
            parse = parse.withAssetIdHints(AssetIdHints.builder().project(project).build());
        }
        EffectiveStream stream = new EffectiveStream("gcp-test", "s1", transport, envelope, location, parse,
                StreamFilters.NONE, Duration.ofMinutes(10), Duration.ofMinutes(5), null, null, false, false, false);
        return new StreamContext(stream, "GCP_CLOUD_AUDIT", IngestMode.STREAM, Clock.fixed(NOW, ZoneOffset.UTC),
                new DoneObjects(done));
    }

    /** A processed-object store backed by a set. */
    static final class DoneObjects implements ProcessedObjectStore {
        private final Set<String> done;

        DoneObjects(Set<String> done) {
            this.done = new HashSet<>(done);
        }

        @Override
        public boolean isDone(String bindingId, String streamId, String objectKey, String etag) {
            return done.contains(objectKey + "@" + etag);
        }

        @Override
        public void markDone(String bindingId, String streamId, String objectKey, String etag, long records,
                             long quarantined) {
            done.add(objectKey + "@" + etag);
        }

        @Override
        public void markFailed(String bindingId, String streamId, String objectKey, String etag) {
        }

        @Override
        public int attempts(String bindingId, String streamId, String objectKey, String etag) {
            return 0;
        }
    }
}
