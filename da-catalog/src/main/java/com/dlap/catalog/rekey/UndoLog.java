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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Appends entries to {@code dlap_catalog.asset_uri_rekey_undo} for one run, in the order the run
 * changes rows. Every entry is written by an {@code INSERT ... SELECT} that reads the row before
 * the statement that changes it, so the before-image is exact. Not thread-safe: one run, one
 * transaction, one thread.
 */
final class UndoLog {

    /**
     * The common head of every undo statement: run id, the next sequence number plus the row
     * number of the selected row, then the table name, the before-image and the after-key.
     */
    static final String HEAD = "INSERT INTO dlap_catalog.asset_uri_rekey_undo (run_id, seq, table_name, before_row, "
            + "after_key) SELECT ?, ? + row_number() OVER (ORDER BY ";

    /** An asset row updated in place: the after-key is its own key. */
    static final String ASSET_UPDATED = HEAD + "x.asset_id), 'dlap_assets', to_jsonb(x), "
            + "jsonb_build_object('asset_id', x.asset_id) FROM dlap_common.dlap_assets x WHERE x.asset_id = ?";

    /**
     * Asset rows updated in place, many at once: the parameter is a {@code uuid[]} literal such as
     * <code>{id1,id2}</code>, cast by the database, so a malformed id fails the statement.
     */
    static final String ASSETS_UPDATED = HEAD + "x.asset_id), 'dlap_assets', to_jsonb(x), "
            + "jsonb_build_object('asset_id', x.asset_id) FROM dlap_common.dlap_assets x "
            + "JOIN unnest(?::uuid[]) AS ids(id) ON x.asset_id = ids.id";

    /** An asset row deleted. */
    static final String ASSET_DELETED = HEAD + "x.asset_id), 'dlap_assets', to_jsonb(x), NULL::jsonb "
            + "FROM dlap_common.dlap_assets x WHERE x.asset_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final UUID runId;
    private int seq;

    UndoLog(JdbcTemplate jdbcTemplate, UUID runId) {
        this.jdbcTemplate = jdbcTemplate;
        this.runId = runId;
    }

    /**
     * Runs one undo statement (a constant that starts with {@link #HEAD}).
     *
     * @param sql  the statement
     * @param args its parameters after the run id and the sequence number
     * @return the number of entries written
     */
    int record(String sql, Object... args) {
        List<Object> params = new ArrayList<>(args.length + 2);
        params.add(runId);
        params.add(seq);
        params.addAll(Arrays.asList(args));
        int n = jdbcTemplate.update(sql, params.toArray());
        seq += n;
        return n;
    }

    /**
     * Records one asset row that the run updates in place.
     *
     * @param assetId the row
     */
    void assetUpdated(UUID assetId) {
        record(ASSET_UPDATED, assetId);
    }

    /**
     * Records one asset row that the run deletes.
     *
     * @param assetId the row
     */
    void assetDeleted(UUID assetId) {
        record(ASSET_DELETED, assetId);
    }

    /**
     * The number of entries written so far.
     *
     * @return the count
     */
    int size() {
        return seq;
    }
}
