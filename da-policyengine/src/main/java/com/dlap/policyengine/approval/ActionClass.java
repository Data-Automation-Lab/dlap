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

package com.dlap.policyengine.approval;

import java.util.Locale;
import java.util.Map;

/**
 * What an action does to data, which decides how many people must approve it
 * (docs/specs/approvals.md, "Action classes").
 *
 * <p>The mapping from a {@code PolicyActionRequest} follows the consumer rules of
 * docs/specs/policy-dsl.md: an {@code actionType} that contains {@code ARCHIVE} is an archive; a
 * {@code DELETE} or {@code AUTO_DELETE} is a delete. A delete is a hard delete when its
 * {@code softDeleteWindowDays} is zero or less, when {@code hardDelete} is true, or when the window
 * cannot be read; the strictest class wins whenever the parameters are unclear.
 */
public enum ActionClass {

    /** Moves data to a colder storage class. Reversible. */
    ARCHIVE(true),
    /** Marks data deleted with a restore window. Reversible inside the window. */
    SOFT_DELETE(true),
    /** Removes data for good. Never reversible; dual control. */
    HARD_DELETE(true),
    /** Any other action whose policy asked for approval ({@code requireApproval: true}). */
    OTHER(false);

    private final boolean destructive;

    ActionClass(boolean destructive) {
        this.destructive = destructive;
    }

    /**
     * Whether actions of this class always wait for approval, whatever the policy says.
     *
     * @return true for archive and both deletes
     */
    public boolean destructive() {
        return destructive;
    }

    /**
     * The class of a policy action.
     *
     * @param actionType       the {@code actionType} of the action; may be {@code null}
     * @param actionParameters its parameters; may be {@code null}
     * @return the class; {@link #OTHER} for a non-destructive action
     */
    public static ActionClass of(String actionType, Map<String, Object> actionParameters) {
        if (actionType == null) {
            return OTHER;
        }
        String type = actionType.strip().toUpperCase(Locale.ROOT);
        if (type.contains("ARCHIVE")) {
            return ARCHIVE;
        }
        if ("DELETE".equals(type) || "AUTO_DELETE".equals(type)) {
            return isHardDelete(actionParameters) ? HARD_DELETE : SOFT_DELETE;
        }
        return OTHER;
    }

    /**
     * Parses a class name sent by an API client.
     *
     * @param name the name, case-insensitive
     * @return the class
     * @throws IllegalArgumentException when the name is not a class
     */
    public static ActionClass parse(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("actionClass is required");
        }
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("actionClass must be one of ARCHIVE, SOFT_DELETE, HARD_DELETE, OTHER",
                    e);
        }
    }

    private static boolean isHardDelete(Map<String, Object> parameters) {
        if (parameters == null) {
            return false;
        }
        Object hard = parameters.get("hardDelete");
        if (hard != null && !"false".equalsIgnoreCase(hard.toString().strip())) {
            return true;
        }
        Object window = parameters.get("softDeleteWindowDays");
        if (window == null) {
            return false;
        }
        try {
            // Written as "not positive" so NaN counts as a hard delete too.
            return !(Double.parseDouble(window.toString().strip()) > 0);
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
