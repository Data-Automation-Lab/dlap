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

package com.dlap.logingest.parse.core.mapping;

import com.dlap.logingest.parse.core.VersionDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every loaded family mapping, by family and by source kind. One family per source kind and one
 * source kind per family; anything else is refused at startup.
 */
public final class MappingSpecs {

    private final Map<String, MappingSpec> byFamily;
    private final Map<String, MappingSpec> bySourceKind;

    /**
     * Indexes the specs.
     *
     * @param specs the specs
     * @throws IllegalStateException when two specs share a family or a source kind
     */
    public MappingSpecs(Collection<MappingSpec> specs) {
        Map<String, MappingSpec> f = new TreeMap<>();
        Map<String, MappingSpec> k = new TreeMap<>();
        for (MappingSpec s : specs) {
            if (f.putIfAbsent(s.family(), s) != null) {
                throw new IllegalStateException("two mappings for family " + s.family());
            }
            MappingSpec other = k.putIfAbsent(s.sourceKind(), s);
            if (other != null) {
                throw new IllegalStateException("families " + other.family() + " and " + s.family()
                        + " both map source kind " + s.sourceKind());
            }
        }
        this.byFamily = Collections.unmodifiableMap(f);
        this.bySourceKind = Collections.unmodifiableMap(k);
    }

    /**
     * No mappings.
     *
     * @return an empty set
     */
    public static MappingSpecs empty() {
        return new MappingSpecs(List.of());
    }

    /**
     * Finds a family.
     *
     * @param family the family id
     * @return the spec, or empty
     */
    public Optional<MappingSpec> family(String family) {
        return Optional.ofNullable(byFamily.get(family));
    }

    /**
     * Finds the family of a source kind.
     *
     * @param sourceKind the source kind
     * @return the spec, or empty
     */
    public Optional<MappingSpec> forSourceKind(String sourceKind) {
        return Optional.ofNullable(bySourceKind.get(sourceKind));
    }

    /**
     * Returns every spec.
     *
     * @return the specs, sorted by family
     */
    public List<MappingSpec> all() {
        return List.copyOf(byFamily.values());
    }

    /**
     * Builds one declarative detector per envelope of every spec.
     *
     * @return the detectors
     */
    public List<VersionDetector> detectors() {
        List<VersionDetector> out = new ArrayList<>();
        for (MappingSpec spec : byFamily.values()) {
            for (MappingSpec.Envelope e : spec.envelopes()) {
                out.add(new DeclarativeVersionDetector(spec.keyOf(e), e.detection()));
            }
        }
        return List.copyOf(out);
    }
}
