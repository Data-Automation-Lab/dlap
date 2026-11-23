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

package com.dlap.autodelete.providers.gcp;

import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.BaseServiceException;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Applies a BigQuery table expiration with {@code tables.patch}. An expiration that is already
 * earlier than DLAP's is kept (the table goes sooner anyway); a table without one, or with a later
 * one, gets DLAP's. Least privilege: {@code bigquery.tables.get}, {@code bigquery.tables.update}.
 */
@Component
public class BigQueryTableExpirationApplier implements NativeRuleApplier {

    /**
     * Table access, so tests need no SDK.
     */
    public interface Tables {

        /**
         * The table's current expiration.
         *
         * @param table the table
         * @return the expiration in epoch milliseconds, empty when none
         * @throws NativeRuleException when the table does not exist
         */
        Optional<Long> expiration(TableId table);

        /**
         * Sets the expiration.
         *
         * @param table        the table
         * @param epochMillis  the expiration
         */
        void setExpiration(TableId table, long epochMillis);
    }

    private final Tables tables;

    /** The production applier: application default credentials, project from the table id. */
    @Autowired
    public BigQueryTableExpirationApplier() {
        this(new SdkTables());
    }

    /**
     * An applier with its own table access (tests).
     *
     * @param tables the table access
     */
    public BigQueryTableExpirationApplier(Tables tables) {
        this.tables = tables;
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.BIGQUERY_TABLE_EXPIRATION;
    }

    @Override
    public void apply(NativeRule rule) {
        JsonNode doc = RuleDocuments.read(rule.document());
        TableId id = TableId.of(doc.path("projectId").asText(), doc.path("datasetId").asText(),
                doc.path("tableId").asText());
        long ours = Long.parseLong(doc.path("body").path("expirationTime").asText());
        try {
            Optional<Long> current = tables.expiration(id);
            if (current.isPresent() && current.get() <= ours) {
                return;
            }
            tables.setExpiration(id, ours);
        } catch (BaseServiceException e) {
            throw new NativeRuleException("BigQuery refused the expiration of " + rule.targetUri() + ": HTTP "
                    + e.getCode() + " " + e.getReason(), e);
        } catch (NativeRuleException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NativeRuleException("BigQuery could not be reached for " + rule.targetUri() + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    /** The SDK implementation. */
    static final class SdkTables implements Tables {

        private static BigQuery client(TableId id) {
            return BigQueryOptions.newBuilder().setProjectId(id.getProject()).build().getService();
        }

        @Override
        public Optional<Long> expiration(TableId id) {
            Table table = client(id).getTable(id);
            if (table == null) {
                throw new NativeRuleException("BigQuery table " + id + " does not exist", null);
            }
            return Optional.ofNullable(table.getExpirationTime());
        }

        @Override
        public void setExpiration(TableId id, long epochMillis) {
            Table table = client(id).getTable(id);
            if (table == null) {
                throw new NativeRuleException("BigQuery table " + id + " does not exist", null);
            }
            table.toBuilder().setExpirationTime(epochMillis).build().update();
        }
    }
}
