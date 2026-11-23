-- HA-01: lock table for ShedLock (JdbcTemplateLockProvider, usingDbTime()).
--
-- The compliance signal sync (ComplianceSignalSyncService, ADR-0035) is a singleton job: with more
-- than one da-autocompliance replica every replica would otherwise pull the external signals and
-- apply the same labels on its own timer. Each run takes one named row here; the replica that wins
-- the row runs, the others skip that tick.
--
-- The column set is the one ShedLock requires, unchanged (same as da-catalog V4, da-discovery V10,
-- da-logingest V3 and da-policyengine V6): name is the lock key, lock_until the expiry (written
-- with the database clock, so replica clock skew cannot shorten or extend a lock), locked_at and
-- locked_by identify the holder for operators. ShedLock stores timestamps in UTC, hence TIMESTAMP
-- without time zone.
--
-- Not an entity: Hibernate never maps this table, so ddl-auto=validate is unaffected.
-- Idempotent (CREATE TABLE IF NOT EXISTS) per the migration rules in CLAUDE.md section 7; the
-- table is schema-qualified like V4, so it lands in dlap_autocompliance whatever the search path.
CREATE TABLE IF NOT EXISTS dlap_autocompliance.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
