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
import com.dlap.common.assetid.AssetProvider;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The bindings the registry pipeline defines itself: one per provider and push channel, for
 * records that arrive already normalized (ADR-0060).
 *
 * <p>Ids are {@code dlap-<channel>-<provider>} ({@code dlap-rest-aws}, {@code dlap-replay-gcp});
 * the {@code dlap-} prefix and the {@code DLAP_NORMALIZED_} source kinds are reserved, so the
 * bindings file cannot shadow them. On the wire, {@code binding_id} therefore tells a REST push
 * and a fixture replay apart from provider logs (threat B7-S2). They keep every raw record
 * ({@code persist-raw: ALL}), as the legacy pipeline did for these paths.
 */
public final class BuiltinBindings {

    /** Prefix of every built-in binding id; reserved. */
    public static final String ID_PREFIX = "dlap-";

    private BuiltinBindings() {
    }

    /** A push channel of normalized records. */
    public enum Channel {

        /** {@code POST /api/v1/logingest/events}. Records stream in; stored ids are not republished. */
        REST("rest", "REST", IngestMode.STREAM),

        /** The offline fixture replay ({@code FixtureReplaySeeder}). Origin {@code ORIGIN_REPLAY}. */
        FIXTURE_REPLAY("replay", "FIXTURE_REPLAY", IngestMode.REPLAY);

        private final String id;
        private final String transport;
        private final IngestMode mode;

        Channel(String id, String transport, IngestMode mode) {
            this.id = id;
            this.transport = transport;
            this.mode = mode;
        }

        /**
         * The channel id, used in binding ids and as the stream id.
         *
         * @return for example {@code rest}
         */
        public String id() {
            return id;
        }

        /**
         * The transport name recorded in {@code RawRef}; reserved for this channel.
         *
         * @return for example {@code REST}
         */
        public String transport() {
            return transport;
        }

        /**
         * The ingest mode of the channel's records.
         *
         * @return the mode
         */
        public IngestMode mode() {
            return mode;
        }

        /**
         * The binding of one provider's records on this channel.
         *
         * @param provider the provider
         * @return for example {@code dlap-rest-aws}
         */
        public String bindingId(SourceProvider provider) {
            return ID_PREFIX + id + "-" + provider.name().toLowerCase(Locale.ROOT);
        }

        /**
         * Finds the channel of a reserved transport name.
         *
         * @param transport a transport id
         * @return the channel, or empty
         */
        public static Optional<Channel> ofTransport(String transport) {
            for (Channel c : values()) {
                if (c.transport.equals(transport)) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Every built-in binding: one per channel and provider.
     *
     * @return the bindings, sorted by id
     */
    public static List<EffectiveBinding> all() {
        List<EffectiveBinding> out = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            for (SourceProvider provider : DlapNormalizedFormat.providers()) {
                out.add(binding(channel, provider));
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    /**
     * The built-in binding of one channel and provider.
     *
     * @param channel  the channel
     * @param provider the provider
     * @return the binding
     */
    public static EffectiveBinding binding(Channel channel, SourceProvider provider) {
        String id = channel.bindingId(provider);
        String kind = DlapNormalizedFormat.sourceKind(provider);
        BindingConfig parse = new BindingConfig(id, kind, Optional.empty(), OnMismatch.DEFAULT, Map.of(), List.of(),
                PersistRaw.of(PersistRaw.Policy.ALL), BindingConfig.DEFAULT_MAX_RECORD_BYTES,
                AssetIdHints.of(AssetProvider.valueOf(provider.name())));
        EffectiveStream stream = new EffectiveStream(id, channel.id(), channel.transport(), DlapNormalizedFormat.ENVELOPE,
                Map.of(), parse, StreamFilters.NONE, Duration.ZERO, Duration.ZERO, null, null, false, false, true);
        return EffectiveBinding.of(id, ID_PREFIX + channel.id(), kind, Optional.of(provider), ConfigOrigin.BUILTIN,
                true, true, Map.of(), List.of(stream));
    }

    /**
     * Whether an id is reserved for a built-in binding.
     *
     * @param bindingId an id
     * @return true for ids starting with {@value #ID_PREFIX}
     */
    public static boolean isReservedId(String bindingId) {
        return bindingId != null && bindingId.toLowerCase(Locale.ROOT).startsWith(ID_PREFIX);
    }
}
