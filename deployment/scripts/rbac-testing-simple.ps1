# Simple RBAC Testing Script for DLAP System (PowerShell)
# This script tests Role-Based Access Control with different users and endpoints

param(
    [string]$KeycloakUrl = "http://localhost:8180",
    [string]$ServiceUrl = "http://localhost:8087",
    [string]$Realm = "dlap",
    [string]$ClientId = "dlap-backend",
    [string]$ClientSecret = $env:DLAP_BACKEND_CLIENT_SECRET
)

# Function to print colored output
function Write-Status {
    param(
        [string]$Status,
        [string]$Message
    )
    
    switch ($Status) {
        "SUCCESS" { Write-Host "SUCCESS: $Message" -ForegroundColor Green }
        "FAILURE" { Write-Host "FAILURE: $Message" -ForegroundColor Red }
        "INFO" { Write-Host "INFO: $Message" -ForegroundColor Blue }
        "WARNING" { Write-Host "WARNING: $Message" -ForegroundColor Yellow }
    }
}

# Function to get access token
function Get-Token {
    param(
        [string]$Username,
        [string]$Password
    )
    
    Write-Status "INFO" "Getting token for user: $Username"
    
    $body = @{
        grant_type = "password"
        client_id = $ClientId
        client_secret = $ClientSecret
        username = $Username
        password = $Password
    }
    
    try {
        $response = Invoke-RestMethod -Uri "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" `
            -Method Post -Body $body -ContentType "application/x-www-form-urlencoded"
        
        if ($response.access_token) {
            Write-Status "SUCCESS" "Token obtained for user: $Username"
            return $response.access_token
        } else {
            Write-Status "FAILURE" "Failed to get token for user: $Username"
            return $null
        }
    }
    catch {
        Write-Status "FAILURE" "Failed to get token for user: $Username"
        Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

# Function to test endpoint
function Test-Endpoint {
    param(
        [string]$Method,
        [string]$Endpoint,
        [string]$Token,
        [string]$ExpectedStatus,
        [string]$Data,
        [string]$Description
    )
    
    Write-Status "INFO" "Testing: $Description"
    Write-Status "INFO" "Endpoint: $Method $Endpoint"
    Write-Status "INFO" "Expected Status: $ExpectedStatus"
    
    $headers = @{
        "Content-Type" = "application/json"
    }
    
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }
    
    try {
        $uri = "$ServiceUrl$Endpoint"
        
        if ($Data) {
            $response = Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers -Body $Data
        } else {
            $response = Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers
        }
        
        Write-Status "SUCCESS" "Expected status $ExpectedStatus received"
        if ($response) {
            $response | ConvertTo-Json -Depth 10
        }
    }
    catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -eq $ExpectedStatus) {
            Write-Status "SUCCESS" "Expected status $ExpectedStatus received"
        } else {
            Write-Status "FAILURE" "Expected status $ExpectedStatus, got $statusCode"
        }
    }
    
    Write-Host "---" -ForegroundColor Gray
}

# Function to test user permissions
function Test-UserPermissions {
    param(
        [string]$Username,
        [string]$Password,
        [string]$Role
    )
    
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Status "INFO" "Testing permissions for user: $Username (Role: $Role)"
    Write-Host "==========================================" -ForegroundColor Cyan
    
    # Get token
    $token = Get-Token -Username $Username -Password $Password
    if (-not $token) {
        return
    }
    
    # Test deletion configuration endpoints
    Write-Host "Testing Deletion Configuration Endpoints:" -ForegroundColor Yellow
    
    # GET /api/v1/deletion/config
    switch ($Role) {
        "ROLE_ADMIN" { 
            Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/config" -Token $token -ExpectedStatus "200" -Description "GET deletion config as $Role (should succeed)"
        }
        "ROLE_CDO" { 
            Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/config" -Token $token -ExpectedStatus "200" -Description "GET deletion config as $Role (should succeed)"
        }
        default { 
            Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/config" -Token $token -ExpectedStatus "403" -Description "GET deletion config as $Role (should fail)"
        }
    }
    
    # PUT /api/v1/deletion/config
    $configData = @{
        enabled = $true
        softDeleteByDefault = $true
        defaultSoftDeleteRetentionDays = 30
        requireApprovalForHardDelete = $true
        requireApprovalForSoftDelete = $false
        notificationEmailsOnError = @()
    } | ConvertTo-Json
    
    switch ($Role) {
        "ROLE_ADMIN" { 
            Test-Endpoint -Method "PUT" -Endpoint "/api/v1/deletion/config" -Token $token -ExpectedStatus "200" -Data $configData -Description "PUT deletion config as $Role (should succeed)"
        }
        default { 
            Test-Endpoint -Method "PUT" -Endpoint "/api/v1/deletion/config" -Token $token -ExpectedStatus "403" -Data $configData -Description "PUT deletion config as $Role (should fail)"
        }
    }
    
    # Test deletion task endpoints
    Write-Host "Testing Deletion Task Endpoints:" -ForegroundColor Yellow
    
    # POST /api/v1/deletion/tasks
    $taskData = @{
        taskName = "Test Deletion Task - $Username"
        scope = @{
            assetIds = @("asset-001", "asset-002")
        }
        triggeredBy = "$Username@dalab.com"
    } | ConvertTo-Json
    
    switch ($Role) {
        "ROLE_ADMIN" { 
            Test-Endpoint -Method "POST" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "202" -Data $taskData -Description "POST deletion task as $Role (should succeed)"
        }
        "ROLE_CDO" { 
            Test-Endpoint -Method "POST" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "202" -Data $taskData -Description "POST deletion task as $Role (should succeed)"
        }
        "ROLE_DATA_ENGINEER" { 
            Test-Endpoint -Method "POST" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "202" -Data $taskData -Description "POST deletion task as $Role (should succeed)"
        }
        default { 
            Test-Endpoint -Method "POST" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "403" -Data $taskData -Description "POST deletion task as $Role (should fail)"
        }
    }
    
    # GET /api/v1/deletion/tasks
    switch ($Role) {
        "ROLE_ADMIN" { 
            Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "200" -Description "GET deletion tasks as $Role (should succeed)"
        }
        "ROLE_CDO" { 
            Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "200" -Description "GET deletion tasks as $Role (should succeed)"
        }
        "ROLE_DATA_ENGINEER" { 
            Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "200" -Description "GET deletion tasks as $Role (should succeed)"
        }
        default { 
            Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/tasks" -Token $token -ExpectedStatus "403" -Description "GET deletion tasks as $Role (should fail)"
        }
    }
    
    Write-Host ""
}

# Function to test without authentication
function Test-NoAuth {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Status "INFO" "Testing endpoints without authentication"
    Write-Host "==========================================" -ForegroundColor Cyan
    
    Test-Endpoint -Method "GET" -Endpoint "/api/v1/deletion/config" -ExpectedStatus "401" -Description "GET deletion config without auth (should fail)"
    Test-Endpoint -Method "POST" -Endpoint "/api/v1/deletion/tasks" -ExpectedStatus "401" -Data '{"taskName":"test"}' -Description "POST deletion task without auth (should fail)"
    
    Write-Host ""
}

# Function to check prerequisites
function Test-Prerequisites {
    Write-Status "INFO" "Checking prerequisites..."
    
    # Check if Keycloak is running
    try {
        $null = Invoke-RestMethod -Uri "$KeycloakUrl/realms/$Realm/.well-known/openid_configuration" -Method Get
        Write-Status "SUCCESS" "Keycloak is accessible"
    }
    catch {
        Write-Status "FAILURE" "Keycloak is not accessible at $KeycloakUrl"
        return $false
    }
    
    # Check if service is running
    try {
        $null = Invoke-RestMethod -Uri "$ServiceUrl/actuator/health" -Method Get
        Write-Status "SUCCESS" "Service is accessible"
    }
    catch {
        Write-Status "FAILURE" "Service is not accessible at $ServiceUrl"
        return $false
    }
    
    Write-Host ""
    return $true
}

# Main execution
function Main {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Status "INFO" "DLAP RBAC Testing Script (PowerShell)"
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "Keycloak URL: $KeycloakUrl"
    Write-Host "Service URL: $ServiceUrl"
    Write-Host "Realm: $Realm"
    Write-Host ""
    
    # Check prerequisites
    if (-not (Test-Prerequisites)) {
        exit 1
    }
    
    # Test without authentication
    Test-NoAuth
    
    # Test with different users
    $adminPassword = $(if ($env:KEYCLOAK_TEST_ADMIN_PASSWORD) { $env:KEYCLOAK_TEST_ADMIN_PASSWORD } else { "change-me-dev-only" })
    $userPassword = $(if ($env:KEYCLOAK_TEST_USER_PASSWORD) { $env:KEYCLOAK_TEST_USER_PASSWORD } else { "change-me-dev-only" })

    Test-UserPermissions -Username "admin" -Password $adminPassword -Role "ROLE_ADMIN"
    Test-UserPermissions -Username "cdo" -Password $userPassword -Role "ROLE_CDO"
    Test-UserPermissions -Username "director" -Password $userPassword -Role "ROLE_DIRECTOR"
    Test-UserPermissions -Username "dataengineer" -Password $userPassword -Role "ROLE_DATA_ENGINEER"
    Test-UserPermissions -Username "datascientist" -Password $userPassword -Role "ROLE_DATA_SCIENTIST"
    
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Status "SUCCESS" "RBAC testing completed!"
    Write-Host "==========================================" -ForegroundColor Cyan
}

# Run main function
Main 