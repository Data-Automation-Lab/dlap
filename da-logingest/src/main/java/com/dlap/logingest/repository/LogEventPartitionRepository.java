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

package com.dlap.logingest.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the monthly partitions of {@code log_events} (Flyway V3, hardening critic item 20,
 * ADR-0060).
 *
 * <p>Partitions are named {@code log_events_pYYYY_MM} and cover {@code [first of the month, first
 * of the next month)} of {@code ingest_time} in UTC; {@code log_events_default} holds rows of a
 * month that has no partition yet. Retention drops whole partitions and never deletes inside one,
 * so a sweep costs one catalog change per month instead of a scan of every row.
 *
 * <p>Only tables that match the naming convention are ever dropped. Every statement addresses the
 * table by schema, because plain JDBC does not see Hibernate's default schema.
 */
@Repository
public class LogEventPartitionRepository {

    /** The partitioned parent table. */
    public static final String PARENT = "log_events";

    /** The default partition. */
    public static final String DEFAULT_PARTITION = "log_events_default";

    /** Advisory lock namespace of partition maintenance; the key is {@code year * 100 + month}. */
    public static final int LOCK_NAMESPACE = 0x444C_0003;

    private static final Pattern MONTHLY = Pattern.compile("log_events_p(\\d{4})_(\\d{2})");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final DateTimeFormatter BOUND = DateTimeFormatter.ofPattern("yyyy-MM-dd' 00:00:00+00'");

    private final JdbcTemplate jdbc;
    private final String schema;

    /**
     * Creates the repository.
     *
     * @param jdbc   the service datasource's template
     * @param schema the service schema
     * @throws IllegalArgumentException when the schema is not a plain SQL identifier
     */
    public LogEventPartitionRepository(JdbcTemplate jdbc,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_logingest}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for log_events: " + schema);
        }
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /**
     * One monthly partition.
     *
     * @param name  the table name
     * @param month the month it covers
     */
    public record MonthlyPartition(String name, YearMonth month) {

        /**
         * The first instant after the partition.
         *
         * @return the exclusive upper bound
         */
        public Instant upperBound() {
            return startOf(month.plusMonths(1));
        }

        /**
         * The first instant of the partition.
         *
         * @return the inclusive lower bound
         */
        public Instant lowerBound() {
            return startOf(month);
        }
    }

    /**
     * The partition name of a month.
     *
     * @param month the month
     * @return {@code log_events_pYYYY_MM}
     */
    public static String partitionName(YearMonth month) {
        return String.format("log_events_p%04d_%02d", month.getYear(), month.getMonthValue());
    }

    /**
     * The month a partition name stands for.
     *
     * @param name a table name
     * @return the month, or empty when the name does not follow the convention
     */
    public static Optional<YearMonth> monthOf(String name) {
        Matcher m = MONTHLY.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) {
            return Optional.empty();
        }
        return Optional.of(YearMonth.of(Integer.parseInt(m.group(1)), month));
    }

    /**
     * The first instant of a month in UTC.
     *
     * @param month the month
     * @return midnight UTC on its first day
     */
    public static Instant startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Whether {@code log_events} is a partitioned table.
     *
     * @return true after Flyway V3
     */
    public boolean isPartitioned() {
        List<String> kinds = jdbc.queryForList(
                "SELECT c.relkind::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = ? AND c.relname = ?", String.class, schema, PARENT);
        return kinds.size() == 1 && "p".equals(kinds.get(0));
    }

    /**
     * The monthly partitions that exist, oldest first. The default partition is not listed.
     *
     * @return the partitions
     */
    public List<MonthlyPartition> monthlyPartitions() {
        List<String> names = jdbc.queryForList(
                "SELECT c.relname::text FROM pg_inherits i "
                        + "JOIN pg_class c ON c.oid = i.inhrelid "
                        + "JOIN pg_class p ON p.oid = i.inhparent "
                        + "JOIN pg_namespace n ON n.oid = p.relnamespace "
                        + "WHERE n.nspname = ? AND p.relname = ?", String.class, schema, PARENT);
        List<MonthlyPartition> out = new ArrayList<>();
        for (String name : names) {
            monthOf(name).ifPresent(m -> out.add(new MonthlyPartition(name, m)));
        }
        out.sort(Comparator.comparing(MonthlyPartition::month));
        return out;
    }

    /**
     * Creates the partition of a month unless it exists. Rows of that month already waiting in the
     * default partition move into the new partition in the same transaction, which PostgreSQL
     * requires before the range can be attached. Concurrent callers serialize on an advisory lock
     * of the month, so replicas starting together cannot race.
     *
     * @param month the month
     * @return true when the partition was created
     */
    @Transactional
    public boolean ensureMonthlyPartition(YearMonth month) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, LOCK_NAMESPACE,
                month.getYear() * 100 + month.getMonthValue());
        String name = partitionName(month);
        if (tableExists(name)) {
            return false;
        }
        OffsetDateTime from = OffsetDateTime.ofInstant(startOf(month), ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.ofInstant(startOf(month.plusMonths(1)), ZoneOffset.UTC);
        String bounds = "FOR VALUES FROM ('" + BOUND.format(from) + "') TO ('" + BOUND.format(to) + "')";
        Boolean waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + qualified(DEFAULT_PARTITION)
                + " WHERE ingest_time >= ? AND ingest_time < ?)", Boolean.class, from, to);
        if (!Boolean.TRUE.equals(waiting)) {
            jdbc.execute("CREATE TABLE " + qualified(name) + " PARTITION OF " + qualified(PARENT) + " " + bounds);
            return true;
        }
        jdbc.execute("CREATE TABLE " + qualified(name) + " (LIKE " + qualified(PARENT)
                + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
        jdbc.update("INSERT INTO " + qualified(name) + " SELECT * FROM " + qualified(DEFAULT_PARTITION)
                + " WHERE ingest_time >= ? AND ingest_time < ?", from, to);
        jdbc.update("DELETE FROM " + qualified(DEFAULT_PARTITION) + " WHERE ingest_time >= ? AND ingest_time < ?",
                from, to);
        jdbc.execute("ALTER TABLE " + qualified(PARENT) + " ATTACH PARTITION " + qualified(name) + " " + bounds);
        return true;
    }

    /**
     * Drops one monthly partition with every row in it.
     *
     * @param partition the partition
     * @throws IllegalArgumentException when the name does not follow the monthly convention
     */
    public void drop(MonthlyPartition partition) {
        if (monthOf(partition.name()).filter(partition.month()::equals).isEmpty()) {
            throw new IllegalArgumentException("not a monthly log_events partition: " + partition.name());
        }
        jdbc.execute("DROP TABLE IF EXISTS " + qualified(partition.name()));
    }

    /**
     * Deletes rows of the default partition ingested before a cutoff: rows that never had a
     * monthly partition, for example because maintenance did not run that month.
     *
     * @param cutoff the retention cutoff
     * @return the rows deleted
     */
    public int deleteDefaultRowsBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM " + qualified(DEFAULT_PARTITION) + " WHERE ingest_time < ?",
                OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }

    /**
     * Counts the rows of the default partition.
     *
     * @return the count
     */
    public long defaultRowCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + qualified(DEFAULT_PARTITION), Long.class);
        return n == null ? 0 : n;
    }

    private boolean tableExists(String name) {
        String regclass = jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, schema + "." + name);
        return regclass != null;
    }

    private String qualified(String table) {
        return schema + "." + table;
    }
}
