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

package com.dlap.logingest.parse.cloudtrail;

import com.dlap.common.assetid.AssetKind;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.VersionedDeletes;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses CloudTrail records with the family mapping and adds what needs code.
 *
 * <p><b>Target.</b> An S3 call names its bucket and key in {@code requestParameters}
 * ({@code bucketName}, {@code key}): the target is {@code s3://bucket/key}, or {@code s3://bucket}
 * without a key. A record without them takes the first {@code resources[]} entry of type
 * {@code AWS::S3::Object}, else the first entry with an ARN. The id is normalized through
 * {@code AssetIdNormalizer} with the asset kind of the classification row; an id that does not
 * normalize leaves {@code canonical_uri} empty, and a data event with an asset kind is then
 * quarantined as {@code UNRESOLVABLE_ASSET_ID} by the mapping-driven parser. An object row whose
 * record names no object (only a bucket) gets no canonical URI either. An
 * {@code s3lifecycle.amazonaws.com} record that names no object gets no target at all, whatever its row
 * (D36, Q-650): S3 lifecycle acts on objects, so no lifecycle record can ever read as an event about
 * the bucket, and no consumer can normalize a bucket id it does not carry.
 *
 * <p><b>Unlisted lifecycle events.</b> The mapping's catch-all rows classify every
 * {@code s3lifecycle.amazonaws.com} event name it does not list as {@code UPDATE}. A name the rows
 * cannot reach (one that does not start with a capital letter) would fall to the verb heuristics; it
 * is turned into {@code UPDATE} in category {@code MANAGEMENT} too, so an unknown lifecycle event is
 * never a delete.
 *
 * <p><b>Version-qualified deletes</b> (D36, Q-651). A {@code DELETE} of an S3 object whose record
 * names {@code requestParameters.versionId} removes one version, and one whose response says
 * {@code x-amz-delete-marker: true} without a version id only added a delete marker: both become
 * {@code UPDATE} of the object with the version action in its technical metadata
 * ({@link VersionedDeletes}). Only a delete of the current object without a version id stays
 * {@code DELETE}.
 *
 * <p><b>Principal type.</b> {@code userIdentity.type} through the mapping's {@code principal-types};
 * {@code AWSService} is {@code SYSTEM}, so an S3 lifecycle action
 * ({@code invokedBy lifecycle.s3.amazonaws.com}) is a system actor. A record with no identity type
 * but an {@code invokedBy} is a service too ({@code SYSTEM}), as the legacy CloudTrail source reads
 * it.
 */
public class CloudTrailParser extends MappingDrivenParser {

    /**
     * Creates the parser of one version range of the {@code s3-file} envelope.
     *
     * @param spec  the family mapping
     * @param range one of the envelope's ranges
     */
    public CloudTrailParser(MappingSpec spec, VersionRange range) {
        super(spec, CloudTrailFormat.S3_FILE, range, CloudTrailFormat.PARSER_ID, CloudTrailFormat.PARSER_SEMVER);
    }

    /**
     * One parser per version range of the {@code s3-file} envelope.
     *
     * @param spec the family mapping
     * @return the parsers
     * @throws IllegalArgumentException when the mapping has no {@code s3-file} envelope
     */
    public static List<CloudTrailParser> forSpec(MappingSpec spec) {
        MappingSpec.Envelope envelope = spec.envelope(CloudTrailFormat.S3_FILE).orElseThrow(() ->
                new IllegalArgumentException("family " + spec.family() + " has no envelope " + CloudTrailFormat.S3_FILE));
        List<CloudTrailParser> out = new ArrayList<>();
        for (VersionRange r : envelope.ranges()) {
            out.add(new CloudTrailParser(spec, r));
        }
        return List.copyOf(out);
    }

    @Override
    public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
        ParseResult result = super.parse(record, effective, binding);
        if (!(result instanceof ParseResult.Parsed parsed)) {
            return result;
        }
        JsonNode body = record.body();
        boolean serviceWithoutType = serviceInvokedWithoutType(body);
        List<CanonicalEvent> events = new ArrayList<>();
        for (CanonicalEvent e : parsed.events()) {
            CanonicalEvent out = e;
            CanonicalEvent.Actor p = out.principal();
            if (serviceWithoutType && p.type() == PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED) {
                out = out.toBuilder()
                        .principal(new CanonicalEvent.Actor(PrincipalType.SYSTEM, p.id(), p.displayName(), p.accountId()))
                        .build();
            }
            out = unlistedLifecycleIsUpdate(out);
            out = versionQualified(out, body);
            events.add(out);
        }
        return new ParseResult.Parsed(events, parsed.warnings());
    }

    /**
     * An {@code s3lifecycle.amazonaws.com} event that only the verb heuristics classified is an
     * {@code UPDATE} in category {@code MANAGEMENT}, never a delete or a creation (Q-650).
     *
     * @param e the event
     * @return the event, reclassified when it is such a lifecycle event
     */
    static CanonicalEvent unlistedLifecycleIsUpdate(CanonicalEvent e) {
        if (!CloudTrailFormat.S3_LIFECYCLE_SERVICE.equals(e.service())
                || !e.warnings().contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION)) {
            return e;
        }
        return e.toBuilder().changeType(ChangeType.UPDATE).category(EventCategory.EVENT_CATEGORY_MANAGEMENT).build();
    }

    /**
     * A {@code DELETE} of an S3 object that names a version, or that only added a delete marker, is an
     * {@code UPDATE} of the object (Q-651).
     *
     * @param e    the event
     * @param body the record
     * @return the event, rewritten when it is a version-qualified delete
     */
    static CanonicalEvent versionQualified(CanonicalEvent e, JsonNode body) {
        if (e.changeType() != ChangeType.DELETE || !CloudTrailFormat.S3_OBJECT_KIND.equals(e.target().kind())) {
            return e;
        }
        Optional<String> versionId = text(body.path("requestParameters").path("versionId"));
        if (versionId.isPresent()) {
            return VersionedDeletes.asVersionUpdate(e, VersionedDeletes.DELETE_VERSION, versionId);
        }
        JsonNode response = body.path("responseElements");
        if (deleteMarker(response.path(CloudTrailFormat.DELETE_MARKER_HEADER))) {
            return VersionedDeletes.asVersionUpdate(e, VersionedDeletes.CREATE_DELETE_MARKER,
                    text(response.path(CloudTrailFormat.VERSION_ID_HEADER)));
        }
        return e;
    }

    private static boolean deleteMarker(JsonNode node) {
        if (node == null) {
            return false;
        }
        return node.isBoolean() ? node.booleanValue() : node.isTextual() && "true".equalsIgnoreCase(node.asText().strip());
    }

    @Override
    protected CanonicalEvent.Resource target(Context ctx) {
        JsonNode body = ctx.body();
        String project = ctx.fields().text(CanonicalField.TARGET_PROJECT).orElse("");
        String region = ctx.fields().text(CanonicalField.TARGET_REGION).orElse("");
        Optional<String> bucket = text(body.path("requestParameters").path("bucketName"));
        String raw;
        String kind;
        String name;
        if (bucket.isPresent()) {
            Optional<String> key = text(body.path("requestParameters").path("key"));
            raw = "s3://" + bucket.get() + key.map(k -> "/" + k).orElse("");
            kind = key.isPresent() ? CloudTrailFormat.S3_OBJECT_KIND : CloudTrailFormat.S3_BUCKET_KIND;
            name = key.orElse(bucket.get());
        } else {
            JsonNode resource = resource(body.path("resources"));
            if (resource == null) {
                return CanonicalEvent.Resource.builder().project(project).region(region).build();
            }
            raw = text(resource.path("ARN")).orElse("");
            kind = text(resource.path("type")).orElse("");
            name = raw;
        }
        boolean lifecycle = ctx.fields().text(CanonicalField.SERVICE)
                .filter(CloudTrailFormat.S3_LIFECYCLE_SERVICE::equals).isPresent();
        if (lifecycle && !CloudTrailFormat.S3_OBJECT_KIND.equals(kind)) {
            // S3 lifecycle acts on objects only: a lifecycle record that names no object names no target at
            // all, so no consumer can normalize its bucket id into the bucket's URI (D36, Q-650).
            return CanonicalEvent.Resource.builder().project(project).region(region).build();
        }
        CanonicalEvent.ResourceBuilder b = CanonicalEvent.Resource.builder()
                .kind(kind)
                .id(raw)
                .name(name)
                .project(project)
                .region(region);
        // An object operation whose record names only a bucket must never become an event about the
        // bucket: a lifecycle expiration would otherwise delete the bucket in the catalog. The row's
        // data category then quarantines it as UNRESOLVABLE_ASSET_ID.
        boolean objectRow = ctx.classification().assetKind().filter(k -> k == AssetKind.S3_OBJECT).isPresent();
        if (!objectRow || CloudTrailFormat.S3_OBJECT_KIND.equals(kind)) {
            b.canonicalFrom(raw, ctx.assetIdHints());
        }
        return b.build();
    }

    /**
     * The resource a record without request parameters is about: the first S3 object, else the
     * first entry that carries an ARN.
     *
     * @param resources the {@code resources} value
     * @return the entry, or {@code null}
     */
    static JsonNode resource(JsonNode resources) {
        if (resources == null || !resources.isArray()) {
            return null;
        }
        JsonNode first = null;
        for (JsonNode r : resources) {
            if (text(r.path("ARN")).isEmpty()) {
                continue;
            }
            if (CloudTrailFormat.S3_OBJECT_KIND.equals(r.path("type").asText(""))) {
                return r;
            }
            if (first == null) {
                first = r;
            }
        }
        return first;
    }

    /**
     * Whether the record names its invoking service but no identity type.
     *
     * @param body the record
     * @return true for a {@code userIdentity} with {@code invokedBy} and without {@code type}
     */
    static boolean serviceInvokedWithoutType(JsonNode body) {
        JsonNode identity = body.path("userIdentity");
        return text(identity.path("type")).isEmpty() && text(identity.path("invokedBy")).isPresent();
    }

    private static Optional<String> text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return Optional.empty();
        }
        String s = node.asText();
        return s.isBlank() ? Optional.empty() : Optional.of(s);
    }
}
