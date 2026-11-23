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

package com.dlap.common.assetid;

import com.dlap.common.assetid.AssetIdParseResult.Reason;

import java.util.List;
import java.util.Optional;

/**
 * The single entry point for building and parsing asset identifiers (ADR-0047,
 * {@code docs/specs/asset-id.md}).
 *
 * <p>Every alias of a resource that a crawler, a log, lineage or the retire path uses today
 * normalizes to one {@link CanonicalAssetId}. Normalization is idempotent: parsing a canonical URI
 * returns the same URI. Nothing is guessed: a form that needs context it does not have is rejected
 * with {@link Reason#MISSING_HINT} or {@link Reason#AMBIGUOUS}, and destructive callers fail closed
 * on any rejection.
 *
 * <p>Thread-safe and stateless.
 */
public final class AssetIdNormalizer {

    /** Version of the canonical-ID contract in {@code docs/specs/asset-id.md}. */
    public static final String SPEC_VERSION = "1.0";

    /** Maximum canonical URI length in code points, the width of {@code dlap_assets.asset_uri}. */
    public static final int MAX_URI_LENGTH = 2048;

    /** Inputs longer than this are rejected before any parsing. */
    public static final int MAX_INPUT_LENGTH = 8192;

    private static final List<AssetIdScheme> SCHEMES = List.of(
            AwsAssetIdScheme.INSTANCE,
            GcpAssetIdScheme.INSTANCE,
            AzureAssetIdScheme.INSTANCE,
            UnityAssetIdScheme.INSTANCE,
            DatabricksAssetIdScheme.INSTANCE,
            SnowflakeAssetIdScheme.INSTANCE);

    private AssetIdNormalizer() {
    }

    /**
     * Parses an identifier.
     *
     * @param raw   the identifier in any supported form; may be {@code null}
     * @param hints context for forms that do not describe themselves; {@code null} means none
     * @return the canonical ID or a rejection; the result carries {@code raw} unchanged
     */
    public static AssetIdParseResult parse(String raw, AssetIdHints hints) {
        AssetIdHints h = hints == null ? AssetIdHints.none() : hints;
        return IdText.withRaw(parseInternal(raw, h), raw);
    }

    /**
     * Normalizes an identifier.
     *
     * @param raw   the identifier in any supported form; may be {@code null}
     * @param hints context for forms that do not describe themselves; {@code null} means none
     * @return the canonical ID, or empty when the identifier was rejected
     */
    public static Optional<CanonicalAssetId> normalize(String raw, AssetIdHints hints) {
        return parse(raw, hints).id();
    }

    /**
     * Normalizes a self-describing identifier (a URI, an ARN, an ARM ID, a resource name).
     *
     * @param raw the identifier; may be {@code null}
     * @return the canonical ID, or empty when the identifier was rejected
     */
    public static Optional<CanonicalAssetId> normalize(String raw) {
        return normalize(raw, AssetIdHints.none());
    }

    /**
     * The canonical URI of an identifier.
     *
     * @param raw   the identifier; may be {@code null}
     * @param hints context for forms that do not describe themselves; {@code null} means none
     * @return the canonical URI, or empty when the identifier was rejected
     */
    public static Optional<String> canonicalUri(String raw, AssetIdHints hints) {
        return normalize(raw, hints).map(CanonicalAssetId::uri);
    }

    /**
     * Whether a string already is a canonical URI: it normalizes, without hints, to itself.
     *
     * @param uri the candidate; may be {@code null}
     * @return true when {@code uri} is canonical
     */
    public static boolean isCanonical(String uri) {
        return uri != null && normalize(uri).map(id -> id.uri().equals(uri)).orElse(false);
    }

    /**
     * Builds a canonical ID from identity parts, the way a crawler that already holds structured
     * fields should create one. The parts follow the per-kind layout on {@link AssetKind}; object
     * keys and paths are taken verbatim.
     *
     * @param provider the provider
     * @param kind     the kind
     * @param parts    the identity parts
     * @param hints    hints for kinds that need them; {@code null} means none
     * @return the canonical ID, or a rejection when the parts are invalid for the kind
     */
    public static AssetIdParseResult build(AssetProvider provider, AssetKind kind, List<String> parts,
                                           AssetIdHints hints) {
        if (provider == null || kind == null || parts == null || parts.stream().anyMatch(p -> p == null)) {
            return new AssetIdParseResult.Rejected(Reason.MALFORMED, "provider, kind and every part are required", null);
        }
        AssetIdHints h = hints == null ? AssetIdHints.none() : hints;
        AssetIdScheme scheme = schemeFor(provider);
        Optional<AssetIdParseResult> built = scheme == null ? Optional.empty() : scheme.build(kind, List.copyOf(parts), h);
        if (built.isEmpty()) {
            return new AssetIdParseResult.Rejected(Reason.UNSUPPORTED_KIND,
                    provider + " has no canonical scheme for " + kind, null);
        }
        AssetIdParseResult result = finish(built.get());
        if (result instanceof AssetIdParseResult.Normalized n
                && (n.canonical().kind() != kind || n.canonical().provider() != provider)) {
            return new AssetIdParseResult.Rejected(Reason.MALFORMED, "the parts do not form a " + kind, null);
        }
        return result;
    }

    /**
     * Builds a canonical ID for a kind that belongs to one provider.
     *
     * @param kind  the kind; a shared job kind is rejected, use the provider overload
     * @param parts the identity parts
     * @return the canonical ID or a rejection
     */
    public static AssetIdParseResult build(AssetKind kind, List<String> parts) {
        if (kind == null || kind.defaultProvider().isEmpty()) {
            return new AssetIdParseResult.Rejected(Reason.AMBIGUOUS,
                    "the kind is shared by several providers; pass the provider", null);
        }
        return build(kind.defaultProvider().get(), kind, parts, AssetIdHints.none());
    }

    // ---- internals -------------------------------------------------------------------------

    private static AssetIdParseResult parseInternal(String raw, AssetIdHints h) {
        if (raw == null || raw.isBlank()) {
            return rejected(Reason.BLANK, "the identifier is blank");
        }
        if (raw.length() > MAX_INPUT_LENGTH) {
            return rejected(Reason.TOO_LONG, "the identifier exceeds " + MAX_INPUT_LENGTH + " characters");
        }
        if (raw.indexOf('\0') >= 0) {
            return rejected(Reason.MALFORMED, "the identifier contains a NUL character");
        }
        String v = preprocess(raw, h);
        for (AssetIdScheme scheme : SCHEMES) {
            Optional<AssetIdParseResult> r = scheme.parseSelfDescribing(v, h);
            if (r.isPresent()) {
                return finish(r.get());
            }
        }
        AssetProvider target = h.effectiveProvider();
        if (target != null) {
            for (AssetIdScheme scheme : bareSchemes(target)) {
                Optional<AssetIdParseResult> r = scheme.parseBare(v, h);
                if (r.isPresent()) {
                    return finish(r.get());
                }
            }
            if (target == AssetProvider.OCI) {
                return rejected(Reason.UNRECOGNIZED, "no canonical scheme is defined for OCI");
            }
            return rejected(Reason.AMBIGUOUS, "the hints do not resolve this bare form; pass the kind and the scope it needs");
        }
        if (v.indexOf('.') > 0 && v.indexOf('/') < 0 && v.indexOf(':') < 0) {
            return rejected(Reason.AMBIGUOUS,
                    "a dotted name needs a provider hint: BigQuery, Unity Catalog, Glue and Snowflake all use one");
        }
        return rejected(Reason.UNRECOGNIZED, "no scheme recognises the identifier");
    }

    /**
     * Drops leading whitespace and trailing line breaks, and one pair of wrapping double quotes
     * (StorageBlobLogs quotes ObjectKey). Trailing spaces are kept: they can be part of an object key.
     */
    private static String preprocess(String raw, AssetIdHints h) {
        int start = 0;
        while (start < raw.length() && Character.isWhitespace(raw.charAt(start))) {
            start++;
        }
        int end = raw.length();
        while (end > start && (raw.charAt(end - 1) == '\n' || raw.charAt(end - 1) == '\r')) {
            end--;
        }
        String v = raw.substring(start, end);
        if (h.effectiveProvider() != AssetProvider.SNOWFLAKE && v.length() >= 2
                && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"'
                && v.indexOf('"', 1) == v.length() - 1) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static AssetIdParseResult finish(AssetIdParseResult r) {
        if (r instanceof AssetIdParseResult.Normalized n) {
            String uri = n.canonical().uri();
            if (uri.codePointCount(0, uri.length()) > MAX_URI_LENGTH) {
                return rejected(Reason.TOO_LONG, "the canonical URI exceeds " + MAX_URI_LENGTH + " code points");
            }
        }
        return r;
    }

    private static AssetIdParseResult rejected(Reason reason, String detail) {
        return new AssetIdParseResult.Rejected(reason, detail, null);
    }

    private static List<AssetIdScheme> bareSchemes(AssetProvider target) {
        return switch (target) {
            case AWS -> List.of(AwsAssetIdScheme.INSTANCE);
            case GCP -> List.of(GcpAssetIdScheme.INSTANCE);
            case AZURE -> List.of(AzureAssetIdScheme.INSTANCE);
            case UNITY -> List.of(UnityAssetIdScheme.INSTANCE);
            case DATABRICKS -> List.of(DatabricksAssetIdScheme.INSTANCE, UnityAssetIdScheme.INSTANCE);
            case SNOWFLAKE -> List.of(SnowflakeAssetIdScheme.INSTANCE);
            case OCI -> List.of();
        };
    }

    private static AssetIdScheme schemeFor(AssetProvider provider) {
        return switch (provider) {
            case AWS -> AwsAssetIdScheme.INSTANCE;
            case GCP -> GcpAssetIdScheme.INSTANCE;
            case AZURE -> AzureAssetIdScheme.INSTANCE;
            case UNITY -> UnityAssetIdScheme.INSTANCE;
            case DATABRICKS -> DatabricksAssetIdScheme.INSTANCE;
            case SNOWFLAKE -> SnowflakeAssetIdScheme.INSTANCE;
            case OCI -> null;
        };
    }
}
