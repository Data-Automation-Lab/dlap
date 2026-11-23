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

package com.dlap.logingest.parse.s3accesslog;

import com.dlap.common.assetid.AssetKind;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionedDeletes;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses S3 server access log records (CloudWatch Logs JSON) with the family mapping and adds what
 * needs code.
 *
 * <ul>
 *   <li><b>Target:</b> {@code s3://bucket_name/key_name}, or {@code s3://bucket_name} when the
 *       record names no key, normalized through {@code AssetIdNormalizer}. An object operation
 *       without a key gets no canonical URI (never the bucket's), so it is quarantined as
 *       {@code UNRESOLVABLE_ASSET_ID}.</li>
 *   <li><b>Principal:</b> {@code requester}. An operation S3 performs itself ({@code S3.*}: lifecycle
 *       expirations, delete markers, transitions, aborted uploads, checksum jobs) is a {@code SYSTEM}
 *       actor, which is how the Lifecycle State emitter tells a native rule from a person (Q-596). A
 *       requester ARN of a role or an assumed role is a {@code ROLE}, of a user or the root user a
 *       {@code USER}; a canonical user id has no type. The account is read from an ARN.</li>
 *   <li><b>Native key:</b> the mapping's {@code bucket|request time|operation}, then
 *       {@code |request_id|key_name}, each empty when null: the request id alone is not unique (a copy
 *       writes two records with it) and is null on some records.</li>
 *   <li><b>Version-qualified deletes</b> (D36, Q-651): a {@code DELETE} of an object whose record
 *       names a {@code version_id} removed that version only ({@code S3.EXPIRE.OBJECT} of a noncurrent
 *       version, a {@code REST.DELETE.OBJECT} or {@code BATCH.DELETE.OBJECT} of one version), so it
 *       becomes an {@code UPDATE} of the object with the version action in its technical metadata
 *       ({@link VersionedDeletes}). {@code S3.CREATE.DELETEMARKER}, which the mapping classifies as
 *       {@code UPDATE}, carries the {@code CREATE_DELETE_MARKER} action. The S3 lifecycle logging
 *       table has no separate operation for a noncurrent expiration: {@code S3.EXPIRE.OBJECT} is the
 *       only permanent deletion it lists, and {@code version_id} is the version in the request, or
 *       null when no versioned object is involved (sal-cw-log-format, opened 2026-11-01).</li>
 * </ul>
 */
public class S3AccessLogParser extends MappingDrivenParser {

    /**
     * Creates the parser of the {@code cwl-json} envelope.
     *
     * @param spec the family mapping
     */
    public S3AccessLogParser(MappingSpec spec) {
        super(spec, S3AccessLogFormat.CWL_JSON, S3AccessLogFormat.RANGE, S3AccessLogFormat.PARSER_ID,
                S3AccessLogFormat.PARSER_SEMVER);
    }

    @Override
    public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
        ParseResult result = super.parse(record, effective, binding);
        if (!(result instanceof ParseResult.Parsed parsed)) {
            return result;
        }
        JsonNode body = record.body();
        String suffix = LogEventIds.SEPARATOR + text(body.path("request_id")).orElse("")
                + LogEventIds.SEPARATOR + text(body.path("key_name")).orElse("");
        List<CanonicalEvent> events = new ArrayList<>();
        for (CanonicalEvent e : parsed.events()) {
            String key = e.nativeKey() + suffix;
            if (key.length() > CanonicalEvent.MAX_NATIVE_KEY_LENGTH) {
                return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        "the native key is longer than " + CanonicalEvent.MAX_NATIVE_KEY_LENGTH + " characters");
            }
            CanonicalEvent out = e.toBuilder().nativeKey(key).principal(principal(e.operation(), e.principal().id()))
                    .build();
            events.add(versionQualified(out, text(body.path("version_id"))));
        }
        return new ParseResult.Parsed(events, parsed.warnings());
    }

    @Override
    protected CanonicalEvent.Resource target(Context ctx) {
        JsonNode body = ctx.body();
        Optional<String> bucket = text(body.path("bucket_name"));
        if (bucket.isEmpty()) {
            return CanonicalEvent.Resource.NONE;
        }
        Optional<String> key = text(body.path("key_name"));
        String raw = "s3://" + bucket.get() + key.map(k -> "/" + k).orElse("");
        CanonicalEvent.ResourceBuilder b = CanonicalEvent.Resource.builder()
                .kind(key.isPresent() ? S3AccessLogFormat.S3_OBJECT_KIND : S3AccessLogFormat.S3_BUCKET_KIND)
                .id(raw)
                .name(key.orElse(bucket.get()));
        // An object operation without a key must never become an event about the bucket: an expiration
        // would otherwise delete the bucket in the catalog. Its row's data category then quarantines it.
        if (key.isPresent() || !objectOperation(ctx)) {
            b.canonicalFrom(raw, ctx.assetIdHints());
        }
        return b.build();
    }

    /**
     * A {@code DELETE} of an object that names a version is an {@code UPDATE} of the object, and a delete
     * marker creation carries its version action (Q-651).
     *
     * @param e         the event
     * @param versionId the record's {@code version_id}, if any
     * @return the event, rewritten when it is version-qualified
     */
    static CanonicalEvent versionQualified(CanonicalEvent e, Optional<String> versionId) {
        if (!S3AccessLogFormat.S3_OBJECT_KIND.equals(e.target().kind())) {
            return e;
        }
        if (S3AccessLogFormat.CREATE_DELETE_MARKER_OPERATION.equals(e.operation())) {
            return e.toBuilder().target(VersionedDeletes.withAction(e.target(), VersionedDeletes.CREATE_DELETE_MARKER,
                    versionId)).build();
        }
        if (e.changeType() == ChangeType.DELETE && versionId.isPresent()) {
            return VersionedDeletes.asVersionUpdate(e, VersionedDeletes.DELETE_VERSION, versionId);
        }
        return e;
    }

    /**
     * Whether the record's classification row names an S3 object as its target.
     *
     * @param ctx the record context
     * @return true for an object row
     */
    static boolean objectOperation(Context ctx) {
        return ctx.classification().assetKind().filter(k -> k == AssetKind.S3_OBJECT).isPresent();
    }

    /**
     * The actor of a record.
     *
     * @param operation the operation
     * @param requester the requester text, or {@code ""}
     * @return the actor
     */
    static CanonicalEvent.Actor principal(String operation, String requester) {
        String id = requester == null ? "" : requester;
        String account = account(id);
        if (operation != null && operation.startsWith(S3AccessLogFormat.SYSTEM_OPERATION_PREFIX)) {
            return new CanonicalEvent.Actor(PrincipalType.SYSTEM, id, "", account);
        }
        String lower = id.toLowerCase(Locale.ROOT);
        PrincipalType type;
        if (lower.contains(":assumed-role/") || lower.contains(":role/")) {
            type = PrincipalType.ROLE;
        } else if (lower.contains(":user/") || lower.endsWith(":root")) {
            type = PrincipalType.USER;
        } else {
            type = PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED;
        }
        return new CanonicalEvent.Actor(type, id, "", account);
    }

    /**
     * The account of an ARN ({@code arn:partition:service:region:account:resource}).
     *
     * @param id the requester
     * @return the twelve digit account, or {@code ""}
     */
    static String account(String id) {
        if (!id.startsWith("arn:")) {
            return "";
        }
        String[] parts = id.split(":", 6);
        return parts.length == 6 && parts[4].matches("\\d{12}") ? parts[4] : "";
    }

    private static Optional<String> text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return Optional.empty();
        }
        String s = node.asText();
        return s.isBlank() || "-".equals(s) ? Optional.empty() : Optional.of(s);
    }
}
