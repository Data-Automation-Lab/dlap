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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parser of the identity map seed file, a three-column CSV (enforcement-contract.md section 6.1).
 *
 * <pre>
 * # comment lines and blank lines are ignored
 * provider,principal,subject
 * GCP,marketing-agent@acme-prod.iam.gserviceaccount.com,svc-marketing-agent
 * AWS,arn:aws:iam::123456789012:role/analytics-reader,svc-analytics
 * ANY,alice@example.com,alice
 * </pre>
 *
 * <p>The first data line is the header, exactly {@code provider,principal,subject} (any case).
 * {@code provider} is an {@link IdentityProviderScope}; {@code principal} is stored in
 * {@link PrincipalKeys#key} form; {@code subject} is the AuthZEN {@code subject.id}. A key named
 * twice with the same subject is one entry; with two subjects the file is ambiguous and rejected.
 * Every rejection names the line, so a bad file fails the service start instead of silently
 * mapping nobody.
 */
public final class IdentityMapSeedFile {

    /** The header line. */
    public static final String HEADER = "provider,principal,subject";

    /** The longest subject the map stores ({@code principal_identity_map.subject_id}). */
    public static final int MAX_SUBJECT_LENGTH = 512;

    /**
     * One parsed entry.
     *
     * @param provider     the scope
     * @param principalKey the principal in {@link PrincipalKeys#key} form
     * @param subjectId    the DLAP subject
     */
    public record Entry(IdentityProviderScope provider, String principalKey, String subjectId) {
    }

    /** A seed file that cannot be applied. */
    public static final class InvalidSeedFileException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message what is wrong and on which line
         */
        public InvalidSeedFileException(String message) {
            super(message);
        }
    }

    private IdentityMapSeedFile() {
    }

    /**
     * Parses the file text.
     *
     * @param text the file content
     * @return the entries in file order, duplicates removed
     * @throws InvalidSeedFileException when a line is malformed or a key maps to two subjects
     */
    public static List<Entry> parse(String text) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        boolean headerSeen = false;
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].strip();
            if (i == 0 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!headerSeen) {
                if (!HEADER.equalsIgnoreCase(line.replace(" ", ""))) {
                    throw new InvalidSeedFileException("line " + lineNo + ": expected the header '" + HEADER + "'");
                }
                headerSeen = true;
                continue;
            }
            Entry entry = entry(line, lineNo);
            String id = entry.provider() + "\n" + entry.principalKey();
            Entry previous = entries.putIfAbsent(id, entry);
            if (previous != null && !previous.subjectId().equals(entry.subjectId())) {
                throw new InvalidSeedFileException("line " + lineNo + ": principal '" + entry.principalKey()
                        + "' in scope " + entry.provider() + " is already mapped to another subject");
            }
        }
        if (!headerSeen) {
            throw new InvalidSeedFileException("the file has no header '" + HEADER + "'");
        }
        return new ArrayList<>(entries.values());
    }

    private static Entry entry(String line, int lineNo) {
        String[] cells = line.split(",", -1);
        if (cells.length != 3) {
            throw new InvalidSeedFileException("line " + lineNo + ": expected 3 columns, found " + cells.length);
        }
        Optional<IdentityProviderScope> provider = IdentityProviderScope.parse(cells[0]);
        if (provider.isEmpty()) {
            throw new InvalidSeedFileException("line " + lineNo + ": unknown provider '" + cells[0].strip() + "'");
        }
        String key = PrincipalKeys.key(cells[1]);
        if (key.isEmpty() || key.length() > PrincipalKeys.MAX_LENGTH) {
            throw new InvalidSeedFileException("line " + lineNo + ": the principal must have 1 to "
                    + PrincipalKeys.MAX_LENGTH + " characters");
        }
        String subject = cells[2].strip();
        if (subject.isEmpty() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new InvalidSeedFileException("line " + lineNo + ": the subject must have 1 to "
                    + MAX_SUBJECT_LENGTH + " characters");
        }
        return new Entry(provider.get(), key, subject);
    }
}
