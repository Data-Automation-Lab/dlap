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

package com.dlap.logingest.config.sources;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.logingest.config.sources.ConfigProblem.Rule;
import com.dlap.logingest.config.sources.ConfigProblem.Severity;
import com.dlap.logingest.config.sources.LogSourcesProperties.Binding;
import com.dlap.logingest.config.sources.LogSourcesProperties.OperationOverride;
import com.dlap.logingest.config.sources.LogSourcesProperties.Scope;
import com.dlap.logingest.config.sources.LogSourcesProperties.Settings;
import com.dlap.logingest.config.sources.LogSourcesProperties.Source;
import com.dlap.logingest.config.sources.LogSourcesProperties.Stream;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.ClassificationRule;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.FormatNames;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.VersionMatch;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.boot.convert.DurationStyle;

/**
 * Validates the bindings file against the design B2 rules and resolves it into
 * {@link EffectiveBinding}s.
 *
 * <p>Rules: unique source, binding and stream ids; scope id syntax per provider (AWS 12-digit
 * account, GCP project id, Azure subscription GUID, Databricks numeric workspace); every pinned
 * version read by a registered parser on the stream's envelope (a pin no parser reads is an error;
 * a pin above the highest known minor is a warning, because such records parse as the highest
 * known); override keys that are canonical fields and values that are JSON pointers; each
 * transport registered and given its required location keys; each {@code credentials-ref} an
 * existing environment variable or a role ARN, never a value; no two streams on the same scope
 * unless one declares {@code dedup: cross-stream}. Around them: id syntax and reserved ids, known
 * source kinds, resolvable envelopes, valid enumerations, durations and sizes, the frozen
 * unknown-major rule, one set of parse settings per (binding, envelope), and no legacy source
 * running beside the registry pipeline.
 *
 * <p>Inheritance: stream, then binding, then source, then {@code defaults}, then the family's
 * mapping-YAML {@code settings}, then the contract defaults. {@code enabled: false} at any level
 * disables everything below it. {@code field-overrides} merge with the nearer level winning;
 * {@code operation-overrides} concatenate nearest first, so a nearer row wins a tie.
 *
 * <p>The validator is pure: it reads the properties and the context and never the database or the
 * network. Whether an error fails startup is decided by {@link LogSourcesConfiguration}.
 */
public final class LogSourceConfigValidator {

    /** How errors are treated at startup ({@code dlap.logingest.config-validation}). */
    public enum Mode {
        /** Fail under the {@code prod} profile, warn otherwise (design B2). */
        AUTO,
        /** Always fail on an error. */
        STRICT,
        /** Never fail; bindings with errors do not run. */
        WARN
    }

    /**
     * What the validator checks the file against.
     *
     * @param registry              the parser registry: envelopes and the versions parsers read
     * @param providersByKind       the provider each known source kind belongs to
     * @param specs                 the family mappings, for their settings and envelopes
     * @param transports            the registered transports
     * @param pipeline              the selected pipeline
     * @param enabledLegacySources  property names of legacy sources that are switched on
     * @param environment           reads an environment variable, {@code null} when unset
     * @param parserDescriptions    per envelope, what its parsers read, for messages; may be empty
     */
    public record Context(
            ParserRegistry registry,
            Map<String, SourceProvider> providersByKind,
            MappingSpecs specs,
            TransportCatalog transports,
            PipelineMode pipeline,
            List<String> enabledLegacySources,
            UnaryOperator<String> environment,
            Map<EnvelopeKey, List<String>> parserDescriptions) {

        /**
         * Normalizes absent parts.
         *
         * @param registry             see the record description
         * @param providersByKind      see the record description
         * @param specs                see the record description
         * @param transports           see the record description
         * @param pipeline             see the record description
         * @param enabledLegacySources see the record description
         * @param environment          see the record description
         * @param parserDescriptions   see the record description
         */
        public Context {
            Objects.requireNonNull(registry, "registry");
            providersByKind = providersByKind == null ? Map.of() : Map.copyOf(providersByKind);
            specs = specs == null ? MappingSpecs.empty() : specs;
            transports = transports == null ? TransportCatalog.none() : transports;
            pipeline = pipeline == null ? PipelineMode.DEFAULT : pipeline;
            enabledLegacySources = enabledLegacySources == null ? List.of() : List.copyOf(enabledLegacySources);
            environment = environment == null ? System::getenv : environment;
            parserDescriptions = parserDescriptions == null ? Map.of() : Map.copyOf(parserDescriptions);
        }
    }

    /**
     * The outcome of one run.
     *
     * @param bindings   the file's bindings, resolved; invalid ones are kept and marked
     * @param report     every finding
     * @param configMode the configuration mode, {@code FILE}
     * @param mode       how errors are treated at startup
     */
    public record Result(List<EffectiveBinding> bindings, ValidationReport report, String configMode, Mode mode) {

        /**
         * Freezes the result.
         *
         * @param bindings   see the record description
         * @param report     see the record description
         * @param configMode see the record description
         * @param mode       see the record description
         */
        public Result {
            bindings = List.copyOf(bindings);
        }
    }

    /** Transport ids: upper snake case, like source kinds. */
    static final Pattern TRANSPORT = FormatNames.SOURCE_KIND;

    private static final Pattern AWS_ACCOUNT = Pattern.compile("\\d{12}");
    private static final Pattern AWS_REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d{1,2}");
    private static final Pattern GCP_PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    private static final Pattern GUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATABRICKS_WORKSPACE = Pattern.compile("\\d{1,19}");
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern ROLE_ARN = Pattern.compile("arn:aws[a-z-]*:iam::\\d{12}:role/[A-Za-z0-9+=,.@_/-]{1,512}");

    /** Largest record size a binding may allow: 16 MiB, the mapping-YAML ceiling. */
    static final long MAX_RECORD_BYTES_CEILING = 16L * 1024 * 1024;

    /** Polling period when no level and no family sets one. */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMinutes(1);

    private final Context ctx;

    /**
     * Creates a validator.
     *
     * @param ctx what the file is checked against
     */
    public LogSourceConfigValidator(Context ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /**
     * Validates and resolves a bindings file.
     *
     * @param props the bound file
     * @return the resolved bindings and every finding
     */
    public Result validate(LogSourcesProperties props) {
        Run run = new Run();
        String configMode = upper(props.getConfigMode()).orElse("FILE");
        if ("FILE_SEEDS_DB".equals(configMode)) {
            run.error(Rule.CONFIG_MODE, "config-mode", null,
                    "config-mode FILE_SEEDS_DB is reserved; 0.x reads bindings from the file only");
        } else if (!"FILE".equals(configMode)) {
            run.error(Rule.INVALID_VALUE, "config-mode", null, "config-mode must be FILE");
            configMode = "FILE";
        }
        Mode mode = Mode.AUTO;
        Optional<String> modeText = upper(props.getConfigValidation());
        if (modeText.isPresent()) {
            try {
                mode = Mode.valueOf(modeText.get());
            } catch (IllegalArgumentException e) {
                run.warn(Rule.INVALID_VALUE, "config-validation", null,
                        "config-validation must be auto, strict or warn; using auto");
            }
        }
        if (ctx.pipeline() == PipelineMode.REGISTRY) {
            for (String legacy : ctx.enabledLegacySources()) {
                run.error(Rule.LEGACY_SOURCE_ON_REGISTRY, legacy, null, legacy + "=true runs a legacy source that "
                        + "bypasses the parser registry; switch it off when " + PipelineMode.PROPERTY + "=registry");
            }
        }
        Settings defaults = props.getDefaults() == null ? new Settings() : props.getDefaults();
        checkUnknownMajor(run, defaults, "defaults", null);

        Set<String> sourceIds = new HashSet<>();
        Map<String, String> bindingPaths = new HashMap<>();
        List<Draft> drafts = new ArrayList<>();
        List<Source> sources = props.getSources() == null ? List.of() : props.getSources();
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            if (source == null) {
                continue;
            }
            String sPath = "sources[" + label(source.getId(), i) + "]";
            checkId(run, source.getId(), sPath, "source id", null);
            if (source.getId() != null && !sourceIds.add(source.getId())) {
                run.error(Rule.DUPLICATE_ID, sPath, null, "source id " + source.getId() + " is used twice");
            }
            checkUnknownMajor(run, source, sPath, null);
            String kind = source.getKind();
            Optional<SourceProvider> provider = Optional.empty();
            Rule kindRule = null;
            String kindMessage = null;
            if (kind == null || !FormatNames.SOURCE_KIND.matcher(kind).matches()) {
                kindRule = Rule.INVALID_ID;
                kindMessage = "source kind must be upper snake case, for example AWS_CLOUDTRAIL";
            } else if (DlapNormalizedFormat.isFamilyKind(kind)) {
                kindRule = Rule.RESERVED_ID;
                kindMessage = "source kinds starting with " + DlapNormalizedFormat.SOURCE_KIND_PREFIX
                        + " are reserved for the built-in REST and replay channels";
            } else {
                provider = Optional.ofNullable(ctx.providersByKind().get(kind));
                if (provider.isEmpty()) {
                    kindRule = Rule.UNKNOWN_SOURCE_KIND;
                    kindMessage = "no registered parser or mapping reads source kind " + kind
                            + "; its family is not part of this build";
                }
            }
            boolean kindOk = kindRule == null;
            List<Binding> bindings = source.getBindings() == null ? List.of() : source.getBindings();
            if (!kindOk && bindings.isEmpty()) {
                run.error(kindRule, sPath + ".kind", null, kindMessage);
            }
            for (int j = 0; j < bindings.size(); j++) {
                Binding binding = bindings.get(j);
                if (binding == null) {
                    continue;
                }
                String bPath = sPath + ".bindings[" + label(binding.getId(), j) + "]";
                String bindingId = binding.getId();
                boolean idOk = checkId(run, bindingId, bPath, "binding id", null);
                boolean reserved = idOk && BuiltinBindings.isReservedId(bindingId);
                if (reserved) {
                    run.error(Rule.RESERVED_ID, bPath, bindingId, "binding ids starting with "
                            + BuiltinBindings.ID_PREFIX + " are reserved for the built-in channels");
                }
                boolean duplicate = idOk && bindingPaths.containsKey(bindingId);
                if (duplicate) {
                    run.error(Rule.DUPLICATE_ID, bPath, bindingId, "binding id " + bindingId + " is also used at "
                            + bindingPaths.get(bindingId));
                } else if (idOk) {
                    bindingPaths.put(bindingId, bPath);
                }
                String owner = idOk ? bindingId : null;
                if (!kindOk) {
                    run.error(kindRule, sPath + ".kind", owner, kindMessage);
                }
                checkUnknownMajor(run, binding, bPath, owner);
                Scope scope = binding.getScope() == null ? new Scope() : binding.getScope();
                provider.ifPresent(p -> checkScope(run, p, scope, bPath + ".scope", owner));
                List<Stream> streams = new ArrayList<>();
                if (binding.getStream() != null) {
                    streams.add(binding.getStream());
                }
                if (binding.getStreams() != null) {
                    binding.getStreams().stream().filter(Objects::nonNull).forEach(streams::add);
                }
                if (streams.isEmpty()) {
                    run.error(Rule.NO_STREAM, bPath, owner, "a binding needs a stream (stream: or streams:)");
                }
                if (!idOk || duplicate || reserved) {
                    // Not resolved: the id cannot name an effective binding (a reserved id would
                    // collide with the built-in binding of the same name).
                    continue;
                }
                boolean enabled = isEnabled(defaults, source, binding);
                List<EffectiveStream> resolved = new ArrayList<>();
                Set<String> streamIds = new HashSet<>();
                for (int k = 0; k < streams.size(); k++) {
                    Stream stream = streams.get(k);
                    String streamId = stream.getId() != null ? stream.getId() : defaultStreamId(stream.getTransport());
                    String tPath = bPath + ".streams[" + label(streamId, k) + "]";
                    if (checkId(run, streamId, tPath, "stream id", owner) && !streamIds.add(streamId)) {
                        run.error(Rule.DUPLICATE_ID, tPath, owner, "stream id " + streamId + " is used twice in binding "
                                + bindingId);
                    }
                    checkUnknownMajor(run, stream, tPath, owner);
                    Levels levels = new Levels(List.of(stream, binding, source, defaults));
                    Optional<EffectiveStream> s = resolveStream(run, levels, tPath, bindingId, streamId, kind, kindOk,
                            provider, scope);
                    // A stream with enabled: false is validated but not part of the effective binding.
                    if (!Boolean.FALSE.equals(stream.getEnabled())) {
                        s.ifPresent(resolved::add);
                    }
                }
                checkParseSettings(run, resolved, bPath, bindingId);
                drafts.add(new Draft(bindingId, source.getId() == null ? "" : source.getId(),
                        kind == null ? "" : kind, provider, enabled, scopeMap(scope), resolved, scope));
            }
        }
        checkOverlaps(run, drafts);

        ValidationReport preliminary = new ValidationReport(run.problems);
        Set<String> invalid = preliminary.invalidBindings();
        List<EffectiveBinding> out = new ArrayList<>();
        for (Draft d : drafts) {
            EffectiveBinding b = EffectiveBinding.of(d.id, d.sourceId, d.kind, d.provider, ConfigOrigin.FILE, d.enabled,
                    true, d.scopeMap, d.streams);
            out.add(invalid.contains(d.id) ? b.invalid() : b);
        }
        if (ctx.pipeline() == PipelineMode.LEGACY && out.stream().anyMatch(EffectiveBinding::enabled)) {
            run.warn(Rule.INACTIVE_ON_LEGACY, "sources", null, out.stream().filter(EffectiveBinding::enabled).count()
                    + " configured binding(s) do not run: " + PipelineMode.PROPERTY + "=legacy");
        }
        return new Result(out, new ValidationReport(run.problems), configMode, mode);
    }

    // ---------------------------------------------------------------------------------------------
    // Streams
    // ---------------------------------------------------------------------------------------------

    private Optional<EffectiveStream> resolveStream(Run run, Levels levels, String path, String bindingId,
                                                    String streamId, String kind, boolean kindOk,
                                                    Optional<SourceProvider> provider, Scope scope) {
        Stream stream = (Stream) levels.nearest();
        String transport = stream.getTransport();
        boolean transportOk = true;
        if (transport == null || !TRANSPORT.matcher(transport).matches()) {
            run.error(Rule.INVALID_ID, path + ".transport", bindingId, "transport must be upper snake case, for "
                    + "example S3_TRAIL_FILES");
            transportOk = false;
        } else if (BuiltinBindings.Channel.ofTransport(transport).isPresent()) {
            run.error(Rule.RESERVED_ID, path + ".transport", bindingId, "transport " + transport
                    + " is a built-in push channel");
            transportOk = false;
        } else {
            Optional<TransportCatalog.Requirements> req = ctx.transports().requirements(transport);
            if (req.isEmpty()) {
                run.error(Rule.UNKNOWN_TRANSPORT, path + ".transport", bindingId, "no transport " + transport
                        + " is registered in this build");
            } else {
                Map<String, String> location = stream.getLocation() == null ? Map.of() : stream.getLocation();
                for (String key : req.get().required()) {
                    String v = location.get(key);
                    if (v == null || v.isBlank()) {
                        run.error(Rule.MISSING_LOCATION, path + ".location." + key, bindingId, "transport " + transport
                                + " needs location key " + key);
                    }
                }
                for (String key : location.keySet()) {
                    if (!req.get().reads(key)) {
                        run.warn(Rule.UNKNOWN_LOCATION_KEY, path + ".location." + key, bindingId, "transport "
                                + transport + " does not read location key " + key);
                    }
                }
            }
        }

        String envelope = resolveEnvelope(run, levels, path, bindingId, kind, kindOk);
        Optional<MappingSpec.Settings> family = kindOk
                ? ctx.specs().forSourceKind(kind).map(MappingSpec::settings) : Optional.empty();

        Optional<String> pin = levels.first(l -> l.getFormat() == null ? null : l.getFormat().getVersion())
                .map(String::strip)
                .filter(v -> !BindingConfig.AUTO.equalsIgnoreCase(v));
        if (pin.isPresent() && !FormatNames.isVersion(pin.get())) {
            run.error(Rule.VERSION_SYNTAX, path + ".format.version", bindingId, "a pinned version must match "
                    + FormatNames.VERSION.pattern() + " and be at most " + FormatNames.MAX_VERSION_LENGTH
                    + " characters; quote it in YAML (\"1.10\")");
            pin = Optional.empty();
        }
        if (pin.isPresent() && envelope != null && kindOk) {
            EnvelopeKey key = new EnvelopeKey(kind, envelope);
            if (ctx.registry().envelopes().contains(key)) {
                Optional<VersionRange> range = ctx.registry().rangeFor(key, pin.get());
                if (range.isEmpty()) {
                    List<String> reads = ctx.parserDescriptions().getOrDefault(key, List.of());
                    run.error(Rule.PIN_NOT_READ, path + ".format.version", bindingId, "no registered parser reads "
                            + "version " + pin.get() + " of " + key + (reads.isEmpty() ? "" : "; parsers read " + reads));
                } else if (range.get().match(pin.get()).status() == VersionMatch.Status.NEWER_MINOR) {
                    run.warn(Rule.PIN_NEWER_MINOR, path + ".format.version", bindingId, "version " + pin.get()
                            + " is above the highest version the parser knows (" + range.get().describe()
                            + "); records parse as the highest known with PARSE_WARNING_NEWER_MINOR");
                }
            }
        }

        OnMismatch onMismatch = parseEnum(run, levels.first(l -> l.getFormat() != null
                        && l.getFormat().getOnMismatch() != null ? l.getFormat().getOnMismatch() : l.getOnMismatch()),
                OnMismatch.class, path + ".on-mismatch", bindingId)
                .orElseGet(() -> family.map(MappingSpec.Settings::onMismatch).orElse(OnMismatch.DEFAULT));

        PersistRaw.Policy rawPolicy = parseEnum(run, levels.first(Settings::getPersistRaw), PersistRaw.Policy.class,
                path + ".persist-raw", bindingId).orElseGet(() -> family.map(MappingSpec.Settings::persistRaw).orElse(null));
        int sampleOneIn = levels.first(Settings::getPersistRawSampleOneIn)
                .or(() -> family.map(MappingSpec.Settings::persistRawSampleOneIn))
                .orElse(PersistRaw.DEFAULT_SAMPLE_ONE_IN);
        if (sampleOneIn < 1) {
            run.error(Rule.INVALID_VALUE, path + ".persist-raw-sample-one-in", bindingId,
                    "persist-raw-sample-one-in must be at least 1");
            sampleOneIn = PersistRaw.DEFAULT_SAMPLE_ONE_IN;
        }
        long maxRecordBytes = levels.first(Settings::getMaxRecordBytes)
                .or(() -> family.map(MappingSpec.Settings::maxRecordBytes))
                .orElse(BindingConfig.DEFAULT_MAX_RECORD_BYTES);
        if (maxRecordBytes < 1 || maxRecordBytes > MAX_RECORD_BYTES_CEILING) {
            run.error(Rule.INVALID_VALUE, path + ".max-record-bytes", bindingId,
                    "max-record-bytes must be between 1 and " + MAX_RECORD_BYTES_CEILING);
            maxRecordBytes = BindingConfig.DEFAULT_MAX_RECORD_BYTES;
        }
        Duration overlap = parseDuration(run, levels.first(Settings::getOverlap), path + ".overlap", bindingId, true)
                .orElseGet(() -> family.map(MappingSpec.Settings::overlap).orElse(Duration.ZERO));
        Duration poll = parseDuration(run, levels.first(Settings::getPollInterval), path + ".poll-interval", bindingId,
                false).orElseGet(() -> family.map(MappingSpec.Settings::pollInterval).orElse(DEFAULT_POLL_INTERVAL));
        Double rps = levels.first(l -> l.getRateLimit() == null ? null : l.getRateLimit().getRequestsPerSecond())
                .orElse(null);
        if (rps != null && (rps.isNaN() || rps <= 0)) {
            run.error(Rule.INVALID_VALUE, path + ".rate-limit.requests-per-second", bindingId,
                    "rate-limit.requests-per-second must be positive");
            rps = null;
        }
        String credentialsRef = levels.first(Settings::getCredentialsRef).map(String::strip).orElse(null);
        checkCredentialsRef(run, credentialsRef, path + ".credentials-ref", bindingId);
        boolean backfill = levels.first(l -> l.getBackfill() == null ? null : l.getBackfill().getAllowed())
                .orElse(false);
        boolean crossStream = levels.first(Settings::getDedup)
                .map(d -> parseDedup(run, d, path + ".dedup", bindingId)).orElse(false);
        StreamFilters filters = new StreamFilters(
                filterList(run, levels.first(l -> l.getFilters() == null ? null : l.getFilters().getIncludeServices()),
                        path + ".filters.include-services", bindingId),
                filterList(run, levels.first(l -> l.getFilters() == null ? null : l.getFilters().getExcludeServices()),
                        path + ".filters.exclude-services", bindingId),
                filterList(run, levels.first(l -> l.getFilters() == null ? null : l.getFilters().getIncludeOperations()),
                        path + ".filters.include-operations", bindingId),
                filterList(run, levels.first(l -> l.getFilters() == null ? null : l.getFilters().getExcludeOperations()),
                        path + ".filters.exclude-operations", bindingId));
        Map<CanonicalField, List<FieldPath>> fieldOverrides = fieldOverrides(run, levels, path, bindingId);
        List<ClassificationRule> operationOverrides = operationOverrides(run, levels, path, bindingId);

        if (!kindOk || !transportOk) {
            return Optional.empty();
        }
        BindingConfig parse;
        try {
            parse = new BindingConfig(bindingId, kind, pin, onMismatch, fieldOverrides, operationOverrides,
                    new PersistRaw(rawPolicy, sampleOneIn), maxRecordBytes, hints(provider, scope));
        } catch (IllegalArgumentException e) {
            run.error(Rule.INVALID_VALUE, path, bindingId, "the stream's parse settings are invalid: " + e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new EffectiveStream(bindingId, streamId, transport, envelope,
                stream.getLocation(), parse, filters, overlap, poll, rps, credentialsRef, backfill, crossStream, false));
    }

    private String resolveEnvelope(Run run, Levels levels, String path, String bindingId, String kind, boolean kindOk) {
        Optional<String> explicit = levels.first(l -> l.getFormat() == null ? null : l.getFormat().getEnvelope())
                .map(String::strip);
        if (!kindOk) {
            return explicit.orElse(null);
        }
        if (explicit.isEmpty()) {
            List<String> known = ctx.registry().envelopes().stream()
                    .filter(k -> k.sourceKind().equals(kind)).map(EnvelopeKey::envelope).sorted().toList();
            if (known.size() == 1) {
                return known.get(0);
            }
            run.error(Rule.ENVELOPE_UNRESOLVED, path + ".format.envelope", bindingId, "set format.envelope; source "
                    + "kind " + kind + " has envelopes " + known);
            return null;
        }
        String envelope = explicit.get();
        if (!FormatNames.ENVELOPE.matcher(envelope).matches()) {
            run.error(Rule.INVALID_VALUE, path + ".format.envelope", bindingId, "an envelope id is lower kebab case");
            return null;
        }
        if (!ctx.registry().envelopes().contains(new EnvelopeKey(kind, envelope))) {
            run.error(Rule.UNKNOWN_ENVELOPE, path + ".format.envelope", bindingId, "no detector is registered for "
                    + kind + "/" + envelope);
        }
        return envelope;
    }

    private void checkParseSettings(Run run, List<EffectiveStream> streams, String path, String bindingId) {
        Map<String, EffectiveStream> byEnvelope = new HashMap<>();
        for (EffectiveStream s : streams) {
            if (s.envelope() == null) {
                continue;
            }
            EffectiveStream other = byEnvelope.putIfAbsent(s.envelope(), s);
            if (other != null && (!other.parse().equals(s.parse()) || !other.filters().equals(s.filters()))) {
                run.error(Rule.PARSE_SETTINGS_CONFLICT, path, bindingId, "streams " + other.streamId() + " and "
                        + s.streamId() + " deliver envelope " + s.envelope() + " with different format, override, "
                        + "filter or raw-record settings; one binding parses an envelope one way");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scope, credentials, overlap
    // ---------------------------------------------------------------------------------------------

    private static void checkScope(Run run, SourceProvider provider, Scope s, String path, String bindingId) {
        switch (provider) {
            case AWS -> {
                require(run, s.getAccountId(), AWS_ACCOUNT, path + ".account-id", bindingId, "account-id",
                        "an AWS account id is 12 digits (quote it in YAML)");
                if (s.getRegions() != null) {
                    for (String r : s.getRegions()) {
                        if (r == null || !AWS_REGION.matcher(r).matches()) {
                            run.error(Rule.SCOPE_SYNTAX, path + ".regions", bindingId,
                                    "an AWS region looks like us-east-1");
                        }
                    }
                }
            }
            case GCP -> require(run, s.getProjectId(), GCP_PROJECT, path + ".project-id", bindingId, "project-id",
                    "a GCP project id is 6 to 30 lowercase letters, digits and hyphens, starting with a letter");
            case AZURE -> require(run, s.getSubscriptionId(), GUID, path + ".subscription-id", bindingId,
                    "subscription-id", "an Azure subscription id is a GUID");
            case DATABRICKS -> {
                boolean workspace = notBlank(s.getWorkspaceId());
                boolean account = notBlank(s.getAccountId());
                if (!workspace && !account) {
                    run.error(Rule.SCOPE_MISSING, path, bindingId, "a Databricks binding needs workspace-id or "
                            + "account-id");
                }
                if (workspace && !DATABRICKS_WORKSPACE.matcher(s.getWorkspaceId()).matches()) {
                    run.error(Rule.SCOPE_SYNTAX, path + ".workspace-id", bindingId,
                            "a Databricks workspace id is numeric");
                }
                if (account && !GUID.matcher(s.getAccountId()).matches()) {
                    run.error(Rule.SCOPE_SYNTAX, path + ".account-id", bindingId,
                            "a Databricks account id is a UUID");
                }
            }
            case UNITY -> require(run, s.getMetastoreId(), PLAIN_ID, path + ".metastore-id", bindingId,
                    "metastore-id", "a Unity Catalog metastore id is letters, digits, dots, underscores and hyphens");
            case SNOWFLAKE -> require(run, s.getAccountId(), PLAIN_ID, path + ".account-id", bindingId, "account-id",
                    "a Snowflake account identifier is letters, digits, dots, underscores and hyphens");
            default -> {
                if (primaryScope(provider, s).isEmpty()) {
                    run.error(Rule.SCOPE_MISSING, path, bindingId, "the binding needs a scope");
                }
            }
        }
    }

    private static void require(Run run, String value, Pattern syntax, String path, String bindingId, String key,
                                String hint) {
        if (!notBlank(value)) {
            run.error(Rule.SCOPE_MISSING, path, bindingId, "scope needs " + key);
        } else if (!syntax.matcher(value).matches()) {
            run.error(Rule.SCOPE_SYNTAX, path, bindingId, hint);
        }
    }

    private void checkCredentialsRef(Run run, String ref, String path, String bindingId) {
        if (ref == null || ref.isEmpty()) {
            return;
        }
        if (ref.startsWith("arn:")) {
            if (!ROLE_ARN.matcher(ref).matches()) {
                run.error(Rule.CREDENTIALS_REF_SYNTAX, path, bindingId, "a credentials-ref ARN must be an IAM role ARN");
            }
            return;
        }
        String name = ref.startsWith("env:") ? ref.substring(4) : ref;
        if (!ENV_NAME.matcher(name).matches()) {
            // Never echo the value: it may be the secret itself.
            run.error(Rule.CREDENTIALS_REF_SYNTAX, path, bindingId, "credentials-ref must name an environment "
                    + "variable or an IAM role ARN, never hold a secret value");
            return;
        }
        String value = ctx.environment().apply(name);
        if (value == null || value.isEmpty()) {
            run.error(Rule.CREDENTIALS_REF_MISSING, path, bindingId, "environment variable " + name + " is not set");
        }
    }

    private static void checkOverlaps(Run run, List<Draft> drafts) {
        record Ref(Draft binding, String streamId, boolean dedup) {
        }
        List<Ref> refs = new ArrayList<>();
        for (Draft d : drafts) {
            if (!d.enabled || d.provider.isEmpty()) {
                continue;
            }
            for (EffectiveStream s : d.streams) {
                refs.add(new Ref(d, s.streamId(), s.crossStreamDedup()));
            }
        }
        for (int i = 0; i < refs.size(); i++) {
            for (int j = i + 1; j < refs.size(); j++) {
                Ref a = refs.get(i);
                Ref b = refs.get(j);
                if (!a.binding.kind.equals(b.binding.kind) || a.dedup || b.dedup) {
                    continue;
                }
                Optional<String> sa = primaryScope(a.binding.provider.get(), a.binding.scope);
                Optional<String> sb = primaryScope(b.binding.provider.get(), b.binding.scope);
                if (sa.isEmpty() || !sa.equals(sb) || !regionsIntersect(a.binding.scope, b.binding.scope)) {
                    continue;
                }
                String msg = "streams " + a.binding.id + "/" + a.streamId + " and " + b.binding.id + "/" + b.streamId
                        + " read the same " + a.binding.kind + " scope; declare dedup: cross-stream on one of them "
                        + "if both are intended";
                run.error(Rule.OVERLAPPING_STREAMS, "bindings[" + a.binding.id + "]", a.binding.id, msg);
                if (!a.binding.id.equals(b.binding.id)) {
                    run.error(Rule.OVERLAPPING_STREAMS, "bindings[" + b.binding.id + "]", b.binding.id, msg);
                }
            }
        }
    }

    private static boolean regionsIntersect(Scope a, Scope b) {
        List<String> ra = a.getRegions() == null ? List.of() : a.getRegions();
        List<String> rb = b.getRegions() == null ? List.of() : b.getRegions();
        if (ra.isEmpty() || rb.isEmpty()) {
            return true;
        }
        return ra.stream().anyMatch(rb::contains);
    }

    /**
     * The scope value that identifies what a binding reads, for the overlap rule.
     *
     * @param provider the provider
     * @param s        the scope
     * @return the identifying value, or empty when the scope has none
     */
    static Optional<String> primaryScope(SourceProvider provider, Scope s) {
        String v = switch (provider) {
            case AWS, SNOWFLAKE -> s.getAccountId();
            case GCP -> s.getProjectId();
            case AZURE -> notBlank(s.getStorageAccount())
                    ? s.getSubscriptionId() + "/" + s.getStorageAccount() : s.getSubscriptionId();
            case DATABRICKS -> notBlank(s.getWorkspaceId()) ? s.getWorkspaceId() : s.getAccountId();
            case UNITY -> s.getMetastoreId();
            default -> firstNonBlank(s.getAccountId(), s.getProjectId(), s.getSubscriptionId(), s.getWorkspaceId(),
                    s.getMetastoreId());
        };
        return notBlank(v) ? Optional.of(v) : Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------
    // Values
    // ---------------------------------------------------------------------------------------------

    private static void checkUnknownMajor(Run run, Settings s, String path, String bindingId) {
        if (s == null || s.getOnUnknownMajor() == null) {
            return;
        }
        if (!"QUARANTINE".equals(upper(s.getOnUnknownMajor()).orElse(""))) {
            run.error(Rule.UNKNOWN_MAJOR_POLICY, path + ".on-unknown-major", bindingId, "on-unknown-major can only "
                    + "be QUARANTINE: a version no parser reads is always quarantined (ADR-0054)");
        }
    }

    private static boolean checkId(Run run, String id, String path, String what, String bindingId) {
        if (id == null || !FormatNames.BINDING_ID.matcher(id).matches()) {
            run.error(Rule.INVALID_ID, path, bindingId, what + " must match " + FormatNames.BINDING_ID.pattern());
            return false;
        }
        return true;
    }

    private static <E extends Enum<E>> Optional<E> parseEnum(Run run, Optional<String> text, Class<E> type,
                                                             String path, String bindingId) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String v = text.get().strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Optional.of(Enum.valueOf(type, v));
        } catch (IllegalArgumentException e) {
            List<String> allowed = java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
            run.error(Rule.INVALID_VALUE, path, bindingId, "must be one of " + allowed);
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseDuration(Run run, Optional<String> text, String path, String bindingId,
                                                    boolean zeroAllowed) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Duration d;
        try {
            d = DurationStyle.detectAndParse(text.get().strip());
        } catch (IllegalArgumentException e) {
            run.error(Rule.INVALID_VALUE, path, bindingId, "must be a duration such as PT20M or 20m");
            return Optional.empty();
        }
        if (d.isNegative() || (!zeroAllowed && d.isZero())) {
            run.error(Rule.INVALID_VALUE, path, bindingId, zeroAllowed ? "must not be negative" : "must be positive");
            return Optional.empty();
        }
        return Optional.of(d);
    }

    private static boolean parseDedup(Run run, String text, String path, String bindingId) {
        String v = text.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (v) {
            case "CROSS_STREAM" -> true;
            case "NONE" -> false;
            default -> {
                run.error(Rule.INVALID_VALUE, path, bindingId, "dedup must be none or cross-stream");
                yield false;
            }
        };
    }

    private static List<String> filterList(Run run, Optional<List<String>> list, String path, String bindingId) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String v : list.get()) {
            if (v == null || v.isBlank()) {
                run.error(Rule.INVALID_VALUE, path, bindingId, "filter entries must not be blank");
            } else {
                out.add(v.strip());
            }
        }
        return out;
    }

    private static Map<CanonicalField, List<FieldPath>> fieldOverrides(Run run, Levels levels, String path,
                                                                       String bindingId) {
        Map<String, String> merged = new LinkedHashMap<>();
        List<Settings> farToNear = new ArrayList<>(levels.all());
        java.util.Collections.reverse(farToNear);
        for (Settings l : farToNear) {
            if (l.getFieldOverrides() != null) {
                merged.putAll(l.getFieldOverrides());
            }
        }
        Map<CanonicalField, List<FieldPath>> out = new EnumMap<>(CanonicalField.class);
        merged.forEach((key, pointer) -> {
            Optional<CanonicalField> field = CanonicalField.fromKey(key);
            if (field.isEmpty()) {
                run.error(Rule.FIELD_OVERRIDE_KEY, path + ".field-overrides." + key, bindingId, key
                        + " is not a canonical field; known: " + java.util.Arrays.stream(CanonicalField.values())
                        .map(CanonicalField::key).toList());
                return;
            }
            if (pointer == null || pointer.isEmpty() || !FieldPath.isValid(pointer)) {
                run.error(Rule.FIELD_OVERRIDE_POINTER, path + ".field-overrides." + key, bindingId,
                        "a field override is an RFC 6901 JSON pointer such as /protoPayload/authenticationInfo");
                return;
            }
            out.put(field.get(), List.of(FieldPath.of(pointer)));
        });
        return out;
    }

    private static List<ClassificationRule> operationOverrides(Run run, Levels levels, String path, String bindingId) {
        List<ClassificationRule> out = new ArrayList<>();
        for (Settings l : levels.all()) {
            List<OperationOverride> rows = l.getOperationOverrides() == null ? List.of() : l.getOperationOverrides();
            for (int i = 0; i < rows.size(); i++) {
                OperationOverride o = rows.get(i);
                String rPath = path + ".operation-overrides[" + i + "]";
                if (o == null) {
                    continue;
                }
                try {
                    ChangeType changeType = ChangeType.valueOf(upper(o.getChangeType()).orElse("")
                            .replace('-', '_'));
                    String cat = upper(o.getCategory()).orElse("").replace('-', '_');
                    EventCategory category = EventCategory.valueOf(cat.startsWith("EVENT_CATEGORY_")
                            ? cat : "EVENT_CATEGORY_" + cat);
                    AssetKind kind = o.getAssetKind() == null || o.getAssetKind().isBlank()
                            ? null : AssetKind.valueOf(o.getAssetKind().strip().toUpperCase(Locale.ROOT));
                    out.add(new ClassificationRule(o.getService(), o.getOperation(), o.getOperationPrefix(), changeType,
                            category, kind, o.getExtractor()));
                } catch (IllegalArgumentException | NullPointerException e) {
                    run.error(Rule.OPERATION_OVERRIDE, rPath, bindingId, "an operation override needs service, "
                            + "exactly one of operation and operation-prefix, a change-type (CREATE, UPDATE, DELETE, "
                            + "ACCESS, PERMISSION, ARCHIVE), a category (MANAGEMENT, DATA_READ, DATA_WRITE, PERMISSION, "
                            + "DATA_READ_OBSERVED) and optionally a known asset-kind");
                }
            }
        }
        return out;
    }

    private static AssetIdHints hints(Optional<SourceProvider> provider, Scope s) {
        AssetIdHints.Builder h = AssetIdHints.builder();
        provider.ifPresent(p -> {
            h.provider(AssetProvider.valueOf(p.name()));
            switch (p) {
                case AWS, SNOWFLAKE -> h.account(s.getAccountId());
                case AZURE -> h.account(s.getStorageAccount());
                default -> {
                    // No account hint for the other providers.
                }
            }
        });
        h.project(s.getProjectId());
        if (s.getRegions() != null && s.getRegions().size() == 1) {
            h.region(s.getRegions().get(0));
        }
        h.subscription(s.getSubscriptionId());
        h.resourceGroup(s.getResourceGroup());
        h.metastoreId(s.getMetastoreId());
        h.workspaceId(s.getWorkspaceId());
        return h.build();
    }

    private static Map<String, Object> scopeMap(Scope s) {
        Map<String, Object> m = new TreeMap<>();
        putIfSet(m, "account-id", s.getAccountId());
        putIfSet(m, "project-id", s.getProjectId());
        putIfSet(m, "subscription-id", s.getSubscriptionId());
        putIfSet(m, "resource-group", s.getResourceGroup());
        putIfSet(m, "storage-account", s.getStorageAccount());
        putIfSet(m, "workspace-id", s.getWorkspaceId());
        putIfSet(m, "metastore-id", s.getMetastoreId());
        if (s.getRegions() != null && !s.getRegions().isEmpty()) {
            m.put("regions", List.copyOf(s.getRegions()));
        }
        return m;
    }

    private static boolean isEnabled(Settings... levels) {
        for (Settings l : levels) {
            if (l != null && Boolean.FALSE.equals(l.getEnabled())) {
                return false;
            }
        }
        return true;
    }

    private static String defaultStreamId(String transport) {
        return transport == null ? null : transport.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String label(String id, int index) {
        return id == null || id.isBlank() ? String.valueOf(index) : id;
    }

    private static Optional<String> upper(String s) {
        return s == null || s.isBlank() ? Optional.empty() : Optional.of(s.strip().toUpperCase(Locale.ROOT));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static void putIfSet(Map<String, Object> m, String key, String value) {
        if (notBlank(value)) {
            m.put(key, value.strip());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** The levels of one stream, nearest first. */
    private record Levels(List<Settings> all) {

        Settings nearest() {
            return all.get(0);
        }

        <T> Optional<T> first(Function<Settings, T> getter) {
            for (Settings l : all) {
                if (l == null) {
                    continue;
                }
                T v = getter.apply(l);
                if (v instanceof String s && s.isBlank()) {
                    continue;
                }
                if (v != null) {
                    return Optional.of(v);
                }
            }
            return Optional.empty();
        }
    }

    /** A binding resolved enough for the overlap rule and the final assembly. */
    private record Draft(String id, String sourceId, String kind, Optional<SourceProvider> provider, boolean enabled,
                         Map<String, Object> scopeMap, List<EffectiveStream> streams, Scope scope) {
    }

    /** Collects findings. */
    private static final class Run {
        private final List<ConfigProblem> problems = new ArrayList<>();

        void error(Rule rule, String path, String bindingId, String message) {
            problems.add(new ConfigProblem(Severity.ERROR, rule, path, bindingId, message));
        }

        void warn(Rule rule, String path, String bindingId, String message) {
            problems.add(new ConfigProblem(Severity.WARNING, rule, path, bindingId, message));
        }
    }
}
