#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.


# RBAC Testing Script for DLAP System
# This script tests Role-Based Access Control with different users and endpoints

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
KEYCLOAK_URL="http://localhost:8180"
SERVICE_URL="http://localhost:8087"
REALM="dlap"
CLIENT_ID="dlap-backend"
CLIENT_SECRET="${DLAP_BACKEND_CLIENT_SECRET:-change-me-dev-only}"

# Function to print colored output
print_status() {
    local status=$1
    local message=$2
    case $status in
        "SUCCESS")
            echo -e "${GREEN}✅ $message${NC}"
            ;;
        "FAILURE")
            echo -e "${RED}❌ $message${NC}"
            ;;
        "INFO")
            echo -e "${BLUE}ℹ️  $message${NC}"
            ;;
        "WARNING")
            echo -e "${YELLOW}⚠️  $message${NC}"
            ;;
    esac
}

# Function to get access token
get_token() {
    local username=$1
    local password=$2
    
    print_status "INFO" "Getting token for user: $username"
    
    local response=$(curl -s -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password" \
        -d "client_id=$CLIENT_ID" \
        -d "client_secret=$CLIENT_SECRET" \
        -d "username=$username" \
        -d "password=$password")
    
    local token=$(echo "$response" | jq -r '.access_token')
    
    if [ "$token" = "null" ] || [ -z "$token" ]; then
        print_status "FAILURE" "Failed to get token for user: $username"
        echo "Response: $response"
        return 1
    fi
    
    print_status "SUCCESS" "Token obtained for user: $username"
    echo "$token"
}

# Function to test endpoint with expected status
test_endpoint() {
    local method=$1
    local endpoint=$2
    local token=$3
    local expected_status=$4
    local data=$5
    local description=$6
    
    print_status "INFO" "Testing: $description"
    print_status "INFO" "Endpoint: $method $endpoint"
    print_status "INFO" "Expected Status: $expected_status"
    
    local curl_cmd="curl -s -w '%{http_code}' -X $method '$SERVICE_URL$endpoint'"
    
    if [ -n "$token" ]; then
        curl_cmd="$curl_cmd -H 'Authorization: Bearer $token'"
    fi
    
    if [ -n "$data" ]; then
        curl_cmd="$curl_cmd -H 'Content-Type: application/json' -d '$data'"
    fi
    
    local response=$(eval "$curl_cmd")
    local status_code=$(echo "$response" | tail -c 4)
    local body=$(echo "$response" | head -c -4)
    
    if [ "$status_code" = "$expected_status" ]; then
        print_status "SUCCESS" "Expected status $expected_status received"
        if [ -n "$body" ]; then
            echo "Response: $body" | jq '.' 2>/dev/null || echo "Response: $body"
        fi
    else
        print_status "FAILURE" "Expected status $expected_status, got $status_code"
        if [ -n "$body" ]; then
            echo "Response: $body" | jq '.' 2>/dev/null || echo "Response: $body"
        fi
    fi
    
    echo "---"
}

# Function to test user permissions
test_user_permissions() {
    local username=$1
    local password=$2
    local role=$3
    
    echo "=========================================="
    print_status "INFO" "Testing permissions for user: $username (Role: $role)"
    echo "=========================================="
    
    # Get token
    local token=$(get_token "$username" "$password")
    if [ $? -ne 0 ]; then
        return 1
    fi
    
    # Test deletion configuration endpoints
    echo "Testing Deletion Configuration Endpoints:"
    
    # GET /api/v1/deletion/config
    case $role in
        "ROLE_ADMIN"|"ROLE_CDO")
            test_endpoint "GET" "/api/v1/deletion/config" "$token" "200" "" "GET deletion config as $role (should succeed)"
            ;;
        *)
            test_endpoint "GET" "/api/v1/deletion/config" "$token" "403" "" "GET deletion config as $role (should fail)"
            ;;
    esac
    
    # PUT /api/v1/deletion/config
    local config_data='{
        "enabled": true,
        "softDeleteByDefault": true,
        "defaultSoftDeleteRetentionDays": 30,
        "requireApprovalForHardDelete": true,
        "requireApprovalForSoftDelete": false,
        "notificationEmailsOnError": []
    }'
    
    case $role in
        "ROLE_ADMIN")
            test_endpoint "PUT" "/api/v1/deletion/config" "$token" "200" "$config_data" "PUT deletion config as $role (should succeed)"
            ;;
        *)
            test_endpoint "PUT" "/api/v1/deletion/config" "$token" "403" "$config_data" "PUT deletion config as $role (should fail)"
            ;;
    esac
    
    # Test deletion task endpoints
    echo "Testing Deletion Task Endpoints:"
    
    # POST /api/v1/deletion/tasks
    local task_data='{
        "taskName": "Test Deletion Task - '$username'",
        "scope": {
            "assetIds": ["asset-001", "asset-002"]
        },
        "triggeredBy": "'$username'@dalab.com"
    }'
    
    case $role in
        "ROLE_ADMIN"|"ROLE_CDO"|"ROLE_DATA_ENGINEER")
            test_endpoint "POST" "/api/v1/deletion/tasks" "$token" "202" "$task_data" "POST deletion task as $role (should succeed)"
            ;;
        *)
            test_endpoint "POST" "/api/v1/deletion/tasks" "$token" "403" "$task_data" "POST deletion task as $role (should fail)"
            ;;
    esac
    
    # GET /api/v1/deletion/tasks
    case $role in
        "ROLE_ADMIN"|"ROLE_CDO"|"ROLE_DATA_ENGINEER")
            test_endpoint "GET" "/api/v1/deletion/tasks" "$token" "200" "" "GET deletion tasks as $role (should succeed)"
            ;;
        *)
            test_endpoint "GET" "/api/v1/deletion/tasks" "$token" "403" "" "GET deletion tasks as $role (should fail)"
            ;;
    esac
    
    # POST /api/v1/deletion/tasks/{taskId}/approve (using a dummy task ID)
    local approval_data='{
        "approver": "'$username'@dalab.com",
        "comments": "Approved by '$username'"
    }'
    
    case $role in
        "ROLE_ADMIN"|"ROLE_CDO")
            test_endpoint "POST" "/api/v1/deletion/tasks/dummy-task-id/approve" "$token" "404" "$approval_data" "POST task approval as $role (should fail with 404, not 403)"
            ;;
        *)
            test_endpoint "POST" "/api/v1/deletion/tasks/dummy-task-id/approve" "$token" "403" "$approval_data" "POST task approval as $role (should fail)"
            ;;
    esac
    
    echo ""
}

# Function to test without authentication
test_no_auth() {
    echo "=========================================="
    print_status "INFO" "Testing endpoints without authentication"
    echo "=========================================="
    
    test_endpoint "GET" "/api/v1/deletion/config" "" "401" "" "GET deletion config without auth (should fail)"
    test_endpoint "POST" "/api/v1/deletion/tasks" "" "401" '{"taskName":"test"}' "POST deletion task without auth (should fail)"
    test_endpoint "GET" "/api/v1/deletion/tasks" "" "401" "" "GET deletion tasks without auth (should fail)"
    
    echo ""
}

# Function to test with invalid token
test_invalid_token() {
    echo "=========================================="
    print_status "INFO" "Testing endpoints with invalid token"
    echo "=========================================="
    
    test_endpoint "GET" "/api/v1/deletion/config" "invalid-token" "401" "" "GET deletion config with invalid token (should fail)"
    test_endpoint "POST" "/api/v1/deletion/tasks" "invalid-token" "401" '{"taskName":"test"}' "POST deletion task with invalid token (should fail)"
    
    echo ""
}

# Function to check prerequisites
check_prerequisites() {
    print_status "INFO" "Checking prerequisites..."
    
    # Check if Keycloak is running
    if ! curl -s "$KEYCLOAK_URL/realms/$REALM/.well-known/openid_configuration" > /dev/null; then
        print_status "FAILURE" "Keycloak is not accessible at $KEYCLOAK_URL"
        return 1
    fi
    print_status "SUCCESS" "Keycloak is accessible"
    
    # Check if service is running
    if ! curl -s "$SERVICE_URL/actuator/health" > /dev/null; then
        print_status "FAILURE" "Service is not accessible at $SERVICE_URL"
        return 1
    fi
    print_status "SUCCESS" "Service is accessible"
    
    # Check if jq is installed
    if ! command -v jq &> /dev/null; then
        print_status "FAILURE" "jq is not installed. Please install jq to run this script."
        return 1
    fi
    print_status "SUCCESS" "jq is available"
    
    echo ""
}

# Main execution
main() {
    echo "=========================================="
    print_status "INFO" "DLAP RBAC Testing Script"
    echo "=========================================="
    echo "Keycloak URL: $KEYCLOAK_URL"
    echo "Service URL: $SERVICE_URL"
    echo "Realm: $REALM"
    echo ""
    
    # Check prerequisites
    check_prerequisites || exit 1
    
    # Test without authentication
    test_no_auth
    
    # Test with invalid token
    test_invalid_token
    
    # Test with different users
    test_user_permissions "${KEYCLOAK_TEST_ADMIN_USER:-admin}" "${KEYCLOAK_TEST_ADMIN_PASSWORD:-change-me-dev-only}" "ROLE_ADMIN"
    test_user_permissions "${KEYCLOAK_TEST_CDO_USER:-cdo}" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}" "ROLE_CDO"
    test_user_permissions "${KEYCLOAK_TEST_DIRECTOR_USER:-director}" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}" "ROLE_DIRECTOR"
    test_user_permissions "${KEYCLOAK_TEST_DATAENGINEER_USER:-dataengineer}" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}" "ROLE_DATA_ENGINEER"
    test_user_permissions "${KEYCLOAK_TEST_DATASCIENTIST_USER:-datascientist}" "${KEYCLOAK_TEST_USER_PASSWORD:-change-me-dev-only}" "ROLE_DATA_SCIENTIST"
    
    echo "=========================================="
    print_status "SUCCESS" "RBAC testing completed!"
    echo "=========================================="
}

# Run main function
main "$@" 