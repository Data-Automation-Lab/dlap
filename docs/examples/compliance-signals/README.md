<!--
Licensed to the Apache Software Foundation (ASF) under one or more contributor license
agreements. See the NOTICE file distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file to you under the Apache License,
Version 2.0. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
-->

# External compliance signals: reference providers

The **primary** compliance path in DLAP is DLAP-native control-policies (see
[`../compliance-policies/`](../compliance-policies/)). The `ComplianceSignalProvider` SPI is
the **optional supplement** for ingesting posture from an *external* GRC tool and projecting
it onto asset labels (ADR-0035). Two reference providers ship (both cloud-neutral, no vendor
SDK); a deployment supplies a bean and the no-op default yields.

A `ComplianceSignal` is `{assetUri, framework, controlId, status, detail}` where
`status ∈ {COMPLIANT, VIOLATION, UNKNOWN}`. `UNKNOWN` maps to **no** label; a definite
posture maps to `compliance:<framework>:<status>` via the authoritative addLabel pipeline.

## File provider: "bring your export"

Reads signals from a JSON file (this directory's [`signals.json`](signals.json) is a valid
example). Zero dependencies; works air-gapped; ideal for demos/evaluation and batch exports.

```properties
dlap.compliance.signal-provider=file
dlap.compliance.signal-provider.file.path=/etc/dlap/compliance-signals.json
dlap.compliance.signal-sync.enabled=true
```

## HTTP provider: pull from your GRC tool

GETs the same JSON array from a REST endpoint your GRC tool (or a thin adapter you run)
exposes. 5s connect / 15s read timeout; transport failures are non-fatal (retried next sync).

```properties
dlap.compliance.signal-provider=http
dlap.compliance.signal-provider.http.url=https://grc.internal.example.com/dlap/signals
dlap.compliance.signal-sync.enabled=true
```

## Writing your own

Implement `ComplianceSignalProvider` (`fetchSignals()` + `isEnabled()`) as a Spring bean for
AWS Security Hub / GCP SCC / Wiz / OneTrust / ServiceNow GRC, etc. Because it is a bean, it
displaces the no-op default (`@ConditionalOnMissingBean`) and its signals flow through the
same label pipeline - no other code changes.
