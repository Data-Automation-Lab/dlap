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

import java.util.Map;
import java.util.UUID;

/**
 * The result of one re-key command.
 *
 * @param status  what happened
 * @param runId   the run applied or reverted; {@code null} for a dry run and when nothing was done
 * @param message one line for the log
 * @param report  the JSON report as a map (see {@link RekeyReport})
 */
public record RekeyOutcome(Status status, UUID runId, String message, Map<String, Object> report) {

    /** What a command did. */
    public enum Status {
        /** The dry run found nothing that blocks an apply, or the apply or revert committed. */
        DONE,
        /** Every row is already canonical: the apply changed nothing and recorded no run. */
        NOTHING_TO_DO,
        /** The command was refused (dry run: an apply would be refused); nothing changed. */
        REFUSED
    }

    /**
     * The process exit code of the command line: 0 done or nothing to do, 2 refused (the
     * convention of {@code run-upgrades.sh}).
     *
     * @return the exit code
     */
    public int exitCode() {
        return status == Status.REFUSED ? 2 : 0;
    }
}
