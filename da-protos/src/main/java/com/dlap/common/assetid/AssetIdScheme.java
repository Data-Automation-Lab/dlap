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

import java.util.List;
import java.util.Optional;

/**
 * One provider's identifier rules. Package-private on purpose: callers go through
 * {@link AssetIdNormalizer} so there is a single entry point.
 */
interface AssetIdScheme {

    /**
     * Parses a form that names its own provider (a URI scheme, an ARN, an ARM ID, a resource
     * name with a fixed prefix). Hints may refine but never change the provider.
     *
     * @param value the pre-processed input
     * @param hints the hints
     * @return a result when this scheme claims the form, else empty
     */
    Optional<AssetIdParseResult> parseSelfDescribing(String value, AssetIdHints hints);

    /**
     * Parses a form that needs the hints to be read at all (a bare bucket name, a dotted table
     * name). Called only when the hints point at this scheme's provider.
     *
     * @param value the pre-processed input
     * @param hints the hints
     * @return a result when this scheme claims the form, else empty
     */
    Optional<AssetIdParseResult> parseBare(String value, AssetIdHints hints);

    /**
     * Builds a canonical ID from identity parts in the {@link AssetKind} layout.
     *
     * @param kind  the kind
     * @param parts the identity parts
     * @param hints hints for kinds that need them
     * @return the result, or empty when this scheme does not produce the kind
     */
    Optional<AssetIdParseResult> build(AssetKind kind, List<String> parts, AssetIdHints hints);
}
