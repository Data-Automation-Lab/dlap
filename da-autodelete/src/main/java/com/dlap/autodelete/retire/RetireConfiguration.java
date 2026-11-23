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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.nativerule.TestEndpoints;
import com.dlap.common.audit.AuditLogWriter;
import java.time.Clock;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wiring of the delete executor (RET-02): the settings, the clock, the AUD-01 audit writer on
 * {@code dlap_common}, and the ShedLock provider of the sweeper (plan rule R7, Q-548).
 *
 * <p>The audit pool URL is {@code dlap.audit.datasource.url} when set, else
 * {@code spring.datasource.url} with the database replaced by {@code dlap_common}; the user and
 * password default to the service's own ({@code da_autodelete_user}, the stream's writer role).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RetireProperties.class, TestEndpoints.class})
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class RetireConfiguration {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /**
     * The business clock (work-plan rule R6); tests replace it.
     *
     * @return the UTC system clock
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock retireClock() {
        return Clock.systemUTC();
    }

    /**
     * The dlap_common pool of the audit writer.
     *
     * @param env the environment
     * @return the pool, which connects lazily
     */
    @Bean(destroyMethod = "close")
    public RetireAuditDatabase retireAuditDatabase(Environment env) {
        String url = env.getProperty("dlap.audit.datasource.url", "");
        if (url.isBlank()) {
            url = RetireAuditDatabase.commonUrlNextTo(env.getProperty("spring.datasource.url"));
        }
        String user = env.getProperty("dlap.audit.datasource.username", "");
        if (user.isBlank()) {
            user = env.getProperty("spring.datasource.username", "");
        }
        String password = env.getProperty("dlap.audit.datasource.password", "");
        if (password.isBlank()) {
            password = env.getProperty("spring.datasource.password", "");
        }
        int pool = env.getProperty("dlap.audit.datasource.maximum-pool-size", Integer.class, 4);
        return new RetireAuditDatabase(url, user, password, pool);
    }

    /**
     * The delete stream's writer.
     *
     * @param db    the pool
     * @param clock the clock for {@code recordedAt}
     * @return the audit log
     */
    @Bean
    public RetireAuditLog retireAuditLog(RetireAuditDatabase db, Clock clock) {
        return new RetireAuditLog(new AuditLogWriter(db.dataSource(), clock));
    }

    /**
     * The ShedLock provider: database time, the table addressed by schema because the provider
     * issues plain JDBC (Flyway V5 creates {@code dlap_autodelete.shedlock}).
     *
     * @param dataSource the service data source
     * @param schema     the service schema
     * @return the provider
     */
    @Bean
    public LockProvider autodeleteLockProvider(DataSource dataSource,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_autodelete}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the ShedLock table: " + schema);
        }
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(schema + ".shedlock")
                .usingDbTime()
                .build());
    }
}
