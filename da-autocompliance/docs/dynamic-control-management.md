# Dynamic Compliance Management

## Overview

The compliance system has been updated to support **100% dynamic data management** through the API instead of relying on any hardcoded data. This applies to both **compliance controls** and **compliance report definitions**. All data must come from the database only.

## What Was Removed

### 1. Hardcoded Controls
- **Before:** Controls like "CIS-GCP-1.1" and "PCI-REQ-8.2.3" were hardcoded in `initDefaultControls()`
- **After:** No controls are created automatically - all must be added via API

### 2. Hardcoded Report Definitions
- **Before:** Report definitions like "cis-gcp-foundations-v1.3" and "pci-dss-aws-v3.2.1" were hardcoded in `initDefaultReportDefinitions()`
- **After:** No report definitions are created automatically - all must be added via API

## API Endpoints

### Compliance Controls

#### 1. List Available Controls
**GET** `/api/v1/compliance/controls`

Returns all enabled compliance controls from the database.

**Response:**
```json
[
  {
    "controlId": "CIS-GCP-1.1",
    "name": "Ensure corporate login credentials are used",
    "description": "Use managed corporate accounts instead of personal gmail.com accounts",
    "severity": "MEDIUM",
    "applicableFrameworks": ["CIS GCP Foundations Benchmark v1.3"],
    "targetAssetTypes": ["GCP_IAM_POLICY"],
    "remediationSteps": "Migrate any gmail.com accounts with IAM roles to managed corporate accounts.",
    "evaluationParametersDefinition": {
      "gcpProjectId": "string"
    },
    "detailsLink": "https://www.cisecurity.org/benchmark/google_cloud_computing_platform/",
    "enabled": true
  }
]
```

#### 2. Create New Control
**POST** `/api/v1/compliance/controls`

Creates a new compliance control in the database.

#### 3. Update Control
**PUT** `/api/v1/compliance/controls/{controlId}`

Updates an existing compliance control.

#### 4. Delete Control
**DELETE** `/api/v1/compliance/controls/{controlId}`

Deletes a compliance control from the database.

### Compliance Report Definitions

#### 1. List Available Report Definitions
**GET** `/api/v1/compliance/reports`

Returns all compliance report definitions from the database.

**Response:**
```json
[
  {
    "reportType": "cis-gcp-foundations-v1.3",
    "displayName": "CIS Google Cloud Platform Foundations Benchmark v1.3",
    "description": "Checks for compliance against the CIS GCP Foundations Benchmark v1.3.",
    "version": "1.3.0",
    "applicableComplianceFrameworks": ["CIS Benchmark"],
    "targetAssetTypes": ["GCP_PROJECT", "GCP_IAM_POLICY", "GCP_VPC_NETWORK"],
    "generationParametersDefinition": {
      "gcpProjectId": "string",
      "targetRegions": "list_string"
    },
    "detailsLink": "https://www.cisecurity.org/benchmark/google_cloud_computing_platform/"
  }
]
```

#### 2. Create New Report Definition
**POST** `/api/v1/compliance/reports`

Creates a new compliance report definition in the database.

#### 3. Update Report Definition
**PUT** `/api/v1/compliance/reports/{reportType}`

Updates an existing compliance report definition.

#### 4. Delete Report Definition
**DELETE** `/api/v1/compliance/reports/{reportType}`

Deletes a compliance report definition from the database.

## Initial Setup

Since no data is hardcoded, you need to create initial data through the API:

### 1. Create Initial Report Definitions
```bash
# Create CIS GCP Foundations report definition
curl -X POST http://localhost:8080/api/v1/compliance/reports \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "reportType": "cis-gcp-foundations-v1.3",
    "displayName": "CIS Google Cloud Platform Foundations Benchmark v1.3",
    "description": "Checks for compliance against the CIS GCP Foundations Benchmark v1.3.",
    "version": "1.3.0",
    "applicableComplianceFrameworks": ["CIS Benchmark"],
    "targetAssetTypes": ["GCP_PROJECT", "GCP_IAM_POLICY", "GCP_VPC_NETWORK"],
    "generationParametersDefinition": {
      "gcpProjectId": "string",
      "targetRegions": "list_string"
    },
    "detailsLink": "https://www.cisecurity.org/benchmark/google_cloud_computing_platform/"
  }'

# Create PCI DSS AWS report definition
curl -X POST http://localhost:8080/api/v1/compliance/reports \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "reportType": "pci-dss-aws-v3.2.1",
    "displayName": "PCI DSS v3.2.1 for AWS Infrastructure",
    "description": "Assesses AWS resources against applicable PCI DSS v3.2.1 requirements.",
    "version": "3.2.1",
    "applicableComplianceFrameworks": ["PCI DSS v3.2.1"],
    "targetAssetTypes": ["AWS_EC2_INSTANCE", "AWS_S3_BUCKET", "AWS_VPC"],
    "generationParametersDefinition": {
      "awsAccountId": "string",
      "awsRegion": "string"
    },
    "detailsLink": "https://aws.amazon.com/compliance/pci-dss/"
  }'
```

### 2. Create Initial Controls
```bash
# Create CIS GCP control
curl -X POST http://localhost:8080/api/v1/compliance/controls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "controlId": "CIS-GCP-1.1",
    "name": "Ensure that corporate login credentials are used instead of gmail.com accounts",
    "description": "GCP supports using Google Workspace or Cloud Identity to manage user identities. It is recommended to use managed corporate accounts instead of personal gmail.com accounts for managing cloud resources.",
    "severity": "MEDIUM",
    "applicableFrameworks": ["CIS GCP Foundations Benchmark v1.3"],
    "targetAssetTypes": ["GCP_IAM_POLICY"],
    "remediationSteps": "Migrate any gmail.com accounts with IAM roles to managed corporate accounts.",
    "evaluationParametersDefinition": {
      "gcpProjectId": "string"
    },
    "detailsLink": "https://www.cisecurity.org/benchmark/google_cloud_computing_platform/",
    "enabled": true
  }'

# Create PCI DSS control
curl -X POST http://localhost:8080/api/v1/compliance/controls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "controlId": "PCI-REQ-8.2.3",
    "name": "Use strong passwords (or passphrases)",
    "description": "Ensure passwords/passphrases meet minimum complexity requirements as defined by PCI DSS requirement 8.2.3.",
    "severity": "HIGH",
    "applicableFrameworks": ["PCI DSS v3.2.1"],
    "targetAssetTypes": ["AWS_IAM_USER", "Linux_Server"],
    "remediationSteps": "Configure password policies to enforce complexity. For existing users, enforce password change at next login.",
    "evaluationParametersDefinition": {
      "minPasswordLength": "integer",
      "requiresUppercase": "boolean"
    },
    "detailsLink": "https://www.pcisecuritystandards.org/",
    "enabled": true
  }'
```

## Verification

To verify that no hardcoded data exists:

1. **Check the database directly:**
   ```sql
   SELECT * FROM compliance_controls;
   SELECT * FROM compliance_report_definitions;
   ```

2. **Use the API endpoints:**
   ```bash
   GET /api/v1/compliance/controls
   GET /api/v1/compliance/reports
   ```

3. **Expected initial state:** Both endpoints should return empty arrays `[]` until you add data through the API.

## Benefits

1. **100% Database-Driven:** No hardcoded data anywhere in the system
2. **Complete Control:** All data can be managed through API endpoints
3. **Flexibility:** Easy to add, modify, or remove compliance rules without code changes
4. **Audit Trail:** All changes are tracked in the database
5. **Scalability:** Supports unlimited compliance rules and report types
6. **Maintainability:** No need to redeploy for data changes

## Security

- **Create/Update/Delete:** Requires `ADMIN` or `DATA_STEWARD` role
- **List/Read:** Available to all authenticated users with appropriate roles
- **Validation:** All input is validated before saving to database

## Best Practices

1. **Initial Setup:** Create essential report definitions and controls through API during system setup
2. **Data Management:** Use the API endpoints for all data management operations
3. **Validation:** Always validate data before saving
4. **Documentation:** Keep documentation of compliance rules up to date
5. **Testing:** Test new controls and report definitions thoroughly before enabling them
6. **Monitoring:** Monitor compliance evaluation results and adjust as needed

## Migration from Hardcoded Data

If you were previously using hardcoded data:

1. **Backup existing data** (if any exists in database)
2. **Clear the database** of any hardcoded data
3. **Use the API endpoints** to recreate the data dynamically
4. **Verify** that all endpoints return data from the database only

This ensures a completely dynamic and maintainable compliance management system. 