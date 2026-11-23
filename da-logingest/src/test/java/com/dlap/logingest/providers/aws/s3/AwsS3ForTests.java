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

package com.dlap.logingest.providers.aws.s3;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.parse.cloudtrail.CloudTrailParser;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.s3accesslog.S3AccessLogFormat;
import com.dlap.logingest.parse.s3accesslog.S3AccessLogParser;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/** An in-memory S3 bucket, AWS stream contexts and the real AWS parsers, for the transport tests. */
final class AwsS3ForTests {

    static final Instant NOW = Instant.parse("2026-11-02T06:00:00Z");
    static final String ACCOUNT = "123456789012";
    static final String BUCKET = "dlap-trail-logs";

    static final MappingSpec CLOUDTRAIL = AbstractParserContractTest.classpathMapping(CloudTrailFormat.FAMILY);
    static final DefaultParserRegistry CLOUDTRAIL_REGISTRY =
            AbstractParserContractTest.registryOf(CLOUDTRAIL, CloudTrailParser.forSpec(CLOUDTRAIL));
    static final MappingSpec ACCESS_LOG = AbstractParserContractTest.classpathMapping(S3AccessLogFormat.FAMILY);
    static final DefaultParserRegistry ACCESS_LOG_REGISTRY =
            AbstractParserContractTest.registryOf(ACCESS_LOG, List.of(new S3AccessLogParser(ACCESS_LOG)));

    private AwsS3ForTests() {
    }

    /** An in-memory bucket that lists in key order, like S3. */
    static final class FakeS3 implements S3LogStore {
        final Map<String, byte[]> objects = new TreeMap<>();
        final Map<String, Instant> modified = new TreeMap<>();
        final List<String> listed = new ArrayList<>();
        final List<String> read = new ArrayList<>();
        final List<Long> readLimits = new ArrayList<>();

        FakeS3 put(String key, byte[] bytes) {
            objects.put(key, bytes);
            modified.put(key, NOW);
            return this;
        }

        FakeS3 put(String key, String text) {
            return put(key, text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public List<S3LogObject> list(StreamContext context, String bucket, String prefix, String startAfter,
                                      String stopAfter) {
            listed.add(prefix + "|" + startAfter + "|" + stopAfter);
            List<S3LogObject> out = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : objects.entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(prefix) || (!startAfter.isEmpty() && k.compareTo(startAfter) <= 0)) {
                    continue;
                }
                if (!stopAfter.isEmpty() && k.compareTo(stopAfter) > 0) {
                    break;
                }
                out.add(new S3LogObject(k, "\"etag-" + e.getValue().length + "\"", e.getValue().length,
                        modified.get(k)));
            }
            return out;
        }

        @Override
        public List<String> folders(StreamContext context, String bucket, String prefix) {
            Set<String> out = new java.util.TreeSet<>();
            for (String k : objects.keySet()) {
                if (k.startsWith(prefix)) {
                    int slash = k.indexOf('/', prefix.length());
                    if (slash > 0) {
                        out.add(k.substring(0, slash + 1));
                    }
                }
            }
            return List.copyOf(out);
        }

        @Override
        public byte[] read(StreamContext context, String bucket, S3LogObject object, long maxBytes) {
            read.add(object.key());
            readLimits.add(maxBytes);
            byte[] b = objects.get(object.key());
            return b.length <= maxBytes ? b : Arrays.copyOf(b, (int) maxBytes);
        }
    }

    /** A processed-object store backed by a set of {@code key@etag}. */
    static final class DoneObjects implements ProcessedObjectStore {
        final Set<String> done = new HashSet<>();

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

    /**
     * A stream context of an AWS binding.
     *
     * @param transport the transport id
     * @param kind      the source kind
     * @param envelope  the envelope
     * @param region    the binding's single scope region, or {@code null}
     * @param location  the location keys
     * @param objects   the processed-object store
     * @return the context
     */
    static StreamContext context(String transport, String kind, String envelope, String region,
                                 Map<String, String> location, ProcessedObjectStore objects) {
        return context(transport, kind, envelope, region, location, objects, BindingConfig.DEFAULT_MAX_RECORD_BYTES,
                null);
    }

    /**
     * A stream context of an AWS binding with a record limit and a credentials reference.
     *
     * @param transport      the transport id
     * @param kind           the source kind
     * @param envelope       the envelope
     * @param region         the binding's single scope region, or {@code null}
     * @param location       the location keys
     * @param objects        the processed-object store
     * @param maxRecordBytes the binding's record limit
     * @param credentialsRef the credentials reference, or {@code null}
     * @return the context
     */
    static StreamContext context(String transport, String kind, String envelope, String region,
                                 Map<String, String> location, ProcessedObjectStore objects, long maxRecordBytes,
                                 String credentialsRef) {
        BindingConfig parse = BindingConfig.auto("aws-test", kind)
                .withAssetIdHints(AssetIdHints.builder().provider(AssetProvider.AWS).account(ACCOUNT).region(region)
                        .build())
                .withMaxRecordBytes(maxRecordBytes);
        EffectiveStream stream = new EffectiveStream("aws-test", "s1", transport, envelope, location, parse,
                StreamFilters.NONE, Duration.ofMinutes(20), Duration.ofMinutes(5), null, credentialsRef, false, false,
                false);
        return new StreamContext(stream, kind, IngestMode.STREAM, Clock.fixed(NOW, ZoneOffset.UTC), objects);
    }

    /** A CloudTrail stream context. */
    static StreamContext trail(Map<String, String> location, ProcessedObjectStore objects) {
        return context(CloudTrailTrailFileTransport.ID, CloudTrailFormat.SOURCE_KIND, CloudTrailFormat.S3_FILE,
                "us-east-1", location, objects);
    }

    /** An access log stream context. */
    static StreamContext accessLog(Map<String, String> location, ProcessedObjectStore objects) {
        return context(S3ServerAccessLogTransport.ID, S3AccessLogFormat.SOURCE_KIND, S3AccessLogFormat.CWL_JSON,
                "us-east-1", location, objects);
    }

    /**
     * Drains an iterator.
     *
     * @param it the iterator
     * @return the batches
     */
    static List<RawBatch> drain(RawBatchIterator it) {
        List<RawBatch> out = new ArrayList<>();
        try (it) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }

    /**
     * Parses a record with the family's registry and the stream's binding settings.
     *
     * @param context the stream
     * @param record  the record
     * @return the outcome
     */
    static ParseOutcome parse(StreamContext context, RawRecord record) {
        DefaultParserRegistry registry = CloudTrailFormat.SOURCE_KIND.equals(record.sourceKind())
                ? CLOUDTRAIL_REGISTRY : ACCESS_LOG_REGISTRY;
        return registry.parse(record, context.stream().parse());
    }

    /**
     * Gzips text.
     *
     * @param text the text
     * @return the gzip bytes
     */
    static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(out)) {
                z.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A CloudTrail record of an S3 lifecycle expiration (the event name RET-02 assumes, Q-639).
     *
     * @param eventId the event id
     * @param time    the event time
     * @param key     the expired object's key
     * @return the record JSON
     */
    static String lifecycleDelete(String eventId, String time, String key) {
        return "{\"eventVersion\":\"1.11\",\"userIdentity\":{\"type\":\"AWSService\",\"invokedBy\":"
                + "\"lifecycle.s3.amazonaws.com\"},\"eventTime\":\"" + time + "\",\"eventSource\":"
                + "\"s3lifecycle.amazonaws.com\",\"eventName\":\"DeleteObject\",\"awsRegion\":\"us-east-1\","
                + "\"sourceIPAddress\":\"lifecycle.s3.amazonaws.com\",\"requestParameters\":{\"bucketName\":"
                + "\"dlap-aws-e2e\",\"key\":\"" + key + "\"},\"responseElements\":null,\"requestID\":\"AWS01REQ"
                + eventId.substring(eventId.length() - 4) + "\",\"eventID\":\"" + eventId + "\",\"readOnly\":false,"
                + "\"resources\":[{\"type\":\"AWS::S3::Object\",\"ARN\":\"arn:aws:s3:::dlap-aws-e2e/" + key + "\"}],"
                + "\"eventType\":\"AwsServiceEvent\",\"managementEvent\":false,\"recipientAccountId\":\"" + ACCOUNT
                + "\",\"eventCategory\":\"Data\"}";
    }

    /**
     * A trail file holding records.
     *
     * @param records the record JSON texts
     * @return {@code {"Records":[...]}}
     */
    static String trailFile(String... records) {
        return "{\"Records\":[" + String.join(",", records) + "]}";
    }

    /**
     * The key of a trail file.
     *
     * @param prefix the trail prefix, {@code ""} for none
     * @param region the region
     * @param time   the delivery time, {@code yyyyMMdd'T'HHmm'Z'}
     * @param unique the unique part
     * @return the key
     */
    static String trailKey(String prefix, String region, String time, String unique) {
        return prefix + "AWSLogs/" + ACCOUNT + "/CloudTrail/" + region + "/" + time.substring(0, 4) + "/"
                + time.substring(4, 6) + "/" + time.substring(6, 8) + "/" + ACCOUNT + "_CloudTrail_" + region + "_"
                + time + "_" + unique + ".json.gz";
    }
}
