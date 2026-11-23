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

package com.dlap.autoarchival.audit;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.common.audit.AuditLogWriter;

/**
 * Wires the audit writer of the archive path (AUD-01, stream {@code retire.archive}).
 *
 * <p>The audit log lives in the shared {@code dlap_common} database and only the stream's writer
 * role may append (docs/specs/audit-log.md section 7). This service's own database role
 * {@code da_autoarchival_user} is that writer, so the connection reuses the service's database
 * user and password against the {@code dlap_common} database on the same server; set
 * {@code dlap.audit.datasource.*} to point elsewhere. The data source opens a connection per
 * append and none at start-up, so the service starts without the audit database; the first
 * archive then fails closed.
 */
@Configuration
@EnableConfigurationProperties(ArchiveRuleProperties.class)
public class ArchiveAuditConfiguration {

    /**
     * The clock for business and record times.
     *
     * @return the UTC system clock
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock archiveClock() {
        return Clock.systemUTC();
    }

    /**
     * The audit writer.
     *
     * @param configuredUrl {@code dlap.audit.datasource.url}; empty derives it from the main data source
     * @param mainUrl       {@code spring.datasource.url}
     * @param username      the writer role; defaults to the service's database user
     * @param password      its password; defaults to the service's database password
     * @param clock         the clock for {@code recordedAt}
     * @return the writer
     */
    @Bean
    public AuditLogWriter archiveAuditLogWriter(
            @Value("${dlap.audit.datasource.url:}") String configuredUrl,
            @Value("${spring.datasource.url:}") String mainUrl,
            @Value("${dlap.audit.datasource.username:${spring.datasource.username:}}") String username,
            @Value("${dlap.audit.datasource.password:${spring.datasource.password:}}") String password,
            Clock clock) {
        DriverManagerDataSource ds = new DriverManagerDataSource(commonUrl(configuredUrl, mainUrl), username, password);
        return new AuditLogWriter(ds, clock);
    }

    /**
     * Derives the {@code dlap_common} URL from the service's own database URL.
     *
     * @param configured the explicit URL, or empty
     * @param mainUrl    the service's JDBC URL
     * @return the URL of the {@code dlap_common} database on the same server
     */
    static String commonUrl(String configured, String mainUrl) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (mainUrl == null || mainUrl.isBlank()) {
            return "jdbc:postgresql://localhost:5432/dlap_common";
        }
        int query = mainUrl.indexOf('?');
        String base = query < 0 ? mainUrl : mainUrl.substring(0, query);
        String tail = query < 0 ? "" : mainUrl.substring(query);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + "dlap_common" + tail;
    }
}
