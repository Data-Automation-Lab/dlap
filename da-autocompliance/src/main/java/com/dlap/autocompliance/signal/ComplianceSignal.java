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

package com.dlap.autocompliance.signal;

/**
 * A single compliance observation about one asset, as reported by an external
 * compliance/GRC tool (AWS Config, GCP Security Command Center, Wiz, Prisma
 * Cloud, OneTrust, ServiceNow GRC, …) through a {@link ComplianceSignalProvider}.
 *
 * <p>DLAP does not evaluate framework controls itself (ADR-0035); it ingests
 * signals and projects them onto asset <b>labels</b>, which the Policy DSL then
 * enforces over the data lifecycle. The {@code assetUri} is the canonical join
 * key (see the Asset Descriptor spec); {@code framework}/{@code controlId} are
 * the source tool's identifiers (e.g. {@code GDPR} / {@code art-17-retention}).
 *
 * @param assetUri  canonical asset identifier the signal is about (required)
 * @param framework compliance framework, e.g. {@code GDPR}, {@code HIPAA}, {@code SOC2}, {@code PCI-DSS}
 * @param controlId the source tool's control/check id (may be null for a framework-level roll-up)
 * @param status    the observed posture for this control on this asset
 * @param detail    optional human-readable detail carried onto the label as provenance
 */
public record ComplianceSignal(
        String assetUri,
        String framework,
        String controlId,
        ComplianceStatus status,
        String detail) {

    /** Posture reported by the external tool for a control on an asset. */
    public enum ComplianceStatus {
        COMPLIANT,
        VIOLATION,
        UNKNOWN
    }
}
