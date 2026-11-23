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

package com.dlap.discovery.coverage;

import java.util.ArrayList;
import java.util.List;

/**
 * A coverage level with its reasons. For {@code PARTIAL}, {@code NONE} and unknown the reasons
 * name the gaps (an unknown names the missing permission); for {@code FULL} they name the evidence.
 * Reasons never quote a principal, a tag value or any other customer data beyond resource names.
 *
 * @param level   the level; {@code null} is unknown
 * @param reasons the reasons, never {@code null}
 */
public record CoverageVerdict(CoverageLevel level, List<String> reasons) {

    /**
     * Creates the verdict.
     *
     * @param level   the level
     * @param reasons the reasons
     */
    public CoverageVerdict {
        reasons = List.copyOf(reasons);
    }

    /**
     * A {@code FULL} verdict.
     *
     * @param evidence what proves it
     * @return the verdict
     */
    public static CoverageVerdict full(List<String> evidence) {
        return new CoverageVerdict(CoverageLevel.FULL, evidence);
    }

    /**
     * A {@code PARTIAL} verdict.
     *
     * @param gaps the gaps
     * @return the verdict
     */
    public static CoverageVerdict partial(List<String> gaps) {
        return new CoverageVerdict(CoverageLevel.PARTIAL, gaps);
    }

    /**
     * A {@code NONE} verdict.
     *
     * @param gaps why nothing is recorded
     * @return the verdict
     */
    public static CoverageVerdict none(List<String> gaps) {
        return new CoverageVerdict(CoverageLevel.NONE, gaps);
    }

    /**
     * An unknown verdict.
     *
     * @param why why the collector cannot tell, for example the missing permission
     * @return the verdict
     */
    public static CoverageVerdict unknown(List<String> why) {
        return new CoverageVerdict(null, why);
    }

    /**
     * An unknown verdict for a missing permission.
     *
     * @param denied the denial
     * @return the verdict
     */
    public static CoverageVerdict unknown(MissingPermissionException denied) {
        return unknown(List.of(denied.reason()));
    }

    /**
     * Whether the level is known.
     *
     * @return false for unknown
     */
    public boolean known() {
        return level != null;
    }

    /**
     * This verdict, downgraded to {@code PARTIAL} by further gaps when it was {@code FULL}. A
     * {@code PARTIAL}, {@code NONE} or unknown verdict keeps its level and gains the reasons only
     * when it is {@code PARTIAL}.
     *
     * @param gaps further gaps; empty leaves the verdict as it is
     * @return the verdict
     */
    public CoverageVerdict limitedBy(List<String> gaps) {
        if (gaps.isEmpty()) {
            return this;
        }
        if (level == CoverageLevel.FULL) {
            return partial(gaps);
        }
        if (level == CoverageLevel.PARTIAL) {
            List<String> all = new ArrayList<>(reasons);
            all.addAll(gaps);
            return partial(all);
        }
        return this;
    }
}
