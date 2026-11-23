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

package com.dlap.logingest.parse.core;

import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one shared fallback for operations a family's classification table does not list (design B1:
 * "Unlisted operations fall back to the existing verb heuristics"). Every result is flagged
 * heuristic, so the event carries {@code PARSE_WARNING_HEURISTIC_CLASSIFICATION} and reports can
 * mark it.
 *
 * <p>The verb segment is the last part of the operation after {@code .} or {@code /}
 * ({@code GetObject}, {@code storage.objects.get}, {@code TableService.DeleteTable}), split into
 * words at case changes, digits, {@code _} and {@code -} and lower-cased: {@code setIamPermissions}
 * gives {@code [set, iam, permissions]}. Rules, first match wins:
 * <ol>
 *   <li>the first word is a metadata-read verb ({@code list}, {@code describe}, {@code lookup},
 *       {@code search}): {@code ACCESS}, {@code MANAGEMENT};</li>
 *   <li>the first word is a content-read verb ({@code get}, {@code read}, {@code head},
 *       {@code download}, {@code select}, {@code query}, {@code scan}, or {@code batch get}) and
 *       any word is a permission noun ({@code policy}, {@code permission}, {@code acl},
 *       {@code grant}, {@code iam}, and their plurals): {@code ACCESS}, {@code MANAGEMENT};</li>
 *   <li>the first word is a content-read verb: {@code ACCESS}, {@code DATA_READ};</li>
 *   <li>any word is a permission word (the permission nouns and {@code revoke}):
 *       {@code PERMISSION}, {@code PERMISSION};</li>
 *   <li>the first word is a create verb ({@code create}, {@code insert}, {@code put}, {@code run},
 *       {@code upload}, {@code copy}, {@code import}): {@code CREATE}, {@code MANAGEMENT};</li>
 *   <li>the first word is a delete verb ({@code delete}, {@code remove}, {@code terminate},
 *       {@code drop}, {@code purge}): {@code DELETE}, {@code MANAGEMENT};</li>
 *   <li>anything else: {@code UPDATE}, {@code MANAGEMENT}.</li>
 * </ol>
 * Rules 1 and 2 follow log-event.md section 4.3: a metadata read (list, describe, get-policy) is
 * {@code MANAGEMENT}, not a data read, so a guessed metadata read never moves
 * {@code last_accessed_at} or a usage count. Rule 2 also keeps {@code GetBucketPolicy} from being
 * routed as a permission change, and matching whole words keeps {@code CreateDiamondTable} from
 * matching {@code iam}: two defects of the pre-registry substring rules. An operation whose list
 * verb returns content (BigQuery {@code tabledataservice.list}) belongs in the family's explicit
 * table as {@code DATA_READ}.
 */
public final class VerbHeuristics {

    private static final Set<String> METADATA_READ = Set.of("list", "describe", "lookup", "search");
    private static final Set<String> CONTENT_READ = Set.of(
            "get", "read", "head", "download", "select", "query", "scan");
    private static final Set<String> PERMISSION_NOUN = Set.of(
            "policy", "policies", "permission", "permissions", "acl", "acls", "grant", "grants", "iam");
    private static final Set<String> CREATE = Set.of("create", "insert", "put", "run", "upload", "copy", "import");
    private static final Set<String> DELETE = Set.of("delete", "remove", "terminate", "drop", "purge");

    private static final Pattern WORD_BREAK = Pattern.compile(
            "(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])|[_\\s-]+");

    private VerbHeuristics() {
    }

    /**
     * Classifies an operation by its verb.
     *
     * @param operation the provider operation; {@code null} or blank gives {@code UPDATE}
     * @return a heuristic classification
     */
    public static Classification classify(String operation) {
        List<String> words = words(operation);
        String first = words.isEmpty() ? "" : words.get(0);
        boolean batchGet = words.size() > 1 && "batch".equals(first) && "get".equals(words.get(1));
        if (METADATA_READ.contains(first)) {
            return of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_MANAGEMENT);
        }
        if (CONTENT_READ.contains(first) || batchGet) {
            return words.stream().anyMatch(PERMISSION_NOUN::contains)
                    ? of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_MANAGEMENT)
                    : of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ);
        }
        if (words.stream().anyMatch(w -> PERMISSION_NOUN.contains(w) || "revoke".equals(w))) {
            return of(ChangeType.PERMISSION, EventCategory.EVENT_CATEGORY_PERMISSION);
        }
        if (CREATE.contains(first)) {
            return of(ChangeType.CREATE, EventCategory.EVENT_CATEGORY_MANAGEMENT);
        }
        if (DELETE.contains(first)) {
            return of(ChangeType.DELETE, EventCategory.EVENT_CATEGORY_MANAGEMENT);
        }
        return of(ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_MANAGEMENT);
    }

    /**
     * The lower-cased words of the verb segment of an operation.
     *
     * @param operation the operation; may be {@code null}
     * @return the words; empty for no operation
     */
    static List<String> words(String operation) {
        if (operation == null || operation.isBlank()) {
            return List.of();
        }
        String s = operation.strip();
        int cut = Math.max(s.lastIndexOf('.'), s.lastIndexOf('/'));
        String segment = s.substring(cut + 1);
        return Arrays.stream(WORD_BREAK.split(segment))
                .filter(w -> !w.isEmpty())
                .map(w -> w.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static Classification of(ChangeType type, EventCategory category) {
        return new Classification(type, category, Optional.empty(), Optional.empty(), true);
    }
}
