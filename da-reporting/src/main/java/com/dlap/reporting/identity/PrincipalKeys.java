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

package com.dlap.reporting.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normal form of a cloud principal id, the key of the principal identity map.
 *
 * <p>The rules, applied in order (enforcement-contract.md section 6.1):
 * <ol>
 *   <li>Surrounding white space is removed.</li>
 *   <li>A GCP IAM member prefix ({@code user:}, {@code serviceAccount:}, {@code group:}, any
 *       case) is removed, so {@code serviceAccount:a@p.iam.gserviceaccount.com} and the bare email
 *       the audit log carries are one key.</li>
 *   <li>An email address is lower-cased (the domain is case-insensitive and the providers treat
 *       the local part the same way).</li>
 *   <li>A GUID (an Entra object id) is lower-cased.</li>
 *   <li>Anything else (an ARN, a {@code principal://} subject) is kept as written.</li>
 * </ol>
 *
 * <p>An AWS assumed-role session ARN also yields the role ARN as a second lookup candidate, so one
 * map entry for the role covers every session of it.
 */
public final class PrincipalKeys {

    /** The longest key the map stores ({@code principal_identity_map.principal_key}). */
    public static final int MAX_LENGTH = 512;

    private static final List<String> MEMBER_PREFIXES = List.of("user:", "serviceaccount:", "group:");

    private static final Pattern EMAIL = Pattern.compile("[^\\s@/:]+@[^\\s@/:]+");

    private static final Pattern GUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** {@code arn:<partition>:sts::<account>:assumed-role/<role>/<session>}. */
    private static final Pattern ASSUMED_ROLE =
            Pattern.compile("arn:([^:]+):sts::([0-9]+):assumed-role/([^/]+)/.+");

    private PrincipalKeys() {
    }

    /**
     * The normal form of a principal id.
     *
     * @param raw the principal id as a log event or a seed file names it; may be {@code null}
     * @return the key, or an empty string when {@code raw} is blank
     */
    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.strip();
        String lower = id.toLowerCase(Locale.ROOT);
        for (String prefix : MEMBER_PREFIXES) {
            if (lower.startsWith(prefix)) {
                id = id.substring(prefix.length()).strip();
                break;
            }
        }
        if (EMAIL.matcher(id).matches() || GUID.matcher(id).matches()) {
            return id.toLowerCase(Locale.ROOT);
        }
        return id;
    }

    /**
     * The keys to look a principal up by, most specific first: the key itself and, for an AWS
     * assumed-role session ARN, the ARN of the role.
     *
     * @param raw the principal id; may be {@code null}
     * @return the candidates; empty when {@code raw} is blank
     */
    public static List<String> candidates(String raw) {
        String key = key(raw);
        List<String> out = new ArrayList<>(2);
        if (key.isEmpty()) {
            return out;
        }
        out.add(key);
        Matcher m = ASSUMED_ROLE.matcher(key);
        if (m.matches()) {
            out.add("arn:" + m.group(1) + ":iam::" + m.group(2) + ":role/" + m.group(3));
        }
        return out;
    }
}
