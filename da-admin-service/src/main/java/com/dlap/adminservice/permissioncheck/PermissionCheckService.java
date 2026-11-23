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

package com.dlap.adminservice.permissioncheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.adminservice.permissioncheck.PermissionCheckReport.Status;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.TargetReport;

/**
 * The permission self-check (IAM-04): for each cloud, asks the provider which permissions the
 * Actor identity holds on every configured resource and compares the answer with the committed
 * trace. A permission the trace gives the executor on that scope and the identity lacks is
 * missing; a permission the identity holds that the trace does not give it there (another
 * executor's, a {@code never_grant} entry, anything on a resource the operator never named, or for
 * a provider that lists effective permissions anything else) is extra. Permissions of the same
 * cloud's Reader trace are listed apart, not counted as extra.
 */
public class PermissionCheckService {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionCheckService.class);

    private final TraceSource traces;
    private final Map<String, PermissionProbe> probes = new LinkedHashMap<>();

    /**
     * Creates the service.
     *
     * @param traces where the committed traces are read
     * @param probes one probe per cloud
     */
    public PermissionCheckService(TraceSource traces, List<PermissionProbe> probes) {
        this.traces = traces;
        for (PermissionProbe p : probes) {
            this.probes.put(p.cloud(), p);
        }
    }

    /** @return the clouds the service can check, in a stable order */
    public List<String> clouds() {
        return List.copyOf(new TreeSet<>(probes.keySet()));
    }

    /**
     * Checks every cloud.
     *
     * @return one report per cloud
     */
    public List<PermissionCheckReport> checkAll() {
        List<PermissionCheckReport> out = new ArrayList<>();
        for (String cloud : clouds()) {
            out.add(check(cloud).orElseThrow());
        }
        return out;
    }

    /**
     * Checks one cloud.
     *
     * @param cloud the cloud, for example {@code aws}
     * @return the report, or empty when no probe serves the cloud
     */
    public Optional<PermissionCheckReport> check(String cloud) {
        PermissionProbe probe = probes.get(cloud);
        if (probe == null) {
            return Optional.empty();
        }
        List<PermissionProbe.Target> targets = probe.targets();
        if (targets.isEmpty()) {
            return Optional.of(new PermissionCheckReport(cloud, Status.NOT_CONFIGURED, probe.method(), null,
                    probe.identityBasis(), probe.identityNote(), List.of(), List.of(), List.of()));
        }
        ActorTrace trace = traces.load(cloud);
        List<TargetReport> reports = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        boolean error = false;
        for (PermissionProbe.Target t : targets) {
            TargetReport r = checkTarget(trace, probe, t);
            reports.add(r);
            error |= r.error() != null;
            r.missing().forEach(p -> missing.add(t.executor() + ": " + p + " on " + t.resource()));
            r.extra().forEach(p -> extra.add(t.executor() + ": " + p + " on " + t.resource()
                    + (t.namedResource() ? "" : " (a resource the operator did not name)")));
        }
        Status status = error ? Status.ERROR : missing.isEmpty() && extra.isEmpty() ? Status.PASS : Status.FAIL;
        Set<String> identities = new TreeSet<>();
        targets.forEach(t -> identities.add(t.principal()));
        return Optional.of(new PermissionCheckReport(cloud, status, probe.method(), String.join(", ", identities),
                probe.identityBasis(), probe.identityNote(), missing, extra, reports));
    }

    private static TargetReport checkTarget(ActorTrace trace, PermissionProbe probe, PermissionProbe.Target t) {
        Set<String> expected = t.grantExpected() && t.namedResource() ? trace.expected(t.executor(), t.scope())
                : new TreeSet<>();
        Set<String> candidates = trace.candidates(t.scope());
        Set<String> acceptable = new TreeSet<>(expected);
        if (t.namedResource()) {
            acceptable.addAll(trace.inherited(t.executor(), t.scope()));
        }
        if (candidates.isEmpty()) {
            return new TargetReport(t.executor(), t.scope(), t.resource(), t.principal(), t.namedResource(),
                    List.copyOf(expected), List.of(), List.of(), List.of(), null);
        }
        PermissionProbe.Answer answer;
        try {
            answer = probe.probe(t, candidates, acceptable);
        } catch (RuntimeException e) {
            LOG.warn("Permission check of {} {} on {} failed: {}", probe.cloud(), t.executor(), t.resource(),
                    e.getMessage());
            return new TargetReport(t.executor(), t.scope(), t.resource(), t.principal(), t.namedResource(),
                    List.copyOf(expected), List.of(), List.of(), List.of(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        Set<String> allowed = answer.allowed() == null ? Set.of() : answer.allowed();
        Set<String> missing = new TreeSet<>();
        for (String p : expected) {
            if (!ActorTrace.containsIgnoreCase(allowed, p)) {
                missing.add(p);
            }
        }
        Set<String> held = new TreeSet<>();
        for (String p : allowed) {
            if (!ActorTrace.containsIgnoreCase(acceptable, p)) {
                held.add(p);
            }
        }
        if (answer.grantedBeyond() != null) {
            held.addAll(answer.grantedBeyond());
        }
        Set<String> readerHeld = new TreeSet<>();
        Set<String> extra = new TreeSet<>();
        for (String p : held) {
            if (t.namedResource() && trace.isReaderPermission(p)) {
                readerHeld.add(p);
            } else {
                extra.add(p);
            }
        }
        return new TargetReport(t.executor(), t.scope(), t.resource(), t.principal(), t.namedResource(),
                List.copyOf(expected), List.copyOf(missing), List.copyOf(extra), List.copyOf(readerHeld), null);
    }
}
