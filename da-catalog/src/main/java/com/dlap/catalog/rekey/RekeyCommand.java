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

package com.dlap.catalog.rekey;

import java.util.Locale;
import java.util.Optional;

/** The re-key commands an operator can run ({@code dlap.catalog.rekey.command}). */
public enum RekeyCommand {

    /** Plan and report; change nothing. */
    DRY_RUN("dry-run"),

    /** Plan, then re-key and merge in one transaction, recording the reversible map. */
    APPLY("apply"),

    /** Undo the latest applied run (or the named one, when it is the latest). */
    REVERT("revert");

    private final String text;

    RekeyCommand(String text) {
        this.text = text;
    }

    /**
     * The command as an operator types it.
     *
     * @return the text
     */
    public String text() {
        return text;
    }

    /**
     * Parses a command, ignoring case and surrounding blanks.
     *
     * @param value the text
     * @return the command, or empty when the text names none
     */
    public static Optional<RekeyCommand> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (RekeyCommand c : values()) {
            if (c.text.equals(v)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
