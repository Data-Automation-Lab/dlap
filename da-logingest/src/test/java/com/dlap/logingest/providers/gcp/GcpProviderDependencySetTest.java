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

package com.dlap.logingest.providers.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.storage.v1.ArrowSchema;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.BigQueryReadSettings;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;

/**
 * The GCP provider dependency set of DEPS-01 works on the runtime the convergence check aligns: the
 * Spring Boot 4.1 versions of protobuf, gRPC and Netty instead of the slightly older ones the Google
 * Cloud libraries BOM was built with. Nothing here touches the network: the clients are built with no
 * credentials and their channels are never used. The transports that use these libraries arrive with
 * GCP-01 and GCP-02.
 */
class GcpProviderDependencySetTest {

    private static final String PROJECT = "dlap-deps-test";

    /** Cloud Logging, BigQuery and Cloud Storage clients build and close on the aligned runtime. */
    @Test
    void theClientsBuildWithoutCredentialsOrNetwork() throws Exception {
        try (Logging logging = LoggingOptions.newBuilder()
                .setProjectId(PROJECT)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService()) {
            assertThat(logging.getOptions().getProjectId()).isEqualTo(PROJECT);
        }

        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setProjectId(PROJECT)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        assertThat(bigQuery.getOptions().getProjectId()).isEqualTo(PROJECT);

        try (Storage storage = StorageOptions.newBuilder()
                .setProjectId(PROJECT)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService()) {
            assertThat(storage.getOptions().getProjectId()).isEqualTo(PROJECT);
        }
    }

    /** The BigQuery Storage Read API client builds its gRPC stub; the channel never connects. */
    @Test
    void theStorageReadClientBuildsItsGrpcStub() throws Exception {
        BigQueryReadSettings settings = BigQueryReadSettings.newBuilder()
                .setCredentialsProvider(NoCredentialsProvider.create())
                .setEndpoint("localhost:1")
                .build();
        BigQueryReadClient client = BigQueryReadClient.create(settings);
        try {
            assertThat(client.getSettings().getEndpoint()).isEqualTo("localhost:1");
        } finally {
            client.shutdownNow();
            assertThat(client.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Generated messages of the Logging v2 and Storage Read v1 APIs initialise their descriptors on the
     * aligned protobuf runtime (protobuf 4 validates the gencode version there) and round-trip through
     * the binary and the JSON form.
     */
    @Test
    void generatedMessagesRoundTripOnTheAlignedProtobuf() throws Exception {
        ListLogEntriesRequest list = ListLogEntriesRequest.newBuilder()
                .addResourceNames("projects/" + PROJECT)
                .setFilter("logName:\"cloudaudit.googleapis.com%2Fdata_access\"")
                .setPageSize(1000)
                .build();
        assertThat(ListLogEntriesRequest.parseFrom(list.toByteArray())).isEqualTo(list);

        String table = TableId.of(PROJECT, "dlap_audit", "cloudaudit_googleapis_com_data_access").getIAMResourceName();
        CreateReadSessionRequest session = CreateReadSessionRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setReadSession(ReadSession.newBuilder().setTable(table).setDataFormat(DataFormat.ARROW))
                .setMaxStreamCount(1)
                .build();
        String json = JsonFormat.printer().print(session);
        CreateReadSessionRequest.Builder parsed = CreateReadSessionRequest.newBuilder();
        JsonFormat.parser().merge(json, parsed);
        assertThat(parsed.build()).isEqualTo(session);
        assertThat(ArrowSchema.getDescriptor().getFullName()).isEqualTo("google.cloud.bigquery.storage.v1.ArrowSchema");

        assertThat(BlobId.of("dlap-sink", "cloudaudit.googleapis.com/data_access/2026/10/19/00:00:00_00:59:59_S0.json")
                .toGsUtilUri()).startsWith("gs://dlap-sink/");
    }

    /**
     * Arrow, the format the Storage Read API returns, allocates through its Netty memory module on the
     * aligned Netty 4.2. Arrow reaches into java.nio, so on Java 21 the JVM needs
     * {@code --add-opens=java.base/java.nio=ALL-UNNAMED}; the test JVM does not have it, so the check
     * runs in a child JVM with the flag, and once without it to pin the failure a service would see.
     */
    @Test
    void arrowAllocatesOnTheAlignedNettyWhenJavaNioIsOpen() throws Exception {
        Run open = runChild("--add-opens=java.base/java.nio=ALL-UNNAMED");
        assertThat(open.exitCode()).as(open.output()).isZero();
        assertThat(open.output()).contains("arrow-ok 42");

        Run closed = runChild();
        assertThat(closed.exitCode()).as(closed.output()).isNotZero();
        assertThat(closed.output()).contains("--add-opens=java.base/java.nio");
    }

    /** The exit code and the merged output of a child JVM. */
    private record Run(int exitCode, String output) { }

    private static Run runChild(String... jvmFlags) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The class path goes through an argument file: on Windows it exceeds the command-line limit.
        Path argFile = Files.createTempFile("dlap-arrow-probe", ".args");
        argFile.toFile().deleteOnExit();
        String classPath = System.getProperty("java.class.path").replace('\\', '/');
        Files.writeString(argFile, "-cp \"" + classPath + "\"\n", StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(List.of(java));
        command.addAll(List.of(jvmFlags));
        command.addAll(List.of("@" + argFile, ArrowProbe.class.getName()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    /** Child JVM entry point: one Arrow buffer, written and read back. */
    static final class ArrowProbe {
        private ArrowProbe() { }

        /**
         * Allocates one Arrow buffer, writes and reads one value, prints it.
         *
         * @param args unused
         */
        public static void main(String[] args) {
            try (BufferAllocator allocator = new RootAllocator(1024 * 1024); ArrowBuf buffer = allocator.buffer(64)) {
                buffer.setLong(0, 42L);
                System.out.println("arrow-ok " + buffer.getLong(0) + " " + allocator.getClass().getName());
            }
        }
    }
}
