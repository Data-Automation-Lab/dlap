# RBAC Testing Guide for DALab System

## Overview
This guide demonstrates how to test Role-Based Access Control (RBAC) in the DALab system using the da-autodelete service as an example.

## Prerequisites
- Keycloak running on port 8180
- da-autodelete service running on port 8080
- Docker containers for PostgreSQL and Kafka running

## Available Roles and Users

### Roles Defined in Keycloak
- `ROLE_ADMIN` - Full administrative access
- `ROLE_CDO` - Chief Data Officer access  
- `ROLE_DIRECTOR` - Director level access
- `ROLE_DATA_ENGINEER` - Data engineering access
- `ROLE_DATA_SCIENTIST` - Data science access
- `ROLE_DATA_ANALYST` - Data analysis access
- `ROLE_POLICY_MANAGER` - Policy management access
- `ROLE_AUDITOR` - Audit and compliance access
- `ROLE_VIEWER` - Read-only access

### Pre-configured Users
| Username | Password | Role | Description |
|----------|----------|------|-------------|
| `admin` | `${KEYCLOAK_TEST_ADMIN_PASSWORD:-change-me-dev-only}` | `ROLE_ADMIN` | Full administrator |
| `cdo` | `${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}` | `ROLE_CDO` | Chief Data Officer |
| `director` | `${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}` | `ROLE_DIRECTOR` | Director |
| `dataengineer` | `${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}` | `ROLE_DATA_ENGINEER` | Data Engineer |
| `datascientist` | `${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}` | `ROLE_DATA_SCIENTIST` | Data Scientist |

## API Endpoints and Required Permissions

### Deletion Configuration Endpoints
- `GET /api/v1/deletion/config` - Requires: `ROLE_ADMIN`, `ROLE_DATA_STEWARD`
- `PUT /api/v1/deletion/config` - Requires: `ROLE_ADMIN` only

### Deletion Task Endpoints
- `POST /api/v1/deletion/tasks` - Requires: `ROLE_ADMIN`, `ROLE_CDO`, `ROLE_DATA_ENGINEER`
- `GET /api/v1/deletion/tasks/{taskId}` - Requires: `ROLE_ADMIN`, `ROLE_CDO`, `ROLE_VIEWER`, `ROLE_DATA_ENGINEER`
- `GET /api/v1/deletion/tasks` - Requires: `ROLE_ADMIN`, `ROLE_CDO`, `ROLE_VIEWER`, `ROLE_DATA_ENGINEER`
- `POST /api/v1/deletion/tasks/{taskId}/approve` - Requires: `ROLE_ADMIN`, `ROLE_CDO`
- `POST /api/v1/deletion/tasks/{taskId}/reject` - Requires: `ROLE_ADMIN`, `ROLE_CDO`

## Testing Scripts

### 1. Get Access Token Function
```bash
get_token() {
    local username=$1
    local password=$2
    
    curl -s -X POST "http://localhost:8180/realms/dalab/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password" \
        -d "client_id=dalab-backend" \
        -d "client_secret=${DALAB_BACKEND_CLIENT_SECRET}" \
        -d "username=$username" \
        -d "password=$password" | jq -r '.access_token'
}
```

### 2. Test Deletion Configuration Access

#### Test as Admin (Should Succeed)
```bash
echo "=== Testing Admin Access to Deletion Config ==="
TOKEN=$(get_token "admin" "${KEYCLOAK_TEST_ADMIN_PASSWORD:-change-me-dev-only}")
echo "Token: ${TOKEN:0:50}..."

# Test GET - Should succeed
echo "Testing GET /api/v1/deletion/config as ADMIN..."
curl -s -X GET "http://localhost:8080/api/v1/deletion/config" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" | jq '.'

# Test PUT - Should succeed
echo "Testing PUT /api/v1/deletion/config as ADMIN..."
curl -s -X PUT "http://localhost:8080/api/v1/deletion/config" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "enabled": true,
        "softDeleteByDefault": true,
        "defaultSoftDeleteRetentionDays": 30,
        "requireApprovalForHardDelete": true,
        "requireApprovalForSoftDelete": false,
        "notificationEmailsOnError": []
    }' | jq '.'
```

#### Test as CDO (Should Succeed for GET, Fail for PUT)
```bash
echo "=== Testing CDO Access to Deletion Config ==="
TOKEN=$(get_token "cdo" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}")
echo "Token: ${TOKEN:0:50}..."

# Test GET - Should succeed
echo "Testing GET /api/v1/deletion/config as CDO..."
curl -s -X GET "http://localhost:8080/api/v1/deletion/config" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" | jq '.'

# Test PUT - Should fail (403 Forbidden)
echo "Testing PUT /api/v1/deletion/config as CDO (should fail)..."
curl -s -X PUT "http://localhost:8080/api/v1/deletion/config" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "enabled": true,
        "softDeleteByDefault": true,
        "defaultSoftDeleteRetentionDays": 30,
        "requireApprovalForHardDelete": true,
        "requireApprovalForSoftDelete": false,
        "notificationEmailsOnError": []
    }' | jq '.'
```

#### Test as Data Engineer (Should Fail for both)
```bash
echo "=== Testing Data Engineer Access to Deletion Config ==="
TOKEN=$(get_token "dataengineer" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}")
echo "Token: ${TOKEN:0:50}..."

# Test GET - Should fail (403 Forbidden)
echo "Testing GET /api/v1/deletion/config as DATA_ENGINEER (should fail)..."
curl -s -X GET "http://localhost:8080/api/v1/deletion/config" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" | jq '.'
```

### 3. Test Deletion Task Endpoints

#### Test Task Submission (Admin, CDO, Data Engineer should succeed)
```bash
echo "=== Testing Deletion Task Submission ==="

# Test as Admin
echo "Testing POST /api/v1/deletion/tasks as ADMIN..."
TOKEN=$(get_token "admin" "${KEYCLOAK_TEST_ADMIN_PASSWORD:-change-me-dev-only}")
curl -s -X POST "http://localhost:8080/api/v1/deletion/tasks" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "taskName": "Test Deletion Task - Admin",
        "scope": {
            "assetIds": ["asset-001", "asset-002"]
        },
        "triggeredBy": "admin@dalab.com"
    }' | jq '.'

# Test as CDO
echo "Testing POST /api/v1/deletion/tasks as CDO..."
TOKEN=$(get_token "cdo" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}")
curl -s -X POST "http://localhost:8080/api/v1/deletion/tasks" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "taskName": "Test Deletion Task - CDO",
        "scope": {
            "assetIds": ["asset-003", "asset-004"]
        },
        "triggeredBy": "cdo@dalab.com"
    }' | jq '.'

# Test as Data Engineer
echo "Testing POST /api/v1/deletion/tasks as DATA_ENGINEER..."
TOKEN=$(get_token "dataengineer" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}")
curl -s -X POST "http://localhost:8080/api/v1/deletion/tasks" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "taskName": "Test Deletion Task - Data Engineer",
        "scope": {
            "assetIds": ["asset-005", "asset-006"]
        },
        "triggeredBy": "dataengineer@dalab.com"
    }' | jq '.'
```

#### Test Task Approval (Only Admin and CDO should succeed)
```bash
echo "=== Testing Deletion Task Approval ==="

# First, get a task ID from the previous submissions
TASK_ID="your-task-id-here"  # Replace with actual task ID

# Test as Admin
echo "Testing POST /api/v1/deletion/tasks/{taskId}/approve as ADMIN..."
TOKEN=$(get_token "admin" "${KEYCLOAK_TEST_ADMIN_PASSWORD:-change-me-dev-only}")
curl -s -X POST "http://localhost:8080/api/v1/deletion/tasks/$TASK_ID/approve" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "approver": "admin@dalab.com",
        "comments": "Approved by admin"
    }' | jq '.'

# Test as CDO
echo "Testing POST /api/v1/deletion/tasks/{taskId}/approve as CDO..."
TOKEN=$(get_token "cdo" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}")
curl -s -X POST "http://localhost:8080/api/v1/deletion/tasks/$TASK_ID/approve" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "approver": "cdo@dalab.com",
        "comments": "Approved by CDO"
    }' | jq '.'

# Test as Data Engineer (should fail)
echo "Testing POST /api/v1/deletion/tasks/{taskId}/approve as DATA_ENGINEER (should fail)..."
TOKEN=$(get_token "dataengineer" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}")
curl -s -X POST "http://localhost:8080/api/v1/deletion/tasks/$TASK_ID/approve" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "approver": "dataengineer@dalab.com",
        "comments": "Approved by data engineer"
    }' | jq '.'
```

### 4. Test Without Authentication (Should Fail)
```bash
echo "=== Testing Without Authentication ==="
echo "Testing GET /api/v1/deletion/config without token (should fail)..."
curl -s -X GET "http://localhost:8080/api/v1/deletion/config" \
    -H "Content-Type: application/json" | jq '.'
```

### 5. Test with Invalid Token (Should Fail)
```bash
echo "=== Testing With Invalid Token ==="
echo "Testing GET /api/v1/deletion/config with invalid token (should fail)..."
curl -s -X GET "http://localhost:8080/api/v1/deletion/config" \
    -H "Authorization: Bearer invalid-token" \
    -H "Content-Type: application/json" | jq '.'
```

## Expected Results Summary

| Endpoint | Admin | CDO | Director | Data Engineer | Data Scientist | No Auth |
|----------|-------|-----|----------|---------------|----------------|---------|
| GET /config | ✅ 200 | ✅ 200 | ❌ 403 | ❌ 403 | ❌ 403 | ❌ 401 |
| PUT /config | ✅ 200 | ❌ 403 | ❌ 403 | ❌ 403 | ❌ 403 | ❌ 401 |
| POST /tasks | ✅ 202 | ✅ 202 | ❌ 403 | ✅ 202 | ❌ 403 | ❌ 401 |
| GET /tasks | ✅ 200 | ✅ 200 | ❌ 403 | ✅ 200 | ❌ 403 | ❌ 401 |
| POST /tasks/{id}/approve | ✅ 200 | ✅ 200 | ❌ 403 | ❌ 403 | ❌ 403 | ❌ 401 |

## Troubleshooting

### Common Issues

1. **401 Unauthorized**: Check if Keycloak is running and accessible
2. **403 Forbidden**: User doesn't have the required role
3. **500 Internal Server Error**: Check service logs for details

### Debug Commands

```bash
# Check Keycloak status
curl -s "http://localhost:8180/realms/dalab/.well-known/openid_configuration" | jq '.'

# Check service health
curl -s "http://localhost:8080/actuator/health" | jq '.'

# Decode JWT token to see roles
echo "your-token-here" | cut -d'.' -f2 | base64 -d | jq '.'
```

## Creating New Users for Testing

To create additional test users with specific roles:

1. Access Keycloak Admin Console: http://localhost:8180
2. Login with admin credentials
3. Select "dalab" realm
4. Go to Users → Add User
5. Assign appropriate roles from the "Realm Roles" section

## Security Best Practices

1. **Regular Role Audits**: Periodically review user roles and permissions
2. **Principle of Least Privilege**: Assign minimum required permissions
3. **Token Expiration**: Use short-lived access tokens
4. **Audit Logging**: Monitor access patterns and failed attempts
5. **Regular Testing**: Run RBAC tests after any permission changes 