-- 2026-04-11 — Add cloud_connection / created_by_user columns (if missing) and FK indexes on dlap_common.dlap_assets
-- Tracked under M1-19 (Apache OSS launch hardening)
-- Idempotent: safe to re-run

ALTER TABLE dlap_common.dlap_assets ADD COLUMN IF NOT EXISTS cloud_connection_id UUID;
ALTER TABLE dlap_common.dlap_assets ADD COLUMN IF NOT EXISTS created_by_user_id UUID;

CREATE INDEX IF NOT EXISTS idx_asset_cloud_connection
    ON dlap_common.dlap_assets (cloud_connection_id);

CREATE INDEX IF NOT EXISTS idx_asset_created_by_user
    ON dlap_common.dlap_assets (created_by_user_id);

-- Verification:
--   SELECT indexname FROM pg_indexes WHERE schemaname = 'dlap_common' AND tablename = 'dlap_assets';
