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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL 15.1 container per test JVM for the da-logingest database tests, with a fresh
 * database per caller so tests do not see each other's rows. The container user is
 * {@code da_logingest_user}, the role Flyway V1 grants to. Requires Docker; without it the
 * container start fails and the test errors, which counts as unverified (plan rule R11).
 */
public final class LogIngestPostgres {

    /** The service schema. */
    public static final String SCHEMA = "dlap_logingest";

    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15.1"))
            .withDatabaseName("logingest_tests")
            .withUsername("da_logingest_user")
            .withPassword("da_logingest_pass");

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private LogIngestPostgres() {
    }

    /**
     * Creates an empty database.
     *
     * @return a data source on it
     */
    public static synchronized DataSource newDatabase() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        String name = "li_" + ProcessHandle.current().pid() + "_" + DATABASES.incrementAndGet();
        try (Connection c = dataSource(CONTAINER.getDatabaseName()).getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create test database " + name, e);
        }
        return dataSource(name);
    }

    /**
     * The JDBC URL of a data source made by {@link #newDatabase()}.
     *
     * @param ds the data source
     * @return the URL
     */
    public static String url(DataSource ds) {
        return ((PGSimpleDataSource) ds).getURL();
    }

    /**
     * The container user.
     *
     * @return the user name
     */
    public static String user() {
        return CONTAINER.getUsername();
    }

    /**
     * The container password.
     *
     * @return the password
     */
    public static String password() {
        return CONTAINER.getPassword();
    }

    /**
     * Flyway for the service migrations on a database.
     *
     * @param ds     the database
     * @param target the target version, or {@code null} for the latest
     * @return a configured Flyway
     */
    public static Flyway flyway(DataSource ds, String target) {
        var config = Flyway.configure()
                .dataSource(ds)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(true);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    /**
     * A database migrated to the latest version.
     *
     * @return the data source
     */
    public static DataSource migratedDatabase() {
        DataSource ds = newDatabase();
        flyway(ds, null).migrate();
        return ds;
    }

    private static DataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(CONTAINER.getJdbcUrl().replace("/" + CONTAINER.getDatabaseName(), "/" + database));
        ds.setUser(CONTAINER.getUsername());
        ds.setPassword(CONTAINER.getPassword());
        return ds;
    }
}
