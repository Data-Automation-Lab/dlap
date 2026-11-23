-- V3: additional catalog-backed compliance controls.
-- PII-ENCRYPTION-001 and GDPR-RESIDENCY-001 were originally appended to V2 by the
-- catalog-backed-evaluation work; they are split into this new migration to preserve
-- V2's Flyway checksum on databases where V2 has already been applied. Idempotent
-- (ON CONFLICT DO NOTHING), so re-applying on a fresh DB that also has them in V2 is safe.


-- ---------------------------------------------------------------------------
-- PII-ENCRYPTION-001 control
--
-- Catalog-backed evaluator (AssetComplianceEvaluator): flags any asset that
-- carries a sensitivity label (PII / CONFIDENTIAL / SENSITIVE / PHI /
-- RESTRICTED) but has no encryption marker in its technical metadata.
-- Encrypted sensitive assets are COMPLIANT; non-sensitive assets are out of
-- scope (no finding). No target asset types -> applies to every asset type.
-- ---------------------------------------------------------------------------
INSERT INTO dlap_autocompliance.compliance_controls (
    control_id,
    name,
    description,
    severity,
    remediation_steps,
    evaluation_parameters_definition,
    details_link,
    enabled,
    created_by,
    created_date,
    last_modified_by,
    last_modified_date
) VALUES (
    'PII-ENCRYPTION-001',
    'Sensitive-Data Encryption at Rest',
    'Requires encryption at rest for any asset classified as sensitive '
        || '(PII / CONFIDENTIAL / SENSITIVE / PHI). Evaluated against the '
        || 'asset''s catalog labels and technical metadata (KMS/CMEK markers).',
    'CRITICAL',
    'Enable encryption at rest with a managed key (KMS/CMEK) for the sensitive '
        || 'asset and record the key reference in its technical metadata.',
    '{"sensitivityLabels":"PII,CONFIDENTIAL,SENSITIVE,PHI,RESTRICTED"}'::jsonb,
    'https://dlap.dev/controls/PII-ENCRYPTION-001',
    TRUE,
    'system',
    NOW(),
    'system',
    NOW()
)
ON CONFLICT (control_id) DO NOTHING;

INSERT INTO dlap_autocompliance.control_frameworks (control_id, framework_name) VALUES
    ('PII-ENCRYPTION-001', 'HIPAA'),
    ('PII-ENCRYPTION-001', 'PCI-DSS'),
    ('PII-ENCRYPTION-001', 'GDPR')
ON CONFLICT (control_id, framework_name) DO NOTHING;

-- No target asset types: PII-ENCRYPTION-001 applies to every asset type.

-- ---------------------------------------------------------------------------
-- GDPR-RESIDENCY-001 control
--
-- Catalog-backed evaluator (AssetComplianceEvaluator): flags any asset whose
-- region is outside the allowed set (evaluation_parameters_definition.allowedRegions,
-- default EU regions). In-region assets are COMPLIANT; assets with no region are
-- out of scope (no finding). No target asset types -> applies to every asset type.
-- ---------------------------------------------------------------------------
INSERT INTO dlap_autocompliance.compliance_controls (
    control_id,
    name,
    description,
    severity,
    remediation_steps,
    evaluation_parameters_definition,
    details_link,
    enabled,
    created_by,
    created_date,
    last_modified_by,
    last_modified_date
) VALUES (
    'GDPR-RESIDENCY-001',
    'GDPR Data Residency',
    'Requires personal data to reside within allowed (EU) regions. Evaluated '
        || 'against the asset''s catalog region against the control''s '
        || 'allowedRegions parameter.',
    'HIGH',
    'Relocate the data to an allowed region, or record a documented transfer '
        || 'mechanism (e.g. Standard Contractual Clauses) for the cross-border processing.',
    '{"allowedRegions":"EU,EUROPE,EU-WEST-1,EU-WEST-2,EU-WEST-3,EU-CENTRAL-1,EU-NORTH-1,EU-SOUTH-1,EUROPE-WEST1,EUROPE-WEST2,EUROPE-WEST3,EUROPE-WEST4,EUROPE-NORTH1,EUROPE-CENTRAL2"}'::jsonb,
    'https://dlap.dev/controls/GDPR-RESIDENCY-001',
    TRUE,
    'system',
    NOW(),
    'system',
    NOW()
)
ON CONFLICT (control_id) DO NOTHING;

INSERT INTO dlap_autocompliance.control_frameworks (control_id, framework_name) VALUES
    ('GDPR-RESIDENCY-001', 'GDPR')
ON CONFLICT (control_id, framework_name) DO NOTHING;

-- No target asset types: GDPR-RESIDENCY-001 applies to every asset type.
