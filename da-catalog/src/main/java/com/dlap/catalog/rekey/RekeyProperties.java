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

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the one-shot asset_uri re-key ({@code dlap.catalog.rekey.*}, ID-03). Nothing here is
 * set in {@code application.properties}: the re-key runs only when an operator starts the catalog
 * with {@code dlap.catalog.rekey.command} (environment {@code DLAP_CATALOG_REKEY_COMMAND}), as the
 * runbook in {@code docs/guides/upgrade.md} ("Re-key asset URIs") describes.
 */
@ConfigurationProperties(prefix = "dlap.catalog.rekey")
public class RekeyProperties {

    /** {@code dry-run}, {@code apply} or {@code revert}; blank means the service runs normally. */
    private String command = "";

    /** The run to revert; empty means the latest applied run. */
    private UUID runId;

    /** Where to write the JSON report as well as standard output; empty means standard output only. */
    private Path reportFile;

    /** How long the apply or revert waits for the table lock before it gives up. */
    private Duration lockTimeout = Duration.ofSeconds(30);

    /** A name for the run record (who ran it); optional. */
    private String operator;

    /** Whether the process exits once the command finished (off only in tests). */
    private boolean exit = true;

    /**
     * The command.
     *
     * @return the command text, never {@code null}
     */
    public String getCommand() {
        return command;
    }

    /**
     * Sets the command.
     *
     * @param command {@code dry-run}, {@code apply} or {@code revert}
     */
    public void setCommand(String command) {
        this.command = command == null ? "" : command;
    }

    /**
     * The run to revert.
     *
     * @return the run id, or {@code null} for the latest applied run
     */
    public UUID getRunId() {
        return runId;
    }

    /**
     * Sets the run to revert.
     *
     * @param runId the run id
     */
    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    /**
     * The report file.
     *
     * @return the path, or {@code null}
     */
    public Path getReportFile() {
        return reportFile;
    }

    /**
     * Sets the report file.
     *
     * @param reportFile the path
     */
    public void setReportFile(Path reportFile) {
        this.reportFile = reportFile;
    }

    /**
     * The lock timeout.
     *
     * @return the timeout
     */
    public Duration getLockTimeout() {
        return lockTimeout;
    }

    /**
     * Sets the lock timeout.
     *
     * @param lockTimeout a positive duration
     */
    public void setLockTimeout(Duration lockTimeout) {
        if (lockTimeout == null || lockTimeout.isNegative() || lockTimeout.isZero()) {
            throw new IllegalArgumentException("dlap.catalog.rekey.lock-timeout must be positive");
        }
        this.lockTimeout = lockTimeout;
    }

    /**
     * The operator name.
     *
     * @return the name, or {@code null}
     */
    public String getOperator() {
        return operator;
    }

    /**
     * Sets the operator name.
     *
     * @param operator the name
     */
    public void setOperator(String operator) {
        this.operator = operator;
    }

    /**
     * Whether the process exits after the command.
     *
     * @return the flag
     */
    public boolean isExit() {
        return exit;
    }

    /**
     * Sets whether the process exits after the command.
     *
     * @param exit the flag
     */
    public void setExit(boolean exit) {
        this.exit = exit;
    }
}
