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

package com.dlap.fixtures.gen.scale;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Command line of the scale generators (E2E-02), run by {@code e2e-tests/perf/run.sh} from the compiled
 * classes. Every command prints {@code key=value} lines on standard output and nothing else, so a shell
 * can read the result; errors go to standard error and the exit code is 1 (2 for a usage error).
 *
 * <pre>
 * trail  --seed N --count N --end ISO --span-hours H --account A --region R --per-file N
 *        (--out-dir DIR | --endpoint URL --bucket B) [--threads T] [--ids-out FILE]
 * replay --provider gcp|azure --seed N --count N --end ISO --span-hours H --per-file N --out-dir DIR
 *        [--format json|lines] [--ids-out FILE]
 * post   --dir DIR --prefix gcp|azure --url URL [--threads T]
 *        [--token-url URL --client-id ID --user NAME]
 * flaws  --tar-dir DIR --endpoint URL --bucket B [--threads T]
 * </pre>
 *
 * <p>{@code trail} writes or uploads CloudTrail trail files; {@code replay} writes the normalized GCP or
 * Azure events, as JSON array files named {@code <provider>-scale-NNNNN.json} (the shape the fixture
 * replay seeder reads) or, with {@code --format lines}, as {@code .jsonl} files of one event per line;
 * {@code post} sends those lines to the REST channel of da-logingest from many threads, getting tokens
 * with the password grant when {@code --token-url} is given (the client secret and the password come from
 * the environment variables {@code DLAP_PERF_CLIENT_SECRET} and {@code DLAP_PERF_PASSWORD}, never from
 * the arguments); {@code flaws} places the files of an extracted public CloudTrail archive under trail
 * keys and uploads them. {@code --end} is required, never read from the clock, so a run is reproducible.
 */
public final class ScaleMain {

    private static final int DEFAULT_THREADS = 4;

    private ScaleMain() {
    }

    /**
     * Entry point.
     *
     * @param args the command and its options
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs a command.
     *
     * @param args the command and its options
     * @param out  where the {@code key=value} lines go
     * @param err  where errors go
     * @return the exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("usage: ScaleMain trail|replay|post|flaws [--option value]...");
            return 2;
        }
        try {
            Map<String, String> opts = options(args);
            return switch (args[0]) {
                case "trail" -> trail(opts, out);
                case "replay" -> replay(opts, out);
                case "post" -> post(opts, out);
                case "flaws" -> flaws(opts, out);
                default -> {
                    err.println("unknown command " + args[0] + "; want trail, replay, post or flaws");
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println("usage error: " + e.getMessage());
            return 2;
        } catch (IOException | ExecutionException e) {
            err.println("failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("interrupted");
            return 1;
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --option value pairs, got " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        return opts;
    }

    private static String need(Map<String, String> opts, String name) {
        String v = opts.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return v;
    }

    private static long number(Map<String, String> opts, String name, long min) {
        String v = need(opts, name);
        try {
            long n = Long.parseLong(v);
            if (n < min) {
                throw new IllegalArgumentException("--" + name + " must be at least " + min);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number, got " + v);
        }
    }

    private static ScaleWindow window(Map<String, String> opts) {
        Instant end;
        try {
            end = Instant.parse(need(opts, "end"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("--end must be an ISO instant such as 2026-11-01T12:00:00Z");
        }
        long hours = number(opts, "span-hours", 1);
        return new ScaleWindow(number(opts, "seed", 0), number(opts, "count", 1), end, Duration.ofHours(hours));
    }

    private static int threads(Map<String, String> opts) {
        return opts.containsKey("threads") ? (int) number(opts, "threads", 1) : DEFAULT_THREADS;
    }

    private static int trail(Map<String, String> opts, PrintStream out)
            throws IOException, InterruptedException, ExecutionException {
        ScaleWindow window = window(opts);
        CloudTrailScale ct = new CloudTrailScale(window, need(opts, "account"), need(opts, "region"));
        TrailFiles files = new TrailFiles(ct, (int) number(opts, "per-file", 1));
        Path outDir = opts.containsKey("out-dir") ? Path.of(opts.get("out-dir")) : null;
        String endpoint = opts.get("endpoint");
        if ((outDir == null) == (endpoint == null)) {
            throw new IllegalArgumentException("give exactly one of --out-dir and --endpoint (with --bucket)");
        }
        ObjectUploader uploader = null;
        String bucket = null;
        if (endpoint != null) {
            bucket = need(opts, "bucket");
            uploader = new ObjectUploader(endpoint);
            uploader.createBucket(bucket);
        } else {
            Files.createDirectories(outDir);
        }
        if (opts.containsKey("ids-out")) {
            writeIds(Path.of(opts.get("ids-out")), window.count(), ct::eventId);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads(opts));
        try {
            List<Future<Long>> done = new ArrayList<>();
            for (int f = 0; f < files.fileCount(); f++) {
                final int file = f;
                final ObjectUploader up = uploader;
                final String b = bucket;
                done.add(pool.submit(() -> {
                    byte[] bytes = files.gzip(file);
                    if (up != null) {
                        up.put(b, files.key(file), bytes);
                    } else {
                        Path target = outDir.resolve(files.key(file));
                        Files.createDirectories(target.getParent());
                        Files.write(target, bytes);
                    }
                    return (long) bytes.length;
                }));
            }
            long bytes = 0;
            for (Future<Long> d : done) {
                bytes += d.get();
            }
            out.println("records=" + window.count());
            out.println("files=" + files.fileCount());
            out.println("bytes=" + bytes);
            out.println("eventsFrom=" + window.start());
            out.println("eventsTo=" + window.end());
            out.println("deliveredFirst=" + files.deliveredAt(0));
            out.println("deliveredLast=" + files.deliveredAt(files.fileCount() - 1));
        } finally {
            pool.shutdownNow();
        }
        return 0;
    }

    private static int replay(Map<String, String> opts, PrintStream out) throws IOException {
        ScaleWindow window = window(opts);
        String provider = need(opts, "provider");
        java.util.function.LongFunction<NormalizedEvent> source;
        switch (provider) {
            case "gcp" -> {
                GcpAuditScale gcp = new GcpAuditScale(window, "dlap-scale-gcp");
                source = gcp::normalized;
            }
            case "azure" -> {
                AzureActivityScale az = new AzureActivityScale(window, "6c1f0f6e-7a54-4d2b-9b0a-5d3e8f4a1c20");
                source = az::normalized;
            }
            default -> throw new IllegalArgumentException("--provider must be gcp or azure");
        }
        int perFile = (int) number(opts, "per-file", 1);
        String format = opts.getOrDefault("format", "json");
        if (!format.equals("json") && !format.equals("lines")) {
            throw new IllegalArgumentException("--format must be json or lines");
        }
        boolean lines = format.equals("lines");
        Path dir = Path.of(need(opts, "out-dir"));
        Files.createDirectories(dir);
        Path idsOut = opts.containsKey("ids-out") ? Path.of(opts.get("ids-out")) : null;
        int fileCount = 0;
        try (BufferedWriter ids = idsOut == null ? null : Files.newBufferedWriter(idsOut, StandardCharsets.UTF_8)) {
            for (long from = 0; from < window.count(); from += perFile) {
                long to = Math.min(window.count(), from + perFile);
                Path file = dir.resolve(String.format("%s-scale-%05d.%s", provider, fileCount + 1,
                        lines ? "jsonl" : "json"));
                try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    if (!lines) {
                        w.write('[');
                    }
                    for (long i = from; i < to; i++) {
                        NormalizedEvent e = source.apply(i);
                        if (lines) {
                            w.write(e.toJson());
                            w.write('\n');
                        } else {
                            if (i > from) {
                                w.write(',');
                            }
                            w.write(e.toJson());
                        }
                        if (ids != null) {
                            ids.write(e.eventId());
                            ids.write('\n');
                        }
                    }
                    if (!lines) {
                        w.write(']');
                    }
                }
                fileCount++;
            }
        }
        out.println("records=" + window.count());
        out.println("files=" + fileCount);
        out.println("eventsFrom=" + window.start());
        out.println("eventsTo=" + window.end());
        return 0;
    }

    private static int post(Map<String, String> opts, PrintStream out) throws IOException, InterruptedException {
        Path dir = Path.of(need(opts, "dir"));
        String prefix = need(opts, "prefix");
        List<Path> files;
        try (Stream<Path> walk = Files.list(dir)) {
            files = walk.filter(p -> p.getFileName().toString().startsWith(prefix + "-scale-")
                    && p.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no " + prefix + "-scale-*.jsonl file in " + dir);
        }
        java.util.function.Supplier<String> tokens = null;
        if (opts.containsKey("token-url")) {
            String secret = System.getenv("DLAP_PERF_CLIENT_SECRET");
            String password = System.getenv("DLAP_PERF_PASSWORD");
            if (secret == null || password == null) {
                throw new IllegalArgumentException(
                        "DLAP_PERF_CLIENT_SECRET and DLAP_PERF_PASSWORD must be set in the environment");
            }
            tokens = new RestPoster.PasswordGrant(java.net.http.HttpClient.newHttpClient(),
                    java.net.URI.create(opts.get("token-url")), need(opts, "client-id"), secret, need(opts, "user"),
                    password);
        }
        long started = System.nanoTime();
        RestPoster.Result r = new RestPoster(java.net.URI.create(need(opts, "url")), tokens, threads(opts)).post(files);
        double seconds = (System.nanoTime() - started) / 1e9;
        out.println("sent=" + r.sent());
        out.println("accepted=" + r.accepted());
        out.println("duplicates=" + r.duplicates());
        out.println("rejected=" + r.rejected());
        out.println("failed=" + r.failed());
        out.println("seconds=" + Math.round(seconds));
        return r.failed() == 0 ? 0 : 1;
    }

    private static int flaws(Map<String, String> opts, PrintStream out)
            throws IOException, InterruptedException, ExecutionException {
        Path dir = Path.of(need(opts, "tar-dir"));
        ObjectUploader uploader = new ObjectUploader(need(opts, "endpoint"));
        String bucket = need(opts, "bucket");
        List<Path> candidates;
        try (Stream<Path> walk = Files.walk(dir)) {
            candidates = walk.filter(Files::isRegularFile).sorted().toList();
        }
        uploader.createBucket(bucket);
        TreeSet<String> accounts = new TreeSet<>();
        TreeSet<String> regions = new TreeSet<>();
        Instant first = null;
        Instant last = null;
        long unmapped = 0;
        long mapped = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threads(opts));
        try {
            List<Future<?>> done = new ArrayList<>();
            for (Path p : candidates) {
                var placed = FlawsKeys.place(p.getFileName().toString());
                if (placed.isEmpty()) {
                    unmapped++;
                    continue;
                }
                mapped++;
                accounts.add(placed.get().account());
                regions.add(placed.get().region());
                Instant at = placed.get().delivered();
                first = first == null || at.isBefore(first) ? at : first;
                last = last == null || at.isAfter(last) ? at : last;
                done.add(pool.submit(() -> {
                    uploader.put(bucket, placed.get().key(), Files.readAllBytes(p));
                    return null;
                }));
            }
            for (Future<?> d : done) {
                d.get();
            }
        } finally {
            pool.shutdownNow();
        }
        out.println("files=" + mapped);
        out.println("unmapped=" + unmapped);
        out.println("accounts=" + String.join(",", accounts));
        out.println("regions=" + String.join(",", regions));
        out.println("deliveredFirst=" + (first == null ? "" : first));
        out.println("deliveredLast=" + (last == null ? "" : last));
        return 0;
    }

    private static void writeIds(Path file, long count, java.util.function.LongFunction<String> id) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (long i = 0; i < count; i++) {
                w.write(id.apply(i));
                w.write('\n');
            }
        }
    }
}
