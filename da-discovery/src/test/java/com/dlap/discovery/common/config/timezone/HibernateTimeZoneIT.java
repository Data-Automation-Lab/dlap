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

package com.dlap.discovery.common.config.timezone;

import static java.lang.String.*;
import static org.assertj.core.api.Assertions.*;

import com.dlap.discovery.common.model.repository.timezone.DateTimeWrapper;
import com.dlap.discovery.common.model.repository.timezone.IDateTimeWrapperRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for verifying the behavior of Hibernate in the context of
 * storing various date and time types across different databases.
 * The tests focus on ensuring that the stored values are correctly transformed
 * and stored according to the configured timezone.
 * Timezone is environment specific, and can be adjusted according to your
 * needs.
 *
 * For more context, refer to:
 * - GitHub Issue: https://github.com/jhipster/generator-jhipster/issues/22579
 * - Pull Request: https://github.com/jhipster/generator-jhipster/pull/22946
 */
// @Testcontainers is listed first so its extension starts the container before Spring's
// extension runs the class-level @Sql script below.
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = HibernateTimeZoneIT.TimeZoneTestConfig.class)
@TestPropertySource(
    properties = {
        // The scaffold table is created by the @Sql script below, so Hibernate only maps it.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        // The application maps into dlap_discovery; this scaffold table lives in public.
        "spring.jpa.properties.hibernate.default_schema=public",
    }
)
@DirtiesContext // Force new context for each test class to avoid contamination
@Sql(
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS,
    statements = {
        "DROP TABLE IF EXISTS jhi_date_time_wrapper",
        "CREATE TABLE jhi_date_time_wrapper ("
            + "id BIGSERIAL PRIMARY KEY, "
            + "instant TIMESTAMP WITH TIME ZONE, "
            + "local_date_time TIMESTAMP, "
            + "offset_date_time TIMESTAMP WITH TIME ZONE, "
            + "zoned_date_time TIMESTAMP WITH TIME ZONE, "
            + "local_time TIME, "
            + "offset_time TIME WITH TIME ZONE, "
            + "local_date DATE)",
    }
)
class HibernateTimeZoneIT {

    /** A real PostgreSQL, because the time-zone behaviour under test is the driver's and the server's. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final Logger log = LoggerFactory.getLogger(HibernateTimeZoneIT.class);
    private static TimeZone originalDefaultTimeZone;

    @BeforeAll
    static void setUtcDefaultTimeZone() {
        originalDefaultTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        log.info("HibernateTimeZoneIT: Default TimeZone set to UTC for tests.");
    }

    @AfterAll
    static void restoreDefaultTimeZone() {
        if (originalDefaultTimeZone != null) {
            TimeZone.setDefault(originalDefaultTimeZone);
            log.info("HibernateTimeZoneIT: Default TimeZone restored to {}.", originalDefaultTimeZone.getID());
        }
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.dlap.discovery.common.model.repository.timezone")
    @EntityScan("com.dlap.discovery.common.model.repository.timezone")
    @EnableTransactionManagement
    static class TimeZoneTestConfig {
    }

    @Autowired
    private IDateTimeWrapperRepository dateTimeWrapperRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.jpa.properties.hibernate.jdbc.time_zone:UTC}")
    private String zoneId;

    private DateTimeWrapper dateTimeWrapper;
    private DateTimeFormatter dateTimeFormatter;
    private DateTimeFormatter timeFormatter;
    private DateTimeFormatter offsetTimeFormatter;
    private DateTimeFormatter dateFormatter;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    public void setup() {
        // Ensure UTC timezone is set for each test method (protection against test contamination)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // Clear any existing Hibernate/JPA timezone settings that might have been contaminated
        System.setProperty("user.timezone", "UTC");

        dateTimeWrapper = new DateTimeWrapper();
        dateTimeWrapper.setInstant(Instant.parse("2014-11-12T05:10:00.0Z"));
        dateTimeWrapper.setLocalDateTime(LocalDateTime.parse("2014-11-12T07:20:00.0"));
        dateTimeWrapper.setOffsetDateTime(OffsetDateTime.parse("2011-12-14T08:30:00.0Z"));
        dateTimeWrapper.setZonedDateTime(ZonedDateTime.parse("2011-12-14T08:40:00.0Z"));
        dateTimeWrapper.setLocalTime(LocalTime.parse("14:50:00"));
        dateTimeWrapper.setOffsetTime(OffsetTime.parse("14:00:00+02:00"));
        dateTimeWrapper.setLocalDate(LocalDate.parse("2016-09-10"));

        // Force UTC timezone for all formatters
        dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("UTC"));
        timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("UTC"));
        offsetTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        log.debug("HibernateTimeZoneIT setup: Current default timezone: {}, zoneId property: {}", TimeZone.getDefault().getID(), zoneId);
    }

    @Test
    @Transactional
    void storeInstantWithZoneIdConfigShouldBeStoredOnConfiguredTimeZone() {
        dateTimeWrapperRepository.saveAndFlush(dateTimeWrapper);

        String request = generateSqlRequest("instant", dateTimeWrapper.getId());
        SqlRowSet resultSet = jdbcTemplate.queryForRowSet(request);
        Instant expectedInstant = dateTimeWrapper.getInstant();

        Instant dbInstant = null;
        if (resultSet.next()) {
            // Retrieve as java.sql.Timestamp then convert to Instant
            java.sql.Timestamp timestamp = resultSet.getTimestamp(1);
            if (timestamp != null) {
                dbInstant = timestamp.toInstant();
            }
            log.info(
                "Instant DB Value: '{}', Expected: '{}' (Original objects: DB Instant: {}, Expected Instant: {})",
                timestamp, // Log raw timestamp for debugging format
                dateTimeFormatter.format(expectedInstant), // Log expected formatted string for context
                dbInstant,
                expectedInstant
            );
        }

        assertThat(dbInstant).isNotNull();
        // Compare Instants, ignoring milliseconds for robustness against minor precision differences
        assertThat(dbInstant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
            .isEqualTo(expectedInstant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @Transactional
    void storeLocalDateTimeWithZoneIdConfigShouldBeStoredOnConfiguredTimeZone() {
        dateTimeWrapperRepository.saveAndFlush(dateTimeWrapper);

        String request = generateSqlRequest("local_date_time", dateTimeWrapper.getId());
        SqlRowSet resultSet = jdbcTemplate.queryForRowSet(request);
        LocalDateTime expectedLocalDateTime = dateTimeWrapper.getLocalDateTime();

        LocalDateTime dbLocalDateTime = null;
        java.sql.Timestamp rawTimestamp = null;
        if (resultSet.next()) {
            // Try to get as LocalDateTime directly, fallback to Timestamp conversion
            try {
                dbLocalDateTime = resultSet.getObject(1, LocalDateTime.class);
            } catch (Exception e) { // Catch broader exception if getObject with class fails
                rawTimestamp = resultSet.getTimestamp(1);
                if (rawTimestamp != null) {
                    dbLocalDateTime = rawTimestamp.toLocalDateTime();
                }
            }
            log.info(
                "LocalDateTime DB Value: {} (Raw Timestamp: {}), Expected: {} (Original object: {})",
                dbLocalDateTime,
                rawTimestamp, // Log raw timestamp if fallback was used
                dateTimeFormatter.format(expectedLocalDateTime.atZone(ZoneId.of("UTC"))), // Use UTC consistently
                expectedLocalDateTime
            );
        }

        assertThat(dbLocalDateTime).isNotNull();
        // LocalDateTime already has no zone/offset, direct comparison is fine.
        // Truncate to seconds for robustness against millisecond precision differences.
        assertThat(dbLocalDateTime.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
            .isEqualTo(expectedLocalDateTime.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @Transactional
    void storeOffsetDateTimeWithZoneIdConfigShouldBeStoredOnConfiguredTimeZone() {
        dateTimeWrapperRepository.saveAndFlush(dateTimeWrapper);

        String request = generateSqlRequest("offset_date_time", dateTimeWrapper.getId());
        SqlRowSet resultSet = jdbcTemplate.queryForRowSet(request);
        OffsetDateTime expectedOffsetDateTime = dateTimeWrapper.getOffsetDateTime();

        OffsetDateTime dbOffsetDateTime = null;
        if (resultSet.next()) {
            java.sql.Timestamp timestamp = resultSet.getTimestamp(1);
            if (timestamp != null) {
                // Assuming the timestamp from DB is in UTC as per hibernate.jdbc.time_zone=UTC
                dbOffsetDateTime = OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneId.of("UTC"));
            }
            log.info(
                "OffsetDateTime DB Value: {} (Raw Timestamp: {}), Expected: {} (Original object: {})",
                dbOffsetDateTime,
                timestamp,
                dateTimeFormatter.format(expectedOffsetDateTime), // Log expected formatted string for context
                expectedOffsetDateTime
            );
        }

        assertThat(dbOffsetDateTime).isNotNull();
        // Compare OffsetDateTimes by their instant, ignoring milliseconds
        assertThat(dbOffsetDateTime.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
            .isEqualTo(expectedOffsetDateTime.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @Transactional
    void storeZoneDateTimeWithZoneIdConfigShouldBeStoredOnConfiguredTimeZone() {
        dateTimeWrapperRepository.saveAndFlush(dateTimeWrapper);

        String request = generateSqlRequest("zoned_date_time", dateTimeWrapper.getId());
        SqlRowSet resultSet = jdbcTemplate.queryForRowSet(request);
        ZonedDateTime expectedZonedDateTime = dateTimeWrapper.getZonedDateTime();

        ZonedDateTime dbZonedDateTime = null;
        if (resultSet.next()) {
            java.sql.Timestamp timestamp = resultSet.getTimestamp(1);
            if (timestamp != null) {
                // Assuming the timestamp from DB is in UTC as per hibernate.jdbc.time_zone=UTC
                // Convert to ZonedDateTime in UTC, then compare instants.
                dbZonedDateTime = ZonedDateTime.ofInstant(timestamp.toInstant(), ZoneId.of("UTC"));
            }
            log.info(
                "ZonedDateTime DB Value: {} (Raw Timestamp: {}), Expected: {} (Original object: {})",
                dbZonedDateTime,
                timestamp,
                dateTimeFormatter.format(expectedZonedDateTime), // Log expected formatted string for context
                expectedZonedDateTime
            );
        }

        assertThat(dbZonedDateTime).isNotNull();
        // Compare ZonedDateTimes by their instant, ignoring milliseconds
        assertThat(dbZonedDateTime.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
            .isEqualTo(expectedZonedDateTime.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @Transactional
    void storeLocalTimeWithZoneIdConfigShouldBeStoredOnConfiguredTimeZoneAccordingToHis1stJan1970Value() {
        dateTimeWrapperRepository.saveAndFlush(dateTimeWrapper);

        String request = generateSqlRequest("local_time", dateTimeWrapper.getId());
        SqlRowSet resultSet = jdbcTemplate.queryForRowSet(request);
        String expectedValue = dateTimeWrapper
            .getLocalTime()
            .atDate(LocalDate.of(1970, Month.JANUARY, 1))
            .atZone(ZoneId.of("UTC")) // Force UTC consistently
            .format(timeFormatter);

        assertThatValueFromSqlRowSetIsEqualToExpectedValue(resultSet, expectedValue);
    }

    @Test
    @Transactional
    void storeOffsetTimeWithZoneIdConfigShouldBeStoredOnConfiguredTimeZoneAccordingToHis1stJan1970Value() {
        dateTimeWrapperRepository.saveAndFlush(dateTimeWrapper);

        String request = generateSqlRequest("offset_time", dateTimeWrapper.getId());
        SqlRowSet resultSet = jdbcTemplate.queryForRowSet(request);
        String expectedValue = dateTimeWrapper
            .getOffsetTime()
            // Convert to UTC timezone consistently
            .withOffsetSameInstant(ZoneId.of("UTC").getRules().getOffset(Instant.now()))
            // Normalize to UTC TimeZone.
            .withOffsetSameLocal(OffsetDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC")).getOffset())
            // Convert the normalized value to UTC timezone
            .withOffsetSameInstant(ZoneId.of("UTC").getRules().getOffset(Instant.EPOCH))
            .format(offsetTimeFormatter);

        assertThatValueFromSqlRowSetIsEqualToExpectedValue(resultSet, expectedValue);
    }

    @Test
    @Transactional
    void storeLocalDateWithZoneIdConfigShouldBeStoredWithoutTransformation() {
        dateTimeWrapperRepository.saveAndFlush(dateTimeWrapper);

        String request = generateSqlRequest("local_date", dateTimeWrapper.getId());
        SqlRowSet resultSet = jdbcTemplate.queryForRowSet(request);
        String expectedValue = dateTimeWrapper.getLocalDate().format(dateFormatter);

        assertThatValueFromSqlRowSetIsEqualToExpectedValue(resultSet, expectedValue);
    }

    private String generateSqlRequest(String fieldName, long id) {
        return format("SELECT %s FROM jhi_date_time_wrapper where id=%d", fieldName, id);
    }

    private void assertThatValueFromSqlRowSetIsEqualToExpectedValue(SqlRowSet sqlRowSet, String expectedValue) {
        int rows = 0;
        while (sqlRowSet.next()) {
            rows++;
            String dbValue = sqlRowSet.getString(1);
            log.info("DB Value: '{}', Expected: '{}'", dbValue, expectedValue);
            assertThat(dbValue).isNotNull();
            assertThat(dbValue).isEqualTo(expectedValue);
        }
        // An empty result would otherwise pass without checking anything.
        assertThat(rows).as("rows returned for the stored wrapper").isEqualTo(1);
    }
}
