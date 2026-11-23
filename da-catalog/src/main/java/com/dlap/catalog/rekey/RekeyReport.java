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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import com.dlap.catalog.rekey.RekeyPlan.Change;
import com.dlap.catalog.rekey.RekeyPlan.MergeGroup;
import com.dlap.catalog.rekey.RekeyPlan.RejectedRow;
import com.dlap.catalog.rekey.RekeyPlan.StoredAsset;
import com.dlap.common.assetid.AssetIdNormalizer;

/**
 * The JSON report of a re-key command, as an ordered map (Jackson writes it). Shape:
 * <pre>
 * command, specVersion, generatedAt, runId (apply, revert)
 * summary     scanned, unchanged, rekeyed, kindChanges, mergeGroups, mergedRows, rejected,
 *             rejectedByReason
 * collisions  per group: canonicalUri, serviceType, cloudProvider, kindConflict, survivor,
 *             merged[], references (dry run: rows that will move; apply: what moved)
 * rekeys      per in-place change: assetId, from, to, serviceType and provider before and after
 * rejected    per row that does not normalize: assetId, assetUri, reason
 * blockers    what stops an apply (kind, count, detail)
 * applied     apply only: totals over all groups and the undo-log size
 * </pre>
 */
public final class RekeyReport {

    private RekeyReport() {
    }

    /**
     * Builds the report of a plan.
     *
     * @param command     the command
     * @param generatedAt DLAP's clock when the report was made
     * @param runId       the run, or {@code null}
     * @param plan        the plan
     * @param references  per survivor, the reference counts to show for its group
     * @param blockers    what blocks an apply
     * @return the report
     */
    public static Map<String, Object> of(RekeyCommand command, Instant generatedAt, UUID runId, RekeyPlan plan,
                                         Function<MergeGroup, Map<String, Integer>> references,
                                         List<Map<String, Object>> blockers) {
        Map<String, Object> report = header(command, generatedAt, runId);
        report.put("summary", summary(plan));
        List<Object> collisions = new ArrayList<>();
        for (MergeGroup g : plan.groups()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("canonicalUri", g.target().uri());
            c.put("serviceType", g.target().serviceType());
            c.put("cloudProvider", g.target().cloudProvider());
            c.put("kindConflict", g.kindConflict());
            c.put("survivor", row(g.survivor()));
            c.put("merged", g.merged().stream().map(RekeyReport::row).toList());
            c.put("references", references.apply(g));
            collisions.add(c);
        }
        report.put("collisions", collisions);
        List<Object> rekeys = new ArrayList<>();
        for (Change ch : plan.changes()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("assetId", ch.row().assetId().toString());
            r.put("from", ch.row().assetUri());
            r.put("to", ch.target().uri());
            r.put("serviceTypeFrom", ch.row().serviceType());
            r.put("serviceTypeTo", ch.target().serviceType());
            r.put("cloudProviderFrom", ch.row().cloudProvider());
            r.put("cloudProviderTo", ch.target().cloudProvider());
            rekeys.add(r);
        }
        report.put("rekeys", rekeys);
        List<Object> rejected = new ArrayList<>();
        for (RejectedRow r : plan.rejected()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("assetId", r.row().assetId().toString());
            m.put("assetUri", r.row().assetUri());
            m.put("reason", r.reason());
            rejected.add(m);
        }
        report.put("rejected", rejected);
        report.put("blockers", blockers);
        return report;
    }

    /**
     * The report of a revert.
     *
     * @param generatedAt DLAP's clock
     * @param runId       the reverted run
     * @param entries     undo entries replayed
     * @param assets      asset rows the run had changed (now restored)
     * @return the report
     */
    public static Map<String, Object> revert(Instant generatedAt, UUID runId, int entries, int assets) {
        Map<String, Object> report = header(RekeyCommand.REVERT, generatedAt, runId);
        Map<String, Object> reverted = new LinkedHashMap<>();
        reverted.put("undoEntriesReplayed", entries);
        reverted.put("assetsRestored", assets);
        report.put("reverted", reverted);
        return report;
    }

    /**
     * A blocker entry.
     *
     * @param kind   a stable code
     * @param count  how many rows it concerns
     * @param detail what to do
     * @return the entry
     */
    public static Map<String, Object> blocker(String kind, int count, String detail) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", kind);
        b.put("count", count);
        b.put("detail", detail);
        return b;
    }

    private static Map<String, Object> header(RekeyCommand command, Instant generatedAt, UUID runId) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("command", command.text());
        report.put("specVersion", AssetIdNormalizer.SPEC_VERSION);
        report.put("generatedAt", generatedAt.toString());
        if (runId != null) {
            report.put("runId", runId.toString());
        }
        return report;
    }

    private static Map<String, Object> summary(RekeyPlan plan) {
        Map<String, Integer> byReason = new TreeMap<>();
        plan.rejected().forEach(r -> byReason.merge(r.reason(), 1, Integer::sum));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("scanned", plan.scanned());
        s.put("unchanged", plan.unchanged());
        s.put("rekeyed", plan.changes().size());
        s.put("kindChanges", plan.changes().stream().filter(Change::kindChange).count());
        s.put("mergeGroups", plan.groups().size());
        s.put("mergedRows", plan.mergedRows());
        s.put("rejected", plan.rejected().size());
        s.put("rejectedByReason", byReason);
        return s;
    }

    private static Map<String, Object> row(StoredAsset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetId", a.assetId().toString());
        m.put("assetUri", a.assetUri());
        m.put("serviceType", a.serviceType());
        m.put("cloudProvider", a.cloudProvider());
        m.put("createdTs", a.createdTs() == null ? null : a.createdTs().toInstant().toString());
        return m;
    }
}
