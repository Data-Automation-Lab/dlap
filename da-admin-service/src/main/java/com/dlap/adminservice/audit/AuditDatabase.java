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

package com.dlap.adminservice.audit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The connection pool to the shared {@code dlap_common} database, where the audit log lives.
 *
 * <p>Deliberately not a {@link DataSource} bean: a second {@code DataSource} bean would replace
 * Spring Boot's auto-configured one for JPA and Flyway, and would add a health indicator that
 * turns the service DOWN while {@code dlap_common} is unreachable. The pool connects lazily, so
 * the service starts without the database.
 */
public final class AuditDatabase implements AutoCloseable {

    /** The shared database name. */
    public static final String COMMON_DATABASE = "dlap_common";

    private static final Pattern POSTGRES_URL = Pattern.compile("^(jdbc:postgresql://[^/?#]+/)([^/?#]+)(\\?.*)?$");

    private final HikariDataSource dataSource;
    private final String url;

    /**
     * Creates the pool without connecting.
     *
     * @param url      the JDBC URL of {@code dlap_common}
     * @param username the user name
     * @param password the password
     * @param poolSize the largest pool size, at least 2
     */
    public AuditDatabase(String url, String username, String password, int poolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("dlap-audit-common");
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(Math.max(2, poolSize));
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(10_000);
        // Start without a connection: the admin service must come up while dlap_common is away.
        cfg.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(cfg);
        this.url = url;
    }

    /** @return the pool */
    public DataSource dataSource() {
        return dataSource;
    }

    /** @return the JDBC URL, without credentials */
    public String url() {
        return url;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /**
     * The {@code dlap_common} URL next to a service database URL: the same host, port and
     * parameters with the database name replaced.
     *
     * @param serviceUrl for example {@code jdbc:postgresql://postgresql:5432/da_admin?sslmode=verify-full}
     * @return for example {@code jdbc:postgresql://postgresql:5432/dlap_common?sslmode=verify-full}
     * @throws IllegalArgumentException when the URL is not a PostgreSQL JDBC URL with a database name
     */
    public static String commonUrlNextTo(String serviceUrl) {
        Matcher m = POSTGRES_URL.matcher(serviceUrl == null ? "" : serviceUrl.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("spring.datasource.url is not a jdbc:postgresql URL with a database;"
                    + " set dlap.audit.datasource.url");
        }
        return m.group(1) + COMMON_DATABASE + (m.group(3) == null ? "" : m.group(3));
    }
}
