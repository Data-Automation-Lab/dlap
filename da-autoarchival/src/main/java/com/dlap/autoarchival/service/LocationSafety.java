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
package com.dlap.autoarchival.service;

import java.util.Locale;

/**
 * Checks the retire path applies to a location before it is parsed, shared by the location
 * resolver and the archive providers so the rules cannot drift apart.
 *
 * <p>{@code AssetIdNormalizer} drops the query string and fragment of an {@code http(s)} URL so a
 * SAS token never reaches an identifier. That is right for identity and wrong for an archive or
 * restore: a query can select an object version, a GCS generation or an Azure snapshot, and an
 * action that ignored it would act on the whole object. These helpers let the retire path refuse
 * such a URL (fail closed) and keep signatures out of logs and error messages.
 *
 * <p>Thread-safe and stateless.
 */
public final class LocationSafety {

    private LocationSafety() {
    }

    /**
     * Whether the location is an {@code http(s)} URL with a query string or fragment. A
     * {@code ?} or {@code #} inside an {@code s3://} or {@code gs://} key is part of the key and
     * does not count.
     *
     * @param location the location; may be {@code null}
     * @return true when it is a URL that carries a query or a fragment
     */
    public static boolean carriesUrlSelector(String location) {
        if (location == null) {
            return false;
        }
        String v = location.strip();
        if (v.startsWith("\"")) {
            v = v.substring(1);
        }
        v = v.toLowerCase(Locale.ROOT);
        return (v.startsWith("https://") || v.startsWith("http://"))
                && (v.indexOf('?') >= 0 || v.indexOf('#') >= 0);
    }

    /**
     * Strips the query string and fragment from an {@code http(s)} URL before it is logged or
     * returned in an error, so a SAS token or signature never reaches a log line. Input that is
     * not a URL is returned unchanged.
     *
     * @param location the location; may be {@code null}
     * @return the location safe to log
     */
    public static String redact(String location) {
        if (location == null) {
            return null;
        }
        String lower = location.strip().toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("\"http"))) {
            return location;
        }
        int cut = location.length();
        int q = location.indexOf('?');
        int h = location.indexOf('#');
        if (q >= 0) {
            cut = q;
        }
        if (h >= 0) {
            cut = Math.min(cut, h);
        }
        return location.substring(0, cut);
    }

    /**
     * Whether an object key has leading or trailing whitespace. Such a key is ambiguous: trimming
     * it would act on another object, keeping it usually misses. The retire path refuses it rather
     * than guess.
     *
     * @param key the object key; may be {@code null}
     * @return true when the key is non-null and differs from its stripped form
     */
    public static boolean hasEdgeWhitespace(String key) {
        return key != null && !key.equals(key.strip());
    }
}
